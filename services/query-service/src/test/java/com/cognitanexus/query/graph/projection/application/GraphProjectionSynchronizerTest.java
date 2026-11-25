package com.cognitanexus.query.graph.projection.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv.FakeSource;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv.FakeStore;
import com.cognitanexus.query.graph.projection.application.GraphSyncOutcome.Action;
import com.cognitanexus.query.graph.projection.domain.GraphProjectedWork;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 单条成果写入图的行为（幂等、版本守卫、退场、错误分类）。
 *
 * <p>这些用例锁住的都是「看起来成功了但结果不对」的风险：
 *
 * <ul>
 *   <li>重复投递不得累计出第二条边（不能靠「强度加一」）；
 *   <li>迟到事件不得把新值改回旧值（版本守卫）；
 *   <li>成果下线必须清掉物化合作边（仅版本守卫不会删边）；
 *   <li>数据错误不得重试，基础设施错误必须有限重试。
 * </ul>
 */
class GraphProjectionSynchronizerTest {

  private static final String GEN = "gen0001";

  private FakeStore store;
  private FakeSource source;
  private List<Long> sleeps;

  @BeforeEach
  void setUp() {
    store = new FakeStore("query-public", 0L, GEN);
    source = new FakeSource(GEN, 0L);
    sleeps = new ArrayList<>();
  }

  private GraphProjectionSynchronizer synchronizer(GraphProjectionRetryPolicy policy) {
    return new GraphProjectionSynchronizer(source, store, policy, sleeps::add);
  }

  @Test
  @DisplayName("合格成果：先写学者，再按成果整体替换，作者数与关系状态落库")
  void appliesEligibleWork() {
    source.add(FakeGraphProjectionEnv.work("w-1", 5L, "s-1", "s-2"));
    GraphProjectionSynchronizer synchronizer = synchronizer(GraphProjectionRetryPolicy.noRetry());

    Optional<GraphSyncOutcome> outcome = synchronizer.syncWork(GEN, "w-1");

    assertThat(outcome).isPresent();
    assertThat(outcome.get().action()).isEqualTo(Action.APPLIED);
    assertThat(outcome.get().authorships()).isEqualTo(2);
    assertThat(store.works.get("w-1").work().isPublic()).isTrue();
    assertThat(store.works.get("w-1").authorships()).hasSize(2);
    assertThat(store.scholars).extracting("scholarName").contains("name-s-1", "name-s-2");
    // 学者必须先于成果写入：否则 CREATE_AUTHORED 会先建出无姓名节点。
    assertThat(store.calls.get(0)).startsWith("upsertScholars");
    assertThat(store.calls.get(1)).startsWith("replaceWork");
  }

  @Test
  @DisplayName("重复投递：同版本重放结果与一次相同（不更旧即接受，先删后建不累计）")
  void duplicateDeliveryIsIdempotent() {
    source.add(FakeGraphProjectionEnv.work("w-1", 5L, "s-1", "s-2"));
    GraphProjectionSynchronizer synchronizer = synchronizer(GraphProjectionRetryPolicy.noRetry());

    assertThat(synchronizer.syncWork(GEN, "w-1").orElseThrow().action()).isEqualTo(Action.APPLIED);
    assertThat(synchronizer.syncWork(GEN, "w-1").orElseThrow().action()).isEqualTo(Action.APPLIED);
    // 幂等指的是「结果相同」：再写一次不会多出作者/边，也不会把强度累计成 2。
    assertThat(store.works.get("w-1").authorships()).hasSize(2);
    assertThat(store.works.get("w-1").work().sourceVersion()).isEqualTo(5L);
  }

  @Test
  @DisplayName("迟到事件：图里已有更新版本时不覆盖，sourceVersion 保持新值")
  void lateEventDoesNotOverwriteNewerVersion() {
    // 图里已经是 v9（例如回填读到了更新的 PG 行）
    store.works.put(
        "w-1",
        new FakeGraphProjectionEnv.StoredWork(
            FakeGraphProjectionEnv.work("w-1", 9L, "s-1", "s-2").toWorkNode(),
            FakeGraphProjectionEnv.work("w-1", 9L, "s-1", "s-2").authorships()));
    // 事件携带的 PG 行是旧的 v7
    source.add(FakeGraphProjectionEnv.work("w-1", 7L, "s-1", "s-2", "s-3"));
    GraphProjectionSynchronizer synchronizer = synchronizer(GraphProjectionRetryPolicy.noRetry());

    assertThat(synchronizer.syncWork(GEN, "w-1").orElseThrow().action())
        .isEqualTo(Action.STALE_IGNORED);
    assertThat(store.works.get("w-1").work().sourceVersion()).isEqualTo(9L);
    assertThat(store.works.get("w-1").authorships()).hasSize(2);
  }

