package com.cognitanexus.query.graph.infrastructure;

import com.cognitanexus.query.graph.application.GraphSchemaStore;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.neo4j.driver.Record;

/**
 * Neo4j 版 schema 访问：执行 DDL 并读回约束/索引名。
 *
 * <p>{@code SHOW CONSTRAINTS} / {@code SHOW INDEXES} 是 Neo4j 5 的管理命令，需要账号具备
 * schema 读权限。缺少权限时读取失败会转成明确异常，而不是返回「空集合 + 初始化成功」——
 * 后者会让服务带着缺失的唯一约束上线。
 */
public class Neo4jGraphSchemaStore implements GraphSchemaStore {

  private final Neo4jSessionRunner runner;

  public Neo4jGraphSchemaStore(Neo4jSessionRunner runner) {
    this.runner = runner;
  }

  @Override
  public void applySchemaStatement(String cypher) {
    runner.applySchemaStatement(cypher);
  }

  @Override
  public SchemaObjects readSchemaObjects() {
    return new SchemaObjects(
        readNames("SHOW CONSTRAINTS YIELD name RETURN name"),
        readNames("SHOW INDEXES YIELD name RETURN name"));
  }

  private Set<String> readNames(String cypher) {
    List<Record> rows = runner.query(cypher, Map.of(), record -> record);
    Set<String> names = new LinkedHashSet<>();
    for (Record row : rows) {
      if (!row.get("name").isNull()) {
        names.add(row.get("name").asString());
      }
    }
    return names;
  }
}
