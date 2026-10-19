package com.cognitanexus.query.projection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.platform.persistence.JsonCodec;
import com.cognitanexus.query.projection.application.ProjectionRepositories.ReadModelStore;
import com.cognitanexus.query.projection.domain.InboxEventRecord;
import com.cognitanexus.query.projection.domain.ProjectedAchievement;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 事件写入投影时的派生集合处理。
 *
 * <p>守的是一条只在切换代时才暴露的一致性：增量路径与重建路径必须产出同一份投影。重建只导入
 * 公开快照，因此撤销发布之后再按事件带来的参与关系表写一遍，投影里就会留下已下架成果的作者行；
 * 全量重建会把这些行清掉，于是「重建结果」与「增量结果」从此不相等。
 */
class ProjectionApplierTest {

  private ReadModelStore store;
  private ProjectionApplier applier;

  @BeforeEach
  void setUp() {
    store = mock(ReadModelStore.class);
    ProjectionProperties properties =
        new ProjectionProperties(
            "query", 500, 5, 200, true, 2000L, 20000, 60000, 800L, 10000, 20, 100);
    applier = new ProjectionApplier(store, new JsonCodec(new ObjectMapper()), properties);
  }

  private static InboxEventRecord event(
      String status, String visibility, List<Map<String, Object>> contributors) {
    return new InboxEventRecord(
        "inb_1",
        "query",
        "evt_1",
        QueryEventTypes.CATALOG_CHANGE_COMMITTED,
        1,
        "ACHIEVEMENT",
        "ach_1",
        3L,
        42L,
        Instant.parse("2026-06-01T00:00:00Z"),
        "trace",
        Map.of(
            "changeKind",
            QueryEventTypes.CHANGE_UPSERT,
            "achievementId",
            "ach_1",
            "title",
            "某成果",
            "type",
            "PAPER",
            "dataNature",
            "REAL",
            "visibility",
            visibility,
            "effectiveStatus",
            status,
            "publicationYear",
            2024,
            "contributions",
            contributors,
            "topics",
            List.of("top_1")),
        0);
  }

  private static List<Map<String, Object>> twoAuthors() {
    return List.of(
        Map.of("scholarId", "sch_1", "scholarName", "甲", "relationState", "AUTO", "authorOrder", 1),
        Map.of(
            "scholarId", "sch_2", "scholarName", "乙", "relationState", "AUTO", "authorOrder", 2));
  }

  @Test
  @DisplayName("公开且生效的成果：派生集合照写，合作边重建")
  void visibleAchievementKeepsDerivedSets() {
    when(store.findAchievement("gen0007", "ach_1"))
        .thenReturn(
            Optional.of(
                new ProjectedAchievement(
                    "ach_1",
                    "PAPER",
                    "某成果",
                    "某成果",
                    "REAL",
                    "PUBLIC",
                    "EFFECTIVE",
                    "NONE",
                    2024,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    3L,
                    42L)));
    when(store.listContributions("gen0007", "ach_1")).thenReturn(List.of());

    ProjectionApplier.Outcome outcome =
        applier.apply("gen0007", event("EFFECTIVE", "PUBLIC", twoAuthors()), Optional.empty());

    assertThat(outcome.shouldIsolate()).isFalse();
    verify(store).replaceContributions(eq("gen0007"), eq("ach_1"), any());
    verify(store).replaceTopics(eq("gen0007"), eq("ach_1"), any());
  }

