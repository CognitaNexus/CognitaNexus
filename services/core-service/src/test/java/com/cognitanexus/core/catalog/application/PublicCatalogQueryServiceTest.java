package com.cognitanexus.core.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ContributionRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ScholarRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.TopicRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.EffectiveStatus;
import com.cognitanexus.core.catalog.domain.PublicationYear;
import com.cognitanexus.core.catalog.domain.Scholar;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.Visibility;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 公开详情与合并重定向。
 *
 * <p>这里守的是一条曾经被绕过的规则：被合并的旧标识要跳到幸存实体，但<b>跳转不是授权的替代</b>。
 * 早先的实现解析出幸存实体后直接组装响应，于是任何拿到旧标识的人都能读到仍处于待治理或受限状态
 * 的内容——公开资格校验被跳转分支绕开了。
 */
class PublicCatalogQueryServiceTest {

  private AchievementRepository achievements;
  private ScholarRepository scholars;
  private PublicCatalogQueryService service;

  @BeforeEach
  void setUp() {
    achievements = mock(AchievementRepository.class);
    scholars = mock(ScholarRepository.class);
    ContributionRepository contributions = mock(ContributionRepository.class);
    TopicRepository topics = mock(TopicRepository.class);
    service =
        new PublicCatalogQueryService(achievements, scholars, contributions, topics);
    // 这些查询在校验通过后才发生；默认给空，避免每个用例重复声明。
    lenient().when(achievements.findPaper(anyString())).thenReturn(Optional.empty());
    lenient().when(achievements.findPatent(anyString())).thenReturn(Optional.empty());
    lenient().when(achievements.findProject(anyString())).thenReturn(Optional.empty());
    lenient().when(achievements.findAward(anyString())).thenReturn(Optional.empty());
    lenient().when(contributions.listByAchievement(anyString())).thenReturn(List.of());
    lenient().when(topics.listAssignmentsOf(anyString())).thenReturn(List.of());
  }

  private static Achievement achievement(
      String id, EffectiveStatus status, Visibility visibility, String mergedIntoId) {
    return new Achievement(
        id,
        AchievementType.PAPER,
        "标题 " + id,
        ("标题 " + id).toLowerCase(java.util.Locale.ROOT),
        DataNature.REAL,
        visibility,
        status,
        PublicationYear.of(2024),
        mergedIntoId,
        1L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  @DisplayName("被合并的旧标识跳到幸存实体，并说明这是一次跳转")
  void mergedIdRedirectsToSurvivor() {
    when(achievements.findById("ach_old"))
        .thenReturn(Optional.of(achievement("ach_old", EffectiveStatus.WITHDRAWN, Visibility.PUBLIC, "ach_new")));
    when(achievements.resolveSurvivorId("ach_old")).thenReturn(Optional.of("ach_new"));
    when(achievements.findById("ach_new"))
        .thenReturn(Optional.of(achievement("ach_new", EffectiveStatus.EFFECTIVE, Visibility.PUBLIC, null)));

    Map<String, Object> view = service.achievementDetail("ach_old");

    assertThat(view).containsEntry("id", "ach_new");
    assertThat(view).containsEntry("redirectedFrom", "ach_old");
    assertThat(view.get("redirectNote")).asString().contains("已被合并");
  }

  @Test
  @DisplayName("跳转不能绕过公开资格：幸存实体待治理时，旧标识一样打不开")
  void redirectToPendingEntityIsNotFound() {
    when(achievements.findById("ach_old"))
        .thenReturn(Optional.of(achievement("ach_old", EffectiveStatus.WITHDRAWN, Visibility.PUBLIC, "ach_new")));
    when(achievements.resolveSurvivorId("ach_old")).thenReturn(Optional.of("ach_new"));
    when(achievements.findById("ach_new"))
        .thenReturn(Optional.of(achievement("ach_new", EffectiveStatus.PENDING_GOVERNANCE, Visibility.RESTRICTED, null)));

    assertThatThrownBy(() -> service.achievementDetail("ach_old"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("尚不可见");
    // 未通过资格校验时不得继续读扩展字段：那等于把未公开内容拼进响应再被拒绝。
    verify(achievements, never()).findPaper("ach_new");
  }

  @Test
  @DisplayName("幸存实体受限时同样打不开，不因「是从旧标识来的」而放宽")
  void redirectToRestrictedEntityIsNotFound() {
    when(achievements.findById("ach_old"))
        .thenReturn(Optional.of(achievement("ach_old", EffectiveStatus.WITHDRAWN, Visibility.PUBLIC, "ach_new")));
    when(achievements.resolveSurvivorId("ach_old")).thenReturn(Optional.of("ach_new"));
    when(achievements.findById("ach_new"))
        .thenReturn(Optional.of(achievement("ach_new", EffectiveStatus.EFFECTIVE, Visibility.RESTRICTED, null)));

    assertThatThrownBy(() -> service.achievementDetail("ach_old"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("未合并但不可见的成果：不存在与不可见对外表现一致")
  void hiddenEntityLooksMissing() {
    when(achievements.findById("ach_1"))
        .thenReturn(Optional.of(achievement("ach_1", EffectiveStatus.PENDING_GOVERNANCE, Visibility.RESTRICTED, null)));

    assertThatThrownBy(() -> service.achievementDetail("ach_1"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("尚不可见");
  }

  @Test
  @DisplayName("学者跳转到终点时带上参与关系，旧链接不是空壳页")
  void scholarRedirectCarriesContributions() {
    Scholar merged =
        new Scholar(
            "sch_old", "张三", "张三", DataNature.REAL, Scholar.ClaimedStatus.UNCLAIMED,
            "北京航空航天大学", null, "sch_new", 1L,
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
    Scholar survivor =
        new Scholar(
            "sch_new", "张三", "张三", DataNature.REAL, Scholar.ClaimedStatus.CLAIMED,
            "北京航空航天大学", "研究简介", null, 2L,
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
    when(scholars.findById("sch_old")).thenReturn(Optional.of(merged));
    when(scholars.resolveSurvivorId("sch_old")).thenReturn(Optional.of("sch_new"));
    when(scholars.findById("sch_new")).thenReturn(Optional.of(survivor));

    Map<String, Object> view = service.scholarDetail("sch_old");

    assertThat(view).containsEntry("id", "sch_new").containsKey("contributions");
    assertThat(view).containsEntry("redirectedFrom", "sch_old");
  }
}
