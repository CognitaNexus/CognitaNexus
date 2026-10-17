package com.cognitanexus.core.governance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ScholarRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.EffectiveStatus;
import com.cognitanexus.core.catalog.domain.PublicationYear;
import com.cognitanexus.core.catalog.domain.Scholar;
import com.cognitanexus.core.governance.domain.DedupCandidate;
import com.cognitanexus.core.governance.domain.GovernanceAnomaly;
import com.cognitanexus.core.governance.domain.MergeLedgerEntry;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.event.OutboxStore;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.util.PageRequest;
import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.ErrorCode;
import com.cognitanexus.sharedkernel.Visibility;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 治理业务（候选登记、裁决、合并）的行为约束。
 *
 * <p>依据：FR08/FR09（治理与去重）、BR-IMP-02/03/04（相似标题不得直接合并、同名不得直接合并学
 * 者、不得静默覆盖人工结论）、AC-IMPL-FR09-02/03、AC-IMPL-FR15-01/02。
 *
 * <p>本测试只 mock 出站端口，业务判定逻辑真实执行。关注点集中在三条不可越过的边界：
 * 相似度只产生候选不产生合并、每次合并必须留台账与审计、合并必须发布变更事件。
 */
@ExtendWith(MockitoExtension.class)
class GovernanceServiceTest {

  @Mock GovernanceRepositories.DedupCandidateRepository candidates;
  @Mock GovernanceRepositories.MergeLedgerRepository ledger;
  @Mock GovernanceRepositories.AnomalyRepository anomalies;
  @Mock GovernanceRepositories.MergeTargetRepository mergeTargets;
  @Mock GovernanceRepositories.AchievementStateRepository achievementStates;
  @Mock AchievementRepository achievements;
  @Mock ScholarRepository scholars;
  @Mock OutboxStore outbox;
  @Mock AuditRecorder audit;

  private GovernanceService service;
  private ActorContext actor;

  @BeforeEach
  void setUp() {
    service =
        new GovernanceService(
            candidates,
            ledger,
            anomalies,
            mergeTargets,
            achievementStates,
            achievements,
            scholars,
            outbox,
            audit);
    actor =
        new ActorContext(
            new AccountId("acc_reviewer"), "reviewer", "审核员", Set.of(RoleNames.REVIEWER), 1L);
  }

