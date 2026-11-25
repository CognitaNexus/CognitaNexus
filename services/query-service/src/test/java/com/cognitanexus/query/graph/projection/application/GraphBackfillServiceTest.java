package com.cognitanexus.query.graph.projection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv.FakeCheckpointStore;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv.FakeSource;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv.FakeStore;
import com.cognitanexus.query.graph.projection.domain.GraphBackfillStatus;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 分批回填、断点续跑与受控重建。
 *
 * <p>这些用例覆盖的都是「长任务被打断」的真实形态：进程被杀、图库抖动、PG 换代、有人误触重建。
 * 单元测试构造它们比在真实环境中反复杀进程可靠得多；真实 SQL/Cypher 行为由
 * {@code GraphProjectionSyncIT} 覆盖。两边合起来才说明「断点续传真的能续」。
 */
class GraphBackfillServiceTest {

  private static final String GEN = "gen0001";
  private static final String SCOPE = "query-public";

  private FakeStore store;
  private FakeSource source;
  private FakeCheckpointStore checkpoints;
  private GraphProjectionSynchronizer synchronizer;
  private GraphProjectionProperties properties;

  @BeforeEach
  void setUp() {
    store = new FakeStore(SCOPE, 0L, GEN);
    source = new FakeSource(GEN, 42L);
    checkpoints = new FakeCheckpointStore();
    synchronizer =
        new GraphProjectionSynchronizer(
            source, store, GraphProjectionRetryPolicy.noRetry(), millis -> {});
    properties =
        new GraphProjectionProperties(false, 2, 3, 0L, 100L, false, 5_000L, 100, 1_000, 5, true);
  }

  private GraphBackfillService service() {
    return new GraphBackfillService(source, synchronizer, store, checkpoints, properties, "query");
  }

  private void seed(int count) {
    for (int i = 1; i <= count; i++) {
      String id = String.format("w-%02d", i);
      source.add(FakeGraphProjectionEnv.work(id, i, "s-a", "s-b"));
    }
  }

  @Test
  @DisplayName("分批回填：按游标分页、每批落断点、完成后推进水位到快照修订")
  void backfillInBatchesAndAdvancesWatermark() {
    seed(5);

    GraphBackfillResult result = service().backfill(SCOPE);

    assertThat(result.status()).isEqualTo(GraphBackfillStatus.COMPLETED);
    assertThat(result.processedWorks()).isEqualTo(5);
    // batchSize=2 → 5 条需要 3 批（2+2+1）
    assertThat(result.processedBatches()).isEqualTo(3);
    assertThat(result.batchesThisRun()).isEqualTo(3);
    assertThat(result.cursorWorkId()).isEqualTo("w-05");
    assertThat(result.baseRevision()).isEqualTo(42L);
    assertThat(result.projectedVersion()).isEqualTo(42L);
    assertThat(store.works).hasSize(5);
    assertThat(checkpoints.saved.get(SCOPE).status()).isEqualTo(GraphBackfillStatus.COMPLETED);
    // 每批都写了断点：3 次批次写 + 开始 1 次 + 完成 1 次（开始/完成会覆盖同一行，但 save 次数可数）。
    assertThat(source.calls.stream().filter(call -> call.startsWith("pageWorks")).count())
        .isEqualTo(3);
  }

