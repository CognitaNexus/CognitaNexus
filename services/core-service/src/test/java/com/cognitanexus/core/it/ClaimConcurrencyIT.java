package com.cognitanexus.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cognitanexus.core.identity.application.IdentityRepositories.PortalAuthorityRepository;
import com.cognitanexus.core.identity.domain.PortalAuthority;
import com.cognitanexus.core.portal.application.PortalService;
import com.cognitanexus.core.portal.domain.ClaimRequest;
import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 认领互斥的真实数据库验证（T10／T11／T12，§7.1 A 阶段的「受控双事务／多审核者竞争」）。
 *
 * <p>为什么必须是真实数据库：这三条的断言对象是「同一门户到底有几条生效授权」「两笔并发插入谁被拦下、
 * 事务回滚到哪里」「被拦下的那笔会不会把已批准申请改判成争议」。这些结论全部由
 * {@code uk_iam_portal_authority_active}（部分唯一索引）与行锁的交互决定，Mock 仓储只能复述实现里的 if。
 *
 * <p>重叠是<b>受控制造</b>的：{@code @BeforeEach} 在 {@code iam_portal_authority} 上装一个只做延迟的触发器，
 * 让两笔插入几乎同时到达索引，从而稳定走到「都通过了前置查询、由唯一索引裁决」这条路径。触发器只加延迟，
 * 不改约束、不改数据，也不参与断言；没有它时两笔事务会自然串行，测试看似通过却没验证到索引。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.session.store-type=none",
      "cn.outbox.dispatcher-enabled=false",
      "cn.bootstrap.platform-admin-username=it-admin"
    })
class ClaimConcurrencyIT {

  private static final String RUN = "cc" + Long.toString(System.nanoTime(), 36);

  private static final String SCHOLAR = RUN + "-scholar";

  private static final String APPLICANT_A = RUN + "-acc-a";
  private static final String APPLICANT_B = RUN + "-acc-b";
  private static final String REVIEWER = RUN + "-acc-reviewer-a";
  private static final String REVIEWER_2 = RUN + "-acc-reviewer-b";

