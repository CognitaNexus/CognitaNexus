package com.cognitanexus.core.catalog.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 引用文本格式。
 *
 * <p>依据：FR02「成果可被正确引用」。四种格式各有实际用途，不是同一种文本的排版变体：
 *
 * <ul>
 *   <li>{@link #BIBTEX}：进 LaTeX 参考文献库，机读。
 *   <li>{@link #RIS}：EndNote／Zotero 等文献管理软件通用交换格式，机读。
 *   <li>{@link #GB_T_7714}：中文期刊投稿与学位论文的国标格式，人读。
 *   <li>{@link #APA}：英文社科等领域的作者—年份制，人读。
 * </ul>
 *
 * <p>不提供 {@code PLAIN} 一类的「纯文本」选项：它既不是任何期刊要求的格式，又会让读者以为
 * 「平台给的那一行可以直接贴进参考文献」，反而比只给标准格式更容易产生格式错误的引用。
 */
public enum CitationFormat {
  BIBTEX("BibTeX", "bib", "application/x-bibtex", true),
  RIS("RIS", "ris", "application/x-research-info-systems", true),
  GB_T_7714("GB/T 7714-2015", "txt", "text/plain", false),
  APA("APA 7th", "txt", "text/plain", false);

  private final String label;
  private final String fileExtension;
  private final String mediaType;
  private final boolean machineReadable;

  CitationFormat(String label, String fileExtension, String mediaType, boolean machineReadable) {
    this.label = label;
    this.fileExtension = fileExtension;
    this.mediaType = mediaType;
    this.machineReadable = machineReadable;
  }

  public String label() {
    return label;
  }

  public String fileExtension() {
    return fileExtension;
  }

  public String mediaType() {
    return mediaType;
  }

  /** 是否给文献管理软件读取。据此决定下载入口的措辞是「导入」还是「粘贴」。 */
  public boolean machineReadable() {
    return machineReadable;
  }

  /**
   * 严格解析，只容忍大小写与连字符的书写差异。
   *
   * <p>接受 {@code bibtex}、{@code BIBTEX}、{@code gbt7714}、{@code gb-t-7714}、{@code gb_t_7714}，
   * 但不接受 {@code bib}、{@code 国标} 之类缩写或别名：猜错的代价是给出一段格式正确而类型不对的
   * 文本，调用方看不出来。
   */
  public static CitationFormat of(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("引用格式不能为空，可选值：" + codes());
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    if ("GBT7714".equals(normalized) || "GB_T_7714_2015".equals(normalized)) {
      return GB_T_7714;
    }
    for (CitationFormat value : values()) {
      if (value.name().equals(normalized)) {
        return value;
      }
    }
    throw new IllegalArgumentException("不支持的引用格式「" + raw + "」，可选值：" + codes());
  }

  /** 全部可选值的规范书写（小写），顺序即展示顺序。 */
  public static List<String> codes() {
    List<String> codes = new ArrayList<>();
    for (CitationFormat value : values()) {
      codes.add(value.name().toLowerCase(Locale.ROOT));
    }
    return codes;
  }
}
