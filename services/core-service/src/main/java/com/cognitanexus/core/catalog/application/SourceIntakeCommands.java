package com.cognitanexus.core.catalog.application;

import com.cognitanexus.sharedkernel.DataNature;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 来源交接契约（ingestion-service → core-service）。
 *
 * <p>依据：架构 V0.2 §6.2。批次为可调工程默认值（初始 100 条、请求体不超过 2 MiB）；
 * 202 仅表示持久接收，不表示治理通过。
 *
 * <p>{@code payload} 是来源适配器给出的规范化记录，字段白名单之外的原始内容不进入本请求：
 * 不以「保留原始响应」为由持久化来源意外返回的密码、私人联系方式或无关个人信息。
 */
public final class SourceIntakeCommands {

  private SourceIntakeCommands() {}

  /** 一个交接批次。 */
  public record SourceBatch(
      String sourceCode, String configVersion, String batchId, List<SourceRecordInput> records) {

    public SourceBatch {
      records = records == null ? List.of() : List.copyOf(records);
    }
  }

  /**
   * 单条来源记录。
   *
   * @param sourceRecordId 来源侧稳定标识
   * @param contentFingerprint 内容指纹；缺省时由核心服务按规范化字段重新计算
   * @param dataNature 数据性质；容量测试合成数据必须显式标为 SIMULATED
   */
  public record SourceRecordInput(
      String sourceRecordId,
      String contentFingerprint,
      Map<String, Object> payload,
      DataNature dataNature,
      Instant sourceUpdatedAt,
      LocalDate sourcePublishedAt,
      Instant fetchedAt) {}

  /** 单条记录的接收结果。 */
  public record RecordReceipt(
      String sourceRecordId,
      String status,
      String achievementId,
      String scholarMatched,
      String message) {

    public RecordReceipt withMessage(String newMessage) {
      return new RecordReceipt(sourceRecordId, status, achievementId, scholarMatched, newMessage);
    }

    public static RecordReceipt accepted(String sourceRecordId, String achievementId) {
      return new RecordReceipt(sourceRecordId, "ACCEPTED", achievementId, null, null);
    }

    public static RecordReceipt attached(
        String sourceRecordId, String achievementId, String message) {
      return new RecordReceipt(sourceRecordId, "ATTACHED", achievementId, null, message);
    }

    public static RecordReceipt duplicate(String sourceRecordId, String achievementId) {
      return new RecordReceipt(
          sourceRecordId, "DUPLICATE", achievementId, null, "相同交接键已接收，返回原接收结果");
    }

    public static RecordReceipt rejected(String sourceRecordId, String message) {
      return new RecordReceipt(sourceRecordId, "REJECTED", null, null, message);
    }
  }

  /** 批次接收结果。 */
  public record BatchReceipt(
      String sourceId,
      String batchId,
      int received,
      int accepted,
      int attached,
      int duplicate,
      int rejected,
      long catalogRevision,
      List<RecordReceipt> receipts) {}
}
