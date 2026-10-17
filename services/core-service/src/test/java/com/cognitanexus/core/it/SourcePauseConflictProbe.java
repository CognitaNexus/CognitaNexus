package com.cognitanexus.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.core.catalog.application.SourceAdminService;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.sharedkernel.AccountId;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
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
 * 来源「暂停采集」与「撤销展示许可」语义冲突的<b>实测探针</b>（ADR-001 §2.1／§2.2／§2.4）。
 *
 * <p><b>这不是验收用例。</b>下面每条断言断的是「当前实现做了什么」，而 SRS 要求的是相反的行为
 * （AC-FR01-S01-02、BR-606：来源停用后既有公开成果仍可读）。用例全绿的含义是「冲突仍然存在」，
 * 不是「系统符合需求」。将来 ADR-001 签认并拆开两种语义后，这些断言会失败——那时应当改写为
 * 断言 SRS 口径，而不是删掉它们。
 *
 * <p>为什么不以 {@code IT} 结尾：它不是一条应该在每次 {@code mvn verify} 里守护的需求，
 * 而是给 ADR-001 取证的探针。默认构建不收集它，用
 * {@code bash ops/verify/source-pause-conflict.sh} 单独运行。放进默认构建会让后来者把
 * 「断言 RESTRICTED」误读成需求。
 *
 * <p>实测前提：跑在 55432 的隔离实例（库 {@code core_it}），{@code @BeforeEach} 先核对连接串，
 * 避免误连共享开发库后清表。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.session.store-type=none",
      "cn.outbox.dispatcher-enabled=false",
      "cn.bootstrap.platform-admin-username=it-probe"
    })
class SourcePauseConflictProbe {

  private static final String RUN = "probe" + Long.toString(System.nanoTime(), 36);

  /**
   * 来源管理能力只挂在平台管理员上（{@code Capabilities.of} 里 USER／REVIEWER 都不含
   * {@code source:manage}），所以这里必须用 PLATFORM_ADMIN 角色，直接塞能力字符串不会生效。
   */
  private static final ActorContext ADMIN =
      new ActorContext(
          new AccountId(RUN + "-admin"),
          RUN + "-admin",
          RUN + "-admin",
          Set.of(RoleNames.PLATFORM_ADMIN),
          1L);

