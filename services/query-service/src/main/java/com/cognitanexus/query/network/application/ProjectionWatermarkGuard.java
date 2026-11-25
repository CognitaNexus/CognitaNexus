package com.cognitanexus.query.network.application;

import com.cognitanexus.query.graph.application.GraphProjectionInitializationProbe;
import com.cognitanexus.query.graph.application.GraphProjectionInitializationProbe.InitializationState;
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
 * 已发布修订与活动代</b>——不接受调用方传一个「看起来够新」的版本号。
 *
 * <p>判定顺序是刻意的：
 *
 * <ol>
 *   <li>先看 schema：缺唯一约束的图即使数据完整也不能服务（重复节点会让同一个人出现在网络里两次）；
 *   <li>再看水位记录是否存在：没有记录说明从未回填或作用域配错，此时 {@code projectedVersion}
 *       不能当作 0 参与比较（0 会以「落后」的形式误报原因）；
 *   <li>然后看<b>基线初始化是否完成</b>：{@code projectedVersion == 0} 或 generation 仍是占位值
 *       说明图里还没有一份完整的历史回填，此时即使 {@code publishedRevision == 0} 也<b>不能</b>
 *       把 {@code 0 >= 0} 读成「不落后」；
 *   <li>再比 generation：两侧都必须<b>可判定且严格相等</b>。任一为空或占位值就不构成「同一代」的
 *       证据，按未初始化处理——旧实现「任一方为空即放行」会让一次漏传参数变成整库图结果被当成
 *       当前结果返回；
 *   <li>然后看回填就绪探针（{@link GraphProjectionInitializationProbe}）：存在初始化状态时必须是
 *       {@code COMPLETED}、{@code failedWorks == 0} 且属于当前活动代。有失败成果说明图上少了一批
 *       合作边，而缺边会被读成「这两位不合作」；
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
  private final GraphProjectionInitializationProbe initializationProbe;

  /**
   * @param initializationProbe 回填初始化状态探针；{@code null} 等同于「本部署没有初始化状态记录」，
   *     此时只做元数据完整性判定（版本 &gt; 0、generation 可判定且严格相等）
   */
  public ProjectionWatermarkGuard(
      GraphProjectionStore projectionStore,
      Neo4jSchemaInitializer schemaInitializer,
      Neo4jGraphProperties graphProperties,
      GraphProjectionInitializationProbe initializationProbe) {
    this.projectionStore = Objects.requireNonNull(projectionStore, "projectionStore");
    this.schemaInitializer = Objects.requireNonNull(schemaInitializer, "schemaInitializer");
    this.graphProperties = Objects.requireNonNull(graphProperties, "graphProperties");
    this.initializationProbe = initializationProbe;
  }

  /**
   * 判定图库是否可以回答 {@code authoritativeRevision} 这一版的公开查询。
   *
   * @param authoritativeRevision PG 已发布修订；负数按 0 处理
   * @param authoritativeGeneration PG 当前活动代；为空或占位值时不能证明图属于当前代，判为未初始化
   */
  public GraphProjectionStatus evaluate(
      long authoritativeRevision, String authoritativeGeneration) {
    long revision = Math.max(authoritativeRevision, 0L);
    if (!schemaInitializer.isReady()) {
      return GraphProjectionStatus.schemaNotReady(revision, authoritativeGeneration);
    }
    Optional<GraphProjectionMeta> meta;
    Optional<InitializationState> initialization;
    try {
      // 两次读取放在同一段容错里：探针失败与水位读取失败同属「图库读不到」，处置方式相同
      // （计数 + 回退 PG），不能把探针异常伪装成「未初始化」——那会让人去重跑回填。
      meta = projectionStore.readMeta(graphProperties.scope());
      initialization =
          initializationProbe == null
              ? Optional.empty()
              : initializationProbe.inspect(graphProperties.scope());
    } catch (RuntimeException ex) {
      // 只记录异常类型：日志与降级原因里不放主机名/凭据（错误响应不得包含内部地址）。
      return GraphProjectionStatus.metaUnreadable(
          revision, authoritativeGeneration, ex.getClass().getSimpleName());
    }
    if (meta == null || meta.isEmpty()) {
      return GraphProjectionStatus.metaMissing(revision, authoritativeGeneration);
    }
    GraphProjectionMeta projection = meta.get();
    if (projection.projectedVersion() <= 0L) {
      // 空库上 projectedVersion == publishedRevision == 0：只看「不落后」会把一个从未回填的图判成
      // 可用。版本 0 的含义是「还没有任何成果被完整写入」，它不可能覆盖任何已发布修订。
      return GraphProjectionStatus.notInitialized(
          projection,
          revision,
          authoritativeGeneration,
          "projectedVersion=" + projection.projectedVersion() + " 表示图里还没有任何一个已发布成果被回填完成");
    }
    if (GraphProjectionMeta.isPlaceholderGeneration(projection.generation())
        || GraphProjectionMeta.isPlaceholderGeneration(authoritativeGeneration)) {
      return GraphProjectionStatus.notInitialized(
          projection,
          revision,
          authoritativeGeneration,
          "generation 不可判定或仍是占位值（projectedGeneration="
              + projection.generation()
              + ", authoritativeGeneration="
              + authoritativeGeneration
              + "），无法证明图属于当前活动代");
    }
    String projectedGeneration = projection.generation().trim();
    String activeGeneration = authoritativeGeneration.trim();
    if (!projectedGeneration.equals(activeGeneration)) {
      return GraphProjectionStatus.generationMismatch(projection, revision, activeGeneration);
    }
    if (initialization.isPresent()) {
      String problem = initializationProblem(initialization.get(), activeGeneration);
      if (problem != null) {
        return GraphProjectionStatus.notInitialized(
            projection, revision, activeGeneration, problem);
      }
    }
    if (projection.behind(revision)) {
      return GraphProjectionStatus.stale(projection, revision, activeGeneration);
    }
    return GraphProjectionStatus.ready(
        projection.projectedVersion(), revision, projectedGeneration);
  }

  /**
   * 回填状态是否足以支撑「图已完整初始化」。
   *
   * @return {@code null} 表示通过；否则返回可公开的原因说明
   */
  private static String initializationProblem(InitializationState state, String activeGeneration) {
    if (!state.completed()) {
      return "回填状态为 " + state.status() + "，基线初始化尚未完成";
    }
    if (!state.withoutFailedWorks()) {
      return "回填有 " + state.failedWorks() + " 条成果失败，图上缺少对应的合作边";
    }
    if (!state.belongsTo(activeGeneration)) {
      return "回填状态记录属于 generation " + state.generation() + "，而当前活动代是 " + activeGeneration;
    }
    return null;
  }
}
