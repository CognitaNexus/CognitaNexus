package com.cognitanexus.query.graph.projection.application;

import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import com.cognitanexus.query.graph.projection.domain.GraphBackfillStatus;
import com.cognitanexus.query.graph.projection.domain.GraphProjectedWork;
import com.cognitanexus.query.graph.projection.domain.GraphProjectionCheckpoint;
import com.cognitanexus.query.graph.projection.domain.GraphReconciliationFinding;
import com.cognitanexus.query.graph.projection.domain.GraphReconciliationReport;
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
 * （对账的 {@code WORK_MISSING_IN_GRAPH} 会指名它们）。
 *
 * <p><b>完成与水位的前提是零失败</b>（这是本类最硬的一条）：扫描到底但存在任何失败成果时，断点
 * 一律记 {@code FAILED}（保留 cursor 与 {@code last_error}）且<b>不</b>调用 {@code advanceMeta}。
 * 理由：水位回答的是「图能不能用于公开查询」，而「扫完了」与「都进图了」是两件事。把带缺口的一次
 * 回填标成 COMPLETED，增量就会从快照修订之后接上并推进水位，读路径从此信任一张缺了几条成果的图，
 * 且缺口再也不会被重试（游标已越过了它们）。正确处置是：修好失败成果后 {@link #rebuild(String)}
 * 或 {@link GraphProjectionReconciler#repair} 补齐，再让水位前进。
 *
 * <p><b>修复后必须能恢复 COMPLETED（否则「零失败才能 COMPLETED」会变成永久封锁）</b>：失败成果都在
 * 游标之后，{@link #backfill(String)} 续跑不会再扫到它们（游标已越过），仅仅重跑回填只会把
 * {@code failed_works} 继续累加、永远停在 FAILED，于是 {@link GraphProjectionReadiness} 永久拒绝增量。
 * 因此本类提供 {@link #markRepairedAndCompleted} 作为唯一的收尾入口：它要求一份<b>零阻塞差异</b>的对账
 * 报告作为证据（调用路径见 {@link GraphProjectionReconciler#repairAndComplete}），才把断点改回
 * COMPLETED、{@code failed_works} 归零并推进水位。没有证据就没有 COMPLETED——「修好了」不是一句话。
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

  /**
   * 定向修复后的收尾：把 FAILED/RUNNING 的断点恢复为 COMPLETED、{@code failed_works} 归零并推进图水位。
   *
   * <p>这是「零失败才能 COMPLETED」的<b>另一半</b>：失败成果都在游标之后，所以继续 {@code backfill}
   * 永远扫不到它们，{@code failed_works} 只会单调累加——如果不提供这条路径，唯一的解除封锁办法就是
   * 整库 {@code rebuild}，一次脏数据就能把百万级重建重做一遍。
   *
   * <p>五道校验，缺一不开门（都是为了不让水位说谎）：
   *
   * <ol>
   *   <li><b>必须有对账报告</b>：没有证据的「已修好」不接受；
   *   <li><b>报告必须同作用域、同 generation</b>：拿别的代/别的环境的一致结论来收尾本代，等于伪造；
   *   <li><b>报告不得有阻塞差异</b>（{@link GraphReconciliationReport#hasDrift()}）：仍缺成果/多边/
   *       标题过期时水位不能前进；
   *   <li><b>报告不得被截断</b>（{@code scanTruncated}）：只对比了前 5000 条时「零差异」只在那 5000 条
   *       内成立，不能升级成全库结论；
   *   <li><b>断点 generation 必须等于 PG 活动代</b>：不同代不是收尾问题，是整库重建问题。
   * </ol>
   *
   * <p>{@code processedWorks} 补上本次实际写进图的成果数（{@code repairedWorks}），让累计计数与图的真实
   * 内容一致；水位推进沿用 {@link #advanceWatermark} 的「只前进」规则，所以新事件到达后再由增量追赶。
   *
   * @param scope 投影作用域（null 走默认作用域）
   * @param verification 修复后重新执行的对账报告（必须零阻塞差异且未被截断）
   * @param repairedWorks 本次修复真正写入图的成果数
   * @throws IllegalStateException 证据不足/仍存在差异/扫描被截断/generation 不一致时拒绝收尾
   */
  public GraphBackfillResult markRepairedAndCompleted(
      String scope, GraphReconciliationReport verification, int repairedWorks) {
    if (verification == null) {
      throw new IllegalArgumentException("恢复 COMPLETED 必须附带一份对账报告作为证据：无证据的『已修好』不予收尾");
    }
    String effectiveScope = effectiveScope(scope);
    if (!effectiveScope.equals(verification.scope())) {
      throw new IllegalStateException(
          "对账报告作用域 "
              + verification.scope()
              + " 与待收尾作用域 "
              + effectiveScope
              + " 不一致：不得用别的作用域的一致结论收尾");
    }
    if (verification.hasDrift()) {
      throw new IllegalStateException(
          "对账仍存在 "
              + verification.countBySeverity(GraphReconciliationFinding.Severity.ERROR)
              + " 条阻塞差异（"
              + verification.summary()
              + "）：断点保持 FAILED，不恢复 COMPLETED、不推进水位");
    }
    if (verification.scanTruncated()) {
      throw new IllegalStateException(
          "对账扫描被截断（只覆盖前 "
              + verification.scannedWorks()
              + " 条合格成果）：『零差异』只在该范围内成立，不足以支持全库 COMPLETED；请提高 scan-limit 后重新对账");
    }
    GraphProjectionSource.Stage stage = requireStage();
    GraphProjectionCheckpoint current =
        checkpoints
            .find(effectiveScope)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "尚无回填断点（scope=" + effectiveScope + "）：先执行 backfill 再谈修复收尾"));
    if (!current.matchesGeneration(stage.generation())) {
      throw new IllegalStateException(
          "断点 generation="
              + current.generation()
              + " 与 PG 活动代 "
              + stage.generation()
              + " 不一致：这不是修复收尾能解决的问题，必须整库重建（rebuild）");
    }
    if (!stage.generation().trim().equals(verification.generation())) {
      throw new IllegalStateException(
          "对账报告属于 generation="
              + verification.generation()
              + "，PG 活动代是 "
              + stage.generation()
              + "：请针对当前代重新对账");
    }
    if (current.status() == GraphBackfillStatus.COMPLETED && current.failedWorks() == 0) {
      return toResult(
          current, readProjectedVersion(effectiveScope), 0, 0, "断点已是 COMPLETED 且零失败，无需收尾");
    }

    Instant now = clock.instant();
    GraphProjectionCheckpoint repaired = current.repaired(repairedWorks, now);
    checkpoints.save(repaired);
    long projected = advanceWatermark(effectiveScope, repaired, stage);
    log.warn(
        "图投影修复收尾 scope={} generation={} failed {}→0 修复成果={} 水位 {}→{}",
        effectiveScope,
        stage.generation(),
        current.failedWorks(),
        Math.max(repairedWorks, 0),
        current.baseRevision(),
        projected);
    return toResult(
        repaired,
        projected,
        Math.max(repairedWorks, 0),
        0,
        "对账零阻塞差异，断点已恢复 COMPLETED、失败数归零并推进水位到 " + projected);
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
      if (checkpoint.failedWorks() > 0) {
        // 扫描到底但存在失败成果：绝不能标 COMPLETED、绝不能推进图水位（ADR-005 §5.2）。
        // 保留 cursor 与 last_error：失败成果的标识与原因仍可定位，修复后可用 repair 单点补齐，
        // 或直接 rebuild 全量重建。
        String error =
            "回填扫描到底但存在 "
                + checkpoint.failedWorks()
                + " 条失败成果（最后失败样例："
                + (checkpoint.lastError() == null ? "未记录" : checkpoint.lastError())
                + "），断点保持 FAILED：图不完整，不得标为完成、也不推进图水位；"
                + "修复后执行 reconcile + repairAndComplete（对账零差异才恢复 COMPLETED）或 rebuild";
        checkpoint = checkpoint.failed(error, finishedAt);
        checkpoints.save(checkpoint);
        log.error(
            "图投影回填未能完成（存在失败成果） scope={} generation={} works={} batches={} failed={} revision={}",
            effectiveScope,
            stage.generation(),
            checkpoint.processedWorks(),
            checkpoint.processedBatches(),
            checkpoint.failedWorks(),
            readProjectedVersion(effectiveScope));
        return toResult(
            checkpoint, readProjectedVersion(effectiveScope), worksThisRun, batchesThisRun, error);
      }
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
