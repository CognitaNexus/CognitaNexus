package com.cognitanexus.core.taxonomy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.application.CatalogRepositories.TopicRepository;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.taxonomy.application.TopicInferenceRepository.PendingAchievement;
import com.cognitanexus.core.taxonomy.domain.AcademicTaxonomy;
import com.cognitanexus.core.taxonomy.domain.TopicClassifier;
import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 领域推断服务。
 *
 * <p>重点不在"能判出领域"，而在四条边界：能力不足时不得动数据、写操作必须有理由、判不出的必须
 * 如实记为待分类（而不是留空让队列卡住）、以及推断产物必须带证据与版本。
 */
@ExtendWith(MockitoExtension.class)
class TopicInferenceServiceTest {

  private static final String RULE_VERSION = "t/token-prefix";

  @Mock TopicInferenceRepository inferences;
  @Mock TopicRepository topics;
  @Mock AuditRecorder audit;

  private TopicInferenceService service;

  private static ActorContext admin() {
    return new ActorContext(
        new AccountId("acc_admin"), "admin", "平台管理员", Set.of(RoleNames.PLATFORM_ADMIN), 1L);
  }

  private static ActorContext plainUser() {
    return new ActorContext(new AccountId("acc_user"), "user", "普通用户", Set.of(RoleNames.USER), 1L);
  }

  @BeforeEach
  void setUp() throws IOException {
    AcademicTaxonomy taxonomy =
        AcademicTaxonomy.fromJson(
            new ObjectMapper()
                .readTree(
                    """
                    {"version":"t","matchMode":"token-prefix","minScore":4,"secondaryRatio":0.5,
                     "titleWeight":1,"abstractWeight":0.5,"fields":[
                       {"canonicalName":"人工智能",
                        "terms":[{"term":"graph neural","weight":5},{"term":"neural network","weight":5}]},
                       {"canonicalName":"生物学",
                        "terms":[{"term":"protein","weight":5}]},
                       {"canonicalName":"计算机科学与技术",
                        "terms":[{"term":"algorithm","weight":2}]}]}
                    """));
    service = new TopicInferenceService(inferences, topics, new TopicClassifier(taxonomy), audit);
  }

  private void givenPending(PendingAchievement... items) {
    when(inferences.listPending(anyInt(), eq(RULE_VERSION))).thenReturn(List.of(items));
  }

