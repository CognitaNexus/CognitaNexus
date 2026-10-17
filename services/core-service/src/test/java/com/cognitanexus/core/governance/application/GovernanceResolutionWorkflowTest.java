package com.cognitanexus.core.governance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.application.AdminCatalogService;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.sharedkernel.AccountId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 裁决编排：把「裁决」与「重评估发布」串起来的那一层。
 *
 * <p>要守住两件事：一是重评估必须拿到裁决真正涉及的两端成果（漏传或传空会让整条闭环静默失效，
 * 表面上接口一切正常）；二是重评估的依据文案要带上候选裁决的理由，事后翻审计才知道当初凭什么放行。
 */
@ExtendWith(MockitoExtension.class)
class GovernanceResolutionWorkflowTest {

  @Mock GovernanceService governance;
  @Mock AdminCatalogService catalog;

  private GovernanceResolutionWorkflow workflow;
  private ActorContext actor;

  @BeforeEach
  void setUp() {
    workflow = new GovernanceResolutionWorkflow(governance, catalog);
    actor =
        new ActorContext(
            new AccountId("acc_reviewer"), "reviewer", "审核员", Set.of(RoleNames.REVIEWER), 1L);
  }

  @Test
  @DisplayName("单条裁决：把裁决涉及的成果交给重评估，并把释放结果回传")
  void resolveFeedsAffectedAchievementsToRecheck() {
    when(governance.resolveCandidate("ddc_1", "REJECT", null, "不是同一篇", actor))
        .thenReturn(List.of("ach_left", "ach_right"));
    when(catalog.recheckReleaseAfterResolution(anyList(), any(), anyString()))
        .thenReturn(List.of("ach_left"));

    GovernanceResolutionWorkflow.Outcome outcome =
        workflow.resolve("ddc_1", "REJECT", null, "不是同一篇", actor);

    ArgumentCaptor<List<String>> affected = ArgumentCaptor.forClass(List.class);
    verify(catalog).recheckReleaseAfterResolution(affected.capture(), eq(actor), anyString());
    assertThat(affected.getValue()).containsExactly("ach_left", "ach_right");
    assertThat(outcome.candidateId()).isEqualTo("ddc_1");
    assertThat(outcome.affectedCount()).isEqualTo(2);
    assertThat(outcome.released()).containsExactly("ach_left");
  }

  @Test
  @DisplayName("重评估的依据带上裁决理由：审计里要能看出这次放行是因为哪一次判定")
  void recheckReasonCarriesResolutionReason() {
    when(governance.resolveCandidate(anyString(), anyString(), any(), anyString(), any()))
        .thenReturn(List.of("ach_left"));
    when(catalog.recheckReleaseAfterResolution(anyList(), any(), anyString()))
        .thenReturn(List.of());

    workflow.resolve("ddc_1", "REJECT", null, "DOI 不同", actor);

    ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
    verify(catalog).recheckReleaseAfterResolution(anyList(), any(), reason.capture());
    assertThat(reason.getValue()).contains("ddc_1").contains("DOI 不同");
  }

  @Test
  @DisplayName("批量驳回：整批裁决完成后统一重评估一次，拿的是全部受影响成果的去重结果")
  void batchRejectRechecksOnceForWholeBatch() {
    when(governance.batchReject(anyList(), anyString(), any()))
        .thenReturn(
            new GovernanceService.BatchResolveOutcome(
                3, 2, List.of("ddc_x：候选不存在"), List.of("ach_1", "ach_2", "ach_1")));
    when(catalog.recheckReleaseAfterResolution(anyList(), any(), anyString()))
        .thenReturn(List.of("ach_1", "ach_2"));

    GovernanceResolutionWorkflow.BatchOutcome outcome =
        workflow.batchReject(List.of("ddc_1", "ddc_2", "ddc_x"), "标题仅共享通用词", actor);

    verify(catalog).recheckReleaseAfterResolution(anyList(), eq(actor), anyString());
    assertThat(outcome.requested()).isEqualTo(3);
    assertThat(outcome.resolved()).isEqualTo(2);
    assertThat(outcome.skipped()).containsExactly("ddc_x：候选不存在");
    assertThat(outcome.released()).containsExactly("ach_1", "ach_2");
  }

  @Test
  @DisplayName("裁决一步失败时不进入重评估：不能出现「裁决没成立却把成果放行」的组合")
  void failedResolutionSkipsRecheck() {
    when(governance.resolveCandidate(anyString(), anyString(), any(), anyString(), any()))
        .thenThrow(com.cognitanexus.sharedkernel.BusinessException.versionConflict("候选已裁决"));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> workflow.resolve("ddc_1", "REJECT", null, "理由", actor))
        .isInstanceOf(com.cognitanexus.sharedkernel.BusinessException.class);

    org.mockito.Mockito.verifyNoInteractions(catalog);
  }

  @Test
  @DisplayName("改判：原两端与最终两端都交给重评估，缺一组会让闭环静默少一段")
  void rebaseFeedsBothOriginalAndFinalEndpoints() {
    when(governance.rebaseCandidate("ddc_1", "上游被合并", actor))
        .thenReturn(
            new GovernanceService.RebaseOutcome(
                "ddc_1",
                "REBASED",
                "ddc_2",
                false,
                "ach_s",
                "ach_c",
                List.of("ach_a", "ach_c", "ach_s")));
    when(catalog.recheckReleaseAfterResolution(anyList(), any(), anyString()))
        .thenReturn(List.of("ach_c"));

    GovernanceResolutionWorkflow.RebaseOutcome outcome = workflow.rebase("ddc_1", "上游被合并", actor);

    ArgumentCaptor<List<String>> affected = ArgumentCaptor.forClass(List.class);
    verify(catalog).recheckReleaseAfterResolution(affected.capture(), eq(actor), anyString());
    assertThat(affected.getValue()).containsExactly("ach_a", "ach_c", "ach_s");
    assertThat(outcome.status()).isEqualTo("REBASED");
    assertThat(outcome.rebasedToId()).isEqualTo("ddc_2");
    assertThat(outcome.targetExisted()).isFalse();
    assertThat(outcome.released()).containsExactly("ach_c");

    ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
    verify(catalog).recheckReleaseAfterResolution(anyList(), any(), reason.capture());
    assertThat(reason.getValue()).contains("候选改判").contains("上游被合并");
  }

  @Test
  @DisplayName("改判结果不可变：释放列表在内存里也改不动")
  void rebaseResultIsImmutable() {
    GovernanceResolutionWorkflow.RebaseOutcome outcome =
        new GovernanceResolutionWorkflow.RebaseOutcome(
            "ddc_1", "MERGED", null, false, "ach_s", "ach_s", List.of("ach_s"));
    assertThat(outcome.released()).isUnmodifiable();
  }

  @Test
  @DisplayName("结果对象不可变：调用方改不动里面的列表，避免把「已释放」这件事在内存里改掉")
  void resultsAreImmutable() {
    GovernanceResolutionWorkflow.Outcome outcome =
        new GovernanceResolutionWorkflow.Outcome("ddc_1", 2, List.of("ach_1"));
    assertThat(outcome.released()).isUnmodifiable();

    GovernanceResolutionWorkflow.BatchOutcome batch =
        new GovernanceResolutionWorkflow.BatchOutcome(1, 1, List.of("skipped"), List.of("ach_1"));
    assertThat(batch.released()).isUnmodifiable();
    assertThat(batch.skipped()).isUnmodifiable();
  }
}
