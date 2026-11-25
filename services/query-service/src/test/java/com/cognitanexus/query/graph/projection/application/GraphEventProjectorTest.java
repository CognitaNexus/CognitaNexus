package com.cognitanexus.query.graph.projection.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv.FakeCheckpointStore;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv.FakeSource;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv.FakeStore;
import com.cognitanexus.query.graph.projection.application.GraphProjectionSource.AppliedEvent;
import com.cognitanexus.query.graph.projection.domain.GraphProjectedWork;
import com.cognitanexus.query.graph.projection.domain.GraphProjectionCheckpoint;
import com.cognitanexus.query.platform.persistence.JsonCodec;
import com.cognitanexus.query.projection.application.QueryEventTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Outbox/Inbox 事件的图增量投影。
 *
 * <p>被锁住的语义：
 *
 * <ul>
 *   <li>水位只前进，且只在「水位之前的每条事件都已成功应用」后前进；失败即停，缺口可见；
 *   <li>重复/迟到事件不会再次生效（水位过滤 + 成果版本守卫两层）；
 *   <li>不同事件类型到成果的正确映射：收紧 → 退场；合并 → 幸存者重写、被合并者在 PG 中已无行
 *       时删除图内残留节点与边（P1-2）；
 *   <li>generation 不一致时拒绝写入，避免把新代数据补到旧代图上；
 *   <li>初始化前置条件：断点缺失/未完成/有失败成果/跨代时拒绝消费且不推水位（P1-3）；
 *   <li>任何成果同步或清理失败都不推水位（P1-2）。
 * </ul>
 */
class GraphEventProjectorTest {

  private static final String GEN = "gen0001";
  private static final String SCOPE = "query-public";

  private FakeStore store;
  private FakeSource source;
  private FakeCheckpointStore checkpoints;
  private GraphProjectionSynchronizer synchronizer;
  private GraphEventProjector projector;

  @BeforeEach
  void setUp() {
    store = new FakeStore(SCOPE, 0L, GEN);
    source = new FakeSource(GEN, 0L);
    checkpoints = new FakeCheckpointStore();
    // 增量消费的初始化前置条件：初始回填已真正完成且没有失败成果。
    checkpoints.saved.put(SCOPE, completedCheckpoint(GEN, 0L));
    synchronizer =
        new GraphProjectionSynchronizer(
            source, store, GraphProjectionRetryPolicy.noRetry(), millis -> {});
    projector = newProjector(store, source);
  }

  private GraphEventProjector newProjector(FakeStore targetStore, FakeSource targetSource) {
    return new GraphEventProjector(
        targetSource,
        new GraphProjectionSynchronizer(
            targetSource, targetStore, GraphProjectionRetryPolicy.noRetry(), millis -> {}),
        targetStore,
        checkpoints,
        new JsonCodec(new ObjectMapper()),
        "query");
  }

  /** 一份「回填已成功完成」的断点：COMPLETED、零失败、同一代。 */
  private static GraphProjectionCheckpoint completedCheckpoint(
      String generation, long failedWorks) {
    Instant now = Instant.now();
    GraphProjectionCheckpoint advanced =
        GraphProjectionCheckpoint.started(SCOPE, generation, 0L, now)
            .advanced(
                "w-9", 1, failedWorks, failedWorks > 0 ? "w-9:FAILED_PERMANENT:测试注入" : null, now);
    return advanced.completed(now);
  }

  private static AppliedEvent event(
      String id, String type, long revision, Map<String, Object> payload) {
    return new AppliedEvent(id, type, revision, payload);
  }

  @Test
  @DisplayName("成果变更事件：写入图并把水位推进到该事件修订")
  void catalogChangeAppliesWorkAndAdvancesWatermark() {
    source.add(FakeGraphProjectionEnv.work("w-1", 3L, "s-1", "s-2"));
    source.addEvent(
        event(
            "e-1",
            QueryEventTypes.CATALOG_CHANGE_COMMITTED,
            7L,
            Map.of("changeKind", QueryEventTypes.CHANGE_UPSERT, "achievementId", "w-1")));

    GraphEventProjectionResult result = projector.applyPending(SCOPE, 10);

    assertThat(result.worksApplied()).isEqualTo(1);
    assertThat(result.projectedRevision()).isEqualTo(7L);
    assertThat(store.meta.projectedVersion()).isEqualTo(7L);
    assertThat(store.works.get("w-1").work().isPublic()).isTrue();
  }

