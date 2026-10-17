package com.cognitanexus.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.core.catalog.application.SourceIntakeCommands.SourceBatch;
import com.cognitanexus.core.catalog.application.SourceIntakeCommands.SourceRecordInput;
import com.cognitanexus.core.catalog.application.SourceIntakeService;
import com.cognitanexus.sharedkernel.DataNature;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 来源字段白名单的全链路验证（WB-10，验收 T29）。
 *
 * <p>改动前的事实：{@code applyFieldWhitelist} 的结果只用于保存原始记录 JSON，而规范化成果、类型扩展、
 * 贡献挂接读的仍是未过滤的 {@code record}。于是「原始 JSON 已经干净」与「规范表里仍留着不该收的字段」
 * 可以同时成立——只看原始存储会得出「白名单生效」的错误结论。
 *
 * <p>本组用例把禁入标记同时放进摘要、DOI、刊物与作者，逐出口检查：
 * 原始存储、论文扩展表、标识注册表、贡献表。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.session.store-type=none",
      "cn.outbox.dispatcher-enabled=false",
      "cn.bootstrap.platform-admin-username=it-admin"
    })
class SourceFieldWhitelistIT {

  private static final String RUN = "it" + Long.toString(System.nanoTime(), 36);
  private static final String SOURCE = RUN + "-src-wl";
  private static final String MARKER = "WL-LEAK-" + RUN;

  @Autowired private SourceIntakeService intake;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

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
  void resetDataAndSeedSource() throws SQLException {
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
    // 只许可三个字段：类型、题名、年份。摘要、DOI、刊物、作者都不在许可范围内。
    jdbc.update(
        "INSERT INTO raw_source_config"
            + " (id, source_code, display_name, enabled, license_scope, base_url, allowed_fields)"
            + " VALUES (?, ?, ?, true, 'CC-BY-4.0', 'https://example.invalid',"
            + " CAST(? AS jsonb))"
            + " ON CONFLICT (id) DO NOTHING",
        SOURCE,
        SOURCE,
        "白名单测试来源",
        "[\"kind\",\"title\",\"publicationYear\"]");
  }

