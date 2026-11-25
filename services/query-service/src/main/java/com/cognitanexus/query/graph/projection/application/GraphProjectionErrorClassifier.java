package com.cognitanexus.query.graph.projection.application;

import com.cognitanexus.query.graph.infrastructure.GraphStoreUnavailableException;

/**
 * 图投影写入失败的分类（有限重试 vs 立即失败）。
 *
 * <p>分类的价值不在于「重试几次」，而在于<b>不该重试的错误不重试</b>：一条成果的参与关系里出现
 * 非法关系状态时，重试 3 次仍然非法，只是把一次明确的「数据有问题」拖成三倍耗时，并让
 * 「重试后成功」掩盖了上游数据错误。反过来，图库重启/连接池耗尽属于瞬态，立刻判死会让整批回填
 * 在几秒的抖动里中止。
 *
 * <p>分类依据只看异常类型，不看消息文本：消息里带成果标识就不能作为规则，否则一次数据修复
 * 会悄悄改变重试行为。{@link IllegalArgumentException} / {@link IllegalStateException} 代表
 * 「同一输入再跑一次结果相同」的域/数据问题，一律 PERMANENT。
 */
public final class GraphProjectionErrorClassifier {

  private GraphProjectionErrorClassifier() {}

  public enum Kind {
    /** 可重试：瞬态基础设施问题。 */
    RETRYABLE,
    /** 不可重试：输入/域规则问题，重试只会重复同一个失败。 */
    PERMANENT
  }

  /**
   * 分类一个写入异常。
   *
   * <p>{@link GraphStoreUnavailableException} 归 RETRYABLE：它是驱动异常的统一出口（连接失败、
   * 超时、事务被中止），这些都可能自行恢复。超出这些类型的 RuntimeException 也按 RETRYABLE
   * 处理，并由调用方的尝试次数上限兜底——未知异常的默认动作应当是「有限重试后放弃并记录」，
   * 而不是「静默跳过」。
   */
  public static Kind classify(RuntimeException ex) {
    if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
      return Kind.PERMANENT;
    }
    return Kind.RETRYABLE;
  }
}
