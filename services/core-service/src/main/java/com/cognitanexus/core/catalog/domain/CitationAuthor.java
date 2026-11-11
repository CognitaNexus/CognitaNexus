package com.cognitanexus.core.catalog.domain;

/**
 * 引用文本里的一位署名者。
 *
 * <p>只承载「引用需要知道的那几件事」：显示名、署名序、归属状态。刻意不放 scholarId——
 * 引用文本给人看，标识给人看只会被误当成 DOI 或工号贴进参考文献。
 *
 * @param authorOrder 署名序，从 1 开始；来源未给出时为 null，不用 0 冒充第一作者
 * @param relationState 归属状态（{@code AUTO} / {@code SELF_CONFIRMED} / {@code PENDING_VERIFY} /
 *     {@code DISPUTED}）；{@code EXCLUDED} 在装配阶段已被剔除，不会出现在这里
 */
public record CitationAuthor(String displayName, Integer authorOrder, String relationState) {

  public boolean orderKnown() {
    return authorOrder != null;
  }

  public boolean orderUsable() {
    return authorOrder != null && authorOrder >= 1;
  }

  /**
   * 归属是否只是「平台推断」而非已确认。
   *
   * <p>引用作者列表本身不因关系状态而删减（署名是元数据事实，治理状态回答的是「这位学者是否被正确
   * 匹配」，两者不是一回事），但要让读者知道其中有些匹配还没定论。
   */
  public boolean pendingVerify() {
    return "PENDING_VERIFY".equals(relationState) || "DISPUTED".equals(relationState);
  }
}
