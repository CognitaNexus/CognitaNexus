package com.cognitanexus.core.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ContributionRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.FieldProvenanceRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ScholarRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.Contribution;
import com.cognitanexus.core.catalog.domain.EffectiveStatus;
import com.cognitanexus.core.catalog.domain.PublicationYear;
import com.cognitanexus.core.catalog.domain.Scholar;
import com.cognitanexus.core.catalog.domain.ScholarProfile;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.portal.application.PortalService;
import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.RelationState;
import com.cognitanexus.sharedkernel.Visibility;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 门户资料自助维护。
 *
 * <p>这里守的是三条容易在实现里走样的规则：
 *
 * <ol>
 *   <li><b>对象级授权不能省</b>：平台管理员身份不等于可以改任意学者档案，校验一律委托给
 *       {@link PortalService#requirePortalAuthority}，本类不另写一份；
 *   <li><b>「不改」与「清空」必须区分</b>：缺省字段不能被当成空串，否则只改简介会把主页清掉；
 *   <li><b>置顶要么全成要么全不成</b>：部分成功会让学者以为自己选的三条都在。
 * </ol>
 */
class ScholarProfileServiceTest {

  private static final String SCHOLAR = "sch_test_0001";
  private static final String ACH_1 = "ach_test_0001";
  private static final String ACH_2 = "ach_test_0002";

  private ScholarRepository scholars;
  private AchievementRepository achievements;
  private ContributionRepository contributions;
  private FieldProvenanceRepository provenance;
  private ScholarAvatarStorage avatarStorage;
  private PortalService portalService;
  private PublicCatalogQueryService catalog;
  private AuditRecorder audit;
  private ScholarProfileService service;

  private final ActorContext owner =
      new ActorContext(new AccountId("acc_owner"), "owner", "本人", Set.of(RoleNames.USER), 1L);

  @BeforeEach
  void setUp() {
    scholars = mock(ScholarRepository.class);
    achievements = mock(AchievementRepository.class);
    contributions = mock(ContributionRepository.class);
    provenance = mock(FieldProvenanceRepository.class);
    avatarStorage = mock(ScholarAvatarStorage.class);
    portalService = mock(PortalService.class);
    catalog = mock(PublicCatalogQueryService.class);
    audit = mock(AuditRecorder.class);
    service =
        new ScholarProfileService(
            scholars,
            achievements,
            contributions,
            provenance,
            avatarStorage,
            portalService,
            catalog,
            audit);

    when(scholars.findById(SCHOLAR)).thenReturn(Optional.of(scholar(SCHOLAR, null)));
    when(scholars.findProfile(SCHOLAR)).thenReturn(Optional.of(ScholarProfile.empty(SCHOLAR)));
    when(scholars.listFeatured(SCHOLAR)).thenReturn(List.of());
    lenient().when(achievements.findById(anyString())).thenReturn(Optional.empty());
    lenient().when(contributions.listByAchievement(anyString())).thenReturn(List.of());
  }

  // ---------------------------------------------------------------------------
  // 授权
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("未登录：拒绝，且不触碰任何写入")
  void rejectsAnonymous() {
    assertThatThrownBy(() -> service.updateProfile(null, SCHOLAR, "简介", null))
        .isInstanceOf(BusinessException.class);

    verify(scholars, never()).updateResearchSummary(anyString(), any());
  }

  @Test
  @DisplayName("对象级授权委托给 PortalService，而不是自己判断管理员身份")
  void delegatesObjectLevelAuthority() {
    doThrow(BusinessException.accessDenied("不是管理者"))
        .when(portalService)
        .requirePortalAuthority(any(), eq(SCHOLAR));

    assertThatThrownBy(() -> service.updateProfile(owner, SCHOLAR, "简介", null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不是管理者");

    verify(scholars, never()).updateResearchSummary(anyString(), any());
  }

  @Test
  @DisplayName("已合并的档案拒绝维护：应到合并后的档案上改")
  void rejectsMergedScholar() {
    when(scholars.findById("sch_old")).thenReturn(Optional.of(scholar("sch_old", "sch_new")));

    assertThatThrownBy(() -> service.updateProfile(owner, "sch_old", "简介", null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已被合并");
  }

  // ---------------------------------------------------------------------------
  // 简介与主页
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("只提交简介时不动主页（缺省 ≠ 清空）")
  void updatingSummaryLeavesHomepageAlone() {
    service.updateProfile(owner, SCHOLAR, "新的研究简介", null);

    verify(scholars).updateResearchSummary(SCHOLAR, "新的研究简介");
    verify(scholars, never()).updateHomepageUrl(anyString(), any());
    verify(provenance).upsertReviewed("SCHOLAR", SCHOLAR, "researchSummary", "MANUAL", "新的研究简介");
  }

  @Test
  @DisplayName("空串表示清空：落库为 null，公开页退回「尚未填写」")
  void blankValueClearsField() {
    service.updateProfile(owner, SCHOLAR, "", "");

    verify(scholars).updateResearchSummary(SCHOLAR, null);
    verify(scholars).updateHomepageUrl(SCHOLAR, null);
  }

  @Test
  @DisplayName("简介超长被拒，且没有半截写入")
  void rejectsOverlongSummary() {
    String tooLong = "字".repeat(2001);

    assertThatThrownBy(() -> service.updateProfile(owner, SCHOLAR, tooLong, null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("2000");

    verify(scholars, never()).updateResearchSummary(anyString(), any());
  }

  @Test
  @DisplayName("拒绝 javascript: 主页：它会原样变成档案页上的链接")
  void rejectsNonHttpHomepage() {
    assertThatThrownBy(() -> service.updateProfile(owner, SCHOLAR, null, "javascript:alert(1)"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("http");

    verify(scholars, never()).updateHomepageUrl(anyString(), any());
  }

  @Test
  @DisplayName("主页缺少主机名被拒（https:///path 语法合法但没有主机）")
  void rejectsHomepageWithoutHost() {
    assertThatThrownBy(() -> service.updateProfile(owner, SCHOLAR, null, "https:///path"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("主机名");
  }

  @Test
  @DisplayName("主页不是合法 URL 时也被拒（https:// 这种半截输入）")
  void rejectsMalformedHomepage() {
    assertThatThrownBy(() -> service.updateProfile(owner, SCHOLAR, null, "https://"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("地址");
  }

  @Test
  @DisplayName("两个字段都没提交时明确报错，而不是默默成功")
  void rejectsEmptyUpdate() {
    assertThatThrownBy(() -> service.updateProfile(owner, SCHOLAR, null, null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("没有需要修改的字段");
  }

  // ---------------------------------------------------------------------------
  // 置顶
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("置顶超过上限被拒，且一条都不写")
  void rejectsTooManyFeatured() {
    assertThatThrownBy(() -> service.updateFeatured(owner, SCHOLAR, List.of("a", "b", "c", "d")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("3");

    verify(scholars, never()).replaceFeatured(anyString(), any());
  }

  @Test
  @DisplayName("置顶别人的成果被拒：置顶不是收藏，不能指向没有合格关系的成果")
  void rejectsForeignAchievement() {
    when(achievements.findById(ACH_1)).thenReturn(Optional.of(achievement(ACH_1, null)));
    when(contributions.listByAchievement(ACH_1))
        .thenReturn(List.of(contribution(ACH_1, "sch_someone_else", RelationState.AUTO)));

    assertThatThrownBy(() -> service.updateFeatured(owner, SCHOLAR, List.of(ACH_1)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("合格参与关系");

    verify(scholars, never()).replaceFeatured(anyString(), any());
  }

  @Test
  @DisplayName("置顶不可公开的成果被拒（置顶不能成为绕过可见性的入口）")
  void rejectsNonPublicAchievement() {
    when(achievements.findById(ACH_1)).thenReturn(Optional.of(achievement(ACH_1, null)));
    when(contributions.listByAchievement(ACH_1))
        .thenReturn(List.of(contribution(ACH_1, SCHOLAR, RelationState.AUTO)));
    when(catalog.requirePublicTarget(ACH_1)).thenThrow(BusinessException.notFound("不可公开"));

    assertThatThrownBy(() -> service.updateFeatured(owner, SCHOLAR, List.of(ACH_1)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不可公开");
  }

  @Test
  @DisplayName("置顶成功：顺序按提交顺序落库，重复项去重保序")
  void keepsOrderAndDeduplicates() {
    when(achievements.findById(anyString()))
        .thenAnswer(
            invocation -> Optional.of(achievement(invocation.getArgument(0, String.class), null)));
    when(contributions.listByAchievement(anyString()))
        .thenAnswer(
            invocation ->
                List.of(
                    contribution(
                        invocation.getArgument(0, String.class),
                        SCHOLAR,
                        RelationState.SELF_CONFIRMED)));
    when(catalog.requirePublicTarget(anyString()))
        .thenAnswer(
            invocation ->
                new PublicCatalogQueryService.PublicTarget(
                    achievement(invocation.getArgument(0, String.class), null), null));

    service.updateFeatured(owner, SCHOLAR, List.of(ACH_2, ACH_1, ACH_2));

    verify(scholars).replaceFeatured(SCHOLAR, List.of(ACH_2, ACH_1));
  }

  @Test
  @DisplayName("清空置顶：空数组是合法输入，不是错误")
  void clearingFeaturedIsAllowed() {
    service.updateFeatured(owner, SCHOLAR, List.of());

    verify(scholars).replaceFeatured(SCHOLAR, List.of());
    verify(audit).record(any());
  }

  // ---------------------------------------------------------------------------
  // 头像
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("上传头像：先落盘再落库，最后才删旧文件")
  void uploadAvatarWritesThenDeletesOld() {
    when(scholars.findProfile(SCHOLAR))
        .thenReturn(
            Optional.of(new ScholarProfile(SCHOLAR, null, "sav_old.png", "image/png", null)));
    when(avatarStorage.store(eq(SCHOLAR), eq("image/png"), any()))
        .thenReturn(new ScholarAvatarStorage.StoredAvatar("sav_new.png", "image/png", 68L, "hash"));

    service.uploadAvatar(owner, SCHOLAR, "a.png", "image/png", new byte[] {1, 2, 3});

    verify(scholars).updateAvatar(SCHOLAR, "sav_new.png", "image/png");
    verify(avatarStorage).deleteQuietly("sav_old.png");
  }

  @Test
  @DisplayName("没有头像时删除是 404，不是静默成功")
  void removeAvatarWhenAbsent() {
    assertThatThrownBy(() -> service.removeAvatar(owner, SCHOLAR))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("没有头像");
  }

  @Test
  @DisplayName("读公开头像：没有头像时 404，不返回占位图")
  void avatarMissing() {
    assertThatThrownBy(() -> service.avatar(SCHOLAR))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("未上传头像");
  }

  // ---------------------------------------------------------------------------
  // 公开视图
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("公开置顶跳过已不可公开的条目并计数，而不是静默丢弃")
  void publicFeaturedCountsHidden() {
    when(scholars.listFeatured(SCHOLAR)).thenReturn(List.of(ACH_1, ACH_2));
    when(catalog.requirePublicTarget(ACH_1))
        .thenReturn(new PublicCatalogQueryService.PublicTarget(achievement(ACH_1, null), null));
    when(catalog.requirePublicTarget(ACH_2)).thenThrow(BusinessException.notFound("不可公开"));

    var view = service.publicFeaturedOf(SCHOLAR);

    assertThat((List<?>) view.get("items")).hasSize(1);
    assertThat(view.get("unavailableCount")).isEqualTo(1);
  }

  @Test
  @DisplayName("公开自述区块：没维护过时字段为空，但字段本身仍存在")
  void publicProfileOfEmptyScholar() {
    var view = service.publicProfileOf(SCHOLAR);

    assertThat(view).containsKey("homepageUrl").containsKey("avatarUrl");
    assertThat(view.get("homepageUrl")).isNull();
    assertThat(view.get("avatarUrl")).isNull();
  }

  // ---------------------------------------------------------------------------
  // 夹具
  // ---------------------------------------------------------------------------

  private static Scholar scholar(String id, String mergedInto) {
    return new Scholar(
        id,
        "测试学者",
        "测试学者",
        DataNature.REAL,
        Scholar.ClaimedStatus.CLAIMED,
        "测试大学",
        null,
        mergedInto,
        1L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static Achievement achievement(String id, String mergedInto) {
    return new Achievement(
        id,
        AchievementType.PAPER,
        "标题 " + id,
        ("标题 " + id).toLowerCase(java.util.Locale.ROOT),
        DataNature.REAL,
        Visibility.PUBLIC,
        EffectiveStatus.EFFECTIVE,
        PublicationYear.of(2024),
        mergedInto,
        1L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static Contribution contribution(
      String achievementId, String scholarId, RelationState state) {
    return new Contribution(
        "con_" + achievementId + "_" + scholarId,
        scholarId,
        achievementId,
        "AUTHOR",
        1,
        state,
        null,
        1L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }
}
