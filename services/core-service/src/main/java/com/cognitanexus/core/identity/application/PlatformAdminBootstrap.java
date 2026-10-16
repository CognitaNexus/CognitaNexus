package com.cognitanexus.core.identity.application;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 首个平台管理员引导的启动触发点。
 *
 * <p>依据：AC-IMPL-FR04-02、架构 V0.2 §10.1。角色授予接口本身需要 account:manage 能力，
 * 因此全新部署在存在平台管理员之前无法通过接口授予第一个管理员，需要一个受控的引导入口。
 *
 * <p>刻意不采用「首个注册账号自动成为管理员」：那等于把引导权交给任何能在服务启动后抢先提交
 * 注册请求的人。本组件要求管理员登录名与口令由部署配置显式提供。
 *
 * <p>本类只负责「何时触发」与「如何告警」，判定与写入在 {@link PlatformAdminBootstrapper}，
 * 后者独立成 Bean 以保证事务代理生效。引导失败不阻断启动：服务本身可用，运维可据日志修正配置后
 * 重启，避免因一处配置错误导致整个服务无法拉起。
 */
@Component
public class PlatformAdminBootstrap {

  private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

  private final BootstrapProperties properties;
  private final PlatformAdminBootstrapper bootstrapper;

  public PlatformAdminBootstrap(
      BootstrapProperties properties, PlatformAdminBootstrapper bootstrapper) {
    this.properties = properties;
    this.bootstrapper = bootstrapper;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    if (!properties.configured()) {
      log.debug("未配置平台管理员引导，跳过（cn.bootstrap.platform-admin-username 为空）");
      return;
    }
    PlatformAdminBootstrapper.Outcome outcome;
    try {
      outcome = bootstrapper.bootstrapIfAbsent();
    } catch (RuntimeException e) {
      log.error("平台管理员引导执行失败，服务继续运行。请检查 cn.bootstrap 配置与数据库可用性。", e);
      return;
    }
    logOutcome(outcome);
  }

  private void logOutcome(PlatformAdminBootstrapper.Outcome outcome) {
    switch (outcome.kind()) {
      case CREATED -> {
        log.warn("已按部署配置引导平台管理员 accountId={}。请登录后更换口令，并移除引导配置。", outcome.accountId());
        if (properties.usingBuiltInDevPassword()) {
          log.warn("该管理员使用的是内置开发口令，仅限本地使用；部署到服务器前必须通过环境变量 " + "CN_BOOTSTRAP_ADMIN_PASSWORD 覆盖。");
        }
      }
      case PROMOTED -> log.warn("已按部署配置为既有账号补充平台管理员角色 accountId={}。请移除引导配置。", outcome.accountId());
      case SKIPPED_INACTIVE_HOLDER ->
          log.error("引导未执行：账号 {} 曾持有平台管理员角色但当前非 ACTIVE，需人工处理。", outcome.accountId());
      case SKIPPED_EXISTING_ADMIN ->
          log.info("已存在 {} 个活跃平台管理员，引导跳过。", outcome.existingAdminCount());
    }
  }

  /** 供运维核对，不含口令。 */
  public List<String> describeConfiguration() {
    return List.of(
        "enabled=" + properties.configured(),
        "username=" + properties.platformAdminUsername(),
        "builtInDevPassword=" + properties.usingBuiltInDevPassword());
  }
}
