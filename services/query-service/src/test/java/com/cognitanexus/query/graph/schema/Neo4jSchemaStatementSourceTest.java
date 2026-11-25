package com.cognitanexus.query.graph.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** schema.cypher 的解析与内容约束。 */
class Neo4jSchemaStatementSourceTest {

  @Test
  @DisplayName("解析时剔除整行注释与空行，按分号切分，不产生空语句")
  void parseIgnoresCommentsAndBlankLines() {
    List<String> statements =
        Neo4jSchemaStatementSource.parse(
            """
            // 注释一
            CREATE CONSTRAINT a_unique IF NOT EXISTS FOR (n:A) REQUIRE n.id IS UNIQUE;

            // 注释二
            CREATE INDEX a_name IF NOT EXISTS FOR (n:A) ON (n.name);
            """);

    assertThat(statements)
        .containsExactly(
            "CREATE CONSTRAINT a_unique IF NOT EXISTS FOR (n:A) REQUIRE n.id IS UNIQUE",
            "CREATE INDEX a_name IF NOT EXISTS FOR (n:A) ON (n.name)");
    assertThat(statements).allSatisfy(statement -> assertThat(statement).doesNotContain(";"));
  }

  @Test
  @DisplayName("空文件不产生语句")
  void parseEmpty() {
    assertThat(Neo4jSchemaStatementSource.parse("")).isEmpty();
    assertThat(Neo4jSchemaStatementSource.parse("   \n // nothing\n")).isEmpty();
    assertThat(Neo4jSchemaStatementSource.parse(null)).isEmpty();
  }

  @Test
  @DisplayName("仓库内 schema 只包含幂等的 CREATE ... IF NOT EXISTS，且覆盖三类唯一约束与关键索引")
  void repositorySchemaIsIdempotentAndComplete() {
    Neo4jSchemaStatementSource source =
        new Neo4jSchemaStatementSource(
            new ClassPathResource(Neo4jSchemaStatementSource.RESOURCE_PATH));
    List<String> statements = source.load();

    assertThat(statements).isNotEmpty();
    for (String statement : statements) {
      // 启动会重复执行这份文件：任何 DROP / 无 IF NOT EXISTS 的 DDL 都会让第二次启动失败。
      assertThat(statement).startsWith("CREATE");
      assertThat(statement).contains("IF NOT EXISTS");
      assertThat(statement.toUpperCase(java.util.Locale.ROOT)).doesNotContain("DROP", "DELETE");
    }
    String all = String.join("\n", statements);
    assertThat(all)
        .contains("scholar_id_unique", "work_id_unique", "graph_projection_meta_scope_unique");
    assertThat(all).contains("scholar_domain", "scholar_organization", "scholar_data_origin");
    assertThat(all).contains("work_public", "work_data_origin", "work_source_version");
    assertThat(all).contains("authored_work_id", "cooperated_pair_key", "cooperated_work_id");
  }

  @Test
  @DisplayName("缺失 schema 文件时明确失败，而不是当作「没有约束」继续启动")
  void missingResourceFailsFast() {
    Neo4jSchemaStatementSource source =
        new Neo4jSchemaStatementSource(new ClassPathResource("neo4j/not-exists.cypher"));

    org.assertj.core.api.Assertions.assertThatThrownBy(source::load)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("neo4j/schema.cypher");
  }
}
