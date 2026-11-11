package com.cognitanexus.core;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CognitaNexus 核心业务服务。
 *
 * <p>权威数据：账号与会话撤销、门户授权、学者、成果、来源事实、参与关系、审核决定、分类映射、
 * 审计事件、Outbox 变更集（架构 V0.2 §4.2）。
 *
 * <p>对外职责：注册登录、成果与门户详情、认领与维护、治理、内部鉴权与来源接收。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan("com.cognitanexus.core")
// 按注解扫描而非按包名。业务模块的 Mapper 位于各自 infrastructure/ 下，而 platform
// 属横切技术模块，其 Mapper 与端口实现同包放置（如 platform.event 下
// OutboxMapper 与 JdbcOutboxStore 并列）。若只按 infrastructure 包名扫描，会漏掉
// platform 下的 Mapper；若放开整个 platform 包，又会把 OutboxStore、AuditRecorder
// 这类端口接口误注册成 MyBatis 映射器。限定 annotationClass 可两者兼顾。
@MapperScan(basePackages = "com.cognitanexus.core", annotationClass = Mapper.class)
@EnableScheduling
public class CoreServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(CoreServiceApplication.class, args);
  }
}