  @Autowired private SourceAdminService sourceAdmin;
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
    try (Connection conn = dataSource.getConnection()) {
      assertThat(conn.getMetaData().getURL())
          .as("探针必须连 55432 的隔离实例")
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
                   OR tablename LIKE 'evt%' OR tablename LIKE 'wf%'
                   OR tablename LIKE 'iam%')
              AND tablename <> 'evt_revision_counter'
          LOOP
            EXECUTE format('TRUNCATE TABLE %I CASCADE', t);
          END LOOP;
        END $$;
        """);
    jdbc.update(
        "INSERT INTO evt_revision_counter (id, current_revision) VALUES (1, 0)"
            + " ON CONFLICT (id) DO NOTHING");
  }

  // ===========================================================================
  // §2.1：一次「先别抓了」的暂停，把独占该来源的公开成果整体下线
  // ===========================================================================

  @Test
  @DisplayName("§2.1 暂停采集会把独占来源的公开成果收紧——SRS 要求它仍可检索")
  void pausingCollectionTightensPublicAchievements() {
    seedSource("src-a", true);
    seedSource("src-b", true);
    seedAchievement("a1"); // 仅 src-a
    linkSource("a1", "src-a", "ra1");
    seedAchievement("b1"); // src-a + src-b
    linkSource("b1", "src-a", "rb1");
    linkSource("b1", "src-b", "rb2");

    var outcome = sourceAdmin.setEnabled(ADMIN, "src-a", false, "上游维护窗口，暂停采集");

    // 操作者说的是「先别抓了」；实际发生的是「这两条不再公开」。
    assertThat(outcome.tightenedCount())
        .as("现状：暂停动作收紧了 %d 条成果；ADR-001 认为暂停不该触碰可见性", outcome.tightenedCount())
        .isEqualTo(1);
    String a1 = visibilityOf("a1");
    System.out.printf(
        "[PROBE 2.1] 暂停 src-a 后：a1(仅 src-a) 可见性=%s，b1(有 src-b) 可见性=%s，收紧条数=%d%n",
        a1, visibilityOf("b1"), outcome.tightenedCount());
    assertThat(a1).as("现状：独占来源的成果被改成 RESTRICTED；AC-FR01-S01-02 要求历史成果仍可检索").isEqualTo("RESTRICTED");
    assertThat(visibilityOf("b1")).as("有第二个来源的成果不受影响，这一条与「只有完全依赖该来源才收紧」的设计一致").isEqualTo("PUBLIC");
  }

  // ===========================================================================
  // §2.2：5001 条时只收紧前 5000 条，且响应里看不出还有剩余
  // ===========================================================================

  @Test
  @DisplayName("§2.2 独占公开成果 5001 条时只收紧 5000 条，第 5001 条仍公开")
  void tighteningStopsAtBatchLimitWithoutReportingRemainder() {
    seedSource("src-bulk", true);
    seedBulkAchievements("src-bulk", 5_001);

    var outcome = sourceAdmin.setEnabled(ADMIN, "src-bulk", false, "许可到期，撤销展示许可");

    assertThat(outcome.tightenedCount())
        .as("现状：单批上限 5000，响应里只有 tightenedCount，没有 remaining／truncated 标记")
        .isEqualTo(5_000);

    Long stillPublic =
        jdbc.queryForObject(
            "SELECT count(*) FROM core_achievement"
                + " WHERE id LIKE 'bulk-%' AND visibility = 'PUBLIC'",
            Long.class);
    System.out.printf(
        "[PROBE 2.2] 5001 条来源撤权：响应 tightenedCount=%d，仍为 PUBLIC 的条数=%d%n",
        outcome.tightenedCount(), stillPublic);
    assertThat(stillPublic).as("现状：提出撤权的人从响应上无法知道还有 1 条没处理").isEqualTo(1L);
  }

  // ===========================================================================
  // §2.4：「还有其他来源」的判据不检查那个来源当前是否仍有效
  // ===========================================================================

  @Test
  @DisplayName("§2.4 第二来源已停用仍被当作有效依据，结果残留在公开状态")
  void thirdPartySourceCountsAsEvidenceEvenWhenItIsNotRunnable() {
    seedSource("src-x", true);
    seedSource("src-y", true);
    seedAchievement("c1");
    linkSource("c1", "src-x", "rc1");
    linkSource("c1", "src-y", "rc2");

    // 第一步：停用 src-y（相当于它的许可已撤回），c1 还有 src-x，不该被收紧——这一条是对的。
    sourceAdmin.setEnabled(ADMIN, "src-y", false, "许可撤回");
    assertThat(visibilityOf("c1")).as("还有 src-x 提供展示依据，c1 继续公开").isEqualTo("PUBLIC");

    // 第二步：停用 src-x。此时两个来源都已停用，c1 已无任何有效展示依据。
    sourceAdmin.setEnabled(ADMIN, "src-x", false, "上游维护窗口，暂停采集");

    Long sourcesLeft =
        jdbc.queryForObject(
            "SELECT count(*) FROM core_achievement_source WHERE achievement_id = 'c1'", Long.class);
    assertThat(sourcesLeft).as("c1 的两条来源关联都还在，只是两个来源都已停用").isEqualTo(2L);
    String c1 = visibilityOf("c1");
    System.out.printf("[PROBE 2.4] src-y 与 src-x 均已停用后：c1 可见性=%s，来源关联条数=%d%n", c1, sourcesLeft);
    assertThat(c1).as("现状：NOT EXISTS 只看有没有第二个来源行，不看它是否仍具备展示许可，于是 c1 残留公开").isEqualTo("PUBLIC");
  }

  // ===========================================================================
  // 造数
  // ===========================================================================

  private void seedSource(String id, boolean enabled) {
    jdbc.update(
        "INSERT INTO raw_source_config"
            + " (id, source_code, display_name, enabled, license_scope, base_url, allowed_fields)"
            + " VALUES (?, ?, ?, ?, ?, ?, '[]'::jsonb)",
        id,
        id.toUpperCase(),
        "探针来源 " + id,
        enabled,
        "CC-BY-4.0",
        "https://example.invalid/" + id);
  }

  /**
   * 造一条可发布成果。
   *
   * <p>分两步：先以不可发布状态插入，再补类型扩展，最后更新为 PUBLIC／EFFECTIVE。反过来会在
   * 「可发布成果必须恰好有一个类型扩展」的延迟检查上失败。
   */
  private void seedAchievement(String id) {
    jdbc.update(
        "INSERT INTO core_achievement"
            + " (id, type, title, title_norm, data_nature, visibility, effective_status,"
            + "  publication_year, year_precision)"
            + " VALUES (?, 'PAPER', ?, ?, 'SIMULATED', 'RESTRICTED', 'PENDING_GOVERNANCE', 2024, 'YEAR')",
        id,
        "探针成果 " + id,
        "探针成果" + id);
    jdbc.update("INSERT INTO core_paper (achievement_id) VALUES (?)", id);
    jdbc.update(
        "UPDATE core_achievement SET visibility = 'PUBLIC', effective_status = 'EFFECTIVE'"
            + " WHERE id = ?",
        id);
  }

  private void linkSource(String achievementId, String sourceId, String recordSuffix) {
    String recordId = "rec-" + recordSuffix;
    jdbc.update(
        "INSERT INTO raw_source_record"
            + " (id, source_id, source_record_id, content_fingerprint, payload, data_nature,"
            + "  fetched_at, catalog_revision)"
            + " VALUES (?, ?, ?, ?, '{}'::jsonb, 'SIMULATED', now(), 0)",
        recordId,
        sourceId,
        recordSuffix,
        "fp-" + recordSuffix);
    jdbc.update(
        "INSERT INTO core_achievement_source"
            + " (id, achievement_id, source_record_id, source_id, received_at)"
            + " VALUES (?, ?, ?, ?, now())",
        "link-" + recordSuffix,
        achievementId,
        recordId,
        sourceId);
  }

  /**
   * 批量造 n 条仅依赖指定来源的公开成果。
   *
   * <p>先以 RESTRICTED／PENDING_GOVERNANCE 插入、补齐扩展后再整体更新为可发布：延迟检查要求
   * 「可发布成果恰好有一个类型扩展」，逐条走两次插入在 5001 条上没有必要。
   */
  private void seedBulkAchievements(String sourceId, int n) {
    jdbc.update(
        """
        INSERT INTO core_achievement
          (id, type, title, title_norm, data_nature, visibility, effective_status,
           publication_year, year_precision)
        SELECT 'bulk-' || lpad(g::text, 5, '0'), 'PAPER', '批量成果 ' || g, '批量成果' || g,
               'SIMULATED', 'RESTRICTED', 'PENDING_GOVERNANCE', 2024, 'YEAR'
        FROM generate_series(1, ?) g
        """,
        n);
    jdbc.update(
        """
        INSERT INTO core_paper (achievement_id)
        SELECT 'bulk-' || lpad(g::text, 5, '0') FROM generate_series(1, ?) g
        """,
        n);
    jdbc.update(
        """
        INSERT INTO raw_source_record
          (id, source_id, source_record_id, content_fingerprint, payload, data_nature,
           fetched_at, catalog_revision)
        SELECT 'rec-bulk-' || lpad(g::text, 5, '0'), ?, 'rb-' || g, 'fpb-' || g,
               '{}'::jsonb, 'SIMULATED', now(), 0
        FROM generate_series(1, ?) g
        """,
        sourceId,
        n);
    jdbc.update(
        """
        INSERT INTO core_achievement_source
          (id, achievement_id, source_record_id, source_id, received_at)
        SELECT 'link-bulk-' || lpad(g::text, 5, '0'), 'bulk-' || lpad(g::text, 5, '0'),
               'rec-bulk-' || lpad(g::text, 5, '0'), ?, now()
        FROM generate_series(1, ?) g
        """,
        sourceId,
        n);
    jdbc.update(
        """
        UPDATE core_achievement SET visibility = 'PUBLIC', effective_status = 'EFFECTIVE'
        WHERE id LIKE 'bulk-%'
        """);
  }

  private String visibilityOf(String achievementId) {
    return jdbc.queryForObject(
        "SELECT visibility FROM core_achievement WHERE id = ?", String.class, achievementId);
  }
}
