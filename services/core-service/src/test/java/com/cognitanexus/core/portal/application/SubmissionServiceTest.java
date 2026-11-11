package com.cognitanexus.core.portal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.application.CatalogChangePublisher;
import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ContributionRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.FieldProvenanceRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.IdentifierRegistryRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ScholarRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.AchievementDetails;
import com.cognitanexus.core.catalog.domain.Contribution;
import com.cognitanexus.core.catalog.domain.FieldProvenance;
import com.cognitanexus.core.catalog.domain.IdentifierKind;
import com.cognitanexus.core.catalog.domain.Scholar;
import com.cognitanexus.core.notification.application.NotificationService;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.idempotency.IdempotencyGuard;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.portal.domain.Submission;
import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.ErrorCode;
import com.cognitanexus.sharedkernel.RelationState;
import com.cognitanexus.sharedkernel.Visibility;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 成果自助提交的业务规则。
 *
 * <p>这里守的是三件最容易做错的事：
 *
 * <ol>
 *   <li>提交件<b>不能</b>在审核通过前变成成果（一旦漏了，未审内容就进了公开目录）；
 *   <li>查重只写提示，<b>不能</b>据此合并或改状态（相似标题不直接合并是红线）；
 *   <li>驳回/补充材料必须给原因（没有原因的驳回，提交人只能反复试）。
 * </ol>
 */
class SubmissionServiceTest {

  private PortalRepositories.SubmissionRepository submissions;
  private ScholarRepository scholars;
  private AchievementRepository achievements;
  private ContributionRepository contributions;
  private FieldProvenanceRepository provenance;
  private IdentifierRegistryRepository identifiers;
  private CatalogChangePublisher catalogChanges;
  private IdempotencyGuard idempotency;
  private AuditRecorder audit;
  private NotificationService notifications;
  private SubmissionService service;

  private final ActorContext author =
      new ActorContext(new AccountId("acc_author"), "author", "作者", Set.of(RoleNames.USER), 1L);
  private final ActorContext reviewer =
      new ActorContext(new AccountId("acc_rev"), "reviewer", "审核员", Set.of(RoleNames.REVIEWER), 1L);
  private final ActorContext nobody =
      new ActorContext(new AccountId("acc_none"), "nobody", "无角色", Set.of(), 1L);

  @BeforeEach
  void setUp() {
    submissions = mock(PortalRepositories.SubmissionRepository.class);
    scholars = mock(ScholarRepository.class);
    achievements = mock(AchievementRepository.class);
    contributions = mock(ContributionRepository.class);
    provenance = mock(FieldProvenanceRepository.class);
    identifiers = mock(IdentifierRegistryRepository.class);
    catalogChanges = mock(CatalogChangePublisher.class);
    idempotency = mock(IdempotencyGuard.class);
    audit = mock(AuditRecorder.class);
    notifications = mock(NotificationService.class);
    service =
        new SubmissionService(
            submissions,
            scholars,
            achievements,
            contributions,
            provenance,
            identifiers,
            catalogChanges,
            idempotency,
            audit,
            notifications);
    // 默认：没有幂等键（返回 NOT_PROVIDED），查重不命中。
    lenient()
        .when(idempotency.reserve(anyString(), anyString(), any(), anyString()))
        .thenReturn(new IdempotencyGuard.Reservation(IdempotencyGuard.Status.NOT_PROVIDED, null));
    lenient().when(submissions.findAchievementIdByDoi(anyString())).thenReturn(Optional.empty());
    lenient().when(submissions.findMostSimilarTitle(anyString())).thenReturn(Optional.empty());
    lenient().when(scholars.findById(anyString())).thenReturn(Optional.empty());
  }

  private SubmissionService.NewSubmission paper(String title) {
    return new SubmissionService.NewSubmission(
        "PAPER", title, null, "某期刊", 2023, "摘要", "张三, 李四", null);
  }