  @Test
  @DisplayName("不再合格的成果：留下墓碑并清空作者集合（等价于删除物化合作边）")
  void ineligibleWorkIsRetired() {
    source.add(FakeGraphProjectionEnv.work("w-1", 5L, "s-1", "s-2"));
    store.works.put(
        "w-1",
        new FakeGraphProjectionEnv.StoredWork(
            FakeGraphProjectionEnv.work("w-1", 5L, "s-1", "s-2").toWorkNode(),
            FakeGraphProjectionEnv.work("w-1", 5L, "s-1", "s-2").authorships()));
    // PG 侧已收紧：同一成果现在不合格、版本 +1
    source.add(FakeGraphProjectionEnv.work("w-1", 6L, false, "REAL", List.of(), List.of()));
    GraphProjectionSynchronizer synchronizer = synchronizer(GraphProjectionRetryPolicy.noRetry());

    GraphSyncOutcome outcome = synchronizer.syncWork(GEN, "w-1").orElseThrow();

    assertThat(outcome.action()).isEqualTo(Action.RETIRED);
    assertThat(store.works.get("w-1").work().isPublic()).isFalse();
    assertThat(store.works.get("w-1").authorships()).isEmpty();
    // 退场不能顺手删掉墓碑节点：它记录了「曾经投影过、现在不公开」。
    assertThat(store.works).containsKey("w-1");
  }

  @Test
  @DisplayName("PG 中已不存在该成果：不回写、不猜版本，交由对账退场")
  void missingInSourceIsSkipped() {
    GraphProjectionSynchronizer synchronizer = synchronizer(GraphProjectionRetryPolicy.noRetry());

    assertThat(synchronizer.syncWork(GEN, "w-unknown")).isEmpty();
    assertThat(store.calls).isEmpty();
  }

  @Test
  @DisplayName("不可重试错误：只尝试一次，不消耗重试预算")
  void permanentErrorIsNotRetried() {
    source.add(FakeGraphProjectionEnv.work("w-1", 5L, "s-1", "s-2"));
    store.permanentFailures.add("w-1");
    GraphProjectionSynchronizer synchronizer = synchronizer(new GraphProjectionRetryPolicy(3, 10L));

    GraphSyncOutcome outcome = synchronizer.syncWork(GEN, "w-1").orElseThrow();

    assertThat(outcome.action()).isEqualTo(Action.FAILED_PERMANENT);
    assertThat(outcome.attempts()).isEqualTo(1);
    assertThat(sleeps).isEmpty();
    assertThat(store.works).doesNotContainKey("w-1");
  }

  @Test
  @DisplayName("可重试错误：按策略重试后成功，attempts 如实记录")
  void transientErrorIsRetriedThenApplied() {
    source.add(FakeGraphProjectionEnv.work("w-1", 5L, "s-1", "s-2"));
    store.transientFailures.put("w-1", 2);
    GraphProjectionSynchronizer synchronizer = synchronizer(new GraphProjectionRetryPolicy(3, 10L));

    GraphSyncOutcome outcome = synchronizer.syncWork(GEN, "w-1").orElseThrow();

    assertThat(outcome.action()).isEqualTo(Action.APPLIED);
    assertThat(outcome.attempts()).isEqualTo(3);
    // 线性退避：第一次等 10ms、第二次等 20ms。
    assertThat(sleeps).containsExactly(10L, 20L);
  }

  @Test
  @DisplayName("可重试错误耗尽：返回 FAILED_RETRYABLE，不假装成功")
  void transientErrorExhaustsAttempts() {
    source.add(FakeGraphProjectionEnv.work("w-1", 5L, "s-1", "s-2"));
    store.transientFailures.put("w-1", 10);
    GraphProjectionSynchronizer synchronizer = synchronizer(new GraphProjectionRetryPolicy(2, 0L));

    GraphSyncOutcome outcome = synchronizer.syncWork(GEN, "w-1").orElseThrow();

    assertThat(outcome.action()).isEqualTo(Action.FAILED_RETRYABLE);
    assertThat(outcome.attempts()).isEqualTo(2);
    assertThat(store.works).doesNotContainKey("w-1");
  }

  @Test
  @DisplayName("批量同步：单条失败不中断其余成果，计数分档")
  void batchContinuesAfterFailure() {
    GraphProjectedWork first = FakeGraphProjectionEnv.work("w-1", 1L, "s-1", "s-2");
    GraphProjectedWork second = FakeGraphProjectionEnv.work("w-2", 1L, "s-1", "s-3");
    GraphProjectedWork third = FakeGraphProjectionEnv.work("w-3", 1L, "s-2", "s-3");
    store.permanentFailures.add("w-2");
    GraphProjectionSynchronizer synchronizer = synchronizer(GraphProjectionRetryPolicy.noRetry());

    GraphSyncBatch batch = synchronizer.syncWorks(List.of(first, second, third));

    assertThat(batch.processed()).isEqualTo(3);
    assertThat(batch.applied()).isEqualTo(2);
    assertThat(batch.failed()).isEqualTo(1);
    assertThat(batch.permanentFailures()).isEqualTo(1);
    assertThat(batch.failureSummary(5)).contains("w-2").contains("FAILED_PERMANENT");
    assertThat(store.works).containsKeys("w-1", "w-3");
  }
}
