package com.cognitanexus.core.identity.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台管理员引导配置。
 *
 * <p>依据：架构 V0.2 §10.1。引导是「全新部署时如何获得第一个平台管理员」的唯一受控入口，
 * 因此必须由部署配置显式提供凭据，不能依赖注册顺序。
 *
 * <p>安全约定：
 *
 * <ul>
 *   <li>口令只在此处声明，不写日志、不出现在任何接口响应中；
 *   <li>一旦库中存在活跃平台管理员，引导不再执行，配置可保留或移除；
 *   <li>内置开发口令仅用于本地，启动时会输出告警，生产部署必须通过环境变量覆盖。
 * </ul>
 *
 * @param platformAdminUsername 引导管理员登录名；为空或空白表示不启用引导
 * @param platformAdminPassword 引导管理员口令；仅在目标账号不存在时用于创建账号
 * @param platformAdminDisplayName 新建账号的显示名，默认「平台管理员」
 */
@ConfigurationProperties(prefix = "cn.bootstrap")
public record BootstrapProperties(
    String platformAdminUsername, String platformAdminPassword, String platformAdminDisplayName) {

  /** 本地开发默认口令占位，生产必须覆盖。 */
  public static final String BUILT_IN_DEV_PASSWORD = "cn-admin-Dev-2026";

  public BootstrapProperties {
    platformAdminDisplayName =
        platformAdminDisplayName == null || platformAdminDisplayName.isBlank()
            ? "平台管理员"
            : platformAdminDisplayName;
  }

  /** 是否具备执行引导的最小配置。 */
  public boolean configured() {
    return platformAdminUsername != null
        && !platformAdminUsername.isBlank()
        && platformAdminPassword != null
        && !platformAdminPassword.isBlank();
  }

  /** 是否仍在使用内置开发口令。 */
  public boolean usingBuiltInDevPassword() {
    return BUILT_IN_DEV_PASSWORD.equals(platformAdminPassword);
  }
}
