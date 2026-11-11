package com.cognitanexus.core.notification.domain;

import java.time.Instant;

/**
 * 一条站内通知。
 *
 * <p>依据：ADR-003。不可变，只能追加与标记已读；不存在「编辑通知」的业务动作。
 */
public record Notification(
    String id,
    String accountId,
    String kind,
    String title,
    String body,
    String refType,
    String refId,
    Instant createdAt,
    Instant readAt) {

  /** kind 的合法取值。写成常量而不是枚举：写入点分散在四个业务模块，枚举会让它们反向依赖本模块。 */
  public static final String KIND_CLAIM_DECIDED = "CLAIM_DECIDED";

  public static final String KIND_PORTAL_CHANGE_DECIDED = "PORTAL_CHANGE_DECIDED";
  public static final String KIND_ACHIEVEMENT_MERGED = "ACHIEVEMENT_MERGED";
  public static final String KIND_RELATION_DECIDED = "RELATION_DECIDED";
  public static final String KIND_SOURCE_LICENSE_REVOKED = "SOURCE_LICENSE_REVOKED";
  public static final String KIND_SOURCE_LICENSE_RESTORED = "SOURCE_LICENSE_RESTORED";

  /** 自助提交的成果被裁决。与认领/变更的裁决分开：接收端要跳到「我的提交」而不是门户页。 */
  public static final String KIND_SUBMISSION_DECIDED = "SUBMISSION_DECIDED";

  /**
   * 管理员发布的系统公告。
   *
   * <p>与其他 kind 的区别不只是文案：它的 refType 固定是 ANNOUNCEMENT，refId 指向发布台账，
   * 前端据此把公告渲染成可展开的整段正文，而不是一条「某某事情有结论了」的短通知。
   */
  public static final String KIND_SYSTEM_ANNOUNCEMENT = "SYSTEM_ANNOUNCEMENT";
}
