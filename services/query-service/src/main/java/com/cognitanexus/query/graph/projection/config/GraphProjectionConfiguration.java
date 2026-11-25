package com.cognitanexus.query.graph.projection.config;

import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.config.Neo4jGraphProperties;
import com.cognitanexus.query.graph.infrastructure.Neo4jSessionRunner;
import com.cognitanexus.query.graph.projection.application.GraphBackfillService;
import com.cognitanexus.query.graph.projection.application.GraphEventProjector;
import com.cognitanexus.query.graph.projection.application.GraphProjectionCheckpointStore;
import com.cognitanexus.query.graph.projection.application.GraphProjectionInspector;
import com.cognitanexus.query.graph.projection.application.GraphProjectionProperties;
import com.cognitanexus.query.graph.projection.application.GraphProjectionReconciler;
import com.cognitanexus.query.graph.projection.application.GraphProjectionRetryPolicy;
import com.cognitanexus.query.graph.projection.application.GraphProjectionSource;
import com.cognitanexus.query.graph.projection.application.GraphProjectionSynchronizer;
import com.cognitanexus.query.graph.projection.infrastructure.GraphProjectionCheckpointAdapter;
import com.cognitanexus.query.graph.projection.infrastructure.GraphProjectionCheckpointMapper;
import com.cognitanexus.query.graph.projection.infrastructure.GraphProjectionSourceAdapter;
import com.cognitanexus.query.graph.projection.infrastructure.GraphProjectionSourceMapper;
import com.cognitanexus.query.graph.projection.infrastructure.Neo4jGraphProjectionInspector;
import com.cognitanexus.query.platform.persistence.JsonCodec;
import com.cognitanexus.query.projection.application.ProjectionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 图投影同步（阶段四）的装配。
 *
 * <p>两层开关，缺一不装：
 *
 * <ol>
 *   <li>{@code cn.query.graph.enabled=true}：没有图库就不可能有图投影；
 *   <li>{@code cn.query.graph.projection.enabled=true}：同步是<b>后台写入</b>能力，必须显式开启，
 *       避免「只是打开了图读路径」的部署突然开始往图里灌几十万条数据。
 * </ol>
 *
 * <p>默认关闭也意味着：本类在默认配置下不创建任何 Bean，因此不会因为多了一个 Mapper 或调度器
 * 而改变现有行为（AGENTS.md §1「先复用，再演进」）。回填与对账的<b>触发</b>留给运维脚本/后续
 * Issue；本类只装配可被调用的服务，不自动启动长任务——重建需要先把读路径切到 PG（ADR-005 §8.2），
 * 这件事不能由启动过程替人决定。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GraphProjectionProperties.class)
@ConditionalOnProperty(prefix = "cn.query.graph", name = "enabled", havingValue = "true")
public class GraphProjectionConfiguration {

  @Bean
  public GraphProjectionSource graphProjectionSource(
      GraphProjectionSourceMapper mapper, JsonCodec json) {
    return new GraphProjectionSourceAdapter(mapper, json);
  }

  @Bean
  public GraphProjectionCheckpointStore graphProjectionCheckpointStore(
      GraphProjectionCheckpointMapper mapper) {
    return new GraphProjectionCheckpointAdapter(mapper);
  }

  @Bean
  public GraphProjectionInspector graphProjectionInspector(Neo4jSessionRunner runner) {
    return new Neo4jGraphProjectionInspector(runner);
  }

  /** 同步、回填、增量与对账只在投影开关打开时创建。 */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(
      prefix = "cn.query.graph.projection",
      name = "enabled",
      havingValue = "true")
  static class ProjectionServices {

    @Bean
    public GraphProjectionSynchronizer graphProjectionSynchronizer(
        GraphProjectionSource source,
        GraphProjectionStore store,
        GraphProjectionProperties properties) {
      return new GraphProjectionSynchronizer(
          source, store, GraphProjectionRetryPolicy.from(properties), millis -> sleep(millis));
    }

    @Bean
    public GraphEventProjector graphEventProjector(
        GraphProjectionSource source,
        GraphProjectionSynchronizer synchronizer,
        GraphProjectionStore store,
        JsonCodec json,
        ProjectionProperties projectionProperties) {
      return new GraphEventProjector(
          source, synchronizer, store, json, projectionProperties.consumerId());
    }

    @Bean
    public GraphBackfillService graphBackfillService(
        GraphProjectionSource source,
        GraphProjectionSynchronizer synchronizer,
        GraphProjectionStore store,
        GraphProjectionCheckpointStore checkpoints,
        GraphProjectionProperties properties,
        ProjectionProperties projectionProperties) {
      return new GraphBackfillService(
          source, synchronizer, store, checkpoints, properties, projectionProperties.consumerId());
    }

    @Bean
    public GraphProjectionReconciler graphProjectionReconciler(
        GraphProjectionSource source,
        GraphProjectionInspector inspector,
        GraphProjectionSynchronizer synchronizer,
        GraphProjectionStore store,
        Neo4jGraphProperties neo4jProperties,
        ProjectionProperties projectionProperties) {
      return new GraphProjectionReconciler(
          source,
          inspector,
          synchronizer,
          store,
          neo4jProperties.maxAuthorsPerWorkForMaterializedEdge(),
          projectionProperties.consumerId());
    }

    /** 与同步器里的默认等待一致：被中断时恢复中断标记，而不是把中断吞掉。 */
    private static void sleep(long millis) {
      if (millis <= 0) {
        return;
      }
      try {
        Thread.sleep(millis);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("图投影重试等待被中断", interrupted);
      }
    }
  }
}
