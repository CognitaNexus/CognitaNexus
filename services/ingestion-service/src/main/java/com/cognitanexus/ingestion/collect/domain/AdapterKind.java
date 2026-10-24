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
  CROSSREF("Crossref", false),
  /** OpenAlex API，面向论文、作者标识与机构归属。 */
  OPENALEX("OpenAlex", false),
  /** NSF Award Search API，面向国家级科研项目（真实项目数据，免密钥）。 */
  NSF("NSF", false),
  /**
   * Nobel Prize API，面向国际学术奖项（真实获奖数据，免密钥）。
   *
   * <p>唯一允许「空查询」的来源：它的翻页语义就是「按获奖人遍历全量名单」（约一千条），
   * 不存在「一个查询串对应一批结果」的结构。其余来源留空会真的去抓整个库，必须拒绝。
   */
  NOBEL("Nobel", true),
  /**
   * Wikidata（SPARQL），面向专利。
   *
   * <p>为什么要单独接一家：专利没有像 Crossref／OpenAlex 那样的通用元数据服务。USPTO ODP 无 key
   * 直接 401，EPO OPS 匿名 403，PatentsView 旧接口已下线；Wikidata 是唯一免密钥、可程序化按属性
   * 投影、且条款允许再分发的来源（CC0）。
   *
   * <p>允许空查询：它的查询串是「附加过滤」而不是「检索词」，留空表示遍历全量专利清单
   * （约一千条），这是专利这一类补数据的正常用法。
   */
  WIKIDATA_PATENT("Wikidata 专利", true),
  /**
   * 文件导入：记录由使用者以 CSV/JSON 文件提供，不经外部抓取。
   *
   * <p>它没有对应的 {@code SourceAdapter}：导入走 {@code ImportService} 直接把记录交给 core，
   * 不适用「按游标翻页抓取」的模型。因此给该来源建采集任务时会被明确拒绝（找不到适配器），
   * 这比让它「跑起来但一条也抓不到」更不容易误解。
   */
  FILE("文件导入", true);

  private final String label;

  private final boolean fullScan;

  AdapterKind(String label, boolean fullScan) {
    this.label = label;
    this.fullScan = fullScan;
  }

  public String label() {
    return label;
  }

  /** 该来源是否允许不带查询串的全量采集。 */
  public boolean supportsFullScan() {
    return fullScan;
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
