package com.cognitanexus.query.graph.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.graph.application.CollaborationGraphRepository;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

/**
 * 图库健康指示器的语义：**整体永远 UP**，降级信息只放在 details。
 *
 * <p>为什么这是必须锁住的行为：图库是派生读投影，ADR-005 §7 承诺「图库故障时降级回 PostgreSQL，
 * 公开查询服务整体仍可用」。若指示器返回 {@code DOWN}，`/actuator/health` 聚合会变成 HTTP 503，
 * 部署探针随即摘除整个 query-service——可选组件的故障被放大成全部公开查询不可用。
 * 这里的断言就是这个承诺的可执行版本。
 */
class Neo4jGraphConfigurationTest {

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
  @DisplayName("图库可用且 schema 就绪：UP 且不标记降级")
  void availableGraphIsUp() {
    Health health = Neo4jGraphConfiguration.graphHealth(available(true), properties(), true);

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("graphAvailable", true)
        .containsEntry("schemaReady", true)
        .containsEntry("degradedToPostgres", false)
        .containsEntry("scope", "query-public")
        .containsEntry("database", "neo4j")
        // 正常时不应出现降级原因，否则运维会怀疑降级是否在生效。
        .doesNotContainKey("degradedReason");
  }

  @Test
  @DisplayName("图库不可达：仍然 UP（不是 DOWN），details 暴露降级，避免探针摘除整个服务")
  void unavailableGraphStaysUpButDegraded() {
    Health health = Neo4jGraphConfiguration.graphHealth(available(false), properties(), true);

    assertThat(health.getStatus())
        .as("图库不可用不得让 /actuator/health 聚合变成 503（ADR-005 §7）")
        .isEqualTo(Status.UP)
        .isNotEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("graphAvailable", false)
        .containsEntry("degradedToPostgres", true)
        .containsEntry("schemaReady", true);
    assertThat(health.getDetails().get("degradedReason").toString()).contains("PostgreSQL");
  }

  @Test
  @DisplayName("schema 未就绪（启动初始化失败）同样降级：缺唯一约束的图不能服务查询")
  void schemaNotReadyIsDegraded() {
    Health health = Neo4jGraphConfiguration.graphHealth(available(true), properties(), false);

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("graphAvailable", true)
        .containsEntry("schemaReady", false)
        .containsEntry("degradedToPostgres", true);
    assertThat(health.getDetails().get("degradedReason").toString()).contains("schema");
  }

  @Test
  @DisplayName("探针自身抛异常时按不可用处理，健康端点不得变成 500")
  void probeExceptionIsTreatedAsUnavailable() {
    CollaborationGraphRepository repository = mock(CollaborationGraphRepository.class);
    when(repository.isAvailable()).thenThrow(new IllegalStateException("pool exhausted"));

    Health health = Neo4jGraphConfiguration.graphHealth(repository, properties(), true);

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("graphAvailable", false)
        .containsEntry("degradedToPostgres", true)
        // 只暴露异常类型：健康响应不得包含主机名、凭据或内部地址。
        .containsEntry("probeError", "IllegalStateException");
  }

  @Test
  @DisplayName("装配出的 HealthIndicator 与静态构造逻辑一致：UP + 降级详情")
  void beanDelegatesToSameSemantics() {
    Neo4jGraphConfiguration configuration = new Neo4jGraphConfiguration();
    HealthIndicator indicator =
        configuration.neo4jGraphHealthIndicator(available(false), readyInitializer(), properties());

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("graphAvailable", false)
        .containsEntry("degradedToPostgres", true)
        .containsEntry("schemaReady", true);
  }

  private static CollaborationGraphRepository available(boolean value) {
    CollaborationGraphRepository repository = mock(CollaborationGraphRepository.class);
    when(repository.isAvailable()).thenReturn(value);
    return repository;
  }

  /** schema 初始化器只需要 `isReady()`：用真实实例 + 请求 OK 的假 schema 仓储，保持依赖最小。 */
  private static com.cognitanexus.query.graph.schema.Neo4jSchemaInitializer readyInitializer() {
    com.cognitanexus.query.graph.schema.Neo4jSchemaInitializer initializer =
        new com.cognitanexus.query.graph.schema.Neo4jSchemaInitializer(
            new com.cognitanexus.query.graph.application.GraphSchemaStore() {
              @Override
              public void applySchemaStatement(String cypher) {
                // 无需执行：本用例只验证健康语义，不验证 DDL。
              }

              @Override
              public SchemaObjects readSchemaObjects() {
                return new SchemaObjects(
                    java.util.Set.of(
                        "scholar_id_unique",
                        "work_id_unique",
                        "graph_projection_meta_scope_unique",
                        "graph_projection_initialization_scope_unique"),
                    java.util.Set.of("scholar_domain"));
              }
            },
            new com.cognitanexus.query.graph.schema.Neo4jSchemaStatementSource(),
            false);
    initializer.initialize();
    return initializer;
  }
}
