package com.cognitanexus.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 真实 PostgreSQL 上的 schema 不变量。
 *
 * <p>这组用例回答的是「迁移脚本声明的规则在数据库里真的成立吗」。此前的验证手段是断言 SQL 字符串、Mock
 * Repository 和接口自检——三者都绕过数据库本身：唯一索引没建、触发器写错、权限没回收，Mock 一律发现不了。
 *
 * <p>每个断言都对应迁移里的一句声明，来源写在各用例的注释里。
 */
class CoreSchemaInvariantsIT {

  private static PostgresTestDatabase db;

  @BeforeAll
  static void migrateOnce() {
    db = PostgresTestDatabase.migrated("classpath:db/migration");
  }

  @AfterAll
  static void closeDb() {
    if (db != null) {
      db.close();
    }
  }

  @Test
  @DisplayName("空库可完整执行全部迁移，且历史记录全部成功")
  void migrationsApplyToEmptyDatabase() throws SQLException {
    try (Connection conn = db.superuser();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL"
                    + " ORDER BY installed_rank")) {
      List<String> versions = new ArrayList<>();
      while (rs.next()) {
        assertThat(rs.getBoolean("success")).as("迁移 %s 是否成功", rs.getString("version")).isTrue();
        versions.add(rs.getString("version"));
      }
      // V1..V10：版本号跳号说明有迁移没被执行；多出说明有人手工改库。
      assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
    }
  }

  @Test
  @DisplayName("同一 DOI 归一值不能建两个成果：唯一索引在数据库层拦截，而不是靠应用先查后写")
  void duplicateDoiRejectedByUniqueIndex() throws SQLException {
    // 依据 V1 第 195 行：CREATE UNIQUE INDEX uk_core_paper_doi_norm ... WHERE doi_norm IS NOT NULL
    try (Connection conn = db.superuser()) {
      insertAchievement(conn, "it_doi_1", "PAPER");
      insertAchievement(conn, "it_doi_2", "PAPER");
      insertPaper(conn, "it_doi_1", "10.1000/it.dup");

      SQLException ex = expectSqlException(() -> insertPaper(conn, "it_doi_2", "10.1000/it.dup"));

      assertThat(ex.getSQLState()).isEqualTo("23505");
      assertThat(ex.getMessage()).contains("uk_core_paper_doi_norm");
    }
  }

  @Test
  @DisplayName("DOI 为空不参与唯一约束：两条无 DOI 的论文可以共存")
  void nullDoiDoesNotConflict() throws SQLException {
    // 部分索引 WHERE doi_norm IS NOT NULL 的语义：缺标识不是「同一个空值」，不能互相顶掉。
    try (Connection conn = db.superuser()) {
      insertAchievement(conn, "it_doi_null_1", "PAPER");
      insertAchievement(conn, "it_doi_null_2", "PAPER");
      insertPaper(conn, "it_doi_null_1", null);
      insertPaper(conn, "it_doi_null_2", null);

      assertThat(countRows(conn, "core_paper", "achievement_id LIKE 'it_doi_null_%'")).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("可发布成果缺类型扩展时，提交阶段被拒绝（延迟约束真的延迟到 commit）")
  void publishableAchievementWithoutExtensionRejectedOnCommit() throws SQLException {
    // 依据 V2 第 57-82 行：DEFERRABLE INITIALLY DEFERRED 的约束触发器。
    // 它必须延迟到提交才判断，否则治理流程里「先建实体后补扩展」的中间态会被误杀。
    try (Connection conn = db.superuser()) {
      conn.setAutoCommit(false);
      insertAchievement(conn, "it_noext", "PAPER", "PUBLIC", "EFFECTIVE");

      SQLException ex = expectSqlException(conn::commit);

      assertThat(ex.getSQLState()).isEqualTo("23514");
      assertThat(ex.getMessage()).contains("必须恰好有一个类型扩展");
      conn.rollback();
    }
  }

  @Test
  @DisplayName("类型扩展与成果类型不一致时立即拒绝：PAPER 成果不能挂专利扩展")
  void mismatchedExtensionTypeRejected() throws SQLException {
    // 依据 V2 第 13-54 行：AFTER INSERT OR UPDATE ... INITIALLY IMMEDIATE。
    try (Connection conn = db.superuser()) {
      insertAchievement(conn, "it_type_mismatch", "PAPER");

      SQLException ex =
          expectSqlException(
              () -> {
                try (PreparedStatement ps =
                    conn.prepareStatement(
                        "INSERT INTO core_patent (achievement_id, application_no)"
                            + " VALUES ('it_type_mismatch', 'IT-PAT-1')")) {
                  ps.executeUpdate();
                }
              });

      assertThat(ex.getSQLState()).isEqualTo("23514");
      assertThat(ex.getMessage()).contains("不允许建立 PATENT 扩展");
    }
  }

  @Test
  @DisplayName("运行账号对只追加表没有 UPDATE 权限：权限由迁移回收，不是靠代码自觉")
  void runtimeAccountCannotUpdateAppendOnlyTable() throws SQLException {
    // 依据 V2 第 90-106 行与 V6 第 45-49 行的 REVOKE。
    // 关键点：必须用 core_app 身份连，超级用户验证不出这类问题。
    seedSourceConfigAndRecord("it_src_perm", "it_raw_perm");

    try (Connection app = db.runtime("core_app");
        PreparedStatement ps =
            app.prepareStatement("UPDATE raw_source_record SET payload = '{}' WHERE id = ?")) {
      ps.setString(1, "it_raw_perm");
      SQLException ex = expectSqlException(ps::executeUpdate);

      assertThat(ex.getSQLState()).isEqualTo("42501");
      assertThat(ex.getMessage()).contains("raw_source_record");
    }
  }

  @Test
  @DisplayName("运行账号对只追加表仍可读：回收 UPDATE/DELETE 不等于连查询都禁掉")
  void runtimeAccountCanStillReadAppendOnlyTable() throws SQLException {
    // 反向断言：只验「被拒」会让「表根本不存在/授权全被撤」也通过，那是假绿。
    seedSourceConfigAndRecord("it_src_read", "it_raw_read");

    try (Connection app = db.runtime("core_app");
        PreparedStatement ps =
            app.prepareStatement("SELECT count(*) FROM raw_source_record WHERE id = ?")) {
      ps.setString(1, "it_raw_read");
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt(1)).isEqualTo(1);
      }
    }
  }

  @Test
  @DisplayName("乐观版本：带过期 version 的更新影响 0 行，不会被当成成功写回")
  void staleVersionUpdateAffectsNoRow() throws SQLException {
    try (Connection conn = db.superuser()) {
      insertAchievement(conn, "it_version", "PAPER");

      int first = updateWithVersion(conn, "it_version", 1, "第一次改名");
      int stale = updateWithVersion(conn, "it_version", 1, "用同一个旧版本再改一次");

      assertThat(first).as("版本匹配时更新生效").isEqualTo(1);
      assertThat(stale).as("版本已过期时不得更新").isZero();
      assertThat(titleOf(conn, "it_version")).isEqualTo("第一次改名");
    }
  }

  // ---------------------------------------------------------------------------
  // 夹具：只插入用例需要的最小列，其余走表默认值。
  // ---------------------------------------------------------------------------

  private static void insertAchievement(Connection conn, String id, String type)
      throws SQLException {
    insertAchievement(conn, id, type, "RESTRICTED", "PENDING_GOVERNANCE");
  }

  private static void insertAchievement(
      Connection conn, String id, String type, String visibility, String status)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO core_achievement"
                + " (id, type, title, title_norm, data_nature, visibility, effective_status, version)"
                + " VALUES (?, ?, ?, ?, 'REAL', ?, ?, 1)")) {
      ps.setString(1, id);
      ps.setString(2, type);
      ps.setString(3, "集成测试成果 " + id);
      ps.setString(4, "it " + id);
      ps.setString(5, visibility);
      ps.setString(6, status);
      ps.executeUpdate();
    }
  }

  private static void insertPaper(Connection conn, String achievementId, String doiNorm)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO core_paper (achievement_id, doi, doi_norm) VALUES (?, ?, ?)")) {
      ps.setString(1, achievementId);
      ps.setString(2, doiNorm);
      ps.setString(3, doiNorm);
      ps.executeUpdate();
    }
  }

  private static void seedSourceConfigAndRecord(String sourceId, String recordId)
      throws SQLException {
    try (Connection conn = db.superuser()) {
      try (PreparedStatement ps =
          conn.prepareStatement(
              "INSERT INTO raw_source_config"
                  + " (id, source_code, display_name, enabled, license_scope, base_url)"
                  + " VALUES (?, ?, ?, true, 'CC0', 'https://example.invalid')"
                  + " ON CONFLICT (id) DO NOTHING")) {
        ps.setString(1, sourceId);
        ps.setString(2, sourceId);
        ps.setString(3, "集成测试来源 " + sourceId);
        ps.executeUpdate();
      }
      try (PreparedStatement ps =
          conn.prepareStatement(
              "INSERT INTO raw_source_record"
                  + " (id, source_id, source_record_id, content_fingerprint, payload,"
                  + "  data_nature, fetched_at, catalog_revision)"
                  + " VALUES (?, ?, ?, ?, '{}'::jsonb, 'REAL', now(), 1)"
                  + " ON CONFLICT (id) DO NOTHING")) {
        ps.setString(1, recordId);
        ps.setString(2, sourceId);
        ps.setString(3, recordId);
        ps.setString(4, "fp-" + recordId);
        ps.executeUpdate();
      }
    }
  }

  private static int updateWithVersion(Connection conn, String id, long version, String title)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "UPDATE core_achievement SET title = ?, version = version + 1"
                + " WHERE id = ? AND version = ?")) {
      ps.setString(1, title);
      ps.setString(2, id);
      ps.setLong(3, version);
      return ps.executeUpdate();
    }
  }

  private static String titleOf(Connection conn, String id) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("SELECT title FROM core_achievement WHERE id = ?")) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  private static int countRows(Connection conn, String table, String where) throws SQLException {
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table + " WHERE " + where)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private static SQLException expectSqlException(ThrowingAction action) {
    try {
      action.run();
    } catch (SQLException e) {
      return e;
    }
    throw new AssertionError("预期数据库拒绝该操作，但它成功了");
  }

  @FunctionalInterface
  private interface ThrowingAction {
    void run() throws SQLException;
  }
}