  @Test
  @DisplayName("无治理能力时拒绝，且不触碰仓储")
  void requiresGovernanceCapability() {
    assertThatThrownBy(() -> service.infer(plainUser(), 10, false, "试跑"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).errorCode())
                    .isEqualTo(ErrorCode.ACCESS_DENIED));
    verifyNoInteractions(inferences);
  }

  @Test
  @DisplayName("未登录直接拒绝")
  void rejectsAnonymous() {
    assertThatThrownBy(() -> service.infer(null, 10, false, "试跑"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).errorCode())
                    .isEqualTo(ErrorCode.AUTH_REQUIRED));
  }

  @Test
  @DisplayName("写操作必须说明理由；dryRun 不必")
  void reasonRequiredForWritesOnly() {
    assertThatThrownBy(() -> service.infer(admin(), 10, false, "  "))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("理由");
    verifyNoInteractions(inferences);

    givenPending();
    service.infer(admin(), 10, true, null);
    verify(inferences).listPending(anyInt(), anyString());
  }

  @Test
  @DisplayName("批大小越界时拒绝，超上限时收敛到上限")
  void clampsBatchSize() {
    assertThatThrownBy(() -> service.infer(admin(), 0, false, "理由"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("批大小");

    givenPending();
    service.infer(admin(), 5000, false, "理由");
    // 整批一个事务，批太大既撑大事务也拉长锁窗口
    verify(inferences).listPending(500, RULE_VERSION);
  }

  @Test
  @DisplayName("判出领域：落归属并写台账，证据与规则版本一并落库")
  void assignsWithEvidenceAndRuleVersion() {
    givenPending(
        new PendingAchievement(
            "ach_1", "Graph Neural Network for Protein Folding", "Nature", null));
    when(inferences.countInferredRows(RULE_VERSION)).thenReturn(0L, 2L);
    when(inferences.countPending(RULE_VERSION)).thenReturn(5L, 4L);
    when(inferences.countAchievementsWithoutTopic()).thenReturn(4L);
    when(topics.findOrCreate("人工智能")).thenReturn("top_ai");
    when(topics.findOrCreate("生物学")).thenReturn("top_bio");
    when(inferences.linkInferred(anyString(), anyString(), anyString(), any(), anyString()))
        .thenReturn(1);

    TopicInferenceService.BatchReport report = service.infer(admin(), 10, false, "接通领域数据");

    assertThat(report.examined()).isEqualTo(1);
    assertThat(report.assigned()).isEqualTo(1);
    assertThat(report.unresolved()).isZero();
    assertThat(report.createdRows()).isEqualTo(2);
    assertThat(report.idempotentRows()).isZero();
    assertThat(report.ruleVersion()).isEqualTo(RULE_VERSION);
    assertThat(report.pendingRemaining()).isEqualTo(4L);

    ArgumentCaptor<String> evidence = ArgumentCaptor.forClass(String.class);
    verify(inferences)
        .linkInferred(eq("ach_1"), eq("top_ai"), eq(RULE_VERSION), any(), evidence.capture());
    assertThat(evidence.getValue()).contains("title:graph neural=5.0");

    ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Integer> count = ArgumentCaptor.forClass(Integer.class);
    verify(inferences)
        .upsertInference(
            eq("ach_1"), eq(RULE_VERSION), outcome.capture(), count.capture(), any(), anyString());
    assertThat(outcome.getValue()).isEqualTo("ASSIGNED");
    assertThat(count.getValue()).isEqualTo(2);
  }

  @Test
  @DisplayName("判不出：如实记为待分类并写明原因，不落任何归属")
  void unclassifiableIsRecordedRatherThanLeftBlank() {
    givenPending(new PendingAchievement("ach_2", "A Study of Nothing in Particular", null, null));
    when(inferences.countInferredRows(RULE_VERSION)).thenReturn(0L, 0L);
    when(inferences.countPending(RULE_VERSION)).thenReturn(1L, 0L);
    when(inferences.countAchievementsWithoutTopic()).thenReturn(1L);

    TopicInferenceService.BatchReport report = service.infer(admin(), 10, false, "接通领域数据");

    assertThat(report.unresolved()).isEqualTo(1);
    assertThat(report.assigned()).isZero();
    verify(inferences, never())
        .linkInferred(anyString(), anyString(), anyString(), any(), anyString());
    ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
    verify(inferences)
        .upsertInference(
            eq("ach_2"), eq(RULE_VERSION), eq("UNRESOLVED"), eq(0), isNull(), reason.capture());
    // 只说「未分类」，治理人员无从判断是文本不足、阈值过高还是词表缺词
    assertThat(reason.getValue()).contains("没有任何词表判别词命中");
    assertThat(report.samples())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.unresolvedReason()).isNotNull();
              assertThat(item.assignments()).isEmpty();
            });
  }

  @Test
  @DisplayName("弱命中但低于阈值：原因里带上最高得分与阈值，并保持待分类")
  void unresolvedReasonCarriesScoreAndThreshold() {
    givenPending(new PendingAchievement("ach_3", "An Algorithm Survey", null, null));
    when(inferences.countInferredRows(RULE_VERSION)).thenReturn(0L, 0L);
    when(inferences.countPending(RULE_VERSION)).thenReturn(1L, 0L);
    when(inferences.countAchievementsWithoutTopic()).thenReturn(1L);

    service.infer(admin(), 10, false, "接通领域数据");

    // algorithm 权重 2 < 阈值 4：不强行归类，但要把判断依据写清楚
    verify(inferences, never())
        .linkInferred(anyString(), anyString(), anyString(), any(), anyString());
    ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
    verify(inferences)
        .upsertInference(
            eq("ach_3"), eq(RULE_VERSION), eq("UNRESOLVED"), eq(0), isNull(), reason.capture());
    assertThat(reason.getValue()).contains("最高得分 2.0").contains("低于阈值 4.0").contains("计算机科学与技术");
  }

  @Test
  @DisplayName("强命中走归属分支而不是待分类")
  void strongSingleTermClassifies() {
    givenPending(new PendingAchievement("ach_3b", "Protein Structure Study", null, null));
    when(inferences.countInferredRows(RULE_VERSION)).thenReturn(0L, 1L);
    when(inferences.countPending(RULE_VERSION)).thenReturn(1L, 0L);
    when(inferences.countAchievementsWithoutTopic()).thenReturn(0L);
    when(topics.findOrCreate("生物学")).thenReturn("top_bio");
    when(inferences.linkInferred(anyString(), anyString(), anyString(), any(), anyString()))
        .thenReturn(1);

    service.infer(admin(), 10, false, "接通领域数据");
    verify(inferences)
        .linkInferred(eq("ach_3b"), eq("top_bio"), eq(RULE_VERSION), any(), anyString());
    verify(inferences, never())
        .upsertInference(anyString(), anyString(), eq("UNRESOLVED"), anyInt(), any(), anyString());
  }

  @Test
  @DisplayName("dryRun 只算不写")
  void dryRunWritesNothing() {
    givenPending(new PendingAchievement("ach_4", "Graph Neural Network", null, null));
    when(inferences.countInferredRows(RULE_VERSION)).thenReturn(7L);
    when(inferences.countPending(RULE_VERSION)).thenReturn(3L);
    when(inferences.countAchievementsWithoutTopic()).thenReturn(3L);

    TopicInferenceService.BatchReport report = service.infer(admin(), 10, true, null);

    assertThat(report.dryRun()).isTrue();
    assertThat(report.assigned()).isEqualTo(1);
    // dryRun 也把样本算出来，否则"先看看会判成什么"就无从看起
    assertThat(report.samples())
        .singleElement()
        .satisfies(
            item ->
                assertThat(item.assignments())
                    .singleElement()
                    .satisfies(
                        assignment -> {
                          assertThat(assignment.fieldName()).isEqualTo("人工智能");
                          // 未落库，因此没有 topicId
                          assertThat(assignment.topicId()).isNull();
                        }));
    verify(inferences, never())
        .linkInferred(anyString(), anyString(), anyString(), any(), anyString());
    verify(inferences, never())
        .upsertInference(anyString(), anyString(), anyString(), anyInt(), any(), anyString());
    verify(audit, never()).record(any());
  }

  @Test
  @DisplayName("换规则版本重算：旧版本归属被清掉并计入统计")
  void countsSupersededRows() {
    givenPending(new PendingAchievement("ach_5", "Graph Neural Network", null, null));
    when(inferences.countInferredRows(RULE_VERSION)).thenReturn(1L, 1L);
    when(inferences.countPending(RULE_VERSION)).thenReturn(1L, 0L);
    when(inferences.countAchievementsWithoutTopic()).thenReturn(0L);
    when(inferences.deleteInferredOfOtherVersions("ach_5", RULE_VERSION)).thenReturn(3);
    when(topics.findOrCreate("人工智能")).thenReturn("top_ai");
    when(inferences.linkInferred(anyString(), anyString(), anyString(), any(), anyString()))
        .thenReturn(1);

    TopicInferenceService.BatchReport report = service.infer(admin(), 10, false, "换词表版本重算");
    assertThat(report.supersededRows()).isEqualTo(3);
  }

  @Test
  @DisplayName("归属已存在时计为幂等而不是新建")
  void countsIdempotentRows() {
    givenPending(new PendingAchievement("ach_6", "Graph Neural Network", null, null));
    when(inferences.countInferredRows(RULE_VERSION)).thenReturn(1L, 1L);
    when(inferences.countPending(RULE_VERSION)).thenReturn(1L, 0L);
    when(inferences.countAchievementsWithoutTopic()).thenReturn(0L);
    when(topics.findOrCreate("人工智能")).thenReturn("top_ai");
    when(inferences.linkInferred(anyString(), anyString(), anyString(), any(), anyString()))
        .thenReturn(0);

    TopicInferenceService.BatchReport report = service.infer(admin(), 10, false, "重复推进");
    assertThat(report.idempotentRows()).isEqualTo(1);
    assertThat(report.createdRows()).isZero();
  }

  @Test
  @DisplayName("审计带上前后口径与理由")
  void auditsWithCounts() {
    givenPending();
    when(inferences.countInferredRows(RULE_VERSION)).thenReturn(10L);
    when(inferences.countPending(RULE_VERSION)).thenReturn(0L);
    when(inferences.countAchievementsWithoutTopic()).thenReturn(0L);

    service.infer(admin(), 10, false, "接通领域数据");

    ArgumentCaptor<AuditRecorder.AuditEntry> entry =
        ArgumentCaptor.forClass(AuditRecorder.AuditEntry.class);
    verify(audit).record(entry.capture());
    assertThat(entry.getValue().action()).isEqualTo("TOPIC_INFERENCE_APPLIED");
    assertThat(entry.getValue().targetType()).isEqualTo("TAXONOMY");
    assertThat(entry.getValue().targetId()).isEqualTo(RULE_VERSION);
    assertThat(entry.getValue().reason()).isEqualTo("接通领域数据");
    assertThat(entry.getValue().beforeValue()).containsEntry("inferredRows", 10L);
  }

  @Test
  @DisplayName("概况同时给出覆盖率、归属来源盘点与推断结果盘点")
  void summaryExposesCoverage() {
    when(inferences.countInferredRows(RULE_VERSION)).thenReturn(1234L);
    when(inferences.countPending(RULE_VERSION)).thenReturn(56L);
    when(inferences.countAchievementsWithoutTopic()).thenReturn(300L);
    when(inferences.countByKind())
        .thenReturn(
            List.of(
                new TopicInferenceRepository.KindCount("SOURCE", 5L, 4L),
                new TopicInferenceRepository.KindCount("INFERRED", 1234L, 1000L)));
    when(inferences.countByOutcome(RULE_VERSION))
        .thenReturn(
            List.of(
                new TopicInferenceRepository.OutcomeCount("ASSIGNED", 980L),
                new TopicInferenceRepository.OutcomeCount("UNRESOLVED", 20L)));

    Map<String, Object> view = service.summary(admin());

    assertThat(view).containsEntry("ruleVersion", RULE_VERSION);
    assertThat(view).containsEntry("inferredRows", 1234L);
    assertThat(view).containsEntry("pending", 56L);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> byKind = (List<Map<String, Object>>) view.get("byAssignmentKind");
    assertThat(byKind).hasSize(2);
    assertThat(byKind.get(0)).containsEntry("assignmentKind", "SOURCE").containsEntry("rows", 5L);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> byOutcome = (List<Map<String, Object>>) view.get("byOutcome");
    assertThat(byOutcome.get(1))
        .containsEntry("outcome", "UNRESOLVED")
        .containsEntry("achievements", 20L);
  }

  @Test
  @DisplayName("待分类清单带上原因与时间")
  void unresolvedListCarriesReason() {
    Instant when = Instant.parse("2026-07-01T00:00:00Z");
    when(inferences.listUnresolved(RULE_VERSION, 10))
        .thenReturn(
            List.of(
                new TopicInferenceRepository.InferenceRecord(
                    "ach_9", "UNRESOLVED", 0, null, "最高得分 3.0 低于阈值 4.0", when)));

    List<Map<String, Object>> items = service.unresolved(admin(), 10);

    assertThat(items)
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item).containsEntry("achievementId", "ach_9");
              assertThat(item).containsEntry("reason", "最高得分 3.0 低于阈值 4.0");
              assertThat(item).containsEntry("inferredAt", "2026-07-01T00:00:00Z");
            });
  }

  @Test
  @DisplayName("置信度写入时收敛为三位小数")
  void confidenceIsRoundedBeforePersisting() {
    givenPending(new PendingAchievement("ach_7", "Graph Neural Network", null, null));
    when(inferences.countInferredRows(RULE_VERSION)).thenReturn(0L, 1L);
    when(inferences.countPending(RULE_VERSION)).thenReturn(1L, 0L);
    when(inferences.countAchievementsWithoutTopic()).thenReturn(0L);
    when(topics.findOrCreate("人工智能")).thenReturn("top_ai");
    when(inferences.linkInferred(anyString(), anyString(), anyString(), any(), anyString()))
        .thenReturn(1);

    service.infer(admin(), 10, false, "接通领域数据");

    ArgumentCaptor<BigDecimal> confidence = ArgumentCaptor.forClass(BigDecimal.class);
    verify(inferences)
        .linkInferred(
            eq("ach_7"), eq("top_ai"), eq(RULE_VERSION), confidence.capture(), anyString());
    assertThat(confidence.getValue().scale()).isLessThanOrEqualTo(3);
  }
}
