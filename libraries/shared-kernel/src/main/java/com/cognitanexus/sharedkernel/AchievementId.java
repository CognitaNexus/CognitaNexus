package com.cognitanexus.sharedkernel;

import java.util.Objects;

/**
 * 成果实体内部标识。
 *
 * <p>依据：架构 V0.2 §9.3。所有外部接口中的内部 ID 一律使用字符串表示；本类型仅用于服务内部，
 * 序列化到外部契约时使用 {@link #value()}。
 */
public record AchievementId(String value) {

  public AchievementId {
    Objects.requireNonNull(value, "achievementId");
    if (value.isBlank()) {
      throw new IllegalArgumentException("achievementId 不能为空");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