  @Test
  @DisplayName("重复/迟到：水位已覆盖的事件不再被读取，图不再变化")
  void eventsBelowWatermarkAreNotRead() {
    source.add(FakeGraphProjectionEnv.work("w-1", 3L, "s-1", "s-2"));
    source.addEvent(
        event(
            "e-1",
            QueryEventTypes.CATALOG_CHANGE_COMMITTED,
            7L,
            Map.of("changeKind", QueryEventTypes.CHANGE_UPSERT, "achievementId", "w-1")));
    projector.applyPending(SCOPE, 10);
    int replaceCallsAfterFirstRun =
        (int) store.calls.stream().filter(call -> call.startsWith("replaceWork")).count();

    GraphEventProjectionResult second = projector.applyPending(SCOPE, 10);

    assertThat(second.eventsRead()).isZero();
    assertThat(second.projectedRevision()).isEqualTo(7L);
    assertThat((int) store.calls.stream().filter(call -> call.startsWith("replaceWork")).count())
        .isEqualTo(replaceCallsAfterFirstRun);
  }

  @Test
  @DisplayName("隐私收紧事件：成果在 PG 中已不合格，投影时必须退场并删除物化边")
  void visibilityTightenedRetiresWork() {
    GraphProjectedWork publicWork = FakeGraphProjectionEnv.work("w-1", 3L, "s-1", "s-2");
    source.add(publicWork);
    store.replaceWork(publicWork.toWorkNode(), publicWork.authorships());
    store.works.put(
        "w-1",
        new FakeGraphProjectionEnv.StoredWork(publicWork.toWorkNode(), publicWork.authorships()));
    // PG 投影收到收紧事件后已把可见性改为 RESTRICTED，版本 +1
    source.add(FakeGraphProjectionEnv.work("w-1", 4L, false, "REAL", List.of(), List.of()));
    source.addEvent(
        event(
            "e-1",
            QueryEventTypes.PUBLIC_VISIBILITY_TIGHTENED,
            9L,
            Map.of("tightenedAchievementIds", List.of("w-1"))));

    GraphEventProjectionResult result = projector.applyPending(SCOPE, 10);

    assertThat(result.worksRetired()).isEqualTo(1);
    assertThat(store.works.get("w-1").work().isPublic()).isFalse();
    assertThat(store.works.get("w-1").authorships()).isEmpty();
    assertThat(result.projectedRevision()).isEqualTo(9L);
  }

  @Test
  @DisplayName("不影响图的事件（门户授权/认领结论）：只推进水位，不写任何成果")
  void nonGraphEventsOnlyAdvanceWatermark() {
    source.addEvent(
        event("e-1", QueryEventTypes.PORTAL_AUTHORITY_GRANTED, 5L, Map.of("scholarId", "s-1")));
    source.addEvent(event("e-2", "UnknownFutureEvent", 6L, Map.of("x", "y")));

    GraphEventProjectionResult result = projector.applyPending(SCOPE, 10);

    assertThat(result.eventsRead()).isEqualTo(2);
    assertThat(result.eventsIgnored()).isEqualTo(2);
    // 唯一允许的副作用是推水位：不得写任何成果/学者节点。
    assertThat(store.works).isEmpty();
    assertThat(store.calls).allMatch(call -> call.startsWith("advanceMeta"));
    assertThat(result.projectedRevision()).isEqualTo(6L);
  }

  @Test
  @DisplayName("写入失败：停止在失败事件之前，水位不越过它（缺口保持可见）")
  void failureStopsWatermark() {
    source.add(FakeGraphProjectionEnv.work("w-1", 3L, "s-1", "s-2"));
    source.add(FakeGraphProjectionEnv.work("w-2", 3L, "s-1", "s-2"));
    store.permanentFailures.add("w-1");
    source.addEvent(
        event(
            "e-1",
            QueryEventTypes.CATALOG_CHANGE_COMMITTED,
            4L,
            Map.of("changeKind", QueryEventTypes.CHANGE_UPSERT, "achievementId", "w-1")));
    source.addEvent(
        event(
            "e-2",
            QueryEventTypes.CATALOG_CHANGE_COMMITTED,
            5L,
            Map.of("changeKind", QueryEventTypes.CHANGE_UPSERT, "achievementId", "w-2")));

    GraphEventProjectionResult result = projector.applyPending(SCOPE, 10);

    assertThat(result.stopped()).isTrue();
    assertThat(result.stoppedEventId()).isEqualTo("e-1");
    assertThat(result.failedWorks()).isEqualTo(1);
    // 水位停在失败事件之前：0 而不是 4/5，下一次仍会从同一条事件重来。
    assertThat(result.projectedRevision()).isZero();
    assertThat(store.meta.projectedVersion()).isZero();
    assertThat(store.works).doesNotContainKey("w-2");
  }

