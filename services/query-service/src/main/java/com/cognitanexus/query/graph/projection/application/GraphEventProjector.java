package com.cognitanexus.query.graph.projection.application;

import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import com.cognitanexus.query.graph.projection.application.GraphProjectionSource.AppliedEvent;
import com.cognitanexus.query.platform.persistence.JsonCodec;
import com.cognitanexus.query.projection.application.QueryEventTypes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把已提交到 {@code query_db} 的事件增量投影到图（ADR-005 §5.1 的 Outbox/Inbox 入口）。
 *
 * <p>事件来源是 {@code qry_inbox_event} 中 {@code processing_state='APPLIED'} 的行，而不是 core 的
 * Outbox 直投。理由：
 *
 * <ul>
 *   <li>图只应表示「已通过公开资格与版本屏障」的结果，而 PG 投影的 APPLIED 正是这个判定；
 *   <li>Inbox 已有唯一键 {@code (consumer_id, event_id)} 与连续水位，重复投递不会产生第二次
 *       业务效果；图只需要在它之后补一段「从修订 R 到 S 的成果重写」；
 *   <li>图侧失败不影响 PG 投影发布：图是可丢的派生读投影，不能因为图库慢就把公开检索一起卡住。
 * </ul>
 *
 * <p>事件到成果的映射（只影响图的部分）：
 *
 * <table>
 *   <tr><th>事件</th><th>图动作</th></tr>
 *   <tr><td>{@code CatalogChangeCommitted} UPSERT</td><td>重同步该成果（含不合格时退场）</td></tr>
 *   <tr><td>{@code PublicVisibilityTightened} / {@code Restored}</td><td>逐条重同步列出的成果：
 *       收紧后 PG 中不再是 PUBLIC，同步时自动走 {@code retireWork}</td></tr>
 *   <tr><td>{@code AchievementMerged}</td><td>重同步幸存者；被合并者已从 PG 投影删除，留待对账退场</td></tr>
 *   <tr><td>{@code ScholarMerged}</td><td>重同步幸存者与合并者名下的全部成果</td></tr>
 *   <tr><td>门户授权 / 认领结论</td><td>无图效果，只推进水位</td></tr>
 *   <tr><td>其它类型</td><td>无图效果并记录（PG 侧已 APPLIED，不在此重判）</td></tr>
 * </table>
 *
 * <p>水位只在水位之前的每条事件都已成功应用后前进：失败即停，下一次从同一修订重来。这样
 * 「图落后多少」是一个可回答的数字，而不是「大概追上了」。
 */
public class GraphEventProjector {

  private static final Logger log = LoggerFactory.getLogger(GraphEventProjector.class);

  /** payload 中可能出现的成果标识字段（与 core 事件契约一致，见 EventPayloadReader）。 */
  private static final List<String> WORK_ID_FIELDS =
      List.of("achievementId", "survivorId", "mergedId", "targetAchievementId");

  private static final List<String> WORK_ID_ARRAY_FIELDS =
      List.of("tightenedAchievementIds", "restoredAchievementIds", "tightened", "restored");

  private final GraphProjectionSource source;
  private final GraphProjectionSynchronizer synchronizer;
  private final GraphProjectionStore store;
  private final JsonCodec json;
  private final String consumerId;

  public GraphEventProjector(
      GraphProjectionSource source,
      GraphProjectionSynchronizer synchronizer,
      GraphProjectionStore store,
      JsonCodec json,
      String consumerId) {
    this.source = source;
    this.synchronizer = synchronizer;
    this.store = store;
    this.json =
        json == null ? new JsonCodec(new com.fasterxml.jackson.databind.ObjectMapper()) : json;
    this.consumerId = consumerId == null || consumerId.isBlank() ? "query" : consumerId;
  }

