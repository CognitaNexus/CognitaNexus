package com.cognitanexus.query.graph.schema;

import com.cognitanexus.query.graph.application.GraphSchemaStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动时建立图库约束与索引，并校验关键约束确实存在。
 *
 * <p>为什么需要它：Neo4j 的唯一约束不是「建议」，而是「同一 scholar_id 只有一个节点」这一
 * 不变量的唯一执行者。缺少约束时 MERGE 仍然能跑，但在并发写入下会生成重复节点，表现是
 * 学者在网络里出现两次、路径绕行一个假节点。这类错误不会抛异常，只会在结果里悄悄出现。
 *
 * <p>幂等性：schema 文件全部使用 {@code IF NOT EXISTS}，重复启动不报错、不重建索引。
 * 初始化后仍会读一次库中的约束名做校验——「语句执行成功」不等于「约束存在」，
 * 例如权限不足时 DDL 可能被静默忽略（不同版本行为不同），必须核对结果而不是相信返回值。
 *
 * <p>仅在 {@code cn.query.graph.enabled=true} 时装配；默认关闭时本类不存在，现有测试与
 * 现有 PG 功能零影响。
 */
@Component
@ConditionalOnProperty(prefix = "cn.query.graph", name = "enabled", havingValue = "true")
public class Neo4jSchemaInitializer implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(Neo4jSchemaInitializer.class);

  /**
   * 必须存在的约束。
   *
   * <p>三个唯一约束分别保护：学者身份、成果身份、投影水位作用域。少任何一个，对应实体就可能在
   * 重放/并发下重复，而图查询会把这些重复节点当成不同的人或成果。
   */
  public static final Set<String> REQUIRED_CONSTRAINTS =
      Set.of("scholar_id_unique", "work_id_unique", "graph_projection_meta_scope_unique");

  private final GraphSchemaStore store;
  private final Neo4jSchemaStatementSource source;

  public Neo4jSchemaInitializer(GraphSchemaStore store, Neo4jSchemaStatementSource source) {
    this.store = store;
    this.source = source;
  }

  @Override
  public void run(ApplicationArguments args) {
    initialize();
  }

  /** 应用 schema 并校验。返回实际结果，便于测试与运维端点直接展示。 */
  public SchemaApplication initialize() {
    List<String> statements = source.load();
    for (String statement : statements) {
      if (!statement.toUpperCase(java.util.Locale.ROOT).startsWith("CREATE")) {
        throw new IllegalStateException(
            "图库 schema 只允许 CREATE ... IF NOT EXISTS 形式，拒绝执行：" + statement);
      }
      store.applySchemaStatement(statement);
    }
    GraphSchemaStore.SchemaObjects objects = store.readSchemaObjects();
    Set<String> missing = new LinkedHashSet<>(REQUIRED_CONSTRAINTS);
    missing.removeAll(objects.constraints());
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "图库 schema 初始化后仍缺少必需的唯一约束：" + missing + "。请检查图库账号是否有 DDL 权限。");
    }
    log.info(
        "图库 schema 就绪：语句 {} 条，约束 {} 个，索引 {} 个，必需约束已全部存在",
        statements.size(),
        objects.constraints().size(),
        objects.indexes().size());
    return new SchemaApplication(statements, objects.constraints(), objects.indexes());
  }

  /** 一次 schema 初始化的结果。 */
  public record SchemaApplication(
      List<String> statements, Set<String> constraints, Set<String> indexes) {

    public SchemaApplication {
      statements = statements == null ? List.of() : List.copyOf(new ArrayList<>(statements));
      constraints = constraints == null ? Set.of() : Set.copyOf(constraints);
      indexes = indexes == null ? Set.of() : Set.copyOf(indexes);
    }
  }
}
