package com.cognitanexus.ingestion;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CognitaNexus 采集服务。
 *
 * <p>权威数据：来源运行配置副本、采集任务与检查点、交接暂存、交接批次、采集运行日志
 * （架构 V0.2 §4.2）。
 *
 * <p>对外职责：管理采集源与任务；在事务外抓取来源、按字段白名单暂存、以幂等批次交接给
 * core；维护检查点使任务可恢复。本服务不修改核心成果表，也不持有来源的长期证据。
 *
 * <p>不引入 Spring Security：本服务没有自己的登录态与授权事实（会话在 core、权限判定也在
 * core），只需两个普通过滤器——内部端点验服务令牌、管理端点把请求交给 core 鉴权。
 * 引入完整安全框架反而会让人误以为本服务在本地也维护了一套独立的权限判断。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan("com.cognitanexus.ingestion")
@MapperScan(basePackages = "com.cognitanexus.ingestion", annotationClass = Mapper.class)
@EnableScheduling
public class IngestionServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(IngestionServiceApplication.class, args);
  }
}
