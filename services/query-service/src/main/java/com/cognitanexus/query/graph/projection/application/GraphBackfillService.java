package com.cognitanexus.query.graph.projection.application;

import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import com.cognitanexus.query.graph.projection.domain.GraphBackfillStatus;
import com.cognitanexus.query.graph.projection.domain.GraphProjectedWork;
import com.cognitanexus.query.graph.projection.domain.GraphProjectionCheckpoint;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从 PG 读投影分批回填图（初次全量、断点续跑、受控重建）。
 *
 * <p>依据：ADR-005 §5.2、§8.2。三条不可让步的规则：
 *
 * <ol>
 *   <li><b>必须有界</b>：每批最多 {@code batchSize} 条（默认 500）。2C8G 实例上每批要装成果、
 *       作者、领域、机构与作者对，批次过大是重建期 OOM 的首要原因；上限在配置层再钳一次，
 *       写错的配置也不能把整代成果读进内存；
 *   <li><b>断点必须落库</b>：每批结束写一行断点（游标 = 本批最后一条 workId）。进程被杀后
 *       重启能从同一位置继续，而不是从 0 重扫（重扫虽然幂等，但把重建窗口放大成 N 倍）；
 *   <li><b>失败要能被看见</b>：失败成果记入 {@code failedWorks} 并在超过阈值时把整次运行转
 *       FAILED。「跑完了但有几万条没进图」不是成功。
 * </ol>
 *
 * <p><b>计数口径</b>：{@code processed_works} 只累加非失败成果（APPLIED / RETIRED /
 * STALE_IGNORED），失败数单独落在 {@code failed_works} 与 {@code last_error}。
 * 单条永久失败不会让整次回填停摆（否则真正的问题成果永远轮不到修复：回填会在同一条上反复卡住），
 * 游标继续前进；这些漏格的点由 {@link GraphProjectionReconciler#repair} 在对账后单点补齐
 * （对账的 {@code WORK_MISSING_IN_GRAPH} 会指名它们）。但失败数超过
 * {@code max-failed-works} 时整次运行转 FAILED——不能被当成跑完了。
 *
 * <p>并发：同进程内用 {@link AtomicBoolean} 互斥，避免调度器与人工触发同时写同一批成果——
 * ADR-005 §5.1 明确「同一 workId 必须串行消费」，版本守卫只兜底迟到/重放，不承担并发互斥。
 * 跨进程互斥需要 PG advisory lock 或运维层面的单实例约束，本类不假装已经解决。
 *
 * <p>generation 变化（PG 重建切换）时<b>拒绝续跑</b>：在旧代游标上继续写新代数据，会得到
 * 「图里一半旧代一半新代」的混合视图，而水位看起来在前进。正确动作是 {@link #rebuild(String)}。
 */
public class GraphBackfillService {

  private static final Logger log = LoggerFactory.getLogger(GraphBackfillService.class);

  private final GraphProjectionSource source;
  private final GraphProjectionSynchronizer synchronizer;
  private final GraphProjectionStore store;
  private final GraphProjectionCheckpointStore checkpoints;
  private final GraphProjectionProperties properties;
  private final String consumerId;
  private final Clock clock;
  private final AtomicBoolean running = new AtomicBoolean(false);

  public GraphBackfillService(
      GraphProjectionSource source,
      GraphProjectionSynchronizer synchronizer,
      GraphProjectionStore store,
      GraphProjectionCheckpointStore checkpoints,
      GraphProjectionProperties properties,
      String consumerId) {
    this(source, synchronizer, store, checkpoints, properties, consumerId, Clock.systemUTC());
  }

  public GraphBackfillService(
      GraphProjectionSource source,
      GraphProjectionSynchronizer synchronizer,
      GraphProjectionStore store,
      GraphProjectionCheckpointStore checkpoints,
      GraphProjectionProperties properties,
      String consumerId,
      Clock clock) {
    this.source = source;
    this.synchronizer = synchronizer;
    this.store = store;
    this.checkpoints = checkpoints;
    this.properties = properties;
    this.consumerId = consumerId == null || consumerId.isBlank() ? "query" : consumerId;
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  /** 当前断点（没有则为 empty），供运维与对账工具读取进度。 */
  public Optional<GraphProjectionCheckpoint> checkpoint(String scope) {
    return checkpoints.find(effectiveScope(scope));
  }

  /**
   * 回填：没有断点时从头开始，有 RUNNING/FAILED 断点时从游标继续，COMPLETED 时直接返回。
   *
   * <p>回填完成会把图水位推进到<b>本次快照的 PG 已发布修订</b>（{@code baseRevision}）；之后
   * 由 {@link GraphEventProjector} 从该修订之后追平。「快照 + 回放」的锚点写进断点，重启后
   * 仍然知道该从哪一条修订开始补事件。
   */
  public GraphBackfillResult backfill(String scope) {
    return run(scope, false);
  }

  /**
   * 受控全量重建：清空<b>整库</b>（{@code resetAllForRebuild}，独占实例才允许）后从头回填。
   *
   * <p>调用前图查询必须已在 PG 降级路径（ADR-005 §8.2）：清空期间图上没有数据，任何仍读图的
   * 请求都会看到空图，而不是「稍等就有」。
   */
  public GraphBackfillResult rebuild(String scope) {
    return run(scope, true);
  }

  private GraphBackfillResult run(String scope, boolean rebuild) {
    String effectiveScope = effectiveScope(scope);
    if (!running.compareAndSet(false, true)) {
      throw new IllegalStateException(
          "已有回填任务在执行（scope="
              + effectiveScope
              + "）；同一实例不允许并发回填，"
              + "否则同一成果会被两个写入者交错替换（ADR-005 §5.1）");
    }
    GraphProjectionCheckpoint checkpoint = null;
    try {
      GraphProjectionSource.Stage stage = requireStage();
      if (rebuild) {
        // 整库清空：实现会在同一事务里拒绝「存在其它作用域」的情况并回滚，这里不做兜底判断，
        // 否则就变成两处各写一份独占规则。
        store.resetAllForRebuild(effectiveScope);
        log.warn("图投影受控重建已清空图库 scope={} generation={}", effectiveScope, stage.generation());
      }
      checkpoint = prepareCheckpoint(effectiveScope, stage, rebuild);
      if (!checkpoint.resumable()) {
        return toResult(checkpoint, readProjectedVersion(effectiveScope), 0, 0, "该代已回填完成，无需续跑");
      }

      long worksThisRun = 0L;
      int batchesThisRun = 0;
      String cursor = checkpoint.cursorWorkId();
      while (true) {
        List<GraphProjectedWork> page =
            source.pageWorks(stage.generation(), cursor, properties.batchSize());
        if (page.isEmpty()) {
          break;
        }
        GraphSyncBatch batch = synchronizer.syncWorks(page);
        String nextCursor = page.get(page.size() - 1).workId();
        Instant now = clock.instant();
        // processed_works 只计「真正写进图」的成果（APPLIED / RETIRED / STALE_IGNORED），
        // 失败数单独记在 failed_works + last_error（与 V7 表注释同一口径）。
        // 若把失败成果也计入 processed_works，「跑完了但几万条没进图」会被一次求和掩盖。
        int succeeded = (int) (batch.processed() - batch.failed());
        String batchError = batch.failed() > 0 ? batch.failureSummary(5) : null;
        checkpoint = checkpoint.advanced(nextCursor, succeeded, batch.failed(), batchError, now);
        checkpoints.save(checkpoint);
        worksThisRun += succeeded;
        batchesThisRun++;
        cursor = nextCursor;
        if (batch.failed() > 0) {
          log.warn(
              "图投影批次存在失败 scope={} batch={} failed={} detail={}",
              effectiveScope,
              batchesThisRun,
              batch.failed(),
              batch.failureSummary(5));
        }
        if (checkpoint.failedWorks() > properties.maxFailedWorks()) {
          String error =
              "失败成果数 "
                  + checkpoint.failedWorks()
                  + " 超过上限 "
                  + properties.maxFailedWorks()
                  + "，中止本次回填（最后失败样例："
                  + batch.failureSummary(3)
                  + "）";
          checkpoint = checkpoint.failed(error, now);
          checkpoints.save(checkpoint);
          log.error("图投影回填中止 scope={} reason={}", effectiveScope, error);
          return toResult(
              checkpoint,
              readProjectedVersion(effectiveScope),
              worksThisRun,
              batchesThisRun,
              error);
        }
        if (page.size() < properties.batchSize()) {
          break;
        }
      }

      Instant finishedAt = clock.instant();
      checkpoint = checkpoint.completed(finishedAt);
      checkpoints.save(checkpoint);
      long projected = advanceWatermark(effectiveScope, checkpoint, stage);
      log.info(
          "图投影回填完成 scope={} generation={} works={} batches={} failed={} revision={}",
          effectiveScope,
          stage.generation(),
          checkpoint.processedWorks(),
          checkpoint.processedBatches(),
          checkpoint.failedWorks(),
          projected);
      return toResult(checkpoint, projected, worksThisRun, batchesThisRun, null);
    } catch (RuntimeException ex) {
      // 断点写失败不掩盖原因：先尽量把 FAILED 落库，再原样抛出（调用方要对账/告警）。
      if (checkpoint != null) {
        try {
          GraphProjectionCheckpoint failed =
              checkpoint.failed(
                  ex.getClass().getSimpleName() + ": " + message(ex), clock.instant());
          checkpoints.save(failed);
        } catch (RuntimeException persistFailure) {
          log.error("图投影回填失败且断点写入失败 scope={}", effectiveScope, persistFailure);
        }
      }
      throw ex;
    } finally {
      running.set(false);
    }
  }

  /**
   * 读取或新建断点。
   *
   * <p>三种情况必须分开处理：
   *
   * <ul>
   *   <li>没有断点 → 新建（首次回填）；
   *   <li>断点属于别的 generation → 拒绝并提示 rebuild：旧代游标在新代上没有意义；
   *   <li>断点 COMPLETED 且同一代 → 直接返回（不重扫，重扫会把「已完成」变成「每次调用都全量」）。
   * </ul>
   */
  private GraphProjectionCheckpoint prepareCheckpoint(
      String scope, GraphProjectionSource.Stage stage, boolean rebuild) {
    Optional<GraphProjectionCheckpoint> existing = checkpoints.find(scope);
    Instant now = clock.instant();
    if (rebuild || existing.isEmpty()) {
      GraphProjectionCheckpoint fresh =
          GraphProjectionCheckpoint.started(
              scope, stage.generation(), stage.publishedRevision(), now);
      checkpoints.save(fresh);
      return fresh;
    }
    GraphProjectionCheckpoint current = existing.get();
    if (!current.matchesGeneration(stage.generation())) {
      throw new IllegalStateException(
          "PG 投影 generation 已从 "
              + current.generation()
              + " 切换到 "
              + stage.generation()
              + "（scope="
              + scope
              + "）。在旧代游标上续跑会得到新旧混合的图，必须先 rebuild 全量重建");
    }
    if (!current.resumable()) {
      return current;
    }
    // 重新置为 RUNNING：上一轮可能是被进程杀掉留下的 RUNNING，也可能是已经 FAILED 后修复。
    GraphProjectionCheckpoint resumed =
        new GraphProjectionCheckpoint(
            scope,
            GraphBackfillStatus.RUNNING,
            current.generation(),
            current.cursorWorkId(),
            current.baseRevision(),
            current.processedWorks(),
            current.processedBatches(),
            current.failedWorks(),
            null,
            current.startedAt(),
            now,
            null);
    checkpoints.save(resumed);
    return resumed;
  }

  /** 回填完成后把图水位推进到本次快照修订；只前进，已有更高水位时保持原值。 */
  private long advanceWatermark(
      String scope, GraphProjectionCheckpoint checkpoint, GraphProjectionSource.Stage stage) {
    GraphProjectionMeta current = store.readMeta(scope).orElse(GraphProjectionMeta.empty(scope));
    long target = Math.max(checkpoint.baseRevision(), current.projectedVersion());
    GraphProjectionMeta meta =
        new GraphProjectionMeta(
            scope,
            target,
            Math.max(current.catalogRevision(), checkpoint.baseRevision()),
            stage.generation(),
            clock.instant());
    return store.advanceMeta(meta);
  }

  private long readProjectedVersion(String scope) {
    return store.readMeta(scope).map(GraphProjectionMeta::projectedVersion).orElse(0L);
  }

  private GraphProjectionSource.Stage requireStage() {
    return source
        .readStage(consumerId)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "PG 投影水位缺失（qry_consumer_state 无 consumer_id=" + consumerId + "），无法回填图投影"));
  }

  private String effectiveScope(String scope) {
    if (scope != null && !scope.isBlank()) {
      return scope.trim();
    }
    return "query-public";
  }

  private GraphBackfillResult toResult(
      GraphProjectionCheckpoint checkpoint,
      long projectedVersion,
      long worksThisRun,
      int batchesThisRun,
      String note) {
    return new GraphBackfillResult(
        checkpoint.scope(),
        checkpoint.status(),
        checkpoint.generation(),
        checkpoint.processedWorks(),
        checkpoint.processedBatches(),
        checkpoint.failedWorks(),
        checkpoint.cursorWorkId(),
        checkpoint.baseRevision(),
        projectedVersion,
        worksThisRun,
        batchesThisRun,
        note);
  }

  private static String message(RuntimeException ex) {
    String message = ex.getMessage();
    if (message == null || message.isBlank()) {
      return "无消息";
    }
    String firstLine = message.split("\\R", 2)[0].trim();
    return firstLine.length() > 300 ? firstLine.substring(0, 300) + "…" : firstLine;
  }
}
