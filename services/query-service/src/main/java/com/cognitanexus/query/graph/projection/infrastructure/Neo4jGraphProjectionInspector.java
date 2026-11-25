package com.cognitanexus.query.graph.projection.infrastructure;

import com.cognitanexus.query.graph.projection.application.GraphProjectionInspector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.neo4j.driver.Record;

/**
 * {@link GraphProjectionInspector} 的 Neo4j 实现。
 *
 * <p>所有计数都复述 ADR-005 的口径，且与 PG 侧 SQL 一一对应：
 *
 * <ul>
 *   <li>公开成果：{@code Work.is_public = true}。墓碑（{@code false}）不计入——它是「曾经投影过、
 *       现在不该出现」的事实，不是当前成果；
 *   <li>学者与参与边：在公开成果上、且 {@code relation_state} 属于合格集合的 {@code AUTHORED}。
 *       墓碑成果上保留的参与边（用于追溯与二部图）不能被计入，否则「PG 已下线」的成果会继续
 *       贡献可合作的学者；
 *   <li>物化合作边：{@code COOPERATED_WITH} 全量条数。它们只存在于公开成果上，退场时按
 *       {@code work_id} 删净（ADR-005 §7）。
 * </ul>
 *
 * <p>只读实现：本类不提供任何写方法，修复必须走投影链路（重新同步/退场），保持「图只能由 PG
 * 派生」这条不变量。
 */
public class Neo4jGraphProjectionInspector implements GraphProjectionInspector {

  /** Neo4j 参数为 null 时不能直接用属性比较，统一用 null 表示「不过滤」。 */
  private final com.cognitanexus.query.graph.infrastructure.Neo4jSessionRunner runner;

  public Neo4jGraphProjectionInspector(
      com.cognitanexus.query.graph.infrastructure.Neo4jSessionRunner runner) {
    this.runner = runner;
  }

  @Override
  public GraphCounts counts(String dataOrigin) {
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("dataOrigin", dataOrigin);
    Record record = runner.query(COUNT_PROJECTION, parameters, row -> row).get(0);
    return new GraphCounts(
        record.get("publicWorks").asLong(),
        record.get("scholars").asLong(),
        record.get("authoredRelations").asLong(),
        record.get("materializedEdges").asLong());
  }

  @Override
  public List<String> publicWorkIds(int limit) {
    if (limit <= 0) {
      return List.of();
    }
    return runner.query(
        "MATCH (w:Work) WHERE w.is_public = true RETURN w.work_id AS workId "
            + "ORDER BY workId LIMIT $limit",
        Map.of("limit", limit),
        record -> record.get("workId").asString());
  }

  @Override
  public Set<String> publicWorkIdsAmong(Collection<String> workIds) {
    List<String> ids = distinct(workIds);
    if (ids.isEmpty()) {
      return Set.of();
    }
    List<String> found =
        runner.query(
            "MATCH (w:Work) WHERE w.work_id IN $ids AND w.is_public = true "
                + "RETURN w.work_id AS workId",
            Map.of("ids", ids),
            record -> record.get("workId").asString());
    return new LinkedHashSet<>(found);
  }

  @Override
  public Map<String, Set<String>> materializedPairsOf(Collection<String> workIds) {
    List<String> ids = distinct(workIds);
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<String, Set<String>> pairs = new LinkedHashMap<>();
    runner
        .query(
            "MATCH (:Scholar)-[c:COOPERATED_WITH]->() WHERE c.work_id IN $ids "
                + "RETURN c.work_id AS workId, c.pair_key AS pairKey",
            Map.of("ids", ids),
            record -> record)
        .forEach(
            record ->
                pairs
                    .computeIfAbsent(record.get("workId").asString(), key -> new LinkedHashSet<>())
                    .add(record.get("pairKey").isNull() ? null : record.get("pairKey").asString()));
    // 没有 pair_key 的边不应存在（写入时必填）；把它显式记为 null 元素，让对账报出差异。
    pairs.values().forEach(set -> set.remove(null));
    return pairs;
  }

  @Override
  public Map<String, String> workTitles(Collection<String> workIds) {
    List<String> ids = distinct(workIds);
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<String, String> titles = new LinkedHashMap<>();
    for (Record record :
        runner.query(
            "MATCH (w:Work) WHERE w.work_id IN $ids "
                + "RETURN w.work_id AS workId, coalesce(w.title, '') AS title",
            Map.of("ids", ids),
            row -> row)) {
      titles.put(record.get("workId").asString(), record.get("title").asString());
    }
    return titles;
  }

  @Override
  public Map<String, Set<String>> evidenceTitlesOf(Collection<String> workIds) {
    List<String> ids = distinct(workIds);
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<String, Set<String>> titles = new LinkedHashMap<>();
    for (Record record :
        runner.query(
            "MATCH (:Scholar)-[c:COOPERATED_WITH]->() WHERE c.work_id IN $ids "
                + "RETURN c.work_id AS workId, coalesce(c.work_title, '') AS title",
            Map.of("ids", ids),
            row -> row)) {
      titles
          .computeIfAbsent(record.get("workId").asString(), key -> new LinkedHashSet<>())
          .add(record.get("title").asString());
    }
    return titles;
  }

  private static List<String> distinct(Collection<String> workIds) {
    Set<String> ids = new LinkedHashSet<>();
    if (workIds != null) {
      for (String id : workIds) {
        if (id != null && !id.isBlank()) {
          ids.add(id.trim());
        }
      }
    }
    return new ArrayList<>(ids);
  }

  /**
   * 四个计数一次查询完成，减少对账对图库的往返次数（默认读路径不受影响）。
   *
   * <p>{@code $dataOrigin IS NULL OR ...} 是显式的「不限制」语义，与 PG 侧
   * {@code <if test="dataOrigin != null">} 对应：不要靠参数缺失表达放宽，靠可读的条件。
   */
  private static final String COUNT_PROJECTION =
      """
      CALL () {
        MATCH (w:Work)
        WHERE w.is_public = true
          AND ($dataOrigin IS NULL OR w.data_origin = $dataOrigin)
        RETURN count(w) AS publicWorks
      }
      CALL () {
        MATCH (s:Scholar)-[r:AUTHORED]->(w:Work)
        WHERE w.is_public = true
          AND r.relation_state IN ['AUTO', 'SELF_CONFIRMED']
          AND ($dataOrigin IS NULL OR w.data_origin = $dataOrigin)
        RETURN count(DISTINCT s) AS scholars, count(r) AS authoredRelations
      }
      CALL () {
        MATCH (:Scholar)-[c:COOPERATED_WITH]->()
        WHERE $dataOrigin IS NULL OR c.data_origin = $dataOrigin
        RETURN count(c) AS materializedEdges
      }
      RETURN publicWorks, scholars, authoredRelations, materializedEdges
      """;
}
