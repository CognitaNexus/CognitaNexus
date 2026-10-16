package com.cognitanexus.core.governance.domain;

import java.time.Instant;
import java.util.Map;

/**
 * 治理异常。
 *
 * <p>依据：架构 V0.2 §6.2、AC-IMPL-FR08-01。异常不直接成为公开成果；待核验与异常数量必须在
 * 任务结果中单列披露，不能把失败伪装成「已处理」。
 */
public record GovernanceAnomaly(
    String id,
    String kind,
    String sourceRecordId,
    String entityId,
    Map<String, Object> detail,
    String status,
    Instant createdAt,
    Instant resolvedAt) {

  /** 异常种类。名称进入数据库，避免使用会被误解为业务结论的描述。 */
  public static final String KIND_STRUCTURE_INVALID = "STRUCTURE_INVALID";

  public static final String KIND_IDENTIFIER_CONFLICT = "IDENTIFIER_CONFLICT";
  public static final String KIND_MISSING_REQUIRED_FIELD = "MISSING_REQUIRED_FIELD";
  public static final String KIND_SOURCE_DISABLED = "SOURCE_DISABLED";
  public static final String KIND_UNMAPPED_CATEGORY = "UNMAPPED_CATEGORY";
}
