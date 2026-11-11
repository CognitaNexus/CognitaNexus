package com.cognitanexus.query;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * query-service 启动入口。
 *
 * <p>依据：架构 V0.2 §4.2（可重建读投影、统计、论文边与证据、Inbox、发布版本）、§10.1（公开检索、
 * 统计、网络三个路由归本服务所有）。
 *
 * <p><b>刻意不引入 Spring Security</b>：本服务对外只有匿名可读的公开端点，没有任何需要区分身份的
 * 外部接口。公开资格与版本屏障由 {@code PublicBarrierGuard} 在每次请求内校验，内部端点由服务令牌
 * 过滤器把关。若为了「统一技术栈」引入安全框架再逐条 permitAll，只会得到一份看起来在授权、
 * 实际全部放行的配置——那是比不引入更危险的状态。
 *
 * <p>{@code @MapperScan} 必须按注解扫描而不是按包名扫描：平台层的 Mapper（Inbox、水位、重建记录）
 * 与各模块的 Mapper 并列分布在多个包下，只扫 infrastructure 包会漏掉平台层 Mapper，而放开整个
 * 包路径又会把端口接口误当 Mapper 注册。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan("com.cognitanexus.query")
@MapperScan(basePackages = "com.cognitanexus.query", annotationClass = Mapper.class)
@EnableScheduling
public class QueryServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(QueryServiceApplication.class, args);
  }
}
