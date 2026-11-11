package com.cognitanexus.core.identity.infrastructure;

import com.cognitanexus.core.identity.application.IdentityRepositories.PortalAuthorityRepository;
import com.cognitanexus.core.identity.domain.PortalAuthority;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 门户授权仓储适配器。 */
@Repository
public class PortalAuthorityRepositoryAdapter implements PortalAuthorityRepository {

  private final PortalAuthorityMapper mapper;

  public PortalAuthorityRepositoryAdapter(PortalAuthorityMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<PortalAuthority> findActiveByScholar(String scholarId) {
    return mapper.findActiveByScholar(scholarId).map(mapper::toDomain);
  }

  @Override
  public Optional<PortalAuthority> findActiveByAccountAndScholar(
      String accountId, String scholarId) {
    return mapper.findActiveByAccountAndScholar(accountId, scholarId).map(mapper::toDomain);
  }

  @Override
  public List<PortalAuthority> listActiveByAccount(String accountId) {
    return mapper.listActiveByAccount(accountId).stream().map(mapper::toDomain).toList();
  }

  @Override
  public Optional<PortalAuthority> findById(String id) {
    return mapper.findById(id).map(mapper::toDomain);
  }

  @Override
  public boolean insertActive(PortalAuthority authority, String grantedByAccountId) {
    int affected =
        mapper.insertActive(
            authority.id(),
            authority.accountId(),
            authority.scholarId(),
            authority.claimRequestId(),
            authority.grantedAt(),
            grantedByAccountId);
    return affected == 1;
  }

  @Override
  public boolean revoke(String authorityId, String reason, Instant at) {
    return mapper.revoke(authorityId, reason, at) == 1;
  }
}