  @Test
  @DisplayName("generation 不一致：拒绝增量写入（避免把新代数据补到旧代图上）")
  void generationMismatchRefusesToWrite() {
    store = new FakeStore(SCOPE, 3L, "gen0001");
    source = new FakeSource("gen0002", 3L);
    checkpoints.saved.put(SCOPE, completedCheckpoint("gen0002", 0L));
    projector = newProjector(store, source);
    source.add(FakeGraphProjectionEnv.work("w-1", 3L, "s-1", "s-2"));
    source.addEvent(
        event(
            "e-1",
            QueryEventTypes.CATALOG_CHANGE_COMMITTED,
            4L,
            Map.of("changeKind", QueryEventTypes.CHANGE_UPSERT, "achievementId", "w-1")));

    GraphEventProjectionResult result = projector.applyPending(SCOPE, 10);

    assertThat(result.eventsRead()).isZero();
    assertThat(result.projectedRevision()).isEqualTo(3L);
    assertThat(result.notes()).anyMatch(note -> note.contains("不一致"));
    assertThat(store.works).isEmpty();
  }

  @Test
  @DisplayName("P1-2 成果合并：PG 中已删除的被合并成果必须从图里删掉节点与合作边")
  void achievementMergedDeletesGraphRemnantOfMergedWork() {
    GraphProjectedWork survivor = FakeGraphProjectionEnv.work("w-survivor", 3L, "s-1", "s-2");
    GraphProjectedWork merged = FakeGraphProjectionEnv.work("w-merged", 3L, "s-1", "s-3");
    for (GraphProjectedWork work : List.of(survivor, merged)) {
      source.add(work);
      store.replaceWork(work.toWorkNode(), work.authorships());
    }
    // ProjectionApplier 处理 AchievementMerged 时会从 PG 读投影里连同参与关系一起删掉被合并者，
    // 因此图侧的 syncWork 会拿到 empty——这不是「跳过」，而是「必须清理」。
    source.works.remove("w-merged");
    source.addEvent(
        event(
            "e-merge",
            QueryEventTypes.ACHIEVEMENT_MERGED,
            12L,
            Map.of("survivorId", "w-survivor", "survivorTitle", "幸存", "mergedId", "w-merged")));

    GraphEventProjectionResult result = projector.applyPending(SCOPE, 10);

    assertThat(result.worksApplied()).isEqualTo(1);
    assertThat(result.worksDeleted()).isEqualTo(1);
    assertThat(store.works).doesNotContainKey("w-merged");
    assertThat(store.calls).contains("deleteWork:w-merged");
    // 图上不再有任何引用被合并成果的合作边（stub 存储以节点删除体现，这里显式断言节点已消失）
    assertThat(store.works.keySet()).containsExactly("w-survivor");
    assertThat(result.projectedRevision()).isEqualTo(12L);
    assertThat(store.meta.projectedVersion()).isEqualTo(12L);
  }

  @Test
  @DisplayName("P1-2 清理失败：任何成果同步/清理失败都不得推进图水位（哪怕之前的事件已成功）")
  void cleanupFailureBlocksWatermarkEvenAfterSuccessfulEvents() {
    source.add(FakeGraphProjectionEnv.work("w-1", 3L, "s-1", "s-2"));
    source.addEvent(
        event(
            "e-1",
            QueryEventTypes.CATALOG_CHANGE_COMMITTED,
            4L,
            Map.of("changeKind", QueryEventTypes.CHANGE_UPSERT, "achievementId", "w-1")));
    // 第二条事件的被合并者已不在 PG 中 → 走 deleteWork；注入图库故障让清理失败。
    store.transientFailures.put("w-gone", 1);
    source.addEvent(
        event(
            "e-2",
            QueryEventTypes.ACHIEVEMENT_MERGED,
            6L,
            Map.of("survivorId", "w-1", "mergedId", "w-gone")));

    GraphEventProjectionResult result = projector.applyPending(SCOPE, 10);

    assertThat(result.failedWorks()).isEqualTo(1);
    assertThat(result.stopped()).isTrue();
    // 关键：即使 e-1 已成功应用（图里确实写了 w-1），水位也停在 0，不推到 4 或 6。
    assertThat(store.works).containsKey("w-1");
    assertThat(result.projectedRevision()).isZero();
    assertThat(store.meta.projectedVersion()).isZero();
    assertThat(String.join("\n", result.notes())).contains("不推进");
  }

