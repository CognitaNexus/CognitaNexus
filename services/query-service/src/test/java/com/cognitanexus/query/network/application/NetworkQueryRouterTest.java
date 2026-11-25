package com.cognitanexus.query.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.query.graph.domain.GraphFilterCriteria;
import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import com.cognitanexus.query.network.config.NetworkGraphQueryProperties;
import com.cognitanexus.query.network.domain.GraphParityComparisonResult;
import com.cognitanexus.query.network.domain.GraphProjectionStatus;
import com.cognitanexus.query.network.domain.GraphQueryMode;
import com.cognitanexus.query.network.domain.ParityDiffReport;
import com.cognitanexus.query.network.infrastructure.NetworkMapper;
import com.cognitanexus.query.network.infrastructure.NetworkMapper.EdgeRow;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 查询路由：四种模式、水位保护、降级与严格模式。
 *
 * <p>这些用例逐条锁住 ADR-005 §6.1/§7/§8.1 的可执行语义：
 *
 * <ul>
 *   <li>默认模式与图库关闭时，行为与今天完全一致（PG 是唯一出口）；
 *   <li>对照模式不改变用户拿到的结果，只记录一致/不一致/跳过；
 *   <li>水位落后、无水位、换代不一致、schema 未就绪、图库异常都必须回退并计数；
 *   <li>规模/预算类业务错误不得降级（降级会把同一错误换成一条慢路径，掩盖真实规模问题）；
 *   <li>严格模式任何降级条件都显式失败，验收不会在没走图库的路径上「通过」。
 * </ul>
 */
class NetworkQueryRouterTest {

  private List<EdgeRow> edges;
  private NetworkMapper mapper;
  private NetworkService postgres;
  private NetworkGraphQueryService graphService;
  private FakeGraphBackend backend;
  private NetworkGraphQueryMetrics metrics;
  private PublicBarrierGuard barrierGuard;

  @BeforeEach
  void setUp() {
    edges =
        List.of(
            edge("s", "b", 1),
            edge("b", "t", 2),
            edge("s", "c", 1),
            edge("c", "t", 1),
            edge("t", "u", 1));
    mapper = NetworkTestFixtures.mapper(edges);
    postgres = NetworkTestFixtures.postgres(mapper);
    graphService = NetworkTestFixtures.graphQueryService(edges, mapper, true);
    backend = new FakeGraphBackend(graphService);
    metrics = new NetworkGraphQueryMetrics();
    barrierGuard = mock(PublicBarrierGuard.class);
    when(barrierGuard.assertPubliclyServed()).thenReturn(NetworkTestFixtures.barrier());
  }

  private NetworkQueryRouter router(GraphQueryMode mode, GraphNetworkBackend graphBackend) {
    return router(mode, graphBackend, Runnable::run);
  }

  private NetworkQueryRouter router(
      GraphQueryMode mode,
      GraphNetworkBackend graphBackend,
      java.util.concurrent.Executor executor) {
    return router(mode, graphBackend, executor, false);
  }

  private NetworkQueryRouter router(
      GraphQueryMode mode,
      GraphNetworkBackend graphBackend,
      java.util.concurrent.Executor executor,
      boolean shadowCompareAsync) {
    return new NetworkQueryRouter(
        postgres,
        barrierGuard,
        new NetworkGraphQueryProperties(mode.name(), shadowCompareAsync, 8),
        metrics,
        new NetworkParityComparator(),
        graphBackend,
        executor);
  }

  /**
   * 断言两次响应的业务字段完全一致。
   *
   * <p>不能直接用 {@code Map.equals}：{@code generatedAt} 是墙钟时间，两次调用必然不同，而对照器
   * 明确把该诊断字段排除在一致性之外——测试也应采用同一条判据，否则「一致」会被时间戳左右。
   */
  private void assertSameResponse(
      String label, Map<String, Object> expected, Map<String, Object> actual) {
    ParityDiffReport report = new NetworkParityComparator().compare(label, label, expected, actual);
    assertThat(report.matched()).as(report.summary()).isTrue();
  }

