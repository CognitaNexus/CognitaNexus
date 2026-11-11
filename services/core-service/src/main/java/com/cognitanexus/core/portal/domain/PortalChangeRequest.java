package com.cognitanexus.core.portal.domain;

import java.time.Instant;

/**
 * 门户信息变更申请。
 *
 * <p>依据：架构 V0.2 §5.4、AC-IMPL-FR06-01。影响身份/归属或来源事实的字段必须审批；
 * 待审版本绝不覆盖已公开版本——这是本记录与「直接改字段」的根本区别。
 */
public record PortalChangeRequest(
    String id,
    String accountId,
    String scholarId,
    Field field,
    String currentValue,
    String proposedValue,
    boolean requiresReview,
    Status status,
    Instant submittedAt,
    Instant decidedAt,
    String decidedBy,
    String decisionReason,
    long version) {

  /**
   * 可变更字段。
   *
   * <p>为什么研究简介不再需要审核：它是<b>本人对自己工作的自述</b>，不是来源事实，也不是身份归属。
   * 要求每改一次简介都过人工审核，等于让管理员替学者的自我描述背书，而平台恰恰不打算背书
   * （公开页标注「由本人填写」）。身份与归属类字段（机构）仍然必须审核：改错会把他人的成果
   * 挂到别人名下。认领环节已经确认了「谁能改」，这一层不再重复确认。
   */
  public enum Field {
    RESEARCH_SUMMARY(false),
    ORGANIZATION_NAME(true),
    DISPLAY_ORDER(false),
    TOPIC_SET(false);

    private final boolean requiresReview;

    Field(boolean requiresReview) {
      this.requiresReview = requiresReview;
    }

    /** 是否属于「影响身份/归属或来源事实」的字段。 */
    public boolean requiresReview() {
      return requiresReview;
    }

    /** 面向用户的中文标签。通知与页面共用，避免各处手写映射漂移。 */
    public String label() {
      return switch (this) {
        case RESEARCH_SUMMARY -> "研究简介";
        case ORGANIZATION_NAME -> "机构名称";
        case DISPLAY_ORDER -> "成果排序";
        case TOPIC_SET -> "研究领域";
      };
    }

    public static Field of(String raw) {
      for (Field value : values()) {
        if (value.name().equals(raw)) {
          return value;
        }
      }
      throw new IllegalArgumentException("未知门户字段: " + raw);
    }
  }

  /** 变更状态。 */
  public enum Status {
    PENDING_REVIEW,
    APPROVED,
    REJECTED;

    public static Status of(String raw) {
      for (Status value : values()) {
        if (value.name().equals(raw)) {
          return value;
        }
      }
      throw new IllegalArgumentException("未知变更状态: " + raw);
    }
  }
}
