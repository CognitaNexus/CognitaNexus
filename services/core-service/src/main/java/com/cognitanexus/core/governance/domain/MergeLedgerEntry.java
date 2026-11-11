package com.cognitanexus.core.governance.domain;

import java.time.Instant;
import java.util.Map;

/**
 * 合并/拆分台账。
 *
 * <p>依据：架构 V0.2 §5.4。必须留下旧 ID 重定向、来源归属与关系前后快照，
 * 使旧链接跳到正确对象而不是指向错误的人或成果。只追加，数据库层另有 REVOKE 兜底。
 *
 * @param snapshot 合并前后的完整快照，含被合并实体的来源归属与参与关系
 */
public record MergeLedgerEntry(
    String id,
    Operation operation,
    String survivorId,
    String mergedId,
    String entityType,
    Map<String, Object> snapshot,
    String reason,
    String operatorId,
    Instant occurredAt) {

  public enum Operation {
    MERGE,
    SPLIT;

    public static Operation of(String raw) {
      for (Operation value : values()) {
        if (value.name().equals(raw)) {
          return value;
        }
      }
      throw new IllegalArgumentException("未知台账操作: " + raw);
    }
  }

  public enum EntityType {
    ACHIEVEMENT,
    SCHOLAR
  }
}
