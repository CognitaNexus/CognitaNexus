package com.cognitanexus.query.network.application;

import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.query.graph.domain.GraphFilterCriteria;
import com.cognitanexus.query.network.config.NetworkGraphQueryProperties;
import com.cognitanexus.query.network.domain.GraphParityComparisonResult;
import com.cognitanexus.query.network.domain.GraphProjectionStatus;
import com.cognitanexus.query.network.domain.GraphQueryMode;
import com.cognitanexus.query.network.domain.ParityDiffReport;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 合作网络查询路由：在 PostgreSQL 权威实现与图投影实现之间切换。
 *
 * <p>依据：ADR-005 §6.1（公开资格与版本屏障仍由 PG/core 决定）、§7（降级而不是猜）、§8.1（一键回 PG）。
 * 本类是公开网络端点的唯一应用入口，控制器只做协议转换。
 *
 * <h2>四条不变式</h2>
 *
 * <ol>
 *   <li><b>屏障先于一切</b>：每次请求只调用一次 {@link PublicBarrierGuard#assertPubliclyServed()}，
 *       并把同一个快照交给两条实现。这样 {@code publishedRevision}/{@code graphVersion} 在响应的两
 *       侧含义相同，对照才有意义；
 *   <li><b>绝不静默返回落后结果</b>：图模式必须先用同一快照的『已发布修订 + 活动代』做水位判定；
 *       落后、无水位、换代不一致、schema 未就绪都显式计数并回退。图库异常同样回退；
 *   <li><b>业务规模问题不降级</b>：{@code GRAPH_SCOPE_TOO_LARGE} 与 {@code GRAPH_TIMEOUT} 直接抛给
 *       调用方（ADR-005 §7）。降级会让同一请求在大图上悄悄变成慢查询，掩盖真实规模；
 *   <li><b>严格模式不假装成功</b>：{@code NEO4J_STRICT} 下任何降级条件都变成 503 错误，
 *       确保验收不会在一条没走图库的路径上「通过」。
 * </ol>
 *
 * <h2>输入语义与 PG 保持一致</h2>
 *
 * <p>空种子/空端点（{@code scholarId=""}、{@code from=""/to=""}）不是参数错误，而是「该范围内没有
 * 这个人」。PG 实现据此返回空图或无路径，而图用例层会把它当 {@code INVALID_ARGUMENT}。若按模式
 * 分支处理，同一请求换个模式就会从「空结果」变成「400」——因此空 ID 一律交给权威路径。
 *
 * <h2>对照模式</h2>
 *
 * <p>{@link GraphQueryMode#SHADOW_COMPARE} 返回 PG 结果，同时在<b>同一屏障快照</b>下执行图查询并
 * 逐字段比较；跳过（图不可用/水位落后）与不一致分别计数，不把跳过计入一致。对照默认异步执行，
 * {@code cn.query.graph.query.shadow-compare-async=false} 时在调用线程内同步执行。
 */
public class NetworkQueryRouter {

  private static final Logger log = LoggerFactory.getLogger(NetworkQueryRouter.class);

  /** 图侧用例抛出的「业务结果」错误码：两条实现本应给出同样的答案，重试 PG 没有意义。 */
  private static final java.util.Set<ErrorCode> NON_INFRASTRUCTURE_ERRORS =
      java.util.EnumSet.of(
          ErrorCode.INVALID_ARGUMENT, ErrorCode.GRAPH_SCOPE_TOO_LARGE, ErrorCode.GRAPH_TIMEOUT);

  private final NetworkService postgres;
  private final PublicBarrierGuard barrierGuard;
  private final NetworkGraphQueryProperties properties;
  private final NetworkGraphQueryMetrics metrics;
  private final NetworkParityComparator comparator;
  private final GraphNetworkBackend graphBackend;
  private final Executor shadowExecutor;

  public NetworkQueryRouter(
      NetworkService postgres,
      PublicBarrierGuard barrierGuard,
      NetworkGraphQueryProperties properties,
      NetworkGraphQueryMetrics metrics,
      NetworkParityComparator comparator,
      GraphNetworkBackend graphBackend,
      Executor shadowExecutor) {
    this.postgres = postgres;
    this.barrierGuard = barrierGuard;
    this.properties = properties;
    this.metrics = metrics;
    this.comparator = comparator;
    this.graphBackend = graphBackend;
    this.shadowExecutor = shadowExecutor;
    GraphQueryMode configured = properties.queryMode();
    if (graphBackend == null && configured.requiresGraph()) {
      // 严格模式的契约是「必须由图库返回」：缺图库 Bean 时构造即失败，而不是静默退化为 PG 后对
      // 每个请求返回 200。否则一次漏配 cn.query.graph.enabled=true 就能让验收在没走图库的路径上
      // 「通过」，这与 GraphQueryMode#requiresGraph() 的注释直接矛盾。
      throw new IllegalStateException(
          "cn.query.graph.query.mode="
              + configured
              + " 要求图库，但图库客户端未装配（缺 cn.query.graph.enabled=true 或连接配置）："
              + "请打开 cn.query.graph.enabled 并配置图库连接，或把模式改为"
              + GraphQueryMode.NEO4J_WITH_PG_FALLBACK
              + " / "
              + GraphQueryMode.POSTGRES_ONLY
              + "。");
    }
    if (graphBackend == null && configured != GraphQueryMode.POSTGRES_ONLY) {
      // 缺图库 Bean（cn.query.graph.enabled=false）但配置要求图模式：显式退化为 PG，并在启动日志
      // 中说明，而不是让每个请求都去尝试一个不存在的后端。
      log.warn(
          "cn.query.graph.query.mode={} 已配置，但图库客户端未装配（cn.query.graph.enabled=false），"
              + "合作网络查询全部走 PostgreSQL。要启用图查询请同时打开 cn.query.graph.enabled 并配置图库连接。",
          configured);
    }
  }

  /** 自我网络。签名与 {@link NetworkService#neighborhood} 相同，控制器无需感知路由细节。 */
  public Map<String, Object> neighborhood(
      String scholarId,
      int depth,
      int maxNodesRequested,
      String dataNature,
      String topicId,
      String organization) {
    PublicBarrierGuard.BarrierSnapshot barrier = barrierGuard.assertPubliclyServed();
    GraphQueryMode mode = effectiveMode();
    if (mode == GraphQueryMode.POSTGRES_ONLY || scholarId == null || scholarId.isBlank()) {
      metrics.recordPostgresOnly();
      return postgres.neighborhood(
          barrier, scholarId, depth, maxNodesRequested, dataNature, topicId, organization);
    }
    GraphFilterCriteria criteria = new GraphFilterCriteria(dataNature, topicId, organization);
    Supplier<Map<String, Object>> graphQuery =
        () -> graphBackend.neighborhood(barrier, scholarId, depth, maxNodesRequested, criteria);
    Supplier<Map<String, Object>> postgresQuery =
        () ->
            postgres.neighborhood(
                barrier, scholarId, depth, maxNodesRequested, dataNature, topicId, organization);
    String requestKey = neighborhoodKey(barrier, scholarId, depth, maxNodesRequested, criteria);

    if (mode.shadows()) {
      long startedAt = System.nanoTime();
      Map<String, Object> authoritative = postgresQuery.get();
      long postgresNanos = System.nanoTime() - startedAt;
      shadowCompare(barrier, "neighborhood", requestKey, postgresNanos, authoritative, graphQuery);
      return authoritative;
    }
    return serveFromGraph(mode, barrier, "neighborhood", requestKey, graphQuery, postgresQuery);
  }

  /** 最短合作路径。签名与 {@link NetworkService#shortestPath} 相同。 */
  public Map<String, Object> shortestPath(
      String from, String to, String dataNature, String topicId, String organization) {
    PublicBarrierGuard.BarrierSnapshot barrier = barrierGuard.assertPubliclyServed();
    GraphQueryMode mode = effectiveMode();
    if (mode == GraphQueryMode.POSTGRES_ONLY
        || from == null
        || from.isBlank()
        || to == null
        || to.isBlank()) {
      metrics.recordPostgresOnly();
      return postgres.shortestPath(barrier, from, to, dataNature, topicId, organization);
    }
    GraphFilterCriteria criteria = new GraphFilterCriteria(dataNature, topicId, organization);
    Supplier<Map<String, Object>> graphQuery =
        () -> graphBackend.shortestPath(barrier, from, to, criteria);
    Supplier<Map<String, Object>> postgresQuery =
        () -> postgres.shortestPath(barrier, from, to, dataNature, topicId, organization);
    String requestKey = pathKey(barrier, from, to, criteria);

    if (mode.shadows()) {
      long startedAt = System.nanoTime();
      Map<String, Object> authoritative = postgresQuery.get();
      long postgresNanos = System.nanoTime() - startedAt;
      shadowCompare(barrier, "shortestPath", requestKey, postgresNanos, authoritative, graphQuery);
      return authoritative;
    }
    return serveFromGraph(mode, barrier, "shortestPath", requestKey, graphQuery, postgresQuery);
  }

  /**
   * 当前生效的路由模式。图库 Bean 不存在时强制 PG（配置值保留在日志里以便排查）：
   * {@code NEO4J_STRICT} 不会走到这里——它在构造阶段就被拒绝。
   */
  public GraphQueryMode effectiveMode() {
    if (graphBackend == null) {
      return GraphQueryMode.POSTGRES_ONLY;
    }
    return properties.queryMode();
  }

  /** 供运维/测试查看的指标快照。 */
  public Map<String, Object> metricsSnapshot() {
    Map<String, Object> view = new java.util.LinkedHashMap<>(metrics.snapshot());
    view.put("mode", effectiveMode().name());
    view.put("configuredMode", properties.queryMode().name());
    view.put("graphBackendPresent", graphBackend != null);
    return view;
  }

  private Map<String, Object> serveFromGraph(
      GraphQueryMode mode,
      PublicBarrierGuard.BarrierSnapshot barrier,
      String operation,
      String requestKey,
      Supplier<Map<String, Object>> graphQuery,
      Supplier<Map<String, Object>> postgresQuery) {
    GraphProjectionStatus status = readStatus(barrier, operation, requestKey);
    if (!status.graphUsable()) {
      if (mode.requiresGraph()) {
        metrics.recordStrictRejection(status);
        log.error(
            "NEO4J_STRICT 模式拒绝返回结果：operation={}, request={}, {}。",
            operation,
            requestKey,
            status.describe());
        throw strictRejection(status);
      }
      metrics.recordFallback(status);
      log.warn(
          "合作网络图查询已降级到 PostgreSQL：operation={}, request={}, {}。",
          operation,
          requestKey,
          status.describe());
      return postgresQuery.get();
    }
    try {
      Map<String, Object> served = graphQuery.get();
      metrics.recordGraphServed();
      return served;
    } catch (BusinessException ex) {
      if (NON_INFRASTRUCTURE_ERRORS.contains(ex.errorCode())) {
        // 规模/预算/参数错误与存储无关：降级只会把同一个错误换成另一条慢路径。
        throw ex;
      }
      return handleGraphFailure(mode, operation, requestKey, ex, postgresQuery);
    } catch (RuntimeException ex) {
      return handleGraphFailure(mode, operation, requestKey, ex, postgresQuery);
    }
  }

  private Map<String, Object> handleGraphFailure(
      GraphQueryMode mode,
      String operation,
      String requestKey,
      RuntimeException failure,
      Supplier<Map<String, Object>> postgresQuery) {
    metrics.recordGraphErrorFallback();
    if (mode.requiresGraph()) {
      metrics.recordStrictRejection(null);
      log.error(
          "NEO4J_STRICT 模式图查询失败且不允许降级：operation={}, request={}, error={}。",
          operation,
          requestKey,
          failure.getClass().getSimpleName());
      throw new BusinessException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "图查询不可用，NEO4J_STRICT 模式不允许降级到 PostgreSQL，请稍后重试");
    }
    log.warn(
        "图查询失败，已降级到 PostgreSQL：operation={}, request={}, error={}。",
        operation,
        requestKey,
        failure.getClass().getSimpleName());
    return postgresQuery.get();
  }

  private GraphProjectionStatus readStatus(
      PublicBarrierGuard.BarrierSnapshot barrier, String operation, String requestKey) {
    try {
      return graphBackend.status(barrier.publishedRevision(), barrier.activeGeneration());
    } catch (RuntimeException ex) {
      // 判定本身失败（驱动内部错误等）等同于「图库不可用」：不让一次水位探测把请求变成 500。
      log.warn(
          "图投影水位判定失败，按图库不可用处理：operation={}, request={}, error={}。",
          operation,
          requestKey,
          ex.getClass().getSimpleName());
      return GraphProjectionStatus.metaUnreadable(
          barrier.publishedRevision(), barrier.activeGeneration(), ex.getClass().getSimpleName());
    }
  }

  /**
   * 对照执行。图库不可用 / 水位落后时<b>跳过</b>并单独计数：把跳过记成一致会让对照指标失去意义。
   */
  private void shadowCompare(
      PublicBarrierGuard.BarrierSnapshot barrier,
      String operation,
      String requestKey,
      long postgresNanos,
      Map<String, Object> authoritative,
      Supplier<Map<String, Object>> graphQuery) {
    if (graphBackend == null || shadowExecutor == null) {
      return;
    }
    Runnable task =
        () -> {
          GraphProjectionStatus status = readStatus(barrier, operation, requestKey);
          if (!status.graphUsable()) {
            GraphParityComparisonResult skipped =
                GraphParityComparisonResult.skipped(
                    operation,
                    requestKey,
                    GraphQueryMode.SHADOW_COMPARE,
                    status.reasonCode(),
                    postgresNanos);
            metrics.recordParityComparison(skipped);
            log.info("新旧查询对照跳过：{}（{}）。", skipped.describe(), status.describe());
            return;
          }
          long graphStartedAt = System.nanoTime();
          try {
            Map<String, Object> graphResponse = graphQuery.get();
            long graphNanos = System.nanoTime() - graphStartedAt;
            ParityDiffReport report =
                comparator.compare(operation, requestKey, authoritative, graphResponse);
            GraphParityComparisonResult result =
                GraphParityComparisonResult.compared(
                    operation,
                    requestKey,
                    GraphQueryMode.SHADOW_COMPARE,
                    report,
                    postgresNanos,
                    graphNanos);
            metrics.recordParityComparison(result);
            if (result.matched()) {
              log.debug("新旧查询对照一致：{}。", result.describe());
            } else {
              // 差异必须 WARN 级：静默的对照等于没有对照。
              log.warn("新旧查询对照不一致：{}。", result.describe());
            }
          } catch (BusinessException ex) {
            if (!NON_INFRASTRUCTURE_ERRORS.contains(ex.errorCode())) {
              metrics.recordParityComparison(
                  GraphParityComparisonResult.skipped(
                      operation,
                      requestKey,
                      GraphQueryMode.SHADOW_COMPARE,
                      "GRAPH_ERROR",
                      postgresNanos));
              log.warn(
                  "对照图查询失败，已跳过：operation={}, request={}, error={}。",
                  operation,
                  requestKey,
                  ex.errorCode());
              return;
            }
            metrics.recordParityComparison(
                GraphParityComparisonResult.skipped(
                    operation,
                    requestKey,
                    GraphQueryMode.SHADOW_COMPARE,
                    ex.errorCode().name(),
                    postgresNanos));
            log.info(
                "对照图查询按业务规则拒绝（与规模/预算有关，PostgreSQL 侧也会同样拒绝）：operation={}, request={}, code={}。",
                operation,
                requestKey,
                ex.errorCode());
          } catch (RuntimeException ex) {
            metrics.recordParityComparison(
                GraphParityComparisonResult.skipped(
                    operation,
                    requestKey,
                    GraphQueryMode.SHADOW_COMPARE,
                    "GRAPH_ERROR",
                    postgresNanos));
            log.warn(
                "对照图查询失败，已跳过：operation={}, request={}, error={}。",
                operation,
                requestKey,
                ex.getClass().getSimpleName());
          }
        };
    if (!properties.shadowCompareAsyncEnabled()) {
      // 显式配置 shadow-compare-async=false：在当前线程同步执行对照，便于本地核对与测试直接观察。
      // 对照仍然绝不影响用户拿到的权威结果——同步执行的意外失败也只计数丢弃，不向外抛。
      try {
        task.run();
      } catch (RuntimeException ex) {
        metrics.recordParityDropped();
        log.warn(
            "同步对照任务执行失败，已计数丢弃：operation={}, request={}, error={}。",
            operation,
            requestKey,
            ex.getClass().getSimpleName());
      }
      return;
    }
    try {
      shadowExecutor.execute(task);
    } catch (RuntimeException ex) {
      metrics.recordParityDropped();
      log.warn(
          "对照任务未能提交（执行器拒绝），已计数丢弃：operation={}, request={}, error={}。",
          operation,
          requestKey,
          ex.getClass().getSimpleName());
    }
  }

  private static BusinessException strictRejection(GraphProjectionStatus status) {
    ErrorCode code =
        GraphProjectionStatus.PROJECTION_META_UNREADABLE.equals(status.reasonCode())
            ? ErrorCode.DEPENDENCY_UNAVAILABLE
            : ErrorCode.VIEW_UPDATING;
    return new BusinessException(code, "图查询不可用且当前为 NEO4J_STRICT 模式（不允许降级）：" + status.reason());
  }

  private static String neighborhoodKey(
      PublicBarrierGuard.BarrierSnapshot barrier,
      String scholarId,
      int depth,
      int maxNodesRequested,
      GraphFilterCriteria criteria) {
    return "seed="
        + scholarId
        + ";depth="
        + depth
        + ";maxNodes="
        + maxNodesRequested
        + ";"
        + criteriaKey(criteria)
        + ";rev="
        + barrier.publishedRevision()
        + ";gen="
        + barrier.activeGeneration();
  }

  private static String pathKey(
      PublicBarrierGuard.BarrierSnapshot barrier,
      String from,
      String to,
      GraphFilterCriteria criteria) {
    return "from="
        + from
        + ";to="
        + to
        + ";"
        + criteriaKey(criteria)
        + ";rev="
        + barrier.publishedRevision()
        + ";gen="
        + barrier.activeGeneration();
  }

  private static String criteriaKey(GraphFilterCriteria criteria) {
    return "nature="
        + criteria.dataNatureLabel()
        + ";topic="
        + criteria.topicId()
        + ";org="
        + criteria.organization();
  }
}
