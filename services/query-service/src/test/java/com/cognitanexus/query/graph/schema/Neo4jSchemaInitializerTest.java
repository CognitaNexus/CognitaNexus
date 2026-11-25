package com.cognitanexus.query.graph.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cognitanexus.query.graph.application.GraphSchemaStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 图库 schema 初始化：幂等执行、结果校验、缺失约束时明确失败。 */
class Neo4jSchemaInitializerTest {

  private static final Pattern CONSTRAINT_NAME =
      Pattern.compile("CONSTRAINT\\s+([A-Za-z0-9_]+)", Pattern.CASE_INSENSITIVE);

  private final Neo4jSchemaStatementSource source =
      new Neo4jSchemaStatementSource(
          new ClassPathResource(Neo4jSchemaStatementSource.RESOURCE_PATH));

  @Test
  @DisplayName("启动时应用全部 schema 语句，并确认三类唯一约束确实存在")
  void appliesSchemaAndVerifiesConstraints() {
    FakeSchemaStore store = new FakeSchemaStore();
    Neo4jSchemaInitializer initializer = new Neo4jSchemaInitializer(store, source, false);

    Neo4jSchemaInitializer.SchemaApplication result = initializer.initialize();

    assertThat(initializer.isReady()).isTrue();

    assertThat(result.statements()).isEqualTo(source.load());
    assertThat(store.applied).hasSize(result.statements().size());
    assertThat(result.constraints())
        .contains("scholar_id_unique", "work_id_unique", "graph_projection_meta_scope_unique");
    assertThat(result.indexes()).contains("scholar_domain", "work_public");
    assertThat(store.applied)
        .allSatisfy(statement -> assertThat(statement).contains("IF NOT EXISTS"));
  }

  @Test
  @DisplayName("重复初始化是幂等的：第二次执行同一批语句不报错、不改变约束集合")
  void initializationIsIdempotent() {
    FakeSchemaStore store = new FakeSchemaStore();
    Neo4jSchemaInitializer initializer = new Neo4jSchemaInitializer(store, source, false);

    Neo4jSchemaInitializer.SchemaApplication first = initializer.initialize();
    int appliedAfterFirst = store.applied.size();
    Neo4jSchemaInitializer.SchemaApplication second = initializer.initialize();

    assertThat(store.applied).hasSize(appliedAfterFirst * 2);
    assertThat(second.constraints()).isEqualTo(first.constraints());
    assertThat(second.indexes()).isEqualTo(first.indexes());
  }

  @Test
  @DisplayName("DDL 被静默忽略（例如权限不足）时启动失败，而不是带着缺失约束上线")
  void missingRequiredConstraintFailsFast() {
    FakeSchemaStore store = new FakeSchemaStore();
    store.ignoreConstraint = "work_id_unique";
    Neo4jSchemaInitializer initializer = new Neo4jSchemaInitializer(store, source, false);

    assertThatThrownBy(initializer::initialize)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("work_id_unique")
        .hasMessageContaining("DDL 权限");
    // 明确失败之后不得留下「已就绪」的错误状态：图库缺唯一约束时不能服务查询。
    assertThat(initializer.isReady()).isFalse();
  }

  @Test
  @DisplayName("默认启动容错：图库不可达时 run() 不抛异常、保持未就绪，而 initialize() 仍抛异常")
  void startupFailureDegradesInsteadOfCrashing() {
    FakeSchemaStore store = new FakeSchemaStore();
    store.failOnApply = new IllegalStateException("图数据库访问失败：ServiceUnavailable");
    Neo4jSchemaInitializer initializer = new Neo4jSchemaInitializer(store, source, false);

    // 启动入口（ApplicationRunner）不得把进程带下水：图库是派生读投影，
    // 它不可用时公开查询应降级回 PG，而不是整个 query-service 起不来（ADR-005 §7）。
    assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();
    assertThat(initializer.isReady()).isFalse();

    // 显式调用仍抛：CLI/运维脚本与测试需要快速失败，不被启动容错掩埋。
    assertThatThrownBy(initializer::initialize)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ServiceUnavailable");
    assertThat(initializer.isReady()).isFalse();
  }

  @Test
  @DisplayName("fail-fast-on-startup=true 时启动失败，供首次上线/升级 schema 做一次性检查")
  void failFastStartupThrows() {
    FakeSchemaStore store = new FakeSchemaStore();
    store.failOnApply = new IllegalStateException("图数据库访问失败：ServiceUnavailable");
    Neo4jSchemaInitializer initializer = new Neo4jSchemaInitializer(store, source, true);

    assertThatThrownBy(() -> initializer.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ServiceUnavailable");
    assertThat(initializer.isReady()).isFalse();
  }

  @Test
  @DisplayName("拒绝执行非 CREATE 语句：schema 文件里不允许出现删除/清空")
  void rejectsNonCreateStatements() {
    Neo4jSchemaStatementSource destructive =
        new Neo4jSchemaStatementSource(
            new ClassPathResource(Neo4jSchemaStatementSource.RESOURCE_PATH)) {
          @Override
          public List<String> load() {
            return List.of("MATCH (n) DETACH DELETE n");
          }
        };
    Neo4jSchemaInitializer initializer =
        new Neo4jSchemaInitializer(new FakeSchemaStore(), destructive, false);

    assertThatThrownBy(initializer::initialize)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("只允许 CREATE");
  }

  @Test
  @DisplayName("REQUIRED_CONSTRAINTS 与 schema 文件实际定义的约束一致，防止清单漂移")
  void requiredConstraintListMatchesSchemaFile() {
    Set<String> declared = new LinkedHashSet<>();
    for (String statement : source.load()) {
      Matcher matcher = CONSTRAINT_NAME.matcher(statement);
      if (matcher.find()) {
        declared.add(matcher.group(1));
      }
    }
    assertThat(declared).containsAll(Neo4jSchemaInitializer.REQUIRED_CONSTRAINTS);
  }

  /** 记录语句并模拟「执行后约束存在」，可配置为忽略某一条约束（模拟权限不足）。 */
  private static final class FakeSchemaStore implements GraphSchemaStore {

    private final List<String> applied = new ArrayList<>();
    private final Set<String> constraints = new LinkedHashSet<>();
    private final Set<String> indexes = new LinkedHashSet<>();
    private String ignoreConstraint;
    private RuntimeException failOnApply;

    @Override
    public void applySchemaStatement(String cypher) {
      if (failOnApply != null) {
        throw failOnApply;
      }
      applied.add(cypher);
      Matcher constraint = CONSTRAINT_NAME.matcher(cypher);
      if (constraint.find()) {
        if (!constraint.group(1).equals(ignoreConstraint)) {
          constraints.add(constraint.group(1));
        }
        return;
      }
      Matcher index =
          Pattern.compile("INDEX\\s+([A-Za-z0-9_]+)", Pattern.CASE_INSENSITIVE).matcher(cypher);
      if (index.find()) {
        indexes.add(index.group(1));
      }
    }

    @Override
    public SchemaObjects readSchemaObjects() {
      return new SchemaObjects(constraints, indexes);
    }
  }
}
