package com.cognitanexus.query.graph.application;

import com.cognitanexus.query.graph.domain.GraphAuthorship;
import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import com.cognitanexus.query.graph.domain.GraphScholarNode;
import com.cognitanexus.query.graph.domain.GraphWorkNode;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 图投影写端口（Backfill / Outbox 消费者共用）。
 *
 * <p>幂等与顺序语义（ADR-005 §5）：
 *
 * <ul>
 *   <li>所有写入都带 {@code sourceVersion}（成果聚合版本或 catalog revision）。存储层只接受
 *       <b>不更旧</b>的版本，重复投递与迟到事件不会把新值改回旧值；
 *   <li>{@link #replaceWork} 是「按成果整体替换」：先删该成果的参与边与合作边，再按当前作者
 *       集合重建。同一事件重放两次的结果与一次相同（PG 侧 {@code qry_coauthor_edge} 采用同一策略），
 *       不靠「强度加一」累计；
 *   <li>版本水位 {@link #readMeta}/{@link #advanceMeta} 单独存放，只允许前进。落后时公开查询
 *       必须降级到 PG，不能拿旧图当当前结果。
 * </ul>
 *
 * <p>写入范围同样受业务规则约束：只接受 AUTO / SELF_CONFIRMED 的参与关系；高作者数成果不物化
 * 作者对边（避免组合爆炸），但仍保留参与边供二部图查询与证据追溯。
 */
public interface GraphProjectionStore {

  /**
   * 幂等写入学者节点（MERGE by scholar_id）。返回实际应用的节点数（被版本守卫拒绝的不计）。
   */
  int upsertScholars(Collection<GraphScholarNode> scholars);

  /**
   * 按成果整体替换：成果节点、参与边、物化合作边。
   *
   * @param work 成果节点；{@code isPublic=false} 表示下线（等价于 {@link #retireWork}）
   * @param authorships 合格参与关系；不允许为空集合表示「还没解析完」——那会先删边后重建，
   *     中途查询会看到空图。解析失败的事件应由调用方隔离，不进本方法
   * @return 是否应用（版本比已有值旧时返回 false，不产生任何副作用）
   */
  boolean replaceWork(GraphWorkNode work, List<GraphAuthorship> authorships);

  /**
   * 成果下线：保留节点墓碑（{@code is_public=false}）并删除其物化合作边，不再出现在图查询中。
   *
   * @return 是否应用（版本更旧时返回 false）
   */
  boolean retireWork(String workId, long sourceVersion);

  /**
   * <b>对账专用</b>：删除图里存在、但 PG 中已无对应行的孤立成果节点及其边。
   *
   * <p>与 {@link #retireWork} 的区别是「有没有可信版本可用」：{@code retireWork} 是给 PG 里
   * 仍然存在、只是不再合格的成果留墓碑；这个方法用于 PG 里<b>连行都没有</b>的成果——此时任何
   * 版本号都是凭空捏造，而<b>用</b> {@link Long#MAX_VALUE} 之类的哨兵值退场会造成持久伤害：
   * 该成果日后在 PG 中重新合格并公开时，{@link #replaceWork} 的版本守卫
   * （{@code w.source_version <= $sourceVersion}）会永久拒绝正常版本，图上再也恢复不了。
   *
   * <p>删除后节点回到「从未投影过」的状态，日后重新公开时任何版本都能正常写入。
   *
   * @return 是否真的删除了节点（不存在时返回 false，不视为失败）
   */
  boolean deleteWork(String workId);

  Optional<GraphProjectionMeta> readMeta(String scope);

  /**
   * 推进投影水位，只前进不后退。
   *
   * @return 推进后的实际水位（若传入版本更旧，返回已有水位）
   */
  long advanceMeta(GraphProjectionMeta meta);

  /**
   * <b>运维专用</b>：清空<b>整库</b>并重建指定作用域的水位。
   *
   * <p>方法名保留 {@code All} 是有意的：它不是一个「按作用域局部重建」接口，而是独占实例上的
   * 整库清空。实现必须先用同一事务确认库内不存在其他 {@code GraphProjectionMeta.scope}，
   * 否则抛 {@link IllegalStateException} 并回滚，不得默默删掉其他作用域/环境的图数据。
   *
   * <p>调用前图查询必须已切到 PG 降级路径（ADR-005 §7、§8.2）。
   *
   * @param initialScope 本次重建作用域；空值时使用配置的 {@code cn.query.graph.scope}
   */
  void resetAllForRebuild(String initialScope);
}
