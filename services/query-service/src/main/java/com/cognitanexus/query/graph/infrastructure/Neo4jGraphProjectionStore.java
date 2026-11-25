package com.cognitanexus.query.graph.infrastructure;

import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.config.Neo4jGraphProperties;
import com.cognitanexus.query.graph.domain.GraphAuthorship;
import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import com.cognitanexus.query.graph.domain.GraphScholarNode;
import com.cognitanexus.query.graph.domain.GraphWorkNode;
import com.cognitanexus.query.network.domain.NetworkRules;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.neo4j.driver.Record;

/**
 * Neo4j 版图投影写适配器（Backfill 与 Outbox 消费者共用）。
 *
 * <p>写入策略与 PG 侧 {@code qry_coauthor_edge} 逐条对齐：
 *
 * <ul>
 *   <li>成果更新按<b>整体替换</b>：先删该成果的 {@code AUTHORED} 与 {@code COOPERATED_WITH}，
 *       再按当前合格作者重建。不采用「强度加一」，因此事件重放与乱序不会累计出虚假强度；
 *   <li>每条 {@code COOPERATED_WITH} 只代表一篇成果，携带 {@code work_id} 与 {@code work_title}。
 *       证据与边同版本写入，撤销成果时按 {@code work_id} 一次删净；
 *   <li>作者数超过阈值（{@code maxAuthorsPerWorkForMaterializedEdge}，默认 20，硬上限 200）
 *       不建作者对边，只保留参与边，由二部图查询按需推导——这是防组合爆炸的关键取舍；
 *   <li>非 AUTO / SELF_CONFIRMED 的参与关系在领域层即被拒绝，本类不会写入未确认作者。
 * </ul>
 *
 * <p>版本守卫：所有写入按 {@code source_version} 比较，旧版本事件直接返回 false 且不产生副作用。
 * 水位 {@code GraphProjectionMeta.projected_version} 只前进，落后时由调用方降级到 PG。
 */
public class Neo4jGraphProjectionStore implements GraphProjectionStore {

  /** 单条语句最多携带的成果数/作者数/作者对数，防止一次事务参数过大。 */
  private static final int BATCH_SIZE = 500;

  private final Neo4jSessionRunner runner;
  private final int materializedAuthorThreshold;
  private final String scope;

  public Neo4jGraphProjectionStore(Neo4jSessionRunner runner, Neo4jGraphProperties properties) {
    this.runner = runner;
    this.materializedAuthorThreshold = properties.maxAuthorsPerWorkForMaterializedEdge();
    this.scope = properties.scope();
  }

