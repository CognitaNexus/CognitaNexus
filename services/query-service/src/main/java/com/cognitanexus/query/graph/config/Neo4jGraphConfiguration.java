package com.cognitanexus.query.graph.config;

import com.cognitanexus.query.graph.application.CollaborationGraphRepository;
import com.cognitanexus.query.graph.application.CollaborationGraphService;
import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.application.GraphSchemaStore;
import com.cognitanexus.query.graph.infrastructure.Neo4jCollaborationGraphRepository;
import com.cognitanexus.query.graph.infrastructure.Neo4jGraphProjectionStore;
import com.cognitanexus.query.graph.infrastructure.Neo4jGraphSchemaStore;
import com.cognitanexus.query.graph.infrastructure.Neo4jSessionRunner;
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

  @Bean
  public CollaborationGraphService collaborationGraphService(
      CollaborationGraphRepository repository, Neo4jGraphProperties properties) {
    return new CollaborationGraphService(
        repository, properties.maxNodes(), properties.maxEdges(), properties.budgetMillis());
  }

  /**
   * 图库健康指示器。
   *
   * <p>只做连通性探测，不判断水位：水位落后是「降级到 PG」而不是「服务不健康」，
   * 把它算进健康检查会导致图库轻微滞后就让整个实例被摘除，反而扩大故障面。
   */
  @Bean
  public HealthIndicator neo4jGraphHealthIndicator(
      CollaborationGraphRepository repository, Neo4jGraphProperties properties) {
    return () -> {
      boolean available = repository.isAvailable();
      Health.Builder builder = available ? Health.up() : Health.down();
      return builder
          .withDetail("scope", properties.scope())
          .withDetail("database", properties.database())
          .withDetail("degradedToPostgres", !available)
          .build();
    };
  }
}
