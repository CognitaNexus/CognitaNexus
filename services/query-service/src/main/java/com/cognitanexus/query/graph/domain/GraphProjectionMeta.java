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
 *     多作用域共用实例时，重置/重建必须按作用域隔离，不能整库清空。
 *     {@code GraphProjectionStore.resetAllForRebuild(scope)} 因此在删除前先读全部水位节点，
 *     发现其他 scope 就抛 {@link IllegalStateException} 并回滚——宁可拒绝重建，
 *     也不把别人的图数据一起删掉
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

  /**
   * 「尚未初始化」的占位 generation。
   *
   * <p>回填在开始灌数据前会把水位节点写成这个占位值（版本 0、generation 占位）：此刻图里的
   * 节点/边只是半成品。它<b>不是</b>一个真实的 PG 代数，任何以它为依据的「版本不比已发布修订落后
   * 就算可用」的判断都是错的——{@code 0 >= 0} 在空库上永远成立。
   */
  public static final String UNINITIALIZED_GENERATION = "UNINITIALIZED";

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

  /**
   * 该代价值能否作为「投影已完成基线初始化」的证据。
   *
   * <p>空值与 {@link #UNINITIALIZED_GENERATION} 都是「不可判定」，不能当作一致来处理：
   * 只要一侧不可判定，就无法证明图属于当前活动代，必须判为未初始化而不是放行。
   */
  public static boolean isPlaceholderGeneration(String generation) {
    return generation == null
        || generation.isBlank()
        || UNINITIALIZED_GENERATION.equalsIgnoreCase(generation.trim());
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
