package com.cognitanexus.core.identity.domain;

/**
 * 门户对象级授权。
 *
 * <p>依据：BR-IMP-05、AC-IMPL-FR04-01、AC-IMPL-FR05-03。授权是「账号 × 具体学者门户」的关系，
 * 不是角色；一个门户任一时刻至多一个生效授权，竞争认领不自动转移原授权。
 *
 * @param status ACTIVE 或 REVOKED
 */
public record PortalAuthority(
    String id,
    String accountId,
    String scholarId,
    String status,
    String claimRequestId,
    java.time.Instant grantedAt,
    java.time.Instant revokedAt) {

  public boolean active() {
    return "ACTIVE".equals(status);
  }

  public static PortalAuthority active(
      String id,
      String accountId,
      String scholarId,
      String claimRequestId,
      java.time.Instant grantedAt) {
    return new PortalAuthority(id, accountId, scholarId, "ACTIVE", claimRequestId, grantedAt, null);
  }

  public PortalAuthority revoked(String reason, java.time.Instant at) {
    return new PortalAuthority(id, accountId, scholarId, "REVOKED", claimRequestId, grantedAt, at);
  }
}
