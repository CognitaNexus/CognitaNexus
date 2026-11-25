package com.cognitanexus.query.graph.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.graph.config.Neo4jGraphProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Result;
import org.neo4j.driver.TransactionContext;

/**
 * 整库重建的独占性保护（F20-3）。
 *
 * <p>被锁住的行为：{@code resetAllForRebuild} 清空的是整个图库，因此只要库内存在别的
 * {@code GraphProjectionMeta.scope}，就必须在删任何节点之前抛 {@link IllegalStateException}。
 * 这里用假的 {@link TransactionContext} 验证「先查作用域、后删节点」的顺序，而不需要真实图库——
 * 一旦这条顺序被改坏，代价是删掉其他环境/作用域的图数据，属于不可接受事故。
 */
class Neo4jGraphProjectionStoreTest {

  private static final String DELETE_ALL = "MATCH (n) DETACH DELETE n";

  private static Neo4jGraphProperties properties() {
    return new Neo4jGraphProperties(
        true,
        "bolt://127.0.0.1:7687",
        "neo4j",
        "secret",
        "neo4j",
        Duration.ofSeconds(3),
        Duration.ofSeconds(10),
        20,
        50,
        20_000,
        100_000,
        1_000L,
        "query-public",
        false);
  }

  @Test
  @DisplayName("库内没有其他作用域时允许重建（空库与只有本作用域两种情形）")
  void exclusiveScopeIsAccepted() {
    assertThatCode(() -> Neo4jGraphProjectionStore.requireExclusiveScope("query-public", null))
        .doesNotThrowAnyException();
    assertThatCode(() -> Neo4jGraphProjectionStore.requireExclusiveScope("query-public", List.of()))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                Neo4jGraphProjectionStore.requireExclusiveScope(
                    "query-public", List.of("query-public")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("存在其他作用域时拒绝整库清空，并在消息里点名作用域（便于运维定位）")
  void foreignScopeIsRejected() {
    assertThatThrownBy(
            () ->
                Neo4jGraphProjectionStore.requireExclusiveScope(
                    "query-public", List.of("query-public", "staging", "ops-rehearsal")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("拒绝整库清空")
        .hasMessageContaining("query-public")
        .hasMessageContaining("staging")
        .hasMessageContaining("ops-rehearsal")
        .hasMessageContaining("独占实例");
  }

  @Test
  @DisplayName("事务顺序：存在外来作用域时不发 DELETE，也不推进水位")
  void foreignScopeAbortsBeforeAnyDeletion() {
    Neo4jSessionRunner runner = mock(Neo4jSessionRunner.class);
    TransactionContext tx = mock(TransactionContext.class);
    Result scopeResult = mock(Result.class);
    when(tx.run(anyString())).thenReturn(scopeResult);
    when(scopeResult.list(any())).thenReturn(List.of("query-public", "other-scope"));
    when(runner.write(any()))
        .thenAnswer(
            invocation -> {
              Neo4jSessionRunner.TransactionWork<?> work = invocation.getArgument(0);
              return work.apply(tx);
            });

    Neo4jGraphProjectionStore store = new Neo4jGraphProjectionStore(runner, properties());

    assertThatThrownBy(() -> store.resetAllForRebuild("query-public"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("other-scope");

    // 一次 DETACH DELETE 都不允许发出；write 只调用一次（没有走到 advanceMeta）。
    verify(tx, never()).run(DELETE_ALL);
    verify(runner).write(any());
  }

  @Test
  @DisplayName("独占实例上按作用域重建：读作用域 → 清库 → 重置水位")
  void exclusiveResetWipesAndResetsMeta() {
    Neo4jSessionRunner runner = mock(Neo4jSessionRunner.class);
    TransactionContext tx = mock(TransactionContext.class);
    Result result = mock(Result.class);
    when(tx.run(anyString())).thenReturn(result);
    when(result.list(any())).thenReturn(List.of("query-public"));
    final int[] writes = {0};
    when(runner.write(any()))
        .thenAnswer(
            invocation -> {
              Neo4jSessionRunner.TransactionWork<?> work = invocation.getArgument(0);
              writes[0]++;
              // 第二次 write 是 advanceMeta；真实实现读回水位，这里直接回 0。
              return writes[0] == 1 ? work.apply(tx) : 0L;
            });

    Neo4jGraphProjectionStore store = new Neo4jGraphProjectionStore(runner, properties());

    assertThatCode(() -> store.resetAllForRebuild("query-public")).doesNotThrowAnyException();
    verify(tx).run(DELETE_ALL);
  }

  @Test
  @DisplayName("空作用域参数回落到配置的 scope，不因漏传参数而拒绝重建")
  void blankScopeFallsBackToConfiguredScope() {
    Neo4jSessionRunner runner = mock(Neo4jSessionRunner.class);
    TransactionContext tx = mock(TransactionContext.class);
    Result result = mock(Result.class);
    when(tx.run(anyString())).thenReturn(result);
    when(result.list(any())).thenReturn(List.of("query-public"));
    final int[] writes = {0};
    when(runner.write(any()))
        .thenAnswer(
            invocation -> {
              Neo4jSessionRunner.TransactionWork<?> work = invocation.getArgument(0);
              writes[0]++;
              return writes[0] == 1 ? work.apply(tx) : 0L;
            });

    Neo4jGraphProjectionStore store = new Neo4jGraphProjectionStore(runner, properties());

    assertThatCode(() -> store.resetAllForRebuild("  ")).doesNotThrowAnyException();
    verify(tx).run(DELETE_ALL);
  }
}
