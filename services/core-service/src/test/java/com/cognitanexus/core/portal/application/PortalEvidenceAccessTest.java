package com.cognitanexus.core.portal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.application.CatalogRepositories;
import com.cognitanexus.core.identity.application.IdentityRepositories;
import com.cognitanexus.core.notification.application.NotificationService;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.event.OutboxStore;
import com.cognitanexus.core.platform.idempotency.IdempotencyGuard;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.portal.domain.ClaimEvidence;
import com.cognitanexus.core.portal.domain.ClaimRequest;
import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 认领材料的访问控制（A08）。
 *
 * <p>这一组用例锁的是一条具体的探测面：早期实现先查材料、再判权限，未授权者因此可以靠
 * 「404 材料不存在」与「403 无权查看」的差别反推某个材料 id 是否真实存在——把 id 空间枚举一遍
 * 就能数出别人提交了几份材料。修复后两种失败返回同一个错误码与同一句文案，本类用「错误码 + 文案
 * 完全一致」把它钉住：只要有人为了「方便排查」把 403 加回去，这条断言立刻失败。
 *
 * <p>另外验证三条不能含糊的规则：文章本人能读、审核人能读、以及别人把 claimId 塞进批量接口时
 * 不能因为「是前端传的」就采信。
 */
@ExtendWith(MockitoExtension.class)
class PortalEvidenceAccessTest {

  private static final String OWNER = "acc_owner";
  private static final String OTHER = "acc_other";
  private static final String REVIEWER = "acc_reviewer";
  private static final String CLAIM_ID = "clm_1";
  private static final String EVIDENCE_ID = "evd_1";
  private static final Instant WHEN = Instant.parse("2026-05-01T00:00:00Z");

  @Mock PortalRepositories.ClaimRequestRepository claims;
  @Mock PortalRepositories.PortalChangeRequestRepository changes;
  @Mock IdentityRepositories.PortalAuthorityRepository authorities;
  @Mock CatalogRepositories.ScholarRepository scholars;
  @Mock CatalogRepositories.FieldProvenanceRepository provenance;
  @Mock EvidenceStorage evidenceStorage;
  @Mock IdempotencyGuard idempotency;
  @Mock OutboxStore outbox;
  @Mock AuditRecorder audit;
  @Mock NotificationService notifications;

  private PortalService service;

  @BeforeEach
  void setUp() {
    service =
        new PortalService(
            claims,
            changes,
            authorities,
            scholars,
            provenance,
            evidenceStorage,
            idempotency,
            outbox,
            audit,
            notifications);
  }

  private static ActorContext actor(String id, String... roles) {
    return new ActorContext(new AccountId(id), id, id, Set.of(roles), 1L);
  }

  private static ClaimRequest claim(String ownerAccountId) {
    return new ClaimRequest(
        CLAIM_ID,
        ownerAccountId,
        "sch_1",
        ClaimRequest.Status.PENDING_REVIEW,
        "我是本人",
        WHEN,
        null,
        null,
        null,
        null,
        1L);
  }

  private static ClaimEvidence evidence() {
    return new ClaimEvidence(
        EVIDENCE_ID, CLAIM_ID, "f_abc.pdf", "sha256:0", "application/pdf", 1024L, WHEN);
  }

  private void givenEvidenceExists(String ownerAccountId) {
    when(claims.findEvidence(EVIDENCE_ID)).thenReturn(Optional.of(evidence()));
    when(claims.findClaimById(CLAIM_ID)).thenReturn(Optional.of(claim(ownerAccountId)));
  }

  @Test
  @DisplayName("申请人本人可以下载自己的材料")
  void applicantCanDownload() {
    givenEvidenceExists(OWNER);
    when(evidenceStorage.read("f_abc.pdf")).thenReturn(new byte[] {1, 2, 3});

    PortalService.EvidenceDownload download =
        service.downloadEvidence(actor(OWNER, RoleNames.USER), EVIDENCE_ID);

    assertThat(download.content()).containsExactly(1, 2, 3);
    assertThat(download.evidence().id()).isEqualTo(EVIDENCE_ID);
  }

  @Test
  @DisplayName("具备审核能力者可以下载，用于判断这条申请该不该批")
  void reviewerCanDownload() {
    givenEvidenceExists(OWNER);
    when(evidenceStorage.read("f_abc.pdf")).thenReturn(new byte[] {9});

    PortalService.EvidenceDownload download =
        service.downloadEvidence(actor(REVIEWER, RoleNames.REVIEWER), EVIDENCE_ID);

    assertThat(download.content()).containsExactly(9);
  }

