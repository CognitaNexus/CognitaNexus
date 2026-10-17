package com.cognitanexus.ingestion.collect.adapter;

import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 抓取失败，携带「来源要求等多久」。
 *
 * <p>依据：架构 V0.2 §6.1、§6.2；老师指导 WB-13（限流／429／Retry-After 用可控替身测试）。
 *
 * <p>为什么需要单独一种异常而不是继续用 {@link BusinessException}：退避时长有两种来源——服务自己
 * 按连续失败次数算的指数退避，和来源在响应头里明确要求的时间。后者是<b>对方的事实</b>，不能用自己的
 * 推算覆盖它：来源回 429 + {@code Retry-After: 120} 时继续每 2 秒重试，等于在对方限流期间持续敲门，
 * 结果是限流窗口被不断刷新。把「等多久」随异常一起往上带，是因为只有最靠近 HTTP 的那一层知道它，
 * 而做决策的那一层在服务里。
 *
 * <p>上限由调用方（{@code max-backoff-seconds}）兜住：一个异常响应头不该把任务冻结数天。
 * 本类只负责如实传达，不做截断——截断属于业务决策，放在能看到配置的那一层。
 */
public class SourceFetchException extends BusinessException {

  /** HTTP 状态码；非 HTTP 失败（超时、连接失败、响应超限）时为 0。 */
  private final int statusCode;

  /** 来源要求的等待秒数；未给出或无法解析时为 null。 */
  private final Integer retryAfterSeconds;

  private SourceFetchException(
      ErrorCode errorCode, String message, int statusCode, Integer retryAfterSeconds) {
    super(errorCode, message);
    this.statusCode = statusCode;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /** 来源返回非 200。{@code retryAfterHeader} 可为 null。 */
  public static SourceFetchException httpStatus(
      String sourceLabel, int statusCode, String retryAfterHeader) {
    String suffix =
        retryAfterHeader == null || retryAfterHeader.isBlank()
            ? ""
            : "（要求等待 " + retryAfterHeader + "）";
    return new SourceFetchException(
        ErrorCode.DEPENDENCY_UNAVAILABLE,
        sourceLabel + " 返回 " + statusCode + suffix,
        statusCode,
        parseRetryAfter(retryAfterHeader));
  }

  /** 响应体超过允许上限：不解析、不落库，避免一次异常响应把内存与库撑爆。 */
  public static SourceFetchException oversized(String sourceLabel, int maxResponseBytes) {
    return new SourceFetchException(
        ErrorCode.BUSINESS_RULE_VIOLATION,
        sourceLabel + " 响应超过允许上限 " + maxResponseBytes + " 字节，已拒绝解析",
        0,
        null);
  }

  /** 传输层失败（超时、连接被拒、JSON 无法解析等）。 */
  public static SourceFetchException transport(String sourceLabel, Throwable cause) {
    return new SourceFetchException(
        ErrorCode.DEPENDENCY_UNAVAILABLE,
        "抓取 " + sourceLabel + " 失败：" + cause.getClass().getSimpleName(),
        0,
        null);
  }

  public int statusCode() {
    return statusCode;
  }

  /** 来源要求的等待秒数；无则 null。调用方负责与自己的退避配置取上限。 */
  public Integer retryAfterSeconds() {
    return retryAfterSeconds;
  }

  /**
   * 解析 {@code Retry-After} 响应头。
   *
   * <p>两种合法形式都要支持：秒数（{@code 120}）与 HTTP 日期（{@code Wed, 21 Oct 2015 07:28:00 GMT}）。
   * 只支持前者会在真实来源改用日期形式时静默退化成本地退避——看起来"没生效"，实际是没解析出来。
   * 日期已过期时返回 0（立刻重试），负值一律归零。
   */
  static Integer parseRetryAfter(String raw) {
    if (raw == null) {
      return null;
    }
    String value = raw.trim();
    if (value.isEmpty()) {
      return null;
    }
    try {
      long seconds = Long.parseLong(value);
      return (int) Math.max(0, Math.min(seconds, Integer.MAX_VALUE));
    } catch (NumberFormatException ignored) {
      // 不是纯数字，按 HTTP 日期再试一次
    }
    try {
      ZonedDateTime when = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
      long seconds = Duration.between(ZonedDateTime.now(when.getZone()), when).toSeconds();
      return (int) Math.max(0, Math.min(seconds, Integer.MAX_VALUE));
    } catch (DateTimeParseException ignored) {
      // 无法识别的形式：宁可退化成自己的退避，也不要抛一个新异常把原始失败盖掉
      return null;
    }
  }
}
