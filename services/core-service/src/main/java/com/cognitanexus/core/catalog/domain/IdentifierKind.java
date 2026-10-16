package com.cognitanexus.core.catalog.domain;

/**
 * 稳定标识种类。
 *
 * <p>依据：架构 V0.2 §5.3。注册键为「实体类型 + 标识种类 + 作用域 + 规范值」，
 * 作用域用于区分不同命名空间下的同形标识（如不同国家/体系的专利号）。
 */
public enum IdentifierKind {
  DOI("doi"),
  PATENT_APPLICATION_NO("patent_application_no"),
  PROJECT_NO("project_no"),
  ORCID("orcid"),
  OPENALEX_ID("openalex_id");

  private final String columnNameHint;

  IdentifierKind(String columnNameHint) {
    this.columnNameHint = columnNameHint;
  }

  /** 对应类型扩展表中的归一列名，便于排查标识与扩展列是否同步。 */
  public String columnNameHint() {
    return columnNameHint;
  }

  public static IdentifierKind of(String raw) {
    for (IdentifierKind value : values()) {
      if (value.name().equals(raw)) {
        return value;
      }
    }
    throw new IllegalArgumentException("未知标识种类: " + raw);
  }
}
