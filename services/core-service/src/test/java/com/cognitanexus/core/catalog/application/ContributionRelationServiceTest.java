package com.cognitanexus.core.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ContributionRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.Contribution;
import com.cognitanexus.core.catalog.domain.EffectiveStatus;
import com.cognitanexus.core.catalog.domain.PublicationYear;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.portal.application.PortalService;
import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.ErrorCode;
import com.cognitanexus.sharedkernel.RelationState;
import com.cognitanexus.sharedkernel.Visibility;
import java.time.Instant;
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
 * 成果级关系处置的状态机与授权。
 *
 * <p>依据：SRS FR07、AC-IMPL-FR07-01、BR-IMP-03、BR-IMP-08。
 *
 * <p>这里逐条验证三类边界：谁能改（对象级授权而不是角色）、能改成什么（前置状态约束）、改完
 * 之后外部看到什么（必须重发目录变更，否则公开视图会继续按旧状态把争议关系显示成确定合作）。
 */
@ExtendWith(MockitoExtension.class)
class ContributionRelationServiceTest {

  @Mock ContributionRepository contributions;
  @Mock AchievementRepository achievements;
  @Mock PortalService portal;
  @Mock CatalogChangePublisher catalogChanges;
  @Mock AuditRecorder audit;
  @Mock com.cognitanexus.core.notification.application.NotificationService notifications;

  private ContributionRelationService service;
  private ActorContext owner;
  private ActorContext stranger;
  private ActorContext admin;

  @BeforeEach
  void setUp() {
    service =
        new ContributionRelationService(
            contributions, achievements, portal, catalogChanges, audit, notifications);
    owner = new ActorContext(new AccountId("acc_owner"), "owner", "本人", Set.of(RoleNames.USER), 1L);
    stranger =
        new ActorContext(new AccountId("acc_other"), "other", "路人", Set.of(RoleNames.USER), 1L);
    admin =
        new ActorContext(
            new AccountId("acc_admin"), "admin", "平台管理员", Set.of(RoleNames.PLATFORM_ADMIN), 1L);
  }

