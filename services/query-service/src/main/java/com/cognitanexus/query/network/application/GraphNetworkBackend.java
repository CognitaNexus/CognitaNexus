package com.cognitanexus.query.network.application;

import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.query.graph.domain.GraphFilterCriteria;
import com.cognitanexus.query.network.domain.GraphProjectionStatus;
import java.util.Map;

/**
 * 图库版合作网络的后端端口（供 {@link NetworkQueryRouter} 使用）。
 *
 * <p>为什么需要这一层而不是让路由直接依赖 {@code CollaborationGraphService}：
 *
 * <ul>
 *   <li>路由与控制器不得出现 Driver / Session / Cypher 或图库配置类型；端口只暴露本服务自己的
 *       响应结构与判定结论，换成华为云 GES 时替换实现即可（ADR-005 §9）；
 *   <li>图库 Bean 只在 {@code cn.query.graph.enabled=true} 时存在。路由按「端口是否存在」决定能否
 *       使用图模式，缺 Bean 时显式退化为 PG，而不是在字段注入处空指针；
 *   <li>可用性判定（schema 就绪 + 投影水位）与查询放在同一个端口里：如果拆开，调用方就可能
 *       「先查了再用一个已经过期的结论」，而这正是水位保护要避免的。
 * </ul>
 *
 * <p>实现必须<b>只读</b>图库、只按端口返回结果，不自行改写公开资格判断：谁能被看见由
 * {@link PublicBarrierGuard} 与 PG 决定（ADR-005 §6.1）。
 */
public interface GraphNetworkBackend {

  /**
   * 判定当前是否可以由图库回答本次公开查询。
   *
   * @param authoritativeRevision PG 已发布修订（来自同一屏障快照）
   * @param authoritativeGeneration PG 当前活动代
   */
  GraphProjectionStatus status(long authoritativeRevision, String authoritativeGeneration);

  /**
   * 自我网络。参数与 {@code NetworkService#neighborhood} 完全一致，返回同一 HTTP 契约的响应体。
   *
   * @param barrier 本次请求的屏障快照（与 PG 路径共用同一份，保证版本字段一致）
   */
  Map<String, Object> neighborhood(
      PublicBarrierGuard.BarrierSnapshot barrier,
      String scholarId,
      int depth,
      int maxNodesRequested,
      GraphFilterCriteria criteria);

  /** 最短合作路径。参数与 {@code NetworkService#shortestPath} 完全一致。 */
  Map<String, Object> shortestPath(
      PublicBarrierGuard.BarrierSnapshot barrier,
      String from,
      String to,
      GraphFilterCriteria criteria);
}
