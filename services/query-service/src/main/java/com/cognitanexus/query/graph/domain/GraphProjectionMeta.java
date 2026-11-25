package com.cognitanexus.query.graph.domain;

import java.time.Instant;

/**
 * 图投影水位（{@code GraphProjectionMeta.scope} 唯一）。
 *
 * <p>用途：回答「图里已经是 PG 的哪个版本」。图查询必须先把本水位与 PG 的已发布修订
 * （{@code qry_consumer_state.published_revision}）比较，<b>落后就降级回 PG，不能拿旧图当作
 * 当前结果</b>。这和 PG 侧「received/published 水位不一致即拒绝公开查询」是同一条原则：
 * 在「谁能被看见」的问题上，看起来完整的旧答案比明确的错误更危险。
 *
 * <p>水位只允许前进。迟到或重放的事件版本低于当前水位时，存储层忽略而不是回退——回退会让
 * 已经下线的合作边重新出现，而调用方看不出来。
 *
 * @param scope 投影作用域，例如 {@code query-public}。同一 Neo4j 实例只应有一个公开作用域；
 *     多作用域共用实例时，重置/重建必须按作用域隔离，不能整库清空
 * @param projectedVersion 已投影到的 catalog revision（或成果聚合版本）；单调不减
 * @param catalogRevision 投影时看到的 core 目录修订
 * @param generation 投影对应的 PG generation（重建切换后图也要跟着换代）
 * @param updatedAt 最近一次推进时间，供运维判断滞后时长
 */
public record GraphProjectionMeta(
    String scope,
    long projectedVersion,
    long catalogRevision,
    String generation,
    Instant updatedAt) {

  public GraphProjectionMeta {
    if (scope == null || scope.isBlank()) {
      throw new IllegalArgumentException("投影作用域不能为空");
    }
    scope = scope.trim();
    projectedVersion = Math.max(projectedVersion, 0L);
    catalogRevision = Math.max(catalogRevision, 0L);
    generation = generation == null || generation.isBlank() ? "gen0001" : generation;
    updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
  }

  public static GraphProjectionMeta empty(String scope) {
    return new GraphProjectionMeta(scope, 0L, 0L, "gen0001", Instant.EPOCH);
  }

  /** 图投影是否落后于给定的 PG 已发布修订。落后时必须走 PG 降级路径。 */
  public boolean behind(long publishedRevision) {
    return projectedVersion < publishedRevision;
  }

  /** 推进水位；同版本重放视为幂等，不回退 catalogRevision 与 generation。 */
  public GraphProjectionMeta advance(
      long newProjectedVersion, long newCatalogRevision, String newGeneration) {
    if (newProjectedVersion <= projectedVersion) {
      return this;
    }
    return new GraphProjectionMeta(
        scope, newProjectedVersion, newCatalogRevision, newGeneration, Instant.now());
  }
}
