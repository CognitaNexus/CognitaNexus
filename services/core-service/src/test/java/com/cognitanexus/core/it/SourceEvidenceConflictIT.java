package com.cognitanexus.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.core.catalog.application.CatalogRepositories;
import com.cognitanexus.core.catalog.application.SourceIntakeCommands.SourceBatch;
import com.cognitanexus.core.catalog.application.SourceIntakeCommands.SourceRecordInput;
import com.cognitanexus.core.catalog.application.SourceIntakeService;
import com.cognitanexus.core.platform.util.TextNormalizer;
import com.cognitanexus.sharedkernel.DataNature;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 稳定标识命中既有实体时的证据相容性、人工审核值保护、以及并发首次入库的真实数据库验证（WB-09，T25／T27／T28）。
 *
 * <p>为什么必须是真实数据库：这三条验收的断言对象分别是「异常登记表里到底写了什么」「唯一索引与事务到底谁先失败」
 * 「人工值所在的溯源行等级」，Mock 仓储一条都证明不了。并发用例还依赖 PostgreSQL 的咨询锁与唯一索引阻塞语义。
 *
 * <p>连接 {@code ops/local/test-db.sh} 起的独立实例（55432）。每个用例前重新断言这件事，属性覆盖失效时先红。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.session.store-type=none",
      "cn.outbox.dispatcher-enabled=false",
      "cn.bootstrap.platform-admin-username=it-admin"
    })
class SourceEvidenceConflictIT {

  /** 与 {@code SourceIntakeService} 里新增的冲突异常类型一致；此处写字面量是为了让本类在修复前也能编译。 */
  private static final String KIND_METADATA_CONFLICT = "METADATA_CONFLICT";

  private static final String RUN = "cf" + Long.toString(System.nanoTime(), 36);

  @Autowired private SourceIntakeService intake;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private CatalogRepositories.FieldProvenanceRepository provenance;

