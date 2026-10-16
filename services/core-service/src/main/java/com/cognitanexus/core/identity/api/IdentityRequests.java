package com.cognitanexus.core.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 身份接口的请求体。字段长度上限同时是拒绝超长输入的边界，避免无界负载进入哈希与日志。 */
public final class IdentityRequests {

  private IdentityRequests() {}

  public record RegisterRequest(
      @NotBlank @Size(min = 3, max = 64) String username,
      @NotBlank @Size(min = 10, max = 128) String password,
      @NotBlank @Size(min = 1, max = 64) String displayName) {}

  public record LoginRequest(
      @NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 128) String password) {}

  public record DisableAccountRequest(@NotBlank @Size(max = 200) String reason) {}
}
