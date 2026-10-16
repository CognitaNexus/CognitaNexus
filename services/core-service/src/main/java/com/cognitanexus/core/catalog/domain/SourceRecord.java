package com.cognitanexus.core.catalog.domain;

import com.cognitanexus.sharedkernel.DataNature;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 原始来源版本。
 *
 * <p>依据：架构 V0.2 §5.2、§6.2。原值只追加，幂等重复不改原值。
 *
 * <p>三个时间语义不同，不得互相替代：
 *
 * <ul>
 *   <li>{@code sourceUpdatedAt}：来源侧记录更新时间；
 *   <li>{@code sourcePublishedAt}：来源侧发表日期；
 *   <li>{@code fetchedAt}：本次抓取时刻；
 *   <li>{@code receivedAt}：核心服务持久接收时刻（由数据库写入，不由客户端给出）。
 * </ul>
 *
 * <p>抓取时刻不参与指纹计算：否则每次重试都会产生新指纹，重复导入会被当成新事实。
 */
public record SourceRecord(
    String id,
    String sourceId,
    String sourceRecordId,
    String contentFingerprint,
    String payloadJson,
    DataNature dataNature,
    Instant sourceUpdatedAt,
    LocalDate sourcePublishedAt,
    Instant fetchedAt,
    Instant receivedAt,
    long catalogRevision) {

  /** 稳定交接键。 */
  public String handoffKey() {
    return sourceId + "|" + sourceRecordId + "|" + contentFingerprint;
  }
}
