package com.cognitanexus.query.graph.infrastructure;

import com.cognitanexus.query.graph.application.GraphProjectionInitializationProbe;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL 版回填初始化状态探针。
 *
 * <p>依据：ADR-005 §5.2、§6.1、§7。回填断点只有<b>一个</b>权威来源：迁移 {@code V7} 建立的
 * {@code qry_graph_projection_checkpoint} 表（每个 scope 一行，由 Backfill 写入
 * {@code RUNNING / COMPLETED / FAILED}、{@code failed_works} 与本次回填对应的 {@code generation}）。
 * 本类只读这张表，不在图库里另存一份「回填是否完成」——两份记录必然漂移，而漂移后的结果正是
 * 「图缺一批合作边、却被判定为可服务公开查询」。
 *
 * <p>三种返回值的语义是刻意区分的，运维处置完全不同：
 *
 * <ul>
 *   <li>查到该 scope 的行 → 返回真实状态（{@code status / failed_works / generation}）；
 *   <li>表存在但<b>没有该 scope 的行</b> → 返回 {@link #MISSING} 状态，表示「本 scope 从未回填过」。
 *       这里<b>不能</b>返回 {@link Optional#empty()}：那会退化成只看水位元数据，而一个从未回填、
 *       水位却因复用旧数据而非零的实例就会因此放行空/不完整的图；
 *   <li>表本身不存在（{@code SQLState 42P01}，即尚未执行 V7 迁移的部署） → {@link Optional#empty()}，
 *       让水位保护退化为「{@code projected_version > 0} + generation 严格相等」的元数据判定。
 *       这是<b>唯一</b>允许退化的情况，并且只在迁移尚未部署时成立。
 * </ul>
 *
 * <p>其他数据库异常（连接失败、权限不足、列被改名……）一律向上抛：调用方
 * {@code ProjectionWatermarkGuard} 会把它判为 {@code PROJECTION_META_UNREADABLE}。绝不能伪装成
 * 「未初始化」——那会让人去重跑回填，而真正的问题是读不到元数据。
 *
 * <p>只读：本类不写任何东西，也不做 DDL 探测（用 {@code to_regclass} 之类的额外查询会增加一次
 * 往返，而 {@code SELECT} 本身的 {@code 42P01} 已经是准确信号）。
 */
public class PostgresGraphProjectionInitializationProbe
    implements GraphProjectionInitializationProbe {

  /**
   * 「表已部署、但该 scope 从未回填过」的合成状态。
   *
   * <p>它不是数据库里的取值（{@code V7} 的 CHECK 约束只允许 {@code RUNNING / COMPLETED / FAILED}），
   * 而是探针对「行不存在」这一事实的显式表达：与 DB 状态机共用同一套判定，天然不满足
   * {@link InitializationState#completed()}，因此一定被守卫判为 {@code PROJECTION_NOT_INITIALIZED}。
   */
  public static final String MISSING = "MISSING";

  /** PostgreSQL 标准 SQLState：{@code undefined_table}（V7 迁移尚未部署）。 */
  private static final String SQLSTATE_UNDEFINED_TABLE = "42P01";

  private static final Logger log =
      LoggerFactory.getLogger(PostgresGraphProjectionInitializationProbe.class);

  /**
   * {@code scope} 是主键（V7），因此最多一行；仍然按列表读并显式处理「0 行」，而不是用
   * {@code queryForObject} 把「没有行」变成异常——「没有行」是必须区分于「读不到」的正常状态。
   */
  private static final String SELECT_STATE =
      """
      SELECT status, failed_works, generation
      FROM qry_graph_projection_checkpoint
      WHERE scope = ?
      """;

  private final JdbcTemplate jdbc;

  public PostgresGraphProjectionInitializationProbe(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<InitializationState> inspect(String scope) {
    String effectiveScope = scope == null ? "" : scope.trim();
    if (effectiveScope.isEmpty()) {
      // 作用域为空时无法回答「这份完成度属于谁」。抛错而不是返回 empty：静默退化会让一次配置
      // 漏项变成「所有 scope 都只做元数据判定」，而调用方无法从结果里看出这件事。
      throw new IllegalArgumentException("回填初始化状态探针需要非空 scope");
    }
    List<InitializationState> rows;
    try {
      rows =
          jdbc.query(
              SELECT_STATE,
              (rs, rowNum) ->
                  new InitializationState(
                      effectiveScope,
                      rs.getString("status"),
                      rs.getLong("failed_works"),
                      rs.getString("generation")),
              effectiveScope);
    } catch (DataAccessException ex) {
      if (isUndefinedTable(ex)) {
        // 尚未部署 V7 的实例：没有断点表，也就没有完成度记录可读。日志只记异常类型，不带地址。
        log.debug(
            "qry_graph_projection_checkpoint 表不存在（V7 迁移未部署），回填完成度退化为水位元数据判定：{}",
            ex.getClass().getSimpleName());
        return Optional.empty();
      }
      throw ex;
    }
    if (rows == null || rows.isEmpty()) {
      // 表已就绪却没有该 scope 的断点：本 scope 从未回填过。这是「未完成初始化」，不是「没有记录」。
      return Optional.of(new InitializationState(effectiveScope, MISSING, 0L, null));
    }
    return Optional.of(rows.get(0));
  }

  /** SQLState {@code 42P01} 可能被 Spring 包装多层，沿 cause 链找 {@link SQLException}。 */
  private static boolean isUndefinedTable(Throwable error) {
    Throwable cause = error;
    while (cause != null) {
      if (cause instanceof SQLException sqlException
          && SQLSTATE_UNDEFINED_TABLE.equals(sqlException.getSQLState())) {
        return true;
      }
      if (cause.getCause() == cause) {
        break;
      }
      cause = cause.getCause();
    }
    return false;
  }
}
