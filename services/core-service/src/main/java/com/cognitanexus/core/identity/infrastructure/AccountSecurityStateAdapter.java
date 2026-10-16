package com.cognitanexus.core.identity.infrastructure;

import com.cognitanexus.core.identity.application.IdentityRepositories.AccountRepository;
import com.cognitanexus.core.identity.application.IdentityRepositories.SessionRevocationRepository;
import com.cognitanexus.core.identity.domain.Account;
import com.cognitanexus.core.platform.security.AccountSecurityStatePort;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 账号安全状态端口实现。
 *
 * <p>依据：架构 V0.2 §10.2。平台层只声明端口，实现在身份模块，保证平台层不反向依赖业务模块。
 * 每次受保护请求都重新读取数据库当前状态：会话里保存的只是「上次登录时是什么」，
 * 不能作为权限事实源。
 */
@Component
public class AccountSecurityStateAdapter implements AccountSecurityStatePort {

  private final AccountRepository accountRepository;
  private final SessionRevocationRepository revocationRepository;

  public AccountSecurityStateAdapter(
      AccountRepository accountRepository, SessionRevocationRepository revocationRepository) {
    this.accountRepository = accountRepository;
    this.revocationRepository = revocationRepository;
  }

  @Override
  public Optional<AccountSecurityState> load(String accountId) {
    return accountRepository.findById(accountId).map(AccountSecurityStateAdapter::toState);
  }

  @Override
  public boolean isSessionRevoked(String sessionIdHash) {
    return revocationRepository.isRevoked(sessionIdHash);
  }

  private static AccountSecurityState toState(Account account) {
    return new AccountSecurityState(
        account.id(),
        account.username(),
        account.displayName(),
        account.roles(),
        account.status().name(),
        account.authVersion());
  }
}
