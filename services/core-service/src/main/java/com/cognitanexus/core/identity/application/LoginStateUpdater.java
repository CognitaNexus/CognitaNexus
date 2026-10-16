package com.cognitanexus.core.identity.application;

import com.cognitanexus.core.identity.application.IdentityRepositories.AccountRepository;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.RoleNames;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录状态推进。
 *
 * <p>依据：架构 V0.2 §6.1、§8.1。独立成 bean 是必要的：登录失败必须把失败计数「提交后再抛错」，
 * 否则失败计数会随业务异常一起回滚，账号永远达不到锁定阈值。若直接在
 * {@code AuthService} 内部自调用带事务注解的方法，Spring 代理不会生效，同样是静默失效。
 *
 * <p>审计与状态推进在同一事务：登录审计不应出现「记录了失败但没有加计数」的错位。
 */
@Component
public class LoginStateUpdater {

  private final AccountRepository accounts;
  private final AuditRecorder audit;

  public LoginStateUpdater(AccountRepository accounts, AuditRecorder audit) {
    this.accounts = accounts;
    this.audit = audit;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onSuccess(String accountId, String username) {
    accounts.recordLoginSuccess(accountId);
    audit.record(
        new AuditRecorder.AuditEntry(
            accountId, username, "LOGIN_SUCCEEDED", "ACCOUNT", accountId, null, null, "口令校验通过"));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onFailure(
      String accountId, String username, int lockThreshold, int lockSeconds, String reason) {
    accounts.recordLoginFailure(accountId, lockThreshold, lockSeconds);
    audit.record(
        new AuditRecorder.AuditEntry(
            accountId, username, "LOGIN_FAILED", "ACCOUNT", accountId, null, null, reason));
  }

  /**
   * 缺少账号（登录名不存在或账号已停用）时的审计。
   *
   * <p>不写失败计数：此时没有可信的账号主体，把「登录名不存在」的失败记到别处会造成
   * 用户枚举侧信道。这里只留一条不含登录名的拒绝痕迹。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onRejected(String reason) {
    audit.record(
        new AuditRecorder.AuditEntry(
            null, RoleNames.SYSTEM, "LOGIN_REJECTED", "ACCOUNT", null, null, null, reason));
  }
}
