package com.cognitanexus.query.graph.projection.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv.FakeCheckpointStore;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv.FakeSource;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv.FakeStore;
import com.cognitanexus.query.graph.projection.application.GraphEventProjector;
import com.cognitanexus.query.graph.projection.application.GraphProjectionCheckpointStore;
import com.cognitanexus.query.graph.projection.application.GraphProjectionProperties;
import com.cognitanexus.query.graph.projection.application.GraphProjectionRetryPolicy;
import com.cognitanexus.query.graph.projection.application.GraphProjectionSource.AppliedEvent;
import com.cognitanexus.query.graph.projection.application.GraphProjectionSynchronizer;
import com.cognitanexus.query.graph.projection.domain.GraphProjectionCheckpoint;
import com.cognitanexus.query.platform.persistence.JsonCodec;
import com.cognitanexus.query.projection.application.QueryEventTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 增量追赶调度（{@code GraphProjectionWorker}）。
 *
 * <p>被锁住的两件事：
 *
 * <ol>
 *   <li><b>调度确实委托给投影器</b>：调度器本身不写图，只把「已应用事件」交给
 *       {@link GraphEventProjector}；否则「调度开着」会变成一句无法核实的话；
 *   <li><b>回填未完成时不得追赶</b>：增量会把 {@code GraphProjectionMeta.projected_version} 推进到
 *       {@code published_revision}，而回填只灌了一半成果。水位看上去追平、图却缺一大段，
 *       公开读路径会把半张图当完整图用（ADR-005 §5.2）。这条守卫就是「水位不越界」的可执行版本。
 * </ol>
 */
class GraphProjectionWorkerTest {

  private static final String GEN = "gen0001";
  private static final String SCOPE = "query-public";

  private FakeStore store;
  private FakeSource source;
  private FakeCheckpointStore checkpoints;
  private GraphProjectionWorker worker;

  @BeforeEach
  void setUp() {
    store = new FakeStore(SCOPE, 10L, GEN);
    source = new FakeSource(GEN, 20L);
    checkpoints = new FakeCheckpointStore();
    worker = workerWith(checkpoints, enabledProperties());
  }

  private static GraphProjectionProperties enabledProperties() {
    return new GraphProjectionProperties(true, 500, 3, 0L, 100L, true, 5_000L, 100, 1_000, 5, true);
  }

  private GraphProjectionWorker workerWith(
      GraphProjectionCheckpointStore checkpointStore, GraphProjectionProperties properties) {
    GraphProjectionSynchronizer synchronizer =
        new GraphProjectionSynchronizer(
            source, store, GraphProjectionRetryPolicy.noRetry(), millis -> {});
    GraphEventProjector projector =
        new GraphEventProjector(
            source, synchronizer, store, new JsonCodec(new ObjectMapper()), "query");
    return new GraphProjectionWorker(
        projector, store, source, checkpointStore, properties, SCOPE, "query");
  }

  private void seedEvent() {
    source.add(FakeGraphProjectionEnv.work("w-1", 3L, "s-1", "s-2"));
    source.addEvent(
        new AppliedEvent(
            "e-1",
            QueryEventTypes.CATALOG_CHANGE_COMMITTED,
            11L,
            Map.of("changeKind", QueryEventTypes.CHANGE_UPSERT, "achievementId", "w-1")));
  }

  private GraphProjectionCheckpoint checkpoint() {
    return GraphProjectionCheckpoint.started(SCOPE, GEN, 20L, Instant.now());
  }

  @Test
  @DisplayName("没有回填断点：本轮把事件追进图，水位推进到事件修订")
  void catchUpDelegatesToProjector() {
    seedEvent();

    worker.catchUp();

    assertThat(store.works.get("w-1").work().isPublic()).isTrue();
    assertThat(store.meta.projectedVersion()).isEqualTo(11L);
  }

  @Test
  @DisplayName("回填进行中（断点 RUNNING）：跳过本轮追赶，水位不越界推进到 published_revision")
  void runningBackfillBlocksIncrementalCatchUp() {
    seedEvent();
    checkpoints.saved.put(SCOPE, checkpoint());

    worker.catchUp();

    // 水位仍是 10（PG 已发布 20）：没有把「回填只灌了一半」伪装成「已追平」。
    assertThat(store.meta.projectedVersion()).isEqualTo(10L);
    // 也没有任何写图动作：事件没有被消费。
    assertThat(store.works).isEmpty();
    assertThat(store.calls).isEmpty();
  }

  @Test
  @DisplayName("回填失败待续跑（断点 FAILED）同样阻止追赶：缺口不能被后续事件跨过")
  void failedBackfillAlsoBlocksCatchUp() {
    seedEvent();
    checkpoints.saved.put(SCOPE, checkpoint().failed("模拟回填中断", Instant.now()));

    worker.catchUp();

    assertThat(store.meta.projectedVersion()).isEqualTo(10L);
    assertThat(store.calls).isEmpty();
  }

  @Test
  @DisplayName("回填已完成（断点 COMPLETED）：恢复正常追赶")
  void completedBackfillAllowsCatchUp() {
    seedEvent();
    checkpoints.saved.put(SCOPE, checkpoint().completed(Instant.now()));

    worker.catchUp();

    assertThat(store.meta.projectedVersion()).isEqualTo(11L);
  }

  @Test
  @DisplayName("投影开关关闭时不做事：不读 PG、不写图")
  void disabledWorkerDoesNothing() {
    seedEvent();
    GraphProjectionWorker disabled =
        workerWith(
            checkpoints,
            new GraphProjectionProperties(
                false, 500, 3, 0L, 100L, false, 5_000L, 100, 1_000, 5, true));

    disabled.catchUp();

    assertThat(store.meta.projectedVersion()).isEqualTo(10L);
    assertThat(store.calls).isEmpty();
  }

  @Test
  @DisplayName("断点读取失败：就地记录，不抛给调度线程，水位不前进")
  void checkpointReadFailureDoesNotAdvanceWatermark() {
    seedEvent();
    GraphProjectionCheckpointStore broken =
        new GraphProjectionCheckpointStore() {
          @Override
          public Optional<GraphProjectionCheckpoint> find(String scope) {
            throw new IllegalStateException("PG 不可达（测试注入）");
          }

          @Override
          public int save(GraphProjectionCheckpoint checkpoint) {
            throw new IllegalStateException("PG 不可达（测试注入）");
          }
        };
    GraphProjectionWorker failing = workerWith(broken, enabledProperties());

    assertThatCode(failing::catchUp).doesNotThrowAnyException();
    assertThat(store.meta.projectedVersion()).isEqualTo(10L);
    assertThat(store.calls).isEmpty();
  }

  @Test
  @DisplayName("断点状态判定：只有 COMPLETED 才允许增量追赶")
  void backfillInProgressOnlyWhenNotCompleted() {
    assertThat(worker.backfillInProgress()).as("没有断点=没有回填在跑").isFalse();

    checkpoints.saved.put(SCOPE, checkpoint().completed(Instant.now()));
    assertThat(worker.backfillInProgress()).isFalse();

    checkpoints.saved.put(SCOPE, checkpoint());
    assertThat(worker.backfillInProgress()).isTrue();

    checkpoints.saved.put(SCOPE, checkpoint().failed("中断", Instant.now()));
    assertThat(worker.backfillInProgress()).as("FAILED 仍保留游标等待续跑").isTrue();
  }
}
