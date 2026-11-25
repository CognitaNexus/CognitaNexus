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
 * <p>仅在 {@code cn.query.graph.enabled=true} 时装配（由 {@link
 * com.cognitanexus.query.graph.config.Neo4jGraphConfiguration} 以 {@code @Bean} 注册，便于把
 * 「图库不可达是否拒绝启动」作为部署策略显式绑定）。
 *
 * <p><b>启动容错</b>：默认（{@code cn.query.graph.fail-fast-on-startup=false}）在图库不可达时
 * 只记录 {@code log.error}、保持 {@link #isReady()} 为 {@code false}，让 query-service
 * 照常启动并把合作网络查询降级回 PostgreSQL——这符合 ADR-005 §7 的承诺。图库只是派生读投影，
 * 它不可用时如果整个公开查询服务进程崩溃重启，就把「可选组件故障」放大成了「全部公开功能不可用」。
 * 打开该开关（或直接调用 {@link #initialize()}）时仍会抛异常，便于运维 CLI 与测试获得快速失败。
 */
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
  private final boolean failFastOnStartup;

  /** schema 是否已成功应用并校验通过。未就绪时因库查询必须降级回 PG（ADR-005 §7）。 */
  private volatile boolean ready;

  public Neo4jSchemaInitializer(
      GraphSchemaStore store, Neo4jSchemaStatementSource source, boolean failFastOnStartup) {
    this.store = store;
    this.source = source;
    this.failFastOnStartup = failFastOnStartup;
  }

  /**
   * 启动入口：默认不因图库不可达而让进程启动失败。
   *
   * <p>失败时保持未就绪并记录完整错误，由健康指示器暴露 {@code schemaReady=false}；
   * {@code fail-fast-on-startup=true} 时才向上抛异常中止启动。
   */
  @Override
  public void run(ApplicationArguments args) {
    try {
      initialize();
    } catch (RuntimeException ex) {
      if (failFastOnStartup) {
        throw ex;
      }
      log.error(
          "图库 schema 初始化失败，query-service 继续启动并降级回 PostgreSQL"
              + "（cn.query.graph.fail-fast-on-startup=false）。合作网络读取不得走图库；"
              + "修复图库后用初始化器重试或重启。原因：{}",
          ex.toString(),
          ex);
    }
  }

  /** schema 是否已就绪（约束与索引已确认存在）。供健康指示器与后续读路径开关使用。 */
  public boolean isReady() {
    return ready;
  }

  /**
   * 应用 schema 并校验。返回实际结果，便于测试与运维端点直接展示。
   *
   * <p>本方法对图库不可达/DDL 权限不足一律抛异常（不吞）：调用方决定是降级启动还是失败。
   */
  public SchemaApplication initialize() {
    ready = false;
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
    ready = true;
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