  private Submission stored(Submission.Status status, long version, String accountId) {
    return new Submission(
        "sub_1",
        accountId,
        null,
        com.cognitanexus.sharedkernel.AchievementType.PAPER,
        "深度学习在文献计量中的应用",
        "深度学习在文献计量中的应用",
        null,
        null,
        "某期刊",
        2023,
        "摘要",
        null,
        status,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        version,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  // ---------------------------------------------------------------------------
  // 授权与校验
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("未登录不能提交")
  void rejectsAnonymous() {
    assertThatThrownBy(() -> service.submit(null, paper("标题"), null))
        .isInstanceOf(BusinessException.class)
        .extracting(cause -> ((BusinessException) cause).errorCode())
        .isEqualTo(ErrorCode.AUTH_REQUIRED);
  }

  @Test
  @DisplayName("没有提交能力的账号被拒绝")
  void rejectsActorWithoutCapability() {
    assertThatThrownBy(() -> service.submit(nobody, paper("标题"), null))
        .isInstanceOf(BusinessException.class)
        .extracting(cause -> ((BusinessException) cause).errorCode())
        .isEqualTo(ErrorCode.ACCESS_DENIED);
  }

  @Test
  @DisplayName("题名为空被拒绝")
  void rejectsBlankTitle() {
    assertThatThrownBy(() -> service.submit(author, paper("   "), null))
        .isInstanceOf(BusinessException.class)
        .extracting(cause -> ((BusinessException) cause).errorCode())
        .isEqualTo(ErrorCode.INVALID_ARGUMENT);
  }

  @Test
  @DisplayName("非论文类型被明确拒绝，而不是先建一条缺扩展的记录")
  void rejectsNonPaperType() {
    SubmissionService.NewSubmission patent =
        new SubmissionService.NewSubmission("PATENT", "一种方法", null, null, 2023, null, null, null);
    assertThatThrownBy(() -> service.submit(author, patent, null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("只接受论文");
    verify(submissions, never()).insert(any());
  }

  @Test
  @DisplayName("年份越界被拒绝（下限 1500，上限明年）")
  void rejectsImpossibleYear() {
    SubmissionService.NewSubmission bad =
        new SubmissionService.NewSubmission("PAPER", "标题", null, null, 1400, null, null, null);
    assertThatThrownBy(() -> service.submit(author, bad, null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("发表年份");
  }

  @Test
  @DisplayName("指定的学者档案被合并时拒绝提交")
  void rejectsMergedScholar() {
    when(scholars.findById("sch_old"))
        .thenReturn(
            Optional.of(
                new Scholar(
                    "sch_old",
                    "张三",
                    "张三",
                    DataNature.REAL,
                    Scholar.ClaimedStatus.CLAIMED,
                    "北航",
                    null,
                    "sch_new",
                    1L,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T00:00:00Z"))));
    SubmissionService.NewSubmission request =
        new SubmissionService.NewSubmission("PAPER", "标题", null, null, 2023, null, null, "sch_old");
    assertThatThrownBy(() -> service.submit(author, request, null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已被合并");
  }

  // ---------------------------------------------------------------------------
  // 查重：只提示
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("DOI 命中：标出重复提示，但状态仍是待审，且不合并")
  void doiHitMarksHintOnly() {
    when(submissions.findAchievementIdByDoi(anyString())).thenReturn(Optional.of("ach_existing"));
    when(submissions.findById(anyString()))
        .thenAnswer(
            invocation -> Optional.of(stored(Submission.Status.SUBMITTED, 1L, "acc_author")));
    SubmissionService.NewSubmission request =
        new SubmissionService.NewSubmission(
            "PAPER", "标题", "https://doi.org/10.1/ABC", null, 2023, null, null, null);

    service.submit(author, request, null);

    ArgumentCaptor<Submission> captor = ArgumentCaptor.forClass(Submission.class);
    verify(submissions).insert(captor.capture());
    Submission saved = captor.getValue();
    assertThat(saved.duplicateOf()).isEqualTo("ach_existing");
    assertThat(saved.duplicateKind()).isEqualTo(Submission.DuplicateKind.DOI);
    assertThat(saved.status()).isEqualTo(Submission.Status.SUBMITTED);
    assertThat(saved.duplicateNote()).contains("由审核确认是否重复");
    // 查重命中不等于合并：这条路径不许碰成果仓储的任何写方法。
    verify(achievements, never()).insert(any());
  }

  @Test
  @DisplayName("题名相似度达到阈值才提示；低于阈值不打扰审核员")
  void titleSimilarityNeedsThreshold() {
    when(submissions.findMostSimilarTitle(anyString()))
        .thenReturn(
            Optional.of(
                new PortalRepositories.SubmissionRepository.SimilarAchievement(
                    "ach_similar", 0.9)));
    when(submissions.findById(anyString()))
        .thenAnswer(
            invocation -> Optional.of(stored(Submission.Status.SUBMITTED, 1L, "acc_author")));

    service.submit(author, paper("深度学习在文献计量中的应用"), null);
    ArgumentCaptor<Submission> captor = ArgumentCaptor.forClass(Submission.class);
    verify(submissions).insert(captor.capture());
    assertThat(captor.getValue().duplicateKind()).isEqualTo(Submission.DuplicateKind.TITLE);
    assertThat(captor.getValue().duplicateNote()).contains("相似不等于重复");

    // 低于阈值：不标重复。
    when(submissions.findMostSimilarTitle(anyString()))
        .thenReturn(
            Optional.of(
                new PortalRepositories.SubmissionRepository.SimilarAchievement("ach_far", 0.4)));
    service.submit(author, paper("另一篇论文"), null);
    ArgumentCaptor<Submission> second = ArgumentCaptor.forClass(Submission.class);
    verify(submissions, org.mockito.Mockito.times(2)).insert(second.capture());
    assertThat(second.getAllValues().get(1).duplicateOf()).isNull();
  }

  @Test
  @DisplayName("DOI 命中优先于题名相似：唯一标识比线索更硬")
  void doiTakesPrecedenceOverTitle() {
    when(submissions.findAchievementIdByDoi(anyString())).thenReturn(Optional.of("ach_by_doi"));
    when(submissions.findMostSimilarTitle(anyString()))
        .thenReturn(
            Optional.of(
                new PortalRepositories.SubmissionRepository.SimilarAchievement(
                    "ach_by_title", 0.99)));
    when(submissions.findById(anyString()))
        .thenAnswer(
            invocation -> Optional.of(stored(Submission.Status.SUBMITTED, 1L, "acc_author")));
    SubmissionService.NewSubmission request =
        new SubmissionService.NewSubmission(
            "PAPER", "标题", "10.1/xyz", null, 2023, null, null, null);

    service.submit(author, request, null);

    ArgumentCaptor<Submission> captor = ArgumentCaptor.forClass(Submission.class);
    verify(submissions).insert(captor.capture());
    assertThat(captor.getValue().duplicateOf()).isEqualTo("ach_by_doi");
    assertThat(captor.getValue().duplicateKind()).isEqualTo(Submission.DuplicateKind.DOI);
  }

  @Test
  @DisplayName("幂等键重放返回首次那条，不再建第二条")
  void idempotentReplayReturnsFirst() {
    when(idempotency.reserve(anyString(), anyString(), any(), anyString()))
        .thenReturn(new IdempotencyGuard.Reservation(IdempotencyGuard.Status.REPLAY, "sub_1"));
    when(submissions.findById("sub_1"))
        .thenReturn(Optional.of(stored(Submission.Status.SUBMITTED, 1L, "acc_author")));

    Submission result = service.submit(author, paper("标题"), "key-1");

    assertThat(result.id()).isEqualTo("sub_1");
    verify(submissions, never()).insert(any());
  }

  // ---------------------------------------------------------------------------
  // 撤回
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("撤回别人的提交被拒绝（403，且不改状态）")
  void cannotWithdrawOthersSubmission() {
    when(submissions.findById("sub_1"))
        .thenReturn(Optional.of(stored(Submission.Status.SUBMITTED, 1L, "acc_other")));

    assertThatThrownBy(() -> service.withdraw(author, "sub_1"))
        .isInstanceOf(BusinessException.class)
        .extracting(cause -> ((BusinessException) cause).errorCode())
        .isEqualTo(ErrorCode.ACCESS_DENIED);
    verify(submissions, never()).withdraw(anyString(), anyString(), anyLong());
  }

  @Test
  @DisplayName("已裁决的提交不能再撤回")
  void cannotWithdrawDecided() {
    when(submissions.findById("sub_1"))
        .thenReturn(Optional.of(stored(Submission.Status.APPROVED, 2L, "acc_author")));

    assertThatThrownBy(() -> service.withdraw(author, "sub_1"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已被裁决");
  }

  @Test
  @DisplayName("本人撤回成功并留审计")
  void withdrawsOwnSubmission() {
    when(submissions.findById("sub_1"))
        .thenReturn(Optional.of(stored(Submission.Status.SUBMITTED, 1L, "acc_author")))
        .thenReturn(Optional.of(stored(Submission.Status.WITHDRAWN, 2L, "acc_author")));
    when(submissions.withdraw("sub_1", "acc_author", 1L)).thenReturn(1);

    Submission result = service.withdraw(author, "sub_1");

    assertThat(result.status()).isEqualTo(Submission.Status.WITHDRAWN);
    verify(audit).record(any());
  }

  // ---------------------------------------------------------------------------
  // 裁决
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("只有审核能力可以裁决：普通作者调用被拒绝")
  void onlyReviewerCanDecide() {
    assertThatThrownBy(() -> service.decide(author, "sub_1", "APPROVE", "理由"))
        .isInstanceOf(BusinessException.class)
        .extracting(cause -> ((BusinessException) cause).errorCode())
        .isEqualTo(ErrorCode.ACCESS_DENIED);
  }

  @Test
  @DisplayName("驳回必须给原因")
  void rejectRequiresReason() {
    when(submissions.findById("sub_1"))
        .thenReturn(Optional.of(stored(Submission.Status.SUBMITTED, 1L, "acc_author")));

    assertThatThrownBy(() -> service.decide(reviewer, "sub_1", "REJECT", "  "))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("驳回必须说明原因");
    verify(submissions, never())
        .decide(anyString(), anyString(), anyString(), any(), any(), any(), anyLong());
  }

  @Test
  @DisplayName("已裁决的提交再次裁决报冲突，而不是覆盖结论")
  void cannotDecideTwice() {
    when(submissions.findById("sub_1"))
        .thenReturn(Optional.of(stored(Submission.Status.APPROVED, 2L, "acc_author")));

    assertThatThrownBy(() -> service.decide(reviewer, "sub_1", "APPROVE", "再批一次"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已被裁决");
  }

  @Test
  @DisplayName("批准：建成果 + 论文扩展 + 本人确认关系 + 人工值溯源 + 事件 + 通知")
  void approveCreatesEverything() {
    Submission pending =
        new Submission(
            "sub_1",
            "acc_author",
            "sch_author",
            com.cognitanexus.sharedkernel.AchievementType.PAPER,
            "深度学习在文献计量中的应用",
            "深度学习在文献计量中的应用",
            "10.1/xyz",
            "10.1/xyz",
            "某期刊",
            2023,
            "摘要",
            "张三",
            Submission.Status.SUBMITTED,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            1L,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"));
    when(submissions.findById("sub_1")).thenReturn(Optional.of(pending));
    when(submissions.decide(
            eq("sub_1"), eq("APPROVED"), eq("acc_rev"), anyString(), any(), any(), eq(1L)))
        .thenReturn(1);

    SubmissionService.DecisionResult result = service.decide(reviewer, "sub_1", "APPROVE", "材料齐全");

    assertThat(result.status()).isEqualTo("APPROVED");
    assertThat(result.achievementId()).startsWith("ach_");

    ArgumentCaptor<Achievement> achievement = ArgumentCaptor.forClass(Achievement.class);
    verify(achievements).insert(achievement.capture());
    assertThat(achievement.getValue().visibility()).isEqualTo(Visibility.PUBLIC);
    assertThat(achievement.getValue().dataNature()).isEqualTo(DataNature.REAL);

    ArgumentCaptor<AchievementDetails.Paper> paperCaptor =
        ArgumentCaptor.forClass(AchievementDetails.Paper.class);
    verify(achievements).insertPaper(paperCaptor.capture());
    // 引用量必须留空：提交表单里没有这一项，填 0 会被读成「确认无人引用」。
    assertThat(paperCaptor.getValue().citationCount()).isNull();

    ArgumentCaptor<Contribution> contribution = ArgumentCaptor.forClass(Contribution.class);
    verify(contributions).insertIfAbsent(contribution.capture());
    assertThat(contribution.getValue().relationState()).isEqualTo(RelationState.SELF_CONFIRMED);
    assertThat(contribution.getValue().scholarId()).isEqualTo("sch_author");

    verify(provenance)
        .upsertReviewed(
            eq("ACHIEVEMENT"),
            anyString(),
            eq("title"),
            eq(FieldProvenance.ValueLevel.MANUAL.name()),
            anyString());
    verify(catalogChanges).publish(any(Achievement.class), eq(1L));
    verify(notifications)
        .send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

    // 有 DOI 的提交件要进标识注册表：否则引用网络找不到这条成果，红线核对也会把它算成「无稳定标识」。
    verify(identifiers)
        .register(
            anyString(),
            eq("ACHIEVEMENT"),
            anyString(),
            eq(IdentifierKind.DOI),
            eq(""),
            eq("10.1/xyz"));
  }

  @Test
  @DisplayName("批准即人工确认公开：无论有无标识都要留 ACHIEVEMENT_PUBLISH_CONFIRMED")
  void approveRecordsPublishConfirmation() {
    // 这条提交件没有任何稳定标识。没有审计事件时，治理端核对不变量只能判它「未经人工确认就公开」，
    // 而实际上审核通过就是那次确认——缺的是留痕，不是决定。
    when(submissions.findById("sub_1"))
        .thenReturn(Optional.of(stored(Submission.Status.SUBMITTED, 1L, "acc_author")));
    when(submissions.decide(
            anyString(), anyString(), anyString(), anyString(), any(), any(), anyLong()))
        .thenReturn(1);

    service.decide(reviewer, "sub_1", "APPROVE", "材料齐全，通讯作者已核实");

    ArgumentCaptor<AuditRecorder.AuditEntry> entry =
        ArgumentCaptor.forClass(AuditRecorder.AuditEntry.class);
    verify(audit, atLeastOnce()).record(entry.capture());
    AuditRecorder.AuditEntry confirmed =
        entry.getAllValues().stream()
            .filter(e -> "ACHIEVEMENT_PUBLISH_CONFIRMED".equals(e.action()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("批准后没有人工确认公开的审计事件"));
    assertThat(confirmed.targetId()).startsWith("ach_");
    assertThat(confirmed.afterValue())
        .containsEntry("visibility", "PUBLIC")
        .containsEntry("effectiveStatus", "EFFECTIVE");
    // 理由取裁决说明：审计里必须能回答「谁在什么依据下让它公开」。
    assertThat(confirmed.reason()).isEqualTo("材料齐全，通讯作者已核实");
    // 没有 DOI 就不该凭空登记一个标识。
    verify(identifiers, never())
        .register(anyString(), anyString(), anyString(), any(), anyString(), anyString());
  }

  @Test
  @DisplayName("批准但未指定学者：只建成果，不替作者认下署名")
  void approveWithoutScholarSkipsContribution() {
    when(submissions.findById("sub_1"))
        .thenReturn(Optional.of(stored(Submission.Status.SUBMITTED, 1L, "acc_author")));
    when(submissions.decide(
            anyString(), anyString(), anyString(), anyString(), any(), any(), anyLong()))
        .thenReturn(1);

    service.decide(reviewer, "sub_1", "APPROVE", "材料齐全");

    verify(contributions, never()).insertIfAbsent(any());
  }

  @Test
  @DisplayName("要求补充材料：状态迁移到 NEED_MORE_INFO 并通知提交人")
  void needMoreInfoNotifies() {
    when(submissions.findById("sub_1"))
        .thenReturn(Optional.of(stored(Submission.Status.SUBMITTED, 1L, "acc_author")));
    when(submissions.decide(
            anyString(), anyString(), anyString(), anyString(), any(), any(), anyLong()))
        .thenReturn(1);

    SubmissionService.DecisionResult result =
        service.decide(reviewer, "sub_1", "NEED_MORE_INFO", "请补充 DOI");

    assertThat(result.status()).isEqualTo("NEED_MORE_INFO");
    verify(submissions)
        .decide(
            eq("sub_1"),
            eq("NEED_MORE_INFO"),
            eq("acc_rev"),
            eq("请补充 DOI"),
            any(),
            eq(null),
            eq(1L));
    verify(notifications)
        .send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("并发下裁决影响 0 行时报版本冲突，而不是当作成功")
  void concurrentDecisionIsConflict() {
    when(submissions.findById("sub_1"))
        .thenReturn(Optional.of(stored(Submission.Status.SUBMITTED, 1L, "acc_author")));
    when(submissions.decide(
            anyString(), anyString(), anyString(), anyString(), any(), any(), anyLong()))
        .thenReturn(0);

    assertThatThrownBy(() -> service.decide(reviewer, "sub_1", "APPROVE", "材料齐全"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已被其他操作改变");
  }

  @Test
  @DisplayName("队列只对审核者开放")
  void queueRequiresReviewCapability() {
    assertThatThrownBy(() -> service.queue(author, 20, 0))
        .isInstanceOf(BusinessException.class)
        .extracting(cause -> ((BusinessException) cause).errorCode())
        .isEqualTo(ErrorCode.ACCESS_DENIED);
    lenient().when(submissions.listOpen(anyInt(), anyInt())).thenReturn(List.of());
    assertThat(service.queue(reviewer, 20, 0)).isEmpty();
  }
}
