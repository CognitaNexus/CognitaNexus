package com.cognitanexus.core.portal.domain;

import com.cognitanexus.sharedkernel.AchievementType;
import java.time.Instant;

/**
 * 成果自助提交件。
 *
 * <p>依据：需求基线「学者自主提交成果」；SRS 5.2（认领不等于成果确认）、BR-IMP-08（只有合格关系
 * 建边）。这张记录在审核通过之前<b>不是成果</b>：它没有经过发布判定、没有来源记录，也没有参与关系。
 *
 * <p>状态迁移只允许这几条：
 *
 * <pre>
 *   SUBMITTED ──补材料──▶ NEED_MORE_INFO ──重新提交──▶ SUBMITTED
 *       │                      │
 *       ├──批准──▶ APPROVED     └──批准──▶ APPROVED
 *       ├──驳回──▶ REJECTED
 *       └──本人撤回──▶ WITHDRAWN
 * </pre>
 *
 * <p>没有「审核中」这一档：一条提交要么还没被看（SUBMITTED），要么已经被裁决。
 * 加一个中间态只会让「这条到底卡在谁那里」多一个无法回答的分支。
 *
 * @param duplicateOf 查重命中的既有成果标识；只有提示意义，绝不自动合并
 * @param duplicateKind {@code DOI}=同一 DOI 精确命中，{@code TITLE}=题名相似，null=未命中
 */
public record Submission(
    String id,
    String accountId,
    String scholarId,
    AchievementType type,
    String title,
    String titleNorm,
    String doi,
    String doiNorm,
    String venue,
    Integer publicationYear,
    String abstractText,
    String authorsRaw,
    Status status,
    String duplicateOf,
    DuplicateKind duplicateKind,
    String duplicateNote,
    String decisionReason,
    String decidedBy,
    Instant decidedAt,
    String achievementId,
    String idempotencyKey,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  /** 是否还能被本人撤回或被审核裁决。 */
  public boolean open() {
    return status == Status.SUBMITTED || status == Status.NEED_MORE_INFO;
  }

  /** 查重是否给出了提示。注意它不改变状态：命中相似题名不等于重复。 */
  public boolean duplicateSuspected() {
    return duplicateOf != null && duplicateKind != null;
  }

  /** 提交状态。 */
  public enum Status {
    SUBMITTED,
    NEED_MORE_INFO,
    APPROVED,
    REJECTED,
    WITHDRAWN;

    public static Status of(String raw) {
      for (Status value : values()) {
        if (value.name().equals(raw)) {
          return value;
        }
      }
      throw new IllegalArgumentException("未知提交状态: " + raw);
    }

    public boolean open() {
      return this == SUBMITTED || this == NEED_MORE_INFO;
    }
  }

  /** 查重命中方式。只用于提示人工审核，不参与任何自动合并。 */
  public enum DuplicateKind {
    DOI,
    TITLE;

    public static DuplicateKind of(String raw) {
      if (raw == null || raw.isBlank()) {
        return null;
      }
      for (DuplicateKind value : values()) {
        if (value.name().equals(raw)) {
          return value;
        }
      }
      throw new IllegalArgumentException("未知查重方式: " + raw);
    }
  }
}
