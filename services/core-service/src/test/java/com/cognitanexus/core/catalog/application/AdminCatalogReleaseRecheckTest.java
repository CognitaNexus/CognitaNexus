package com.cognitanexus.core.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ContributionRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.FieldProvenanceRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.IdentifierRegistryRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ScholarRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.SourceRecordRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.TopicRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.AchievementDetails;
import com.cognitanexus.core.catalog.domain.EffectiveStatus;
import com.cognitanexus.core.catalog.domain.PublicationYear;
import com.cognitanexus.core.governance.application.GovernanceService;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.event.EventTypes;
import com.cognitanexus.core.platform.event.OutboxEvent;
import com.cognitanexus.core.platform.event.OutboxStore;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.ErrorCode;
import com.cognitanexus.sharedkernel.Visibility;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 裁决后重评估发布的判定条件。
 *
 * <p>依据：架构 V0.2 §6.2、BR-IMP-08。这个入口会在没有人工逐条点「确认发布」的情况下把成果
 * 放进公开目录，因此它的每一条放行条件都必须能被单独验证：少了任何一条，重评估都会变成一条
 * 绕过治理的自动发布通道。这里逐条构造「只差这一条」的输入，确认它确实拦得住。
 */
@ExtendWith(MockitoExtension.class)
class AdminCatalogReleaseRecheckTest {

  @Mock AchievementRepository achievements;
  @Mock ScholarRepository scholars;
  @Mock ContributionRepository contributions;
  @Mock SourceRecordRepository sourceRecords;
  @Mock CatalogRepositories.SourceConfigRepository sourceConfigs;
  @Mock TopicRepository topics;
  @Mock FieldProvenanceRepository provenance;
  @Mock IdentifierRegistryRepository identifiers;
  @Mock GovernanceService governance;
  @Mock PublicCatalogQueryService publicCatalog;
  @Mock OutboxStore outbox;
  @Mock AuditRecorder audit;
  @Mock PublicProjectionAssembler projections;

  private AdminCatalogService service;
  private ActorContext actor;

  @BeforeEach
  void setUp() {
    service =
        new AdminCatalogService(
            achievements,
            scholars,
            contributions,
            sourceRecords,
            sourceConfigs,
            topics,
            provenance,
            identifiers,
            governance,
            publicCatalog,
            outbox,
            audit,
            projections,
            new CatalogChangePublisher(
                achievements,
                projections,
                outbox,
                id -> com.cognitanexus.core.integrity.domain.IntegritySummary.STATUS_NONE),
            id -> com.cognitanexus.core.integrity.domain.IntegritySummary.STATUS_NONE);
    actor =
        new ActorContext(
            new AccountId("acc_reviewer"), "reviewer", "审核员", Set.of(RoleNames.REVIEWER), 1L);
  }

  private static Achievement achievement(
      String id, DataNature nature, EffectiveStatus status, String mergedIntoId) {
    return new Achievement(
        id,
        AchievementType.PAPER,
        "A Title",
        "a title",
        nature,
        Visibility.RESTRICTED,
        status,
        PublicationYear.of(2024),
        mergedIntoId,
        3L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  /** 待治理的真实成果：重评估里唯一会被认为值得继续往下判的状态。 */
  private void givenPendingReal(String id) {
    when(achievements.findById(id))
        .thenReturn(
            Optional.of(
                achievement(id, DataNature.REAL, EffectiveStatus.PENDING_GOVERNANCE, null)));
  }

  private static AchievementDetails.Paper paper(String id) {
    return new AchievementDetails.Paper(
        id,
        "10.1000/x",
        "10.1000/x",
        "Venue",
        java.time.LocalDate.of(2024, 1, 1),
        null,
        0,
        null,
        null);
  }

  @Test
  @DisplayName("条件齐备时发布：更新状态、写审计、发变更事件，缺一不可")
  void releasesWhenAllConditionsMet() {
    // findById 被读两次：判定时读一次拿当前状态，发布后读一次拿刷新后的实体拼事件载荷。
    when(achievements.findById("ach_1"))
        .thenReturn(
            Optional.of(
                achievement("ach_1", DataNature.REAL, EffectiveStatus.PENDING_GOVERNANCE, null)),
            Optional.of(achievement("ach_1", DataNature.REAL, EffectiveStatus.EFFECTIVE, null)));
    when(governance.countOpenCandidatesFor("ach_1")).thenReturn(0L);
    when(identifiers.hasRegistration("ACHIEVEMENT", "ach_1")).thenReturn(true);
    when(achievements.findPaper("ach_1")).thenReturn(Optional.of(paper("ach_1")));
    when(achievements.updatePublicationState(
            eq("ach_1"),
            eq(EffectiveStatus.EFFECTIVE.name()),
            eq(Visibility.PUBLIC.name()),
            eq(3L)))
        .thenReturn(1);

    List<String> released = service.recheckReleaseAfterResolution(List.of("ach_1"), actor, "候选已裁决");

    assertThat(released).containsExactly("ach_1");
    verify(audit)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                entry -> "ACHIEVEMENT_RELEASE_RECHECKED".equals(entry.action())));
    ArgumentCaptor<OutboxEvent.Builder> event = ArgumentCaptor.forClass(OutboxEvent.Builder.class);
    verify(outbox).append(event.capture());
    assertThat(event.getValue().eventType()).isEqualTo(EventTypes.CATALOG_CHANGE_COMMITTED);
  }

