package com.cognitanexus.query.graph.projection.infrastructure;

import com.cognitanexus.query.graph.projection.application.GraphProjectionCheckpointStore;
import com.cognitanexus.query.graph.projection.domain.GraphBackfillStatus;
import com.cognitanexus.query.graph.projection.domain.GraphProjectionCheckpoint;
import java.util.Optional;

/**
 * {@link GraphProjectionCheckpointStore} 的 MyBatis 实现。
 *
 * <p>数据库里的 status 用字符串而不是枚举名：{@code status} 参与 CHECK 约束，字符串值与迁移脚本
 * 里写的一致；未来枚举改名不会让历史行变成「非法值」。读取时非法值直接抛
 * {@link IllegalArgumentException}（Domain 构造器统一校验），而不是悄悄当成 RUNNING——
 * 把未知状态当「继续跑」是最危险的一种宽容。
 */
public class GraphProjectionCheckpointAdapter implements GraphProjectionCheckpointStore {

  private final GraphProjectionCheckpointMapper mapper;

  public GraphProjectionCheckpointAdapter(GraphProjectionCheckpointMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<GraphProjectionCheckpoint> find(String scope) {
    if (scope == null || scope.isBlank()) {
      return Optional.empty();
    }
    GraphProjectionCheckpointMapper.CheckpointRow row = mapper.find(scope.trim());
    if (row == null) {
      return Optional.empty();
    }
    GraphBackfillStatus status;
    try {
      status = GraphBackfillStatus.valueOf(row.status());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          "图投影断点状态非法：" + row.status() + "（scope=" + row.scope() + "）", ex);
    }
    return Optional.of(
        new GraphProjectionCheckpoint(
            row.scope(),
            status,
            row.generation(),
            row.cursorWorkId(),
            row.baseRevision(),
            row.processedWorks(),
            row.processedBatches(),
            row.failedWorks(),
            row.lastError(),
            row.startedAt(),
            row.updatedAt(),
            row.finishedAt()));
  }

  @Override
  public int save(GraphProjectionCheckpoint checkpoint) {
    if (checkpoint == null) {
      throw new IllegalArgumentException("断点不能为空");
    }
    return mapper.upsert(
        new GraphProjectionCheckpointMapper.CheckpointRow(
            checkpoint.scope(),
            checkpoint.status().name(),
            checkpoint.generation(),
            checkpoint.cursorWorkId(),
            checkpoint.baseRevision(),
            checkpoint.processedWorks(),
            checkpoint.processedBatches(),
            checkpoint.failedWorks(),
            checkpoint.lastError(),
            checkpoint.startedAt(),
            checkpoint.updatedAt(),
            checkpoint.finishedAt()));
  }
}
