package com.cognitanexus.core.platform.event;

/**
 * 事件类型与聚合类型常量。
 *
 * <p>依据：架构 V0.2 §6.3。类型名一经发布不得改名；需要变更语义时提升 schemaVersion 并让
 * 接收端按版本分支处理，而不是原地改字段含义。
 */
public final class EventTypes {

  // 事件类型
  public static final String CATALOG_CHANGE_COMMITTED = "CatalogChangeCommitted";
  public static final String PUBLIC_VISIBILITY_TIGHTENED = "PublicVisibilityTightened";

  /**
   * 公开资格放开（ADR-001 方案 A）。
   *
   * <p>刻意与 {@link #PUBLIC_VISIBILITY_TIGHTENED} 分成两个类型，而不是在同一个类型里用 payload
   * 的 {@code change} 区分方向：两者对消费方的后果相反——一个要求立刻停止展示，一个要求重新拉取；
   * 合成一个类型后，任何一处「只处理了收紧、忘了放开」都会表现为「恢复许可了但页面还是空的」，
   * 而那种缺陷很难在日志里看出来。
   */
  public static final String PUBLIC_VISIBILITY_RESTORED = "PublicVisibilityRestored";

  public static final String PORTAL_AUTHORITY_GRANTED = "PortalAuthorityGranted";
  public static final String PORTAL_AUTHORITY_REVOKED = "PortalAuthorityRevoked";
  public static final String CLAIM_DECIDED = "ClaimDecided";
  public static final String SCHOLAR_MERGED = "ScholarMerged";
  public static final String ACHIEVEMENT_MERGED = "AchievementMerged";

  // 聚合类型
  public static final String AGG_ACHIEVEMENT = "Achievement";
  public static final String AGG_SCHOLAR = "Scholar";
  public static final String AGG_CONTRIBUTION = "Contribution";
  public static final String AGG_PORTAL_AUTHORITY = "PortalAuthority";

  // 变更种类（payload.changeKind）
  public static final String CHANGE_UPSERT = "UPSERT";
  public static final String CHANGE_WITHDRAW = "WITHDRAW";
  public static final String CHANGE_RELATION = "RELATION";

  /** 当前所有事件类型共用的 schema 版本。 */
  public static final int CURRENT_SCHEMA_VERSION = 1;

  private EventTypes() {}
}
