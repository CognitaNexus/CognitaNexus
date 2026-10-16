package com.cognitanexus.sharedkernel;

/**
 * 成果类型。一期覆盖论文、专利、科研项目、获奖四类。
 *
 * <p>依据：FR16、需求基线 3.3.1。每个成果实体恰好拥有一个与 type 一致的类型扩展，
 * 不同时拥有四种（架构 V0.2 §5.1）。
 */
public enum AchievementType {
  PAPER("论文"),
  PATENT("专利"),
  PROJECT("科研项目"),
  AWARD("获奖");

  private final String label;

  AchievementType(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
