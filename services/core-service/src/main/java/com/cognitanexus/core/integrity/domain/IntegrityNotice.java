package com.cognitanexus.core.integrity.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 一条学术诚信声明。
 *
 * <p>它记录的是「谁在什么时候、基于什么、对哪条成果做出了什么判断」，而不是成果本身的字段。
 * 因此 {@code reason} 与 {@code declaredBy} 都是必填：一条没有主体也没有理由的「撤稿」标记，
 * 对读者而言与平台擅自下架没有区别。
 *
 * <p>撤销不是删除。{@code status=REVOKED} 的行仍然保留，且必须带撤销人、撤销时间与撤销理由——
 * 事后要能回答「这条声明为什么曾经存在、又为什么被推翻」。
 */
public record IntegrityNotice(
    String id,
    String achievementId,
    IntegrityNoticeType noticeType,
    String status,
    String declaredBy,
    String reason,
    String sourceUrl,
    LocalDate announcedOn,
    String recordedBy,
    Instant recordedAt,
    String revokedBy,
    Instant revokedAt,
    String revokeReason) {

  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_REVOKED = "REVOKED";

  public boolean active() {
    return STATUS_ACTIVE.equals(status);
  }
}
