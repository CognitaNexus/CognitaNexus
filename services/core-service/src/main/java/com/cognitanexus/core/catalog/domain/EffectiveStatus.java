package com.cognitanexus.core.catalog.domain;

/**
 * 成果生效状态。
 *
 * <p>依据：架构 V0.2 §5.4、AC-IMPL-FR10-01。来源接入只登记原始事实并创建待治理实体；
 * 只有治理通过或人工审批才发布为 EFFECTIVE 并进入公开投影。
 */
public enum EffectiveStatus {
  /** 已登记但尚未通过治理，不得进入公开检索与统计。 */
  PENDING_GOVERNANCE,
  /** 已生效：可被公开检索、统计与建边（仍受可见性与关系资格约束）。 */
  EFFECTIVE,
  /** 已撤回：保留历史与来源归属，但不再出现在公开视图。 */
  WITHDRAWN;

  public static EffectiveStatus of(String raw) {
    for (EffectiveStatus value : values()) {
      if (value.name().equals(raw)) {
        return value;
      }
    }
    throw new IllegalArgumentException("未知生效状态: " + raw);
  }

  public boolean publiclyEligible() {
    return this == EFFECTIVE;
  }
}
