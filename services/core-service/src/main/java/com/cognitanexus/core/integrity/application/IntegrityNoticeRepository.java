package com.cognitanexus.core.integrity.application;

import com.cognitanexus.core.integrity.domain.IntegrityNotice;

/**
 * 学术诚信声明的持久化出站端口。
 *
 * <p>依据：编码规范 §2.2。端口在应用层定义、由基础设施实现，应用层不感知 MyBatis。
 *
 * <p><b>没有 update 与 delete。</b>声明只追加、只撤销。撤销是一条独立的写操作（带撤销人、时间、
 * 理由），而不是把行改回某个「初始状态」——那样一来「曾被撤稿」这段事实就没有地方保存了。
 */
public interface IntegrityNoticeRepository {

  void insert(IntegrityNotice notice);

  java.util.Optional<IntegrityNotice> findById(String id);

  /** 某条成果的全部声明（含已撤销），按登记时间倒序。 */
  java.util.List<IntegrityNotice> findByAchievement(String achievementId);

  /** 某条成果某类生效声明；唯一索引保证至多一条，用于「重复登记」的前置判断。 */
  java.util.Optional<IntegrityNotice> findActive(String achievementId, String noticeTypeCode);

  /**
   * 撤销一条生效声明，返回受影响行数。
   *
   * <p>SQL 内自带 {@code status = 'ACTIVE'} 条件：两个人同时撤销同一条声明时，第二个请求影响 0 行，
   * 由调用方按版本冲突返回，而不是静默覆盖第一次的撤销理由。
   */
  int revoke(String id, String revokedBy, java.time.Instant revokedAt, String reason);

  /** 治理台按时间查看最近声明。{@code activeOnly} 为真时只返回生效声明。 */
  java.util.List<IntegrityNotice> listRecent(int limit, boolean activeOnly);
}
