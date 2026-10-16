package com.cognitanexus.core.catalog.domain;

/**
 * 字段级溯源。
 *
 * <p>依据：架构 V0.2 §5.4、BR-IMP-04、AC-IMPL-FR10-01。原值、人工值与已审核值分别追溯：
 * 自动更新只写 SOURCE 级；人工生效值不被来源的新值覆盖。
 */
public record FieldProvenance(
    String id,
    String entityType,
    String entityId,
    String fieldName,
    ValueLevel valueLevel,
    String valueText,
    String sourceRecordId) {

  /** 值级别。 */
  public enum ValueLevel {
    /** 由来源记录自动写入。 */
    SOURCE,
    /** 由人工在门户提交、待审核。 */
    MANUAL,
    /** 已审核生效，自动更新不得覆盖。 */
    REVIEWED;

    public static ValueLevel of(String raw) {
      for (ValueLevel value : values()) {
        if (value.name().equals(raw)) {
          return value;
        }
      }
      throw new IllegalArgumentException("未知值级别: " + raw);
    }
  }

  /**
   * 来源自动更新是否允许覆盖既有值。
   *
   * <p>只有既有值仍是 SOURCE 级时才允许被来源新值替换；MANUAL/REVIEWED 一律保留。
   */
  public boolean allowsSourceOverwrite() {
    return valueLevel == ValueLevel.SOURCE;
  }
}
