package com.cognitanexus.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.core.catalog.application.SourceAdminService;
import com.cognitanexus.core.catalog.infrastructure.AchievementMapper;
import com.cognitanexus.core.catalog.infrastructure.AchievementMapper.AchievementRow;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.sharedkernel.AccountId;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
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
 * 「暂停采集」与「撤销展示许可」两种语义是否真的拆开了——<b>实测探针</b>（ADR-001 方案 A）。
 *
 * <p>这个类的前身是 {@code SourcePauseConflictProbe}，当时断言的是「冲突仍在」（暂停即下线、
 * 5001 条只收紧 5000、第二来源已停用仍算有效依据）。方案 A 落地后，那些断言全部反转成 SRS 口径：
 *
 * <ul>
 *   <li>暂停采集不触碰可见性，导出仍包含这些成果（AC-FR01-S01-02、BR-606）；
 *   <li>撤权在<b>受理那一刻</b>就把成果挡在公开导出之外，逐批收紧只是把库里的状态落成与事实一致，
 *       因此可以中断、可以续跑，不再有「5001 条只处理 5000」这种说不清的残留（ADR-001 §4）；
 *   <li>「还有别的来源」的判据看的是那一行的<b>展示许可</b>，不是它的采集开关（ADR-001 §2.4）。
 * </ul>
 *
 * <p>为什么不以 {@code IT} 结尾：它不是每次 {@code mvn verify} 都要跑的集成用例——那需要 55432
 * 的隔离实例与两万条造数。默认构建不收集它，用 {@code bash ops/verify/source-display-license.sh}
 * 单独运行。放进默认构建会让构建依赖一个只有本机才有的数据库。
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
class SourceDisplayLicenseProbe {

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
  @Autowired private AchievementMapper achievements;
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
  // 方案 A 第一半：暂停采集不再触碰可见性
  // ===========================================================================

  @Test
  @DisplayName("暂停采集只停调度：独占来源的成果仍公开，导出仍包含它")
  void pausingCollectionKeepsAchievementsPublic() {
    seedSource("src-a", true);
    seedSource("src-b", true);
    seedAchievement("a1"); // 仅 src-a
    linkSource("a1", "src-a", "ra1");
    seedAchievement("b1"); // src-a + src-b
    linkSource("b1", "src-a", "rb1");
    linkSource("b1", "src-b", "rb2");

    var outcome = sourceAdmin.setCollectionPaused(ADMIN, "src-a", true, "上游维护窗口，暂停采集");

    assertThat(outcome.enabled()).as("暂停采集意味着调度开关为关").isFalse();
    assertThat(outcome.displayLicenseTouched()).as("暂停采集绝不触碰展示许可——这是方案 A 的核心").isFalse();
    assertThat(visibilityOf("a1"))
        .as("独占来源的成果必须继续公开：AC-FR01-S01-02 要求来源停用后历史成果仍可检索")
        .isEqualTo("PUBLIC");
    assertThat(visibilityOf("b1")).isEqualTo("PUBLIC");
    assertThat(exportedIds()).as("公开导出是查询服务的唯一数据源；它包含这两条，检索侧才看得到").contains("a1", "b1");
    assertThat(licenseOf("src-a")).as("采集开关与展示许可互不影响").isEqualTo("GRANTED");
    System.out.printf(
        "[PROBE A1] 暂停 src-a 后：a1=%s，b1=%s，导出含两者=%s，许可=%s%n",
        visibilityOf("a1"), visibilityOf("b1"), exportedIds().contains("a1"), licenseOf("src-a"));
  }

  // ===========================================================================
  // 方案 A 第二半之一：撤权在受理那一刻就阻断，不依赖逐批收紧
  // ===========================================================================

