package com.cognitanexus.query.it;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;

/**
 * 集成测试用的真实 PostgreSQL 库（query-service）。
 *
 * <p>连接 {@code 127.0.0.1:55432/query_it}，由 {@code ops/local/test-db.sh} 管理，与共享开发库
 * {@code query_db} 完全隔离。构造时重建 schema 并执行全部迁移。
 */
public final class PostgresTestDatabase implements AutoCloseable {

  public static final String DEFAULT_URL =
      System.getProperty("cn.it.jdbcUrl", "jdbc:postgresql://127.0.0.1:55432/query_it");

  private final String url;
  private final HikariDataSource dataSource;

  private PostgresTestDatabase(String url, HikariDataSource dataSource) {
    this.url = url;
    this.dataSource = dataSource;
  }

  public static PostgresTestDatabase migrated(String migrationLocation) {
    HikariDataSource ds = newPool(DEFAULT_URL, "postgres", 4);
    try {
      try (Connection conn = ds.getConnection();
          Statement st = conn.createStatement()) {
        st.execute("DROP SCHEMA IF EXISTS public CASCADE");
        st.execute("CREATE SCHEMA public");
        st.execute("GRANT USAGE, CREATE ON SCHEMA public TO PUBLIC");
      }
      Flyway.configure()
          .dataSource(ds)
          .locations(migrationLocation)
          .baselineOnMigrate(false)
          .validateOnMigrate(true)
          .cleanDisabled(true)
          .load()
          .migrate();
    } catch (SQLException e) {
      ds.close();
      throw new IllegalStateException(
          "集成测试库不可用（" + DEFAULT_URL + "）。先执行 bash ops/local/test-db.sh start，再以 -DskipIt=false 重跑。",
          e);
    }
    return new PostgresTestDatabase(DEFAULT_URL, ds);
  }

  private static HikariDataSource newPool(String url, String user, int poolSize) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(user);
    config.setPassword(user + "_it");
    config.setMaximumPoolSize(poolSize);
    config.setConnectionTimeout(3000);
    config.setPoolName("it-" + user);
    return new HikariDataSource(config);
  }

  public Connection superuser() throws SQLException {
    return DriverManager.getConnection(url, "postgres", "postgres_it");
  }

  public Connection runtime(String user) throws SQLException {
    return DriverManager.getConnection(url, user, user + "_it");
  }

  @Override
  public void close() {
    dataSource.close();
  }
}
