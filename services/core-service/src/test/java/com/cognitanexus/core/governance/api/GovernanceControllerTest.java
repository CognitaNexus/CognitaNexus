package com.cognitanexus.core.governance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.governance.application.DataQualityScanService;
import com.cognitanexus.core.governance.application.GovernanceRepositories;
import com.cognitanexus.core.governance.application.GovernanceRepositories.ScoredCandidate;
import com.cognitanexus.core.governance.application.GovernanceResolutionWorkflow;
import com.cognitanexus.core.governance.application.GovernanceService;
import com.cognitanexus.core.governance.domain.DedupCandidate;
import com.cognitanexus.core.governance.domain.GovernanceAnomaly;
import com.cognitanexus.core.governance.domain.MergeLedgerEntry;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.util.PageRequest;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/**
 * 治理接口的响应结构与元数据。
 *
 * <p>这一层不判定业务，但它决定「界面能不能拿到它需要的东西」。前端不另立阈值、不另立排序键，
 * 全靠 meta 里带回的分档计数与白名单；这些字段一旦缺项，界面只会安静地少一块，不会报错。
 * 因此把 meta 当契约来断言，而不是只看状态码 200。
 */
@ExtendWith(MockitoExtension.class)
class GovernanceControllerTest {

  @Mock GovernanceService governance;
  @Mock GovernanceResolutionWorkflow workflow;
  @Mock DataQualityScanService scans;

  private GovernanceController controller;

  private static final Instant WHEN = Instant.parse("2026-06-01T00:00:00Z");

  @BeforeEach
  void setUp() {
    controller = new GovernanceController(governance, workflow, scans);
    ActorContextHolder.set(
        new ActorContext(
            new AccountId("acc_reviewer"), "reviewer", "审核员", Set.of(RoleNames.REVIEWER), 1L));
  }

  @AfterEach
  void tearDown() {
    ActorContextHolder.clear();
  }

  private static DedupCandidate candidate(String id) {
    return new DedupCandidate(
        id,
        "PAPER",
        "ach_left",
        "ach_right",
        Map.of("similarity", 0.91d, "leftTitle", "A", "rightTitle", "B"),
        DedupCandidate.Status.OPEN,
        WHEN,
        null,
        null,
        null,
        null);
  }

  private static <T> T dataOf(ResponseEntity<ApiResponse<T>> response) {
    assertThat(response.getBody()).isNotNull();
    return response.getBody().data();
  }

  @Test
  @DisplayName("候选列表带回总数：翻页控件必须知道还有多少条，否则只能停在第一页")
  void candidatesCarriesTotal() {
    when(governance.listCandidates(any(PageRequest.class), any()))
        .thenReturn(List.of(candidate("ddc_1")));
    when(governance.countOpenCandidates(any())).thenReturn(42L);

    ResponseEntity<ApiResponse<List<Map<String, Object>>>> response = controller.candidates(1, 20);

    assertThat(dataOf(response)).hasSize(1);
    assertThat(response.getBody().meta()).containsEntry("total", 42L);
    assertThat(response.getBody().meta()).containsEntry("page", 1);
  }

  @Test
  @DisplayName("工作台 meta 带齐分档计数、阈值与排序白名单：界面据此组织待办，不自己猜一套")
  void workbenchMetaCarriesEverything() {
    when(governance.listCandidatesForWorkbench(any(), any(PageRequest.class), any()))
        .thenReturn(List.of(new ScoredCandidate(candidate("ddc_1"), 0.93d)));
    when(governance.countCandidatesForWorkbench(any(), any())).thenReturn(7L);
    when(governance.workbenchSummary(any()))
        .thenReturn(new GovernanceService.WorkbenchSummary(3L, 2L, 2L, 7L, 0.9d, 0.7d));

    ResponseEntity<ApiResponse<List<Map<String, Object>>>> response =
        controller.workbench(null, null, 1, 50);

    Map<String, Object> meta = response.getBody().meta();
    assertThat(meta)
        .containsEntry("total", 7L)
        .containsEntry("openTotal", 7L)
        .containsEntry("high", 3L)
        .containsEntry("mid", 2L)
        .containsEntry("low", 2L)
        .containsEntry("highLow", 0.9d)
        .containsEntry("midLow", 0.7d);
    assertThat(meta.get("allowedSorts"))
        .isEqualTo(GovernanceService.CandidateFilter.allowedSorts());
    assertThat(String.valueOf(meta.get("bucketNote"))).contains("不构成自动裁决结论");
    // 相似度必须逐个候选带回：列表要按它排序、要按它分档，界面读不到就只能自己再算一遍
    assertThat(dataOf(response).get(0)).containsEntry("similarity", 0.93d);
  }

