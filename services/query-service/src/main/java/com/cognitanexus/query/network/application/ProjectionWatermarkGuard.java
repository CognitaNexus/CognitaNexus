package com.cognitanexus.query.network.application;

import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.config.Neo4jGraphProperties;
import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import com.cognitanexus.query.graph.schema.Neo4jSchemaInitializer;
import com.cognitanexus.query.network.domain.GraphProjectionStatus;
import java.util.Objects;
import java.util.Optional;

/**
 * 投影水位落后保护（Stale Projection Guard）。
 *
 * <p>依据：ADR-005 §6.1、§7。图库只是派生读投影：它可能落后、可能停在旧代、也可能根本没回填完。
 * 这个类把「能不能拿图结果回答一个公开请求」压缩成一次判定，并且<b>只依赖权威侧给出的
 * 已发布修订</b>——不接受调用方传一个「看起来够新」的版本号。
 *
 * <p>判定顺序是刻意的：
 *
 * <ol>
 *   <li>先看 schema：缺唯一约束的图即使数据完整也不能服务（重复节点会让同一个人出现在网络里两次）；
 *   <li>再看水位记录是否存在：没有记录说明从未回填或作用域配错，此时 {@code projectedVersion}
 *       不能当作 0 参与比较（0 会以「落后」的形式误报原因）；
 *   <li>然后比 generation：PG 重建换代后旧代图即使版本号不小也不可服务；
 *   <li>最后比版本：{@code projectedVersion < publishedRevision} 即落后。
 * </ol>
 *
 * <p>读水位失败（图库不可达、认证失败、驱动异常）不向上抛：降级是正常路径，判定结果里带
 * {@code PROJECTION_META_UNREADABLE} 让调用方计数并回退。{@code NEO4J_STRICT} 下由路由据此拒绝
 * 返回——**不在这里抛异常**，因为同一份判定还要用于对照模式的「跳过」判断。
 */
public class ProjectionWatermarkGuard {

  private final GraphProjectionStore projectionStore;
  private final Neo4jSchemaInitializer schemaInitializer;
  private final Neo4jGraphProperties graphProperties;

  public ProjectionWatermarkGuard(
      GraphProjectionStore projectionStore,
      Neo4jSchemaInitializer schemaInitializer,
      Neo4jGraphProperties graphProperties) {
    this.projectionStore = Objects.requireNonNull(projectionStore, "projectionStore");
    this.schemaInitializer = Objects.requireNonNull(schemaInitializer, "schemaInitializer");
    this.graphProperties = Objects.requireNonNull(graphProperties, "graphProperties");
  }

  /**
   * 判定图库是否可以回答 {@code authoritativeRevision} 这一版的公开查询。
   *
   * @param authoritativeRevision PG 已发布修订；负数按 0 处理
   * @param authoritativeGeneration PG 当前活动代；可为 null（不做 generation 校验）
   */
  public GraphProjectionStatus evaluate(
      long authoritativeRevision, String authoritativeGeneration) {
    long revision = Math.max(authoritativeRevision, 0L);
    if (!schemaInitializer.isReady()) {
      return GraphProjectionStatus.schemaNotReady(revision, authoritativeGeneration);
    }
    Optional<GraphProjectionMeta> meta;
    try {
      meta = projectionStore.readMeta(graphProperties.scope());
    } catch (RuntimeException ex) {
      // 只记录异常类型：日志与降级原因里不放主机名/凭据（错误响应不得包含内部地址）。
      return GraphProjectionStatus.metaUnreadable(
          revision, authoritativeGeneration, ex.getClass().getSimpleName());
    }
    if (meta == null || meta.isEmpty()) {
      return GraphProjectionStatus.metaMissing(revision, authoritativeGeneration);
    }
    GraphProjectionMeta projection = meta.get();
    if (generationMismatch(projection, authoritativeGeneration)) {
      return GraphProjectionStatus.generationMismatch(
          projection, revision, authoritativeGeneration);
    }
    if (projection.behind(revision)) {
      return GraphProjectionStatus.stale(projection, revision, authoritativeGeneration);
    }
    return GraphProjectionStatus.ready(
        projection.projectedVersion(), revision, projection.generation());
  }

  /**
   * generation 比较。
   *
   * <p>只有两侧都给出非空值时才比较：老图可能没有 generation 属性（早期回填），此时按「未知」
   * 处理而不是判定不一致——否则一次升级会让所有查询无谓降级。任一为空都不构成本次拒绝的理由。
   */
  private static boolean generationMismatch(
      GraphProjectionMeta projection, String authoritativeGeneration) {
    String projected = projection.generation();
    return projected != null
        && !projected.isBlank()
        && authoritativeGeneration != null
        && !authoritativeGeneration.isBlank()
        && !projected.equals(authoritativeGeneration);
  }
}
