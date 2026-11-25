package com.cognitanexus.query.graph.infrastructure;

import com.cognitanexus.query.graph.application.GraphProjectionInitializationProbe;
import com.cognitanexus.query.graph.config.Neo4jGraphProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.neo4j.driver.Record;

/**
 * Neo4j 版回填初始化状态探针。
 *
 * <p>读 {@code (:GraphProjectionInitialization {scope})} 节点上的 {@code status / failed_works /
 * generation}。写入方是 Backfill（ADR-005 §5.2）：开始灌数据前写 {@code RUNNING}，全部灌完并核对
 * 行数后写 {@code COMPLETED}，中途失败写失败态并把失败成果数累计到 {@code failed_works}。
 *
 * <p>为什么这份状态放在图库里而不是 PG：它描述的是<b>图</b>的完成度（哪些成果还没写进来），
 * PG 侧的回填没有任何记录；把它放进 PG 会让「图是否完整」依赖第二个存储的又一次同步。图库整库
 * 重建时它随 {@code MATCH (n) DETACH DELETE n} 一起被清掉，正好对应「重建后必须重新初始化」。
 *
 * <p>只读：本类不写任何东西。查询失败向上抛，由水位保护的调用方判为「水位不可读」（图库不可用/
 * 认证失败），而不是「未初始化」——两者的运维处置完全不同。
 */
public class Neo4jGraphProjectionInitializationProbe implements GraphProjectionInitializationProbe {

  private final Neo4jSessionRunner runner;
  private final String defaultScope;

  public Neo4jGraphProjectionInitializationProbe(
      Neo4jSessionRunner runner, Neo4jGraphProperties properties) {
    this.runner = runner;
    this.defaultScope = properties.scope();
  }

  @Override
  public Optional<InitializationState> inspect(String scope) {
    String effectiveScope = scope == null || scope.isBlank() ? defaultScope : scope;
    List<Record> rows =
        runner.query(READ_INITIALIZATION, Map.of("scope", effectiveScope), record -> record);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Record record = rows.get(0);
    return Optional.of(
        new InitializationState(
            effectiveScope,
            record.get("status").isNull() ? null : record.get("status").asString(),
            record.get("failedWorks").asLong(),
            record.get("generation").isNull() ? null : record.get("generation").asString()));
  }

  /**
   * 同一作用域理论上只有一条状态（唯一约束 {@code graph_projection_initialization_scope_unique}）。
   * 仍然按时间取最新一条并 {@code LIMIT 1}：约束尚未建立的实例上宁可读到最新状态，也不让一次
   * 「同 scope 两条记录」把公开查询变成异常。
   */
  private static final String READ_INITIALIZATION =
      """
      MATCH (i:GraphProjectionInitialization {scope: $scope})
      RETURN i.status AS status,
             coalesce(i.failed_works, 0) AS failedWorks,
             i.generation AS generation
      ORDER BY coalesce(i.updated_at_epoch_ms, 0) DESC
      LIMIT 1
      """;
}
