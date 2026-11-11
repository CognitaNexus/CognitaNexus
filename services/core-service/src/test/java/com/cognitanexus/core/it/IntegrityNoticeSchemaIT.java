package com.cognitanexus.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 学术诚信声明的数据库级不变量（真实 PostgreSQL）。
 *
 * <p>依据：迁移 {@code V13__achievement_integrity_notice.sql}。三条声明在迁移里都有对应机制，
 * 而三者的失效方式都**不会报错**：
 *
 * <ul>
 *   <li>「同一成果同一类型只能一条生效声明」靠部分唯一索引。索引若写成非部分索引，第二条合法
 *       申请（撤销后重新登记）会被拒；若漏了 {@code WHERE status='ACTIVE'}，撤销过的那条会永久
 *       占位，后续更正永远登记不上。
 *   <li>「撤销必须成套」靠 CHECK。缺了它，撤销可以没有理由，事后没人能解释这次撤销是纠正误登记
 *       还是被谁压下去了。
 *   <li>「类型只能是三者之一」靠 CHECK。缺了它，拼写错误会成为一条永远匹配不到任何展示逻辑的
 *       声明，详情页安静地不显示任何标记。
 * </ul>
 *
 * <p>这三条用 Mock 与接口自检都验不出来：前者根本不进数据库，后者只能看到「接口返回 400」，
 * 而分不清是被服务层挡下还是被数据库挡下——服务层挡得住今天的调用方，挡不住明天新增的调用方。
 */
class IntegrityNoticeSchemaIT {

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

  @BeforeEach
  void seed() throws Exception {
    try (Connection conn = db.superuser();
        Statement st = conn.createStatement()) {
      // 账号与成果都按「最小合法状态」插入：成果用 RESTRICTED/PENDING_GOVERNANCE，
      // 避免踩到「可发布成果必须恰好有一个类型扩展」这条延迟约束。
      st.executeUpdate(
          "INSERT INTO iam_account (id, username, username_norm, password_hash, display_name, status)"
              + " VALUES ('acc_it', 'it_user', 'it_user', 'x', '集成测试账号', 'ACTIVE')"
              + " ON CONFLICT (id) DO NOTHING");
      st.executeUpdate(
          "INSERT INTO core_achievement (id, type, title, title_norm, data_nature)"
              + " VALUES ('ach_it', 'PAPER', 'Retracted Example', 'retracted example', 'REAL')"
              + " ON CONFLICT (id) DO NOTHING");
      st.executeUpdate(
          "DELETE FROM core_achievement_integrity_notice WHERE achievement_id = 'ach_it'");
    }
  }

