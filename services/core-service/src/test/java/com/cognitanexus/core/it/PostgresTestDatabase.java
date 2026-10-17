package com.cognitanexus.core.it;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * 集成测试用的真实 PostgreSQL 库。
 *
 * <p>与共享开发库完全隔离：连接 {@code 127.0.0.1:55432/core_it}，该实例由 {@code
 * ops/local/test-db.sh} 管理（独立数据目录、独立端口），不读写 {@code core_db}。每次建库重新执行
 * DROP/CREATE SCHEMA + Flyway，因此用例之间只共享「迁移结果」，不共享业务数据。
 *
 * <p>为什么不用 Testcontainers：本机 Docker Desktop 依赖 WSL2，而 WSL 被安全策略拦截，守护进程起不来
 * （见 {@code ops/local/start-infra.sh} 头注释）。写成「Docker 在才跑」的测试在本机永远不会执行，等于没有。
 * 这里用的是同一套 PG 16 二进制，真实迁移、真实 SQL、真实事务与约束；差别只在实例由脚本管理而非容器。
 *
 * <p>实例未启动时构造会直接抛异常而不是跳过：跳过在报告里显示为 skipped，容易被误读成「已验证」。
 */
public final class PostgresTestDatabase implements AutoCloseable {

  /** 可用 -Dcn.it.jdbcUrl 覆盖；端口对应 ops/local/test-db.sh 的 IT_PORT。 */
  public static final String DEFAULT_URL =
      System.getProperty("cn.it.jdbcUrl", "jdbc:postgresql://127.0.0.1:55432/core_it");

  private final String url;
  private final HikariDataSource dataSource;

  private PostgresTestDatabase(String url, HikariDataSource dataSource) {
    this.url = url;
    this.dataSource = dataSource;
  }

  /**
   * 重置 schema 并执行全部迁移。
   *
   * @param migrationLocation 迁移目录，例如 {@code classpath:db/migration}
   * @return 迁移完成的库
   */
  public static PostgresTestDatabase migrated(String migrationLocation) {
    HikariDataSource ds = newPool(DEFAULT_URL, "postgres", 4);
    try {
      resetPublicSchema(ds);
      Flyway flyway =
          Flyway.configure()
              .dataSource(ds)
              .locations(migrationLocation)
              .baselineOnMigrate(false)
              .validateOnMigrate(true)
              .cleanDisabled(true)
              .load();
      MigrateResult result = flyway.migrate();
      if (!result.success) {
        throw new IllegalStateException("迁移未成功完成：" + result.initialSchemaVersion);
      }
    } catch (SQLException e) {
      ds.close();
      throw new IllegalStateException(
          "集成测试库不可用（"
              + DEFAULT_URL
              + "）。先执行 bash ops/local/test-db.sh start，再以 -DskipITs=false 重跑。",
          e);
    } catch (RuntimeException e) {
      ds.close();
      throw e;
    }
    return new PostgresTestDatabase(DEFAULT_URL, ds);
  }

  private static HikariDataSource newPool(String url, String user, int poolSize) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(user);
    // 实例初始化时设为 trust 认证，密码不参与校验；仍显式给出，避免驱动在空密码时行为不一致。
    config.setPassword(user + "_it");
    config.setMaximumPoolSize(poolSize);
    config.setConnectionTimeout(3000);
    config.setPoolName("it-" + user);
    return new HikariDataSource(config);
  }

  private static void resetPublicSchema(HikariDataSource ds) throws SQLException {
    try (Connection conn = ds.getConnection();
        Statement st = conn.createStatement()) {
      // CASCADE 会一并删掉 V5 装在 public 下的 pg_trgm 扩展，随后由迁移重新创建。
      st.execute("DROP SCHEMA IF EXISTS public CASCADE");
      st.execute("CREATE SCHEMA public");
      st.execute("GRANT USAGE, CREATE ON SCHEMA public TO PUBLIC");
    }
  }

  /** 迁移账号（超级用户）连接：用于造数与断言表结构。 */
  public Connection superuser() throws SQLException {
    return DriverManager.getConnection(url, "postgres", "postgres_it");
  }

  /**
   * 运行账号连接。用于验证迁移里声明的权限边界是否真的生效——只用超级用户测不出这类问题，
   * 因为超级用户绕过一切授权。
   */
  public Connection runtime(String user) throws SQLException {
    return DriverManager.getConnection(url, user, user + "_it");
  }

  @Override
  public void close() {
    dataSource.close();
  }
}
