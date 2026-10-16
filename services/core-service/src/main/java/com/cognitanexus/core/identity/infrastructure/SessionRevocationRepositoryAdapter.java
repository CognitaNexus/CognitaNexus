package com.cognitanexus.core.identity.infrastructure;

import com.cognitanexus.core.identity.application.IdentityRepositories.SessionRevocationRepository;
import org.springframework.stereotype.Repository;

/** 会话撤销登记适配器。 */
@Repository
public class SessionRevocationRepositoryAdapter implements SessionRevocationRepository {

  private final SessionRevocationMapper mapper;

  public SessionRevocationRepositoryAdapter(SessionRevocationMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void register(String sessionIdHash, String accountId, String reason) {
    mapper.register(sessionIdHash, accountId, reason);
  }

  @Override
  public boolean isRevoked(String sessionIdHash) {
    return mapper.exists(sessionIdHash);
  }

  @Override
  public int purgeExpired(int retentionDays) {
    return mapper.purgeExpired(retentionDays);
  }
}
