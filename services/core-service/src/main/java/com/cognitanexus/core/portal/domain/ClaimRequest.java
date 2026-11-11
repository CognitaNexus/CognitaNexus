package com.cognitanexus.core.portal.domain;

import java.time.Instant;

/**
 * 认领申请。
 *
 * <p>依据：架构 V0.2 §4.4、AC-IMPL-FR05-02。审批必须原子完成：冲突检查、申请决定、授权、审计与
 * 事件在同一本地事务内提交，不允许「申请已批准但授权没落下」。
 *
 * <p>状态迁移只允许前进：
 *
 * <pre>
 *   PENDING_REVIEW ──补证──▶ NEED_MORE_EVIDENCE ──重新提交──▶ PENDING_REVIEW
 *          │                        │
 *          ├──批准──▶ APPROVED       └──批准──▶ APPROVED
 *          ├──驳回──▶ REJECTED
 *          └──争议──▶ DISPUTED
 * </pre>
 */
public record ClaimRequest(
    String id,
    String accountId,
    String scholarId,
    Status status,
    String statement,
    Instant submittedAt,
    Instant decidedAt,
    String decidedBy,
    String decisionReason,
    String idempotencyKey,
    long version) {

  /** 申请状态。 */
  public enum Status {
    PENDING_REVIEW,
    NEED_MORE_EVIDENCE,
    APPROVED,
    REJECTED,
    DISPUTED;

    public static Status of(String raw) {
      for (Status value : values()) {
        if (value.name().equals(raw)) {
          return value;
        }
      }
      throw new IllegalArgumentException("未知认领申请状态: " + raw);
    }

    /** 是否仍可被裁决。 */
    public boolean open() {
      return this == PENDING_REVIEW || this == NEED_MORE_EVIDENCE;
    }
  }
}
