package com.cognitanexus.query.graph.config;

import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 图数据库投影的配置。
 *
 * <p>依据：ADR-005。默认 <b>关闭</b>（{@code cn.query.graph.enabled=false}），因此不引入图库
 * 也能完整运行现有的 PG 合作网络：没有 Driver、没有连接、没有启动期 schema 校验。
 * 这是「新增能力不改变现有行为」的具体做法，而不是靠文档承诺。
 *
 * <p>关于 {@code maxAuthorsPerWorkForMaterializedEdge}：一条有 n 位作者的成果会产生 n(n-1)/2
 * 个无序作者对。n=20 时 190 对，n=100 时 4950 对，n=1000 时近 50 万对——一篇大科学协作论文
 * 就能把整张图撑爆。因此超过阈值的成果<b>不物化成对合作边</b>，只保留
 * {@code Scholar-[:AUTHORED]->Work} 参与边，由二部图查询按需推导共同作者并带回证据。
 * 阈值上限 200 与 PG 侧 {@code max-authors-per-achievement-for-edges} 的隔离口径对齐：
 * 两条实现路径对「什么算超大作者数」不能各说各话。
 *
 * @param enabled 是否启用图投影（默认 false）
 * @param uri Bolt/Neo4j URI；默认本机标准端口 7687
 * @param username 图库用户
 * @param password 图库口令；启用时必须显式给出，不提供可用的开发默认值
 * @param database 目标数据库；Community 版只支持 {@code neo4j}
 * @param connectTimeout 建连超时，缺省 3 秒
 * @param queryTimeout 单条查询超时，缺省 10 秒。它不等于路径计算预算：预算由应用层在层间检查，
 *     这里只是一条 Cypher 的最长执行时间，防止单条查询挂死连接池
 * @param maxAuthorsPerWorkForMaterializedEdge 物化作者对边的作者数上限，缺省 20，允许 1–200
 * @param maxEvidenceWorkIdsPerEdge 单条边持久化/返回的证据条数上限，缺省 50。强度仍是真实计数，
 *     截断只影响证据列表长度，响应中必须显式标记
 * @param maxNodes 单次子图节点上限，缺省 20000（与 PG 侧一致）
 * @param maxEdges 单次子图边上限，缺省 100000（与 PG 侧一致）
 * @param budgetMillis 单次路径计算时间预算，缺省 1000 毫秒（与 PG 侧一致）
 * @param scope 投影作用域标识，缺省 {@code query-public}
 * @param failFastOnStartup 启动时图库 schema 初始化失败是否中止进程，缺省 {@code false}。
 *     默认为 false 是因为图库只是派生读投影：它不可用时整个公开查询服务应降级回 PostgreSQL，
 *     而不是被启动失败/探针摘除放大成全部公开功能不可用（ADR-005 §7）。置 true 仅用于
 *     首次上线或升级 schema 时的一次性快速失败检查
 */
@ConfigurationProperties(prefix = "cn.query.graph")
public record Neo4jGraphProperties(
    boolean enabled,
    String uri,
    String username,
    String password,
    String database,
    Duration connectTimeout,
    Duration queryTimeout,
    int maxAuthorsPerWorkForMaterializedEdge,
    int maxEvidenceWorkIdsPerEdge,
    int maxNodes,
    int maxEdges,
    long budgetMillis,
    String scope,
    boolean failFastOnStartup) {

  /** 物化边作者数阈值的硬上限，与 PG 侧单成果建边上限保持一致。 */
  public static final int MAX_MATERIALIZED_AUTHOR_THRESHOLD = 200;

  public Neo4jGraphProperties {
    uri = blankToDefault(uri, "bolt://127.0.0.1:7687").trim();
    username = blankToDefault(username, "neo4j").trim();
    // 口令不做默认值：空的图库口令在开发机上「能用」，到了服务器就是未授权访问入口。
    password = password == null ? "" : password;
    database = blankToDefault(database, "neo4j").trim();
    connectTimeout = positive(connectTimeout, Duration.ofSeconds(3));
    queryTimeout = positive(queryTimeout, Duration.ofSeconds(10));
    maxAuthorsPerWorkForMaterializedEdge =
        maxAuthorsPerWorkForMaterializedEdge <= 0 ? 20 : maxAuthorsPerWorkForMaterializedEdge;
    maxEvidenceWorkIdsPerEdge = maxEvidenceWorkIdsPerEdge <= 0 ? 50 : maxEvidenceWorkIdsPerEdge;
    maxNodes = maxNodes <= 0 ? 20_000 : maxNodes;
    maxEdges = maxEdges <= 0 ? 100_000 : maxEdges;
    budgetMillis = budgetMillis <= 0 ? 1_000L : budgetMillis;
    scope = blankToDefault(scope, "query-public").trim();
  }

  /**
   * 启用图投影时的配置自检。出错就拒绝启动，而不是等到第一次公开查询才发现连不上——
   * 「服务已启动但所有合作网络请求降级」比启动失败更难被发现。
   */
  public void validate() {
    if (!enabled) {
      return;
    }
    if (password.isBlank()) {
      throw new IllegalStateException(
          "已启用 cn.query.graph.enabled，但未设置 cn.query.graph.password（CN_NEO4J_PASSWORD）。"
              + "图库不允许空口令启动。");
    }
    String scheme = uri.contains("://") ? uri.substring(0, uri.indexOf("://")) : "";
    if (!scheme.matches("(bolt|neo4j|bolt\\+s|neo4j\\+s|bolt\\+ssc|neo4j\\+ssc)")) {
      throw new IllegalStateException(
          "cn.query.graph.uri 必须以 bolt/neo4j（或带 +s/+ssc 的安全变体）开头，实际为 " + uri);
    }
    if (maxAuthorsPerWorkForMaterializedEdge > MAX_MATERIALIZED_AUTHOR_THRESHOLD) {
      throw new IllegalStateException(
          "cn.query.graph.max-authors-per-work-for-materialized-edge 不能超过 "
              + MAX_MATERIALIZED_AUTHOR_THRESHOLD
              + "（与 PG 侧单成果建边上限一致），实际为 "
              + maxAuthorsPerWorkForMaterializedEdge);
    }
    if (maxEvidenceWorkIdsPerEdge > 1_000) {
      throw new IllegalStateException(
          "cn.query.graph.max-evidence-work-ids-per-edge 不能超过 1000，实际为 "
              + maxEvidenceWorkIdsPerEdge);
    }
  }

  /** 作者数超过阈值时不物化作者对边（防组合爆炸）。 */
  public boolean shouldMaterializeEdges(int authorCount) {
    return authorCount >= 2 && authorCount <= maxAuthorsPerWorkForMaterializedEdge;
  }

  /** 供日志与健康信息展示的脱敏描述，绝不输出口令。 */
  public String describe() {
    return String.format(
        Locale.ROOT,
        "graph[enabled=%s, uri=%s, database=%s, scope=%s, materializedAuthorThreshold=%d, "
            + "failFastOnStartup=%s]",
        enabled,
        uri,
        database,
        scope,
        maxAuthorsPerWorkForMaterializedEdge,
        failFastOnStartup);
  }

  private static String blankToDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static Duration positive(Duration value, Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }
}
