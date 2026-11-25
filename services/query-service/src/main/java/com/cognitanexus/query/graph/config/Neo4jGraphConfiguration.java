package com.cognitanexus.query.graph.config;

import com.cognitanexus.query.graph.application.CollaborationGraphRepository;
import com.cognitanexus.query.graph.application.CollaborationGraphService;
import com.cognitanexus.query.graph.application.GraphProjectionInitializationProbe;
import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.application.GraphSchemaStore;
import com.cognitanexus.query.graph.infrastructure.Neo4jCollaborationGraphRepository;
import com.cognitanexus.query.graph.infrastructure.Neo4jGraphProjectionInitializationProbe;
import com.cognitanexus.query.graph.infrastructure.Neo4jGraphProjectionStore;
import com.cognitanexus.query.graph.infrastructure.Neo4jGraphSchemaStore;
import com.cognitanexus.query.graph.infrastructure.Neo4jSessionRunner;
import com.cognitanexus.query.graph.schema.Neo4jSchemaInitializer;
import com.cognitanexus.query.graph.schema.Neo4jSchemaStatementSource;
import java.net.URI;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 图投影装配。
 *
 * <p>整个配置类以 {@code cn.query.graph.enabled=true} 为条件：默认关闭时<b>不存在任何图库 Bean</b>，
 * 也就不会尝试连接、不会在启动时执行 DDL、不会因为本机没装 Neo4j 而让现有测试或部署失败。
 * 启用后若配置不完整（例如缺口令），{@link Neo4jGraphProperties#validate()} 在创建 Driver 时
 * 直接失败——「启动成功但所有合作网络请求降级」比启动失败更难被发现。
 *
 * <p>这里只装配端口实现，业务层（{@link CollaborationGraphService}）拿到的是接口。将来换华为云
 * GES 时替换实现类即可，见 ADR-005 §9。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(Neo4jGraphProperties.class)
@ConditionalOnProperty(prefix = "cn.query.graph", name = "enabled", havingValue = "true")
public class Neo4jGraphConfiguration {

  @Bean(destroyMethod = "close")
  public Driver neo4jDriver(Neo4jGraphProperties properties) {
    properties.validate();
    Config config =
        Config.builder()
            .withConnectionTimeout(
                properties.connectTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .withMaxConnectionPoolSize(8)
            // 连接获取超时略大于建连超时：连接池耗尽时给出「等不到连接」，而不是伪装成查询超时。
            .withConnectionAcquisitionTimeout(
                properties.connectTimeout().multipliedBy(2).toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS)
            .withTelemetryDisabled(true)
            .build();
    return GraphDatabase.driver(
        URI.create(properties.uri()),
        AuthTokens.basic(properties.username(), properties.password()),
        config);
  }

  @Bean
  public Neo4jSessionRunner neo4jSessionRunner(
      Driver neo4jDriver, Neo4jGraphProperties properties) {
    return new Neo4jSessionRunner(neo4jDriver, properties);
  }

  @Bean
  public GraphSchemaStore graphSchemaStore(Neo4jSessionRunner runner) {
    return new Neo4jGraphSchemaStore(runner);
  }

  /**
   * 启动时幂等执行 schema 的初始化器。
   *
   * <p>刻意用显式 {@code @Bean} 而不是给类加 {@code @Component}：是否「图库不可达就拒绝启动」是部署
   * 策略，不是领域规则，由 {@code cn.query.graph.fail-fast-on-startup}（默认 false）决定，
   * 在这里一次性绑定，避免有人给类加一个无参构造后又一次变成「启用即崩」。
   */
  @Bean
  public Neo4jSchemaInitializer neo4jSchemaInitializer(
      GraphSchemaStore store, Neo4jSchemaStatementSource source, Neo4jGraphProperties properties) {
    return new Neo4jSchemaInitializer(store, source, properties.failFastOnStartup());
  }

  @Bean
  public CollaborationGraphRepository collaborationGraphRepository(
      Neo4jSessionRunner runner, Neo4jGraphProperties properties) {
    return new Neo4jCollaborationGraphRepository(runner, properties);
  }

  @Bean
  public GraphProjectionStore graphProjectionStore(
      Neo4jSessionRunner runner, Neo4jGraphProperties properties) {
    return new Neo4jGraphProjectionStore(runner, properties);
  }

  /**
   * 回填初始化状态探针。
   *
   * <p>必须与图库后端一起装配：水位只能证明「图写到了哪个版本」，证明不了「基线回填是否完整跑完」。
   * 缺了它，一个版本号恰好等于已发布修订、但仍有失败成果的图会被当成可用（ADR-005 §6.1）。
   */
  @Bean
  public GraphProjectionInitializationProbe graphProjectionInitializationProbe(
      Neo4jSessionRunner runner, Neo4jGraphProperties properties) {
    return new Neo4jGraphProjectionInitializationProbe(runner, properties);
  }

  @Bean
  public CollaborationGraphService collaborationGraphService(
      CollaborationGraphRepository repository, Neo4jGraphProperties properties) {
    return new CollaborationGraphService(
        repository, properties.maxNodes(), properties.maxEdges(), properties.budgetMillis());
  }

  /**
   * 图库健康指示器。
   *
   * <p><b>永远返回 UP</b>：图库只是派生读投影，ADR-005 §7 承诺「图库故障时降级回 PostgreSQL，
   * 公开查询服务整体仍可用」。如果这里返回 {@code Health.down()}，`/actuator/health` 聚合就会给
   * 出 HTTP 503，部署探针会把整个 query-service 判为不健康并摘除——一个可选图库的故障就此
   * 扩大成全部公开查询不可用，与降级承诺相反。因此降级状态放进 details
   * （{@code graphAvailable=false}、{@code degradedToPostgres=true}），而不是放在整体状态里。
   *
   * <p>探针本身抛异常（连接池耗尽、驱动内部错误）同样按「图库不可用」处理，不允许健康端点
   * 因此变成 500：健康检查不应当成为另一个故障点。
   */
  @Bean
  public HealthIndicator neo4jGraphHealthIndicator(
      CollaborationGraphRepository repository,
      Neo4jSchemaInitializer schemaInitializer,
      Neo4jGraphProperties properties) {
    return () -> graphHealth(repository, properties, schemaInitializer.isReady());
  }

  /**
   * 构造图库健康信息。整体状态恒为 UP，降级与否只看 details（ADR-005 §7）。
   *
   * @param schemaReady schema 是否已成功应用到图库；未就绪时同样必须降级到 PG，
   *     因为缺唯一约束的图在并发写入下会悄悄产生重复节点
   */
  static Health graphHealth(
      CollaborationGraphRepository repository,
      Neo4jGraphProperties properties,
      boolean schemaReady) {
    boolean available;
    String probeError = null;
    try {
      available = repository.isAvailable();
    } catch (RuntimeException ex) {
      // 探针失败等同于不可用；只记录异常类型，不把主机名/凭据写进公开健康响应。
      available = false;
      probeError = ex.getClass().getSimpleName();
    }
    boolean degraded = !available || !schemaReady;
    Health.Builder builder =
        Health.up()
            .withDetail("scope", properties.scope())
            .withDetail("database", properties.database())
            .withDetail("graphAvailable", available)
            .withDetail("schemaReady", schemaReady)
            .withDetail("degradedToPostgres", degraded);
    if (degraded) {
      builder.withDetail(
          "degradedReason",
          !available
              ? "图库不可达或认证失败，合作网络查询走 PostgreSQL（ADR-005 §7）"
              : "图库 schema 未就绪（启动时初始化失败或尚未执行），合作网络查询走 PostgreSQL");
    }
    if (probeError != null) {
      builder.withDetail("probeError", probeError);
    }
    return builder.build();
  }
}