  @Test
  @DisplayName("T29 白名单排除的字段不得进入原始存储之外的任何出口")
  void whitelistAppliesBeyondRawJson() {
    var receipt =
        intake.accept(
            new SourceBatch(
                SOURCE,
                "1",
                "wl-batch",
                List.of(
                    new SourceRecordInput(
                        "wl-record",
                        null,
                        Map.of(
                            "kind", "PAPER",
                            "title", "白名单测试论文",
                            "publicationYear", 2024,
                            "doi", "10.5555/IT.WL.1",
                            "venue", "不该出现的期刊",
                            "abstractText", MARKER,
                            "authors", List.of(Map.of("name", "钱七"))),
                        DataNature.REAL,
                        null,
                        null,
                        null))),
            null);

    String achievementId = receipt.receipts().get(0).achievementId();
    assertThat(achievementId).as("白名单内的必要字段足以建实体").isNotNull();

    // 出口一：原始来源记录。改造前这里本来就是干净的，单看它会把缺陷判成「已修」。
    assertThat(
            jdbc.queryForObject(
                "SELECT payload::text FROM raw_source_record WHERE source_record_id = ?",
                String.class,
                "wl-record"))
        .as("原始 JSON 不得保留白名单外字段")
        .doesNotContain(MARKER)
        .doesNotContain("不该出现的期刊");

    // 出口二：论文类型扩展表。改造前摘要与 DOI 会被写进来。
    Map<String, Object> paper =
        jdbc.queryForMap(
            "SELECT abstract_text, doi_norm, venue FROM core_paper WHERE achievement_id = ?",
            achievementId);
    assertThat(paper.get("abstract_text")).as("规范化摘要不得出现白名单外内容").isNull();
    assertThat(paper.get("doi_norm")).as("白名单未许可 DOI 时不得登记归一 DOI").isNull();
    assertThat(paper.get("venue")).as("白名单未许可刊物时不得写入刊物").isNull();

    // 出口三：稳定标识注册表。改造前会按未过滤的 DOI 建一条注册，进而影响去重与发布判定。
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM core_identifier_registry WHERE entity_id = ?",
                Integer.class,
                achievementId))
        .as("白名单外的标识不得进入注册表")
        .isZero();

    // 出口四：贡献挂接。改造前作者列表来自未过滤记录，会凭空产生一条作者关系。
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM core_contribution WHERE achievement_id = ?",
                Integer.class,
                achievementId))
        .as("白名单外的作者不得产生贡献关系")
        .isZero();
  }

  @Test
  @DisplayName("白名单为空表示不限制字段：这是既有语义，不能顺手改成『全部拒绝』")
  void emptyWhitelistMeansNoRestriction() {
    String source = RUN + "-src-open";
    jdbc.update(
        "INSERT INTO raw_source_config"
            + " (id, source_code, display_name, enabled, license_scope, base_url, allowed_fields)"
            + " VALUES (?, ?, ?, true, 'CC0', 'https://example.invalid', '[]'::jsonb)"
            + " ON CONFLICT (id) DO NOTHING",
        source,
        source,
        "不限制字段的来源");

    var receipt =
        intake.accept(
            new SourceBatch(
                source,
                "1",
                "open-batch",
                List.of(
                    new SourceRecordInput(
                        "open-record",
                        null,
                        Map.of(
                            "kind", "PAPER",
                            "title", "无白名单论文",
                            "publicationYear", 2024,
                            "doi", "10.5555/IT.OPEN.1",
                            "abstractText", "允许保留的摘要"),
                        DataNature.REAL,
                        null,
                        null,
                        null))),
            null);

    String achievementId = receipt.receipts().get(0).achievementId();
    Map<String, Object> paper =
        jdbc.queryForMap(
            "SELECT abstract_text FROM core_paper WHERE achievement_id = ?", achievementId);
    assertThat(paper.get("abstract_text")).as("空白名单不应过滤掉合法字段").isEqualTo("允许保留的摘要");
  }

  @Test
  @DisplayName("T29 收紧白名单只对后续入库生效；重放既有原始记录既不能补齐也不能回滚已入库字段")
  void narrowingWhitelistAffectsOnlySubsequentIntake() {
    String source = RUN + "-src-cfg";
    jdbc.update(
        "INSERT INTO raw_source_config"
            + " (id, source_code, display_name, enabled, license_scope, base_url,"
            + "  config_version, allowed_fields)"
            + " VALUES (?, ?, ?, true, 'CC0', 'https://example.invalid', 1, '[]'::jsonb)",
        source,
        source,
        "配置版本变化测试来源");

    // 版本 1：不限制字段，摘要可以进规范表。
    var before =
        intake.accept(
            new SourceBatch(
                source,
                "1",
                "cfg-batch-1",
                List.of(
                    new SourceRecordInput(
                        "cfg-record-1",
                        null,
                        Map.of(
                            "kind", "PAPER",
                            "title", "配置版本变化论文一",
                            "publicationYear", 2023,
                            "abstractText", "版本一许可的摘要"),
                        DataNature.REAL,
                        null,
                        null,
                        null))),
            null);
    String firstId = before.receipts().get(0).achievementId();
    assertThat(
            jdbc.queryForObject(
                "SELECT abstract_text FROM core_paper WHERE achievement_id = ?",
                String.class,
                firstId))
        .isEqualTo("版本一许可的摘要");

    // 版本 2：收紧到三个必要字段。
    jdbc.update(
        "UPDATE raw_source_config SET allowed_fields = CAST(? AS jsonb), config_version = 2"
            + " WHERE id = ?",
        "[\"kind\",\"title\",\"publicationYear\"]",
        source);

    // 重放同一条原始记录（同 sourceRecordId、同报文）：应识别为幂等重复，不重跑规范化。
    var replay =
        intake.accept(
            new SourceBatch(
                source,
                "1",
                "cfg-batch-2",
                List.of(
                    new SourceRecordInput(
                        "cfg-record-1",
                        null,
                        Map.of(
                            "kind", "PAPER",
                            "title", "配置版本变化论文一",
                            "publicationYear", 2023,
                            "abstractText", "版本一许可的摘要"),
                        DataNature.REAL,
                        null,
                        null,
                        null))),
            null);
    assertThat(replay.receipts().get(0).status()).as("同一条原始记录再次到达应判为幂等重复").isEqualTo("DUPLICATE");
    assertThat(
            jdbc.queryForObject(
                "SELECT abstract_text FROM core_paper WHERE achievement_id = ?",
                String.class,
                firstId))
        .as("重放不会回头清洗已入库的字段——历史数据的处理需要受控迁移，本次未获授权")
        .isEqualTo("版本一许可的摘要");

    // 版本 2 之后的新记录：摘要必须被挡住。
    var after =
        intake.accept(
            new SourceBatch(
                source,
                "1",
                "cfg-batch-3",
                List.of(
                    new SourceRecordInput(
                        "cfg-record-2",
                        null,
                        Map.of(
                            "kind", "PAPER",
                            "title", "配置版本变化论文二",
                            "publicationYear", 2023,
                            "abstractText", "版本二禁止的摘要"),
                        DataNature.REAL,
                        null,
                        null,
                        null))),
            null);
    String secondId = after.receipts().get(0).achievementId();
    assertThat(
            jdbc.queryForObject(
                "SELECT abstract_text FROM core_paper WHERE achievement_id = ?",
                String.class,
                secondId))
        .as("收紧后的白名单必须对后续入库立即生效")
        .isNull();
  }
}
