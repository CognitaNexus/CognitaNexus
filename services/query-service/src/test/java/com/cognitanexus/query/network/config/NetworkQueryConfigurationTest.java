package com.cognitanexus.query.network.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.query.graph.application.CollaborationGraphService;
import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.config.Neo4jGraphProperties;
import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import com.cognitanexus.query.graph.schema.Neo4jSchemaInitializer;
import com.cognitanexus.query.network.application.GraphNetworkBackend;
import com.cognitanexus.query.network.application.NetworkGraphQueryMetrics;
import com.cognitanexus.query.network.application.NetworkQueryRouter;
import com.cognitanexus.query.network.application.NetworkService;
import com.cognitanexus.query.network.domain.GraphQueryMode;
import com.cognitanexus.query.network.infrastructure.NetworkMapper;
import com.cognitanexus.query.projection.application.ProjectionProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 路由装配的可执行约束。
 *
 * <p>这里验证的不是「Bean 能不能创建」，而是装配所承载的语义：
 *
 * <ul>
 *   <li>图库后端缺失时装配仍然成功（默认关闭图库是合法状态），路由自行退化 PG；
 *   <li>对照执行器是<b>有界</b>的：队列满时丢弃并计入 {@code parityDroppedCount}，
 *       不允许无限堆积（图库变慢不能拖垮公开查询服务）；
 *   <li>图库后端只在 {@code cn.query.graph.enabled=true} 时注册，注册后其可用性判定与
 *       {@code Neo4jGraphConfiguration} 使用同一套水位语义。
 * </ul>
 */
class NetworkQueryConfigurationTest {

  private final NetworkQueryConfiguration configuration = new NetworkQueryConfiguration();

  @SuppressWarnings("unchecked")
  private static ObjectProvider<GraphNetworkBackend> emptyProvider() {
    ObjectProvider<GraphNetworkBackend> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    return provider;
  }

  @Test
  @DisplayName("图库关闭（无后端 Bean）时路由仍可装配，并退化为 POSTGRES_ONLY")
  void routerAssemblesWithoutGraphBackend() {
    NetworkGraphQueryMetrics metrics = configuration.networkGraphQueryMetrics();
    NetworkQueryRouter router =
        configuration.networkQueryRouter(
            mock(NetworkService.class),
            mock(PublicBarrierGuard.class),
            new NetworkGraphQueryProperties("NEO4J_WITH_PG_FALLBACK", null, null),
            metrics,
            configuration.networkParityComparator(),
            configuration.graphShadowCompareExecutor(
                new NetworkGraphQueryProperties("POSTGRES_ONLY", null, null), metrics),
            emptyProvider());

    assertThat(router.effectiveMode()).isEqualTo(GraphQueryMode.POSTGRES_ONLY);
    assertThat(router.metricsSnapshot()).containsEntry("graphBackendPresent", false);
  }

  @Test
  @DisplayName("对照执行器有界：队列满时丢弃任务并计数，不无界堆积")
  void shadowExecutorIsBoundedAndCountsDrops() throws Exception {
    NetworkGraphQueryMetrics metrics = new NetworkGraphQueryMetrics();
    NetworkGraphQueryProperties properties =
        new NetworkGraphQueryProperties("SHADOW_COMPARE", true, 1);
    ThreadPoolExecutor executor = configuration.graphShadowCompareExecutor(properties, metrics);
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      executor.execute(
          () -> {
            running.countDown();
            try {
              release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
            }
          });
      assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

      // 队列容量 1：第一个进队列，第二个被拒绝。
      executor.execute(() -> {});
      executor.execute(() -> {});

      assertThat(metrics.snapshot()).containsEntry("parityDroppedCount", 1L);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("图库后端启用时：可用性判定沿用同一套水位语义（一致即可用）")
  void graphBackendUsesSameWatermarkSemantics() {
    GraphProjectionStore store = mock(GraphProjectionStore.class);
    when(store.readMeta("query-public"))
        .thenReturn(
            Optional.of(new GraphProjectionMeta("query-public", 120L, 120L, "42", Instant.now())));
    Neo4jSchemaInitializer initializer = mock(Neo4jSchemaInitializer.class);
    when(initializer.isReady()).thenReturn(true);
    Neo4jGraphProperties graphProperties =
        new Neo4jGraphProperties(
            true,
            "bolt://127.0.0.1:7687",
            "neo4j",
            "secret",
            "neo4j",
            Duration.ofSeconds(3),
            Duration.ofSeconds(10),
            20,
            50,
            20_000,
            100_000,
            1_000L,
            "query-public",
            false);
    ProjectionProperties projectionProperties =
        new ProjectionProperties(
            "query", 500, 5, 200, false, 2_000L, 20_000, 100_000, 1_000L, 10_000, 20, 100);

    GraphNetworkBackend backend =
        configuration.graphNetworkBackend(
            mock(CollaborationGraphService.class),
            store,
            initializer,
            graphProperties,
            mock(NetworkMapper.class),
            projectionProperties);

    assertThat(backend.status(120L, "42").graphUsable()).isTrue();
    assertThat(backend.status(121L, "42").graphUsable()).isFalse();
  }
}
