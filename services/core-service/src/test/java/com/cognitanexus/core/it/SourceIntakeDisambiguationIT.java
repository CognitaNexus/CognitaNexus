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
 * 跨源去重与消歧的真实数据库验证（WB-09，验收 T24／T26）。
 *
 * <p>与既有 Mock 测试的区别：这里走真实的 {@code SourceIntakeService}、真实 MyBatis Mapper、真实唯一索引与
 * 触发器。Mock Repository 无法证明「唯一索引真的建了」「事务真的回滚了」，只能证明调用顺序。
 *
 * <p>连接 {@code ops/local/test-db.sh} 起的独立实例（55432），不是共享开发库；每个用例前都会重新断言这件事。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      // 本类只验证入库链路，不需要 Redis 参与；store-type=none 时 Spring Session 不创建
      // Redis 会话仓库，启动阶段也就不会尝试连接。
      "spring.session.store-type=none",
      // 必须关闭：否则会真的向 8083 投递 Outbox 事件，把测试事件推进共享查询库。
      "cn.outbox.dispatcher-enabled=false",
      "cn.bootstrap.platform-admin-username=it-admin"
    })
class SourceIntakeDisambiguationIT {

  private static final String RUN = "it" + Long.toString(System.nanoTime(), 36);

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
  void resetDataAndGuardSharedDatabase() throws SQLException {
    // 安全阀：属性覆盖失效时这里先红，避免把测试数据写进共享库 core_db。
    try (Connection conn = dataSource.getConnection()) {
      assertThat(conn.getMetaData().getURL())
          .as("集成测试必须连 55432 的隔离实例")
          .contains(":55432/")
          .contains("core_it");
    }
    // 用例之间不共享业务数据：否则同一 DOI／ORCID 第二次运行会被判成 DUPLICATE，
    // 表现为「上次通过、这次失败」，而失败与本次改动无关。
    //
    // 必须清 core_identifier_registry：它按 entity_id 逻辑关联，没有外键，
    // TRUNCATE ... CASCADE 带不到它。留下的注册行会让下一次导入以为「实体已存在」，
    // 进而对一条已被清掉的成果插入来源归属，报外键错误——这个现象在本次调试中就出现过。
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
              -- 修订计数器是运行状态而不是业务数据：清掉它会让 currentRevision 查不到行，
              -- MyBatis 把 null 映射到 long 直接抛 BindingException，所有入库用例一起失败。
              AND tablename <> 'evt_revision_counter'
          LOOP
            EXECUTE format('TRUNCATE TABLE %I CASCADE', t);
          END LOOP;
        END $$;
        """);
    // 计数器行由迁移插入；这里做成幂等自愈，避免历史清理留下空表导致 currentRevision 取不到值。
    jdbc.update(
        "INSERT INTO evt_revision_counter (id, current_revision) VALUES (1, 0)"
            + " ON CONFLICT (id) DO NOTHING");
    seedSource(RUN + "-src-a");
    seedSource(RUN + "-src-b");
  }

  @Test
  @DisplayName("T24 同一 DOI 的 URL 写法与裸值归一为一个成果，两个来源都可追溯")
  void doiVariantsResolveToOneAchievement() {
    var first =
        intake.accept(
            batch(
                RUN + "-src-a",
                "b1",
                record(
                    "doi-url",
                    paper(
                        "跨源同论文一",
                        "https://doi.org/10.5555/IT.DOI.CASE",
                        2024,
                        List.of(Map.of("name", "李四"))))),
            null);
    var second =
        intake.accept(
            batch(
                RUN + "-src-b",
                "b2",
                record(
                    "doi-bare",
                    paper("跨源同论文二", "10.5555/it.doi.case", 2024, List.of(Map.of("name", "李四"))))),
            null);

    assertThat(first.receipts().get(0).status()).isEqualTo("ACCEPTED");
    assertThat(second.receipts().get(0).status()).isEqualTo("ATTACHED");

    String achievementId = first.receipts().get(0).achievementId();
    assertThat(achievementId).isNotNull();
    assertThat(second.receipts().get(0).achievementId()).isEqualTo(achievementId);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM core_paper WHERE doi_norm = ?",
                Integer.class,
                "10.5555/it.doi.case"))
        .as("同一 DOI 归一值只能有一个规范化实体")
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM core_achievement_source WHERE achievement_id = ?",
                Integer.class,
                achievementId))
        .as("两个来源都要留下归属记录，不能只留最后一次")
        .isEqualTo(2);
  }

  @Test
  @DisplayName("T26 同一 ORCID 的 URL 写法与裸值必须识别为同一人，而不是两个标识")
  void orcidUrlAndBareValueAreTheSameIdentifier() {
    String orcidBare = "0000-0001-1111-1111";
    String orcidUrl = "https://orcid.org/" + orcidBare;

    intake.accept(
        batch(
            RUN + "-src-a",
            "b3",
            record(
                "orcid-url",
                paper(
                    "ORCID 归一测试论文一",
                    "10.5555/IT.ORCID.A",
                    2024,
                    List.of(Map.of("name", "王五", "orcid", orcidUrl))))),
        null);
    var receipt =
        intake.accept(
            batch(
                RUN + "-src-b",
                "b4",
                record(
                    "orcid-bare",
                    paper(
                        "ORCID 归一测试论文二",
                        "10.5555/IT.ORCID.B",
                        2024,
                        List.of(Map.of("name", "王五", "orcid", orcidBare))))),
            null);

    String secondAchievementId = receipt.receipts().get(0).achievementId();
    assertThat(secondAchievementId).isNotNull();

    // 标识只应有一条：URL 写法与裸值是同一个 ORCID。出现两条说明归一化漏了 ORCID 前缀。
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM core_identifier_registry"
                    + " WHERE identifier_kind = 'ORCID' AND normalized_value LIKE ?",
                Integer.class,
                "%1111-1111"))
        .as("ORCID 的 URL 写法与裸值不得注册成两个标识")
        .isEqualTo(1);

    // 稳定标识一致的关系是 AUTO；退化成同名匹配时关系是 PENDING_VERIFY。
    assertThat(
            jdbc.queryForObject(
                "SELECT relation_state FROM core_contribution"
                    + " WHERE achievement_id = ? ORDER BY author_order LIMIT 1",
                String.class,
                secondAchievementId))
        .as("ORCID 一致时应按稳定标识建立自动关系")
        .isEqualTo("AUTO");
  }

  @Test
  @DisplayName("T26 同名但 ORCID 不同时，两人必须保留为可区分身份，新标识不得静默丢弃")
  void conflictingOrcidForSameNameKeepsBothIdentities() {
    String orcidOne = "0000-0002-1111-1111";
    String orcidTwo = "0000-0002-2222-2222";

    intake.accept(
        batch(
            RUN + "-src-a",
            "b5",
            record(
                "orcid-one",
                paper(
                    "同名不同标识测试一",
                    "10.5555/IT.NAME.A",
                    2024,
                    List.of(Map.of("name", "赵六", "orcid", orcidOne))))),
        null);
    intake.accept(
        batch(
            RUN + "-src-b",
            "b6",
            record(
                "orcid-two",
                paper(
                    "同名不同标识测试二",
                    "10.5555/IT.NAME.B",
                    2024,
                    List.of(Map.of("name", "赵六", "orcid", orcidTwo))))),
        null);

    List<String> firstOwner =
        jdbc.queryForList(
            "SELECT entity_id FROM core_identifier_registry WHERE normalized_value = ?",
            String.class,
            orcidOne);
    List<String> secondOwner =
        jdbc.queryForList(
            "SELECT entity_id FROM core_identifier_registry WHERE normalized_value = ?",
            String.class,
            orcidTwo);

    // 第二个标识必须留下痕迹：静默丢弃会让这个人此后的记录永远只能靠同名匹配，
    // 而且再也无法追溯「他还有第二个 ORCID」这个事实。
    assertThat(secondOwner).as("同名但不同 ORCID 时，新标识不得被静默丢弃").hasSize(1);
    assertThat(firstOwner).as("第一个标识应正常注册").hasSize(1);
    assertThat(secondOwner.get(0))
        .as("不同 ORCID 是不同的人，不能被挂到同一个学者实体上")
        .isNotEqualTo(firstOwner.get(0));
  }

  // ---------------------------------------------------------------------------

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
      String title, String doi, int year, List<Map<String, Object>> authors) {
    return Map.of(
        "kind", "PAPER",
        "title", title,
        "doi", doi,
        "publicationYear", year,
        "authors", authors);
  }
}
