package com.cognitanexus.ingestion.collect.domain;

import java.util.Locale;

/**
 * 来源适配器种类。
 *
 * <p>依据：架构 V0.2 §6.2。每种适配器对应一个真实的外部元数据服务，负责把该服务的字段形态
 * 抹平成 core 侧的规范化记录。此处只列出确实已实现的适配器：留一个没有实现的枚举值会让人误以为
 * 该来源已可采集。
 */
public enum AdapterKind {
  /** Crossref REST API，面向 DOI 注册的期刊与会议论文元数据。 */
  CROSSREF("Crossref"),
  /** OpenAlex API，面向论文、作者标识与机构归属。 */
  OPENALEX("OpenAlex");

  private final String label;

  AdapterKind(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public static AdapterKind of(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
