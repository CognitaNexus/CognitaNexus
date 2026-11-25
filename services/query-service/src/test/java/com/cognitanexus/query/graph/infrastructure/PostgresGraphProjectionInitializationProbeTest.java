package com.cognitanexus.query.graph.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.graph.application.GraphProjectionInitializationProbe.InitializationState;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * PostgreSQL 回填断点探针的取值语义。
 *
 * <p>三种结果对应三种完全不同的运维动作，因此逐条钉住：
 *
 * <ul>
 *   <li>有断点行 → 真实状态；
 *   <li>表存在但无行 → {@code MISSING}（从未回填，必须拦截），<b>不得</b>返回 empty；
 *   <li>表不存在（V7 未迁移，{@code 42P01}）→ empty（退化为水位元数据判定）；其他数据库错误 → 上抛，
 *       由守卫判为「水位不可读」。
 * </ul>
 */
class PostgresGraphProjectionInitializationProbeTest {

  private static final String SCOPE = "query-public";

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final PostgresGraphProjectionInitializationProbe probe =
      new PostgresGraphProjectionInitializationProbe(jdbc);

  @Test
  @DisplayName("断点行存在时返回真实状态（status / failed_works / generation）")
  void returnsRowState() {
    stubRows(jdbc, List.of(new InitializationState(SCOPE, "COMPLETED", 0L, "gen-1")));

    InitializationState state = probe.inspect(SCOPE).orElseThrow();

    assertThat(state.completed()).isTrue();
    assertThat(state.withoutFailedWorks()).isTrue();
    assertThat(state.belongsTo("gen-1")).isTrue();
    assertThat(state.generation()).isEqualTo("gen-1");
  }

  @Test
  @DisplayName("表已部署但该 scope 无行：返回 MISSING 而不是 empty（不能退化成只看水位）")
  void missingRowIsNotMissingTable() {
    stubRows(jdbc, List.of());

    InitializationState state = probe.inspect(SCOPE).orElseThrow();

    assertThat(state.status()).isEqualTo(PostgresGraphProjectionInitializationProbe.MISSING);
    assertThat(state.completed()).isFalse();
    assertThat(state.belongsTo("gen-1")).isFalse();
  }

  @Test
  @DisplayName("表不存在（SQLState 42P01，V7 未部署）：退化为 Optional.empty()")
  void undefinedTableDegradesToEmpty() {
    stubFailure(
        jdbc,
        new BadSqlGrammarException(
            "query checkpoint",
            "SELECT ...",
            new SQLException("relation does not exist", "42P01")));

    assertThat(probe.inspect(SCOPE)).isEmpty();
  }

  @Test
  @DisplayName("权限不足（42501）不是「表不存在」：上抛，让人去修权限而不是重跑回填")
  void permissionErrorPropagates() {
    JdbcTemplate failing = failingJdbc();
    stubFailure(
        failing,
        new BadSqlGrammarException(
            "query checkpoint", "SELECT ...", new SQLException("permission denied", "42501")));
    PostgresGraphProjectionInitializationProbe failingProbe =
        new PostgresGraphProjectionInitializationProbe(failing);

    assertThatThrownBy(() -> failingProbe.inspect(SCOPE))
        .isInstanceOf(BadSqlGrammarException.class);
  }

  @Test
  @DisplayName("连接类故障同样上抛，由守卫判为 PROJECTION_META_UNREADABLE")
  void connectionFailurePropagates() {
    JdbcTemplate failing = failingJdbc();
    stubFailure(failing, new DataAccessResourceFailureException("连接池耗尽"));
    PostgresGraphProjectionInitializationProbe failingProbe =
        new PostgresGraphProjectionInitializationProbe(failing);

    assertThatThrownBy(() -> failingProbe.inspect(SCOPE))
        .isInstanceOf(DataAccessResourceFailureException.class);
  }

  @Test
  @DisplayName("scope 为空：明确拒绝，不静默退化成「所有 scope 只看水位」")
  void blankScopeIsRejected() {
    assertThatThrownBy(() -> probe.inspect("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scope");
  }

  private static JdbcTemplate failingJdbc() {
    return mock(JdbcTemplate.class);
  }

  @SuppressWarnings("unchecked")
  private static RowMapper<InitializationState> stateRowMapper() {
    return (RowMapper<InitializationState>) any(RowMapper.class);
  }

  private static void stubRows(JdbcTemplate target, List<InitializationState> rows) {
    when(target.query(anyString(), stateRowMapper(), any())).thenReturn(rows);
  }

  private static void stubFailure(JdbcTemplate target, RuntimeException failure) {
    when(target.query(anyString(), stateRowMapper(), any())).thenThrow(failure);
  }
}
