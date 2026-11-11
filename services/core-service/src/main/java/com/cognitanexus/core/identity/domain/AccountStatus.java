package com.cognitanexus.core.identity.domain;

/** 账号状态。停用后既有会话立即不可用，但不删除该用户的学者公开档案（AC-IMPL-FR04-02）。 */
public enum AccountStatus {
  ACTIVE,
  DISABLED;

  public static AccountStatus of(String raw) {
    for (AccountStatus status : values()) {
      if (status.name().equals(raw)) {
        return status;
      }
    }
    throw new IllegalArgumentException("未知账号状态: " + raw);
  }

  public boolean active() {
    return this == ACTIVE;
  }
}
