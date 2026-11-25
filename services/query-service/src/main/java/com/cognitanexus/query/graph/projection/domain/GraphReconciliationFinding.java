package com.cognitanexus.query.graph.projection.domain;

import java.util.Locale;

/**
 * 对账差异明细。
 *
 * <p>设计要点：
 *
 * <ul>
 *   <li><b>机器可读的 {@code code} 与 {@code subject}</b>：运维脚本需要按差异类型与对象定位，
 *       从一段中文里正则取 ID 是另一种「不可复核」；
 *   <li><b>expected / actual 用数值而不是字符串</b>：数值差可以直接进入指标与告警阈值；
 *   <li><b>severity 三档</b>：INFO 只用于「本次对账自身有范围限制」（例如只扫描了前 N 条），
 *       不能被读成数据有问题；WARN 是「可解释的落后」（水位落后但数据尚自洽）；ERROR 是
 *       「同一口径下两边数字不同」或「同一成果的证据不同」，这类差异必须修复后再说图可用。
 * </ul>
 */
public record GraphReconciliationFinding(
    Severity severity, String code, String subject, String message, Long expected, Long actual) {

  public GraphReconciliationFinding {
    if (severity == null) {
      throw new IllegalArgumentException("对账差异必须给出等级");
    }
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("对账差异必须有机器可读的 code");
    }
    code = code.trim();
    subject = subject == null ? "" : subject;
    message = message == null ? "" : message;
  }

  public enum Severity {
    INFO,
    WARN,
    ERROR
  }

  /** 差异是否要求修复后才能宣称图与 PG 一致。 */
  public boolean blocking() {
    return severity == Severity.ERROR;
  }

  /** 供日志与报告使用的一行描述（不含凭据与主机信息）。 */
  public String describe() {
    StringBuilder text = new StringBuilder();
    text.append(severity.name()).append(' ').append(code);
    if (!subject.isBlank()) {
      text.append('[').append(subject).append(']');
    }
    if (expected != null || actual != null) {
      text.append(String.format(Locale.ROOT, " expected=%s actual=%s", expected, actual));
    }
    if (!message.isBlank()) {
      text.append(" - ").append(message);
    }
    return text.toString();
  }
}