  private static Achievement achievement(String id, String title, String mergedIntoId) {
    return new Achievement(
        id,
        AchievementType.PAPER,
        title,
        title.toLowerCase(java.util.Locale.ROOT),
        DataNature.REAL,
        Visibility.PUBLIC,
        EffectiveStatus.EFFECTIVE,
        PublicationYear.of(2024),
        mergedIntoId,
        1L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static Scholar scholar(String id, String name, String mergedIntoId) {
    return new Scholar(
        id,
        name,
        name.toLowerCase(java.util.Locale.ROOT),
        DataNature.REAL,
        Scholar.ClaimedStatus.CLAIMED,
        "北京航空航天大学",
        null,
        mergedIntoId,
        1L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static Achievement achievementOf(
      String id, String title, Visibility visibility, EffectiveStatus effectiveStatus, int year) {
    return new Achievement(
        id,
        AchievementType.PAPER,
        title,
        title.toLowerCase(java.util.Locale.ROOT),
        DataNature.REAL,
        visibility,
        effectiveStatus,
        PublicationYear.of(year),
        null,
        1L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static DedupCandidate candidate(
      String id, String left, String right, DedupCandidate.Status status) {
    return new DedupCandidate(
        id,
        "PAPER",
        left,
        right,
        Map.of("reason", "TITLE_SIMILARITY"),
        status,
        Instant.parse("2026-01-01T00:00:00Z"),
        null,
        null,
        null,
        null);
  }

  // ---------------------------------------------------------------------------
  // 候选登记
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("候选登记：相似度只产生候选，不产生合并")
  class ReportSimilarity {

    @Test
    @DisplayName("同一实体自身不登记候选：自己与自己的相似度没有业务含义")
    void sameEntitySkipped() {
      Achievement one = achievement("ach_1", "Graph Neural Networks", null);
      service.reportTitleSimilarity(one, one, 1.0d);
      verifyNoInteractions(candidates);
    }

    @Test
    @DisplayName("左右两端按字典序排列后写入：使 (A,B) 与 (B,A) 命中同一条唯一约束，不产生重复候选")
    void orderedPairStored() {
      Achievement left = achievement("ach_9", "Title A", null);
      Achievement right = achievement("ach_1", "Title B", null);
      when(candidates.insertIfAbsent(any())).thenReturn(true);

      service.reportTitleSimilarity(left, right, 0.87d);

      ArgumentCaptor<DedupCandidate> captor = ArgumentCaptor.forClass(DedupCandidate.class);
      verify(candidates).insertIfAbsent(captor.capture());
      assertThat(captor.getValue().leftAchievementId()).isEqualTo("ach_1");
      assertThat(captor.getValue().rightAchievementId()).isEqualTo("ach_9");
      // 证据里的标题必须跟着同一次排序走：标识按字典序写、标题按调用方向写，会让界面把
      // 候选 A 的标题挂在候选 B 的标识上，裁决人看到的是错位对照。
      assertThat(captor.getValue().evidence())
          .containsEntry("leftTitle", "Title B")
          .containsEntry("rightTitle", "Title A");
    }

    @Test
    @DisplayName("证据记录两侧可见性与生效状态：合并公开与受限实体前，必先看清哪一边对外可见")
    void evidenceCarriesVisibility() {
      Achievement published =
          achievementOf("ach_1", "Title A", Visibility.PUBLIC, EffectiveStatus.EFFECTIVE, 2021);
      Achievement restricted =
          achievementOf(
              "ach_2", "Title B", Visibility.RESTRICTED, EffectiveStatus.PENDING_GOVERNANCE, 2022);
      when(candidates.insertIfAbsent(any())).thenReturn(true);

      service.reportTitleSimilarity(published, restricted, 0.91d);

      ArgumentCaptor<DedupCandidate> captor = ArgumentCaptor.forClass(DedupCandidate.class);
      verify(candidates).insertIfAbsent(captor.capture());
      assertThat(captor.getValue().evidence())
          .containsEntry("leftVisibility", "PUBLIC")
          .containsEntry("rightVisibility", "RESTRICTED")
          .containsEntry("leftEffectiveStatus", "EFFECTIVE")
          .containsEntry("rightEffectiveStatus", "PENDING_GOVERNANCE")
          .containsEntry("leftYear", 2021)
          .containsEntry("rightYear", 2022);
    }

    @Test
    @DisplayName("证据里写明「仅标题相似不构成合并依据」：裁决人必须看到这句话，否则会当成结论")
    void evidenceDeclaresNotAConclusion() {
      when(candidates.insertIfAbsent(any())).thenReturn(true);
      service.reportTitleSimilarity(
          achievement("ach_1", "Title A", null), achievement("ach_2", "Title B", null), 0.87654d);

      ArgumentCaptor<DedupCandidate> captor = ArgumentCaptor.forClass(DedupCandidate.class);
      verify(candidates).insertIfAbsent(captor.capture());
      Map<String, Object> evidence = captor.getValue().evidence();
      assertThat(evidence.get("similarity")).isEqualTo(0.877d);
      assertThat(String.valueOf(evidence.get("note"))).contains("不构成合并依据");
      assertThat(captor.getValue().status()).isEqualTo(DedupCandidate.Status.OPEN);
    }

    @Test
    @DisplayName("候选已存在（唯一约束命中）时不报错，也不重复登记")
    void duplicateSilentlyIgnored() {
      when(candidates.insertIfAbsent(any())).thenReturn(false);
      service.reportTitleSimilarity(
          achievement("ach_1", "Title A", null), achievement("ach_2", "Title B", null), 0.9d);
      verify(candidates).insertIfAbsent(any());
    }

    @Test
    @DisplayName("稳定标识冲突登记为异常，并保留占用方与尝试方，便于人工裁定")
    void identifierConflictRecorded() {
      service.reportIdentifierConflict("DOI", "10.1000/xyz", "ach_occupied", "ach_attempted");

      ArgumentCaptor<GovernanceAnomaly> captor = ArgumentCaptor.forClass(GovernanceAnomaly.class);
      verify(anomalies).record(captor.capture());
      GovernanceAnomaly anomaly = captor.getValue();
      assertThat(anomaly.kind()).isEqualTo(GovernanceAnomaly.KIND_IDENTIFIER_CONFLICT);
      assertThat(anomaly.entityId()).isEqualTo("ach_attempted");
      assertThat(anomaly.status()).isEqualTo("OPEN");
      assertThat(anomaly.detail()).containsEntry("occupiedBy", "ach_occupied");
    }
  }

  // ---------------------------------------------------------------------------
  // 裁决
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("裁决：必须显式给出理由与幸存实体")
  class Resolve {

    @Test
    @DisplayName("候选不存在返回 NOT_FOUND")
    void candidateMissing() {
      when(candidates.findById("ddc_x")).thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.resolveCandidate("ddc_x", "REJECT", null, "理由", actor))
          .isInstanceOf(BusinessException.class)
          .satisfies(
              ex ->
                  assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("候选已裁决返回 409：不覆盖首次结论（BR-IMP-04）")
    void alreadyResolved() {
      when(candidates.findById("ddc_1"))
          .thenReturn(
              Optional.of(candidate("ddc_1", "ach_1", "ach_2", DedupCandidate.Status.MERGED)));
      assertThatThrownBy(() -> service.resolveCandidate("ddc_1", "REJECT", null, "理由", actor))
          .isInstanceOf(BusinessException.class)
          .satisfies(
              ex ->
                  assertThat(((BusinessException) ex).errorCode())
                      .isEqualTo(ErrorCode.VERSION_CONFLICT));
    }

    @Test
    @DisplayName("action 非 MERGE/REJECT 时明确报错，不接受拼写近似值")
    void unknownActionRejected() {
      when(candidates.findById("ddc_1"))
          .thenReturn(
              Optional.of(candidate("ddc_1", "ach_1", "ach_2", DedupCandidate.Status.OPEN)));
      assertThatThrownBy(() -> service.resolveCandidate("ddc_1", "merge", null, "理由", actor))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("MERGE 或 REJECT");
    }

    @Test
    @DisplayName("REJECT 必须给理由：没有理由的裁决无法复核")
    void rejectRequiresReason() {
      when(candidates.findById("ddc_1"))
          .thenReturn(
              Optional.of(candidate("ddc_1", "ach_1", "ach_2", DedupCandidate.Status.OPEN)));
      assertThatThrownBy(() -> service.resolveCandidate("ddc_1", "REJECT", null, "   ", actor))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("必须说明裁决理由");
      verify(candidates, never()).resolve(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("REJECT 成功时写审计，动作名为 DEDUP_CANDIDATE_REJECTED")
    void rejectWritesAudit() {
      when(candidates.findById("ddc_1"))
          .thenReturn(
              Optional.of(candidate("ddc_1", "ach_1", "ach_2", DedupCandidate.Status.OPEN)));
      when(candidates.resolve("ddc_1", "REJECTED", "acc_reviewer", "不是同一篇")).thenReturn(true);

      service.resolveCandidate("ddc_1", "REJECT", null, "不是同一篇", actor);

      ArgumentCaptor<AuditRecorder.AuditEntry> captor =
          ArgumentCaptor.forClass(AuditRecorder.AuditEntry.class);
      verify(audit).record(captor.capture());
      assertThat(captor.getValue().action()).isEqualTo("DEDUP_CANDIDATE_REJECTED");
      assertThat(captor.getValue().targetId()).isEqualTo("ddc_1");
    }

    @Test
    @DisplayName("REJECT 时并发已改变状态返回 409，不假装成功")
    void rejectConflictDetected() {
      when(candidates.findById("ddc_1"))
          .thenReturn(
              Optional.of(candidate("ddc_1", "ach_1", "ach_2", DedupCandidate.Status.OPEN)));
      when(candidates.resolve(eq("ddc_1"), eq("REJECTED"), any(), any())).thenReturn(false);
      assertThatThrownBy(() -> service.resolveCandidate("ddc_1", "REJECT", null, "理由", actor))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("候选状态已被其他操作改变");
    }

    @Test
    @DisplayName("MERGE 未指定幸存实体时报错：不猜哪一边留下")
    void mergeRequiresSurvivor() {
      when(candidates.findById("ddc_1"))
          .thenReturn(
              Optional.of(candidate("ddc_1", "ach_1", "ach_2", DedupCandidate.Status.OPEN)));
      assertThatThrownBy(() -> service.resolveCandidate("ddc_1", "MERGE", null, "理由", actor))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("必须显式指定幸存实体");
    }

    @Test
    @DisplayName("幸存实体必须在候选两端之内：防止把无关成果指定为幸存方")
    void survivorMustBeAnEndpoint() {
      when(candidates.findById("ddc_1"))
          .thenReturn(
              Optional.of(candidate("ddc_1", "ach_1", "ach_2", DedupCandidate.Status.OPEN)));
      assertThatThrownBy(() -> service.resolveCandidate("ddc_1", "MERGE", "ach_3", "理由", actor))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("候选两端之一");
    }

    @Test
    @DisplayName("MERGE 走合并流程：被合并方为候选的另一端")
    void mergeUsesOtherEndpoint() {
      when(candidates.findById("ddc_1"))
          .thenReturn(
              Optional.of(candidate("ddc_1", "ach_1", "ach_2", DedupCandidate.Status.OPEN)));
      when(achievements.findById("ach_2"))
          .thenReturn(Optional.of(achievement("ach_2", "Merged", null)));
      when(achievements.findById("ach_1"))
          .thenReturn(Optional.of(achievement("ach_1", "Survivor", null)));
      when(mergeTargets.snapshotContributions("ach_2")).thenReturn(List.of());
      when(mergeTargets.snapshotSources("ach_2")).thenReturn(List.of());
      when(mergeTargets.moveContributions("ach_2", "ach_1"))
          .thenReturn(new GovernanceRepositories.MergeTargetRepository.ContributionMove(2, 2));
      when(mergeTargets.markAchievementMerged("ach_2", "ach_1")).thenReturn(true);
      when(candidates.resolve(eq("ddc_1"), eq("MERGED"), any(), any())).thenReturn(true);

      service.resolveCandidate("ddc_1", "MERGE", "ach_1", "同一篇论文的两种收录", actor);

      verify(mergeTargets).markAchievementMerged("ach_2", "ach_1");
      verify(candidates).resolve("ddc_1", "MERGED", "acc_reviewer", "同一篇论文的两种收录");
    }
  }

  // ---------------------------------------------------------------------------
  // 合并
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("成果合并：台账、审计与事件三者缺一不可")
  class MergeAchievements {

    @Test
    @DisplayName("幸存成果已被合并时拒绝：必须先解析到最终实体，否则形成重定向链")
    void survivorAlreadyMerged() {
      // 两个实体都要存在：重定向链检查发生在两次查找之后，只 stub 一个会让测试测到别的分支。
      when(achievements.findById("ach_1"))
          .thenReturn(Optional.of(achievement("ach_1", "A", "ach_9")));
      when(achievements.findById("ach_2")).thenReturn(Optional.of(achievement("ach_2", "B", null)));
      assertThatThrownBy(() -> service.mergeAchievements("ach_1", "ach_2", "理由", actor))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("已被合并");
    }

    @Test
    @DisplayName("被合并成果已被合并过时拒绝：不能重复合并")
    void mergedAlreadyMerged() {
      when(achievements.findById("ach_1")).thenReturn(Optional.of(achievement("ach_1", "A", null)));
      when(achievements.findById("ach_2"))
          .thenReturn(Optional.of(achievement("ach_2", "B", "ach_8")));
      assertThatThrownBy(() -> service.mergeAchievements("ach_1", "ach_2", "理由", actor))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("不能重复合并");
    }

    @Test
    @DisplayName("成果不存在返回 NOT_FOUND")
    void missingAchievement() {
      when(achievements.findById("ach_1")).thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.mergeAchievements("ach_1", "ach_2", "理由", actor))
          .isInstanceOf(BusinessException.class)
          .satisfies(
              ex ->
                  assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("关系迁移超过单次上限时中止：避免误操作一次吞掉超大实体")
    void moveLimitEnforced() {
      when(achievements.findById("ach_1")).thenReturn(Optional.of(achievement("ach_1", "A", null)));
      when(achievements.findById("ach_2")).thenReturn(Optional.of(achievement("ach_2", "B", null)));
      when(mergeTargets.snapshotContributions("ach_2")).thenReturn(List.of());
      when(mergeTargets.snapshotSources("ach_2")).thenReturn(List.of());
      when(mergeTargets.moveContributions("ach_2", "ach_1"))
          .thenReturn(new GovernanceRepositories.MergeTargetRepository.ContributionMove(0, 20_001));

      assertThatThrownBy(() -> service.mergeAchievements("ach_1", "ach_2", "理由", actor))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("超过单次上限");
      verify(mergeTargets, never()).markAchievementMerged(anyString(), anyString());
    }

    @Test
    @DisplayName("标记重定向失败返回 409：状态已被改变时不留下半个合并")
    void markMergedConflict() {
      when(achievements.findById("ach_1")).thenReturn(Optional.of(achievement("ach_1", "A", null)));
      when(achievements.findById("ach_2")).thenReturn(Optional.of(achievement("ach_2", "B", null)));
      when(mergeTargets.snapshotContributions("ach_2")).thenReturn(List.of());
      when(mergeTargets.snapshotSources("ach_2")).thenReturn(List.of());
      when(mergeTargets.moveContributions("ach_2", "ach_1"))
          .thenReturn(new GovernanceRepositories.MergeTargetRepository.ContributionMove(1, 1));
      when(mergeTargets.markAchievementMerged("ach_2", "ach_1")).thenReturn(false);

      assertThatThrownBy(() -> service.mergeAchievements("ach_1", "ach_2", "理由", actor))
          .isInstanceOf(BusinessException.class)
          .satisfies(
              ex ->
                  assertThat(((BusinessException) ex).errorCode())
                      .isEqualTo(ErrorCode.VERSION_CONFLICT));
      verifyNoInteractions(ledger);
    }

    @Test
    @DisplayName("成功合并：写台账（含前后快照）、写审计、发布 ACHIEVEMENT_MERGED 事件")
    void happyPath() {
      stubSuccessfulMerge();

      service.mergeAchievements("ach_1", "ach_2", "同一篇论文重复收录", actor);

      ArgumentCaptor<MergeLedgerEntry> entryCaptor =
          ArgumentCaptor.forClass(MergeLedgerEntry.class);
      verify(ledger).append(entryCaptor.capture());
      MergeLedgerEntry entry = entryCaptor.getValue();
      assertThat(entry.operation()).isEqualTo(MergeLedgerEntry.Operation.MERGE);
      assertThat(entry.survivorId()).isEqualTo("ach_1");
      assertThat(entry.mergedId()).isEqualTo("ach_2");
      assertThat(entry.entityType()).isEqualTo("ACHIEVEMENT");
      assertThat(entry.operatorId()).isEqualTo("acc_reviewer");
      assertThat(entry.snapshot()).containsKeys("contributionsBefore", "sourcesBefore");
      assertThat(entry.snapshot()).containsEntry("mergedTitle", "Merged Title");
      assertThat(entry.snapshot()).containsEntry("contributionsInserted", 1);
      // 稳定标识改指幸存实体，并把合并前标识写进快照：不转移会让相同标识继续命中已撤回实体。
      assertThat(entry.snapshot()).containsEntry("identifiersMoved", 1);
      assertThat(entry.snapshot().get("identifiersBefore")).isNotNull();

      ArgumentCaptor<AuditRecorder.AuditEntry> auditCaptor =
          ArgumentCaptor.forClass(AuditRecorder.AuditEntry.class);
      verify(audit).record(auditCaptor.capture());
      assertThat(auditCaptor.getValue().action()).isEqualTo("ACHIEVEMENT_MERGED");
      assertThat(auditCaptor.getValue().beforeValue()).containsEntry("title", "Merged Title");
      assertThat(auditCaptor.getValue().afterValue()).containsEntry("mergedInto", "ach_1");

      verify(outbox).append(any());
      verify(mergeTargets).moveSourceLinks("ach_2", "ach_1");
      verify(mergeTargets).moveTopicLinks("ach_2", "ach_1");
      // 候选问的是「这两条是不是同一条」，合并就是回答：留着 OPEN 会变成处理不掉的死条目。
      assertThat(entry.snapshot()).containsEntry("candidatesClosedWithMerge", 2);
      verify(candidates)
          .resolveOpenBetween(
              eq("ach_1"), eq("ach_2"), eq("MERGED"), eq("acc_reviewer"), anyString());
    }

    @Test
    @DisplayName("没有治理能力的操作者不能合并：合并必须由治理角色发起，可追溯到具体的人")
    void systemActorCannotMerge() {
      // 「相似标题不得直接合并」的反面：连系统主体也不能自动合并。合并没有系统入口，
      // 只有治理角色的人工调用；无操作者（null）与无治理能力的系统主体都被拒绝。
      ActorContext system =
          new ActorContext(
              new AccountId("system"), RoleNames.SYSTEM, RoleNames.SYSTEM, Set.of(RoleNames.SYSTEM), 0L);

      assertThatThrownBy(() -> service.mergeAchievements("ach_1", "ach_2", "自动治理", null))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("请先登录");
      assertThatThrownBy(() -> service.mergeAchievements("ach_1", "ach_2", "自动治理", system))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("能力");
      verifyNoInteractions(ledger);
    }

    private void stubSuccessfulMerge() {
      when(achievements.findById("ach_1"))
          .thenReturn(Optional.of(achievement("ach_1", "Survivor", null)));
      when(achievements.findById("ach_2"))
          .thenReturn(Optional.of(achievement("ach_2", "Merged Title", null)));
      when(mergeTargets.snapshotContributions("ach_2")).thenReturn(List.of("c1"));
      when(mergeTargets.snapshotSources("ach_2")).thenReturn(List.of("s1"));
      when(mergeTargets.previewIdentifiers("ACHIEVEMENT", "ach_2"))
          .thenReturn(
              List.of(
                  new GovernanceRepositories.MergeTargetRepository.RegisteredIdentifier(
                      "DOI", "", "10.1000/xyz")));
      when(mergeTargets.moveContributions("ach_2", "ach_1"))
          .thenReturn(new GovernanceRepositories.MergeTargetRepository.ContributionMove(1, 1));
      when(mergeTargets.moveIdentifiers("ACHIEVEMENT", "ach_2", "ach_1")).thenReturn(1);
      when(mergeTargets.markAchievementMerged("ach_2", "ach_1")).thenReturn(true);
      when(candidates.resolveOpenBetween(
              eq("ach_1"), eq("ach_2"), eq("MERGED"), anyString(), anyString()))
          .thenReturn(2);
    }
  }

  @Nested
  @DisplayName("学者合并：同名不是同人证据")
  class MergeScholars {

    @Test
    @DisplayName("任一方已合并即拒绝")
    void alreadyMerged() {
      when(scholars.findById("sch_1")).thenReturn(Optional.of(scholar("sch_1", "张三", "sch_9")));
      when(scholars.findById("sch_2")).thenReturn(Optional.of(scholar("sch_2", "张三", null)));
      assertThatThrownBy(() -> service.mergeScholars("sch_1", "sch_2", "理由", actor))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("已被合并");
    }

    @Test
    @DisplayName("缺少理由时拒绝：同名合并是高风险操作，必须留下书面依据")
    void reasonRequired() {
      when(scholars.findById("sch_1")).thenReturn(Optional.of(scholar("sch_1", "张三", null)));
      when(scholars.findById("sch_2")).thenReturn(Optional.of(scholar("sch_2", "张三", null)));
      assertThatThrownBy(() -> service.mergeScholars("sch_1", "sch_2", "", actor))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("必须说明裁决理由");
    }

    @Test
    @DisplayName("姓名不同且没有共同稳定标识的两位学者不能合并：那不是归一化差异，是把两个人并成一个")
    void differentNameWithoutSharedIdentifierRejected() {
      when(scholars.findById("sch_1")).thenReturn(Optional.of(scholar("sch_1", "张三", null)));
      when(scholars.findById("sch_2")).thenReturn(Optional.of(scholar("sch_2", "李四", null)));
      // 两人各有自己的 ORCID，只是不同：这是「两个不同的人」，不是「同一人缺标识」。
      when(mergeTargets.previewIdentifiers("SCHOLAR", "sch_1"))
          .thenReturn(
              List.of(
                  new GovernanceRepositories.MergeTargetRepository.RegisteredIdentifier(
                      "ORCID", "person", "0000-0002-1825-0097")));
      when(mergeTargets.previewIdentifiers("SCHOLAR", "sch_2"))
          .thenReturn(
              List.of(
                  new GovernanceRepositories.MergeTargetRepository.RegisteredIdentifier(
                      "ORCID", "person", "0000-0001-5109-3700")));

      assertThatThrownBy(() -> service.mergeScholars("sch_1", "sch_2", "看起来像同一人", actor))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("不能合并");

      verify(mergeTargets, never()).moveScholarContributions(anyString(), anyString());
      verify(ledger, never()).append(any());
    }

    @Test
    @DisplayName("姓名不同但共享 ORCID 时可以合并，台账记下依据是共享标识")
    void sharedIdentifierAllowsMerge() {
      when(scholars.findById("sch_1")).thenReturn(Optional.of(scholar("sch_1", "Zhang San", null)));
      when(scholars.findById("sch_2")).thenReturn(Optional.of(scholar("sch_2", "San Zhang", null)));
      when(mergeTargets.previewIdentifiers("SCHOLAR", "sch_1"))
          .thenReturn(
              List.of(
                  new GovernanceRepositories.MergeTargetRepository.RegisteredIdentifier(
                      "ORCID", "person", "0000-0002-1825-0097")));
      when(mergeTargets.previewIdentifiers("SCHOLAR", "sch_2"))
          .thenReturn(
              List.of(
                  new GovernanceRepositories.MergeTargetRepository.RegisteredIdentifier(
                      "ORCID", "person", "0000-0002-1825-0097")));
      when(mergeTargets.findActiveAuthorityId(anyString())).thenReturn(Optional.empty());
      when(mergeTargets.snapshotScholarContributions("sch_2")).thenReturn(List.of());
      when(mergeTargets.moveScholarContributions("sch_2", "sch_1"))
          .thenReturn(new GovernanceRepositories.MergeTargetRepository.ContributionMove(0, 0));
      when(mergeTargets.markScholarMerged("sch_2", "sch_1")).thenReturn(true);

      service.mergeScholars("sch_1", "sch_2", "ORCID 相同", actor);

      ArgumentCaptor<MergeLedgerEntry> captor = ArgumentCaptor.forClass(MergeLedgerEntry.class);
      verify(ledger).append(captor.capture());
      assertThat(captor.getValue().snapshot()).containsEntry("mergeBasis", "SHARED_IDENTIFIER");
    }

    @Test
    @DisplayName("只有被合并门户有管理者时，授权转到幸存门户")
    void authorityTransferred() {
      when(scholars.findById("sch_1")).thenReturn(Optional.of(scholar("sch_1", "张三", null)));
      when(scholars.findById("sch_2")).thenReturn(Optional.of(scholar("sch_2", "张三", null)));
      when(mergeTargets.findActiveAuthorityId("sch_1")).thenReturn(Optional.empty());
      when(mergeTargets.findActiveAuthorityId("sch_2")).thenReturn(Optional.of("auth_2"));
      when(mergeTargets.snapshotScholarContributions("sch_2")).thenReturn(List.of());
      when(mergeTargets.moveScholarContributions("sch_2", "sch_1"))
          .thenReturn(new GovernanceRepositories.MergeTargetRepository.ContributionMove(1, 1));
      when(mergeTargets.moveActiveAuthority("sch_2", "sch_1")).thenReturn(1);
      when(mergeTargets.markScholarMerged("sch_2", "sch_1")).thenReturn(true);

      service.mergeScholars("sch_1", "sch_2", "同一人", actor);

      verify(mergeTargets).moveActiveAuthority("sch_2", "sch_1");
      verify(mergeTargets, never()).revokeAuthorityOfScholar(anyString(), anyString());
      ArgumentCaptor<MergeLedgerEntry> captor = ArgumentCaptor.forClass(MergeLedgerEntry.class);
      verify(ledger).append(captor.capture());
      assertThat(captor.getValue().snapshot()).containsEntry("authorityTransferred", true);
      assertThat(captor.getValue().entityType()).isEqualTo("SCHOLAR");
    }

    @Test
    @DisplayName("双方都有管理者时不自动转移，改为撤销被合并方授权并留待人工重新授予")
    void bothAuthoritiesRevokedNotMoved() {
      when(scholars.findById("sch_1")).thenReturn(Optional.of(scholar("sch_1", "张三", null)));
      when(scholars.findById("sch_2")).thenReturn(Optional.of(scholar("sch_2", "张三", null)));
      when(mergeTargets.findActiveAuthorityId("sch_1")).thenReturn(Optional.of("auth_1"));
      when(mergeTargets.findActiveAuthorityId("sch_2")).thenReturn(Optional.of("auth_2"));
      when(mergeTargets.snapshotScholarContributions("sch_2")).thenReturn(List.of());
      when(mergeTargets.moveScholarContributions("sch_2", "sch_1"))
          .thenReturn(new GovernanceRepositories.MergeTargetRepository.ContributionMove(0, 0));
      when(mergeTargets.revokeAuthorityOfScholar(eq("sch_2"), anyString())).thenReturn(1);
      when(mergeTargets.markScholarMerged("sch_2", "sch_1")).thenReturn(true);

      service.mergeScholars("sch_1", "sch_2", "同一人", actor);

      verify(mergeTargets, never()).moveActiveAuthority(anyString(), anyString());
      verify(mergeTargets).revokeAuthorityOfScholar("sch_2", "学者合并：双方均有管理者，需人工重新授予");
      ArgumentCaptor<MergeLedgerEntry> captor = ArgumentCaptor.forClass(MergeLedgerEntry.class);
      verify(ledger).append(captor.capture());
      assertThat(captor.getValue().snapshot()).containsEntry("authorityRevokedOnMerge", true);
      assertThat(captor.getValue().snapshot()).containsEntry("mergedHasAuthority", true);
    }

    @Test
    @DisplayName("双方都没有管理者时不做任何授权处置")
    void noAuthorityNoChange() {
      when(scholars.findById("sch_1")).thenReturn(Optional.of(scholar("sch_1", "张三", null)));
      when(scholars.findById("sch_2")).thenReturn(Optional.of(scholar("sch_2", "张三", null)));
      when(mergeTargets.findActiveAuthorityId("sch_1")).thenReturn(Optional.empty());
      when(mergeTargets.findActiveAuthorityId("sch_2")).thenReturn(Optional.empty());
      when(mergeTargets.snapshotScholarContributions("sch_2")).thenReturn(List.of());
      when(mergeTargets.moveScholarContributions("sch_2", "sch_1"))
          .thenReturn(new GovernanceRepositories.MergeTargetRepository.ContributionMove(0, 0));
      when(mergeTargets.markScholarMerged("sch_2", "sch_1")).thenReturn(true);

      service.mergeScholars("sch_1", "sch_2", "同一人", actor);

      verify(mergeTargets, never()).moveActiveAuthority(anyString(), anyString());
      verify(mergeTargets, never()).revokeAuthorityOfScholar(anyString(), anyString());
      ArgumentCaptor<MergeLedgerEntry> captor = ArgumentCaptor.forClass(MergeLedgerEntry.class);
      verify(ledger).append(captor.capture());
      // 双方都没有授权时台账不写 authorityTransferred：没有发生转移，就不能留下「已转移」的痕迹。
      assertThat(captor.getValue().snapshot()).doesNotContainKey("authorityTransferred");
      assertThat(captor.getValue().snapshot()).doesNotContainKey("authorityRevokedOnMerge");
    }

    @Test
    @DisplayName("成功合并发布 SCHOLAR_MERGED 事件")
    void publishesEvent() {
      when(scholars.findById("sch_1")).thenReturn(Optional.of(scholar("sch_1", "张三", null)));
      when(scholars.findById("sch_2")).thenReturn(Optional.of(scholar("sch_2", "张三", null)));
      when(mergeTargets.findActiveAuthorityId(anyString())).thenReturn(Optional.empty());
      when(mergeTargets.snapshotScholarContributions("sch_2")).thenReturn(List.of());
      when(mergeTargets.moveScholarContributions("sch_2", "sch_1"))
          .thenReturn(new GovernanceRepositories.MergeTargetRepository.ContributionMove(0, 0));
      when(mergeTargets.markScholarMerged("sch_2", "sch_1")).thenReturn(true);

      service.mergeScholars("sch_1", "sch_2", "同一人", actor);

      verify(outbox).append(any());
      verify(audit).record(any());
    }
  }

  @Nested
  @DisplayName("异常处理与分页查询")
  class Queries {

    @Test
    @DisplayName("异常不存在或已处理返回 409，不吞掉")
    void resolveAnomalyMissing() {
      when(anomalies.resolve("anm_1")).thenReturn(false);
      assertThatThrownBy(() -> service.resolveAnomaly("anm_1", actor))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("不存在或已处理");
    }

    @Test
    @DisplayName("异常处理成功写审计")
    void resolveAnomalyWritesAudit() {
      when(anomalies.resolve("anm_1")).thenReturn(true);
      service.resolveAnomaly("anm_1", actor);
      ArgumentCaptor<AuditRecorder.AuditEntry> captor =
          ArgumentCaptor.forClass(AuditRecorder.AuditEntry.class);
      verify(audit).record(captor.capture());
      assertThat(captor.getValue().action()).isEqualTo("GOVERNANCE_ANOMALY_RESOLVED");
    }

    @Test
    @DisplayName("列表查询按分页参数透传，不自行改动偏移")
    void listDelegatesWithPaging() {
      when(candidates.listOpenCandidates(anyInt(), anyInt())).thenReturn(List.of());
      service.listCandidates(new PageRequest(3, 20), actor);
      verify(candidates).listOpenCandidates(20, 40);

      when(anomalies.listOpenAnomalies(anyInt(), anyInt())).thenReturn(List.of());
      service.listAnomalies(new PageRequest(2, 50), actor);
      verify(anomalies).listOpenAnomalies(50, 50);
    }

    @Test
    @DisplayName("台账按实体查询透传 limit")
    void ledgerOfDelegates() {
      when(ledger.listByEntity(eq("ach_1"), anyInt())).thenReturn(List.of());
      service.ledgerOf("ach_1", 25, actor);
      verify(ledger).listByEntity("ach_1", 25);
    }
  }

  // ---------------------------------------------------------------------------
  // 工作台：筛选、分档与批量裁决
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("治理工作台")
  class Workbench {

    @Test
    @DisplayName("未给排序键时落到相似度降序：默认顺序就是「最可能重复的排在最前」")
    void defaultSortIsSimilarityDesc() {
      assertThat(GovernanceService.CandidateFilter.of(null, null).sortBy())
          .isEqualTo(GovernanceService.CandidateFilter.SORT_SIMILARITY_DESC);
      assertThat(GovernanceService.CandidateFilter.of(null, "  ").sortBy())
          .isEqualTo("similarity_desc");
    }

    @Test
    @DisplayName("未知排序键报错而不静默回落：界面写着按相似度排、实际按时间排，没人看得出来")
    void unknownSortRejected() {
      assertThatThrownBy(() -> GovernanceService.CandidateFilter.of(null, "title_desc"))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("排序键必须是以下之一");
    }

    @Test
    @DisplayName("相似度下限越界被拒：允许 1.5 等于让筛选条件永远筛不出任何东西")
    void similarityRangeChecked() {
      assertThatThrownBy(() -> GovernanceService.CandidateFilter.of(1.5d, null))
          .isInstanceOf(BusinessException.class);
      assertThatThrownBy(() -> GovernanceService.CandidateFilter.of(-0.1d, null))
          .isInstanceOf(BusinessException.class);
      assertThat(GovernanceService.CandidateFilter.of(1d, null).minSimilarity()).isEqualTo(1d);
    }

    @Test
    @DisplayName("列表按下传的相似度与排序取件，偏移由页码换算而来")
    void listDelegatesFilterAndPaging() {
      when(candidates.listOpenCandidatesScored(0.7d, "created_asc", 20, 40)).thenReturn(List.of());

      service.listCandidatesForWorkbench(
          GovernanceService.CandidateFilter.of(0.7d, "created_asc"), new PageRequest(3, 20), actor);

      verify(candidates).listOpenCandidatesScored(0.7d, "created_asc", 20, 40);
    }

    @Test
    @DisplayName("概览把分档计数与阈值一起给出：界面不另立一套阈值，否则两边会各说各话")
    void summaryCarriesBucketsAndThresholds() {
      when(candidates.countOpenCandidatesByBucket(0.90d, 0.70d))
          .thenReturn(new GovernanceRepositories.CandidateBuckets(9L, 8L, 7L, 24L));

      GovernanceService.WorkbenchSummary summary = service.workbenchSummary(actor);

      assertThat(summary.high()).isEqualTo(9L);
      assertThat(summary.mid()).isEqualTo(8L);
      assertThat(summary.low()).isEqualTo(7L);
      assertThat(summary.total()).isEqualTo(24L);
      assertThat(summary.highLow()).isEqualTo(0.90d);
      assertThat(summary.midLow()).isEqualTo(0.70d);
    }

    @Test
    @DisplayName("筛选计数与列表用同一个条件：两处若不平行，界面会一边说命中 0 条一边列出 50 条")
    void countUsesSameFilterAsList() {
      when(candidates.countOpenCandidatesByFilter(0.9d)).thenReturn(3L);

      assertThat(
              service.countCandidatesForWorkbench(GovernanceService.CandidateFilter.of(0.9d, null), actor))
          .isEqualTo(3L);
      verify(candidates).countOpenCandidatesByFilter(0.9d);
    }

    @Test
    @DisplayName("批量驳回逐条独立裁决：不存在的与已裁决的跳过并说明原因，成功的把两端成果交回重评估")
    void batchRejectMixesOutcomes() {
      when(candidates.findById("ddc_ok"))
          .thenReturn(
              Optional.of(candidate("ddc_ok", "ach_1", "ach_2", DedupCandidate.Status.OPEN)));
      when(candidates.findById("ddc_done"))
          .thenReturn(
              Optional.of(candidate("ddc_done", "ach_3", "ach_4", DedupCandidate.Status.REJECTED)));
      when(candidates.findById("ddc_missing")).thenReturn(Optional.empty());
      when(candidates.resolve(eq("ddc_ok"), eq("REJECTED"), anyString(), eq("依据")))
          .thenReturn(true);

      GovernanceService.BatchResolveOutcome outcome =
          service.batchReject(List.of("ddc_ok", "ddc_done", "ddc_missing"), "依据", actor);

      assertThat(outcome.requested()).isEqualTo(3);
      assertThat(outcome.resolved()).isEqualTo(1);
      assertThat(outcome.skipped()).hasSize(2);
      assertThat(String.join("；", outcome.skipped())).contains("候选不存在").contains("已裁决");
      assertThat(outcome.affectedAchievementIds()).containsExactly("ach_1", "ach_2");
    }

    @Test
    @DisplayName("状态已被他人改变时该条跳过，不整批回滚：一条竞争的候选不该让另外 199 条白点")
    void batchRejectSkipsContendedRow() {
      when(candidates.findById("ddc_1"))
          .thenReturn(
              Optional.of(candidate("ddc_1", "ach_1", "ach_2", DedupCandidate.Status.OPEN)));
      when(candidates.resolve(eq("ddc_1"), eq("REJECTED"), anyString(), anyString()))
          .thenReturn(false);

      GovernanceService.BatchResolveOutcome outcome =
          service.batchReject(List.of("ddc_1"), "依据", actor);

      assertThat(outcome.resolved()).isZero();
      assertThat(String.join("；", outcome.skipped())).contains("状态已被其他操作改变");
      assertThat(outcome.affectedAchievementIds()).isEmpty();
    }

    @Test
    @DisplayName("批量条数超上限直接拒绝：一次点击的责任范围必须可见、可复核")
    void batchLimitEnforced() {
      List<String> tooMany = new ArrayList<>();
      for (int i = 0; i < 201; i++) {
        tooMany.add("ddc_" + i);
      }

      assertThatThrownBy(() -> service.batchReject(tooMany, "依据", actor))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("上限");
    }

    @Test
    @DisplayName("批量驳回同样要求写明依据：没有依据的批量操作事后无法复核")
    void batchRejectRequiresReason() {
      assertThatThrownBy(() -> service.batchReject(List.of("ddc_1"), "   ", actor))
          .isInstanceOf(BusinessException.class);
      assertThatThrownBy(() -> service.batchReject(List.of(), "依据", actor))
          .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("重复候选标识只处理一次：同一批里重复出现不该被记成两条裁决")
    void batchRejectDeduplicatesIds() {
      when(candidates.findById("ddc_1"))
          .thenReturn(
              Optional.of(candidate("ddc_1", "ach_1", "ach_2", DedupCandidate.Status.OPEN)));
      when(candidates.resolve(eq("ddc_1"), eq("REJECTED"), anyString(), anyString()))
          .thenReturn(true);

      GovernanceService.BatchResolveOutcome outcome =
          service.batchReject(List.of("ddc_1", "ddc_1"), "依据", actor);

      assertThat(outcome.resolved()).isEqualTo(1);
      verify(candidates, org.mockito.Mockito.times(1))
          .resolve(eq("ddc_1"), eq("REJECTED"), anyString(), anyString());
    }
  }

  @Nested
  @DisplayName("合并影响预览：把看不见的后果提前说清楚")
  class MergePreview {

    private GovernanceRepositories.MergeTargetRepository.PreviewContribution contribution(
        String scholarId, String name, String state) {
      return new GovernanceRepositories.MergeTargetRepository.PreviewContribution(
          scholarId, name, state, "AUTHOR");
    }

    private GovernanceRepositories.MergeTargetRepository.PreviewSource source(String recordId) {
      return new GovernanceRepositories.MergeTargetRepository.PreviewSource(
          "src_crossref", recordId);
    }

    private GovernanceRepositories.MergeTargetRepository.RegisteredIdentifier doi(String value) {
      return new GovernanceRepositories.MergeTargetRepository.RegisteredIdentifier(
          "DOI", "", value);
    }

    private void givenPair() {
      when(achievements.findById("ach_1"))
          .thenReturn(Optional.of(achievement("ach_1", "Same Title", null)));
      when(achievements.findById("ach_2"))
          .thenReturn(Optional.of(achievement("ach_2", "Same Title", null)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> data, String key) {
      return (Map<String, Object>) data.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> data, String key) {
      return (List<Map<String, Object>>) data.get(key);
    }

    @Test
    @DisplayName("参与关系分成「会迁移」与「与幸存侧合并为一条」两类，两侧都有同学的参与关系只保留一条")
    void contributionsSplit() {
      givenPair();
      when(mergeTargets.previewContributions("ach_1"))
          .thenReturn(
              List.of(contribution("sch_1", "甲", "AUTO"), contribution("sch_2", "乙", "AUTO")));
      when(mergeTargets.previewContributions("ach_2"))
          .thenReturn(
              List.of(contribution("sch_2", "乙", "AUTO"), contribution("sch_3", "丙", "AUTO")));

      Map<String, Object> data = service.previewMerge("ach_1", "ach_2", actor);

      Map<String, Object> contributions = section(data, "contributions");
      assertThat(contributions)
          .containsEntry("survivorRows", 2)
          .containsEntry("mergedRows", 2)
          .containsEntry("willTransfer", 1)
          .containsEntry("willMergeIntoExisting", 1)
          .containsEntry("resultingRows", 3);
      List<Map<String, Object>> transfers = rows(contributions, "transferList");
      assertThat(transfers).hasSize(1);
      assertThat(transfers.get(0))
          .containsEntry("scholarId", "sch_3")
          .containsEntry("scholarName", "丙");
      List<Map<String, Object>> duplicates = rows(contributions, "duplicateList");
      assertThat(duplicates).hasSize(1);
      assertThat(duplicates.get(0))
          .containsEntry("scholarId", "sch_2")
          .containsEntry("stateDiffers", false);
    }

    @Test
    @DisplayName("两侧状态不同的学者会被点明：合并保留幸存侧状态，不自动取更强的那个")
    void conflictingRelationStatesWarned() {
      givenPair();
      when(mergeTargets.previewContributions("ach_1"))
          .thenReturn(List.of(contribution("sch_2", "乙", "AUTO")));
      when(mergeTargets.previewContributions("ach_2"))
          .thenReturn(List.of(contribution("sch_2", "乙", "SELF_CONFIRMED")));

      Map<String, Object> data = service.previewMerge("ach_1", "ach_2", actor);

      List<Map<String, Object>> duplicates = rows(section(data, "contributions"), "duplicateList");
      assertThat(duplicates).hasSize(1);
      assertThat(duplicates.get(0))
          .containsEntry("stateDiffers", true)
          .containsEntry("survivorRelationState", "AUTO")
          .containsEntry("relationState", "SELF_CONFIRMED");
      // 状态不互相推导这句话写在逐条后果里，汇总警告只负责让人注意到有这么一条。
      assertThat(duplicates.get(0).get("effect")).asString().contains("不得互相推导");
      assertThat((List<String>) data.get("warnings"))
          .anySatisfy(warning -> assertThat(warning).contains("保留幸存侧状态"));
    }

    @Test
    @DisplayName("标题或年份不同时明确告知元数据不会随合并转移，且只字不提「已自动合并元数据」")
    void metadataIsNotMerged() {
      when(achievements.findById("ach_1"))
          .thenReturn(
              Optional.of(
                  achievementOf(
                      "ach_1",
                      "Survivor Title",
                      Visibility.PUBLIC,
                      EffectiveStatus.EFFECTIVE,
                      2024)));
      when(achievements.findById("ach_2"))
          .thenReturn(
              Optional.of(
                  achievementOf(
                      "ach_2",
                      "Merged Title",
                      Visibility.PUBLIC,
                      EffectiveStatus.EFFECTIVE,
                      2023)));

      Map<String, Object> data = service.previewMerge("ach_1", "ach_2", actor);

      assertThat((List<String>) data.get("warnings"))
          .anySatisfy(
              warning ->
                  assertThat(warning)
                      .contains("不会写入幸存成果")
                      .contains("Merged Title")
                      .contains("2023"));
    }

    @Test
    @DisplayName("标题与年份都一致时不报元数据警告：年份是 Integer，用 == 比较会把同年判成不同年")
    void equalMetadataProducesNoWarning() {
      givenPair();
      when(mergeTargets.previewContributions(anyString())).thenReturn(List.of());

      Map<String, Object> data = service.previewMerge("ach_1", "ach_2", actor);

      assertThat((List<String>) data.get("warnings"))
          .noneSatisfy(warning -> assertThat(warning).contains("不会写入幸存成果"));
    }

    @Test
    @DisplayName("年份未知时如实写「年份未知」，不补一个看起来正常的年份")
    void unknownYearRenderedHonestly() {
      when(achievements.findById("ach_1"))
          .thenReturn(
              Optional.of(
                  new Achievement(
                      "ach_1",
                      AchievementType.PAPER,
                      "Same Title",
                      "same title",
                      DataNature.REAL,
                      Visibility.PUBLIC,
                      EffectiveStatus.EFFECTIVE,
                      PublicationYear.unknown("来源未提供"),
                      null,
                      1L,
                      Instant.parse("2026-01-01T00:00:00Z"),
                      Instant.parse("2026-01-01T00:00:00Z"))));
      when(achievements.findById("ach_2"))
          .thenReturn(
              Optional.of(
                  achievementOf(
                      "ach_2", "Other Title", Visibility.PUBLIC, EffectiveStatus.EFFECTIVE, 2024)));

      Map<String, Object> data = service.previewMerge("ach_1", "ach_2", actor);

      assertThat((List<String>) data.get("warnings"))
          .anySatisfy(warning -> assertThat(warning).contains("（未知）"));
      // 响应包里年份与年份标签分开给：全局 Jackson 配置会丢掉 null 字段，
      // 只给一个可空的 publicationYear 时，前端拿到 undefined 会把「未知」渲染成空白。
      assertThat(section(data, "survivor"))
          .containsEntry("publicationYear", null)
          .containsEntry("yearKnown", false)
          .containsEntry("publicationYearLabel", "未知");
    }

    @Test
    @DisplayName("来源归属因幸存侧已有同一来源记录而跳过时，说明这不是丢失并列出被跳过的记录")
    void duplicateSourcesExplained() {
      givenPair();
      when(mergeTargets.previewSources("ach_1")).thenReturn(List.of(source("rec_1")));
      when(mergeTargets.previewSources("ach_2"))
          .thenReturn(List.of(source("rec_1"), source("rec_2")));

      Map<String, Object> sources = section(service.previewMerge("ach_1", "ach_2", actor), "sources");

      assertThat(sources)
          .containsEntry("survivorRows", 1)
          .containsEntry("mergedRows", 2)
          .containsEntry("willTransfer", 1)
          .containsEntry("willSkipDuplicate", 1)
          .containsEntry("resultingRows", 2);
      assertThat((List<String>) sources.get("skippedSourceRecordIds")).containsExactly("rec_1");
    }

    @Test
    @DisplayName("幸存侧没有稳定标识时明确提示：自动发布条件在合并之后才成立")
    void identifierTransferIsAnnounced() {
      givenPair();
      when(mergeTargets.previewIdentifiers("ACHIEVEMENT", "ach_1")).thenReturn(List.of());
      when(mergeTargets.previewIdentifiers("ACHIEVEMENT", "ach_2"))
          .thenReturn(List.of(doi("10.1000/xyz")));

      Map<String, Object> data = service.previewMerge("ach_1", "ach_2", actor);

      Map<String, Object> identifiers = section(data, "identifiers");
      assertThat(identifiers)
          .containsEntry("survivorCount", 0)
          .containsEntry("mergedCount", 1)
          .containsEntry("willTransfer", 1)
          .containsEntry("resultingCount", 1);
      assertThat(identifiers.get("effect")).asString().contains("改指幸存实体");
      assertThat((List<String>) data.get("warnings"))
          .anySatisfy(warning -> assertThat(warning).contains("自动发布条件在合并之后才成立"));
    }

    @Test
    @DisplayName("任一侧已被合并时给出阻断原因，但仍返回两侧概要：预览要能解释「为什么不能合」")
    void blockersExplainedButSummaryStillReturned() {
      when(achievements.findById("ach_1")).thenReturn(Optional.of(achievement("ach_1", "A", null)));
      when(achievements.findById("ach_2"))
          .thenReturn(Optional.of(achievement("ach_2", "B", "ach_9")));

      Map<String, Object> data = service.previewMerge("ach_1", "ach_2", actor);

      assertThat(data).containsEntry("canMerge", false);
      assertThat((List<String>) data.get("blockers"))
          .anySatisfy(reason -> assertThat(reason).contains("已被合并过"));
      assertThat(section(data, "merged")).containsEntry("merged", true);
      // 被阻断的预览不能只在内部把 canMerge 置假，却仍用「会迁移 N 条」的既成语气描述后果：
      // 执行路径会拒绝这次合并，那些后果一条都不会发生。
      assertThat((List<String>) data.get("warnings"))
          .anySatisfy(warning -> assertThat(warning).contains("不可执行"));
    }

    @Test
    @DisplayName("迁移行数超过单次上限时提前提示，而不是等执行时才报错")
    void moveLimitSurfacedBeforeExecution() {
      givenPair();
      List<GovernanceRepositories.MergeTargetRepository.PreviewContribution> huge =
          new ArrayList<>();
      for (int i = 0; i < 20_001; i++) {
        huge.add(contribution("sch_" + i, "学者" + i, "AUTO"));
      }
      when(mergeTargets.previewContributions("ach_1")).thenReturn(List.of());
      when(mergeTargets.previewContributions("ach_2")).thenReturn(huge);

      Map<String, Object> data = service.previewMerge("ach_1", "ach_2", actor);

      assertThat(section(data, "effects")).containsEntry("exceedsMoveLimit", true);
      assertThat((List<String>) data.get("warnings"))
          .anySatisfy(warning -> assertThat(warning).contains("超过单次迁移上限"));
    }

    @Test
    @DisplayName("两条实体之间的候选会被本次合并一并裁决，预览里说明这一点")
    void openCandidatesBetweenMentioned() {
      givenPair();
      when(candidates.countOpenBetween("ach_1", "ach_2")).thenReturn(2L);

      Map<String, Object> data = service.previewMerge("ach_1", "ach_2", actor);

      assertThat(section(data, "effects")).containsEntry("candidatesClosedWithMerge", 2L);
      assertThat((List<String>) data.get("warnings"))
          .anySatisfy(warning -> assertThat(warning).contains("仍有 2 条待治理候选").contains("一并被标记为已合并"));
    }

    @Test
    @DisplayName("被合并方牵涉的其它候选不会被本次合并处置，必须单独提示")
    void otherCandidatesOnMergedSideWarned() {
      givenPair();
      when(candidates.countOpenBetween("ach_1", "ach_2")).thenReturn(1L);
      when(candidates.countOpenFor("ach_2")).thenReturn(4L);

      Map<String, Object> data = service.previewMerge("ach_1", "ach_2", actor);

      assertThat(section(data, "effects"))
          .containsEntry("candidatesClosedWithMerge", 1L)
          .containsEntry("otherCandidatesOnMergedSide", 3L);
      assertThat((List<String>) data.get("warnings"))
          .anySatisfy(warning -> assertThat(warning).contains("还牵涉其它 3 条候选"));
    }

    @Test
    @DisplayName("预览不写任何东西：不写台账、不写审计、不发事件、不动实体")
    void previewIsReadOnly() {
      givenPair();
      when(mergeTargets.previewContributions(anyString())).thenReturn(List.of());

      Map<String, Object> data = service.previewMerge("ach_1", "ach_2", actor);

      assertThat(data).containsEntry("dryRun", true);
      verifyNoInteractions(ledger, outbox, audit);
      verify(mergeTargets, never()).moveContributions(anyString(), anyString());
      verify(mergeTargets, never()).moveSourceLinks(anyString(), anyString());
      verify(mergeTargets, never()).moveTopicLinks(anyString(), anyString());
      verify(mergeTargets, never()).moveIdentifiers(anyString(), anyString(), anyString());
      verify(mergeTargets, never()).markAchievementMerged(anyString(), anyString());
      verify(candidates, never())
          .resolveOpenBetween(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("参数缺失或两侧相同直接拒绝，不落成一次看起来正常的预览")
    void argumentsValidated() {
      assertThatThrownBy(() -> service.previewMerge("ach_1", "ach_1", actor))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("不能是同一条");
      assertThatThrownBy(() -> service.previewMerge("ach_1", "  ", actor))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("必须同时指定");
      verifyNoInteractions(achievements);
    }

    @Test
    @DisplayName("成果不存在返回 NOT_FOUND，不静默当成空实体预览")
    void missingSideRejected() {
      when(achievements.findById("ach_1")).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.previewMerge("ach_1", "ach_2", actor))
          .isInstanceOf(BusinessException.class)
          .satisfies(
              ex ->
                  assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
  }

  @Nested
  @DisplayName("候选两端的实时状态")
  class EndpointStates {

    @Test
    @DisplayName("按标识去重后一次取回，并暴露「是否已被并走」")
    void statesIndexedByIdAndMergedFlagged() {
      when(achievementStates.statesOf(any()))
          .thenReturn(
              List.of(state("ach_1", "EFFECTIVE", null), state("ach_2", "WITHDRAWN", "ach_9")));

      Map<String, GovernanceRepositories.AchievementStateRepository.AchievementState> states =
          service.statesOf(List.of("ach_1", "ach_2", "ach_1"), actor);

      assertThat(states).containsOnlyKeys("ach_1", "ach_2");
      assertThat(states.get("ach_1").merged()).isFalse();
      assertThat(states.get("ach_2").merged()).isTrue();
      assertThat(states.get("ach_2").mergedIntoId()).isEqualTo("ach_9");
    }

    @Test
    @DisplayName("空输入返回空表：没有候选时不必为一页空列表发一次查询")
    void emptyInputReturnsEmptyMap() {
      when(achievementStates.statesOf(any())).thenReturn(List.of());

      assertThat(service.statesOf(List.of(), actor)).isEmpty();
    }
  }

  @Nested
  @DisplayName("候选改判：问题转到最终实体，不写不实结论")
  class Rebase {

    @Test
    @DisplayName("两端重定向后是同一条：判为已合并，不新建候选")
    void bothEndpointsRoundToSameEntity() {
      when(candidates.findById("ddc_1"))
          .thenReturn(
              Optional.of(candidate("ddc_1", "ach_a", "ach_b", DedupCandidate.Status.OPEN)));
      when(achievements.resolveSurvivorId("ach_a")).thenReturn(Optional.of("ach_s"));
      when(achievements.resolveSurvivorId("ach_b")).thenReturn(Optional.of("ach_s"));
      when(candidates.resolve("ddc_1", "MERGED", "acc_reviewer", "改判后两端指向同一实体：已合并过"))
          .thenReturn(true);

      GovernanceService.RebaseOutcome outcome = service.rebaseCandidate("ddc_1", "已合并过", actor);

      assertThat(outcome.status()).isEqualTo("MERGED");
      assertThat(outcome.rebasedToId()).isNull();
      assertThat(outcome.finalLeftId()).isEqualTo("ach_s");
      assertThat(outcome.finalRightId()).isEqualTo("ach_s");
      verify(candidates, never()).insertIfAbsent(any());
      verify(candidates, never())
          .resolveRebased(anyString(), anyString(), anyString(), anyString());
      ArgumentCaptor<AuditRecorder.AuditEntry> captor =
          ArgumentCaptor.forClass(AuditRecorder.AuditEntry.class);
      verify(audit).record(captor.capture());
      assertThat(captor.getValue().action()).isEqualTo("DEDUP_CANDIDATE_REBASED");
      assertThat(captor.getValue().afterValue()).containsEntry("status", "MERGED");
    }

    @Test
    @DisplayName("两端不同且这一对从未问过：登记承接候选并保留改判溯源")
    void createsCarrierCandidateWithProvenance() {
      when(candidates.findById("ddc_1"))
          .thenReturn(
              Optional.of(candidate("ddc_1", "ach_a", "ach_c", DedupCandidate.Status.OPEN)));
      when(achievements.resolveSurvivorId("ach_a")).thenReturn(Optional.of("ach_s"));
      when(achievements.resolveSurvivorId("ach_c")).thenReturn(Optional.of("ach_c"));
      when(candidates.findBetween("ach_s", "ach_c")).thenReturn(Optional.empty());
      when(candidates.insertIfAbsent(any())).thenReturn(true);
      when(candidates.resolveRebased(eq("ddc_1"), eq("acc_reviewer"), eq("上游被合并"), anyString()))
          .thenReturn(true);

      GovernanceService.RebaseOutcome outcome = service.rebaseCandidate("ddc_1", "上游被合并", actor);

      assertThat(outcome.status()).isEqualTo("REBASED");
      assertThat(outcome.targetExisted()).isFalse();
      assertThat(outcome.rebasedToId()).startsWith("ddc");
      // 最终实体按端别报告：左端 ach_a 被并走，解析到 ach_s；右端 ach_c 没有合并链，仍是自己。
      assertThat(outcome.finalLeftId()).isEqualTo("ach_s");
      assertThat(outcome.finalRightId()).isEqualTo("ach_c");
      // 原两端与最终两端都要参与重评估：原两端少一个待治理成因，最终两端多一个。
      assertThat(outcome.affectedAchievementIds()).containsExactly("ach_a", "ach_c", "ach_s");

      ArgumentCaptor<DedupCandidate> inserted = ArgumentCaptor.forClass(DedupCandidate.class);
      verify(candidates).insertIfAbsent(inserted.capture());
      DedupCandidate carrier = inserted.getValue();
      assertThat(carrier.status()).isEqualTo(DedupCandidate.Status.OPEN);
      // 左右按字典序落库，否则 (A,B) 与 (B,A) 会绕过唯一约束变成两条候选。
      assertThat(carrier.leftAchievementId()).isEqualTo("ach_c");
      assertThat(carrier.rightAchievementId()).isEqualTo("ach_s");
      @SuppressWarnings("unchecked")
      Map<String, Object> rebase = (Map<String, Object>) carrier.evidence().get("rebase");
      assertThat(rebase).containsEntry("fromCandidateId", "ddc_1");
      assertThat(rebase).containsEntry("fromLeft", "ach_a");
      assertThat(rebase).containsEntry("note", "本条候选由改判产生：原候选的端点已被合并，问题转由最终实体作答");
    }

    @Test
    @DisplayName("这一对最终实体已有候选：复用既有候选，不重复登记同一条问题")
    void reusesExistingCandidate() {
      when(candidates.findById("ddc_1"))
          .thenReturn(
              Optional.of(candidate("ddc_1", "ach_a", "ach_c", DedupCandidate.Status.OPEN)));
      when(achievements.resolveSurvivorId("ach_a")).thenReturn(Optional.of("ach_s"));
      when(achievements.resolveSurvivorId("ach_c")).thenReturn(Optional.of("ach_c"));
      when(candidates.findBetween("ach_s", "ach_c"))
          .thenReturn(
              Optional.of(candidate("ddc_9", "ach_c", "ach_s", DedupCandidate.Status.OPEN)));
      when(candidates.resolveRebased("ddc_1", "acc_reviewer", "上游被合并", "ddc_9")).thenReturn(true);

      GovernanceService.RebaseOutcome outcome = service.rebaseCandidate("ddc_1", "上游被合并", actor);

      assertThat(outcome.rebasedToId()).isEqualTo("ddc_9");
      assertThat(outcome.targetExisted()).isTrue();
      verify(candidates, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("并发下被抢先登记：读回既有候选，不能让原候选指向不存在的承接方")
    void fallsBackToExistingWhenInsertLosesRace() {
      when(candidates.findById("ddc_1"))
          .thenReturn(
              Optional.of(candidate("ddc_1", "ach_a", "ach_c", DedupCandidate.Status.OPEN)));
      when(achievements.resolveSurvivorId("ach_a")).thenReturn(Optional.of("ach_s"));
      when(achievements.resolveSurvivorId("ach_c")).thenReturn(Optional.of("ach_c"));
      when(candidates.findBetween("ach_s", "ach_c"))
          .thenReturn(Optional.empty())
          .thenReturn(
              Optional.of(candidate("ddc_7", "ach_c", "ach_s", DedupCandidate.Status.OPEN)));
      when(candidates.insertIfAbsent(any())).thenReturn(false);
      when(candidates.resolveRebased("ddc_1", "acc_reviewer", "并发", "ddc_7")).thenReturn(true);

      GovernanceService.RebaseOutcome outcome = service.rebaseCandidate("ddc_1", "并发", actor);

      assertThat(outcome.rebasedToId()).isEqualTo("ddc_7");
      assertThat(outcome.targetExisted()).isTrue();
    }

    @Test
    @DisplayName("合并链超限：明确报错，不落任何一行")
    void abortsWhenMergeChainTooLong() {
      when(candidates.findById("ddc_1"))
          .thenReturn(
              Optional.of(candidate("ddc_1", "ach_a", "ach_c", DedupCandidate.Status.OPEN)));
      when(achievements.resolveSurvivorId(anyString())).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.rebaseCandidate("ddc_1", "改判", actor))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("合并链超过解析上限");

      verify(candidates, never()).insertIfAbsent(any());
      verify(candidates, never()).resolve(anyString(), anyString(), anyString(), anyString());
      verify(candidates, never())
          .resolveRebased(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("已裁决的候选不能再改判：重复处置返回版本冲突")
    void rejectsAlreadyResolvedCandidate() {
      when(candidates.findById("ddc_1"))
          .thenReturn(
              Optional.of(candidate("ddc_1", "ach_a", "ach_c", DedupCandidate.Status.REJECTED)));

      assertThatThrownBy(() -> service.rebaseCandidate("ddc_1", "改判", actor))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("已裁决");

      verify(achievements, never()).resolveSurvivorId(anyString());
    }

    @Test
    @DisplayName("理由必填：改判改变「这个问题由谁作答」，必须可追溯")
    void reasonRequired() {
      when(candidates.findById("ddc_1"))
          .thenReturn(
              Optional.of(candidate("ddc_1", "ach_a", "ach_c", DedupCandidate.Status.OPEN)));

      assertThatThrownBy(() -> service.rebaseCandidate("ddc_1", "   ", actor))
          .isInstanceOf(BusinessException.class);

      verify(achievements, never()).resolveSurvivorId(anyString());
    }
  }

  // ---------------------------------------------------------------------------
  // 授权：登录不是授权
  // ---------------------------------------------------------------------------

  /**
   * 治理接口一度只要求「已登录」，于是任何注册用户都能读到候选工作台（两端标题、相似度）并调用
   * 合并接口。前端的隐藏入口不构成授权，这里把能力校验固定在应用层，并逐条反证「读也不行」。
   */
  @Nested
  @DisplayName("治理能力校验")
  class Authorization {

    private ActorContext plainUser() {
      return new ActorContext(
          new AccountId("acc_user"), "user", "普通用户", Set.of(RoleNames.USER), 1L);
    }

    @Test
    @DisplayName("普通用户读不了工作台候选：候选里有两端标题与相似度，属治理内部信息")
    void plainUserCannotReadWorkbench() {
      assertThatThrownBy(
              () ->
                  service.listCandidatesForWorkbench(
                      GovernanceService.CandidateFilter.of(null, null),
                      new PageRequest(1, 20),
                      plainUser()))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("能力");
      verifyNoInteractions(candidates);
    }

    @Test
    @DisplayName("未登录不得查看合并预览：预览会读出两端的关系与来源归属")
    void anonymousCannotPreviewMerge() {
      assertThatThrownBy(() -> service.previewMerge("ach_1", "ach_2", null))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("请先登录");
      verifyNoInteractions(achievements);
    }

    @Test
    @DisplayName("普通用户调用学者合并被拒，且不写台账")
    void plainUserCannotMergeScholars() {
      assertThatThrownBy(
              () -> service.mergeScholars("sch_a", "sch_b", "越权尝试", plainUser()))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("能力");
      verifyNoInteractions(scholars);
      verifyNoInteractions(ledger);
    }

    @Test
    @DisplayName("普通用户读不了台账：台账里有合并前后的完整快照")
    void plainUserCannotReadLedger() {
      assertThatThrownBy(() -> service.ledgerOf("ach_1", 20, plainUser()))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("能力");
      verifyNoInteractions(ledger);
    }
  }

  private static GovernanceRepositories.AchievementStateRepository.AchievementState state(
      String id, String effectiveStatus, String mergedIntoId) {
    return new GovernanceRepositories.AchievementStateRepository.AchievementState(
        id, "PAPER", "标题 " + id, "REAL", "PUBLIC", effectiveStatus, mergedIntoId, 2023, "YEAR");
  }
}