  @Autowired private PortalService portal;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private PortalAuthorityRepository authorities;

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
    installOverlapTrigger();
    seedScholar();
    seedAccount(APPLICANT_A, "USER");
    seedAccount(APPLICANT_B, "USER");
    seedAccount(REVIEWER, "REVIEWER");
    seedAccount(REVIEWER_2, "REVIEWER");
  }

  // ===========================================================================
  // T11：两个账号各自提交认领、两个审核者并发批准
  // ===========================================================================

  @Test
  @DisplayName("T11 竞争认领并发批准：全库只剩一条生效授权，败者落入争议且不转移原授权")
  void competingClaimsLeaveExactlyOneActiveAuthority() throws Exception {
    ClaimRequest claimA = submit(APPLICANT_A, "我是该门户的主人，附 ORCID 与单位主页");
    ClaimRequest claimB = submit(APPLICANT_B, "我也主张这个门户，附另一份身份材料");
    assertThat(claimA.id()).isNotEqualTo(claimB.id());

    // 两个审核者同时裁决两份申请：都读到「该门户尚无生效授权」，再一起去插唯一索引。
    AtomicReference<Object> outcomeA = new AtomicReference<>();
    AtomicReference<Object> outcomeB = new AtomicReference<>();
    CyclicBarrier start = new CyclicBarrier(2);
    Thread ta = decideThread(REVIEWER, claimA.id(), outcomeA, start);
    Thread tb = decideThread(REVIEWER_2, claimB.id(), outcomeB, start);
    ta.start();
    tb.start();
    ta.join(TimeUnit.SECONDS.toMillis(30));
    tb.join(TimeUnit.SECONDS.toMillis(30));
    assertThat(ta.isAlive()).as("审核线程未在 30 秒内结束").isFalse();
    assertThat(tb.isAlive()).as("审核线程未在 30 秒内结束").isFalse();

    List<Map<String, Object>> active =
        jdbc.queryForList(
            "SELECT id, account_id, claim_request_id FROM iam_portal_authority"
                + " WHERE scholar_id = ? AND status = 'ACTIVE'",
            SCHOLAR);
    assertThat(active).as("一个门户任一时刻至多一条生效授权（AC-IMPL-FR05-03）").hasSize(1);

    // 谁赢不确定，但必须是「一方 APPROVED 且拿到授权、另一方 DISPUTED 且没拿到授权」。
    List<Map<String, Object>> claims =
        jdbc.queryForList(
            "SELECT id, status FROM wf_claim_request WHERE scholar_id = ? ORDER BY id", SCHOLAR);
    assertThat(claims).hasSize(2);
    String approvedClaim =
        claims.stream()
            .filter(row -> "APPROVED".equals(row.get("status")))
            .map(row -> String.valueOf(row.get("id")))
            .findFirst()
            .orElse(null);
    String disputedClaim =
        claims.stream()
            .filter(row -> "DISPUTED".equals(row.get("status")))
            .map(row -> String.valueOf(row.get("id")))
            .findFirst()
            .orElse(null);
    assertThat(approvedClaim).as("必须恰好有一份申请被批准，实际为 %s", claims).isNotNull();
    assertThat(disputedClaim).as("另一份申请必须落入争议，实际为 %s", claims).isNotNull();
    assertThat(approvedClaim).isNotEqualTo(disputedClaim);
    assertThat(String.valueOf(active.get(0).get("claim_request_id")))
        .as("生效授权必须指向被批准的那份申请，而不是被争议的那份")
        .isEqualTo(approvedClaim);
    assertThat(String.valueOf(active.get(0).get("account_id")))
        .as("授权只给申请人账号，不给审核者账号")
        .isIn(APPLICANT_A, APPLICANT_B);

    // 败者拿到的是「已标记争议」这种可解释结果，而不是把异常抛给审核者或静默失败。
    Object loser =
        String.valueOf(active.get(0).get("account_id")).equals(APPLICANT_A)
            ? outcomeB.get()
            : outcomeA.get();
    assertThat(loser)
        .as("败者必须返回 DISPUTED 结果，而不是抛出未处理异常：%s", loser)
        .isInstanceOf(PortalService.DecisionResult.class);
    assertThat(((PortalService.DecisionResult) loser).status()).isEqualTo("DISPUTED");

    // 两种竞争路径都要留痕，而且能分清是哪一种：前置查询发现已有授权，还是插入时被唯一索引拦下。
    // 只留「申请变成 DISPUTED」这一个结果，事后无法回答「当时到底有没有并发」，也无法解释为什么没有第二条授权。
    List<Map<String, Object>> traces =
        jdbc.queryForList(
            "SELECT after_value->>'detection' AS detection FROM aud_audit_event"
                + " WHERE action = 'CLAIM_COMPETITION_DETECTED' AND target_id = ?",
            disputedClaim);
    assertThat(traces).as("竞争认领必须在审计里留一条可分辨的痕迹，实际为 %s", traces).hasSize(1);
    assertThat(String.valueOf(traces.get(0).get("detection")))
        .as("痕迹要能区分抑制发生在哪一步")
        .isIn("PRE_CHECK", "UNIQUE_INDEX");

    // 学者状态：认领通过即 CLAIMED，不因另一份申请被争议而回退。
    assertThat(
            jdbc.queryForObject(
                "SELECT claimed_status FROM core_scholar WHERE id = ?", String.class, SCHOLAR))
        .isEqualTo("CLAIMED");
  }

  // ===========================================================================
  // T12：同一份申请被两个审核者同时批准
  // ===========================================================================

  @Test
  @DisplayName("T12 同一份申请并发批准：只产生一条授权，且不把已批准改判为争议")
  void sameClaimApprovedTwiceDoesNotDowngrade() throws Exception {
    ClaimRequest claim = submit(APPLICANT_A, "同一份申请，两位审核者同时点了批准");

    AtomicReference<Object> first = new AtomicReference<>();
    AtomicReference<Object> second = new AtomicReference<>();
    CyclicBarrier start = new CyclicBarrier(2);
    Thread t1 = decideThread(REVIEWER, claim.id(), first, start);
    Thread t2 = decideThread(REVIEWER_2, claim.id(), second, start);
    t1.start();
    t2.start();
    t1.join(TimeUnit.SECONDS.toMillis(30));
    t2.join(TimeUnit.SECONDS.toMillis(30));
    assertThat(t1.isAlive()).as("审核线程未在 30 秒内结束").isFalse();
    assertThat(t2.isAlive()).as("审核线程未在 30 秒内结束").isFalse();

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM iam_portal_authority WHERE scholar_id = ? AND status = 'ACTIVE'",
                Integer.class,
                SCHOLAR))
        .as("重复批准不得产生第二条生效授权")
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM wf_claim_request WHERE id = ?", String.class, claim.id()))
        .as("已批准的申请不能被并发重复批准改判成争议")
        .isEqualTo("APPROVED");

    // 两笔之中至少一笔成功；另一笔允许两种情况：幂等返回 APPROVED，或明确报版本冲突让审核者重试。
    long succeeded =
        List.of(first.get(), second.get()).stream()
            .filter(
                o -> o instanceof PortalService.DecisionResult r && "APPROVED".equals(r.status()))
            .count();
    assertThat(succeeded)
        .as("至少一笔批准必须成功：%s / %s", first.get(), second.get())
        .isGreaterThanOrEqualTo(1);
    Object other =
        first.get() instanceof PortalService.DecisionResult r && "APPROVED".equals(r.status())
            ? second.get()
            : first.get();
    if (other instanceof Throwable error) {
      assertThat(error).as("竞争失败必须是可解释的业务异常，而不是数据库唯一约束异常").isInstanceOf(BusinessException.class);
      assertThat(((BusinessException) error).errorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT);
    } else {
      assertThat(((PortalService.DecisionResult) other).status())
          .as("重复批准要么幂等成功，要么明确冲突；不能落成 DISPUTED")
          .isEqualTo("APPROVED");
    }
  }

  // ===========================================================================
  // 受控双事务：唯一性到底由谁保证
  // ===========================================================================

  @Test
  @DisplayName("T10 受控双事务：先插入未提交时第二笔插入被索引阻塞并最终返回 0 行")
  void secondActiveAuthorityBlockedUntilFirstCommits() throws Exception {
    AtomicReference<Object> blocked = new AtomicReference<>();
    try (Connection first = dataSource.getConnection()) {
      first.setAutoCommit(false);
      try (var ps =
          first.prepareStatement(
              "INSERT INTO iam_portal_authority"
                  + " (id, account_id, scholar_id, status, claim_request_id, granted_at, granted_by_account_id)"
                  + " VALUES (?, ?, ?, 'ACTIVE', NULL, now(), NULL)")) {
        ps.setString(1, RUN + "-pta-first");
        ps.setString(2, APPLICANT_A);
        ps.setString(3, SCHOLAR);
        assertThat(ps.executeUpdate()).isEqualTo(1);
      }

      Thread second =
          new Thread(
              () -> {
                try (Connection other = dataSource.getConnection()) {
                  other.setAutoCommit(false);
                  try (var ps =
                      other.prepareStatement(
                          "INSERT INTO iam_portal_authority"
                              + " (id, account_id, scholar_id, status, claim_request_id, granted_at, granted_by_account_id)"
                              + " VALUES (?, ?, ?, 'ACTIVE', NULL, now(), NULL)"
                              + " ON CONFLICT (scholar_id) WHERE status = 'ACTIVE' DO NOTHING")) {
                    ps.setString(1, RUN + "-pta-second");
                    ps.setString(2, APPLICANT_B);
                    ps.setString(3, SCHOLAR);
                    blocked.set(ps.executeUpdate());
                  }
                  other.commit();
                } catch (SQLException e) {
                  blocked.set(e);
                }
              });
      second.setDaemon(true);
      second.start();
      // 让对方走到插入处并卡在索引等待上，再提交先到者。
      Thread.sleep(400);
      first.commit();
      second.join(TimeUnit.SECONDS.toMillis(30));
    }

    assertThat(blocked.get()).as("第二笔插入必须返回 0 行影响而不是抛异常：%s", blocked.get()).isEqualTo(0);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM iam_portal_authority WHERE scholar_id = ? AND status = 'ACTIVE'",
                Integer.class,
                SCHOLAR))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("T10 反证：删掉部分唯一索引后同一语句直接 42P10，证明唯一性由索引而非应用代码保证")
  void partialUniqueIndexIsWhatEnforcesUniqueness() {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      // DDL 在 PostgreSQL 里是事务性的：这里删索引后由外层回滚，测试库结构不受影响。
                      jdbc.execute("DROP INDEX uk_iam_portal_authority_active");
                      authorities.insertActive(
                          PortalAuthority.active(
                              RUN + "-pta-mutation", APPLICANT_A, SCHOLAR, null, Instant.now()),
                          REVIEWER);
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ON CONFLICT")
        .as("没有该索引时 ON CONFLICT 无法定位仲裁对象，语句本身不成立");

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'uk_iam_portal_authority_active'",
                Integer.class))
        .as("反证后索引必须还在（事务已回滚）")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("撤销后可以重新授予：部分唯一索引只约束 ACTIVE 行")
  void revokedAuthorityDoesNotBlockRegrant() {
    assertThat(
            authorities.insertActive(
                PortalAuthority.active(RUN + "-pta-1", APPLICANT_A, SCHOLAR, null, Instant.now()),
                REVIEWER))
        .isTrue();
    assertThat(authorities.revoke(RUN + "-pta-1", "用户主动放弃门户管理权", Instant.now())).isTrue();
    assertThat(
            authorities.insertActive(
                PortalAuthority.active(RUN + "-pta-2", APPLICANT_B, SCHOLAR, null, Instant.now()),
                REVIEWER))
        .as("历史授权是 REVOKED 时不应阻塞新的生效授权")
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM iam_portal_authority WHERE scholar_id = ?",
                Integer.class,
                SCHOLAR))
        .as("撤销是保留痕迹而不是删除记录")
        .isEqualTo(2);
  }

  // ===========================================================================
  // 夹具
  // ===========================================================================

  /**
   * 只加延迟的重叠触发器。让两笔插入近乎同时抵达索引，从而稳定复现「都通过了前置查询」的那种竞争；
   * 不加延迟时，Spring 的单次调用太快，两笔事务经常自然串行，测试就只验证了 if 分支。
   */
  private void installOverlapTrigger() {
    jdbc.execute(
        """
        CREATE OR REPLACE FUNCTION it_authority_delay() RETURNS trigger AS $$
        BEGIN
          PERFORM pg_sleep(0.4);
          RETURN NEW;
        END $$ LANGUAGE plpgsql
        """);
    jdbc.execute("DROP TRIGGER IF EXISTS it_authority_delay ON iam_portal_authority");
    jdbc.execute(
        "CREATE TRIGGER it_authority_delay BEFORE INSERT ON iam_portal_authority"
            + " FOR EACH ROW EXECUTE FUNCTION it_authority_delay()");
  }

  private void seedScholar() {
    jdbc.update(
        "INSERT INTO core_scholar (id, display_name, name_norm, data_nature, claimed_status)"
            + " VALUES (?, ?, ?, 'SIMULATED', 'UNCLAIMED')",
        SCHOLAR,
        "并发认领测试学者",
        "并发认领测试学者");
  }

  private void seedAccount(String id, String role) {
    jdbc.update(
        "INSERT INTO iam_account (id, username, username_norm, password_hash, display_name, status)"
            + " VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
        id,
        id,
        id,
        "$2a$10$it-only-not-a-real-hash",
        id);
    jdbc.update("INSERT INTO iam_account_role (account_id, role) VALUES (?, ?)", id, role);
  }

  private ClaimRequest submit(String accountId, String statement) {
    return portal.submitClaim(
        actor(accountId, "USER"), SCHOLAR, statement, "idem-" + accountId + "-" + RUN);
  }

  private Thread decideThread(
      String reviewer, String claimId, AtomicReference<Object> sink, CyclicBarrier start) {
    Thread thread =
        new Thread(
            () -> {
              try {
                start.await(10, TimeUnit.SECONDS);
                sink.set(
                    portal.decideClaim(actor(reviewer, "REVIEWER"), claimId, "APPROVE", "材料齐全"));
              } catch (Throwable e) {
                sink.set(e);
              }
            });
    thread.setDaemon(true);
    return thread;
  }

  private static com.cognitanexus.core.platform.security.ActorContext actor(
      String accountId, String role) {
    return new com.cognitanexus.core.platform.security.ActorContext(
        new AccountId(accountId), accountId, accountId, java.util.Set.of(role), 1L);
  }
}
