package com.cognitanexus.core.catalog.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把多条引用装配成一份可交付的文献集合文件。
 *
 * <p>依据：FR02、ADR-004。单条引用与集合文件不是「同一件事的两种排版」，有两个只有集合才存在的问题：
 *
 * <ol>
 *   <li><b>同一文件内的引用键必须唯一</b>。BibTeX 遇到两个同名条目不会报错，而是让后一条<b>静默
 *       覆盖</b>前一条——参考文献表里少一条，编译是绿的。单条下载时这件事不存在，一旦批量导出就
 *       必须处理，否则「导出成功的文件里少了几条」这种错误没有任何地方会显现。
 *   <li><b>人读格式要成篇</b>。国标的参考文献表按 {@code [1]}、{@code [2]} 编号，APA 是按作者
 *       字母顺序排列的段落、不编号。把单条文本简单堆在一起，得到的是一份不能直接使用的清单。
 * </ol>
 *
 * <p>刻意<b>不写文件头注释</b>（例如「由 CognitaNexus 导出」）。BibTeX 认得 {@code %} 注释，但 RIS
 * 没有任何注释语法，写进去会被解析成损坏的标签；而 ADR-004 已经把「出处不进引用正文」作为决策——
 * 出处与导出时刻随接口字段给出，由界面展示，不混进书目数据本身。
 */
public final class CitationCollectionFormatter {

  private CitationCollectionFormatter() {}

  /**
   * 为一组成果分配文件内唯一的引用键，顺序与入参一致。
   *
   * <p>规则：<b>第一次出现的键保持原样</b>，之后的重复键依次追加 {@code a}、{@code b}……（超过 26 个
   * 用 {@code aa}、{@code ab}）。不改第一个，是因为读者可能已经在自己的 .bib 里用过那个键，改它等于
   * 让引用悬空；而后来者是本文件里新增的条目，加后缀不影响任何既有引用。
   *
   * <p>后缀只由「出现顺序」决定，不掺随机数或时间戳：同一批输入必须得到同一份文件，否则同一个
   * 检索结果导两次，两次的键不同，读者的 .bib 里会积出重复条目。
   */
  public static List<String> uniqueCiteKeys(List<CitationSubject> subjects) {
    List<String> keys = new ArrayList<>(subjects.size());
    if (subjects.isEmpty()) {
      return keys;
    }
    Set<String> taken = new LinkedHashSet<>();
    for (CitationSubject subject : subjects) {
      String base = CitationFormatter.citeKey(subject);
      String key = base;
      int attempt = 1;
      while (!taken.add(key)) {
        key = base + suffix(attempt);
        attempt++;
      }
      keys.add(key);
    }
    return List.copyOf(keys);
  }

  /**
   * 把已渲染的条目文本装配成整份文件。
   *
   * <p>四个格式的间隔与编号按各自规范：BibTeX／RIS 之间空一行（RIS 的 {@code ER  - } 之后必须是新
   * 条目，空行是文献管理软件通行的分隔），国标逐条编号，APA 逐段不编号。
   *
   * <p>入参是<b>未编号</b>的单条文本：编号属于「这份文件里的第几条」，不属于这一条文献本身，
   * 因此由本方法统一加，条目本身保持与单条导出逐字一致。
   */
  public static String document(List<String> entryTexts, CitationFormat format) {
    if (entryTexts == null || entryTexts.isEmpty()) {
      return "";
    }
    List<String> kept = new ArrayList<>(entryTexts.size());
    for (String text : entryTexts) {
      if (text != null && !text.isBlank()) {
        kept.add(text);
      }
    }
    if (kept.isEmpty()) {
      return "";
    }
    return switch (format) {
      case BIBTEX, RIS -> String.join("\n", kept);
      case GB_T_7714 -> numbered(kept);
      case APA -> String.join("\n\n", kept);
    };
  }

  private static String numbered(List<String> entryTexts) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < entryTexts.size(); i++) {
      if (i > 0) {
        out.append('\n');
      }
      out.append('[').append(i + 1).append("] ").append(entryTexts.get(i));
    }
    return out.toString();
  }

  /** 1 → {@code a}，26 → {@code z}，27 → {@code aa}：与「多字母计数」的习惯一致。 */
  private static String suffix(int ordinal) {
    StringBuilder out = new StringBuilder();
    int value = ordinal;
    while (value > 0) {
      value--;
      out.insert(0, (char) ('a' + value % 26));
      value /= 26;
    }
    return out.toString();
  }
}
