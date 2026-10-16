package com.cognitanexus.query.platform.scope;

import com.cognitanexus.sharedkernel.BusinessException;
import java.util.List;
import java.util.Locale;

/**
 * 数据性质作用域归一——全服务唯一一份实现。
 *
 * <p>为什么必须集中：这条规则此前在检索、分面、统计、合作网络四处各写了一份，四处都写成
 * 「参数为空即不限制」，同时响应里的作用域字段却写着 REAL。在只有真实数据的库上这与正确实现
 * 表现完全一致，所以测试也照不出来；一旦模拟数据入库，默认的公开统计会把模拟成果算进真实口径，
 * 并且仍然声称自己是 REAL——既错了又声明错了，比单纯漏掉过滤更难发现。
 *
 * <p>把「缺省是否等于放宽」这种判断散落在每个读入口，等价于在每个入口各留一个可能被写反的开关。
 * 收敛成一份之后，判据只有一句：<b>只有调用方显式要求，范围才会扩大</b>。
 *
 * <p>返回值语义刻意用 null 表示「不限制」：SQL 层用 {@code <if test="nature != null">} 拼条件，
 * 用「不传」来表达放宽比传一个特殊字符串更省事，也不会与合法取值撞名。
 */
public final class DataNatureScope {

  /** 默认现实视图口径：只含真实公开元数据。 */
  public static final String REAL = "REAL";

  /** 演示口径：不限数据性质。必须显式给出。 */
  public static final String ALL = "ALL";

  private static final List<String> CONCRETE = List.of(REAL, "SIMULATED", "MIXED");

  private DataNatureScope() {}

  /**
   * 归一作用域。
   *
   * @param raw 请求参数，可为空
   * @return 具体性质名（REAL / SIMULATED / MIXED），或 null 表示不限制
   * @throws BusinessException {@code INVALID_ARGUMENT} 取值不在允许集合内
   */
  public static String normalize(String raw) {
    if (raw == null || raw.isBlank()) {
      return REAL;
    }
    String upper = raw.trim().toUpperCase(Locale.ROOT);
    if (ALL.equals(upper)) {
      return null;
    }
    if (!CONCRETE.contains(upper)) {
      throw BusinessException.invalidArgument(
          "数据性质取值必须是 " + String.join(" / ", CONCRETE) + " / " + ALL + " 之一，实际为 " + raw);
    }
    return upper;
  }

  /**
   * 供响应回显的生效范围。
   *
   * <p>必须回显生效值而不是回显请求参数：参数缺省时生效范围是 REAL，回显 null 会让字段从响应里
   * 消失，调用方因而无法判断自己拿到的是真实口径还是演示口径。在「模拟数据不得冒充真实」是红线
   * 的系统里，缺失会被读成「是真实的」。
   */
  public static String label(String normalized) {
    return normalized == null ? ALL : normalized;
  }

  /** 生效范围的口径说明，随响应返回。 */
  public static String note(String normalized) {
    if (normalized == null) {
      return "当前范围包含全部数据性质（含模拟数据），仅适用于演示与容量测试，不代表真实收录口径";
    }
    if (REAL.equals(normalized)) {
      return "当前范围仅含真实数据（REAL），符合默认现实视图口径";
    }
    return "当前范围仅含 " + normalized + " 数据，属演示口径，不得作为平台真实统计对外引用";
  }
}