  @Test
  @DisplayName("同一成果同一类型只能有一条生效声明：第二条被唯一索引拒绝")
  void rejectsSecondActiveNoticeOfSameType() throws Exception {
    insertNotice("itg_1", "RETRACTION", "ACTIVE", null);
    assertThatThrownBy(() -> insertNotice("itg_2", "RETRACTION", "ACTIVE", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_core_integrity_active");
  }

  @Test
  @DisplayName("撤销后可以重新登记同类型声明：唯一索引只约束 ACTIVE 行")
  void allowsReRegisteringAfterRevoke() throws Exception {
    insertNotice("itg_1", "RETRACTION", "ACTIVE", null);
    revoke("itg_1");
    insertNotice("itg_3", "RETRACTION", "ACTIVE", null);

    try (Connection conn = db.superuser();
        Statement st = conn.createStatement();
        var rs =
            st.executeQuery(
                "SELECT count(*) FROM core_achievement_integrity_notice"
                    + " WHERE achievement_id = 'ach_it' AND notice_type = 'RETRACTION'"
                    + " AND status = 'ACTIVE'")) {
      rs.next();
      assertThat(rs.getInt(1)).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("撤销必须成套：状态为 REVOKED 却缺撤销理由/撤销人，直接违反 CHECK")
  void revokedRowRequiresRevokeFields() {
    assertThatThrownBy(() -> insertNotice("itg_4", "CORRECTION", "REVOKED", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ck_core_integrity_revoked");

    // 只补时间不补理由，仍然不成立：这三个字段是一件事，不能挑着填。
    assertThatThrownBy(() -> insertNotice("itg_5", "CORRECTION", "REVOKED", Instant.now()))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ck_core_integrity_revoked");
  }

  @Test
  @DisplayName("类型与状态只能是枚举值：拼写错误的声明不能落库")
  void rejectsUnknownTypeAndStatus() {
    assertThatThrownBy(() -> insertNotice("itg_6", "RETRACT", "ACTIVE", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ck_core_integrity_type");
    // 未知状态会同时违反两条 CHECK（状态枚举与「撤销必须成套」都不成立），
    // PostgreSQL 只报先命中的那一条，因此这里不能钉死约束名——实测过：同一条语句报的是
    // ck_core_integrity_revoked。能钉住的是「被拒绝」这件事本身。
    assertThatThrownBy(() -> insertNotice("itg_7", "RETRACTION", "PENDING", null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ck_core_integrity_");
    assertThat(countOf("itg_7")).isZero();
  }

  @Test
  @DisplayName("声明主体与理由不能是空白：空白等于一条没有出处的判断")
  void rejectsBlankDeclaredByAndReason() {
    assertThatThrownBy(() -> insert("itg_8", "CONCERN", "ACTIVE", "  ", "有实质内容的理由"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ck_core_integrity_declared_by");
    assertThatThrownBy(() -> insert("itg_9", "CONCERN", "ACTIVE", "某期刊", " "))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ck_core_integrity_reason");
  }

  // ---------------------------------------------------------------------------

  private static int countOf(String id) {
    try (Connection conn = db.superuser();
        PreparedStatement ps =
            conn.prepareStatement(
                "SELECT count(*) FROM core_achievement_integrity_notice WHERE id = ?")) {
      ps.setString(1, id);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 默认夹具：声明主体与理由都齐备，由各用例决定类型、状态与撤销时间。 */
  private static void insertNotice(String id, String type, String status, Instant revokedAt)
      throws SQLException {
    insert(id, type, status, "示例期刊编辑部", "经调查确认图表数据存在重复使用", revokedAt, null);
  }

  private static void insert(
      String id, String type, String status, String declaredBy, String reason) throws SQLException {
    insert(id, type, status, declaredBy, reason, null, null);
  }

  private static void insert(
      String id,
      String type,
      String status,
      String declaredBy,
      String reason,
      Instant revokedAt,
      String revokeReason)
      throws SQLException {
    try (Connection conn = db.superuser();
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO core_achievement_integrity_notice"
                    + " (id, achievement_id, notice_type, status, declared_by, reason, recorded_by,"
                    + "  recorded_at, revoked_by, revoked_at, revoke_reason)"
                    + " VALUES (?, 'ach_it', ?, ?, ?, ?, 'acc_it', now(), ?, ?, ?)")) {
      ps.setString(1, id);
      ps.setString(2, type);
      ps.setString(3, status);
      ps.setString(4, declaredBy);
      ps.setString(5, reason);
      ps.setString(6, "REVOKED".equals(status) ? "acc_it" : null);
      if (revokedAt == null) {
        ps.setTimestamp(7, null);
      } else {
        ps.setTimestamp(7, Timestamp.from(revokedAt));
      }
      ps.setString(8, revokeReason);
      ps.executeUpdate();
    }
  }

  private static void revoke(String id) throws SQLException {
    try (Connection conn = db.superuser();
        PreparedStatement ps =
            conn.prepareStatement(
                "UPDATE core_achievement_integrity_notice"
                    + " SET status = 'REVOKED', revoked_by = 'acc_it', revoked_at = now(),"
                    + "     revoke_reason = '复核后撤回原判断'"
                    + " WHERE id = ?")) {
      ps.setString(1, id);
      ps.executeUpdate();
    }
  }
}
