package com.cognitanexus.query.graph.infrastructure;

import com.cognitanexus.query.graph.application.CollaborationGraphRepository;
import com.cognitanexus.query.graph.config.Neo4jGraphProperties;
import com.cognitanexus.query.graph.domain.GraphCollaborationEdge;
import com.cognitanexus.query.graph.domain.GraphFilterCriteria;
import com.cognitanexus.query.graph.domain.GraphScholarNode;
import com.cognitanexus.query.graph.domain.GraphWorkEvidence;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;

/**
 * Neo4j 版合作图读适配器。
 *
 * <p>两类边来源，合并后对外表现为一种边：
 *
 * <ol>
 *   <li><b>物化边</b> {@code COOPERATED_WITH}：每条关系代表一篇共同合格成果，带 {@code work_id}
 *       与 {@code work_title}。只对作者数 ≤ 阈值的成果建边，避免 n(n-1)/2 组合爆炸；
 *   <li><b>二部图推导</b> {@code Scholar-[:AUTHORED]->Work<-[:AUTHORED]-Scholar}：补上超过阈值
 *       因而不物化的成果。两条路径都在 Cypher 里用 {@code count(DISTINCT work_id)} 聚合强度，
 *       所以强度定义只依赖语义，不依赖存储形态。
 * </ol>
 *
 * <p>带领域/机构筛选时<b>不使用</b>物化边：物化边的强度是「全部合格成果数」，而筛选后的强度
 * 只应统计命中筛选的成果。直接复用会把一条边标成比实际更强，等于凭空制造合作证据。
 * 这类查询全部走二部图重新聚合，代价是若干跳的索引扫描，换取口径与 PG 完全一致。
 *
 * <p>数据性质（REAL / SIMULATED / MIXED）始终下推到成果属性：REAL 查询不会看到模拟成果，
 * 也不会把它们计入强度。
 */
public class Neo4jCollaborationGraphRepository implements CollaborationGraphRepository {

  private final Neo4jSessionRunner runner;
  private final int maxEvidenceWorkIdsPerEdge;

  public Neo4jCollaborationGraphRepository(
      Neo4jSessionRunner runner, Neo4jGraphProperties properties) {
    this.runner = runner;
    this.maxEvidenceWorkIdsPerEdge = properties.maxEvidenceWorkIdsPerEdge();
  }

  @Override
  public Expansion expand(Collection<String> frontier, GraphFilterCriteria criteria, int maxEdges) {
    if (frontier == null || frontier.isEmpty()) {
      return Expansion.empty();
    }
    if (maxEdges <= 0) {
      return new Expansion(List.of(), true);
    }
    List<String> ids = new ArrayList<>(new LinkedHashSet<>(frontier));
    int limit = maxEdges + 1;
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("ids", ids);
    parameters.put("dataOrigin", criteria.dataNature());
    parameters.put("topicId", criteria.topicId());
    parameters.put("organization", criteria.organization());
    parameters.put("limit", limit);

    List<Record> rows = new ArrayList<>();
    if (criteria.hasWorkLevelFilter()) {
      // 筛选后的强度必须按命中筛选的成果重新聚合，全部走二部图。
      rows.addAll(runner.query(FILTERED_BIPARTITE_QUERY, parameters, record -> record));
    } else {
      rows.addAll(runner.query(MATERIALIZED_QUERY, parameters, record -> record));
      rows.addAll(runner.query(UNMATERIALIZED_BIPARTITE_QUERY, parameters, record -> record));
    }
    boolean truncated = rows.size() > maxEdges;
    List<GraphCollaborationEdge> edges = toEdges(rows, maxEdges);
    return new Expansion(edges, truncated);
  }

  @Override
  public List<GraphScholarNode> findScholars(Collection<String> scholarIds) {
    if (scholarIds == null || scholarIds.isEmpty()) {
      return List.of();
    }
    Map<String, Object> parameters = Map.of("ids", new ArrayList<>(scholarIds));
    return runner.query(
        """
        MATCH (s:Scholar)
        WHERE s.scholar_id IN $ids
        RETURN s.scholar_id AS scholarId,
               s.scholar_name AS scholarName,
               s.domain AS domain,
               s.organization AS organization,
               s.data_origin AS dataOrigin,
               coalesce(s.source_version, 0) AS sourceVersion
        ORDER BY scholarId
        """,
        parameters,
        record ->
            new GraphScholarNode(
                record.get("scholarId").asString(),
                stringOrNull(record.get("scholarName")),
                stringOrNull(record.get("domain")),
                stringOrNull(record.get("organization")),
                stringOrNull(record.get("dataOrigin")),
                record.get("sourceVersion").asLong()));
  }

  @Override
  public boolean isAvailable() {
    return runner.isAvailable();
  }

  private List<GraphCollaborationEdge> toEdges(List<Record> rows, int maxEdges) {
    Map<String, Accumulator> accumulators = new LinkedHashMap<>();
    for (Record row : rows) {
      String left = row.get("aId").asString();
      String right = row.get("bId").asString();
      if (left.equals(right)) {
        continue;
      }
      String scholarA = left.compareTo(right) <= 0 ? left : right;
      String scholarB = left.compareTo(right) <= 0 ? right : left;
      String pairKey = scholarA + "\u0001" + scholarB;
      Accumulator accumulator =
          accumulators.computeIfAbsent(pairKey, key -> new Accumulator(scholarA, scholarB));
      accumulator.absorb(row.get("strength").asLong(), row.get("evidence"));
      if (accumulators.size() > maxEdges) {
        // 多取一条即足以判定越界；不把结果继续拼大。
        break;
      }
    }
    List<GraphCollaborationEdge> edges = new ArrayList<>(accumulators.size());
    for (Accumulator accumulator : accumulators.values()) {
      edges.add(accumulator.toEdge(maxEvidenceWorkIdsPerEdge));
    }
    return edges;
  }