  private Map<String, Object> pgNeighborhood() {
    return postgres.neighborhood("s", 2, 0, "REAL", null, null);
  }

  private Map<String, Object> pgPath() {
    return postgres.shortestPath("s", "t", "REAL", null, null);
  }

  @Test
  @DisplayName("POSTGRES_ONLY：只走 PG，图后端一次都不调用")
  void postgresOnlyNeverTouchesGraph() {
    Map<String, Object> view =
        router(GraphQueryMode.POSTGRES_ONLY, backend).neighborhood("s", 2, 0, "REAL", null, null);

    assertSameResponse("postgres-only", pgNeighborhood(), view);
    assertThat(backend.neighborhoodCalls).isZero();
    assertThat(metrics.snapshot()).containsEntry("postgresOnlyQueries", 1L);
  }

  @Test
  @DisplayName("图库 Bean 缺失时即使配了图模式也退化 PG，并在指标里如实说明")
  void missingBackendFallsBackToPostgresOnly() {
    NetworkQueryRouter router = router(GraphQueryMode.NEO4J_WITH_PG_FALLBACK, null);

    assertThat(router.effectiveMode()).isEqualTo(GraphQueryMode.POSTGRES_ONLY);
    assertSameResponse(
        "missing-backend",
        postgres.neighborhood("s", 1, 0, "REAL", null, null),
        router.neighborhood("s", 1, 0, "REAL", null, null));
    assertThat(router.metricsSnapshot())
        .containsEntry("configuredMode", "NEO4J_WITH_PG_FALLBACK")
        .containsEntry("graphBackendPresent", false);
  }

