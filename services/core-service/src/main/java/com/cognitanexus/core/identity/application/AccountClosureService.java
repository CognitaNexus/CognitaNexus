package com.cognitanexus.core.identity.application;

import com.cognitanexus.core.identity.domain.Account;
import com.cognitanexus.core.identity.infrastructure.ClosureMapper;
import com.cognitanexus.core.identity.infrastructure.ClosureMapper.ClosureRow;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.Capabilities;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.sharedkernel.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 账号注销申请。
 *
 * <p>依据：合规向的账号自助能力。核心是一条：<b>注销不等于删除数据</b>。
 *
 * <p>这个账号可能是一批认领申请、成果提交、审计记录的 actor。物理删除会让「谁在什么时候
 * 批准了这条成果」变成悬空的 actor_id；级联删审计又等于销毁审计。因此执行注销时做三件事：
 * 停用账号、撤销角色、提升认证版本（既有会话立即失效）。用户看到的效果与注销一致——
 * 登不上、资料不再对外——但「这条决定是谁做的」仍然查得到。
 *
 * <p>冷静期不是形式：{@code effective_at} 写在 SQL 里参与裁决条件，管理员的机器时钟无法绕过。
 * 申请人在冷静期内可以随时撤销，撤销只需要一条 UPDATE，不需要任何审批。
 */
@Service
public class AccountClosureService {

  private static final Logger log = LoggerFactory.getLogger(AccountClosureService.class);

  /** 冷静期天数。7 天是工程默认值，须与运营确认后调整；这里不宣称已通过法务评审。 */
  static final int COOLING_DAYS = 7;

  private static final int MAX_REASON = 500;
  private static final int MAX_NOTE = 500;
  private static final int MAX_LIMIT = 100;

  /** 单轮自动执行上限。冷静期届满的申请不会一夜之间堆积到几千条，但批量要有上界。 */
  static final int MAX_AUTO_BATCH = 50;

  private final ClosureMapper closures;
  private final IdentityRepositories.AccountRepository accounts;
  private final AuditRecorder audit;

  /** 自动执行时逐条独立提交：一条失败不能连累同一批里的其他申请。 */
  private final TransactionTemplate rowTx;

  public AccountClosureService(
      ClosureMapper closures,
      IdentityRepositories.AccountRepository accounts,
      AuditRecorder audit,
      PlatformTransactionManager txManager) {
    this.closures = closures;
    this.accounts = accounts;
    this.audit = audit;
    this.rowTx = new TransactionTemplate(txManager);
  }

  /** 本人的申请视图。effectiveAt 是冷静期结束时刻，之前执行会被拒。 */
  public record ClosureView(
      String id,
      String accountId,
      String accountUsername,
      String reason,
      String status,
      java.time.Instant requestedAt,
      java.time.Instant effectiveAt,
      java.time.Instant decidedAt,
      String decisionNote) {}

