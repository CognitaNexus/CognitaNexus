package com.cognitanexus.query.graph.projection.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.query.graph.projection.domain.GraphProjectionCheckpoint;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 增量消费的初始化前置条件（P1-3）。
 *
 * <p>这四条规则被 {@link GraphEventProjector} 与 {@code GraphProjectionWorker} 共用，因此单独锁住：
 * 断点存在、{@code COMPLETED}、{@code failed_works=0}、generation 与 PG 活动代一致。任何一条不满足
 * 都必须拒绝消费——否则水位会被推到「已追平」，而图其实是空的、缺一段的或跨代的。
 */
class GraphProjectionReadinessTest {

  private static final String SCOPE = "query-public";
  private static final String GEN = "gen0001";

  private static GraphProjectionCheckpoint checkpoint(String generation) {
    return GraphProjectionCheckpoint.started(SCOPE, generation, 10L, Instant.now());
  }

  @Test
  @DisplayName("没有断点：拒绝（从未回填等于图基本为空）")
  void missingCheckpointIsBlocked() {
    assertThat(GraphProjectionReadiness.blocker(Optional.empty(), GEN)).isPresent();
    assertThat(GraphProjectionReadiness.blocker(null, GEN)).isPresent();
  }

  @Test
  @DisplayName("断点 RUNNING/FAILED：拒绝（缺口未补齐）")
  void unfinishedCheckpointIsBlocked() {
    assertThat(GraphProjectionReadiness.blocker(Optional.of(checkpoint(GEN)), GEN))
        .hasValueSatisfying(reason -> assertThat(reason).contains("RUNNING"));

    Instant now = Instant.now();
    GraphProjectionCheckpoint failed =
        checkpoint(GEN).advanced("w-1", 1, 0, now).failed("回填中断", now);
    assertThat(GraphProjectionReadiness.blocker(Optional.of(failed), GEN))
        .hasValueSatisfying(reason -> assertThat(reason).contains("FAILED"));
  }

  @Test
  @DisplayName("断点 COMPLETED 但存在失败成果：拒绝（COMPLETED 与「都进图了」不是一件事）")
  void completedWithFailedWorksIsBlocked() {
    Instant now = Instant.now();
    GraphProjectionCheckpoint completed =
        checkpoint(GEN).advanced("w-1", 1, 4, "w-1:FAILED_PERMANENT:x", now).completed(now);

    assertThat(GraphProjectionReadiness.blocker(Optional.of(completed), GEN))
        .hasValueSatisfying(reason -> assertThat(reason).contains("失败成果"));
  }

  @Test
  @DisplayName("断点与 PG 活动代不一致：拒绝（混代会得到一半旧一半新的图）")
  void crossGenerationCheckpointIsBlocked() {
    Instant now = Instant.now();
    GraphProjectionCheckpoint completed =
        checkpoint("gen0000").advanced("w-1", 1, 0, now).completed(now);

    assertThat(GraphProjectionReadiness.blocker(Optional.of(completed), GEN))
        .hasValueSatisfying(reason -> assertThat(reason).contains("gen0000").contains("rebuild"));

    assertThat(GraphProjectionReadiness.blocker(Optional.of(completed), " "))
        .as("缺少活动代同样拒绝，而不是默认放行")
        .isPresent();
  }

  @Test
  @DisplayName("断点 COMPLETED + 零失败 + 同代：允许增量消费")
  void completedCleanSameGenerationIsAllowed() {
    Instant now = Instant.now();
    GraphProjectionCheckpoint completed = checkpoint(GEN).advanced("w-1", 1, 0, now).completed(now);

    assertThat(GraphProjectionReadiness.blocker(Optional.of(completed), GEN)).isEmpty();
  }
}
