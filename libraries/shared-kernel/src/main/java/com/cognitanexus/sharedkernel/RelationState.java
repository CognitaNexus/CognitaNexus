package com.cognitanexus.sharedkernel;

/**
 * 学者与成果之间的参与关系状态。多个状态相互独立，不得互相推导。
 *
 * <p>依据：BR-IMP-03、BR-IMP-08、SRS 6.4、架构 V0.2 §5.4。
 *
 * <p>关键约束：只有 {@link #AUTO} 与 {@link #SELF_CONFIRMED} 计入「确定关系」，可用于门户
 * 的确定展示与论文合作建边；{@link #PENDING_VERIFY}、{@link #DISPUTED}、{@link #EXCLUDED}
 * 一律不得生成确定合作边，也不得显示为已确认合作。
 */
public enum RelationState {
  /** 自动关联：由稳定标识与相容证据自动建立，尚未经本人确认。 */
  AUTO,
  /** 本人确认：学者本人确认的参与关系。 */
  SELF_CONFIRMED,
  /** 待核验：证据不足或存在冲突，等待人工或本人处理。 */
  PENDING_VERIFY,
  /** 争议：存在竞争性主张，等待人工结论。 */
  DISPUTED,
  /** 已排除：经审核判定该参与关系不成立。 */
  EXCLUDED;

  /** 是否为可用于确定展示与建边的合格关系。 */
  public boolean qualified() {
    return this == AUTO || this == SELF_CONFIRMED;
  }
}