  @Test
  @DisplayName("仍有未裁决候选时不发布：裁决解除的只是成因之一，不是全部")
  void keepsPendingWhenCandidateStillOpen() {
    givenPendingReal("ach_1");
    when(governance.countOpenCandidatesFor("ach_1")).thenReturn(1L);

    assertThat(service.recheckReleaseAfterResolution(List.of("ach_1"), actor, "理由")).isEmpty();
    verify(achievements, never())
        .updatePublicationState(anyString(), anyString(), anyString(), anyLong());
    verifyNoInteractions(outbox);
  }

  @Test
  @DisplayName("没有稳定标识时不发布：缺少跨源同一性的依据，只能人工确认")
  void keepsPendingWithoutStableIdentifier() {
    givenPendingReal("ach_1");
    when(governance.countOpenCandidatesFor("ach_1")).thenReturn(0L);
    when(identifiers.hasRegistration("ACHIEVEMENT", "ach_1")).thenReturn(false);

    assertThat(service.recheckReleaseAfterResolution(List.of("ach_1"), actor, "理由")).isEmpty();
    verify(achievements, never())
        .updatePublicationState(anyString(), anyString(), anyString(), anyLong());
  }

  @Test
  @DisplayName("数据性质非真实时不发布：模拟数据不得经重评估进入现实视图")
  void keepsSimulatedDataOutOfRealityView() {
    when(achievements.findById("ach_1"))
        .thenReturn(
            Optional.of(
                achievement(
                    "ach_1", DataNature.SIMULATED, EffectiveStatus.PENDING_GOVERNANCE, null)));

    assertThat(service.recheckReleaseAfterResolution(List.of("ach_1"), actor, "理由")).isEmpty();
    verifyNoInteractions(governance, outbox);
  }

  @Test
  @DisplayName("类型扩展不唯一时不发布：公开成果必须恰好有一个与类型一致的扩展")
  void keepsPendingWithoutMatchingExtension() {
    givenPendingReal("ach_1");
    when(governance.countOpenCandidatesFor("ach_1")).thenReturn(0L);
    when(identifiers.hasRegistration("ACHIEVEMENT", "ach_1")).thenReturn(true);
    when(achievements.findPaper("ach_1")).thenReturn(Optional.empty());

    assertThat(service.recheckReleaseAfterResolution(List.of("ach_1"), actor, "理由")).isEmpty();
    verify(achievements, never())
        .updatePublicationState(anyString(), anyString(), anyString(), anyLong());
  }

  @Test
  @DisplayName("已合并的成果不发布：发布目标应指向幸存实体，不能把重定向源重新公开")
  void skipsMergedEntity() {
    when(achievements.findById("ach_1"))
        .thenReturn(
            Optional.of(
                achievement(
                    "ach_1", DataNature.REAL, EffectiveStatus.PENDING_GOVERNANCE, "ach_9")));

    assertThat(service.recheckReleaseAfterResolution(List.of("ach_1"), actor, "理由")).isEmpty();
    verifyNoInteractions(governance, outbox);
  }

  @Test
  @DisplayName("空集合或空白标识不触发任何查询：避免把空输入变成一次全表判定")
  void ignoresBlankInput() {
    assertThat(service.recheckReleaseAfterResolution(List.of(), actor, "理由")).isEmpty();
    assertThat(service.recheckReleaseAfterResolution(List.of("  "), actor, "理由")).isEmpty();
    verifyNoInteractions(achievements);
  }

  // ---------------------------------------------------------------------------
  // 同一发布路径上的另一个人工入口
  // ---------------------------------------------------------------------------

  private ActorContext admin() {
    return new ActorContext(
        new AccountId("acc_admin"), "admin", "平台管理员", Set.of(RoleNames.PLATFORM_ADMIN), 1L);
  }

  @Test
  @DisplayName("人工确认发布对待治理成果可用：此前状态判断写反，唯一该走这条路的成果反被 409 挡回")
  void confirmPublicationAcceptsPendingGovernance() {
    when(achievements.findById("ach_1"))
        .thenReturn(
            Optional.of(
                achievement("ach_1", DataNature.REAL, EffectiveStatus.PENDING_GOVERNANCE, null)),
            Optional.of(achievement("ach_1", DataNature.REAL, EffectiveStatus.EFFECTIVE, null)));
    when(achievements.findPaper("ach_1")).thenReturn(Optional.of(paper("ach_1")));
    when(achievements.updatePublicationState(
            eq("ach_1"),
            eq(EffectiveStatus.EFFECTIVE.name()),
            eq(Visibility.PUBLIC.name()),
            eq(3L)))
        .thenReturn(1);

    Map<String, Object> view = service.confirmPublication(admin(), "ach_1", "人工核对来源后发布");

    assertThat(view).containsEntry("effectiveStatus", EffectiveStatus.EFFECTIVE.name());
    verify(outbox).append(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("已生效成果再次确认发布返回冲突：重复动作不得悄悄推进版本")
  void confirmPublicationRejectsAlreadyEffective() {
    when(achievements.findById("ach_1"))
        .thenReturn(
            Optional.of(achievement("ach_1", DataNature.REAL, EffectiveStatus.EFFECTIVE, null)));

    assertThatThrownBy(() -> service.confirmPublication(admin(), "ach_1", "理由"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).errorCode())
                    .isEqualTo(ErrorCode.VERSION_CONFLICT));
  }
}
