package com.cognitanexus.core.platform.security;

/** 角色与能力名称常量。角色表达身份，能力表达具体操作许可。 */
public final class RoleNames {

  public static final String USER = "USER";
  public static final String REVIEWER = "REVIEWER";
  public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

  /** 系统任务主体：用于采集治理等无人类操作者的场景，审计中呈现为 SYSTEM。 */
  public static final String SYSTEM = "SYSTEM";

  // 能力名称（编码规范 §4.2：Controller 不判断业务授权，能力在应用层校验）
  public static final String CAP_CLAIM_REVIEW = "claim:review";
  public static final String CAP_CLAIM_SUBMIT = "claim:submit";
  public static final String CAP_PORTAL_MAINTAIN = "portal:maintain";
  public static final String CAP_CHANGE_REVIEW = "change:review";
  public static final String CAP_SOURCE_MANAGE = "source:manage";
  public static final String CAP_GOVERNANCE_MANAGE = "governance:manage";
  public static final String CAP_AUDIT_READ = "audit:read";
  public static final String CAP_ACCOUNT_MANAGE = "account:manage";
  public static final String CAP_ACHIEVEMENT_CONFIRM = "achievement:confirm";

  /** 自助提交成果：任何登录用户都有，但提交件在审核通过前不是成果。 */
  public static final String CAP_SUBMISSION_SUBMIT = "submission:submit";

  /** 审核他人提交的成果：与「认领审核」分开，因为它决定一条新记录会不会进入公开目录。 */
  public static final String CAP_SUBMISSION_REVIEW = "submission:review";

  private RoleNames() {}
}
