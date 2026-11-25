package com.cognitanexus.query.graph.projection.worker;

import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.projection.application.GraphEventProjectionResult;
import com.cognitanexus.query.graph.projection.application.GraphEventProjector;
import com.cognitanexus.query.graph.projection.application.GraphProjectionProperties;
import com.cognitanexus.query.graph.projection.application.GraphProjectionSource;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 图投影的增量追赶调度。
 *
 * <p>只有一件职责：把 PG 已应用的事件追进图。它<b>不</b>自动启动回填——全量重建会清空整库，
 * 而清空的前提是读路径已经切到 PG（ADR-005 §8.2）。把这种破坏性动作藏进启动调度，等于让一次
 * 重启决定「现在可以清库」，这不是调度器该有的权力。
 *
 * <p>异常就地记录：图库抖动一次不能让调度线程停摆，否则下一次连「把失败记下来」都做不到。
 * 水位不会因此前进（失败即停），缺口因此可见，而不是被后续事件悄悄跨过。
 */
@Component
@ConditionalOnProperty(
    prefix = "cn.query.graph.projection",
    name = "worker-enabled",
    havingValue = "true")
public class GraphProjectionWorker {

  private static final Logger log = LoggerFactory.getLogger(GraphProjectionWorker.class);

  private final GraphEventProjector projector;
  private final GraphProjectionStore store;
  private final GraphProjectionSource source;
  private final GraphProjectionProperties properties;
  private final String scope;
  private final String consumerId;

  public GraphProjectionWorker(
      GraphEventProjector projector,
      GraphProjectionStore store,
      GraphProjectionSource source,
      GraphProjectionProperties properties,
      com.cognitanexus.query.graph.config.Neo4jGraphProperties neo4jProperties,
      com.cognitanexus.query.projection.application.ProjectionProperties projectionProperties) {
    this.projector = projector;
    this.store = store;
    this.source = source;
    this.properties = properties;
    this.scope = neo4jProperties.scope();
    this.consumerId = projectionProperties.consumerId();
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

  /** 当前图水位，供运维端点/脚本读取（不触发任何写入）。 */
  public long projectedVersion() {
    return store.readMeta(scope).map(meta -> meta.projectedVersion()).orElse(0L);
  }
}
