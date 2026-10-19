package com.cognitanexus.core.integrity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.application.CatalogChangePublisher;
import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.EffectiveStatus;
import com.cognitanexus.core.catalog.domain.PublicationYear;
import com.cognitanexus.core.integrity.domain.IntegrityNotice;
import com.cognitanexus.core.integrity.domain.IntegrityNoticeType;
import com.cognitanexus.core.integrity.domain.IntegritySummary;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.ErrorCode;
import com.cognitanexus.sharedkernel.Visibility;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 学术诚信声明的登记与撤销。
 *
 * <p>这一组用例守的东西按重要度排列：① 声明不得改写成果字段（红线）；② 撤销必须成套（人、时间、
 * 理由）且撤销后状态真的回落；③ 能力校验在服务层，不靠 URL；④ 同一类型不能并存两条生效声明。
 *
 * <p>时钟是注入的：声明日期「不能晚于今天」这条规则若用系统时钟断验，用例会在跨零点时随机失败，
 * 而这类失败最容易被当成环境抖动忽略。
 */
class IntegrityNoticeServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");

  private AchievementRepository achievements;
  private IntegrityNoticeRepository notices;
  private CatalogChangePublisher publisher;
  private AuditRecorder audit;
  private IntegrityNoticeService service;

  private final ActorContext reviewer =
      new ActorContext(
          new AccountId("acc_reviewer"), "reviewer", "审核员", Set.of(RoleNames.REVIEWER), 1L);

  private final ActorContext plainUser =
      new ActorContext(new AccountId("acc_user"), "user", "普通用户", Set.of(RoleNames.USER), 1L);

  @BeforeEach
  void setUp() {
    achievements = mock(AchievementRepository.class);
    notices = mock(IntegrityNoticeRepository.class);
    publisher = mock(CatalogChangePublisher.class);
    audit = mock(AuditRecorder.class);
    service =
        new IntegrityNoticeService(
            achievements, notices, publisher, audit, Clock.fixed(NOW, ZoneOffset.UTC));
    when(achievements.findById(anyString())).thenReturn(Optional.of(paper("ach_1", null)));
    when(notices.findActive(anyString(), anyString())).thenReturn(Optional.empty());
  }

  private static Achievement paper(String id, String mergedIntoId) {
    return new Achievement(
        id,
        AchievementType.PAPER,
        "Attention Is All You Need",
        "attention is all you need",
        DataNature.REAL,
        Visibility.PUBLIC,
        EffectiveStatus.EFFECTIVE,
        PublicationYear.of(2017),
        mergedIntoId,
        3L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static IntegrityNoticeService.RegisterCommand retraction() {
    return new IntegrityNoticeService.RegisterCommand(
        "RETRACTION",
        "示例期刊编辑部",
        "经调查确认图 3 数据存在重复使用，编辑部决定撤稿",
        "https://example.org/retraction/1",
        LocalDate.parse("2026-10-01"));
  }

  @Test
  @DisplayName("登记成功：只写声明表，不改成果任何字段，并外发一次目录变更")
  void registersWithoutTouchingAchievement() {
    service.register(reviewer, "ach_1", retraction());

    ArgumentCaptor<IntegrityNotice> captor = ArgumentCaptor.forClass(IntegrityNotice.class);
    verify(notices).insert(captor.capture());
    IntegrityNotice saved = captor.getValue();
    assertThat(saved.achievementId()).isEqualTo("ach_1");
    assertThat(saved.noticeType()).isEqualTo(IntegrityNoticeType.RETRACTION);
    assertThat(saved.status()).isEqualTo(IntegrityNotice.STATUS_ACTIVE);
    assertThat(saved.declaredBy()).isEqualTo("示例期刊编辑部");
    assertThat(saved.sourceUrl()).isEqualTo("https://example.org/retraction/1");
    assertThat(saved.recordedBy()).isEqualTo("acc_reviewer");
    assertThat(saved.recordedAt()).isEqualTo(NOW);

    // 红线：声明不写成果表。这里没有任何 achievements 的写操作被调用。
    verify(achievements, never())
        .updatePublicationState(anyString(), anyString(), anyString(), anyLong());
    // 但必须外发事件，否则检索侧投影永远看不到这条声明。
    verify(publisher).publish(any(Achievement.class), eq(3L));
    verify(audit).record(any(AuditRecorder.AuditEntry.class));
  }

  @Test
  @DisplayName("未知类型被拒：报错里列出允许的三个值，而不是只说不认识")
  void rejectsUnknownType() {
    IntegrityNoticeService.RegisterCommand command =
        new IntegrityNoticeService.RegisterCommand("RETRACT", "某期刊", "理由足够长的一段说明文字", null, null);
    assertThatThrownBy(() -> service.register(reviewer, "ach_1", command))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("CONCERN / CORRECTION / RETRACTION");
    verify(notices, never()).insert(any());
  }

  @Test
  @DisplayName("理由过短被拒：短到无法判断这次声明的依据")
  void rejectsTooShortReason() {
    IntegrityNoticeService.RegisterCommand command =
        new IntegrityNoticeService.RegisterCommand("RETRACTION", "某期刊", "数据有问题", null, null);
    assertThatThrownBy(() -> service.register(reviewer, "ach_1", command))
        .hasMessageContaining("至少");
    verify(notices, never()).insert(any());
  }

  @Test
  @DisplayName("声明主体为空被拒：没有主体的撤稿标记与平台擅自下架无法区分")
  void rejectsBlankDeclaredBy() {
    IntegrityNoticeService.RegisterCommand command =
        new IntegrityNoticeService.RegisterCommand(
            "CONCERN", "  ", "对图 2 的统计方法存在疑问，尚待作者答复", null, null);
    assertThatThrownBy(() -> service.register(reviewer, "ach_1", command))
        .hasMessageContaining("声明主体");
  }

  @Test
  @DisplayName("链接只接受 http/https：javascript: 伪协议会把详情页交给登记人控制")
  void rejectsNonHttpSourceUrl() {
    IntegrityNoticeService.RegisterCommand command =
        new IntegrityNoticeService.RegisterCommand(
            "CORRECTION", "某期刊", "已发布更正说明，修正表 2 的单位错误", "javascript:alert(1)", null);
    assertThatThrownBy(() -> service.register(reviewer, "ach_1", command))
        .hasMessageContaining("http");
  }

  @Test
  @DisplayName("声明日期不能在未来：未来的撤稿日期是录入错误，不是事实")
  void rejectsFutureAnnouncedOn() {
    IntegrityNoticeService.RegisterCommand command =
        new IntegrityNoticeService.RegisterCommand(
            "RETRACTION", "某期刊", "经复核决定撤稿，理由见图 3 说明", null, LocalDate.parse("2026-12-31"));
    assertThatThrownBy(() -> service.register(reviewer, "ach_1", command))
        .hasMessageContaining("不能晚于今天");
  }

  @Test
  @DisplayName("被合并的成果不能登记：墓碑上写的声明永远不会被展示")
  void rejectsMergedAchievement() {
    when(achievements.findById("ach_old")).thenReturn(Optional.of(paper("ach_old", "ach_new")));
    assertThatThrownBy(() -> service.register(reviewer, "ach_old", retraction()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("ach_new");
  }

  @Test
  @DisplayName("同一类型不能并存两条生效声明：报错给出已有那条的标识")
  void rejectsDuplicateActiveNotice() {
    when(notices.findActive("ach_1", "RETRACTION"))
        .thenReturn(
            Optional.of(active("integrity_existing", "ach_1", IntegrityNoticeType.RETRACTION)));

    assertThatThrownBy(() -> service.register(reviewer, "ach_1", retraction()))
        .hasMessageContaining("integrity_existing");
    verify(notices, never()).insert(any());
  }

  @Test
  @DisplayName("无治理能力被拒：普通用户不能给自己或别人的成果打撤稿标记")
  void rejectsActorWithoutCapability() {
    assertThatThrownBy(() -> service.register(plainUser, "ach_1", retraction()))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            error ->
                assertThat(((BusinessException) error).errorCode())
                    .isEqualTo(ErrorCode.ACCESS_DENIED));
    verify(notices, never()).insert(any());
  }

  @Test
  @DisplayName("撤销：写入撤销三件套、记审计、重算状态并外发")
  void revokesWithAuditAndRepublish() {
    IntegrityNotice existing = active("integrity_1", "ach_1", IntegrityNoticeType.RETRACTION);
    when(notices.findById("integrity_1"))
        .thenReturn(Optional.of(existing))
        .thenReturn(Optional.of(revoked("integrity_1", "ach_1", IntegrityNoticeType.RETRACTION)));
    when(notices.revoke(eq("integrity_1"), eq("acc_reviewer"), eq(NOW), anyString())).thenReturn(1);

    IntegrityNotice result = service.revoke(reviewer, "integrity_1", "经作者申诉与编辑部复核，原声明依据不成立");

    assertThat(result.status()).isEqualTo(IntegrityNotice.STATUS_REVOKED);
    verify(notices).revoke(eq("integrity_1"), eq("acc_reviewer"), eq(NOW), anyString());
    verify(publisher).publish(any(Achievement.class), eq(3L));
    verify(audit).record(any(AuditRecorder.AuditEntry.class));
  }

  @Test
  @DisplayName("撤销理由过短被拒：撤销本身也需要解释")
  void rejectsTooShortRevokeReason() {
    when(notices.findById("integrity_1"))
        .thenReturn(Optional.of(active("integrity_1", "ach_1", IntegrityNoticeType.RETRACTION)));
    assertThatThrownBy(() -> service.revoke(reviewer, "integrity_1", "算了"))
        .hasMessageContaining("至少");
    verify(notices, never()).revoke(anyString(), anyString(), any(), anyString());
  }

  @Test
  @DisplayName("重复撤销按版本冲突返回：不覆盖第一次写下的撤销理由")
  void rejectsRevokingTwice() {
    when(notices.findById("integrity_1"))
        .thenReturn(Optional.of(revoked("integrity_1", "ach_1", IntegrityNoticeType.RETRACTION)));
    assertThatThrownBy(() -> service.revoke(reviewer, "integrity_1", "经复核原声明依据不成立的决定"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            error ->
                assertThat(((BusinessException) error).errorCode())
                    .isEqualTo(ErrorCode.VERSION_CONFLICT));
  }

  @Test
  @DisplayName("并发撤销时影响 0 行按版本冲突返回，而不是当成成功")
  void treatsZeroRowRevokeAsConflict() {
    when(notices.findById("integrity_1"))
        .thenReturn(Optional.of(active("integrity_1", "ach_1", IntegrityNoticeType.RETRACTION)));
    when(notices.revoke(anyString(), anyString(), any(), anyString())).thenReturn(0);

    assertThatThrownBy(() -> service.revoke(reviewer, "integrity_1", "经复核原声明依据不成立的决定"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            error ->
                assertThat(((BusinessException) error).errorCode())
                    .isEqualTo(ErrorCode.VERSION_CONFLICT));
  }

  @Test
  @DisplayName("汇总取最严重的一条：撤稿与更正并存时对外显示「已撤稿」")
  void summaryTakesMostSevere() {
    IntegritySummary summary =
        IntegritySummary.of(
            List.of(
                active("n1", "ach_1", IntegrityNoticeType.CORRECTION),
                active("n2", "ach_1", IntegrityNoticeType.RETRACTION)));

    assertThat(summary.status()).isEqualTo("RETRACTION");
    assertThat(summary.label()).isEqualTo("已撤稿");
    assertThat(summary.activeCount()).isEqualTo(2);
    assertThat(summary.retracted()).isTrue();
  }

  @Test
  @DisplayName("唯一一条声明撤销后回到「无诚信声明」，但历史条数仍记着")
  void summaryFallsBackAfterRevoke() {
    IntegritySummary summary =
        IntegritySummary.of(List.of(revoked("n1", "ach_1", IntegrityNoticeType.RETRACTION)));

    assertThat(summary.status()).isEqualTo(IntegritySummary.STATUS_NONE);
    assertThat(summary.activeCount()).isZero();
    assertThat(summary.historyCount()).isEqualTo(1);
    assertThat(summary.description()).contains("撤销");
    assertThat(summary.retracted()).isFalse();
  }

  @Test
  @DisplayName("没有声明时也给出完整字段，不返回空对象")
  void summaryWithoutNoticesIsExplicit() {
    IntegritySummary summary = IntegritySummary.of(List.of());
    assertThat(summary.status()).isEqualTo(IntegritySummary.STATUS_NONE);
    assertThat(summary.label()).isEqualTo("无诚信声明");
    assertThat(summary.activeTypes()).isEmpty();
    assertThat(summary.historyCount()).isZero();
  }

  // ---------------------------------------------------------------------------

  private static IntegrityNotice active(String id, String achievementId, IntegrityNoticeType type) {
    return new IntegrityNotice(
        id,
        achievementId,
        type,
        IntegrityNotice.STATUS_ACTIVE,
        "示例期刊编辑部",
        "经调查确认图 3 数据存在重复使用，编辑部决定撤稿",
        "https://example.org/retraction/1",
        LocalDate.parse("2026-10-01"),
        "acc_reviewer",
        NOW,
        null,
        null,
        null);
  }

  @Test
  @DisplayName("治理端读接口同样要求治理能力：普通用户连一条声明都读不到")
  void managementReadsRequireGovernanceCapability() {
    assertThatThrownBy(() -> service.recent(plainUser, 50, true))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
    assertThatThrownBy(() -> service.noticesOf(plainUser, "ach_1"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
    assertThatThrownBy(() -> service.summaryOf(plainUser, "ach_1"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
    // 不只是「返回空列表」：被拒时根本不应查库，否则越权请求仍然消耗了数据库
    verify(notices, never()).listRecent(anyInt(), anyBoolean());
    verify(notices, never()).findByAchievement(anyString());
  }

  private static IntegrityNotice revoked(
      String id, String achievementId, IntegrityNoticeType type) {
    return new IntegrityNotice(
        id,
        achievementId,
        type,
        IntegrityNotice.STATUS_REVOKED,
        "示例期刊编辑部",
        "经调查确认图 3 数据存在重复使用，编辑部决定撤稿",
        "https://example.org/retraction/1",
        LocalDate.parse("2026-10-01"),
        "acc_reviewer",
        NOW,
        "acc_other",
        NOW.plusSeconds(3600),
        "经作者申诉与编辑部复核，原声明依据不成立");
  }
}
