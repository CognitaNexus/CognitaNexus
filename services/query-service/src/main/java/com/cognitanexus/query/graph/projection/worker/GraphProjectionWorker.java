package com.cognitanexus.query.graph.projection.worker;

import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.projection.application.GraphEventProjectionResult;
import com.cognitanexus.query.graph.projection.application.GraphEventProjector;
import com.cognitanexus.query.graph.projection.application.GraphProjectionCheckpointStore;
import com.cognitanexus.query.graph.projection.application.GraphProjectionProperties;
import com.cognitanexus.query.graph.projection.application.GraphProjectionReadiness;
import com.cognitanexus.query.graph.projection.application.GraphProjectionSource;
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
 * <p><b>与回填的关系</b>：增量只能在「全量回填已经把这一代灌完、且没有留下失败成果」之后开始。
 * 四条前置条件（断点存在、{@code COMPLETED}、{@code failed_works=0}、generation 与 PG 活动代一致）
 * 由 {@link GraphProjectionReadiness} 统一判定，调度器与 {@link GraphEventProjector} 用的是同一份
 * 规则。原因不是「怕慢」而是「怕说谎」：增量追赶会把 {@code GraphProjectionMeta.projected_version}
 * 推进到 {@code published_revision}，而回填只灌了一半（或根本没跑）——水位看上去是「已追平」，
 * 公开读路径就会把残缺的图当成完整图使用。只有全部前置条件成立，增量才从回填快照修订之后接上
 * （ADR-005 §5.2「快照 + 回放」）。
 *
 * <p>特别地，<b>没有断点不等于「不需回填」</b>：从未回填过的实例里图是空的（或只有上一次运行的
 * 残余），此时任何水位推进都是伪造的，因此「无断点」与「断点 RUNNING/FAILED/有失败成果/跨代」
 * 同等处理：跳过本轮。
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
      Optional<GraphProjectionSource.Stage> stage = source.readStage(consumerId);
      if (stage.isEmpty()) {
        return;
      }
      Optional<String> blocked = incrementalBlocker(stage.get().generation());
      if (blocked.isPresent()) {
        // 回填未完成（或无断点/有失败成果/跨代）时推水位，会让读路径信任一张残缺的图（ADR-005 §5.2）。
        log.debug("图投影增量追赶前置条件不满足，跳过本轮 scope={}：{}", scope, blocked.get());
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
   * 本轮增量消费是否被初始化前置条件拦住。
   *
   * @return 空表示允许追赶；有值表示拒绝原因（进 debug 日志，不抛给调度线程）
   */
  Optional<String> incrementalBlocker(String activeGeneration) {
    return GraphProjectionReadiness.blocker(checkpoints.find(scope), activeGeneration);
  }

  /** 当前图水位，供运维端点/脚本读取（不触发任何写入）。 */
  public long projectedVersion() {
    return store.readMeta(scope).map(meta -> meta.projectedVersion()).orElse(0L);
  }
}