  /**
   * 消费水位之后的事件。
   *
   * @param scope 投影作用域
   * @param maxEvents 单次最多消费的事件数（防止一次追赶占用连接过久）
   */
  public GraphEventProjectionResult applyPending(String scope, int maxEvents) {
    String effectiveScope = requireScope(scope);
    GraphProjectionMeta meta =
        store.readMeta(effectiveScope).orElse(GraphProjectionMeta.empty(effectiveScope));
    long fromRevision = meta.projectedVersion();

    Optional<GraphProjectionSource.Stage> stage = source.readStage(consumerId);
    if (stage.isEmpty()) {
      return new GraphEventProjectionResult(
          0, 0, 0, 0, 0, 0, fromRevision, fromRevision, null, List.of("PG 尚未初始化水位，暂不消费事件"));
    }
    String generation = stage.get().generation();
    if (!meta.generation().equals(generation)) {
      // 水位的 generation 与 PG 活动代不同：图里是旧代成果。此时继续写新代事件会让图变成
      // 「旧代 + 新代补丁」的混合视图，且水位看起来在前进。必须整库重建（ADR-005 §5.3）。
      return new GraphEventProjectionResult(
          0,
          0,
          0,
          0,
          0,
          0,
          fromRevision,
          fromRevision,
          null,
          List.of(
              "图水位 generation="
                  + meta.generation()
                  + " 与 PG 活动代 "
                  + generation
                  + " 不一致，拒绝增量写入；需先执行全量重建"));
    }

    List<AppliedEvent> events =
        source.listAppliedEventsAfter(consumerId, fromRevision, Math.max(maxEvents, 1));
    if (events.isEmpty()) {
      return new GraphEventProjectionResult(
          0, 0, 0, 0, 0, 0, fromRevision, fromRevision, null, List.of());
    }

    List<String> notes = new ArrayList<>();
    long revision = fromRevision;
    int ignored = 0;
    long applied = 0;
    long retired = 0;
    long stale = 0;
    long failed = 0;
    String stoppedAt = null;

    for (AppliedEvent event : events) {
      Set<String> workIds = affectedWorkIds(generation, event);
      if (workIds.isEmpty()) {
        ignored++;
        revision = event.catalogRevision();
        continue;
      }
      boolean eventFailed = false;
      for (String workId : workIds) {
        Optional<GraphSyncOutcome> outcome = synchronizer.syncWork(generation, workId);
        if (outcome.isEmpty()) {
          // PG 里已无该成果（被合并删除等）：图侧墓碑由对账的定向修复退场，不在此凭猜测删数据。
          notes.add(event.eventId() + ":" + workId + " 已不在 PG 投影中，留待对账处理");
          continue;
        }
        GraphSyncOutcome value = outcome.get();
        switch (value.action()) {
          case APPLIED -> applied++;
          case RETIRED -> retired++;
          case STALE_IGNORED -> stale++;
          default -> {
            failed++;
            eventFailed = true;
            notes.add("事件 " + event.eventId() + " 的成果 " + workId + " 写入失败：" + value.describe());
          }
        }
      }
      if (eventFailed) {
        stoppedAt = event.eventId();
        break;
      }
      revision = event.catalogRevision();
    }

    if (revision > fromRevision) {
      GraphProjectionMeta advanced =
          new GraphProjectionMeta(
              effectiveScope,
              revision,
              Math.max(meta.catalogRevision(), revision),
              generation,
              Instant.now());
      long actual = store.advanceMeta(advanced);
      if (actual < revision) {
        notes.add("图水位未推进到目标修订：" + actual + " < " + revision + "（已有更高水位）");
      }
      revision = actual;
    }

    GraphEventProjectionResult result =
        new GraphEventProjectionResult(
            events.size(),
            ignored,
            applied,
            retired,
            stale,
            failed,
            fromRevision,
            revision,
            stoppedAt,
            notes);
    log.info(
        "图事件增量消费完成 consumer={} {} failed={} stoppedAt={}",
        consumerId,
        result.summary(),
        failed,
        stoppedAt);
    return result;
  }

  /** 事件影响的成果标识集合（去重、有序，便于日志与测试复现）。 */
  Set<String> affectedWorkIds(String generation, AppliedEvent event) {
    Map<String, Object> payload = event.payload();
    if (payload == null) {
      payload = Map.of();
    }
    Set<String> workIds = new LinkedHashSet<>();
    switch (event.eventType()) {
      case QueryEventTypes.CATALOG_CHANGE_COMMITTED -> {
        String changeKind = stringOf(payload.get("changeKind"));
        if (changeKind != null && !QueryEventTypes.CHANGE_UPSERT.equals(changeKind)) {
          // 删除类变更在 PG 投影侧是「移除成果」，图侧对应退场；没有成果标识就不做任何事。
          return workIds;
        }
        addIfPresent(workIds, stringOf(payload.get("achievementId")));
      }
      case QueryEventTypes.PUBLIC_VISIBILITY_TIGHTENED,
          QueryEventTypes.PUBLIC_VISIBILITY_RESTORED -> {
        for (String field : WORK_ID_ARRAY_FIELDS) {
          for (String value : json.readStringList(payload.get(field))) {
            addIfPresent(workIds, value);
          }
        }
        for (String field : WORK_ID_FIELDS) {
          addIfPresent(workIds, stringOf(payload.get(field)));
        }
      }
      case QueryEventTypes.ACHIEVEMENT_MERGED -> {
        addIfPresent(workIds, stringOf(payload.get("survivorId")));
        addIfPresent(workIds, stringOf(payload.get("mergedId")));
      }
      case QueryEventTypes.SCHOLAR_MERGED -> {
        // 合并本身改的是「学者 → 成果」的归属，必须把两边名下的成果都重写一遍，
        // 否则图上会保留旧学者的参与边，而 PG 已经把它改挂到幸存者。
        for (String field : List.of("survivorId", "mergedId")) {
          String scholarId = stringOf(payload.get(field));
          if (scholarId != null) {
            workIds.addAll(source.achievementIdsOfScholar(generation, scholarId));
          }
        }
      }
      default -> {
        // 门户授权、认领结论与未知类型不改变公开成果集合，图无动作。
        return workIds;
      }
    }
    return workIds;
  }

  private static void addIfPresent(Set<String> workIds, String value) {
    if (value != null && !value.isBlank()) {
      workIds.add(value.trim());
    }
  }

  private static String stringOf(Object value) {
    if (value instanceof String text && !text.isBlank()) {
      return text.trim();
    }
    return null;
  }

  private String requireScope(String scope) {
    return scope == null || scope.isBlank() ? "query-public" : scope.trim();
  }
}
