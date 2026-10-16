package com.cognitanexus.core.platform.audit;

import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import java.util.Map;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计记录端口。
 *
 * <p>依据：架构 V0.2 §6.1、AC-IMPL-FR14-02。审计是显式应用端口，与业务在同一个本地事务内提交，
 * 「审核/授权/审计同时成功或同时回滚」（A09）；AOP 只能补通用追踪，不能作为审计必定完整的唯一保证。
 *
 * <p>调用方未显式传入操作者时，从认证上下文取真实操作者；系统任务使用 SYSTEM 主体，
 * 不创建假管理员账号绕过审计。
 */
public interface AuditRecorder {

  /**
   * 在调用方事务内写入一条审计事件。
   *
   * <p>使用 MANDATORY 传播：调用方没有事务时直接失败，避免审计落库而业务回滚产生不一致痕迹。
   */
  @Transactional(propagation = Propagation.MANDATORY)
  void record(AuditEntry entry);

  /** 构造审计条目。before/after 只允许放可公开的字段快照，不得放材料原文或口令。 */
  record AuditEntry(
      String actorAccountId,
      String actorUsername,
      String action,
      String targetType,
      String targetId,
      Map<String, Object> beforeValue,
      Map<String, Object> afterValue,
      String reason) {

    public static AuditEntry of(
        ActorContext actor,
        String action,
        String targetType,
        String targetId,
        Map<String, Object> beforeValue,
        Map<String, Object> afterValue,
        String reason) {
      String accountId = actor == null ? null : actor.accountIdValue();
      String username = actor == null ? RoleNames.SYSTEM : actor.username();
      return new AuditEntry(
          accountId, username, action, targetType, targetId, beforeValue, afterValue, reason);
    }

    public static AuditEntry system(
        String action, String targetType, String targetId, String reason) {
      return new AuditEntry(
          null, RoleNames.SYSTEM, action, targetType, targetId, null, null, reason);
    }
  }
}
