package com.cognitanexus.core.identity.infrastructure;

import com.cognitanexus.core.identity.application.IdentityRepositories.AccountRepository;
import com.cognitanexus.core.identity.domain.Account;
import com.cognitanexus.core.identity.domain.AccountCredentials;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;

/**
 * 账号仓储适配器。
 *
 * <p>依据：编码规范 §2.2。端口在应用层定义，实现在基础设施层；装配由构造器注入完成，
 * 应用层不感知 MyBatis。
 */
@Repository
public class AccountRepositoryAdapter implements AccountRepository {

  private final AccountMapper mapper;

  public AccountRepositoryAdapter(AccountMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<Account> findById(String id) {
    return mapper.findById(id).map(row -> mapper.toDomain(row, mapper.findRoles(row.id())));
  }

  @Override
  public Optional<AccountCredentials> findCredentialsByUsernameNorm(String usernameNorm) {
    return mapper
        .findByUsernameNorm(usernameNorm)
        .map(row -> mapper.toCredentials(row, mapper.findRoles(row.id())));
  }

  @Override
  public void insert(Account account, String rawPasswordHash) {
    mapper.insert(
        account.id(),
        account.username(),
        account.usernameNorm(),
        rawPasswordHash,
        account.displayName());
    for (String role : account.roles()) {
      mapper.insertRole(account.id(), role);
    }
  }

  @Override
  public int disable(String accountId) {
    return mapper.disable(accountId);
  }

  @Override
  public Set<String> findRoles(String accountId) {
    return mapper.findRoles(accountId);
  }

  @Override
  public void grantRole(String accountId, String role) {
    mapper.insertRole(accountId, role);
  }

  @Override
  public int revokeRole(String accountId, String role) {
    return mapper.deleteRole(accountId, role);
  }

  @Override
  public int bumpAuthVersion(String accountId) {
    return mapper.bumpAuthVersion(accountId);
  }

  @Override
  public long countActiveByRole(String role) {
    return mapper.countActiveByRole(role);
  }

  @Override
  public List<Account> listAll(int limit) {
    return mapper.listAll(limit).stream()
        .map(row -> mapper.toDomain(row, mapper.findRoles(row.id())))
        .toList();
  }

  @Override
  public void recordLoginSuccess(String accountId) {
    mapper.recordLoginSuccess(accountId);
  }

  @Override
  public void recordLoginFailure(String accountId, int lockThreshold, int lockSeconds) {
    mapper.recordLoginFailure(accountId, lockThreshold, lockSeconds);
  }

  @Override
  public Instant now() {
    return mapper.now();
  }
}