  @Test
  @DisplayName("许可一置为 REVOKED，导出立即不含这些成果——即使 visibility 还没被逐批改完")
  void revokingLicenseBlocksExportBeforeAnyTightening() {
    seedSource("src-instant", true);
    seedAchievement("i1");
    linkSource("i1", "src-instant", "ri1");

    // 只改许可位，等价于「受理事务已提交、第一批收紧还没跑」的那一瞬间。
    jdbc.update(
        "UPDATE raw_source_config SET display_license_status = 'REVOKED',"
            + " display_license_changed_at = now() WHERE id = 'src-instant'");

    assertThat(visibilityOf("i1")).as("此刻库里还没有任何收紧动作，visibility 仍是 PUBLIC").isEqualTo("PUBLIC");
    assertThat(exportedIds()).as("导出条件读的是许可，所以阻断与批处理进度无关——这正是可以中断的前提").doesNotContain("i1");
    System.out.printf(
        "[PROBE A2] 许可撤销但未收紧：i1 visibility=%s，是否在导出中=%s%n",
        visibilityOf("i1"), exportedIds().contains("i1"));
  }

  @Test
  @DisplayName("5001 条撤权一次跑完：目标数、处理数、残留数三者对得上，作业落 SUCCEEDED")
  void revokingLargeSourceFinishesWithZeroResidual() {
    seedSource("src-bulk", true);
    seedBulkAchievements("src-bulk", 5_001);

    var outcome = sourceAdmin.revokeDisplayLicense(ADMIN, "src-bulk", "许可到期，撤销展示许可");

    assertThat(outcome.targetCount()).as("受理时统计的目标条数").isEqualTo(5_001L);
    assertThat(outcome.processedCount()).as("逐批落地后处理条数应等于目标").isEqualTo(5_001L);
    assertThat(outcome.residualCount()).as("完成判据是残留核对为 0，不是「处理数等于目标数」").isZero();
    assertThat(outcome.complete()).isTrue();
    assertThat(outcome.displayLicenseStatus()).isEqualTo("REVOKED");

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM core_achievement"
                    + " WHERE id LIKE 'bulk-%' AND visibility = 'PUBLIC'",
                Long.class))
        .as("没有任何一条以 PUBLIC 状态残留")
        .isZero();
    assertThat(exportedIds()).noneMatch(id -> id.startsWith("bulk-"));
    assertThat(jobStatus(outcome.jobId())).isEqualTo("SUCCEEDED");
    System.out.printf(
        "[PROBE A3] 5001 条撤权：目标=%d 处理=%d 残留=%d 完成=%s 作业=%s%n",
        outcome.targetCount(),
        outcome.processedCount(),
        outcome.residualCount(),
        outcome.complete(),
        jobStatus(outcome.jobId()));
  }

  // ===========================================================================
  // 方案 A 第二半之二：判据是「许可」，不是「采集开关」
  // ===========================================================================

  @Test
  @DisplayName("第二来源只是暂停采集时仍算有效依据；它被撤权后才算失去依据")
  void pausedSourceStillCountsAsValidEvidenceButRevokedOneDoesNot() {
    seedSource("src-x", true);
    seedSource("src-y", true);
    seedAchievement("c1");
    linkSource("c1", "src-x", "rc1");
    linkSource("c1", "src-y", "rc2");

    // 第一步：src-y 只是暂停采集（许可仍在），c1 还有 src-x，本来就不该被收紧。
    sourceAdmin.setCollectionPaused(ADMIN, "src-y", true, "维护窗口");
    assertThat(visibilityOf("c1")).isEqualTo("PUBLIC");

    // 第二步：撤掉 src-x 的许可。此时 c1 唯一的有效依据是「已暂停但仍持许可」的 src-y，
    // 所以它应当保持公开——暂停不是撤权。
    var afterFirstRevoke = sourceAdmin.revokeDisplayLicense(ADMIN, "src-x", "许可到期");
    assertThat(afterFirstRevoke.residualCount()).as("src-y 仍持许可，c1 不该被牵连").isZero();
    assertThat(visibilityOf("c1"))
        .as("停用采集的来源仍持有展示许可，因此仍是有效依据（ADR-001 §2.4 的修法）")
        .isEqualTo("PUBLIC");

    // 第三步：再撤掉 src-y。这一次两份许可都没了，c1 必须收紧。
    var afterSecondRevoke = sourceAdmin.revokeDisplayLicense(ADMIN, "src-y", "许可到期");
    assertThat(afterSecondRevoke.residualCount()).isZero();
    assertThat(visibilityOf("c1")).isEqualTo("RESTRICTED");
    assertThat(exportedIds()).doesNotContain("c1");
    System.out.printf(
        "[PROBE A4] 暂停 src-y 后撤 src-x：c1=%s；再撤 src-y 后：c1=%s%n",
        "PUBLIC（第一步断言）", visibilityOf("c1"));
  }

  @Test
  @DisplayName("恢复许可只放开因本次撤权被收紧的成果，且恢复前要重新判定是否真有有效依据")
  void restoringLicenseReopensOnlyAttributedAchievements() {
    seedSource("src-x", true);
    seedSource("src-y", true);
    seedAchievement("c1"); // x + y
    linkSource("c1", "src-x", "rc1");
    linkSource("c1", "src-y", "rc2");
    seedAchievement("d1"); // 仅 x
    linkSource("d1", "src-x", "rd1");

    sourceAdmin.revokeDisplayLicense(ADMIN, "src-x", "许可到期");
    assertThat(visibilityOf("c1")).isEqualTo("PUBLIC"); // y 仍持许可
    assertThat(visibilityOf("d1")).isEqualTo("RESTRICTED");

    var restored = sourceAdmin.restoreDisplayLicense(ADMIN, "src-x", "许可续签");
    assertThat(restored.residualCount()).isZero();
    assertThat(visibilityOf("d1")).as("因本次撤权被收紧、且已重新具备依据的成果应恢复公开").isEqualTo("PUBLIC");
    assertThat(visibilityOf("c1")).as("c1 从未被收紧，恢复动作不该顺手改它").isEqualTo("PUBLIC");
    assertThat(exportedIds()).contains("c1", "d1");

    // 归因字段是恢复的安全边界：人工限制的成果即使许可恢复了也不能被放开。
    jdbc.update("UPDATE core_achievement SET visibility = 'RESTRICTED' WHERE id = 'd1'");
    sourceAdmin.revokeDisplayLicense(ADMIN, "src-x", "许可再次到期");
    jdbc.update(
        "UPDATE core_achievement SET visibility = 'RESTRICTED',"
            + " display_restriction_source_id = NULL WHERE id = 'd1'");
    var second = sourceAdmin.restoreDisplayLicense(ADMIN, "src-x", "许可再次续签");
    assertThat(second.targetCount()).as("无归因的受限成果不计入恢复目标，宁可少恢复也不误放开").isZero();
    assertThat(visibilityOf("d1")).isEqualTo("RESTRICTED");
    System.out.printf(
        "[PROBE A5] 恢复许可：首次恢复后 d1=%s；制造无归因受限后再次恢复，目标条数=%d，d1=%s%n",
        "PUBLIC（首次断言）", second.targetCount(), visibilityOf("d1"));
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

  private String licenseOf(String sourceId) {
    return jdbc.queryForObject(
        "SELECT display_license_status FROM raw_source_config WHERE id = ?",
        String.class,
        sourceId);
  }

  private String jobStatus(String jobId) {
    return jdbc.queryForObject(
        "SELECT status FROM core_source_display_revocation WHERE id = ?", String.class, jobId);
  }

  /** 公开导出的 id 集合——查询服务看到的就是这份集合。 */
  private List<String> exportedIds() {
    return achievements.listPublicForExport(100_000, 0).stream().map(AchievementRow::id).toList();
  }
}
