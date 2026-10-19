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

  /**
   * 本人改密。
   *
   * <p>两个字段都只校验长度上限，强度规则由 {@code PasswordPolicy} 在应用层判定：
   * 在这里再写一份「至少 10 位且含字母数字」等于把规则维护两遍，改一处必忘另一处。
   */
  public record ChangePasswordRequest(
      @NotBlank @Size(max = 128) String currentPassword,
      @NotBlank @Size(max = 128) String newPassword) {}

  /**
   * 管理员重置口令。
   *
   * <p>newPassword 可空：空表示由服务端生成一次性口令并随响应返回一次。
   * reason 必填，重置是他人的账号安全操作，必须留下依据。
   */
  public record ResetPasswordRequest(
      @Size(max = 128) String newPassword, @NotBlank @Size(max = 200) String reason) {}
}
