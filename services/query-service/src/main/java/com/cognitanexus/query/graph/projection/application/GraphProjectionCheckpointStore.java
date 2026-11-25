package com.cognitanexus.query.graph.projection.application;

import com.cognitanexus.query.graph.projection.domain.GraphProjectionCheckpoint;
import java.util.Optional;

/**
 * Backfill 断点的持久化端口（PG 表 {@code qry_graph_projection_checkpoint}）。
 *
 * <p>断点必须落 PG 而不是图库或内存：图库整体可丢弃（ADR-005 §8.3），断点丢在里面就等于
 * 「重建过程中图库一重启，进度归零」；内存断点在进程重启后无法解释哪些成果已真正写入。
 *
 * <p>刻意不提供 delete：清理进度用「重写一行」完成（{@code upsert}），保留历史可避免
 * 「谁在什么时候从哪里开始重建」这个问题无从回答。
 */
public interface GraphProjectionCheckpointStore {

  Optional<GraphProjectionCheckpoint> find(String scope);

  /** 幂等写入（按 scope upsert）；返回写入的行数，便于断点丢失时能被测试发现。 */
  int save(GraphProjectionCheckpoint checkpoint);
}
