package com.cognitanexus.core.integrity.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条成果的诚信状态汇总。
 *
 * <p><b>字段一律非空</b>。三个服务都配了 {@code default-property-inclusion: non_null}，null 字段会
 * 从响应里消失，前端随后拿到 {@code undefined}，于是「没有声明」与「服务端漏写字段」在报文上无法
 * 区分——本仓库已经因为这个形态出过一次整页静默卡死（C-07）。因此这里给出的是「无声明」这个
 * <b>明确的值</b>，而不是一个缺失的字段。
 *
 * <p>{@code status} 取生效声明中最严重的一种；{@code historyCount} 含已撤销的声明，它回答的是
 * 「这条记录曾被处理过几次」，与当前是否仍生效是两件事。
 */
public record IntegritySummary(
    String status,
    String label,
    String description,
    List<String> activeTypes,
    int activeCount,
    int historyCount) {

  /** 没有任何生效声明时的状态码。 */
  public static final String STATUS_NONE = "NONE";

  public static IntegritySummary none() {
    return new IntegritySummary(STATUS_NONE, "无诚信声明", "平台未登记任何关于该成果的诚信声明", List.of(), 0, 0);
  }

  /**
   * 按生效声明计算汇总。
   *
   * <p>已撤销的声明不计入 {@code status} 与 {@code activeTypes}：撤销的语义就是「这条判断不再成立」，
   * 若仍按它显示「已撤稿」，撤销功能等于无效。但它们仍计入 {@code historyCount}。
   */
  public static IntegritySummary of(List<IntegrityNotice> notices) {
    if (notices == null || notices.isEmpty()) {
      return none();
    }
    IntegrityNoticeType worst = null;
    List<String> activeTypes = new ArrayList<>();
    for (IntegrityNotice notice : notices) {
      if (!notice.active()) {
        continue;
      }
      activeTypes.add(notice.noticeType().code());
      if (worst == null || notice.noticeType().severity() > worst.severity()) {
        worst = notice.noticeType();
      }
    }
    if (worst == null) {
      return new IntegritySummary(
          STATUS_NONE, "无诚信声明", "历史声明均已撤销，当前无生效声明", List.of(), 0, notices.size());
    }
    return new IntegritySummary(
        worst.code(),
        "已" + worst.label(),
        worst.description(),
        List.copyOf(activeTypes),
        activeTypes.size(),
        notices.size());
  }

  public boolean retracted() {
    return IntegrityNoticeType.RETRACTION.code().equals(status);
  }
}
