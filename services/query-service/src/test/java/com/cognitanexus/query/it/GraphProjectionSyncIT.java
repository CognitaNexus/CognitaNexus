package com.cognitanexus.query.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cognitanexus.query.graph.config.Neo4jGraphProperties;
import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import com.cognitanexus.query.graph.infrastructure.Neo4jGraphProjectionStore;
import com.cognitanexus.query.graph.infrastructure.Neo4jSessionRunner;
import com.cognitanexus.query.graph.projection.application.GraphBackfillResult;
import com.cognitanexus.query.graph.projection.application.GraphBackfillService;
import com.cognitanexus.query.graph.projection.application.GraphEventProjectionResult;
import com.cognitanexus.query.graph.projection.application.GraphEventProjector;
import com.cognitanexus.query.graph.projection.application.GraphProjectionCheckpointStore;
import com.cognitanexus.query.graph.projection.application.GraphProjectionInspector;
import com.cognitanexus.query.graph.projection.application.GraphProjectionProperties;
import com.cognitanexus.query.graph.projection.application.GraphProjectionReconciler;
import com.cognitanexus.query.graph.projection.application.GraphProjectionReconciler.ReconcileOptions;
import com.cognitanexus.query.graph.projection.application.GraphProjectionRetryPolicy;
import com.cognitanexus.query.graph.projection.application.GraphProjectionSource;
import com.cognitanexus.query.graph.projection.application.GraphProjectionSynchronizer;
import com.cognitanexus.query.graph.projection.application.GraphRepairResult;
import com.cognitanexus.query.graph.projection.domain.GraphBackfillStatus;
import com.cognitanexus.query.graph.projection.domain.GraphReconciliationReport;
import com.cognitanexus.query.graph.projection.infrastructure.GraphProjectionCheckpointAdapter;
import com.cognitanexus.query.graph.projection.infrastructure.GraphProjectionCheckpointMapper;
import com.cognitanexus.query.graph.projection.infrastructure.GraphProjectionSourceAdapter;
import com.cognitanexus.query.graph.projection.infrastructure.GraphProjectionSourceMapper;
import com.cognitanexus.query.graph.projection.infrastructure.Neo4jGraphProjectionInspector;
import com.cognitanexus.query.platform.persistence.JsonCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mybatis.spring.SqlSessionTemplate;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

