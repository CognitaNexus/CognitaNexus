package com.cognitanexus.query.graph.projection.application;

import com.cognitanexus.query.graph.projection.domain.GraphBackfillStatus;
import com.cognitanexus.query.graph.projection.domain.GraphProjectionCheckpoint;
import java.util.Optional;

/**
 * 增量消费（Outbox/Inbox → 图）的初始化前置条件。
 *
 * <p>增量追赶只在「全量回填已经把这一代成果灌完并且没有留下失败成果」之后才有意义。四条条件缺一
 * 不可，因为它们各自对应一种<b>会让水位说谎</b>的失败形态：
 *
 * <ol>
 *   <li><b>断点必须存在</b>：从未回填过就没有「哪一代、从哪个修订开始」的锚点，增量一旦把
 *       {@code projected_version} 推到 {@code published_revision}，读路径就会信任一张只含极少几篇
 *       成果的图——缺口不是「落后」，而是被水位掩盖；
 *   <li><b>断点必须 COMPLETED</b>：{@code RUNNING} 表示还有批次没跑，{@code FAILED} 表示有成果没进图；
 *       两者让增量追平均等于「用后续事件跨过缺口」；
 *   <li><b>{@code failed_works} 必须为 0</b>：{@code COMPLETED} 与「没有失败成果」是两件事——
 *       回填扫描到底但个别成果写入失败时，断点绝不能是 COMPLETED（见 {@link GraphBackfillService}），
 *       这里再守一道，避免历史数据或人工改表留下的不一致放行增量；
 *   <li><b>断点 generation 必须等于 PG 活动代</b>：断点记录的是上一代成果，增量却会读新代的成果行，
 *       混代之后图里一半旧一半新，而水位看起来在前进。正确动作是整库重建（ADR-005 §5.3）。
 * </ol>
 *
 * <p>为什么抽成一个类而不是在 {@link GraphEventProjector} 与
 * {@code GraphProjectionWorker} 各写一遍：两处各写一份守卫，迟早只改一处，于是「调度器认为可以跑、
 * 投影器认为不能跑」或反之，而两条路径的水位语义必须完全一致。
 */
public final class GraphProjectionReadiness {

  private GraphProjectionReadiness() {}

  /**
   * 判定本轮是否允许增量消费。
   *
   * @param checkpoint 当前作用域的断点（可能为空：从未回填）
   * @param activeGeneration PG 当前活动 generation
   * @return 空表示允许消费；有值表示拒绝原因（可直接进结果说明/日志）
   */
  public static Optional<String> blocker(
      Optional<GraphProjectionCheckpoint> checkpoint, String activeGeneration) {
    if (checkpoint == null || checkpoint.isEmpty()) {
      return Optional.of("尚无回填断点（从未完成初始回填）：拒绝增量消费，需先执行 backfill");
    }
    GraphProjectionCheckpoint current = checkpoint.get();
    if (current.status() != GraphBackfillStatus.COMPLETED) {
      return Optional.of(
          "回填未完成（status="
              + current.status()
              + ", cursor="
              + current.cursorWorkId()
              + "）：拒绝增量消费，否则水位会越过尚未回填的成果");
    }
    if (current.failedWorks() != 0) {
      return Optional.of(
          "回填存在 "
              + current.failedWorks()
              + " 条失败成果：拒绝增量消费，需先修复这些成果并用对账零差异收尾"
              + "（GraphProjectionReconciler.repairAndComplete / GraphBackfillService.markRepairedAndCompleted），"
              + "或整库 rebuild");
    }
    if (activeGeneration == null || activeGeneration.isBlank()) {
      return Optional.of("缺少 PG 活动 generation：拒绝增量消费");
    }
    if (!current.matchesGeneration(activeGeneration)) {
      return Optional.of(
          "回填断点 generation="
              + current.generation()
              + " 与 PG 活动代 "
              + activeGeneration
              + " 不一致：拒绝增量消费，需先执行整库重建（rebuild）");
    }
    return Optional.empty();
  }
}
