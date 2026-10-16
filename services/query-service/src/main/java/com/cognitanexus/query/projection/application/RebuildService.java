package com.cognitanexus.query.projection.application;

import com.cognitanexus.query.projection.application.ProjectionRepositories.InboxStore;
import com.cognitanexus.query.projection.application.ProjectionRepositories.MaintenanceStore;
import com.cognitanexus.query.projection.domain.ConsumerProgress;
import com.cognitanexus.query.projection.domain.InboxEventRecord;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 全量重建与 generation 切换。
 *
 * <p>依据：架构 V0.2 §7.3。流程严格按文档顺序执行，每一步的失败都留下可查的记录：
 *
 * <ol>
 *   <li>读权威水位得到基线修订 R0，新建 BUILDING 代；
 *   <li>分页读取 core 导出快照；<b>每页都校验 catalogRevision 仍等于 R0</b>。文档明确禁止
 *       「一边分页读取不断变化的 core 数据、一边把开始时水位当作完整快照」——所以一旦发现修订
 *       在导入期间变化，本次重建判定为不一致并放弃，而不是把一个混合了多个时刻的快照换上去；
 *   <li>回放 R0 之后的事件（投影表已持有全部事件），遇到隔离/失败事件立即中止；
 *   <li>{@code markGenerationReady} 后在<b>一个事务内</b>持锁切换活动代并发布水位，再把旧代标记退役。
 *       旧代不立即清空，避免在途请求读到「活动代已换但行已没了」。
 * </ol>
 *
 * <p>重建期间增量发布仍在进行：新事件继续写入旧代。这不会造成丢失，因为回放覆盖到「切换时刻已
 * 接收的全部修订」，切换后增量从该修订继续。反过来若停掉增量，重建越久未投影的事件越多，
 * 切换瞬间反而更难追平。
 */
@Service
public class RebuildService {

  private static final Logger log = LoggerFactory.getLogger(RebuildService.class);

  private static final int EXPORT_PAGE_SIZE = 300;
  private static final int REPLAY_PAGE_SIZE = 200;

  private final com.cognitanexus.query.corelink.CoreInternalClient core;
  private final InboxStore inbox;
  private final MaintenanceStore maintenance;
  private final ProjectionApplier applier;
  private final ProjectionPreparer preparer;
  private final ProjectionProperties properties;
  private final RebuildUnitOfWork unitOfWork;

  public RebuildService(
      com.cognitanexus.query.corelink.CoreInternalClient core,
      InboxStore inbox,
      MaintenanceStore maintenance,
      ProjectionApplier applier,
      ProjectionPreparer preparer,
      ProjectionProperties properties,
      RebuildUnitOfWork unitOfWork) {
    this.core = core;
    this.inbox = inbox;
    this.maintenance = maintenance;
    this.applier = applier;
    this.preparer = preparer;
    this.properties = properties;
    this.unitOfWork = unitOfWork;
  }

  public Map<String, Object> rebuild() {
    String consumerId = properties.consumerId();
    com.cognitanexus.query.corelink.CoreInternalClient.PublicBarrier barrier = core.readBarrier();
    long baseRevision = barrier.catalogRevision();
    String generation = nextGenerationName();
    String runId = com.cognitanexus.query.platform.util.Identifiers.next("rbl");

    maintenance.createGeneration(
        generation, "全量重建：以 core 导出快照为基线（修订 " + baseRevision + "），再回放之后的事件");
    maintenance.startRebuild(runId, generation, baseRevision);
    long startedAt = Instant.now().toEpochMilli();
    log.info("重建开始 run={} generation={} baseRevision={}", runId, generation, baseRevision);

    long importedRows;
    try {
      importedRows = importSnapshot(generation, baseRevision);
    } catch (RebuildAborted aborted) {
      return abort(runId, generation, baseRevision, 0, aborted.getMessage());
    }

    List<InboxEventRecord> replayable;
    try {
      replayable = replayEvents(generation, consumerId, baseRevision, startedAt);
    } catch (RebuildAborted aborted) {
      return abort(runId, generation, baseRevision, importedRows, aborted.getMessage());
    }

    long replayedRevision =
        replayable.isEmpty()
            ? baseRevision
            : replayable.get(replayable.size() - 1).catalogRevision();
    ConsumerProgress switched = unitOfWork.switchTo(consumerId, generation, replayedRevision);
    maintenance.finishRebuild(
        runId,
        "SWITCHED",
        replayedRevision,
        importedRows,
        "快照导入 " + importedRows + " 条，回放至修订 " + replayedRevision + "，已切换活动代");

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("runId", runId);
    result.put("generation", generation);
    result.put("baseRevision", baseRevision);
    result.put("replayedRevision", replayedRevision);
    result.put("importedRows", importedRows);
    result.put("publishedRevision", switched.publishedRevision());
    result.put("receivedRevision", switched.receivedRevision());
    result.put("note", "已发布水位取回放到的修订，而不是当时的最新接收修订：取后者会让「已发布」超出实际写入内容。" + "剩余修订由增量发布继续推进。");
    log.info(
        "重建完成 run={} generation={} 导入={} 回放至={}",
        runId,
        generation,
        importedRows,
        replayedRevision);
    return result;
  }

