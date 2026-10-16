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

  /** 可变更字段。 */
  public enum Field {
    RESEARCH_SUMMARY(true),
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