  @Test
  @DisplayName("P1-3 没有回填断点：拒绝增量消费且不推进水位")
  void missingCheckpointRefusesIncrementalConsumption() {
    checkpoints.saved.clear();
    source.add(FakeGraphProjectionEnv.work("w-1", 3L, "s-1", "s-2"));
    source.addEvent(
        event(
            "e-1",
            QueryEventTypes.CATALOG_CHANGE_COMMITTED,
            7L,
            Map.of("changeKind", QueryEventTypes.CHANGE_UPSERT, "achievementId", "w-1")));

    GraphEventProjectionResult result = projector.applyPending(SCOPE, 10);

    assertThat(result.eventsRead()).isZero();
    assertThat(result.projectedRevision()).isZero();
    assertThat(store.meta.projectedVersion()).isZero();
    assertThat(store.works).isEmpty();
    assertThat(String.join("\n", result.notes())).contains("断点");
  }

  @Test
  @DisplayName("P1-3 断点未完成/有失败成果/跨代：都不允许消费，更不允许推水位")
  void unfinishedOrFailedOrCrossGenerationCheckpointRefusesConsumption() {
    source.add(FakeGraphProjectionEnv.work("w-1", 3L, "s-1", "s-2"));
    source.addEvent(
        event(
            "e-1",
            QueryEventTypes.CATALOG_CHANGE_COMMITTED,
            7L,
            Map.of("changeKind", QueryEventTypes.CHANGE_UPSERT, "achievementId", "w-1")));

    for (GraphProjectionCheckpoint blocked :
        List.of(
            completedCheckpoint(GEN, 0L).failed("回填中断", Instant.now()),
            completedCheckpoint(GEN, 2L),
            completedCheckpoint("gen0000", 0L))) {
      checkpoints.saved.put(SCOPE, blocked);
      store.works.clear();
      store.calls.clear();
      store.meta =
          new com.cognitanexus.query.graph.domain.GraphProjectionMeta(SCOPE, 0L, 0L, GEN, null);

      GraphEventProjectionResult result = projector.applyPending(SCOPE, 10);

      assertThat(result.eventsRead()).as("断点 %s 必须拒绝消费", blocked.status()).isZero();
      assertThat(store.meta.projectedVersion()).isZero();
      assertThat(store.works).isEmpty();
    }
  }

  @Test
  @DisplayName("学者合并：两边名下的成果都要重写（否则图上留着旧学者的参与边）")
  void scholarMergedResyncsBothSides() {
    source.add(FakeGraphProjectionEnv.work("w-1", 3L, "s-merged", "s-x"));
    source.add(FakeGraphProjectionEnv.work("w-2", 3L, "s-survivor", "s-y"));
    source.addEvent(
        event(
            "e-1",
            QueryEventTypes.SCHOLAR_MERGED,
            8L,
            Map.of("survivorId", "s-survivor", "mergedId", "s-merged")));

    GraphEventProjectionResult result = projector.applyPending(SCOPE, 10);

    assertThat(result.worksApplied()).isEqualTo(2);
    assertThat(store.works).containsKeys("w-1", "w-2");
    assertThat(result.projectedRevision()).isEqualTo(8L);
  }

  @Test
  @DisplayName("图里版本更新时，迟到事件被成果版本守卫拒绝但水位照常前进")
  void staleWorkWriteStillAdvancesWatermark() {
    GraphProjectedWork newer = FakeGraphProjectionEnv.work("w-1", 9L, "s-1", "s-2", "s-3");
    store.works.put(
        "w-1", new FakeGraphProjectionEnv.StoredWork(newer.toWorkNode(), newer.authorships()));
    source.add(FakeGraphProjectionEnv.work("w-1", 7L, "s-1", "s-2"));
    source.addEvent(
        event(
            "e-1",
            QueryEventTypes.CATALOG_CHANGE_COMMITTED,
            11L,
            Map.of("changeKind", QueryEventTypes.CHANGE_UPSERT, "achievementId", "w-1")));

    GraphEventProjectionResult result = projector.applyPending(SCOPE, 10);

    assertThat(result.worksStale()).isEqualTo(1);
    assertThat(store.works.get("w-1").authorships()).hasSize(3);
    assertThat(result.projectedRevision()).isEqualTo(11L);
  }
}
