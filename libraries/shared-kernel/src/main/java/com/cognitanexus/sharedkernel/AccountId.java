package com.cognitanexus.sharedkernel;

import java.util.Objects;

/**
 * 账号内部标识。账号负责登录与授权，学者档案负责学术身份，两者分别建模。
 */
public record AccountId(String value) {

  public AccountId {
    Objects.requireNonNull(value, "accountId");
    if (value.isBlank()) {
      throw new IllegalArgumentException("accountId 不能为空");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
