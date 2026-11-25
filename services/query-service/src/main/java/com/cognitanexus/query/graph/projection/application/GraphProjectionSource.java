package com.cognitanexus.query.graph.projection.application;

import com.cognitanexus.query.graph.projection.domain.GraphProjectedWork;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 图投影的 PG 数据源端口（ADR-005 §5）。
 *
 * <p>所有方法读的都是 {@code query_db} 的<b>读投影</b>（{@code qry_achievement}、
 * {@code qry_contribution}、{@code qry_achievement_topic}、{@code qry_contribution_org}、
 * {@code qry_inbox_event}、{@code qry_consumer_state}），而不是 core 权威库：图是
 * 「已经通过公开资格与版本屏障过滤的结果」的第二份表示，直接从 core 拉会让图包含尚未通过
 * 屏障审定的数据，这正是 ADR-005 要避免的第二套判断。
 *
 * <p>为什么端口里既有「按游标分页」又有「按 ID 批量」：
 *
 * <ul>
 *   <li>分页用于 Backfill——必须能在任意位置中断并从该处继续；
 *   <li>按 ID 批量用于事件增量与定向修复——事件只带来少量成果标识，按 ID 取回完整四表视图
 *       才能保证 {@code topic_ids}/{@code organizations} 与 PG 集合一致（ADR-005 §6.2）。
 * </ul>
 */
public interface GraphProjectionSource {

  /**
   * 当前投影阶段：PG 的活动 generation 与已发布修订。
   *
   * @param generation {@code qry_consumer_state.active_generation}
   * @param publishedRevision {@code qry_consumer_state.published_revision}——图水位必须追平它才可用
   */
  record Stage(String generation, long publishedRevision) {}

  /** 一条已被 PG 投影应用（{@code APPLIED}）的事件，供图投影增量消费。 */
  record AppliedEvent(
      String eventId, String eventType, long catalogRevision, Map<String, Object> payload) {}

  /**
   * 合格参与关系的最小视图：仅用于对账时在 Java 侧重算「按阈值应物化的作者对」。
   *
   * <p>只带两端标识而不带关系状态：能出现在这里的关系已经按 {@code AUTO/SELF_CONFIRMED}
   * 过滤过，把状态一起传出去会诱导调用方「再按状态过滤一次」，于是同一规则又散成两处。
   */
  record ContributionView(String achievementId, String scholarId) {}

  /** 读取活动 generation 与已发布修订；缺行时返回 empty（尚未初始化）。 */
  Optional<Stage> readStage(String consumerId);

  /**
   * 按 {@code work_id} 稳定升序分页读取一代成果（含不合格成果）。
   *
   * <p>包含不合格成果是有意的：回填必须为它们留下墓碑并清掉物化边，否则「一次重建后仍留着
   * 已撤销成果的合作边」会以「重建也修不好」的形式出现，而调用方无法从数据上分辨。
   *
   * @param afterWorkId 游标；null/空表示从第一页开始
   */
  List<GraphProjectedWork> pageWorks(String generation, String afterWorkId, int limit);

  /** 按标识批量取回完整视图（四表拼装）；不存在的标识不出现在结果中。 */
  List<GraphProjectedWork> loadWorks(String generation, Collection<String> workIds);

  /** 单条成果；不存在时 empty。 */
  Optional<GraphProjectedWork> loadWork(String generation, String workId);

  /** 合格公开成果（PUBLIC + EFFECTIVE，可选数据性质）。 */
  long countEligibleWorks(String generation, String dataOrigin);

  /** 合格公开成果中关系合格（AUTO / SELF_CONFIRMED）的参与关系数。 */
  long countQualifiedContributions(String generation, String dataOrigin);

  /** 上述参与关系涉及的学者数（distinct）。 */
  long countContributors(String generation, String dataOrigin);

  /**
   * 按物化阈值计算应存在的作者对总数：{@code sum(n*(n-1)/2)}，只统计作者数在
   * {@code [2, threshold]} 内的成果。与 {@code Neo4jGraphProjectionStore} 的物化规则同源。
   */
  long countMaterializedPairs(String generation, int threshold, String dataOrigin);

  /** 合格公开成果标识分页（对账扫描用）。 */
  List<String> pageEligibleWorkIds(
      String generation, String afterWorkId, int limit, String dataOrigin);

  /** 指定成果的合格参与关系（对账时在 Java 侧重算作者对，避免 SQL 里再写一份阈值规则）。 */
  List<ContributionView> loadQualifiedContributions(String generation, Collection<String> workIds);

  /** 该修订之后已应用的事件，按 (catalog_revision, id) 升序。 */
  List<AppliedEvent> listAppliedEventsAfter(String consumerId, long revision, int limit);

  /** 学者参与的成果标识（{@code SCHOLAR_MERGED} 类事件需要据此重写成果）。 */
  List<String> achievementIdsOfScholar(String generation, String scholarId);
}
