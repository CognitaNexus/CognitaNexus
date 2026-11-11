package com.cognitanexus.core.catalog.domain;

/**
 * 引用文本的一条局限说明。
 *
 * <p>引用是「别人据此去找原文」的唯一线索，因此<b>平台不能把一个信息不全的引用悄悄交出去</b>：
 * 年份缺失、署名顺序未知、条目已被撤稿，都会让引用者引错或引到一条已不成立的记录。这些情况
 * 必须随文本一起返回，而不是由前端凭经验判断「看起来像不像有问题」。
 *
 * <p>{@code code} 给程序（前端按码选择展示样式、脚本按码断言），{@code message} 给人。
 * 两个字段都永不缺失——三个服务都配了 {@code default-property-inclusion: non_null}，一旦某个字段
 * 为 null，它在响应里就消失，前端拿到 {@code undefined} 后与「服务端漏写」无法区分。
 */
public record CitationWarning(String code, String message) {

  /** 年份未知，文本用占位符而非编造年份。 */
  public static final String YEAR_UNKNOWN = "YEAR_UNKNOWN";

  /** 有署名者的署名顺序未知，文本中的作者先后不代表真实署名顺序。 */
  public static final String AUTHOR_ORDER_UNKNOWN = "AUTHOR_ORDER_UNKNOWN";

  /** 没有任何可用署名者。 */
  public static final String NO_AUTHOR = "NO_AUTHOR";

  /** 部分署名者的归属尚待核验（不影响是否署名，只提示）。 */
  public static final String AUTHOR_PENDING_VERIFY = "AUTHOR_PENDING_VERIFY";

  /** 有参与关系已被治理判为不成立，未计入署名。 */
  public static final String EXCLUDED_DROPPED = "EXCLUDED_DROPPED";

  /** 有参与关系指向的学者档案取不到显示名，无法写入署名。 */
  public static final String AUTHOR_NAME_UNRESOLVED = "AUTHOR_NAME_UNRESOLVED";

  /** 发表载体缺失。 */
  public static final String VENUE_MISSING = "VENUE_MISSING";

  /** 该成果已被撤稿。 */
  public static final String RETRACTED = "RETRACTED";

  /** 该成果登记了更正声明。 */
  public static final String CORRECTED = "CORRECTED";

  /** 该成果登记了关注声明（尚未定性为撤稿或更正）。 */
  public static final String CONCERNED = "CONCERNED";

  /** 数据性质不是真实来源数据。 */
  public static final String DATA_NATURE_NOT_REAL = "DATA_NATURE_NOT_REAL";

  /** 该格式要求拆分「姓／名首字母」，平台不拆，按原显示名整体呈现。 */
  public static final String NAME_SPLIT_NOT_APPLIED = "NAME_SPLIT_NOT_APPLIED";

  /**
   * 批量导出时同一文件内出现重复引用键，已追加后缀。
   *
   * <p>这是「文件级」的局限，不属于任何单条成果：撞键若不管，BibTeX 会让后一条静默覆盖前一条，
   * 参考文献表里少一条且编译不报错。所以宁可改键并明说，也不交出一个「看起来完整」的文件。
   */
  public static final String CITE_KEY_DISAMBIGUATED = "CITE_KEY_DISAMBIGUATED";

  public static CitationWarning of(String code, String message) {
    return new CitationWarning(code, message);
  }
}
