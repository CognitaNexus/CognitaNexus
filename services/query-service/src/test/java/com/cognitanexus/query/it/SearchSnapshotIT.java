package com.cognitanexus.query.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.corelink.CoreInternalClient;
import com.cognitanexus.query.discovery.application.SearchQuery;
import com.cognitanexus.query.discovery.application.SearchService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 检索快照一致性（WB-06，验收 T17）。
 *
 * <p>要证明的是：一次请求里的 total 与 items 来自同一个数据库快照。做法不是断言「调了几次 Mapper」，
 * 而是在 count 返回之后、list 执行之前，用一条独立连接提交一条新的公开成果，再看响应里的 total 与
 * items 是否仍然匹配。
 *
 * <p>时序靠 MyBatis 拦截器制造，而不是 Mockito 的 spy：SearchMapper 是 MyBatis 的 JDK 动态代理，
 * 对接口代理调用 {@code callRealMethod()} 会抛「Cannot call abstract real method」，测不了。
 *
 * <p>为什么必须真库：默认 READ COMMITTED 下每条语句各取新快照，上面这个时序会得到
 * 「total=1、items=2」——两个数字单看都对，合起来是错的。Mock 复现不出这个现象。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    // 投影工作器会周期性拉取事件并写投影，测试中必须关闭，否则与用例争抢同一批表。
    properties = {"cn.projection.worker-enabled=false"})
@Import(SearchSnapshotIT.CountHookConfiguration.class)
class SearchSnapshotIT {

  private static final String GENERATION = "gen0001";
  private static final long REVISION = 20L;

  /** 拦截器是否要在 count 之后插一条干扰数据。由用例显式打开，避免影响其他用例。 */
  private static final AtomicBoolean HOOK_ARMED = new AtomicBoolean(false);

  /** 每个用例只制造一次干扰。 */
  private static final AtomicBoolean HOOK_FIRED = new AtomicBoolean(false);

  @Autowired private SearchService search;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  /** 公开资格屏障每次向 core 取权威水位；测试里固定返回同一个值。 */
  @MockitoBean private CoreInternalClient core;

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
  void prepareProjection() throws SQLException {
    HOOK_ARMED.set(false);
    HOOK_FIRED.set(false);
    try (Connection conn = dataSource.getConnection()) {
      assertThat(conn.getMetaData().getURL())
          .as("集成测试必须连 55432 的隔离实例")
          .contains(":55432/")
          .contains("query_it");
    }
    jdbc.execute("TRUNCATE qry_achievement");
    jdbc.update(
        "UPDATE qry_consumer_state SET active_generation = ?, received_revision = ?,"
            + " highest_seen_revision = ?, published_revision = ? WHERE consumer_id = 'query'",
        GENERATION,
        REVISION,
        REVISION,
        REVISION);
    when(core.readBarrier()).thenReturn(new CoreInternalClient.PublicBarrier(REVISION, REVISION));
    insert("ach_snap_1", "第一条成果");
  }

  @Test
  @DisplayName("T17 count 与 list 之间提交新投影，total 与 items 仍来自同一快照")
  void countAndListShareOneSnapshot() {
    HOOK_ARMED.set(true);

    Map<String, Object> data = search.search(emptyQuery());

    long total = ((Number) data.get("total")).longValue();
    List<?> items = (List<?>) data.get("items");

    assertThat(HOOK_FIRED).as("干扰数据必须在 count 之后真正提交").isTrue();
    assertThat(total).as("计数只应包含快照内的成果").isEqualTo(1L);
    assertThat(items).as("列表必须与计数来自同一快照；长度不同说明两条 SQL 各自取了新快照").hasSize((int) total);
    assertThat(data.get("queryVersion")).isNotNull();
  }

  @Test
  @DisplayName("快照不跨请求：之前提交的投影在下一次请求里必须可见")
  void nextRequestSeesCommittedData() {
    insert("ach_snap_3", "提前提交的第三条成果");

    Map<String, Object> data = search.search(emptyQuery());

    assertThat(((Number) data.get("total")).longValue())
        .as("新请求必须看到已提交的投影，快照只在本请求内生效")
        .isEqualTo(2L);
  }

  // ---------------------------------------------------------------------------

  private SearchQuery emptyQuery() {
    // 不传 queryVersion：首次请求本来就没有旧 token，版本校验对 null 放行。
    return new SearchQuery(null, null, null, null, null, null, null, null, null, 1, 10, null);
  }

  private void insert(String id, String title) {
    jdbc.update(
        "INSERT INTO qry_achievement"
            + " (generation, achievement_id, type, title, title_norm, data_nature, visibility,"
            + "  effective_status, publication_year, aggregate_version, revision)"
            + " VALUES (?, ?, 'PAPER', ?, ?, 'REAL', 'PUBLIC', 'EFFECTIVE', 2024, 1, ?)",
        GENERATION,
        id,
        title,
        title,
        REVISION);
  }

  @TestConfiguration
  static class CountHookConfiguration {

    @Bean
    ConfigurationCustomizer countHookInterceptor(DataSource dataSource) {
      return configuration -> configuration.addInterceptor(new CountHook(dataSource));
    }
  }

  /** 只拦 countHits，在其真正执行完拿到结果之后再写入干扰行。 */
  @Intercepts({
    @Signature(
        type = Executor.class,
        method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
  })
  static class CountHook implements Interceptor {

    private final DataSource dataSource;

    CountHook(DataSource dataSource) {
      this.dataSource = dataSource;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
      Object result = invocation.proceed();
      MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
      if (statement.getId().endsWith(".countHits")
          && HOOK_ARMED.get()
          && HOOK_FIRED.compareAndSet(false, true)) {
        new SearchSnapshotIT.DirectWriter(dataSource).insert("ach_snap_2", "并发提交的第二条成果");
      }
      return result;
    }
  }

  /** 把插入逻辑抽出来给拦截器复用；拦截器不是测试实例，拿不到外部方法。 */
  static class DirectWriter {

    private final DataSource dataSource;

    DirectWriter(DataSource dataSource) {
      this.dataSource = dataSource;
    }

    void insert(String id, String title) {
      try (Connection conn = dataSource.getConnection()) {
        conn.setAutoCommit(true);
        try (PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO qry_achievement"
                    + " (generation, achievement_id, type, title, title_norm, data_nature,"
                    + "  visibility, effective_status, publication_year, aggregate_version,"
                    + "  revision)"
                    + " VALUES (?, ?, 'PAPER', ?, ?, 'REAL', 'PUBLIC', 'EFFECTIVE', 2024, 1, ?)")) {
          ps.setString(1, GENERATION);
          ps.setString(2, id);
          ps.setString(3, title);
          ps.setString(4, title);
          ps.setLong(5, REVISION);
          ps.executeUpdate();
        }
      } catch (SQLException e) {
        throw new IllegalStateException("独立连接写入失败", e);
      }
    }
  }
}
