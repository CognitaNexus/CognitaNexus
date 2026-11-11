package com.cognitanexus.sharedkernel;

import java.util.Objects;

/**
 * 学者档案内部标识。与账号标识是两种独立对象，不互相推导（架构 V0.2 §5.4）。
 */
public record ScholarId(String value) {

  public ScholarId {
    Objects.requireNonNull(value, "scholarId");
    if (value.isBlank()) {
      throw new IllegalArgumentException("scholarId 不能为空");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