  private static String stringOrNull(Value value) {
    return value == null || value.isNull() ? null : value.asString();
  }

  /** 同一作者对的多路来源聚合：按 workId 去重证据，强度取声明值与去重后证据数的较大者。 */
  private static final class Accumulator {

    private final String scholarA;
    private final String scholarB;
    private final Map<String, GraphWorkEvidence> evidence = new LinkedHashMap<>();
    private long strength;

    private Accumulator(String scholarA, String scholarB) {
      this.scholarA = scholarA;
      this.scholarB = scholarB;
    }

    private void absorb(long statedStrength, Value evidenceValue) {
      strength = Math.max(strength, statedStrength);
      if (evidenceValue == null || evidenceValue.isNull()) {
        return;
      }
      for (Map<String, Object> item : evidenceValue.asList(value -> value.asMap())) {
        String workId = String.valueOf(item.get("workId"));
        String title = item.get("title") == null ? "" : String.valueOf(item.get("title"));
        String dataOrigin =
            item.get("dataOrigin") == null ? null : String.valueOf(item.get("dataOrigin"));
        if (workId.isBlank() || title.isBlank()) {
          // 无标题的证据无法核对，宁可显式失败也不悄悄丢弃（丢弃会让边看起来没有证据）。
          throw new GraphStoreUnavailableException("图库返回的合作证据缺少 workId 或标题，图投影已损坏，需从 PG 重建");
        }
        evidence.putIfAbsent(workId, new GraphWorkEvidence(workId, title, dataOrigin));
      }
    }

    private GraphCollaborationEdge toEdge(int maxEvidence) {
      List<GraphWorkEvidence> all = new ArrayList<>(evidence.values());
      long distinct = all.size();
      long effectiveStrength = Math.max(Math.max(strength, 1L), distinct);
      List<GraphWorkEvidence> limited =
          all.size() <= maxEvidence ? all : new ArrayList<>(all.subList(0, maxEvidence));
      return new GraphCollaborationEdge(scholarA, scholarB, effectiveStrength, limited);
    }
  }

  /** 物化边。每条关系只代表一篇成果，聚合后的强度即不同成果数。 */
  private static final String MATERIALIZED_QUERY =
      """
      MATCH (a:Scholar)-[c:COOPERATED_WITH]-(b:Scholar)
      WHERE a.scholar_id IN $ids
        AND c.is_valid = true
        AND ($dataOrigin IS NULL OR c.data_origin = $dataOrigin)
      WITH a.scholar_id AS aId, b.scholar_id AS bId,
           collect(DISTINCT {workId: c.work_id, title: c.work_title, dataOrigin: c.data_origin}) AS evidence
      RETURN aId, bId, size(evidence) AS strength, evidence
      ORDER BY aId, bId
      LIMIT $limit
      """;

  /** 未物化的高作者数成果：通过二部图推导共同作者，强度仍是不同成果数。 */
  private static final String UNMATERIALIZED_BIPARTITE_QUERY =
      """
      MATCH (a:Scholar)-[ra:AUTHORED]->(w:Work)<-[rb:AUTHORED]-(b:Scholar)
      WHERE a.scholar_id IN $ids
        AND a.scholar_id <> b.scholar_id
        AND w.is_public = true
        AND w.edges_materialized = false
        AND ra.relation_state IN ['AUTO', 'SELF_CONFIRMED']
        AND rb.relation_state IN ['AUTO', 'SELF_CONFIRMED']
        AND ($dataOrigin IS NULL OR w.data_origin = $dataOrigin)
      WITH a.scholar_id AS aId, b.scholar_id AS bId,
           collect(DISTINCT {workId: w.work_id, title: w.title, dataOrigin: w.data_origin}) AS evidence
      RETURN aId, bId, size(evidence) AS strength, evidence
      ORDER BY aId, bId
      LIMIT $limit
      """;

  /** 带领域/机构筛选：使用全部成果重新聚合，保证强度与 PG 的 EXISTS 过滤口径一致。 */
  private static final String FILTERED_BIPARTITE_QUERY =
      """
      MATCH (a:Scholar)-[ra:AUTHORED]->(w:Work)<-[rb:AUTHORED]-(b:Scholar)
      WHERE a.scholar_id IN $ids
        AND a.scholar_id <> b.scholar_id
        AND w.is_public = true
        AND ra.relation_state IN ['AUTO', 'SELF_CONFIRMED']
        AND rb.relation_state IN ['AUTO', 'SELF_CONFIRMED']
        AND ($dataOrigin IS NULL OR w.data_origin = $dataOrigin)
        AND ($topicId IS NULL OR $topicId IN coalesce(w.topic_ids, []))
        AND ($organization IS NULL OR $organization IN coalesce(w.organizations, []))
      WITH a.scholar_id AS aId, b.scholar_id AS bId,
           collect(DISTINCT {workId: w.work_id, title: w.title, dataOrigin: w.data_origin}) AS evidence
      RETURN aId, bId, size(evidence) AS strength, evidence
      ORDER BY aId, bId
      LIMIT $limit
      """;
}