  @DynamicPropertySource
  static void pointToIsolatedDatabase(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> PostgresTestDatabase.DEFAULT_URL);
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres_it");
    registry.add("spring.flyway.url", () -> PostgresTestDatabase.DEFAULT_URL);
    registry.add("spring.flyway.user", () -> "postgres");
    registry.add("spring.flyway.password", () -> "postgres_it");
  }

  @BeforeEach
  void resetDataAndGuardSharedDatabase() throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
      assertThat(conn.getMetaData().getURL())
          .as("集成测试必须连 55432 的隔离实例")
          .contains(":55432/")
          .contains("core_it");
    }
    jdbc.execute(
        """
        DO $$
        DECLARE t text;
        BEGIN
          FOR t IN
            SELECT tablename FROM pg_tables
            WHERE schemaname = 'public'
              AND (tablename LIKE 'core%' OR tablename LIKE 'raw%'
                   OR tablename LIKE 'gov%' OR tablename LIKE 'aud%'
                   OR tablename LIKE 'evt%' OR tablename LIKE 'wf%')
              AND tablename <> 'evt_revision_counter'
          LOOP
            EXECUTE format('TRUNCATE TABLE %I CASCADE', t);
          END LOOP;
        END $$;
        """);
    jdbc.update(
        "INSERT INTO evt_revision_counter (id, current_revision) VALUES (1, 0)"
            + " ON CONFLICT (id) DO NOTHING");
    seedSource(RUN + "-src-a");
    seedSource(RUN + "-src-b");
  }

  @Test
  @DisplayName("T25 同 DOI 但题名／年份／作者明显矛盾时，冲突必须进入受控治理且不采信矛盾字段")
  void sameDoiWithContradictingEvidenceGoesToGovernance() {
    String doi = "10.5555/IT.EVIDENCE.CONFLICT";
    var first =
        intake.accept(
            batch(
                RUN + "-src-a",
                "e1",
                record(
                    "evidence-a",
                    paper(
                        "基于图神经网络的学术合作发现方法",
                        doi,
                        2021,
                        12,
                        List.of(Map.of("name", "张三"), Map.of("name", "李四"))))),
            null);
    assertThat(first.receipts().get(0).status()).isEqualTo("ACCEPTED");
    String achievementId = first.receipts().get(0).achievementId();

    // 同一 DOI、同一类型，但题名完全不同、年份不同、作者完全不相交。
    var second =
        intake.accept(
            batch(
                RUN + "-src-b",
                "e2",
                record(
                    "evidence-b",
                    paper(
                        "城市轨道交通信号系统可靠性分析",
                        doi,
                        2019,
                        999,
                        List.of(Map.of("name", "王五"), Map.of("name", "赵六"))))),
            null);

    // 标识仍是同一篇：不新建实体，否则 DOI 唯一索引会挡住，重复实体也不该被制造。
    assertThat(second.receipts().get(0).achievementId()).isEqualTo(achievementId);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM core_achievement", Integer.class))
        .as("同一 DOI 只能有一个规范成果")
        .isEqualTo(1);

    // 关键断言：矛盾证据必须留下可裁决的记录，而不是被无条件采信。
    List<Map<String, Object>> anomalies =
        jdbc.queryForList(
            "SELECT entity_id, detail::text AS detail, source_record_id FROM gov_anomaly"
                + " WHERE kind = ? AND status = 'OPEN'",
            KIND_METADATA_CONFLICT);
    assertThat(anomalies).as("同 DOI 但题名／年份／作者矛盾时必须登记治理冲突").hasSize(1);
    assertThat(anomalies.get(0).get("entity_id")).isEqualTo(achievementId);
    assertThat(String.valueOf(anomalies.get(0).get("detail")))
        .as("冲突证据里必须逐项写明是哪些字段矛盾")
        .contains("title")
        .contains("publicationYear")
        .contains("authors")
        .contains("城市轨道交通信号系统可靠性分析");

    // 矛盾字段一律不采用：题名、年份仍是首次入库的原值。
    Map<String, Object> canonical =
        jdbc.queryForMap(
            "SELECT title, publication_year FROM core_achievement WHERE id = ?", achievementId);
    assertThat(canonical.get("title")).isEqualTo("基于图神经网络的学术合作发现方法");
    assertThat(((Number) canonical.get("publication_year")).intValue()).isEqualTo(2021);

    // 条目自身声称的引用量不得落到这条成果上：它可能属于另一篇文献。
    assertThat(
            jdbc.queryForObject(
                "SELECT citation_count FROM core_paper WHERE achievement_id = ?",
                Integer.class,
                achievementId))
        .as("冲突记录的指标不得刷新到既有成果")
        .isEqualTo(12);
  }

  @Test
  @DisplayName("T25 反例：同 DOI 且证据相容（仅大小写与标点差异）时不得登记冲突")
  void compatibleEvidenceIsNotReportedAsConflict() {
    String doi = "10.5555/IT.EVIDENCE.OK";
    List<Map<String, Object>> authors = List.of(Map.of("name", "孙七"), Map.of("name", "周八"));
    intake.accept(
        batch(
            RUN + "-src-a",
            "e3",
            record(
                "compatible-a",
                paper("Graph Neural Networks for Link Prediction", doi, 2022, 5, authors))),
        null);
    intake.accept(
        batch(
            RUN + "-src-b",
            "e4",
            record(
                "compatible-b",
                paper("graph neural networks for link prediction.", doi, 2022, 7, authors))),
        null);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gov_anomaly WHERE kind = ?",
                Integer.class,
                KIND_METADATA_CONFLICT))
        .as("证据相容时登记冲突会让治理队列被噪声灌满")
        .isZero();
    // 证据相容的正常路径仍应刷新来源指标。
    assertThat(
            jdbc.queryForObject(
                "SELECT citation_count FROM core_paper WHERE doi_norm = ?",
                Integer.class,
                TextNormalizer.normalizeStableIdentifier(doi)))
        .as("证据相容时来源指标应照常刷新")
        .isEqualTo(7);
  }

  @Test
  @DisplayName("T28 人工审核过的题名不被静默覆盖，来源新值可追溯且冲突登记")
  void reviewedTitleSurvivesLaterSourceUpdate() {
    String doi = "10.5555/IT.REVIEWED.TITLE";
    var first =
        intake.accept(
            batch(
                RUN + "-src-a",
                "e5",
                record(
                    "reviewed-a", paper("来源原始题名A", doi, 2020, 3, List.of(Map.of("name", "钱九"))))),
            null);
    String achievementId = first.receipts().get(0).achievementId();

    // 人工审核：改题名并写 REVIEWED 级溯源。审核改写题名的接口尚未提供，
    // 这里按审核接口最终应产生的两处副作用直接落库（成果表 + 溯源表），不改变被测链路。
    String reviewedTitle = "人工审核后的题名B";
    jdbc.update(
        "UPDATE core_achievement SET title = ?, title_norm = ? WHERE id = ?",
        reviewedTitle,
        TextNormalizer.normalize(reviewedTitle),
        achievementId);
    provenance.upsertReviewed("ACHIEVEMENT", achievementId, "title", "REVIEWED", reviewedTitle);

    // 来源再次给出明显不同的题名 C。
    var third =
        intake.accept(
            batch(
                RUN + "-src-b",
                "e6",
                record(
                    "reviewed-c",
                    paper("来源再次提供的题名C", doi, 2020, 3, List.of(Map.of("name", "钱九"))))),
            null);
    assertThat(third.receipts().get(0).achievementId()).isEqualTo(achievementId);

    assertThat(
            jdbc.queryForObject(
                "SELECT title FROM core_achievement WHERE id = ?", String.class, achievementId))
        .as("人工审核值不得被后续来源静默覆盖")
        .isEqualTo(reviewedTitle);

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT value_level, value_text FROM core_field_provenance"
                + " WHERE entity_type = 'ACHIEVEMENT' AND entity_id = ? AND field_name = 'title'",
            achievementId);
    assertThat(row.get("value_level")).as("溯源等级必须仍是 REVIEWED").isEqualTo("REVIEWED");
    assertThat(row.get("value_text")).isEqualTo(reviewedTitle);

    // 来源新值 C 不能就这么消失：异常登记里要能读到它，且能顺 source_record_id 找回原始报文。
    // 逐字段按 jsonb 取值，而不是去匹配整段 JSON 文本：文本里的空格与字段顺序会随序列化实现变化，
    // 那种断言失败时看起来像「逻辑错了」，实际只是格式不同。
    Map<String, Object> anomaly =
        jdbc.queryForMap(
            "SELECT detail->>'canonicalTitle' AS canonical_title,"
                + "       detail->>'incomingTitle' AS incoming_title,"
                + "       detail->>'manualProtected' AS manual_protected,"
                + "       detail->>'titleValueLevel' AS title_level,"
                + "       detail->>'sourceRecordRef' AS source_record_ref"
                + "  FROM gov_anomaly WHERE kind = ? AND entity_id = ?",
            KIND_METADATA_CONFLICT,
            achievementId);
    assertThat(anomaly.get("canonical_title")).as("冲突证据要给出被保护下来的人工值").isEqualTo(reviewedTitle);
    assertThat(anomaly.get("incoming_title")).as("冲突证据要给出被拒绝采用的来源新值").isEqualTo("来源再次提供的题名C");
    assertThat(anomaly.get("manual_protected"))
        .as("必须标明该字段受人工审核保护，处置人才能理解来源值为何没生效")
        .isEqualTo("true");
    assertThat(anomaly.get("title_level")).isEqualTo("REVIEWED");
    assertThat(anomaly.get("source_record_ref")).as("要能顺来源记录标识找回原始报文").isEqualTo("reviewed-c");
  }

  @Test
  @DisplayName("T27 同 DOI 并发首次导入必须串行：不重复实体、不重复标识，失败方可重试")
  void concurrentFirstImportDoesNotDuplicateEntity() throws Exception {
    String doi = "10.5555/IT.RACE.FIRST";
    String normalized = TextNormalizer.normalizeStableIdentifier(doi);

    AtomicReference<Object> rivalOutcome = new AtomicReference<>();
    AtomicReference<Throwable> rivalError = new AtomicReference<>();
    CountDownLatch rivalStarted = new CountDownLatch(1);
    Thread rival =
        new Thread(
            () -> {
              rivalStarted.countDown();
              try {
                rivalOutcome.set(
                    intake.accept(
                        batch(
                            RUN + "-src-b",
                            "e8",
                            record(
                                "race-b",
                                paper("并发首次导入乙", doi, 2022, 4, List.of(Map.of("name", "吴十"))))),
                        null));
              } catch (Throwable t) {
                // 连 Error 一起记：线程里未被记录的异常会让断言只看到 null，
                // 于是「失败原因」变成「什么都没发生」，比失败本身更难查。
                rivalError.set(t);
              }
            });
    rival.setDaemon(true);
    // 必须真正 start 这个线程：把 Thread 对象交给线程池执行时 isAlive() 恒为 false，
    // join 会立刻返回，于是断言读到的是「竞争者还没跑完」的 null，看起来像「什么都没发生」。
    rival.start();
    try {
      TransactionTemplate gate = new TransactionTemplate(transactionManager);
      String firstId =
          gate.execute(
              status -> {
                // 首次导入的一方按服务协议先拿同一把事务级咨询锁，并保持未提交。
                // 键的拼法与 SourceIntakeService 一致（种类:规范化值），此处用字面量是为了让本类在修复前可编译。
                jdbc.queryForObject(
                    "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) t",
                    Integer.class,
                    "DOI:" + normalized);
                var first =
                    intake.accept(
                        batch(
                            RUN + "-src-a",
                            "e7",
                            record(
                                "race-a",
                                paper("并发首次导入甲", doi, 2022, 4, List.of(Map.of("name", "吴十"))))),
                        null);
                assertThat(first.receipts().get(0).status()).isEqualTo("ACCEPTED");
                awaitQuietly(rivalStarted);
                // 让竞争者走到「查不到标识 → 准备插入」的位置，暴露 check-then-insert 的窗口。
                sleepQuietly(Duration.ofMillis(400));
                return first.receipts().get(0).achievementId();
              });
      rival.join(TimeUnit.SECONDS.toMillis(60));

      assertThat(rival.isAlive()).as("竞争者未在 60 秒内收敛").isFalse();
      assertThat(rivalError.get()).as("并发首次导入不得让一方撞唯一约束抛异常").isNull();
      Object outcome = rivalOutcome.get();
      assertThat(outcome)
          .as("竞争者必须返回接收结果，实际为 %s", outcome)
          .isInstanceOf(
              com.cognitanexus.core.catalog.application.SourceIntakeCommands.BatchReceipt.class);
      var rivalReceipt =
          (com.cognitanexus.core.catalog.application.SourceIntakeCommands.BatchReceipt) outcome;
      assertThat(rivalReceipt.receipts().get(0).status())
          .as("并发下第二个来源应识别为已有实体并追加归属")
          .isEqualTo("ATTACHED");
      assertThat(rivalReceipt.receipts().get(0).achievementId()).isEqualTo(firstId);

      assertThat(jdbc.queryForObject("SELECT count(*) FROM core_paper", Integer.class))
          .as("并发首次导入只能产生一个论文扩展行")
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM core_identifier_registry WHERE normalized_value = ?",
                  Integer.class,
                  normalized))
          .as("同一 DOI 只允许一条标识注册")
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(DISTINCT source_id) FROM core_achievement_source"
                      + " WHERE achievement_id = ?",
                  Integer.class,
                  firstId))
          .as("两个来源都要留下归属")
          .isEqualTo(2);
    } finally {
      rival.interrupt();
    }
  }

  // ---------------------------------------------------------------------------

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).as("竞争者线程未在 10 秒内启动").isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static void sleepQuietly(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private void seedSource(String code) {
    jdbc.update(
        "INSERT INTO raw_source_config"
            + " (id, source_code, display_name, enabled, license_scope, base_url, allowed_fields)"
            + " VALUES (?, ?, ?, true, 'CC0', 'https://example.invalid', '[]'::jsonb)"
            + " ON CONFLICT (id) DO NOTHING",
        code,
        code,
        "集成测试来源 " + code);
  }

  private static SourceBatch batch(String sourceCode, String batchId, SourceRecordInput record) {
    return new SourceBatch(sourceCode, "1", batchId, List.of(record));
  }

  private static SourceRecordInput record(String sourceRecordId, Map<String, Object> payload) {
    return new SourceRecordInput(sourceRecordId, null, payload, DataNature.REAL, null, null, null);
  }

  private static Map<String, Object> paper(
      String title, String doi, int year, int citationCount, List<Map<String, Object>> authors) {
    return Map.of(
        "kind", "PAPER",
        "title", title,
        "doi", doi,
        "publicationYear", year,
        "citationCount", citationCount,
        "authors", authors);
  }
}
