package com.cognitanexus.query.graph.application;

import java.util.Set;

/**
 * 图库结构（约束与索引）的访问口。
 *
 * <p>只暴露「执行一条 schema 语句」与「读出已有约束/索引名」两个动作，不暴露 Driver、Session
 * 或 Cypher 结果类型。Schema 初始化器因此可以用一个假的实现完整测试，而不需要连图库。
 */
public interface GraphSchemaStore {

  /** 执行一条 schema 语句（CREATE CONSTRAINT / CREATE INDEX）。语句必须自带 IF NOT EXISTS。 */
  void applySchemaStatement(String cypher);

  /** 读出当前库中的约束名与索引名，用于初始化后校验「该有的真的在」。 */
  SchemaObjects readSchemaObjects();

  /** 约束与索引名快照。 */
  record SchemaObjects(Set<String> constraints, Set<String> indexes) {

    public SchemaObjects {
      constraints = constraints == null ? Set.of() : Set.copyOf(constraints);
      indexes = indexes == null ? Set.of() : Set.copyOf(indexes);
    }

    public static SchemaObjects empty() {
      return new SchemaObjects(Set.of(), Set.of());
    }
  }
}