  @Transactional
  public ClosureView request(ActorContext actor, String reason) {
    requireActor(actor);
    String safeReason = requireText(reason, MAX_REASON, "注销理由");
    String accountId = actor.accountIdValue();

    if (closures.findOpenByAccount(accountId).isPresent()) {
      throw BusinessException.businessRule("已有待处理的注销申请；如需继续请等待处理，或先撤销原申请");
    }
    Account account =
        accounts.findById(accountId).orElseThrow(() -> BusinessException.notFound("账号不存在"));
    if (!account.canAuthenticate()) {
      throw BusinessException.businessRule("账号已停用，无需再次申请注销");
    }

    String id = Identifiers.next("clo");
    closures.insert(id, accountId, safeReason, COOLING_DAYS);
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "ACCOUNT_CLOSURE_REQUESTED",
            "ACCOUNT",
            accountId,
            null,
            Map.of("coolingDays", String.valueOf(COOLING_DAYS)),
            "本人申请注销账号：" + safeReason));
    log.info("账号注销申请已登记 id={} account={}", id, accountId);
    return toView(closures.findById(id).orElseThrow());
  }

  @Transactional(readOnly = true)
  public ClosureView mine(ActorContext actor) {
    requireActor(actor);
    return closures.findOpenByAccount(actor.accountIdValue()).map(this::toView).orElse(null);
  }

  /** 本人撤销。返回受影响行数，0 表示没有可撤销的申请（已裁决或不存在）。 */
  @Transactional
  public int cancel(ActorContext actor, String requestId) {
    requireActor(actor);
    int affected = closures.cancel(requestId, actor.accountIdValue());
    if (affected == 0) {
      // 不区分「不存在」与「不是你的」：ID 不可枚举，统一按无权限处理。
      throw BusinessException.accessDenied("没有可撤销的注销申请（不存在或已被处理）");
    }
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "ACCOUNT_CLOSURE_CANCELLED",
            "ACCOUNT",
            actor.accountIdValue(),
            null,
            null,
            "本人撤销注销申请"));
    return affected;
  }

  @Transactional(readOnly = true)
  public List<ClosureView> pending(ActorContext actor, int limit, int offset) {
    Capabilities.require(actor, RoleNames.CAP_ACCOUNT_MANAGE);
    int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
    return closures.listPending(capped, Math.max(offset, 0)).stream().map(this::toView).toList();
  }

  @Transactional(readOnly = true)
  public long countPending() {
    return closures.countPending();
  }

  /**
   * 裁决。
   *
   * <p>APPROVE 会真的停用账号——不可撤销的操作，因此三道闸：不能批自己、冷静期必须已过、
   * 只处理 PENDING。任一不满足都由 SQL 的 WHERE 表现为 0 行，这里翻译成具体原因。
   */
  @Transactional
  public Map<String, Object> decide(
      ActorContext actor, String requestId, String decision, String note) {
    Capabilities.require(actor, RoleNames.CAP_ACCOUNT_MANAGE);
    ClosureRow row =
        closures.findById(requestId).orElseThrow(() -> BusinessException.notFound("申请不存在"));
    if (row.accountId().equals(actor.accountIdValue())) {
      throw BusinessException.businessRule("不能审批自己的注销申请；如需注销请由其他管理员处理");
    }
    if (!"PENDING".equals(row.status())) {
      throw BusinessException.versionConflict("该申请已被处理，请刷新队列");
    }
    String normalized = decision == null ? "" : decision.trim().toUpperCase(java.util.Locale.ROOT);
    String targetStatus;
    String action;
    if (normalized.equals("APPROVE")) {
      targetStatus = "APPROVED";
      action = "停用账号并撤销全部角色";
    } else if (normalized.equals("REJECT")) {
      targetStatus = "REJECTED";
      action = "驳回申请，账号保持可用";
    } else {
      throw BusinessException.invalidArgument("裁决只支持 APPROVE 或 REJECT");
    }
    String safeNote = note == null || note.isBlank() ? null : requireText(note, MAX_NOTE, "裁决说明");

    int affected = closures.decide(requestId, targetStatus, actor.accountIdValue(), safeNote);
    if (affected == 0) {
      // 走到这里只有两种可能：状态被并发改掉，或冷静期未满。
      throw BusinessException.businessRule(
          "执行失败：申请状态已变化，或冷静期尚未结束（" + row.effectiveAt() + " 之后才可执行）");
    }

    if (targetStatus.equals("APPROVED")) {
      disableAccountForClosure(row);
    }

    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            targetStatus.equals("APPROVED")
                ? "ACCOUNT_CLOSURE_APPROVED"
                : "ACCOUNT_CLOSURE_REJECTED",
            "ACCOUNT",
            row.accountId(),
            Map.of("status", row.status()),
            Map.of("status", targetStatus),
            (safeNote == null ? "" : safeNote + "；") + action));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", requestId);
    result.put("status", targetStatus);
    result.put("accountId", row.accountId());
    result.put("action", action);
    result.put(
        "note",
        targetStatus.equals("APPROVED")
            ? "账号已停用，既有会话立即失效。业务数据未删除：认领、提交、审计记录保留在原处，" + "以该账号为 actor 的历史记录仍然可查。"
            : "申请已驳回，账号保持可用。");
    return result;
  }

  /**
   * 冷静期届满的申请自动执行。
   *
   * <p>为什么必须有这一段：冷静期是「申请人反悔的窗口」，不是「管理员决定的窗口」。只在
   * 管理端留一个待处理队列，等于把「7 天后生效」变成「7 天后等管理员点一下才生效」——
   * 管理员休假一个月，用户的注销就挂一个月，而页面已经告诉他 7 天后生效。这是承诺与
   * 实现不一致，不是体验问题。
   *
   * <p>管理员的否决权在冷静期内行使：想驳回必须在 {@code effective_at} 之前点，过期即执行。
   *
   * <p>多条之间互相隔离：每条一个独立事务，某条账号已被手工停用或状态被并发改掉时跳过，
   * 不影响同批其他申请。返回实际执行条数。
   */
  public int executeDue(int limit) {
    int capped = Math.min(Math.max(limit, 1), MAX_AUTO_BATCH);
    int executed = 0;
    for (ClosureRow row : closures.listDue(capped)) {
      try {
        Boolean done = rowTx.execute(status -> autoApprove(row));
        if (Boolean.TRUE.equals(done)) {
          executed++;
        }
      } catch (RuntimeException e) {
        // 单条失败不改状态、不吞掉批次：下一轮扫描还会拿到它，但至少其他申请能正常执行。
        log.error("自动执行注销失败 id={} account={}", row.id(), row.accountId(), e);
      }
    }
    return executed;
  }

  /** 在独立事务内完成一条自动执行。返回 false 表示这行已被别人改掉（管理员刚裁过或另一实例先跑了）。 */
  private boolean autoApprove(ClosureRow row) {
    String note = "冷静期届满，由系统任务自动执行";
    // decided_by 传 null：这里没有人类裁决人，审计主体是 SYSTEM。
    int affected = closures.decide(row.id(), "APPROVED", null, note);
    if (affected == 0) {
      return false;
    }
    disableAccountForClosure(row);
    audit.record(
        AuditRecorder.AuditEntry.system(
            "ACCOUNT_CLOSURE_APPROVED",
            "ACCOUNT",
            row.accountId(),
            "冷静期届满自动执行注销：账号已停用，业务数据与审计记录保留在原处"));
    log.info("注销冷静期届满已自动执行 id={} account={}", row.id(), row.accountId());
    return true;
  }

  /** 注销的「执行」只有一处实现：停用账号。没有删除动作，这是 ADR-003 的边界。 */
  private void disableAccountForClosure(ClosureRow row) {
    int disabled = accounts.disable(row.accountId());
    if (disabled != 1) {
      // 账号已被别人停用不是错误，但要让审批人知道「这次调用没有实际改变状态」。
      log.warn("注销执行时账号已非启用状态 account={}", row.accountId());
    }
  }

  private static void requireActor(ActorContext actor) {
    if (actor == null) {
      throw new BusinessException(com.cognitanexus.sharedkernel.ErrorCode.AUTH_REQUIRED, "请先登录");
    }
  }

  private static String requireText(String value, int max, String what) {
    if (value == null || value.isBlank()) {
      throw BusinessException.invalidArgument(what + "不能为空");
    }
    String trimmed = value.trim();
    if (trimmed.length() > max) {
      throw BusinessException.invalidArgument(what + "超过 " + max + " 字");
    }
    return trimmed;
  }

  private ClosureView toView(ClosureRow row) {
    return new ClosureView(
        row.id(),
        row.accountId(),
        row.accountUsername(),
        row.reason(),
        row.status(),
        row.requestedAt(),
        row.effectiveAt(),
        row.decidedAt(),
        row.decisionNote());
  }
}
