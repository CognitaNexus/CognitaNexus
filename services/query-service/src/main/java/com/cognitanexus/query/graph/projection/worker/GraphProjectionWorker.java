package com.cognitanexus.query.graph.projection.worker;

import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.projection.application.GraphEventProjectionResult;
import com.cognitanexus.query.graph.projection.application.GraphEventProjector;
import com.cognitanexus.query.graph.projection.application.GraphProjectionCheckpointStore;
import com.cognitanexus.query.graph.projection.application.GraphProjectionProperties;
import com.cognitanexus.query.graph.projection.application.GraphProjectionSource;
import com.cognitanexus.query.graph.projection.domain.GraphBackfillStatus;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 图投影的增量追赶调度。
 *
 * <p>只有一件职责：把 PG 已应用的事件追进图。它<b>不</b>自动启动回填——全量重建会清空整库，
 * 而清空的前提是读路径已经切到 PG（ADR-005 §8.2）。把这种破坏性动作藏进启动调度，等于让一次
 * 重启决定「现在可以清库」，这不是调度器该有的权力。
 *
 * <p><b>与回填互斥</b>：只要 V7 断点存在且尚未 {@code COMPLETED}（{@code RUNNING}/{@code FAILED}），
 * 本轮增量消费直接跳过。原因不是「怕慢」而是「怕说谎」：增量追赶会把
 * {@code GraphProjectionMeta.projected_version} 推进到 {@code published_revision}，而回填只灌了一半
 * 成果——水位看上去是「已追平」，公开读路径就会把半张图当成完整图使用。断点转
 * {@code COMPLETED} 之后，增量才从回填快照修订之后接上（ADR-005 §5.2「快照 + 回放」）。
 *
 * <p>异常就地记录：图库抖动一次不能让调度线程停摆，否则下一次连「把失败记下来」都做不到。
 * 水位不会因此前进（失败即停），缺口因此可见，而不是被后续事件悄悄跨过。
 *
 * <p>装配在 {@code GraphProjectionConfiguration}（与 {@code cn.query.graph.enabled}、
 * {@code cn.query.graph.projection.enabled}、{@code worker-enabled} 三个开关同级），不靠组件扫描：
 * 条件装配与它所依赖的 {@link GraphEventProjector} 的定义顺序必须可预测。
 */
public class GraphProjectionWorker {

  private static final Logger log = LoggerFactory.getLogger(GraphProjectionWorker.class);

  private final GraphEventProjector projector;
  private final GraphProjectionStore store;
  private final GraphProjectionSource source;
  private final GraphProjectionCheckpointStore checkpoints;
  private final GraphProjectionProperties properties;
  private final String scope;
  private final String consumerId;

  public GraphProjectionWorker(
      GraphEventProjector projector,
      GraphProjectionStore store,
      GraphProjectionSource source,
      GraphProjectionCheckpointStore checkpoints,
      GraphProjectionProperties properties,
      String scope,
      String consumerId) {
    this.projector = projector;
    this.store = store;
    this.source = source;
    this.checkpoints = checkpoints;
    this.properties = properties;
    this.scope = scope == null || scope.isBlank() ? "query-public" : scope.trim();
    this.consumerId = consumerId == null || consumerId.isBlank() ? "query" : consumerId;
  }

  @Scheduled(
      fixedDelayString = "${cn.query.graph.projection.worker-interval-ms:5000}",
      initialDelayString = "5000")
  public void catchUp() {
    if (!properties.enabled() || !properties.workerEnabled()) {
      return;
    }
    try {
      if (backfillInProgress()) {
        // 回填半途把水位推到 published_revision 会让读路径信任一张只灌了一半的图（ADR-005 §5.2）。
        log.debug("图投影回填未完成，跳过本轮增量追赶 scope={}（避免水位提前追平 published_revision）", scope);
        return;
      }
      Optional<GraphProjectionSource.Stage> stage = source.readStage(consumerId);
      if (stage.isEmpty()) {
        return;
      }
      GraphEventProjectionResult result =
          projector.applyPending(scope, properties.eventBatchSize());
      if (result.eventsRead() > 0) {
        log.info("图投影增量追赶 {}", result.summary());
      }
    } catch (RuntimeException ex) {
      // 图库不可用/水位读失败都属于「本轮没追上」；下一轮重试，水位不会前进。
      log.warn("图投影增量追赶失败 scope={} consumer={} error={}", scope, consumerId, ex.toString());
    }
  }

  /**
   * 断点存在且未完成（{@code RUNNING}/{@code FAILED}）视为回填进行中。
   *
   * <p>{@code FAILED} 也算「未完成」：失败的回填保留 cursor 等待续跑，此时让增量把水位推到
   * 已发布修订，等于用「增量追上了」掩盖「回填还缺一段」，而缺的那一段恰恰是最早的成果。
   */
  boolean backfillInProgress() {
    return checkpoints
        .find(scope)
        .filter(cp -> cp.status() != GraphBackfillStatus.COMPLETED)
        .isPresent();
  }

  /** 当前图水位，供运维端点/脚本读取（不触发任何写入）。 */
  public long projectedVersion() {
    return store.readMeta(scope).map(meta -> meta.projectedVersion()).orElse(0L);
  }
}