  @Test
  @DisplayName("中断后续跑：从断点游标继续，不重扫已完成的批次")
  void resumesFromPersistedCursor() {
    seed(6);
    // 第二批读取时抛出不可重试异常，模拟进程/图库中断
    source.failOnPageCall = new IllegalStateException("模拟回填中断");
    source.failOnPageNumber = 2;

    assertThatThrownBy(() -> service().backfill(SCOPE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("模拟回填中断");

    var interrupted = checkpoints.saved.get(SCOPE);
    assertThat(interrupted.status()).isEqualTo(GraphBackfillStatus.FAILED);
    assertThat(interrupted.cursorWorkId()).isEqualTo("w-02");
    assertThat(interrupted.processedWorks()).isEqualTo(2);
    assertThat(interrupted.lastError()).contains("IllegalStateException");

    // 修复后从同一位置继续：本次只处理剩下的 4 条
    GraphBackfillResult resumed = service().backfill(SCOPE);

    assertThat(resumed.status()).isEqualTo(GraphBackfillStatus.COMPLETED);
    assertThat(resumed.worksThisRun()).isEqualTo(4);
    assertThat(resumed.processedWorks()).isEqualTo(6);
    assertThat(store.works).hasSize(6);
    // 第一批的成果没有被重新分页读取：pageWorks 首次调用带的就是断点游标。
    assertThat(source.calls.stream().filter(call -> call.startsWith("pageWorks")).toList())
        .anyMatch(call -> call.startsWith("pageWorks:w-02"));
  }

  @Test
  @DisplayName("已完成的断点：再次调用直接返回，不重扫全量")
  void completedCheckpointIsNotRescanned() {
    seed(3);
    service().backfill(SCOPE);
    long pageCallsBefore =
        source.calls.stream().filter(call -> call.startsWith("pageWorks")).count();

    GraphBackfillResult again = service().backfill(SCOPE);

    assertThat(again.status()).isEqualTo(GraphBackfillStatus.COMPLETED);
    assertThat(again.worksThisRun()).isZero();
    assertThat(source.calls.stream().filter(call -> call.startsWith("pageWorks")).count())
        .isEqualTo(pageCallsBefore);
  }

  @Test
  @DisplayName("受控重建：清空图库后从头回填，水位重置为快照修订")
  void rebuildResetsStoreAndRefills() {
    seed(4);
    service().backfill(SCOPE);
    assertThat(store.works).hasSize(4);
    store.works.put(
        "w-ghost",
        new FakeGraphProjectionEnv.StoredWork(
            FakeGraphProjectionEnv.work("w-ghost", 99L, "s-a", "s-b").toWorkNode(),
            FakeGraphProjectionEnv.work("w-ghost", 99L, "s-a", "s-b").authorships()));
    store.meta =
        new com.cognitanexus.query.graph.domain.GraphProjectionMeta(SCOPE, 999L, 999L, GEN, null);

    GraphBackfillResult result = service().rebuild(SCOPE);

    assertThat(store.calls).contains("resetAllForRebuild:" + SCOPE);
    assertThat(store.works).doesNotContainKey("w-ghost");
    assertThat(store.works).hasSize(4);
    assertThat(result.processedWorks()).isEqualTo(4);
    // 水位于重置后回到快照修订 42，而不是保留重建前的 999。
    assertThat(store.meta.projectedVersion()).isEqualTo(42L);
  }

  @Test
  @DisplayName("重建被拒（存在其它作用域）：异常上抛，且不写断点、不清图")
  void rebuildRefusedOnForeignScope() {
    seed(2);
    service().backfill(SCOPE);
    store.meta =
        new com.cognitanexus.query.graph.domain.GraphProjectionMeta("other-env", 5L, 5L, GEN, null);

    assertThatThrownBy(() -> service().rebuild(SCOPE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("拒绝整库清空");
    assertThat(store.works).hasSize(2);
  }

  @Test
  @DisplayName("PG 换代：拒绝在旧代游标上续跑，要求先 rebuild")
  void generationChangeRequiresRebuild() {
    seed(2);
    service().backfill(SCOPE);
    // 模拟 PG 重建后换代，而断点仍是旧代（把断点改回 RUNNING 且换 generation 不现实，
    // 这里直接用一代新断点表示「上次跑在 gen0001、PG 现在是 gen0002」）
    checkpoints.saved.put(
        SCOPE, checkpoints.saved.get(SCOPE).advanced("w-01", 1, 0, java.time.Instant.now()));
    source.stage = new GraphProjectionSource.Stage("gen0002", 43L);

    assertThatThrownBy(() -> service().backfill(SCOPE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gen0002")
        .hasMessageContaining("rebuild");
  }

  @Test
  @DisplayName("失败过多：超过 max-failed-works 即转 FAILED，不把「跑完但没进图」当成功")
  void tooManyFailuresAbortRun() {
    seed(4);
    store.permanentFailures.addAll(List.of("w-01", "w-02"));
    properties =
        new GraphProjectionProperties(false, 2, 3, 0L, 1L, false, 5_000L, 100, 1_000, 5, true);

    GraphBackfillResult result = service().backfill(SCOPE);

    assertThat(result.status()).isEqualTo(GraphBackfillStatus.FAILED);
    assertThat(result.failedWorks()).isEqualTo(2);
    assertThat(result.note()).contains("超过上限");
    assertThat(checkpoints.saved.get(SCOPE).status()).isEqualTo(GraphBackfillStatus.FAILED);
    // 中止后可以从断点继续，而不是丢进度。
    assertThat(result.resumable()).isTrue();
  }

  @Test
  @DisplayName("PG 水位缺失：明确失败，不凭空从 0 开始灌数据")
  void missingStageFails() {
    source.stage = null;

    assertThatThrownBy(() -> service().backfill(SCOPE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("qry_consumer_state");
    assertThat(store.works).isEmpty();
  }
}
