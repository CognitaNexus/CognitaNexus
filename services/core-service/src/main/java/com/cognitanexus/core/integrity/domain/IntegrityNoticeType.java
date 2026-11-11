package com.cognitanexus.core.integrity.domain;

import com.cognitanexus.sharedkernel.BusinessException;
import java.util.Arrays;

/**
 * 学术诚信声明的类型。
 *
 * <p>依据：COPE 对已发表文献的三类处理（expression of concern / correction / retraction）与 FR02
 * 「成果详情须给出可追溯的来源」的现实推论——读者有权知道「这条记录是否仍然成立」。
 *
 * <p>三类的语义不同，不能合并成一个是非标志：
 *
 * <ul>
 *   <li>{@link #CONCERN}：对可靠性表达关注，<b>结论未定</b>。成果仍然成立，只是有疑问。
 *   <li>{@link #CORRECTION}：已发布更正，成果主体仍成立，部分内容被修正。
 *   <li>{@link #RETRACTION}：声明作废。成果不应再被当作有效研究引用。
 * </ul>
 *
 * <p>{@code severity} 用于「同一成果有多条生效声明时对外显示哪一条」：取最严重的一条。这个顺序
 * 是业务判断，不是字典序——把「关注」显示在「撤稿」之上会让读者得到相反的印象。
 */
public enum IntegrityNoticeType {
  CONCERN(1, "CONCERN", "关注声明", "该成果的可靠性受到关注，结论待定"),
  CORRECTION(2, "CORRECTION", "更正", "该成果已发布更正，部分内容被修正"),
  RETRACTION(3, "RETRACTION", "撤稿", "该成果已被声明作废，不应作为有效研究引用");

  private final int severity;
  private final String code;
  private final String label;
  private final String description;

  IntegrityNoticeType(int severity, String code, String label, String description) {
    this.severity = severity;
    this.code = code;
    this.label = label;
    this.description = description;
  }

  public int severity() {
    return severity;
  }

  public String code() {
    return code;
  }

  public String label() {
    return label;
  }

  public String description() {
    return description;
  }

  /**
   * 解析类型代码。
   *
   * <p>严格匹配，不做大小写折叠也不做前缀猜测：这个值决定「是否显示为撤稿」，宽松解析会让
   * {@code retract} 这种拼写错误悄悄变成一个合法类型，而正确拼写反而报错时最难发现。
   */
  public static IntegrityNoticeType of(String raw) {
    if (raw == null || raw.isBlank()) {
      throw BusinessException.invalidArgument("声明类型不能为空");
    }
    String value = raw.trim();
    return Arrays.stream(values())
        .filter(type -> type.code.equals(value))
        .findFirst()
        .orElseThrow(
            () ->
                BusinessException.invalidArgument(
                    "未知的声明类型：" + value + "（只能是 CONCERN / CORRECTION / RETRACTION）"));
  }
}
