package com.cognitanexus.query.projection.application;

/**
 * 事件类型与变更种类常量。
 *
 * <p>依据：架构 V0.2 §6.3、编码规范 §3.2。这些字符串是与 core 的跨服务契约的一部分，必须与
 * core 的 {@code EventTypes} 逐字一致。查询侧刻意重新声明而不是共享 core 的常量：共享会让两个
 * 服务通过一个 Java 模块耦合（架构 §4.2 禁止服务间 Java 业务模块依赖），而契约的一致性应由
 * 事件 JSON Schema 与集成测试保证，不由编译器保证。
 *
 * <p>类型名一经发布不得改名；语义变化时提升 schemaVersion，并让本侧按版本分支处理。
 */
public final class QueryEventTypes {

  public static final String CATALOG_CHANGE_COMMITTED = "CatalogChangeCommitted";
  public static final String PUBLIC_VISIBILITY_TIGHTENED = "PublicVisibilityTightened";
  public static final String PORTAL_AUTHORITY_GRANTED = "PortalAuthorityGranted";
  public static final String PORTAL_AUTHORITY_REVOKED = "PortalAuthorityRevoked";
  public static final String CLAIM_DECIDED = "ClaimDecided";
  public static final String SCHOLAR_MERGED = "ScholarMerged";
  public static final String ACHIEVEMENT_MERGED = "AchievementMerged";

  public static final String CHANGE_UPSERT = "UPSERT";

  /** 本侧能处理的最高 schema 版本。更高的版本转隔离待处理，不猜字段含义。 */
  public static final int SUPPORTED_SCHEMA_VERSION = 1;

  private QueryEventTypes() {}
}
