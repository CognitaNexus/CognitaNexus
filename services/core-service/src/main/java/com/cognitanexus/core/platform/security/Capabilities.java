package com.cognitanexus.core.platform.security;

import com.cognitanexus.sharedkernel.BusinessException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 角色到能力的映射与校验。
 *
 * <p>依据：架构 V0.2 §10.2「三层授权」。角色表达身份，能力表达具体操作许可，两者分开的原因：
 *
 * <ul>
 *   <li>对象级授权无法用角色表达（「审核人」不等于「这个门户的管理者」）；
 *   <li>把判断写在 Controller 或 URI 前缀上会让新增路由时忘记加检查（A05）。
 * </ul>
 *
 * <p>因此应用层在每次受保护写入时显式调用校验，而不是依赖 URL 约定。前端的隐藏入口不构成授权。
 */
public final class Capabilities {

  private Capabilities() {}

  /**
   * 由角色推导出的能力集合。
   *
   * <p>平台管理员拥有全部能力，但对象级授权<b>不</b>因管理员身份自动获得：管理员可以管理来源与
   * 治理，但不能凭身份直接改写某个学者的门户归属，除非另有授权或走治理流程。
   */
  public static Set<String> of(ActorContext actor) {
    if (actor == null) {
      return Set.of();
    }
    Set<String> capabilities = new LinkedHashSet<>();
    if (actor.hasRole(RoleNames.USER)) {
      capabilities.add(RoleNames.CAP_CLAIM_SUBMIT);
      capabilities.add(RoleNames.CAP_PORTAL_MAINTAIN);
    }
    if (actor.hasRole(RoleNames.REVIEWER)) {
      capabilities.add(RoleNames.CAP_CLAIM_REVIEW);
      capabilities.add(RoleNames.CAP_CHANGE_REVIEW);
      capabilities.add(RoleNames.CAP_GOVERNANCE_MANAGE);
      capabilities.add(RoleNames.CAP_AUDIT_READ);
      capabilities.add(RoleNames.CAP_ACHIEVEMENT_CONFIRM);
    }
    if (actor.hasRole(RoleNames.PLATFORM_ADMIN)) {
      for (String capability : all()) {
        capabilities.add(capability);
      }
    }
    return Set.copyOf(capabilities);
  }

  public static Set<String> all() {
    Set<String> all = new LinkedHashSet<>();
    all.add(RoleNames.CAP_CLAIM_REVIEW);
    all.add(RoleNames.CAP_CLAIM_SUBMIT);
    all.add(RoleNames.CAP_PORTAL_MAINTAIN);
    all.add(RoleNames.CAP_CHANGE_REVIEW);
    all.add(RoleNames.CAP_SOURCE_MANAGE);
    all.add(RoleNames.CAP_GOVERNANCE_MANAGE);
    all.add(RoleNames.CAP_AUDIT_READ);
    all.add(RoleNames.CAP_ACCOUNT_MANAGE);
    all.add(RoleNames.CAP_ACHIEVEMENT_CONFIRM);
    return Set.copyOf(all);
  }

  public static boolean has(ActorContext actor, String capability) {
    return of(actor).contains(capability);
  }

  /** 要求具备某项能力，否则 403。网络超时与 403 必须在错误码上区分，不能都跳登录页。 */
  public static void require(ActorContext actor, String capability) {
    if (actor == null) {
      throw new BusinessException(com.cognitanexus.sharedkernel.ErrorCode.AUTH_REQUIRED, "请先登录");
    }
    if (!has(actor, capability)) {
      throw BusinessException.accessDenied("当前账号不具备该操作的能力");
    }
  }
}