  /** 导入快照；任一页的 catalogRevision 与基线不符即中止。 */
  private long importSnapshot(String generation, long baseRevision) {
    long imported = 0;
    int offset = 0;
    while (true) {
      com.cognitanexus.query.corelink.CoreInternalClient.ExportPage page =
          core.exportProjection(EXPORT_PAGE_SIZE, offset);
      if (page.catalogRevision() != baseRevision) {
        throw new RebuildAborted(
            "导出期间 core 修订发生变化（基线 "
                + baseRevision
                + "，本次读取 "
                + page.catalogRevision()
                + "），快照不一致，放弃本次重建而不是混用多个时刻的数据");
      }
      if (page.items().isEmpty()) {
        break;
      }
      for (Map<String, Object> item : page.items()) {
        String unusable = applier.importProjection(generation, item, baseRevision);
        if (unusable != null) {
          throw new RebuildAborted(
              "导出项不可导入（"
                  + unusable
                  + "），放弃本次重建以避免产出残缺视图；导出项 id="
                  + item.get("id")
                  + "。此类缺失只可能来自 core 的导出实现，请先修导出而不是跳过该行");
        }
        imported++;
      }
      offset += page.items().size();
      if (offset >= page.total()) {
        break;
      }
    }
    return imported;
  }

  /** 回放基线之后已接收的事件；返回实际回放的事件列表。 */
  private List<InboxEventRecord> replayEvents(
      String generation, String consumerId, long baseRevision, long startedAt) {
    long blocked = inbox.firstBlockedRevisionAfter(consumerId, baseRevision);
    if (blocked > 0) {
      throw new RebuildAborted("修订 " + blocked + " 处于隔离或失败状态，无法回放；先处理该事件再重建，" + "否则新视图会永久缺失这条变更");
    }
    List<InboxEventRecord> all = new java.util.ArrayList<>();
    long cursor = baseRevision;
    while (true) {
      List<InboxEventRecord> page = inbox.listAfterRevision(consumerId, cursor, REPLAY_PAGE_SIZE);
      if (page.isEmpty()) {
        break;
      }
      List<ProjectionPreparer.Prepared> prepared = new java.util.ArrayList<>(page.size());
      for (InboxEventRecord event : page) {
        // 事务外取外部数据（架构 §6.1）
        prepared.add(preparer.prepare(event));
      }
      RebuildUnitOfWork.BatchResult result = unitOfWork.applyBatch(generation, prepared);
      all.addAll(page);
      cursor = page.get(page.size() - 1).catalogRevision();
      if (page.size() < REPLAY_PAGE_SIZE) {
        break;
      }
      if (Instant.now().toEpochMilli() - startedAt > REBUILD_TIME_BUDGET_MILLIS) {
        throw new RebuildAborted("重建回放超过单次时间预算，已放弃本次重建以保证请求可预期");
      }
    }
    return all;
  }

  private Map<String, Object> abort(
      String runId, String generation, long baseRevision, long importedRows, String reason) {
    maintenance.markGenerationFailed(generation, reason);
    maintenance.finishRebuild(runId, "ABORTED", baseRevision, importedRows, reason);
    log.error("重建中止 run={} generation={} reason={}", runId, generation, reason);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("runId", runId);
    result.put("generation", generation);
    result.put("status", "ABORTED");
    result.put("baseRevision", baseRevision);
    result.put("importedRows", importedRows);
    result.put("reason", reason);
    result.put("note", "中止时代不参与查询：活动代仍是原代，公开视图内容未受影响。失败代的残留行由清理入口回收。");
    return result;
  }

  /**
   * generation 名称按现有代数量递增。
   *
   * <p>用「现有数量 + 1」而不是时间戳：名称要能一眼看出重建次序，而时间戳在同一秒内多次重建会
   * 撞名，撞名后 createGeneration 的 DO NOTHING 会让后续步骤作用在一个已存在的代上——那批旧行
   * 会被当成新导入的结果，核对通过后切换上去，内容却是历史的。
   */
  private String nextGenerationName() {
    int existing = maintenance.listGenerations().size();
    return String.format("gen%04d", existing + 1);
  }

  public Map<String, Object> purgeable() {
    String active = maintenance.activeGeneration(properties.consumerId());
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("activeGeneration", active);
    view.put("retiredCount", maintenance.countRetiredGenerations(active));
    view.put("generations", maintenance.listGenerations());
    view.put("rebuildRuns", maintenance.listRebuildRuns(20));
    return view;
  }

  public Map<String, Object> purge() {
    String active = maintenance.activeGeneration(properties.consumerId());
    int purged = maintenance.purgeRetiredGenerations(active);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("purgedGenerations", purged);
    view.put("activeGeneration", active);
    view.put("note", "仅清理已退役或已失败且不是当前活动代的投影行。调用方须确认在途请求已排空。");
    return view;
  }

  /** 单次重建的时间预算，避免维护操作无限占用资源。 */
  private static final long REBUILD_TIME_BUDGET_MILLIS = 300_000L;
}
