package com.cognitanexus.query.graph.projection.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 断点的状态迁移。
 *
 * <p>锁住两件事：游标只能随「已处理一批」前进（空游标会让下一轮从第一页重扫），
 * 以及 COMPLETED 之外的失败保留游标与原因——否则修复后只能从头再来。
 */
class GraphProjectionCheckpointTest {

  private static final Instant T0 = Instant.parse("2026-11-25T00:00:00Z");

  @Test
  @DisplayName("新建 → 推进 → 完成：统计与游标如实累积")
  void lifecycle() {
    GraphProjectionCheckpoint started =
        GraphProjectionCheckpoint.started("query-public", "gen0001", 7L, T0);
    assertThat(started.status()).isEqualTo(GraphBackfillStatus.RUNNING);
    assertThat(started.cursorWorkId()).isNull();
    assertThat(started.resumable()).isTrue();

    GraphProjectionCheckpoint advanced = started.advanced("w-500", 500, 3, T0.plusSeconds(5));
    assertThat(advanced.cursorWorkId()).isEqualTo("w-500");
    assertThat(advanced.processedWorks()).isEqualTo(500);
    assertThat(advanced.processedBatches()).isEqualTo(1);
    assertThat(advanced.failedWorks()).isEqualTo(3);

    GraphProjectionCheckpoint completed = advanced.completed(T0.plusSeconds(9));
    assertThat(completed.status()).isEqualTo(GraphBackfillStatus.COMPLETED);
    assertThat(completed.resumable()).isFalse();
    assertThat(completed.finishedAt()).isEqualTo(T0.plusSeconds(9));
    assertThat(completed.failedWorks()).isEqualTo(3);
  }

  @Test
  @DisplayName("中止保留游标与原因，仍可续跑")
  void failedKeepsCursor() {
    GraphProjectionCheckpoint failed =
        GraphProjectionCheckpoint.started("query-public", "gen0001", 7L, T0)
            .advanced("w-500", 500, 0, T0)
            .failed("图库连接中断", T0.plusSeconds(30));

    assertThat(failed.status()).isEqualTo(GraphBackfillStatus.FAILED);
    assertThat(failed.cursorWorkId()).isEqualTo("w-500");
    assertThat(failed.lastError()).contains("图库连接中断");
    assertThat(failed.resumable()).isTrue();
  }

  @Test
  @DisplayName("空游标不得推进进度（否则下一轮静默从第一页重扫）")
  void advancingWithoutCursorIsRejected() {
    GraphProjectionCheckpoint started =
        GraphProjectionCheckpoint.started("query-public", "gen0001", 0L, T0);

    assertThatThrownBy(() -> started.advanced(" ", 10, 0, T0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("空游标");
  }

  @Test
  @DisplayName("非法状态与缺失 generation 被构造器拒绝，而不是被当成 RUNNING")
  void invalidCheckpointRejected() {
    assertThatThrownBy(
            () ->
                new GraphProjectionCheckpoint(
                    "s", null, "gen0001", null, 0, 0, 0, 0, null, T0, T0, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("断点状态");
    assertThatThrownBy(
            () ->
                new GraphProjectionCheckpoint(
                    "s", GraphBackfillStatus.RUNNING, " ", null, 0, 0, 0, 0, null, T0, T0, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("generation");
  }

  @Test
  @DisplayName("代不匹配时不允许续跑")
  void generationMismatchBlocksResume() {
    GraphProjectionCheckpoint checkpoint =
        GraphProjectionCheckpoint.started("query-public", "gen0001", 0L, T0);

    assertThat(checkpoint.matchesGeneration("gen0001")).isTrue();
    assertThat(checkpoint.matchesGeneration("gen0002")).isFalse();
  }
}