  @Test
  @DisplayName("非法排序键明确报错，不静默回落：页面上写着按相似度排序却按时间排，没人能发现")
  void workbenchRejectsUnknownSort() {
    assertThatThrownBy(() -> controller.workbench(null, "title_desc", 1, 50))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("相似度下限越界被拒：允许 2.0 之类的取值等于让筛选条件永远筛不出任何东西")
  void workbenchRejectsOutOfRangeSimilarity() {
    assertThatThrownBy(() -> controller.workbench(1.5d, null, 1, 50))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("工作台候选卡片带两端实时状态与处置可行性：被并走的一侧必须当场可见，不能让人去撞错误码")
  void workbenchCandidateCarriesLiveEndpointState() {
    when(governance.listCandidatesForWorkbench(any(), any(PageRequest.class), any()))
        .thenReturn(
            List.of(
                new ScoredCandidate(candidate("ddc_1"), 1.0d),
                new ScoredCandidate(candidate("ddc_2"), 0.8d)));
    when(governance.countCandidatesForWorkbench(any(), any())).thenReturn(2L);
    when(governance.workbenchSummary(any()))
        .thenReturn(new GovernanceService.WorkbenchSummary(2L, 0L, 0L, 2L, 0.9d, 0.7d));
    // 两条候选都用 ach_left / ach_right：第一条两端都还在；第二条的右端已被并走。
    // 状态只在渲染时实时读，因此同一个标识在不同页、不同时刻可以给出不同结论。
    when(governance.statesOf(any(), any()))
        .thenReturn(
            Map.of(
                "ach_left", state("ach_left", "EFFECTIVE", null),
                "ach_right", state("ach_right", "PENDING_GOVERNANCE", null)));

    ResponseEntity<ApiResponse<List<Map<String, Object>>>> response =
        controller.workbench(null, null, 1, 50);

    Map<String, Object> first = dataOf(response).get(0);
    assertThat((Map<String, Object>) first.get("left"))
        .containsEntry("id", "ach_left")
        .containsEntry("known", true)
        .containsEntry("effectiveStatus", "EFFECTIVE")
        .containsEntry("merged", false)
        .containsEntry("title", "标题 ach_left");
    assertThat((Map<String, Object>) first.get("right")).containsEntry("merged", false);
    assertThat(first).containsEntry("recommendedAction", "MERGE_OR_REJECT");
    // 未被并走时不能给出重定向目标：编一个出来会让前端显示「已并入 xxx」。
    assertThat((Map<String, Object>) first.get("left")).doesNotContainKey("mergedIntoId");

    when(governance.statesOf(any(), any()))
        .thenReturn(
            Map.of(
                "ach_left", state("ach_left", "EFFECTIVE", null),
                "ach_right", state("ach_right", "WITHDRAWN", "ach_left")));
    Map<String, Object> second = dataOf(controller.workbench(null, null, 1, 50)).get(0);
    assertThat((Map<String, Object>) second.get("right"))
        .containsEntry("merged", true)
        .containsEntry("mergedIntoId", "ach_left")
        .containsEntry("effectiveStatus", "WITHDRAWN");
    // 已并走的一端不能作为幸存实体（重定向不覆盖首次结论），因此这条候选只能驳回。
    assertThat(second).containsEntry("recommendedAction", "REJECT_ONLY");
  }

  @Test
  @DisplayName("状态查不到时如实说 known=false，不编造状态")
  void unknownEndpointStateReportedHonestly() {
    when(governance.listCandidatesForWorkbench(any(), any(PageRequest.class), any()))
        .thenReturn(List.of(new ScoredCandidate(candidate("ddc_1"), 0.8d)));
    when(governance.countCandidatesForWorkbench(any(), any())).thenReturn(1L);
    when(governance.workbenchSummary(any()))
        .thenReturn(new GovernanceService.WorkbenchSummary(1L, 0L, 0L, 1L, 0.9d, 0.7d));
    when(governance.statesOf(any(), any())).thenReturn(Map.of());

    Map<String, Object> row = dataOf(controller.workbench(null, null, 1, 50)).get(0);

    assertThat((Map<String, Object>) row.get("left"))
        .containsEntry("known", false)
        .containsEntry("id", "ach_left")
        .doesNotContainKey("effectiveStatus");
    assertThat(row).containsEntry("recommendedAction", "UNKNOWN");
  }

  private static GovernanceRepositories.AchievementStateRepository.AchievementState state(
      String id, String effectiveStatus, String mergedIntoId) {
    return new GovernanceRepositories.AchievementStateRepository.AchievementState(
        id, "PAPER", "标题 " + id, "REAL", "PUBLIC", effectiveStatus, mergedIntoId, 2023, "YEAR");
  }

  @Test
  @DisplayName("单条裁决回报重评估结果：裁决与发布是两件事，结果必须分别告诉调用方")
  void resolveCandidateReportsRelease() {
    when(workflow.resolve(anyString(), anyString(), any(), anyString(), any()))
        .thenReturn(new GovernanceResolutionWorkflow.Outcome("ddc_1", 2, List.of("ach_left")));

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller.resolveCandidate(
            "ddc_1", new GovernanceController.ResolveCandidateRequest("REJECT", null, "理由"));

    assertThat(dataOf(response))
        .containsEntry("resolved", true)
        .containsEntry("affected", 2)
        .containsEntry("releasedCount", 1);
    assertThat(dataOf(response).get("released")).isEqualTo(List.of("ach_left"));
  }

  @Test
  @DisplayName("改判回报承接候选与最终实体：调用方要知道「问题去哪儿了」，而不只是「这条不在了」")
  void rebaseCandidateReportsCarrier() {
    when(workflow.rebase(eq("ddc_1"), eq("上游被合并"), any()))
        .thenReturn(
            new GovernanceResolutionWorkflow.RebaseOutcome(
                "ddc_1", "REBASED", "ddc_2", false, "ach_c", "ach_s", List.of("ach_c")));

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller.rebaseCandidate(
            "ddc_1", new GovernanceController.RebaseCandidateRequest("上游被合并"));

    assertThat(dataOf(response))
        .containsEntry("rebased", true)
        .containsEntry("status", "REBASED")
        .containsEntry("rebasedToId", "ddc_2")
        .containsEntry("targetExisted", false)
        .containsEntry("finalLeftId", "ach_c")
        .containsEntry("finalRightId", "ach_s")
        .containsEntry("releasedCount", 1);
  }

  @Test
  @DisplayName("改判后两端本就同一条：不写 rebasedToId，承接与否由 status 判断而不是靠字段有无")
  void rebaseWithoutCarrierOmitsPointer() {
    when(workflow.rebase(eq("ddc_1"), anyString(), any()))
        .thenReturn(
            new GovernanceResolutionWorkflow.RebaseOutcome(
                "ddc_1", "MERGED", null, false, "ach_s", "ach_s", List.of()));

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller.rebaseCandidate("ddc_1", new GovernanceController.RebaseCandidateRequest("改判"));

    assertThat(dataOf(response)).containsEntry("status", "MERGED");
    assertThat(dataOf(response)).doesNotContainKey("rebasedToId");
  }

  @Test
  @DisplayName("批量驳回回报跳过项与释放数：批量操作最需要知道的是「哪些没做」")
  void batchRejectReportsSkippedAndReleased() {
    when(workflow.batchReject(any(), anyString(), any()))
        .thenReturn(
            new GovernanceResolutionWorkflow.BatchOutcome(
                3, 2, List.of("ddc_x：候选不存在"), List.of("ach_left")));

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller.batchReject(
            new GovernanceController.BatchRejectRequest(List.of("ddc_1", "ddc_2", "ddc_x"), "理由"));

    assertThat(dataOf(response))
        .containsEntry("requested", 3)
        .containsEntry("resolved", 2)
        .containsEntry("releasedCount", 1);
    assertThat(dataOf(response).get("skipped")).isEqualTo(List.of("ddc_x：候选不存在"));
  }

  @Test
  @DisplayName("空候选列表在入口被拒，不进入编排层：否则会变成一次「什么也没做但报成功」的操作")
  void batchRejectRejectsEmptyInput() {
    assertThatThrownBy(
            () ->
                controller.batchReject(
                    new GovernanceController.BatchRejectRequest(List.of(), "理由")))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> controller.batchReject(null)).isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("异常列表带回 kind 与 detail：没有详情的人工无法判断该不该处理")
  void anomaliesCarriesDetail() {
    GovernanceAnomaly anomaly =
        new GovernanceAnomaly(
            "anm_1",
            GovernanceAnomaly.KIND_IDENTIFIER_CONFLICT,
            "src_1",
            "ach_1",
            Map.of("occupiedBy", "ach_2"),
            "OPEN",
            WHEN,
            null);
    when(governance.listAnomalies(any(PageRequest.class), any())).thenReturn(List.of(anomaly));
    when(governance.countOpenAnomalies(any())).thenReturn(1L);

    ResponseEntity<ApiResponse<List<Map<String, Object>>>> response = controller.anomalies(1, 20);

    assertThat(dataOf(response).get(0))
        .containsEntry("kind", GovernanceAnomaly.KIND_IDENTIFIER_CONFLICT)
        .containsEntry("entityId", "ach_1")
        .containsEntry("status", "OPEN");
    assertThat(response.getBody().meta()).containsEntry("total", 1L);
  }

  @Test
  @DisplayName("标记异常已处理：动作完成后回执成功")
  void resolveAnomalyAcknowledges() {
    ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.resolveAnomaly("anm_1");

    assertThat(dataOf(response)).containsEntry("resolved", true);
    verify(governance).resolveAnomaly(eq("anm_1"), any());
  }

  @Test
  @DisplayName("台账按实体查询，limit 被夹在 1..200：越界取值不放大成一次全表读取")
  void ledgerClampsLimit() {
    when(governance.ledgerOf(anyString(), anyInt(), any())).thenReturn(List.of());

    controller.ledger("ach_1", 5000);
    verify(governance).ledgerOf(eq("ach_1"), eq(200), any());

    controller.ledger("ach_1", 0);
    verify(governance).ledgerOf(eq("ach_1"), eq(1), any());
  }

  @Test
  @DisplayName("台账条目带回快照与理由：合并的后果要靠快照理解，只有一串 MERGE 说明不了问题")
  void ledgerViewCarriesSnapshot() {
    MergeLedgerEntry entry =
        new MergeLedgerEntry(
            "mgl_1",
            MergeLedgerEntry.Operation.MERGE,
            "ach_survivor",
            "ach_merged",
            MergeLedgerEntry.EntityType.ACHIEVEMENT.name(),
            Map.of("sourcesMoved", 2),
            "同一篇论文的两种收录",
            "acc_reviewer",
            WHEN);
    when(governance.ledgerOf(anyString(), anyInt(), any())).thenReturn(List.of(entry));

    ResponseEntity<ApiResponse<List<Map<String, Object>>>> response =
        controller.ledger("ach_survivor", 50);

    assertThat(dataOf(response).get(0))
        .containsEntry("operation", "MERGE")
        .containsEntry("survivorId", "ach_survivor")
        .containsEntry("mergedId", "ach_merged")
        .containsEntry("reason", "同一篇论文的两种收录");
  }

  @Test
  @DisplayName("成果与学者合并各走各的入口：同名学者合并不能借道成果合并")
  void mergesAreSeparate() {
    ResponseEntity<ApiResponse<Map<String, Object>>> achievementMerge =
        controller.mergeAchievements(new GovernanceController.MergeRequest("ach_1", "ach_2", "理由"));
    assertThat(dataOf(achievementMerge)).containsEntry("merged", true);
    verify(governance).mergeAchievements(eq("ach_1"), eq("ach_2"), eq("理由"), any());

    ResponseEntity<ApiResponse<Map<String, Object>>> scholarMerge =
        controller.mergeScholars(new GovernanceController.MergeRequest("sch_1", "sch_2", "理由"));
    assertThat(dataOf(scholarMerge)).containsEntry("merged", true);
    verify(governance).mergeScholars(eq("sch_1"), eq("sch_2"), eq("理由"), any());
  }

  @Test
  @DisplayName("合并影响预览是只读端点：两侧标识原样传给服务，回执里带 dryRun 标记")
  void mergePreviewIsReadOnly() {
    when(governance.previewMerge(eq("ach_1"), eq("ach_2"), any()))
        .thenReturn(Map.of("dryRun", true, "canMerge", true));

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller.mergePreview("ach_1", "ach_2");

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(dataOf(response)).containsEntry("dryRun", true).containsEntry("canMerge", true);
    verify(governance).previewMerge(eq("ach_1"), eq("ach_2"), any());
    // 预览不落任何写入动作：不能顺带调一次合并。
    verify(governance, never()).mergeAchievements(anyString(), anyString(), anyString(), any());
  }
}
