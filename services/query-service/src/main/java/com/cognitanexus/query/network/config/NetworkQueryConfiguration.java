package com.cognitanexus.query.network.config;

import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.query.graph.application.CollaborationGraphService;
import com.cognitanexus.query.graph.application.GraphProjectionInitializationProbe;
import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.config.Neo4jGraphProperties;
import com.cognitanexus.query.graph.schema.Neo4jSchemaInitializer;
import com.cognitanexus.query.network.application.GraphNetworkBackend;
import com.cognitanexus.query.network.application.NetworkGraphQueryMetrics;
import com.cognitanexus.query.network.application.NetworkGraphQueryService;
import com.cognitanexus.query.network.application.NetworkParityComparator;
import com.cognitanexus.query.network.application.NetworkQueryRouter;
import com.cognitanexus.query.network.application.NetworkService;
import com.cognitanexus.query.network.application.ProjectionWatermarkGuard;
import com.cognitanexus.query.network.infrastructure.NetworkMapper;
import com.cognitanexus.query.projection.application.ProjectionProperties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 合作网络查询路由的装配。
 *
 * <p>依据：ADR-005 §6.1、§7、§8.1。这里只装配「路由与对照」这些与存储无关的组件；图库后端本身
 * （Driver、Repository、水位 Guard）以 {@code cn.query.graph.enabled=true} 为条件，因此默认配置下
 * 不存在任何图库 Bean，{@link NetworkQueryRouter} 以 {@code graphBackend == null} 的形式退化为
 * {@code POSTGRES_ONLY}——「什么都不配」与今天的行为逐字节相同。
 *
 * <p>为什么后端用 {@link ObjectProvider} 而不是 {@code @Autowired(required=false)} 字段：路由是
 * 应用层对象，构造参数显式列出说明它到底依赖什么；缺后端是<b>合法状态</b>（图库关闭），不是装配
 * 错误，因此不能要求容器里必须有一个占位实现去伪装它。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NetworkGraphQueryProperties.class)
public class NetworkQueryConfiguration {

  @Bean
  public NetworkGraphQueryMetrics networkGraphQueryMetrics() {
    return new NetworkGraphQueryMetrics();
  }

  @Bean
  public NetworkParityComparator networkParityComparator() {
    return new NetworkParityComparator();
  }

  /**
   * 新旧对照的执行器。
   *
   * <p>单线程 + 有界队列 + 丢弃策略：对照是诊断流量，不能与公开请求争抢线程，也不能因为图库变慢
   * 而无限堆积。队列满时由已注入的指标记录 {@code parityDroppedCount}——「对照覆盖率下降」必须
   * 可见，否则对照看上去一直在跑。
   *
   * <p>线程是 daemon：对照任务不应阻止 JVM 退出。
   */
  @Bean(name = "graphShadowCompareExecutor", destroyMethod = "shutdownNow")
  public ThreadPoolExecutor graphShadowCompareExecutor(
      NetworkGraphQueryProperties properties, NetworkGraphQueryMetrics metrics) {
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            1,
            1,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(properties.shadowCompareQueueCapacity()),
            runnable -> {
              Thread thread = new Thread(runnable, "graph-shadow-compare");
              thread.setDaemon(true);
              return thread;
            },
            (runnable, pool) -> metrics.recordParityDropped());
    // 空闲时不保留线程：默认 POSTGRES_ONLY 下这个执行器从未被使用。
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }

  @Bean
  public NetworkQueryRouter networkQueryRouter(
      NetworkService networkService,
      PublicBarrierGuard publicBarrierGuard,
      NetworkGraphQueryProperties properties,
      NetworkGraphQueryMetrics metrics,
      NetworkParityComparator comparator,
      @Qualifier("graphShadowCompareExecutor") ThreadPoolExecutor shadowExecutor,
      ObjectProvider<GraphNetworkBackend> graphBackend) {
    return new NetworkQueryRouter(
        networkService,
        publicBarrierGuard,
        properties,
        metrics,
        comparator,
        graphBackend.getIfAvailable(),
        shadowExecutor);
  }

  /**
   * 图库版后端。仅在 {@code cn.query.graph.enabled=true} 时装配，与 {@code Neo4jGraphConfiguration}
   * 的条件一致：启用图库却缺连接配置时，那个配置类本身会拒绝启动，不会走到这里。
   */
  @Bean
  @ConditionalOnProperty(prefix = "cn.query.graph", name = "enabled", havingValue = "true")
  public GraphNetworkBackend graphNetworkBackend(
      CollaborationGraphService collaborationGraphService,
      GraphProjectionStore graphProjectionStore,
      GraphProjectionInitializationProbe graphProjectionInitializationProbe,
      Neo4jSchemaInitializer schemaInitializer,
      Neo4jGraphProperties graphProperties,
      NetworkMapper networkMapper,
      ProjectionProperties projectionProperties) {
    ProjectionWatermarkGuard watermarkGuard =
        new ProjectionWatermarkGuard(
            graphProjectionStore,
            schemaInitializer,
            graphProperties,
            graphProjectionInitializationProbe);
    return new NetworkGraphQueryService(
        collaborationGraphService,
        networkMapper,
        projectionProperties,
        graphProperties,
        watermarkGuard);
  }
}