  @Test
  @DisplayName("非本人且无审核能力时，不得读取材料本体（A08）")
  void strangerCannotReadContent() {
    givenEvidenceExists(OWNER);

    assertThatThrownBy(() -> service.downloadEvidence(actor(OTHER, RoleNames.USER), EVIDENCE_ID))
        .isInstanceOf(BusinessException.class);

    // 最关键的一条：拒绝发生在读文件之前。先读再判权限等于把内容读进了内存。
    verify(evidenceStorage, never()).read(anyString());
  }

  @Test
  @DisplayName("「无权读」与「不存在」返回同一个错误码与文案，不泄露材料存在性")
  void deniedAndMissingAreIndistinguishable() {
    givenEvidenceExists(OWNER);
    BusinessException denied = null;
    try {
      service.downloadEvidence(actor(OTHER, RoleNames.USER), EVIDENCE_ID);
    } catch (BusinessException e) {
      denied = e;
    }

    // 换成「不存在的材料」：此时仓储对任何 id 都返回空。
    when(claims.findEvidence("evd_missing")).thenReturn(Optional.empty());
    BusinessException missing = null;
    try {
      service.downloadEvidence(actor(OTHER, RoleNames.USER), "evd_missing");
    } catch (BusinessException e) {
      missing = e;
    }

    assertThat(denied).isNotNull();
    assertThat(missing).isNotNull();
    assertThat(denied.errorCode()).isEqualTo(missing.errorCode());
    assertThat(denied.getMessage()).isEqualTo(missing.getMessage());
    // 若把 403 加回来，两条 response 会出现差异，这条断言就会失败——这正是它存在的理由。
    assertThat(denied.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
  }

  @Test
  @DisplayName("未登录先要求登录，不进入存在性判断（否则匿名者也能区分 404 与「需要登录」）")
  void anonymousIsAskedToLogIn() {
    assertThatThrownBy(() -> service.downloadEvidence(null, EVIDENCE_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.AUTH_REQUIRED);
    verifyNoInteractions(claims, evidenceStorage);
  }

  @Test
  @DisplayName("材料元信息列表与下载同一条规则：别人的申请看不到，也问不出是否存在")
  void listEvidenceFollowsSameRule() {
    when(claims.findClaimById(CLAIM_ID)).thenReturn(Optional.of(claim(OWNER)));
    when(claims.listEvidence(CLAIM_ID)).thenReturn(List.of(evidence()));

    assertThat(service.listEvidenceForClaim(actor(OWNER, RoleNames.USER), CLAIM_ID)).hasSize(1);

    assertThatThrownBy(() -> service.listEvidenceForClaim(actor(OTHER, RoleNames.USER), CLAIM_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.NOT_FOUND);
    // 只有本人那次拿到了列表；被拒绝的那次在权限判断处就停住，没有查过任何材料。
    verify(claims, times(1)).listEvidence(CLAIM_ID);
  }

  @Test
  @DisplayName("批量取材料只返回本账号名下的申请：请求里塞别人的 claimId 不生效")
  void batchForApplicantIgnoresForeignClaims() {
    when(claims.listClaimsByAccount(OWNER, 100)).thenReturn(List.of(claim(OWNER)));
    when(claims.listEvidence(CLAIM_ID)).thenReturn(List.of(evidence()));

    Map<String, List<ClaimEvidence>> grouped =
        service.evidenceOfClaimsForApplicant(
            actor(OWNER, RoleNames.USER), List.of(CLAIM_ID, "clm_foreign"));

    assertThat(grouped).containsOnlyKeys(CLAIM_ID);
    // 关键：别人的 id 根本没被查询，因此连「这份申请有没有材料」都不会泄露。
    verify(claims, never()).listEvidence("clm_foreign");
  }

  @Test
  @DisplayName("批量取材料（审核用）要求审核能力，普通账号直接拒绝")
  void batchForReviewRequiresCapability() {
    assertThatThrownBy(
            () ->
                service.evidenceOfClaimsForReview(actor(OTHER, RoleNames.USER), List.of(CLAIM_ID)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.ACCESS_DENIED);
    verify(claims, never()).listEvidence(any());
  }
}
