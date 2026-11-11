package com.cognitanexus.core.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.Visibility;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 成果实体的现实视图资格判定。
 *
 * <p>依据：FR10（治理与发布）、BR-IMP-01（只公开许可允许且当前生效的实体）、
 * BR-IMP-07/10（真实/模拟/混合互斥，模拟数据不得冒充真实）、架构 V0.2 §5.4。
 *
 * <p>{@code eligibleForRealityView()} 是默认统计、排名与合作网络的共同闸门。它一旦放宽，
 * 后果不是「多显示几条」而是「把不该计入的内容算进了对外结论」，所以四个条件必须逐一验证，
 * 且任何一项不满足都要挡住。
 */
class AchievementEligibilityTest {

  private static Achievement achievement(
      DataNature nature, Visibility visibility, EffectiveStatus status, String mergedIntoId) {
    return new Achievement(
        "ach_1",
        AchievementType.PAPER,
        "Graph Neural Networks",
        "graph neural networks",
        nature,
        visibility,
        status,
        PublicationYear.of(2024),
        mergedIntoId,
        1L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  /** 真实、公开、生效、未合并 —— 唯一有资格进入现实视图的组合。 */
  private static Achievement eligible() {
    return achievement(DataNature.REAL, Visibility.PUBLIC, EffectiveStatus.EFFECTIVE, null);
  }

  @Test
  @DisplayName("真实 + 公开 + 生效 + 未合并 才有资格进入默认现实视图")
  void eligibleCombination() {
    assertThat(eligible().eligibleForRealityView()).isTrue();
    assertThat(eligible().merged()).isFalse();
  }

  @Test
  @DisplayName("模拟数据被挡住：模拟成果不得进入默认统计与排名")
  void simulatedBlocked() {
    assertThat(
            achievement(DataNature.SIMULATED, Visibility.PUBLIC, EffectiveStatus.EFFECTIVE, null)
                .eligibleForRealityView())
        .isFalse();
    assertThat(
            achievement(DataNature.MIXED, Visibility.PUBLIC, EffectiveStatus.EFFECTIVE, null)
                .eligibleForRealityView())
        .isFalse();
  }

  @Test
  @DisplayName("受控与隐藏的可见性被挡住：存在不等于可公开")
  void nonPublicVisibilityBlocked() {
    assertThat(
            achievement(DataNature.REAL, Visibility.RESTRICTED, EffectiveStatus.EFFECTIVE, null)
                .eligibleForRealityView())
        .isFalse();
    assertThat(
            achievement(DataNature.REAL, Visibility.HIDDEN, EffectiveStatus.EFFECTIVE, null)
                .eligibleForRealityView())
        .isFalse();
  }

  @Test
  @DisplayName("未通过治理（PENDING_GOVERNANCE）与已撤回（WITHDRAWN）都被挡住")
  void nonEffectiveBlocked() {
    assertThat(
            achievement(
                    DataNature.REAL, Visibility.PUBLIC, EffectiveStatus.PENDING_GOVERNANCE, null)
                .eligibleForRealityView())
        .isFalse();
    assertThat(
            achievement(DataNature.REAL, Visibility.PUBLIC, EffectiveStatus.WITHDRAWN, null)
                .eligibleForRealityView())
        .isFalse();
  }

  @Test
  @DisplayName("被合并的实体不再是有效实体：旧链接必须重定向，不得同时算两条")
  void mergedBlocked() {
    Achievement merged =
        achievement(DataNature.REAL, Visibility.PUBLIC, EffectiveStatus.EFFECTIVE, "ach_2");
    assertThat(merged.merged()).isTrue();
    assertThat(merged.eligibleForRealityView()).isFalse();
  }

  @Test
  @DisplayName("四个条件中任一不满足即整体不合格（逐项交叉验证）")
  void everyConditionIsNecessary() {
    assertThat(eligible().eligibleForRealityView()).isTrue();
    assertThat(
            achievement(DataNature.SIMULATED, Visibility.PUBLIC, EffectiveStatus.EFFECTIVE, null)
                .eligibleForRealityView())
        .isFalse();
    assertThat(
            achievement(DataNature.REAL, Visibility.RESTRICTED, EffectiveStatus.EFFECTIVE, null)
                .eligibleForRealityView())
        .isFalse();
    assertThat(
            achievement(DataNature.REAL, Visibility.PUBLIC, EffectiveStatus.WITHDRAWN, null)
                .eligibleForRealityView())
        .isFalse();
    assertThat(
            achievement(DataNature.REAL, Visibility.PUBLIC, EffectiveStatus.EFFECTIVE, "ach_9")
                .eligibleForRealityView())
        .isFalse();
  }

  @Test
  @DisplayName("withVersion 只改版本号，其余字段逐项保持不变")
  void withVersionKeepsOtherFields() {
    Achievement original = eligible();
    Achievement bumped = original.withVersion(7L);
    assertThat(bumped.version()).isEqualTo(7L);
    assertThat(bumped.id()).isEqualTo(original.id());
    assertThat(bumped.title()).isEqualTo(original.title());
    assertThat(bumped.titleNorm()).isEqualTo(original.titleNorm());
    assertThat(bumped.dataNature()).isEqualTo(original.dataNature());
    assertThat(bumped.visibility()).isEqualTo(original.visibility());
    assertThat(bumped.effectiveStatus()).isEqualTo(original.effectiveStatus());
    assertThat(bumped.publicationYear()).isEqualTo(original.publicationYear());
    assertThat(bumped.mergedIntoId()).isEqualTo(original.mergedIntoId());
    assertThat(bumped.eligibleForRealityView()).isTrue();
  }

  @Test
  @DisplayName("年份未知的成果仍可合格：年份缺失不构成公开资格问题，但统计里须单列")
  void unknownYearStillEligible() {
    Achievement noYear =
        new Achievement(
            "ach_2",
            AchievementType.PROJECT,
            "某国家级项目",
            "某国家级项目",
            DataNature.REAL,
            Visibility.PUBLIC,
            EffectiveStatus.EFFECTIVE,
            PublicationYear.unknown("来源未提供年份"),
            null,
            1L,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(noYear.eligibleForRealityView()).isTrue();
    assertThat(noYear.publicationYear().known()).isFalse();
  }
}