  @Test
  @DisplayName("已下架的成果：参与关系与领域清空，不把作者行留在投影里")
  void withdrawnAchievementDropsDerivedSets() {
    when(store.findAchievement("gen0007", "ach_1"))
        .thenReturn(
            Optional.of(
                new ProjectedAchievement(
                    "ach_1",
                    "PAPER",
                    "某成果",
                    "某成果",
                    "REAL",
                    "PUBLIC",
                    "WITHDRAWN",
                    "NONE",
                    2024,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    4L,
                    43L)));

    applier.apply("gen0007", event("WITHDRAWN", "PUBLIC", twoAuthors()), Optional.empty());

    verify(store).replaceContributions("gen0007", "ach_1", List.of());
    verify(store).replaceTopics("gen0007", "ach_1", List.of());
    // 下架后边必须消失：边是「成果资格 × 关系」的函数，成果不再合格就不该留边。
    verify(store).deleteEdges("gen0007", "ach_1");
    verify(store, never()).replaceEdges(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("受限的成果同样清空派生集合：可见性收紧后作者与领域不再属于公开数据")
  void restrictedAchievementDropsDerivedSets() {
    when(store.findAchievement("gen0007", "ach_1"))
        .thenReturn(
            Optional.of(
                new ProjectedAchievement(
                    "ach_1",
                    "PAPER",
                    "某成果",
                    "某成果",
                    "REAL",
                    "RESTRICTED",
                    "EFFECTIVE",
                    "NONE",
                    2024,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    5L,
                    44L)));

    applier.apply("gen0007", event("EFFECTIVE", "RESTRICTED", twoAuthors()), Optional.empty());

    verify(store).replaceContributions("gen0007", "ach_1", List.of());
    verify(store).replaceTopics("gen0007", "ach_1", List.of());
  }

  @Test
  @DisplayName("模拟数据在显式演示作用域下仍保留作者与领域，不因不是 REAL 就被清空")
  void simulatedButVisibleKeepsDerivedSets() {
    when(store.findAchievement("gen0007", "ach_1"))
        .thenReturn(
            Optional.of(
                new ProjectedAchievement(
                    "ach_1",
                    "PAPER",
                    "某成果",
                    "某成果",
                    "SIMULATED",
                    "PUBLIC",
                    "EFFECTIVE",
                    "NONE",
                    2024,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    6L,
                    45L)));
    when(store.listContributions("gen0007", "ach_1")).thenReturn(List.of());

    applier.apply("gen0007", event("EFFECTIVE", "PUBLIC", twoAuthors()), Optional.empty());

    verify(store).replaceContributions(eq("gen0007"), eq("ach_1"), any());
    verify(store).replaceTopics(eq("gen0007"), eq("ach_1"), any());
  }

  @Test
  @DisplayName("作者数超过建边上限时隔离事件，不静默截断")
  void tooManyAuthorsIsIsolated() {
    List<Map<String, Object>> many =
        java.util.stream.IntStream.rangeClosed(1, 201)
            .mapToObj(i -> Map.<String, Object>of("scholarId", "sch_" + i, "scholarName", "作者" + i))
            .toList();

    ProjectionApplier.Outcome outcome =
        applier.apply("gen0007", event("EFFECTIVE", "PUBLIC", many), Optional.empty());

    assertThat(outcome.shouldIsolate()).isTrue();
    assertThat(outcome.note()).contains("建边上限");
    verify(store, never()).replaceContributions(anyString(), anyString(), any());
  }

  // ---------------------------------------------------------------------------
  // 来源许可事件的两个方向（ADR-001 §4 批次事件契约，2026-10-18 补）
  // ---------------------------------------------------------------------------

  private static InboxEventRecord sourceEvent(String type, Map<String, Object> payload) {
    return new InboxEventRecord(
        "inb_2",
        "query",
        "evt_2",
        type,
        1,
        "Source",
        "src_1",
        0L,
        43L,
        Instant.parse("2026-06-01T00:00:00Z"),
        "trace",
        payload,
        0);
  }

  @Test
  @DisplayName("撤权受理事件（无成果列表、带 sourceId）不再隔离，按已应用放行")
  void sourceLevelTightenEventIsAppliedNoOp() {
    ProjectionApplier.Outcome outcome =
        applier.apply(
            "gen0007",
            sourceEvent(
                QueryEventTypes.PUBLIC_VISIBILITY_TIGHTENED,
                Map.of(
                    "sourceId", "src_1", "change", "DISPLAY_LICENSE_REVOKED", "targetCount", 55)),
            Optional.empty());

    assertThat(outcome.shouldIsolate()).isFalse();
    // 没有成果列表可撤，readModel 不应被触碰
    verify(store, never())
        .restrictAchievement(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  @DisplayName("无成果列表且无 sourceId 的收紧事件仍隔离（保留对未知形状的严格性）")
  void tightenEventWithoutTargetsStaysIsolated() {
    ProjectionApplier.Outcome outcome =
        applier.apply(
            "gen0007",
            sourceEvent(QueryEventTypes.PUBLIC_VISIBILITY_TIGHTENED, Map.of("reason", "x")),
            Optional.empty());

    assertThat(outcome.shouldIsolate()).isTrue();
  }

  @Test
  @DisplayName("恢复事件：可见性翻回 PUBLIC，合作边从投影内参与关系重建")
  void restoreEventRebuildsEdgesFromRetainedContributions() {
    when(store.restoreAchievement("gen0007", "ach_1", 43L)).thenReturn(1);
    when(store.listContributions("gen0007", "ach_1")).thenReturn(qualifiedContributions());

    ProjectionApplier.Outcome outcome =
        applier.apply(
            "gen0007",
            sourceEvent(
                QueryEventTypes.PUBLIC_VISIBILITY_RESTORED,
                Map.of("sourceId", "src_1", "restoredAchievementIds", List.of("ach_1"))),
            Optional.empty());

    assertThat(outcome.shouldIsolate()).isFalse();
    verify(store).restoreAchievement("gen0007", "ach_1", 43L);
    // 一对合格作者 → 一条边；qualifiedForGraph=false 的作者不得混进来
    verify(store)
        .replaceEdges(
            eq("gen0007"),
            eq("ach_1"),
            eq(
                List.of(
                    new com.cognitanexus.query.projection.domain.ScholarPair("sch_1", "sch_2"))));
  }

  private static List<com.cognitanexus.query.projection.domain.ProjectedContribution>
      qualifiedContributions() {
    return List.of(
        new com.cognitanexus.query.projection.domain.ProjectedContribution(
            "ach_1", "sch_1", "甲", "AUTHOR", 1, "AUTO", List.of()),
        new com.cognitanexus.query.projection.domain.ProjectedContribution(
            "ach_1", "sch_2", "乙", "AUTHOR", 2, "SELF_CONFIRMED", List.of()),
        new com.cognitanexus.query.projection.domain.ProjectedContribution(
            "ach_1", "sch_3", "丙", "AUTHOR", 3, "EXCLUDED", List.of()));
  }
}