  @Test
  @DisplayName("图库 Bean 缺失时 NEO4J_STRICT 构造即失败：不允许静默走 PG 返回 200")
  void strictModeWithoutGraphBackendFailsFast() {
    assertThatThrownBy(() -> router(GraphQueryMode.NEO4J_STRICT, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NEO4J_STRICT")
        .hasMessageContaining("cn.query.graph.enabled");
    // 失败发生在构造阶段：一条请求都没被执行，图后端调用数为零，指标也没有任何记录。
    assertThat(backend.neighborhoodCalls).isZero();
    assertThat(backend.pathCalls).isZero();
    assertThat(metrics.snapshot())
        .containsEntry("postgresOnlyQueries", 0L)
        .containsEntry("graphServedQueries", 0L);
  }

  @Test
  @DisplayName("SHADOW_COMPARE：返回 PG 结果，同时记录一次一致的对照")
  void shadowCompareReturnsPostgresAndRecordsMatch() {
    Map<String, Object> view =
        router(GraphQueryMode.SHADOW_COMPARE, backend).neighborhood("s", 2, 0, "REAL", null, null);

    assertSameResponse("shadow", pgNeighborhood(), view);
    assertThat(backend.neighborhoodCalls).isEqualTo(1);
    assertThat(metrics.snapshot())
        .containsEntry("parityComparedCount", 1L)
        .containsEntry("parityMatchedCount", 1L)
        .containsEntry("parityMismatchedCount", 0L);
    assertThat(metrics.lastParityComparison().matched()).isTrue();
  }

  @Test
  @DisplayName("对照发现差异：用户仍拿到 PG 结果，但差异被计数并留下明细")
  void shadowCompareMismatchIsCountedButNotServed() {
    backend.mutator =
        response -> {
          response.put("ruleVersion", "NETWORK_V2");
          return response;
        };

    Map<String, Object> view =
        router(GraphQueryMode.SHADOW_COMPARE, backend).neighborhood("s", 2, 0, "REAL", null, null);

    assertThat(view.get("ruleVersion")).isEqualTo("NETWORK_V1");
    assertThat(metrics.snapshot())
        .containsEntry("parityMismatchedCount", 1L)
        .containsEntry("parityMatchedCount", 0L);
    assertThat(metrics.lastParityComparison().report().differences())
        .anySatisfy(difference -> assertThat(difference.field()).isEqualTo("ruleVersion"));
  }

  @Test
  @DisplayName("回归：投影未完成基线初始化时回退 PG（计入 stale 类），严格模式显式 503")
  void notInitializedProjectionFallsBackToPostgres() {
    backend.status =
        GraphProjectionStatus.notInitialized(
            new GraphProjectionMeta("query-public", 0L, 0L, "42", Instant.now()), 0L, "42", "x");

    Map<String, Object> view =
        router(GraphQueryMode.NEO4J_WITH_PG_FALLBACK, backend)
            .neighborhood("s", 2, 0, "REAL", null, null);

    assertSameResponse("not-initialized", pgNeighborhood(), view);
    assertThat(backend.neighborhoodCalls).isZero();
    assertThat(metrics.snapshot())
        .containsEntry("staleProjectionFallbackCount", 1L)
        .containsEntry("graphServedQueries", 0L);

    NetworkGraphQueryMetrics strictMetrics = new NetworkGraphQueryMetrics();
    NetworkQueryRouter strict =
        new NetworkQueryRouter(
            postgres,
            barrierGuard,
            new NetworkGraphQueryProperties(GraphQueryMode.NEO4J_STRICT.name(), false, 8),
            strictMetrics,
            new NetworkParityComparator(),
            backend,
            Runnable::run);
    assertThatThrownBy(() -> strict.neighborhood("s", 1, 0, "REAL", null, null))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        // 503：未初始化属于「视图还不可用」，不是 500，也不是静默返回 200。
        .isEqualTo(ErrorCode.VIEW_UPDATING);
    assertThat(strictMetrics.snapshot()).containsEntry("strictRejections", 1L);
  }

  @Test
  @DisplayName("对照模式在水位落后时跳过图查询并单独计数：跳过不算一致")
  void shadowCompareSkipsWhenProjectionStale() {
    backend.status =
        GraphProjectionStatus.stale(
            new GraphProjectionMeta("query-public", 1L, 1L, "42", Instant.now()), 120L);

    Map<String, Object> view =
        router(GraphQueryMode.SHADOW_COMPARE, backend).neighborhood("s", 1, 0, "REAL", null, null);

    assertSameResponse("shadow-stale", postgres.neighborhood("s", 1, 0, "REAL", null, null), view);
    assertThat(backend.neighborhoodCalls).isZero();
    assertThat(metrics.snapshot())
        .containsEntry("paritySkippedCount", 1L)
        .containsEntry("parityComparedCount", 0L)
        .containsEntry("parityMatchedCount", 0L);
    assertThat(metrics.lastParityComparison().skipReason())
        .isEqualTo(GraphProjectionStatus.PROJECTION_STALE);
  }

  @Test
  @DisplayName("对照模式下图查询失败：跳过并计数，用户仍拿到 PG 结果")
  void shadowCompareSkipsOnGraphError() {
    backend.failure = new IllegalStateException("boom");

    Map<String, Object> view =
        router(GraphQueryMode.SHADOW_COMPARE, backend).shortestPath("s", "t", "REAL", null, null);

    assertSameResponse("shadow-error", postgres.shortestPath("s", "t", "REAL", null, null), view);
    assertThat(metrics.snapshot()).containsEntry("paritySkippedCount", 1L);
    assertThat(metrics.lastParityComparison().skipReason()).isEqualTo("GRAPH_ERROR");
  }

  @Test
  @DisplayName("对照任务被执行器拒绝时计数丢弃，不阻塞公开请求")
  void shadowCompareDropIsCounted() {
    java.util.concurrent.Executor rejecting =
        command -> {
          throw new RejectedExecutionException("full");
        };

    Map<String, Object> view =
        router(GraphQueryMode.SHADOW_COMPARE, backend, rejecting, true)
            .neighborhood("s", 1, 0, "REAL", null, null);

    assertSameResponse(
        "shadow-dropped", postgres.neighborhood("s", 1, 0, "REAL", null, null), view);
    assertThat(metrics.snapshot()).containsEntry("parityDroppedCount", 1L);
  }

  @Test
  @DisplayName("对照模式异步执行：请求返回后对照仍在后台完成")
  void shadowCompareRunsAsynchronously() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      NetworkQueryRouter router = router(GraphQueryMode.SHADOW_COMPARE, backend, executor, true);
      router.neighborhood("s", 2, 0, "REAL", null, null);

      long deadline = System.currentTimeMillis() + 5_000L;
      while (((Number) metrics.snapshot().get("parityComparedCount")).longValue() == 0L
          && System.currentTimeMillis() < deadline) {
        Thread.sleep(10L);
      }
      assertThat(metrics.snapshot()).containsEntry("parityComparedCount", 1L);
      assertThat(backend.lastCallThread).isNotSameAs(Thread.currentThread());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("shadow-compare-async=false：对照在调用线程同步完成，不提交到异步执行器")
  void shadowCompareRunsSynchronouslyWhenAsyncDisabled() {
    java.util.concurrent.Executor mustNotBeUsed =
        command -> {
          throw new AssertionError("shadow-compare-async=false 时不得提交到异步执行器");
        };

    NetworkQueryRouter router =
        router(GraphQueryMode.SHADOW_COMPARE, backend, mustNotBeUsed, false);
    Thread caller = Thread.currentThread();

    Map<String, Object> view = router.neighborhood("s", 2, 0, "REAL", null, null);

    assertSameResponse("shadow-sync", pgNeighborhood(), view);
    // 同步语义：请求一返回对照就已经完成，无需等待；且确实执行在调用线程上。
    assertThat(metrics.snapshot())
        .containsEntry("parityComparedCount", 1L)
        .containsEntry("parityMatchedCount", 1L)
        .containsEntry("parityMismatchedCount", 0L)
        .containsEntry("parityDroppedCount", 0L);
    assertThat(backend.lastCallThread).isSameAs(caller);
  }

  @Test
  @DisplayName("shadow-compare-async=false 也覆盖路径查询，且图查询仍在调用线程同步执行")
  void shadowComparePathRunsSynchronouslyWhenAsyncDisabled() {
    java.util.concurrent.Executor mustNotBeUsed =
        command -> {
          throw new AssertionError("shadow-compare-async=false 时不得提交到异步执行器");
        };

    NetworkQueryRouter router =
        router(GraphQueryMode.SHADOW_COMPARE, backend, mustNotBeUsed, false);
    Thread caller = Thread.currentThread();

    Map<String, Object> view = router.shortestPath("s", "t", "REAL", null, null);

    assertSameResponse("shadow-path-sync", pgPath(), view);
    assertThat(metrics.snapshot())
        .containsEntry("parityComparedCount", 1L)
        .containsEntry("parityMatchedCount", 1L)
        .containsEntry("parityDroppedCount", 0L);
    assertThat(backend.lastCallThread).isSameAs(caller);
  }

  @Test
  @DisplayName("shadow-compare-async=false 且对照器异常：用户仍拿到 PG 结果，异常只计入跳过")
  void synchronousShadowCompareFailureDoesNotAffectResponse() {
    java.util.concurrent.Executor syncExecutor = Runnable::run;

    // 让对照阶段（响应逐字段比较）炸掉：模拟对照器内部意外异常。
    NetworkParityComparator brokenComparator =
        new NetworkParityComparator() {
          @Override
          public ParityDiffReport compare(
              String operation,
              String requestKey,
              Map<String, Object> postgres,
              Map<String, Object> graph) {
            throw new IllegalStateException("comparator broken");
          }
        };
    NetworkQueryRouter brokenRouter =
        new NetworkQueryRouter(
            postgres,
            barrierGuard,
            new NetworkGraphQueryProperties(GraphQueryMode.SHADOW_COMPARE.name(), false, 8),
            metrics,
            brokenComparator,
            backend,
            syncExecutor);

    Map<String, Object> view = brokenRouter.neighborhood("s", 1, 0, "REAL", null, null);

    assertSameResponse(
        "shadow-sync-broken", postgres.neighborhood("s", 1, 0, "REAL", null, null), view);
    // 对照本身的失败绝不能改变权威响应，也不能被记成「一致」。
    assertThat(metrics.snapshot())
        .containsEntry("parityComparedCount", 0L)
        .containsEntry("parityMatchedCount", 0L)
        .containsEntry("paritySkippedCount", 1L);
    assertThat(metrics.lastParityComparison().skipReason()).isEqualTo("GRAPH_ERROR");
  }

  @Test
  @DisplayName("shadow-compare-async=false 且对照记录本身抛异常：请求仍返回 PG 结果，只计数丢弃")
  void synchronousShadowCompareRecordingFailureDoesNotAffectResponse() {
    // 覆盖同步执行的外层防护：跳过分支的指标记录不在 try/catch 内部，若它抛异常，
    // 绝不能让一个诊断旁路把公开请求变成 500。
    NetworkGraphQueryMetrics brokenMetrics =
        new NetworkGraphQueryMetrics() {
          @Override
          public void recordParityComparison(GraphParityComparisonResult result) {
            throw new IllegalStateException("metrics sink broken");
          }
        };
    backend.status =
        GraphProjectionStatus.stale(
            new GraphProjectionMeta("query-public", 119L, 119L, "42", Instant.now()), 120L);
    NetworkQueryRouter brokenRouter =
        new NetworkQueryRouter(
            postgres,
            barrierGuard,
            new NetworkGraphQueryProperties(GraphQueryMode.SHADOW_COMPARE.name(), false, 8),
            brokenMetrics,
            new NetworkParityComparator(),
            backend,
            Runnable::run);

    Map<String, Object> view = brokenRouter.neighborhood("s", 1, 0, "REAL", null, null);

    assertSameResponse(
        "shadow-sync-metrics-broken", postgres.neighborhood("s", 1, 0, "REAL", null, null), view);
    assertThat(brokenMetrics.snapshot())
        .containsEntry("parityComparedCount", 0L)
        .containsEntry("parityDroppedCount", 1L);
  }

  @Test
  @DisplayName("水位落后：回退 PG 并计入 staleProjectionFallbackCount")
  void staleProjectionFallsBackToPostgres() {
    backend.status =
        GraphProjectionStatus.stale(
            new GraphProjectionMeta("query-public", 119L, 119L, "42", Instant.now()), 120L);

    Map<String, Object> view =
        router(GraphQueryMode.NEO4J_WITH_PG_FALLBACK, backend)
            .neighborhood("s", 2, 0, "REAL", null, null);

    assertSameResponse(
        "stale-fallback", postgres.neighborhood("s", 2, 0, "REAL", null, null), view);
    assertThat(backend.neighborhoodCalls).isZero();
    assertThat(metrics.snapshot())
        .containsEntry("staleProjectionFallbackCount", 1L)
        .containsEntry("graphServedQueries", 0L);
    @SuppressWarnings("unchecked")
    Map<String, Long> byReason = (Map<String, Long>) metrics.snapshot().get("fallbackByReason");
    assertThat(byReason).containsEntry(GraphProjectionStatus.PROJECTION_STALE, 1L);
  }

  @Test
  @DisplayName("没有投影水位记录：按落后处理并回退（尚未回填不是「图是空的」）")
  void missingMetaFallsBackToPostgres() {
    backend.status = GraphProjectionStatus.metaMissing(120L, "42");

    Map<String, Object> view =
        router(GraphQueryMode.NEO4J_WITH_PG_FALLBACK, backend)
            .shortestPath("s", "t", "REAL", null, null);

    assertSameResponse("missing-meta", postgres.shortestPath("s", "t", "REAL", null, null), view);
    assertThat(metrics.snapshot()).containsEntry("staleProjectionFallbackCount", 1L);
  }

  @Test
  @DisplayName("schema 未就绪：回退 PG 并计入 graphUnavailableFallbackCount")
  void schemaNotReadyFallsBackToPostgres() {
    backend.status = GraphProjectionStatus.schemaNotReady(120L, "42");

    router(GraphQueryMode.NEO4J_WITH_PG_FALLBACK, backend)
        .neighborhood("s", 1, 0, "REAL", null, null);

    assertThat(metrics.snapshot()).containsEntry("graphUnavailableFallbackCount", 1L);
  }

  @Test
  @DisplayName("水位读取失败（图库不可达）：回退 PG，不把请求变成 500")
  void unreadableMetaFallsBackToPostgres() {
    backend.status =
        GraphProjectionStatus.metaUnreadable(120L, "42", "GraphStoreUnavailableException");

    Map<String, Object> view =
        router(GraphQueryMode.NEO4J_WITH_PG_FALLBACK, backend)
            .neighborhood("s", 1, 0, "REAL", null, null);

    assertSameResponse(
        "unreadable-meta", postgres.neighborhood("s", 1, 0, "REAL", null, null), view);
    assertThat(metrics.snapshot()).containsEntry("graphUnavailableFallbackCount", 1L);
  }

  @Test
  @DisplayName("水位判定本身抛异常：按图库不可用处理")
  void statusProbeFailureIsTreatedAsUnavailable() {
    backend.statusFailure = new IllegalStateException("probe broken");

    Map<String, Object> view =
        router(GraphQueryMode.NEO4J_WITH_PG_FALLBACK, backend)
            .neighborhood("s", 1, 0, "REAL", null, null);

    assertSameResponse("probe-failure", postgres.neighborhood("s", 1, 0, "REAL", null, null), view);
    assertThat(metrics.snapshot()).containsEntry("graphUnavailableFallbackCount", 1L);
  }

  @Test
  @DisplayName("图查询执行异常（数据损坏等）：回退 PG 并计入 graphErrorFallbackCount")
  void graphRuntimeFailureFallsBackToPostgres() {
    backend.failure = new IllegalStateException("corrupted");

    Map<String, Object> view =
        router(GraphQueryMode.NEO4J_WITH_PG_FALLBACK, backend)
            .neighborhood("s", 1, 0, "REAL", null, null);

    assertSameResponse("graph-error", postgres.neighborhood("s", 1, 0, "REAL", null, null), view);
    assertThat(metrics.snapshot()).containsEntry("graphErrorFallbackCount", 1L);
  }

  @Test
  @DisplayName("规模超限不降级：同一请求在大图上不能悄悄变成 PG 慢查询")
  void scopeTooLargeIsNotFallenBack() {
    backend.failure = new BusinessException(ErrorCode.GRAPH_SCOPE_TOO_LARGE, "范围过大");

    assertThatThrownBy(
            () ->
                router(GraphQueryMode.NEO4J_WITH_PG_FALLBACK, backend)
                    .neighborhood("s", 1, 0, "REAL", null, null))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo(ErrorCode.GRAPH_SCOPE_TOO_LARGE);
    assertThat(metrics.snapshot()).containsEntry("graphErrorFallbackCount", 0L);
  }

  @Test
  @DisplayName("预算超时不降级：PG 侧同样会超预算，重试只是把同一个错误拖慢")
  void timeoutIsNotFallenBack() {
    backend.failure = new BusinessException(ErrorCode.GRAPH_TIMEOUT, "超预算");

    assertThatThrownBy(
            () ->
                router(GraphQueryMode.NEO4J_WITH_PG_FALLBACK, backend)
                    .shortestPath("s", "t", "REAL", null, null))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo(ErrorCode.GRAPH_TIMEOUT);
    assertThat(metrics.snapshot()).containsEntry("graphErrorFallbackCount", 0L);
  }

  @Test
  @DisplayName("NEO4J_WITH_PG_FALLBACK + 水位一致：返回图结果并计数")
  void freshProjectionServesGraph() {
    Map<String, Object> view =
        router(GraphQueryMode.NEO4J_WITH_PG_FALLBACK, backend)
            .neighborhood("s", 2, 0, "REAL", null, null);

    assertSameResponse(
        "graph-served",
        NetworkTestFixtures.graphQueryService(edges, mapper, true)
            .neighborhood(NetworkTestFixtures.barrier(), "s", 2, 0, GraphFilterCriteria.realOnly()),
        view);
    assertThat(metrics.snapshot()).containsEntry("graphServedQueries", 1L);
    assertThat(view.get("graphVersion")).isEqualTo("g42-r120");
  }

  @Test
  @DisplayName("NEO4J_STRICT + 水位一致：返回图结果")
  void strictModeServesGraphWhenFresh() {
    Map<String, Object> view =
        router(GraphQueryMode.NEO4J_STRICT, backend).shortestPath("s", "t", "REAL", null, null);

    assertThat(view.get("path")).isEqualTo(List.of("s", "b", "t"));
    assertThat(metrics.snapshot()).containsEntry("graphServedQueries", 1L);
  }

  @Test
  @DisplayName("NEO4J_STRICT + 水位落后：显式 503 VIEW_UPDATING，不返回旧图也不回退")
  void strictModeRejectsStaleProjection() {
    backend.status =
        GraphProjectionStatus.stale(
            new GraphProjectionMeta("query-public", 119L, 119L, "42", Instant.now()), 120L);

    assertThatThrownBy(
            () ->
                router(GraphQueryMode.NEO4J_STRICT, backend)
                    .neighborhood("s", 1, 0, "REAL", null, null))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo(ErrorCode.VIEW_UPDATING);
    assertThat(backend.neighborhoodCalls).isZero();
    assertThat(metrics.snapshot()).containsEntry("strictRejections", 1L);
  }

  @Test
  @DisplayName("NEO4J_STRICT + 图库不可达：显式 503 DEPENDENCY_UNAVAILABLE")
  void strictModeRejectsUnreadableMeta() {
    backend.status =
        GraphProjectionStatus.metaUnreadable(120L, "42", "GraphStoreUnavailableException");

    assertThatThrownBy(
            () ->
                router(GraphQueryMode.NEO4J_STRICT, backend)
                    .neighborhood("s", 1, 0, "REAL", null, null))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
  }

  @Test
  @DisplayName("NEO4J_STRICT + 图查询异常：显式失败，不静默吞错")
  void strictModeRejectsGraphFailure() {
    backend.failure = new IllegalStateException("corrupted");

    assertThatThrownBy(
            () ->
                router(GraphQueryMode.NEO4J_STRICT, backend)
                    .shortestPath("s", "t", "REAL", null, null))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
  }

  @Test
  @DisplayName("空学者 ID 在全部模式下都走 PG：输入语义不因换存储而改变（空图 vs 400）")
  void blankScholarIdUsesPostgresInGraphModes() {
    for (GraphQueryMode mode :
        List.of(
            GraphQueryMode.SHADOW_COMPARE,
            GraphQueryMode.NEO4J_WITH_PG_FALLBACK,
            GraphQueryMode.NEO4J_STRICT)) {
      Map<String, Object> view =
          router(mode, backend).neighborhood("   ", 1, 0, "REAL", null, null);
      assertThat(view.get("seedInScope")).as(mode.name()).isEqualTo(false);
    }
    assertThat(backend.neighborhoodCalls).isZero();
  }

  @Test
  @DisplayName("空端点路径在全部模式下都走 PG，行为与 PG 一致")
  void blankPathUsesPostgresInGraphModes() {
    Map<String, Object> view =
        router(GraphQueryMode.NEO4J_STRICT, backend).shortestPath("", "", "REAL", null, null);

    assertThat(view.get("reachable")).isEqualTo(false);
    assertThat(backend.pathCalls).isZero();
  }

  @Test
  @DisplayName("复核路径同样支持回退与严格模式")
  void pathRoutingCoversFallbackAndStrict() {
    backend.status =
        GraphProjectionStatus.stale(
            new GraphProjectionMeta("query-public", 0L, 0L, "42", Instant.now()), 120L);

    assertSameResponse(
        "path-stale-fallback",
        postgres.shortestPath("s", "t", "REAL", null, null),
        router(GraphQueryMode.NEO4J_WITH_PG_FALLBACK, backend)
            .shortestPath("s", "t", "REAL", null, null));
    assertThat(metrics.snapshot()).containsEntry("staleProjectionFallbackCount", 1L);

    assertThatThrownBy(
            () ->
                router(GraphQueryMode.NEO4J_STRICT, backend)
                    .shortestPath("s", "t", "REAL", null, null))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("屏障校验失败时路由直接失败：不会绕过公开资格判断")
  void barrierFailurePropagates() {
    when(barrierGuard.assertPubliclyServed())
        .thenThrow(new BusinessException(ErrorCode.VIEW_UPDATING, "视图更新中"));

    assertThatThrownBy(
            () ->
                router(GraphQueryMode.POSTGRES_ONLY, backend)
                    .neighborhood("s", 1, 0, "REAL", null, null))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo(ErrorCode.VIEW_UPDATING);
    assertThat(backend.neighborhoodCalls).isZero();
  }

  /** 可控的图后端：委托真实图装配器，只替换判定、失败注入与响应变异。 */
  private static final class FakeGraphBackend implements GraphNetworkBackend {

    private final NetworkGraphQueryService delegate;
    private GraphProjectionStatus status = GraphProjectionStatus.ready(120L, 120L, "42");
    private RuntimeException failure;
    private RuntimeException statusFailure;
    private UnaryOperator<Map<String, Object>> mutator = UnaryOperator.identity();
    private volatile Thread lastCallThread;
    private int neighborhoodCalls;
    private int pathCalls;

    private FakeGraphBackend(NetworkGraphQueryService delegate) {
      this.delegate = delegate;
    }

    @Override
    public GraphProjectionStatus status(
        long authoritativeRevision, String authoritativeGeneration) {
      if (statusFailure != null) {
        throw statusFailure;
      }
      return status;
    }

    @Override
    public Map<String, Object> neighborhood(
        PublicBarrierGuard.BarrierSnapshot barrier,
        String scholarId,
        int depth,
        int maxNodesRequested,
        GraphFilterCriteria criteria) {
      neighborhoodCalls++;
      lastCallThread = Thread.currentThread();
      if (failure != null) {
        throw failure;
      }
      return mutator.apply(
          delegate.neighborhood(barrier, scholarId, depth, maxNodesRequested, criteria));
    }

    @Override
    public Map<String, Object> shortestPath(
        PublicBarrierGuard.BarrierSnapshot barrier,
        String from,
        String to,
        GraphFilterCriteria criteria) {
      pathCalls++;
      lastCallThread = Thread.currentThread();
      if (failure != null) {
        throw failure;
      }
      return mutator.apply(delegate.shortestPath(barrier, from, to, criteria));
    }
  }

  private static EdgeRow edge(String a, String b, int strength) {
    return NetworkTestFixtures.edge(a, b, strength);
  }
}