  @Override
  public int upsertScholars(Collection<GraphScholarNode> scholars) {
    if (scholars == null || scholars.isEmpty()) {
      return 0;
    }
    List<Map<String, Object>> rows = new ArrayList<>(scholars.size());
    for (GraphScholarNode scholar : scholars) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("scholarId", scholar.scholarId());
      row.put("scholarName", scholar.scholarName());
      row.put("domain", scholar.domain());
      row.put("organization", scholar.organization());
      row.put("dataOrigin", scholar.dataOrigin());
      row.put("sourceVersion", scholar.sourceVersion());
      rows.add(row);
    }
    long applied = 0L;
    for (List<Map<String, Object>> batch : partition(rows)) {
      applied +=
          runner.write(
              tx -> {
                Record record = tx.run(UPSERT_SCHOLARS, Map.of("rows", batch)).single();
                return record.get("applied").asLong();
              });
    }
    return (int) Math.min(applied, Integer.MAX_VALUE);
  }

  @Override
  public boolean replaceWork(GraphWorkNode work, List<GraphAuthorship> authorships) {
    if (work == null) {
      throw new IllegalArgumentException("work 不能为空");
    }
    if (authorships == null) {
      throw new IllegalArgumentException("authorships 不能为 null；解析失败的事件应隔离而不是传空集合");
    }
    List<GraphAuthorship> authors = new ArrayList<>(authorships);
    boolean materialize =
        work.isPublic()
            && materializedAuthorThreshold >= 2
            && authors.size() <= materializedAuthorThreshold;
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("workId", work.workId());
    parameters.put("title", work.title());
    parameters.put("isPublic", work.isPublic());
    parameters.put("dataOrigin", work.dataOrigin());
    parameters.put("sourceVersion", work.sourceVersion());
    parameters.put("topicIds", work.topicIds());
    parameters.put("organizations", work.organizations());
    parameters.put("authorCount", authors.size());
    parameters.put("edgesMaterialized", materialize);
    parameters.put("workTitle", work.title());
    parameters.put("ruleVersion", NetworkRules.RULE_VERSION);

    return runner.write(
        tx -> {
          // 版本守卫：先更新/插入成果节点；版本更旧时不返回行，后续写入全部跳过。
          boolean applied = tx.run(UPSERT_WORK, parameters).stream().findAny().isPresent();
          if (!applied) {
            return false;
          }
          // 按成果整体替换：先删旧边再重建，保证事件重放幂等。
          tx.run(DELETE_AUTHORED, Map.of("workId", work.workId())).consume();
          tx.run(DELETE_COOPERATED, Map.of("workId", work.workId())).consume();

          if (!work.isPublic() || authors.isEmpty()) {
            return true;
          }
          List<Map<String, Object>> authorRows = new ArrayList<>(authors.size());
          for (GraphAuthorship authorship : authors) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scholarId", authorship.scholarId());
            row.put("relationState", authorship.relationState());
            row.put("authorOrder", authorship.authorOrder());
            authorRows.add(row);
          }
          for (List<Map<String, Object>> batch : partition(authorRows)) {
            Map<String, Object> authorParameters = new LinkedHashMap<>(parameters);
            authorParameters.put("authors", batch);
            tx.run(CREATE_AUTHORED, authorParameters).consume();
          }

          if (!materialize) {
            // 高作者数成果：不物化作者对边，只保留参与边供二部图查询与证据追溯。
            return true;
          }
          List<Map<String, Object>> pairs = authorPairs(authors);
          for (List<Map<String, Object>> batch : partition(pairs)) {
            Map<String, Object> pairParameters = new LinkedHashMap<>(parameters);
            pairParameters.put("pairs", batch);
            tx.run(CREATE_COOPERATED, pairParameters).consume();
          }
          return true;
        });
  }

  @Override
  public boolean retireWork(String workId, long sourceVersion) {
    if (workId == null || workId.isBlank()) {
      throw new IllegalArgumentException("workId 不能为空");
    }
    return runner.write(
        tx -> {
          Map<String, Object> parameters = Map.of("workId", workId, "sourceVersion", sourceVersion);
          boolean applied = tx.run(RETIRE_WORK, parameters).stream().findAny().isPresent();
          if (!applied) {
            return false;
          }
          // 成果不再公开，它的合作边必须同时下线；参与边保留为墓碑的一部分，查询侧过滤 is_public。
          tx.run(DELETE_COOPERATED, Map.of("workId", workId)).consume();
          return true;
        });
  }

  /**
   * 删除图内孤立成果节点与它的边（PG 中已无该行）。
   *
   * <p>为什么不用 {@code retireWork(workId, Long.MAX_VALUE)} 代替：那会把 {@code source_version}
   * 抬成哨兵值，该成果日后在 PG 中重新合格时，正常版本的 {@code replaceWork} 会被版本守卫永久
   * 拒绝（{@code STALE_IGNORED}），图上就再也恢复不出这篇成果。删除则让节点回到「从未投影」，
   * 任何版本都能重新写入。
   *
   * <p>{@code AUTHORED} 由 {@code DETACH DELETE} 一并清理；{@code COOPERATED_WITH} 是学者之间的
   * 直接边、只带 {@code work_id} 属性，不会被 DETACH DELETE 带走，必须按 {@code work_id} 显式删除，
   * 否则会留下幽灵合作边。
   */
  @Override
  public boolean deleteWork(String workId) {
    if (workId == null || workId.isBlank()) {
      throw new IllegalArgumentException("workId 不能为空");
    }
    return runner.write(
        tx -> {
          tx.run(DELETE_COOPERATED, Map.of("workId", workId)).consume();
          var summary = tx.run(DELETE_WORK, Map.of("workId", workId)).consume();
          return summary.counters().nodesDeleted() > 0;
        });
  }

  @Override
  public Optional<GraphProjectionMeta> readMeta(String scope) {
    String effectiveScope = scope == null || scope.isBlank() ? this.scope : scope;
    List<Record> rows = runner.query(READ_META, Map.of("scope", effectiveScope), record -> record);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Record record = rows.get(0);
    return Optional.of(
        new GraphProjectionMeta(
            effectiveScope,
            record.get("projectedVersion").asLong(),
            record.get("catalogRevision").asLong(),
            record.get("generation").isNull() ? null : record.get("generation").asString(),
            java.time.Instant.ofEpochMilli(record.get("updatedAtEpochMs").asLong())));
  }

  @Override
  public long advanceMeta(GraphProjectionMeta meta) {
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("scope", meta.scope());
    parameters.put("projectedVersion", meta.projectedVersion());
    parameters.put("catalogRevision", meta.catalogRevision());
    parameters.put("generation", meta.generation());
    parameters.put("updatedAtEpochMs", meta.updatedAt().toEpochMilli());
    return runner.write(
        tx -> tx.run(ADVANCE_META, parameters).single().get("projectedVersion").asLong());
  }

  /**
   * <b>运维专用</b>：清空整库并重建单一作用域的水位。
   *
   * <p>方法名里的 <b>All</b> 是故意的：它清的是整个图库，不是某个作用域的子集，把名字写成
   * {@code resetForRebuild(scope)} 会让人以为可以安全地在共享实例上按作用域重建。
   *
   * <p>安全前置：执行前先在同一事务里读出库内全部 {@code GraphProjectionMeta.scope}，
   * 只要存在本次作用域之外的水位节点就抛 {@link IllegalStateException} 并回滚——
   * 多个作用域共用实例时整库清空会删掉别人的数据，而重建方看不出来。
   *
   * <p>仍必须在图查询已切到 PG 降级路径、且已确认本实例只服务这一个作用域时才调用（ADR-005 §8.2）。
   *
   * @param initialScope 本次重建的作用域；空值时使用配置的 {@code cn.query.graph.scope}
   */
  @Override
  public void resetAllForRebuild(String initialScope) {
    String effectiveScope =
        initialScope == null || initialScope.isBlank() ? this.scope : initialScope;
    runner.write(
        tx -> {
          // 与清空同一事务：先确认独占再删。若存在其他作用域，异常会让整个事务回滚，一行都不删。
          List<String> existingScopes =
              tx.run(READ_ALL_SCOPES).list(record -> record.get("scope").asString());
          requireExclusiveScope(effectiveScope, existingScopes);
          tx.run(DELETE_ALL_NODES).consume();
          return null;
        });
    advanceMeta(GraphProjectionMeta.empty(effectiveScope));
  }

  /**
   * 整库重建的独占性校验：库内只允许存在本次重建的作用域水位。
   *
   * <p>抽成包内静态方法是为了能单独锁住“不要误删别人的作用域”这条规则——它不依赖真实图库，
   * 而一旦写错就是跨环境数据丢失。
   *
   * @param effectiveScope 本次重建作用域
   * @param existingScopes 库内已有的全部 {@code GraphProjectionMeta.scope}
   * @throws IllegalStateException 存在其他作用域时
   */
  static void requireExclusiveScope(String effectiveScope, Collection<String> existingScopes) {
    List<String> foreign = new ArrayList<>();
    if (existingScopes != null) {
      for (String scope : existingScopes) {
        if (scope != null && !scope.equals(effectiveScope)) {
          foreign.add(scope);
        }
      }
    }
    if (!foreign.isEmpty()) {
      foreign.sort(String::compareTo);
      throw new IllegalStateException(
          "拒绝整库清空：图库中存在其他投影作用域 "
              + foreign
              + "（本次重建作用域 "
              + effectiveScope
              + "）。整库重建只能在独占实例上执行；请改用独立实例，或先用对账脚本确认这些作用域已废弃并显式清理。");
    }
  }

  /**
   * 生成无序作者对（规范 ID 升序）。作者数已受阈值约束，最多 200 位 → 19900 对，
   * 分批写入避免单次事务参数过大。
   */
  private List<Map<String, Object>> authorPairs(List<GraphAuthorship> authors) {
    List<String> scholarIds = new ArrayList<>(authors.size());
    for (GraphAuthorship authorship : authors) {
      scholarIds.add(authorship.scholarId());
    }
    scholarIds.sort(String::compareTo);
    List<Map<String, Object>> pairs = new ArrayList<>();
    for (int i = 0; i < scholarIds.size(); i++) {
      for (int j = i + 1; j < scholarIds.size(); j++) {
        String left = scholarIds.get(i);
        String right = scholarIds.get(j);
        if (left.equals(right)) {
          // 同一学者在同一成果的参与关系只应有一条；重复是上游数据问题，不生成自环合作边。
          continue;
        }
        Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("scholarA", left);
        pair.put("scholarB", right);
        pair.put("pairKey", left + "\u0001" + right);
        pairs.add(pair);
      }
    }
    return pairs;
  }

  private static List<List<Map<String, Object>>> partition(List<Map<String, Object>> rows) {
    List<List<Map<String, Object>>> batches = new ArrayList<>();
    for (int start = 0; start < rows.size(); start += BATCH_SIZE) {
      batches.add(rows.subList(start, Math.min(start + BATCH_SIZE, rows.size())));
    }
    return batches;
  }

  private static final String UPSERT_SCHOLARS =
      """
      UNWIND $rows AS row
      MERGE (s:Scholar {scholar_id: row.scholarId})
      WITH s, row, coalesce(s.source_version, -1) AS current
      WHERE current <= row.sourceVersion
      SET s.scholar_name = row.scholarName,
          s.domain = row.domain,
          s.organization = row.organization,
          s.data_origin = row.dataOrigin,
          s.source_version = row.sourceVersion
      RETURN count(s) AS applied
      """;

  private static final String UPSERT_WORK =
      """
      MERGE (w:Work {work_id: $workId})
      ON CREATE SET w.source_version = -1
      WITH w
      WHERE w.source_version <= $sourceVersion
      SET w.title = $title,
          w.is_public = $isPublic,
          w.data_origin = $dataOrigin,
          w.topic_ids = $topicIds,
          w.organizations = $organizations,
          w.author_count = $authorCount,
          w.edges_materialized = $edgesMaterialized,
          w.source_version = $sourceVersion,
          w.updated_at_epoch_ms = timestamp()
      RETURN w.work_id AS workId
      """;

  private static final String DELETE_AUTHORED =
      """
      MATCH (w:Work {work_id: $workId})<-[r:AUTHORED]-()
      DELETE r
      """;

  private static final String DELETE_COOPERATED =
      """
      MATCH (:Scholar)-[c:COOPERATED_WITH]-()
      WHERE c.work_id = $workId
      DELETE c
      """;

  private static final String CREATE_AUTHORED =
      """
      UNWIND $authors AS a
      MATCH (w:Work {work_id: $workId})
      MERGE (s:Scholar {scholar_id: a.scholarId})
      ON CREATE SET s.source_version = 0
      MERGE (s)-[r:AUTHORED {work_id: $workId}]->(w)
      SET r.relation_state = a.relationState,
          r.author_order = a.authorOrder,
          r.work_title = $workTitle
      """;

  private static final String CREATE_COOPERATED =
      """
      UNWIND $pairs AS p
      MATCH (a:Scholar {scholar_id: p.scholarA})
      MATCH (b:Scholar {scholar_id: p.scholarB})
      CREATE (a)-[c:COOPERATED_WITH]->(b)
      SET c.work_id = $workId,
          c.work_title = $workTitle,
          c.pair_key = p.pairKey,
          c.data_origin = $dataOrigin,
          c.source_version = $sourceVersion,
          c.rule_version = $ruleVersion,
          c.is_valid = true
      """;

  private static final String RETIRE_WORK =
      """
      MATCH (w:Work {work_id: $workId})
      WHERE w.source_version <= $sourceVersion
      SET w.is_public = false,
          w.edges_materialized = false,
          w.source_version = $sourceVersion,
          w.updated_at_epoch_ms = timestamp()
      RETURN w.work_id AS workId
      """;

  /** 对账专用：删除 PG 中已不存在的孤立成果节点（{@link #deleteWork}）。 */
  private static final String DELETE_WORK =
      """
      MATCH (w:Work {work_id: $workId})
      DETACH DELETE w
      """;

  private static final String READ_META =
      """
      MATCH (m:GraphProjectionMeta {scope: $scope})
      RETURN m.projected_version AS projectedVersion,
             m.catalog_revision AS catalogRevision,
             m.generation AS generation,
             coalesce(m.updated_at_epoch_ms, 0) AS updatedAtEpochMs
      """;

  /** 读出库内全部投影作用域；整库重建前用它确认独占性。 */
  private static final String READ_ALL_SCOPES =
      """
      MATCH (m:GraphProjectionMeta)
      RETURN m.scope AS scope
      """;

  /** 只有 {@link #resetAllForRebuild(String)} 使用；不在其他地方暴露整库删除。 */
  private static final String DELETE_ALL_NODES = "MATCH (n) DETACH DELETE n";

  private static final String ADVANCE_META =
      """
      MERGE (m:GraphProjectionMeta {scope: $scope})
      ON CREATE SET m.projected_version = -1
      WITH m, m.projected_version AS current
      FOREACH (_ IN CASE WHEN $projectedVersion > current THEN [1] ELSE [] END |
        SET m.projected_version = $projectedVersion,
            m.catalog_revision = $catalogRevision,
            m.generation = $generation,
            m.updated_at_epoch_ms = $updatedAtEpochMs)
      RETURN m.projected_version AS projectedVersion
      """;
}