/**
 * 阶段四：PostgreSQL → Neo4j 投影同步的真实集成验证。
 *
 * <p>为什么必须真实：本 PR 的结论全部落在「真实 SQL 取到的四表集合」「真实 Cypher 写出来的节点与
 * 边」「Flyway V7 的断点表」上。用内存假实现只能证明控制流，证明不了
 * {@code qry_contribution_org}/{@code qry_achievement_topic} 的集合等价、{@code source_version}
 * 版本守卫真的生效、以及断点能在事务提交后读回。
 *
 * <p>环境隔离（避免与并行任务相互干扰）：
 *
 * <ul>
 *   <li>PostgreSQL 使用<b>独立数据库</b> {@code query_graph_it}（不是其它 IT 共用的
 *       {@code query_it}），首次运行自动创建并执行 V1..V7 全部迁移；
 *   <li>Neo4j 用本机隔离实例 {@code bolt://127.0.0.1:17687}，所有节点标识带本次运行前缀
 *       {@code it-gp-xxxx}，结束按前缀清理；开始前若发现库里已有<b>非本次前缀</b>的 Work 节点，
 *       整类跳过并说明原因（计数级对账在共享实例上没有意义）；
 *   <li>实例不可达时整类跳过，<b>不把跳过报告成通过</b>。
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GraphProjectionSyncIT {

  private static final String URI =
      System.getProperty(
          "cn.neo4j.uri", System.getenv().getOrDefault("CN_NEO4J_URI", "bolt://127.0.0.1:17687"));
  private static final String USER =
      System.getProperty("cn.neo4j.user", System.getenv().getOrDefault("CN_NEO4J_USER", "neo4j"));
  private static final String PASSWORD =
      System.getProperty(
          "cn.neo4j.password",
          System.getenv().getOrDefault("CN_NEO4J_PASSWORD", "cn_test_neo4j_2026"));

  private static final String PG_HOST = "127.0.0.1";
  private static final int PG_PORT = 55432;
  private static final String DB_NAME = "query_graph_it";
  private static final String DB_URL =
      "jdbc:postgresql://" + PG_HOST + ":" + PG_PORT + "/" + DB_NAME;

  private static final String PREFIX = "it-gp-" + UUID.randomUUID().toString().substring(0, 8);
  private static final String SCOPE = PREFIX + "-scope";
  private static final String GEN = "gen-" + PREFIX;
  private static final String CONSUMER = "consumer-" + PREFIX;

  private static HikariDataSource dataSource;
  private static Driver driver;
  private static Neo4jSessionRunner runner;
  private static Neo4jGraphProjectionStore store;
  private static GraphProjectionSource source;
  private static GraphProjectionSourceMapper sourceMapper;
  private static GraphProjectionCheckpointMapper checkpointMapper;
  private static GraphProjectionInspector inspector;
  private static GraphProjectionSynchronizer synchronizer;
  private static GraphProjectionProperties properties;
  private static GraphBackfillService backfillService;
  private static GraphEventProjector projector;
  private static GraphProjectionReconciler reconciler;

  @BeforeAll
  static void setUp() {
    // ---- Neo4j ----
    Neo4jGraphProperties neo4jProperties =
        new Neo4jGraphProperties(
            true,
            URI,
            USER,
            PASSWORD,
            "neo4j",
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            20,
            50,
            20_000,
            100_000,
            2_000L,
            SCOPE,
            true);
    try {
      driver = GraphDatabase.driver(URI, org.neo4j.driver.AuthTokens.basic(USER, PASSWORD));
      driver.verifyConnectivity();
    } catch (RuntimeException ex) {
      Assumptions.abort(
          "Neo4j 集成测试实例不可用（"
              + URI
              + "）："
              + ex.getClass().getSimpleName()
              + "。启动方式见 ops/compose/compose.test-infra.yaml 与 ops/verify/test-infra-smoke.sh。");
    }
    runner = new Neo4jSessionRunner(driver, neo4jProperties);
    store = new Neo4jGraphProjectionStore(runner, neo4jProperties);
    inspector = new Neo4jGraphProjectionInspector(runner);

    // ---- PostgreSQL（独立库，避免与其他 IT 的 query_it 相互清库）----
    ensureDatabase();
    migrate();
    dataSource = newPool();
    SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory(dataSource));
    sourceMapper = template.getMapper(GraphProjectionSourceMapper.class);
    checkpointMapper = template.getMapper(GraphProjectionCheckpointMapper.class);
    source = new GraphProjectionSourceAdapter(sourceMapper);
    GraphProjectionCheckpointStore checkpoints =
        new GraphProjectionCheckpointAdapter(checkpointMapper);

    // ---- 装配被测服务 ----
    properties =
        new GraphProjectionProperties(true, 2, 3, 0L, 100L, false, 5_000L, 200, 500, 10, true);
    synchronizer =
        new GraphProjectionSynchronizer(
            source, store, GraphProjectionRetryPolicy.noRetry(), millis -> {});
    backfillService =
        new GraphBackfillService(source, synchronizer, store, checkpoints, properties, CONSUMER);
    projector =
        new GraphEventProjector(
            source, synchronizer, store, new JsonCodec(new ObjectMapper()), CONSUMER);
    reconciler =
        new GraphProjectionReconciler(source, inspector, synchronizer, store, 20, CONSUMER);

    // 共享实例上做计数级对账没有意义：只要存在不属于本测试族的图数据，整类跳过并说明。
    // 本测试的成果标识以 w-0 开头、水位作用域以 it-gp- 开头；其它形态一律视为别人的数据。
    long foreignWorks =
        runner
            .query(
                "MATCH (w:Work) "
                    + "WHERE NOT w.work_id STARTS WITH 'w-0' AND NOT w.work_id STARTS WITH 'it-gp-' "
                    + "RETURN count(w) AS total",
                Map.of(),
                record -> record.get("total").asLong())
            .get(0);
    long foreignMeta =
        runner
            .query(
                "MATCH (m:GraphProjectionMeta) WHERE NOT m.scope STARTS WITH 'it-gp-' "
                    + "RETURN count(m) AS total",
                Map.of(),
                record -> record.get("total").asLong())
            .get(0);
    Assumptions.assumeTrue(
        foreignWorks == 0 && foreignMeta == 0,
        "Neo4j 实例上存在 "
            + foreignWorks
            + " 个外来 Work 节点/ "
            + foreignMeta
            + " 个外来投影水位：计数级对账与整库重建在共享实例上不成立，"
            + "请先清空隔离实例（或等待并行任务结束后重跑）。");

    // 独占实例：清空遗留的图数据，保证每个用例从同一基线开始（图是可丢弃的派生投影）。
    wipeGraph();
    cleanupPostgres();
  }

  @AfterAll
  static void tearDown() {
    try {
      wipeGraph();
      cleanupPostgres();
    } finally {
      if (dataSource != null) {
        dataSource.close();
      }
      if (driver != null) {
        driver.close();
      }
    }
  }

  @BeforeEach
  void seed() {
    wipeGraph();
    cleanupPostgres();
    seedConsumerState();
  }

  // ---------------------------------------------------------------------------
  // Backfill
  // ---------------------------------------------------------------------------

  @Test
  @Order(1)
  @DisplayName("分批 Backfill：成果/作者/领域/机构与 PG 一致，断点落库并完成")
  void backfillCopiesAuthoritativeProjectionIntoGraph() throws SQLException {
    seedWork("w-0001", "PAPER", "论文一", 2, "AUTO", "SELF_CONFIRMED");
    seedWork("w-0002", "PATENT", "专利二", 3, "AUTO", "AUTO", "AUTO");
    // 作者数超过物化阈值（20）的成果：只写 AUTHORED，不物化作者对边
    seedWork("w-0003", "PAPER", "大协作论文", 22, "AUTO");
    // 不合格成果：可见性收紧，必须落墓碑并清掉合作边
    seedWork("w-0004", "PAPER", "已撤销论文", 2, "AUTO");
    execute(
        "UPDATE qry_achievement SET visibility = 'RESTRICTED', aggregate_version = 9 WHERE achievement_id = ?",
        "w-0004");
    // 未确认关系不得进入图（PENDING_VERIFY）
    seedContribution("w-0002", "s-pending", "待核验", "PENDING_VERIFY", 4);
    seedTopic("w-0001", "t-graph");
    seedTopic("w-0001", "t-search");
    seedOrganization("w-0001", "s-1", "清华大学");
    seedOrganization("w-0001", "s-2", "北京大学");

    GraphBackfillResult result = backfillService.backfill(SCOPE);

    assertThat(result.status()).isEqualTo(GraphBackfillStatus.COMPLETED);
    assertThat(result.processedWorks()).isEqualTo(4);
    // batchSize=2 → 4 条正好两批
    assertThat(result.processedBatches()).isEqualTo(2);
    assertThat(result.failedWorks()).isZero();

    GraphProjectionInspector.GraphCounts counts = inspector.counts(null);
    assertThat(counts.publicWorks()).isEqualTo(3);
    // 合格作者：w-0001 两位、w-0002 三位、w-0003 22 位（PENDING 不计）
    assertThat(counts.authoredRelations()).isEqualTo(2 + 3 + 22);
    assertThat(counts.scholars()).isEqualTo(2 + 3 + 22);
    // 物化边：w-0001 = 1 对，w-0002 = 3 对，w-0003 超阈值不物化
    assertThat(counts.materializedEdges()).isEqualTo(1 + 3);

    // 领域/机构集合必须与 PG 完全一致（ADR-005 §6.2 筛选等价前提）
    Map<String, Object> work1 =
        runner
            .query(
                "MATCH (w:Work {work_id: 'w-0001'}) "
                    + "RETURN w.topic_ids AS topics, w.organizations AS orgs, "
                    + "w.data_origin AS origin, w.is_public AS isPublic, "
                    + "w.edges_materialized AS materialized",
                Map.of(),
                record -> {
                  Map<String, Object> row = new java.util.LinkedHashMap<>();
                  row.put("topics", record.get("topics").asList());
                  row.put("orgs", record.get("orgs").asList());
                  row.put("origin", record.get("origin").asString());
                  row.put("isPublic", record.get("isPublic").asBoolean());
                  row.put("materialized", record.get("materialized").asBoolean());
                  return row;
                })
            .get(0);
    assertThat(stringList(work1.get("topics"))).containsExactlyInAnyOrder("t-graph", "t-search");
    assertThat(stringList(work1.get("orgs"))).containsExactlyInAnyOrder("清华大学", "北京大学");
    assertThat(work1.get("origin")).isEqualTo("REAL");
    assertThat(work1.get("isPublic")).isEqualTo(Boolean.TRUE);
    assertThat(work1.get("materialized")).isEqualTo(Boolean.TRUE);

    // 从未入图的不合格成果：图上既没有节点，也没有合作边。
    // 墓碑（is_public=false）只属于「曾投影、后来收紧」的成果——见 retireOnVisibilityTightened
    // 用例；本用例的 w-0004 在首次 Backfill 之前就已 RESTRICTED，因此 {@code retireWork} 的
    // MATCH 按设计不返回行（同步结果是 RETIRED 未生效）。凭空造一个墓碑节点会让图里出现
    // PG 从未投影过的成果，也会让对账的孤立扫描多出一条无意义差异。
    Long work4Nodes =
        runner
            .query(
                "MATCH (w:Work {work_id: 'w-0004'}) RETURN count(w) AS total",
                Map.of(),
                record -> record.get("total").asLong())
            .get(0);
    assertThat(work4Nodes).as("从未入图的不合格成果不得落节点").isZero();
    Long work4Edges =
        runner
            .query(
                "MATCH (:Scholar)-[c:COOPERATED_WITH]->() WHERE c.work_id = 'w-0004' "
                    + "RETURN count(c) AS total",
                Map.of(),
                record -> record.get("total").asLong())
            .get(0);
    assertThat(work4Edges).as("不合格成果不得留下任何合作边").isZero();
    // 图里应当只有 3 个 Work 节点（w-0004 既不公开、也不以墓碑形式存在）
    Long totalWorkNodes =
        runner
            .query(
                "MATCH (w:Work) RETURN count(w) AS total",
                Map.of(),
                record -> record.get("total").asLong())
            .get(0);
    assertThat(totalWorkNodes).isEqualTo(3L);

    // 每一条合作边都带 work_id/title 证据（可追溯）
    Long evidenceMissing =
        runner
            .query(
                "MATCH (:Scholar)-[c:COOPERATED_WITH]->() "
                    + "WHERE c.work_id STARTS WITH $prefix AND (c.work_title IS NULL OR c.work_title = '') "
                    + "RETURN count(c) AS total",
                Map.of("prefix", PREFIX),
                record -> record.get("total").asLong())
            .get(0);
    assertThat(evidenceMissing).isZero();

    // 断点已落库（V7 表），可读回
    var checkpoint = checkpointMapper.find(SCOPE);
    assertThat(checkpoint).isNotNull();
    assertThat(checkpoint.status()).isEqualTo("COMPLETED");
    assertThat(checkpoint.processedWorks()).isEqualTo(4);

    // 水位推进到快照修订（published_revision = 40）
    GraphProjectionMeta meta = store.readMeta(SCOPE).orElseThrow();
    assertThat(meta.projectedVersion()).isEqualTo(40L);
    assertThat(meta.generation()).isEqualTo(GEN);
  }

  @Test
  @Order(2)
  @DisplayName("重复 Backfill：同版本重放幂等，节点与边数量不变")
  void repeatedBackfillIsIdempotent() throws SQLException {
    seedWork("w-0001", "PAPER", "论文一", 2, "AUTO", "AUTO");
    seedWork("w-0002", "PAPER", "论文二", 2, "AUTO", "AUTO");
    backfillService.backfill(SCOPE);
    GraphProjectionInspector.GraphCounts first = inspector.counts(null);

    GraphBackfillResult second = backfillService.backfill(SCOPE);
    GraphProjectionInspector.GraphCounts after = inspector.counts(null);

    // 断点已 COMPLETED：第二次直接返回，不重扫
    assertThat(second.worksThisRun()).isZero();
    assertThat(after).isEqualTo(first);
    // 若强行再全量重放一次（重建路径），结果必须仍然相同
    backfillService.rebuild(SCOPE);
    GraphProjectionInspector.GraphCounts rebuilt = inspector.counts(null);
    assertThat(rebuilt).isEqualTo(first);
  }

  // ---------------------------------------------------------------------------
  // 增量（事件）
  // ---------------------------------------------------------------------------

  @Test
  @Order(3)
  @DisplayName("增量：事件把 PG 变更写进图，重复投递同一事件不产生第二次副作用")
  void incrementalEventsAreIdempotent() throws SQLException {
    seedWork("w-0101", "PAPER", "初始论文", 2, "AUTO", "AUTO");
    backfillService.backfill(SCOPE);
    long revision = 40L;

    // 作者数 +1 的更新（aggregate_version 前进），并产生一条已应用事件
    seedContribution("w-0101", "s-new", "新合作者", "SELF_CONFIRMED", 3);
    execute("UPDATE qry_achievement SET aggregate_version = 55 WHERE achievement_id = ?", "w-0101");
    appendAppliedEvent(
        "evt-" + PREFIX + "-1",
        "CatalogChangeCommitted",
        revision + 5,
        "{\"changeKind\":\"UPSERT\",\"achievementId\":\"w-0101\"}");

    GraphEventProjectionResult applied = projector.applyPending(SCOPE, 50);

    assertThat(applied.worksApplied()).isEqualTo(1);
    assertThat(applied.projectedRevision()).isEqualTo(revision + 5);
    assertThat(inspector.counts(null).authoredRelations()).isEqualTo(3);
    assertThat(inspector.counts(null).materializedEdges()).isEqualTo(3);

    // 重复消费：水位已覆盖该修订，事件不会被再次读取
    GraphEventProjectionResult second = projector.applyPending(SCOPE, 50);
    assertThat(second.eventsRead()).isZero();
    assertThat(inspector.counts(null).materializedEdges()).isEqualTo(3);
  }

  @Test
  @Order(4)
  @DisplayName("迟到事件：图里已是新版本时旧版本写入被拒绝（不回退到旧作者集合）")
  void lateVersionIsRejectedByVersionGuard() throws SQLException {
    seedWork("w-0201", "PAPER", "版本论文", 2, "AUTO", "AUTO");
    backfillService.backfill(SCOPE);
    GraphProjectionMeta before = store.readMeta(SCOPE).orElseThrow();
    assertThat(before.projectedVersion()).isEqualTo(40L);

    // 图里先写入 v60（回填读到了更新的行），随后 PG 因回放/乱序读到 v50 的旧值
    seedContribution("w-0201", "s-late", "后加入者", "AUTO", 3);
    execute("UPDATE qry_achievement SET aggregate_version = 60 WHERE achievement_id = ?", "w-0201");
    synchronizer.syncWork(GEN, "w-0201");
    long authorsAfterNewest = inspector.counts(null).authoredRelations();

    // 旧版本（v50）被写回：版本守卫必须拒绝，作者数不变
    execute("UPDATE qry_achievement SET aggregate_version = 50 WHERE achievement_id = ?", "w-0201");
    var outcome = synchronizer.syncWork(GEN, "w-0201").orElseThrow();

    assertThat(outcome.action().name()).isEqualTo("STALE_IGNORED");
    assertThat(inspector.counts(null).authoredRelations()).isEqualTo(authorsAfterNewest);
    long storedVersion =
        runner
            .query(
                "MATCH (w:Work {work_id: 'w-0201'}) RETURN w.source_version AS v",
                Map.of(),
                record -> record.get("v").asLong())
            .get(0);
    assertThat(storedVersion).isEqualTo(60L);
  }

  @Test
  @Order(5)
  @DisplayName("撤销公开：收紧事件让成果退场并删除其物化合作边（不留幽灵边）")
  void tightenedWorkRetiresAndDropsEdges() throws SQLException {
    seedWork("w-0301", "PAPER", "将撤销论文", 3, "AUTO", "AUTO", "AUTO");
    seedWork("w-0302", "PAPER", "保留论文", 2, "AUTO", "AUTO");
    backfillService.backfill(SCOPE);
    assertThat(inspector.counts(null).materializedEdges()).isEqualTo(3 + 1);

    // PG 侧收紧 + 版本前进，并记录已应用事件
    execute(
        "UPDATE qry_achievement SET visibility = 'RESTRICTED', aggregate_version = 70 "
            + "WHERE achievement_id = ?",
        "w-0301");
    appendAppliedEvent(
        "evt-" + PREFIX + "-2",
        "PublicVisibilityTightened",
        45,
        "{\"tightenedAchievementIds\":[\"w-0301\"]}");

    GraphEventProjectionResult result = projector.applyPending(SCOPE, 50);

    assertThat(result.worksRetired()).isEqualTo(1);
    assertThat(inspector.counts(null).publicWorks()).isEqualTo(1);
    assertThat(inspector.counts(null).materializedEdges()).isEqualTo(1);
    long ghostEdges =
        runner
            .query(
                "MATCH (:Scholar)-[c:COOPERATED_WITH]->() WHERE c.work_id = 'w-0301' "
                    + "RETURN count(c) AS total",
                Map.of(),
                record -> record.get("total").asLong())
            .get(0);
    assertThat(ghostEdges).isZero();
  }

  // ---------------------------------------------------------------------------
  // 断点续跑与重建
  // ---------------------------------------------------------------------------

  @Test
  @Order(6)
  @DisplayName("中断后续跑：断点停在中间批次，续跑只处理剩余成果")
  void backfillResumesFromCheckpoint() throws SQLException {
    for (int i = 1; i <= 5; i++) {
      seedWork(String.format("w-04%02d", i), "PAPER", "续跑论文" + i, 2, "AUTO", "AUTO");
    }
    InterruptingSource interrupting = new InterruptingSource(source, 2);
    GraphBackfillService firstRun =
        new GraphBackfillService(
            interrupting,
            synchronizer,
            store,
            new GraphProjectionCheckpointAdapter(checkpointMapper),
            properties,
            CONSUMER);

    assertThatThrownBy(() -> firstRun.backfill(SCOPE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("模拟中断");
    var interrupted = checkpointMapper.find(SCOPE);
    assertThat(interrupted.status()).isEqualTo("FAILED");
    assertThat(interrupted.cursorWorkId()).isEqualTo("w-0402");
    assertThat(interrupted.processedWorks()).isEqualTo(2);

    GraphBackfillResult resumed = backfillService.backfill(SCOPE);

    assertThat(resumed.status()).isEqualTo(GraphBackfillStatus.COMPLETED);
    assertThat(resumed.worksThisRun()).isEqualTo(3);
    assertThat(resumed.processedWorks()).isEqualTo(5);
    assertThat(inspector.counts(null).publicWorks()).isEqualTo(5);
  }

  @Test
  @Order(7)
  @DisplayName("受控重建：清空图库后全量重灌，孤立成果消失、水位回到快照修订")
  void rebuildWipesAndRefills() throws SQLException {
    seedWork("w-0501", "PAPER", "重建论文", 2, "AUTO", "AUTO");
    backfillService.backfill(SCOPE);
    // 人工注入孤立节点：模拟历史残留（图里有、PG 已无）
    runner.write(
        tx -> {
          tx.run(
                  "CREATE (:Work {work_id: $id, title: 'ghost', is_public: true, "
                      + "data_origin: 'REAL', source_version: 1})",
                  Map.of("id", PREFIX + "-ghost"))
              .consume();
          return null;
        });
    assertThat(inspector.counts(null).publicWorks()).isEqualTo(2);

    GraphBackfillResult result = backfillService.rebuild(SCOPE);

    assertThat(result.status()).isEqualTo(GraphBackfillStatus.COMPLETED);
    assertThat(inspector.counts(null).publicWorks()).isEqualTo(1);
    assertThat(inspector.publicWorkIds(10)).containsExactly("w-0501");
    assertThat(store.readMeta(SCOPE).orElseThrow().projectedVersion()).isEqualTo(40L);
  }

  // ---------------------------------------------------------------------------
  // 对账
  // ---------------------------------------------------------------------------

  @Test
  @Order(8)
  @DisplayName("对账：一致时无差异；人工注入四类漂移后逐类检出，修复后回到一致")
  void reconciliationDetectsAndRepairsDrift() throws SQLException {
    seedWork("w-0601", "PAPER", "对账论文一", 3, "AUTO", "AUTO", "AUTO");
    seedWork("w-0602", "PAPER", "对账论文二", 2, "AUTO", "AUTO");
    seedWork("w-0603", "PATENT", "对账专利", 2, "AUTO", "AUTO");
    backfillService.backfill(SCOPE);

    GraphReconciliationReport consistent = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());
    assertThat(consistent.findings()).isEmpty();
    assertThat(consistent.consistent()).isTrue();
    assertThat(consistent.scanTruncated()).isFalse();

    // 漂移 1：图上删掉一个成果节点（模拟读写不一致/误删）
    runner.write(
        tx -> {
          tx.run("MATCH (w:Work {work_id: 'w-0602'}) DETACH DELETE w", Map.of()).consume();
          return null;
        });
    // 漂移 2：给一个成果补一条该成果本不该有的作者对边（模拟历史残留的跨成果脏边）。
    // 注入的必须是「本成果作者对集合之外」的一对：重复注入一条已存在的作者对虽然会让边数 +1，
    // 但作者对<b>集合</b>不变，对账会假阴性——那正是这个用例要证明检测得出来的东西。
    runner.write(
        tx -> {
          tx.run(
                  "MATCH (a:Scholar {scholar_id: 's-0601-1'}), (b:Scholar {scholar_id: 's-0603-1'}) "
                      + "CREATE (a)-[:COOPERATED_WITH {work_id: 'w-0601', work_title: '对账论文一', "
                      + "pair_key: 's-0601-1\\u0001s-0603-1', data_origin: 'REAL', "
                      + "source_version: 1, rule_version: 'v1', is_valid: true}]->(b)",
                  Map.of())
              .consume();
          return null;
        });
    // 注入必须真的落地（端点写错时 CREATE 会静默不建边，后面的断言就失去意义）
    Long driftedWork1Edges =
        runner
            .query(
                "MATCH (:Scholar)-[c:COOPERATED_WITH]->() WHERE c.work_id = 'w-0601' "
                    + "RETURN count(c) AS total",
                Map.of(),
                record -> record.get("total").asLong())
            .get(0);
    assertThat(driftedWork1Edges).as("3 条正常作者对 + 1 条注入的脏边").isEqualTo(4L);
    // 漂移 3：标题过期（边的证据标题与成果当前标题不一致）
    execute("UPDATE qry_achievement SET title = '对账论文三（改名）' WHERE achievement_id = ?", "w-0603");

    // 漂移 4：图里多出一个 PG 中不存在的成果（孤立节点 → 修复时必须退场）
    runner.write(
        tx -> {
          tx.run(
                  "CREATE (:Work {work_id: $id, title: 'orphan', is_public: true, "
                      + "data_origin: 'REAL', source_version: 1, edges_materialized: false})",
                  Map.of("id", "w-0609"))
              .consume();
          return null;
        });

    GraphReconciliationReport drifted = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());

    assertThat(drifted.hasDrift()).isTrue();
    assertThat(drifted.consistent()).isFalse();
    List<String> codes = drifted.findings().stream().map(item -> item.code()).toList();
    // 四类漂移必须分别报出。注意实际代码表：计数类是 WORK/SCHOLAR/AUTHORED/EDGE_COUNT_MISMATCH，
    // 集合类是 WORK_MISSING_IN_GRAPH / WORK_ORPHANED_IN_GRAPH / EDGE_SET_MISMATCH。
    assertThat(codes)
        .contains(
            "SCHOLAR_COUNT_MISMATCH",
            "AUTHORED_COUNT_MISMATCH",
            "EDGE_COUNT_MISMATCH",
            "WORK_MISSING_IN_GRAPH",
            "WORK_ORPHANED_IN_GRAPH",
            "EDGE_SET_MISMATCH",
            "WORK_TITLE_MISMATCH",
            "EVIDENCE_TITLE_STALE");
    // 本用例特意让「少一个 w-0602 + 多一个 w-0609」相互抵消：总数相等≠一致，
    // 所以 WORK_COUNT_MISMATCH 不出现，遗漏必须由集合级扫描（MISSING/ORPHANED）兜住。
    // 这一条断言就是在钉住「只比总数」不足以为证（ADR-005 §6.2）。
    assertThat(codes).doesNotContain("WORK_COUNT_MISMATCH");
    assertThat(drifted.missingWorks()).contains("w-0602");
    assertThat(drifted.orphanedWorks()).contains("w-0609");
    assertThat(
            drifted.countBySeverity(
                com.cognitanexus.query.graph.projection.domain.GraphReconciliationFinding.Severity
                    .ERROR))
        .isGreaterThanOrEqualTo(5L);

    GraphRepairResult repair = reconciler.repair(drifted);

    assertThat(repair.failedWorks()).isZero();
    assertThat(repair.repairedWorks()).isGreaterThanOrEqualTo(2);
    assertThat(repair.retiredWorks()).isEqualTo(1);
    GraphReconciliationReport after = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());
    assertThat(after.findings()).isEmpty();
    assertThat(after.consistent()).isTrue();
  }

  @Test
  @Order(9)
  @DisplayName("对账拒绝在不一致时宣称可用：水位落后单独报 WARN，不伪造追平")
  void watermarkBehindIsReportedWithoutFaking() throws SQLException {
    seedWork("w-0701", "PAPER", "水位论文", 2, "AUTO", "AUTO");
    backfillService.backfill(SCOPE);
    execute(
        "UPDATE qry_consumer_state SET published_revision = 99 WHERE consumer_id = ?", CONSUMER);

    GraphReconciliationReport report = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());

    assertThat(report.watermarkBehind()).isTrue();
    assertThat(report.consistent()).isFalse();
    assertThat(
            report.countBySeverity(
                com.cognitanexus.query.graph.projection.domain.GraphReconciliationFinding.Severity
                    .WARN))
        .isEqualTo(1);
    // 数据本身仍自洽：环
    assertThat(report.hasDrift()).isFalse();
  }

  // ---------------------------------------------------------------------------
  // 孤立退场与恢复
  // ---------------------------------------------------------------------------

  @Test
  @Order(10)
  @DisplayName("孤立成果退场后可恢复：PG 以较低正常版本重新公开时仍能写回图（不用哨兵版本号）")
  void retiredOrphanCanBeRestoredAtNormalVersion() throws SQLException {
    seedWork("w-0801", "PAPER", "基线论文", 2, "AUTO", "AUTO");
    backfillService.backfill(SCOPE);

    // 图里有一条 PG 中不存在的成果，且它的 source_version 比日后 PG 的正常版本更高（40 > 15）——
    // 这正是「用 Long.MAX_VALUE 退场」与「用真实版本退场」都能通过、但只有删除能恢复的情形。
    runner.write(
        tx -> {
          tx.run(
                  "CREATE (:Work {work_id: 'w-0802', title: '幽灵成果', is_public: true, "
                      + "data_origin: 'REAL', source_version: 40, edges_materialized: false})",
                  Map.of())
              .consume();
          return null;
        });

    GraphReconciliationReport report = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());
    assertThat(report.orphanedWorks()).contains("w-0802");

    GraphRepairResult repair = reconciler.repair(report);

    assertThat(repair.retiredWorks()).isEqualTo(1);
    assertThat(workNodeCount("w-0802")).as("PG 无此行 → 节点与边一起删除").isZero();

    // PG 日后以正常聚合版本（15 < 40）重新收录同一 workId。
    seedWork("w-0802", "PAPER", "重新公开的论文", 2, "AUTO", "AUTO");
    execute(
        "UPDATE qry_achievement SET aggregate_version = 15 WHERE generation = ? "
            + "AND achievement_id = ?",
        GEN,
        "w-0802");

    var outcome = synchronizer.syncWork(GEN, "w-0802").orElseThrow();

    assertThat(outcome.action())
        .as("退场若把 source_version 抬到 Long.MAX_VALUE，这里会永久变成 STALE_IGNORED")
        .isEqualTo(
            com.cognitanexus.query.graph.projection.application.GraphSyncOutcome.Action.APPLIED);
    assertThat(isPublic("w-0802")).isTrue();
    assertThat(cooperatedEdges("w-0802")).as("两位作者 → 1 条物化合作边").isEqualTo(1L);
  }

  // ---------------------------------------------------------------------------
  // 数据准备与清理
  // ---------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static List<String> stringList(Object value) {
    return (List<String>) value;
  }

  private static void seedConsumerState() {
    execute(
        "INSERT INTO qry_generation (generation, status, ready_at, note) VALUES (?, 'READY', now(), 'IT') "
            + "ON CONFLICT (generation) DO NOTHING",
        GEN);
    execute(
        "INSERT INTO qry_consumer_state (consumer_id, active_generation, received_revision, "
            + "published_revision) VALUES (?, ?, 40, 40) "
            + "ON CONFLICT (consumer_id) DO UPDATE SET active_generation = EXCLUDED.active_generation, "
            + "published_revision = 40",
        CONSUMER,
        GEN);
  }

  /** 造一条合格成果；作者标识由成果号派生，避免不同用例互相影响。 */
  private static void seedWork(
      String workId, String type, String title, int authorCount, String... states)
      throws SQLException {
    execute(
        "INSERT INTO qry_achievement (generation, achievement_id, type, title, title_norm, "
            + "data_nature, visibility, effective_status, integrity_status, publication_year, "
            + "aggregate_version, revision) "
            + "VALUES (?, ?, ?, ?, ?, 'REAL', 'PUBLIC', 'EFFECTIVE', 'NONE', 2024, 10, 10) "
            + "ON CONFLICT (generation, achievement_id) DO NOTHING",
        GEN,
        workId,
        type,
        title,
        title.toLowerCase());
    for (int i = 1; i <= authorCount; i++) {
      String scholarId = scholarIdOf(workId, i);
      String state = states.length == 0 ? "AUTO" : states[(i - 1) % states.length];
      seedContribution(workId, scholarId, "学者" + workId + "-" + i, state, i);
    }
  }

  private static String scholarIdOf(String workId, int index) {
    return "s-" + workId.substring(workId.indexOf('-') + 1) + "-" + index;
  }

  private static long workNodeCount(String workId) {
    return runner
        .query(
            "MATCH (w:Work {work_id: $id}) RETURN count(w) AS total",
            Map.of("id", workId),
            record -> record.get("total").asLong())
        .get(0);
  }

  private static boolean isPublic(String workId) {
    return runner
        .query(
            "MATCH (w:Work {work_id: $id}) RETURN w.is_public AS flag",
            Map.of("id", workId),
            record -> record.get("flag").asBoolean())
        .get(0);
  }

  private static long cooperatedEdges(String workId) {
    return runner
        .query(
            "MATCH (:Scholar)-[c:COOPERATED_WITH]->() WHERE c.work_id = $id "
                + "RETURN count(c) AS total",
            Map.of("id", workId),
            record -> record.get("total").asLong())
        .get(0);
  }

  private static void seedContribution(
      String workId, String scholarId, String name, String state, int order) throws SQLException {
    execute(
        "INSERT INTO qry_contribution (generation, achievement_id, scholar_id, scholar_name, role, "
            + "author_order, relation_state) VALUES (?, ?, ?, ?, 'AUTHOR', ?, ?) "
            + "ON CONFLICT (generation, achievement_id, scholar_id) DO UPDATE SET "
            + "relation_state = EXCLUDED.relation_state, author_order = EXCLUDED.author_order",
        GEN,
        workId,
        scholarId,
        name,
        order,
        state);
  }

  private static void seedTopic(String workId, String topicId) throws SQLException {
    execute(
        "INSERT INTO qry_achievement_topic (generation, achievement_id, topic_id, canonical_name) "
            + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
        GEN,
        workId,
        topicId,
        topicId);
  }

  private static void seedOrganization(String workId, String scholarId, String organization)
      throws SQLException {
    execute(
        "INSERT INTO qry_contribution_org (generation, achievement_id, scholar_id, organization) "
            + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
        GEN,
        workId,
        scholarId,
        organization);
  }

  private static void appendAppliedEvent(
      String eventId, String eventType, long revision, String payload) {
    execute(
        "INSERT INTO qry_inbox_event (id, consumer_id, event_id, event_type, schema_version, "
            + "aggregate_type, aggregate_id, aggregate_version, catalog_revision, payload, "
            + "processing_state, applied_at) "
            + "VALUES (?, ?, ?, ?, 1, 'ACHIEVEMENT', ?, 1, ?, ?::jsonb, 'APPLIED', now()) "
            + "ON CONFLICT (consumer_id, event_id) DO NOTHING",
        "it-" + eventId,
        CONSUMER,
        eventId,
        eventType,
        eventId,
        revision,
        payload);
  }

  private static void execute(String sql, Object... parameters) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.execute();
    } catch (SQLException ex) {
      throw new IllegalStateException("IT 数据准备失败：" + sql, ex);
    }
  }

  /**
   * 清空图库（仅限本测试确认独占的实例）。
   *
   * <p>只删除本测试关心的三类节点：{@code Work}、{@code Scholar}、{@code GraphProjectionMeta}。
   * 图是派生读投影，清空不需要保留任何「权威」数据；PG 侧才是事实。
   */
  private static void wipeGraph() {
    runner.write(
        tx -> {
          tx.run("MATCH (w:Work) DETACH DELETE w", Map.of()).consume();
          tx.run("MATCH (s:Scholar) DETACH DELETE s", Map.of()).consume();
          tx.run("MATCH (m:GraphProjectionMeta) DELETE m", Map.of()).consume();
          return null;
        });
  }

  private static void cleanupPostgres() {
    // 只删本测试自己的 generation / consumer / 断点；不 TRUNCATE，不触碰其它数据。
    try {
      execute("DELETE FROM qry_inbox_event WHERE consumer_id = ?", CONSUMER);
      execute("DELETE FROM qry_graph_projection_checkpoint WHERE scope LIKE 'it-gp-%'");
      execute("DELETE FROM qry_coauthor_edge WHERE generation = ?", GEN);
      execute("DELETE FROM qry_achievement_topic WHERE generation = ?", GEN);
      execute("DELETE FROM qry_contribution_org WHERE generation = ?", GEN);
      execute("DELETE FROM qry_contribution WHERE generation = ?", GEN);
      execute("DELETE FROM qry_achievement WHERE generation = ?", GEN);
      execute("DELETE FROM qry_consumer_state WHERE consumer_id = ?", CONSUMER);
      execute("DELETE FROM qry_generation WHERE generation = ?", GEN);
    } catch (RuntimeException ignored) {
      // 库可能尚未迁移（首次运行 setUp 期间）：此时没有可清理的数据。
    }
  }

  private static void ensureDatabase() {
    String adminUrl = "jdbc:postgresql://" + PG_HOST + ":" + PG_PORT + "/postgres";
    try (Connection connection = DriverManager.getConnection(adminUrl, "postgres", "postgres_it")) {
      try (PreparedStatement query =
          connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?"); ) {
        query.setString(1, DB_NAME);
        try (ResultSet result = query.executeQuery()) {
          if (result.next()) {
            return;
          }
        }
      }
      try (PreparedStatement create = connection.prepareStatement("CREATE DATABASE " + DB_NAME)) {
        create.execute();
      }
    } catch (SQLException ex) {
      Assumptions.abort(
          "集成测试 PostgreSQL 不可用（"
              + PG_HOST
              + ":"
              + PG_PORT
              + "）："
              + ex.getMessage()
              + "。先执行 bash ops/local/test-db.sh start。");
    }
  }

  private static void migrate() {
    Flyway.configure()
        .dataSource(DB_URL, "postgres", "postgres_it")
        .locations("classpath:db/migration")
        .baselineOnMigrate(false)
        .validateOnMigrate(true)
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  private static HikariDataSource newPool() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(DB_URL);
    config.setUsername("postgres");
    config.setPassword("postgres_it");
    config.setMaximumPoolSize(4);
    config.setConnectionTimeout(3_000);
    config.setPoolName("it-graph-projection");
    return new HikariDataSource(config);
  }

  private static SqlSessionFactory sqlSessionFactory(HikariDataSource dataSource) {
    Configuration configuration =
        new Configuration(new Environment("it", new JdbcTransactionFactory(), dataSource));
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setArgNameBasedConstructorAutoMapping(true);
    configuration.addMapper(GraphProjectionSourceMapper.class);
    configuration.addMapper(GraphProjectionCheckpointMapper.class);
    return new SqlSessionFactoryBuilder().build(configuration);
  }

  /** 在第 N 页读取时抛错，用来构造「回填中断」而不是靠杀进程。 */
  private static final class InterruptingSource implements GraphProjectionSource {

    private final GraphProjectionSource delegate;
    private final int failAtPage;
    private int pages;

    private InterruptingSource(GraphProjectionSource delegate, int failAtPage) {
      this.delegate = delegate;
      this.failAtPage = failAtPage;
    }

    @Override
    public Optional<Stage> readStage(String consumerId) {
      return delegate.readStage(consumerId);
    }

    @Override
    public List<com.cognitanexus.query.graph.projection.domain.GraphProjectedWork> pageWorks(
        String generation, String afterWorkId, int limit) {
      pages++;
      if (pages == failAtPage) {
        throw new IllegalStateException("模拟中断：第 " + pages + " 页读取被强制中止");
      }
      return delegate.pageWorks(generation, afterWorkId, limit);
    }

    @Override
    public List<com.cognitanexus.query.graph.projection.domain.GraphProjectedWork> loadWorks(
        String generation, Collection<String> workIds) {
      return delegate.loadWorks(generation, workIds);
    }

    @Override
    public Optional<com.cognitanexus.query.graph.projection.domain.GraphProjectedWork> loadWork(
        String generation, String workId) {
      return delegate.loadWork(generation, workId);
    }

    @Override
    public long countEligibleWorks(String generation, String dataOrigin) {
      return delegate.countEligibleWorks(generation, dataOrigin);
    }

    @Override
    public long countQualifiedContributions(String generation, String dataOrigin) {
      return delegate.countQualifiedContributions(generation, dataOrigin);
    }

    @Override
    public long countContributors(String generation, String dataOrigin) {
      return delegate.countContributors(generation, dataOrigin);
    }

    @Override
    public long countMaterializedPairs(String generation, int threshold, String dataOrigin) {
      return delegate.countMaterializedPairs(generation, threshold, dataOrigin);
    }

    @Override
    public List<String> pageEligibleWorkIds(
        String generation, String afterWorkId, int limit, String dataOrigin) {
      return delegate.pageEligibleWorkIds(generation, afterWorkId, limit, dataOrigin);
    }

    @Override
    public List<ContributionView> loadQualifiedContributions(
        String generation, Collection<String> workIds) {
      return delegate.loadQualifiedContributions(generation, workIds);
    }

    @Override
    public List<AppliedEvent> listAppliedEventsAfter(String consumerId, long revision, int limit) {
      return delegate.listAppliedEventsAfter(consumerId, revision, limit);
    }

    @Override
    public List<String> achievementIdsOfScholar(String generation, String scholarId) {
      return delegate.achievementIdsOfScholar(generation, scholarId);
    }
  }
}