  private static Contribution contribution(RelationState state) {
    return new Contribution(
        "ctb_1",
        "sch_1",
        "ach_1",
        "AUTHOR",
        1,
        state,
        "raw_1",
        2L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static Achievement achievement() {
    return new Achievement(
        "ach_1",
        AchievementType.PAPER,
        "A Title",
        "a title",
        DataNature.REAL,
        Visibility.PUBLIC,
        EffectiveStatus.EFFECTIVE,
        PublicationYear.of(2024),
        null,
        7L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private void givenContribution(RelationState state) {
    when(contributions.findContributionById("ctb_1")).thenReturn(Optional.of(contribution(state)));
  }

  /**
   * 迁移前后的两次读取。
   *
   * <p>服务在迁移成功后会重读一次当前状态再组装响应——响应必须反映库里的事实，而不是「我记得
   * 我刚设成了什么」。因此这里要按调用顺序给出前后两个状态。
   */
  private void givenContributionTransition(RelationState before, RelationState after) {
    when(contributions.findContributionById("ctb_1"))
        .thenReturn(Optional.of(contribution(before)), Optional.of(contribution(after)));
  }

  private void givenTransitionSucceeds(RelationState from, RelationState to) {
    when(contributions.transitionRelationState("ctb_1", from, to)).thenReturn(1);
  }

  private void givenAchievement() {
    when(achievements.findById("ach_1")).thenReturn(Optional.of(achievement()));
  }

  @Test
  @DisplayName("本人确认：AUTO → SELF_CONFIRMED，写审计并重发目录变更")
  void ownerConfirms() {
    givenContributionTransition(RelationState.AUTO, RelationState.SELF_CONFIRMED);
    givenTransitionSucceeds(RelationState.AUTO, RelationState.SELF_CONFIRMED);
    givenAchievement();

    var view = service.confirmByOwner(owner, "ctb_1");

    assertThat(view.get("previousState")).isEqualTo("AUTO");
    assertThat(view.get("qualified")).isEqualTo(true);
    verify(audit)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                entry -> "RELATION_SELF_CONFIRMED".equals(entry.action())));
    // 关系状态是公开投影的一部分，不重发事件等于让公开视图继续按旧状态展示。
    verify(catalogChanges).publish(any(Achievement.class), anyLong());
  }

  @Test
  @DisplayName("重复确认是幂等的：不写审计、不发事件，也不报错")
  void repeatedConfirmIsNoop() {
    givenContribution(RelationState.SELF_CONFIRMED);
    givenAchievement();

    var view = service.confirmByOwner(owner, "ctb_1");

    assertThat(view.get("relationState")).isEqualTo("SELF_CONFIRMED");
    assertThat(view.get("note")).asString().contains("未做变更");
    verifyNoInteractions(audit, catalogChanges);
  }

  @Test
  @DisplayName("非门户管理者不能替他人确认：管理员身份也不构成对象级授权")
  void strangerCannotConfirm() {
    givenContribution(RelationState.AUTO);
    doThrow(BusinessException.accessDenied("当前账号不是该学者门户的管理者"))
        .when(portal)
        .requirePortalAuthority(any(), eq("sch_1"));

    assertThatThrownBy(() -> service.confirmByOwner(stranger, "ctb_1"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.ACCESS_DENIED);
    verifyNoInteractions(audit, catalogChanges);
  }

  @Test
  @DisplayName("待核验关系不能被本人确认顶掉：证据不足的成因不会因一句声明消失")
  void pendingVerifyCannotBeSelfConfirmed() {
    givenContribution(RelationState.PENDING_VERIFY);

    assertThatThrownBy(() -> service.confirmByOwner(owner, "ctb_1"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("待核验");
    verify(contributions, never())
        .transitionRelationState(anyString(), any(RelationState.class), any(RelationState.class));
  }

  @Test
  @DisplayName("异议必须写理由：否则一个按钮就能把他人成果从公开关系里拿掉且不留依据")
  void disputeRequiresReason() {
    givenContribution(RelationState.AUTO);

    assertThatThrownBy(() -> service.disputeByOwner(owner, "ctb_1", "  "))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("理由");
    verifyNoInteractions(audit, catalogChanges);
  }

  @Test
  @DisplayName("本人提出异议：AUTO → DISPUTED，并立即重发事件让合作边停止建立")
  void ownerDisputes() {
    givenContributionTransition(RelationState.AUTO, RelationState.DISPUTED);
    givenTransitionSucceeds(RelationState.AUTO, RelationState.DISPUTED);
    givenAchievement();

    var view = service.disputeByOwner(owner, "ctb_1", "这不是我的成果，署名与我无关");

    assertThat(view.get("relationState")).isEqualTo("DISPUTED");
    assertThat(view.get("qualified")).isEqualTo(false);
    verify(audit)
        .record(
            org.mockito.ArgumentMatchers.argThat(
                entry -> "RELATION_DISPUTED".equals(entry.action())));
    verify(catalogChanges).publish(any(Achievement.class), anyLong());
  }

  @Test
  @DisplayName("撤回异议回到自动关联，而不是直接变成已确认")
  void withdrawDisputeReturnsToAuto() {
    givenContributionTransition(RelationState.DISPUTED, RelationState.AUTO);
    givenTransitionSucceeds(RelationState.DISPUTED, RelationState.AUTO);
    givenAchievement();

    var view = service.withdrawDisputeByOwner(owner, "ctb_1");

    assertThat(view.get("relationState")).isEqualTo("AUTO");
    assertThat(view.get("previousState")).isEqualTo("DISPUTED");
  }

  @Test
  @DisplayName("已被排除的关系本人不能自行恢复：推翻裁决属于治理端职责")
  void excludedCannotBeConfirmedByOwner() {
    givenContribution(RelationState.EXCLUDED);

    assertThatThrownBy(() -> service.confirmByOwner(owner, "ctb_1"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不成立");
  }

  @Test
  @DisplayName("迁移影响 0 行时按版本冲突报错，而不是当作成功")
  void concurrentChangeIsReported() {
    givenContribution(RelationState.AUTO);
    when(contributions.transitionRelationState(
            "ctb_1", RelationState.AUTO, RelationState.SELF_CONFIRMED))
        .thenReturn(0);

    assertThatThrownBy(() -> service.confirmByOwner(owner, "ctb_1"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.VERSION_CONFLICT);
  }

  @Test
  @DisplayName("治理端裁决 EXCLUDE：争议 → 已排除，并写明理由")
  void adminExcludes() {
    givenContributionTransition(RelationState.DISPUTED, RelationState.EXCLUDED);
    givenTransitionSucceeds(RelationState.DISPUTED, RelationState.EXCLUDED);
    givenAchievement();

    var view = service.resolveByAdmin(admin, "ctb_1", "EXCLUDE", "作者本人否认且来源署名不一致");

    assertThat(view.get("relationState")).isEqualTo("EXCLUDED");
    ArgumentCaptor<AuditRecorder.AuditEntry> entry =
        ArgumentCaptor.forClass(AuditRecorder.AuditEntry.class);
    verify(audit).record(entry.capture());
    assertThat(entry.getValue().reason()).contains("作者本人否认");
  }

  @Test
  @DisplayName("治理端裁决 CONFIRM：待核验 → 已确认，这是唯一把待核验升为确定的路径")
  void adminConfirmsPendingVerify() {
    givenContributionTransition(RelationState.PENDING_VERIFY, RelationState.SELF_CONFIRMED);
    givenTransitionSucceeds(RelationState.PENDING_VERIFY, RelationState.SELF_CONFIRMED);
    givenAchievement();

    var view = service.resolveByAdmin(admin, "ctb_1", "CONFIRM", "已核对原文署名");

    assertThat(view.get("relationState")).isEqualTo("SELF_CONFIRMED");
  }

  @Test
  @DisplayName("治理端 RESTORE 只对已排除或争议有效：已确认的关系不能借「恢复」抹回自动关联")
  void restoreRejectsSelfConfirmedState() {
    givenContribution(RelationState.SELF_CONFIRMED);

    assertThatThrownBy(() -> service.resolveByAdmin(admin, "ctb_1", "RESTORE", "误操作"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("当前状态相符");
  }

  @Test
  @DisplayName("对已经处于该结论的关系再裁决一次是幂等的：不写审计、不发事件")
  void resolveIsIdempotent() {
    givenContribution(RelationState.EXCLUDED);
    givenAchievement();

    var view = service.resolveByAdmin(admin, "ctb_1", "EXCLUDE", "重复提交");

    assertThat(view.get("relationState")).isEqualTo("EXCLUDED");
    assertThat(view.get("note")).asString().contains("未做变更");
    verifyNoInteractions(audit, catalogChanges);
  }

  @Test
  @DisplayName("未知裁决结论被拒：不接受把中文或近义词当结论")
  void unknownDecisionRejected() {
    givenContribution(RelationState.DISPUTED);

    assertThatThrownBy(() -> service.resolveByAdmin(admin, "ctb_1", "删除", "理由"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("未知裁决结论");
  }

  @Test
  @DisplayName("无治理能力不能裁决：审核员身份也不等于可以改这里")
  void reviewerWithoutCapabilityCannotResolve() {
    ActorContext user =
        new ActorContext(new AccountId("acc_u"), "u", "普通用户", Set.of(RoleNames.USER), 1L);

    assertThatThrownBy(() -> service.resolveByAdmin(user, "ctb_1", "EXCLUDE", "理由"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.ACCESS_DENIED);
  }

  @Test
  @DisplayName("列表随记录给出可执行动作：不可行的处置不显示成可点的按钮")
  void listCarriesAvailableActions() {
    when(contributions.listByScholar("sch_1"))
        .thenReturn(java.util.List.of(contribution(RelationState.DISPUTED)));
    givenAchievement();

    var items = service.listOwnedContributions(owner, "sch_1", 10);

    assertThat(items).hasSize(1);
    assertThat(items.get(0).get("availableActions"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
        .containsExactly("WITHDRAW_DISPUTE");
  }
}
