package com.cognitanexus.query.graph.infrastructure;

import com.cognitanexus.query.graph.config.Neo4jGraphProperties;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.exceptions.Neo4jException;

/**
 * Neo4j 会话的薄封装：统一数据库选择、事务超时与异常边界。
 *
 * <p>存在的理由是让三个适配器（schema / 读 / 写）不必各自重复「建 session、设库、设超时」，
 * 也让超时值只有一处来源。本类只在 {@code graph.infrastructure} 内可见——上层若拿到它，
 * 就会绕过端口直接写 Cypher，ADR-005 的可替换性随之失效。
 *
 * <p>异常处理：驱动异常统一转成 {@link GraphStoreUnavailableException} 交给上层判断降级，
 * 不把 Cypher、主机名或凭据拼进异常消息（错误响应不得包含 SQL/内部地址，这是既有规范）。
 * 但异常类型保留，便于日志区分「连不上」与「查询超时」。
 */
public class Neo4jSessionRunner {

  private final Driver driver;
  private final String database;
  private final TransactionConfig transactionConfig;

  public Neo4jSessionRunner(Driver driver, Neo4jGraphProperties properties) {
    this.driver = driver;
    this.database = properties.database();
    this.transactionConfig =
        TransactionConfig.builder().withTimeout(properties.queryTimeout()).build();
  }

  /** 只读事务。 */
  public <T> T read(TransactionWork<T> work) {
    return withSession(session -> session.executeRead(tx -> work.apply(tx), transactionConfig));
  }

  /** 读写事务。 */
  public <T> T write(TransactionWork<T> work) {
    return withSession(session -> session.executeWrite(tx -> work.apply(tx), transactionConfig));
  }

  /**
   * 执行一条 schema 语句。
   *
   * <p>必须在自动提交事务里执行：Neo4j 不允许在显式事务中执行 DDL，混用会得到
   * {@code This is an administration command...} 一类的错误，而那时启动已经过了一半。
   */
  public void applySchemaStatement(String cypher) {
    withSession(
        session -> {
          session.run(cypher).consume();
          return null;
        });
  }

  /** 执行只读查询并逐行映射。 */
  public <T> java.util.List<T> query(
      String cypher, Map<String, Object> parameters, RecordMapper<T> mapper) {
    return read(
        tx -> {
          java.util.List<T> rows = new java.util.ArrayList<>();
          tx.run(cypher, parameters).forEachRemaining(record -> rows.add(mapper.apply(record)));
          return rows;
        });
  }

  /** 连通性探测；图库不可达时返回 false，不抛异常（健康检查不应让调用方 500）。 */
  public boolean isAvailable() {
    try {
      driver.verifyConnectivity();
      return true;
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private <T> T withSession(SessionWork<T> work) {
    SessionConfig config = SessionConfig.builder().withDatabase(database).build();
    try (Session session = driver.session(config)) {
      return work.apply(session);
    } catch (Neo4jException ex) {
      throw new GraphStoreUnavailableException("图数据库访问失败：" + ex.code(), ex);
    }
  }

  /** 事务内动作。 */
  @FunctionalInterface
  public interface TransactionWork<T> {
    T apply(TransactionContext tx);
  }

  @FunctionalInterface
  private interface SessionWork<T> {
    T apply(Session session);
  }

  /** 记录到领域对象的映射。 */
  @FunctionalInterface
  public interface RecordMapper<T> {
    T apply(Record record);
  }
}
