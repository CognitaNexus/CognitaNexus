package com.cognitanexus.core.catalog.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ContributionRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.Contribution;
import com.cognitanexus.core.notification.application.NotificationService;
import com.cognitanexus.core.notification.domain.Notification;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.Capabilities;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.portal.application.PortalService;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.Csv;
import com.cognitanexus.sharedkernel.RelationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成果级关系处置：本人确认、本人提出异议、本人撤回异议、治理端裁决。
 *
 * <p>依据：SRS FR07、AC-IMPL-FR07-01、BR-IMP-03、BR-IMP-08、架构 V0.2 §5.4。
 *
 * <p><b>为什么这些动作只能改状态、不能删行</b>：一条参与关系既属于学者，也属于成果。删除它会让
 * 「曾经建立过、后被排除」这段历史消失，同一条成果上其他人的关系也会随之失去比对基线。因此
 * 全部动作都表现为 {@link RelationState} 的前置条件迁移，来源记录与发生时机机构原样保留。
 *
 * <p><b>为什么认定标准必须窄</b>：只有 {@link RelationState#AUTO} 与
 * {@link RelationState#SELF_CONFIRMED} 计入确定关系。本人提出异议立即降为
 * {@link RelationState#DISPUTED}——争议不许以「仍在确认中」的样子继续充作确定合作；
 * 反过来，本人确认也不能把 {@link RelationState#PENDING_VERIFY} 直接升成确认，因为「证据不足」
 * 这个成因不会因为本人的一句声明就消失，那属于治理端的核验职责。
 */
@Service
public class ContributionRelationService {

  private static final Logger log = LoggerFactory.getLogger(ContributionRelationService.class);

  /** 单次列出的关系条数上限：门户页按最近变化排序，不提供全量导出。 */
  private static final int MAX_LIST_SIZE = 200;

  private static final int DEFAULT_LIST_SIZE = 50;

  /**
   * 单次导出的行数上限。
   *
   * <p>上限的作用不是「防大文件」，而是让「导出不完整」这件事必须显式发生：超限时抛错要求改用
   * 后台导出，而不是悄悄只给前 N 行。
   */
  private static final int MAX_EXPORT_ROWS = 5000;

  private final ContributionRepository contributions;
  private final AchievementRepository achievements;
  private final PortalService portal;
  private final CatalogChangePublisher catalogChanges;
  private final AuditRecorder audit;
  private final NotificationService notifications;

  public ContributionRelationService(
      ContributionRepository contributions,
      AchievementRepository achievements,
      PortalService portal,
      CatalogChangePublisher catalogChanges,
      AuditRecorder audit,
      NotificationService notifications) {
    this.contributions = contributions;
    this.achievements = achievements;
    this.portal = portal;
    this.catalogChanges = catalogChanges;
    this.audit = audit;
    this.notifications = notifications;
  }

  /**
   * 我的成果：当前账号所管理门户上的参与关系。
   *
   * <p>返回每条的当前状态与「为什么现在不能点确认」。把不可操作的原因随列表一起给出，是为了避免
   * 界面上出现一排点了就报错的按钮——不可行的处置必须当场可见。
   */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> listOwnedContributions(
      ActorContext actor, String scholarId, int limit) {
    Capabilities.require(actor, RoleNames.CAP_PORTAL_MAINTAIN);
    portal.requirePortalAuthority(actor, scholarId);
    List<Map<String, Object>> items = new ArrayList<>();
    int capped = Math.min(limit <= 0 ? DEFAULT_LIST_SIZE : limit, MAX_LIST_SIZE);
    List<Contribution> rows = contributions.listByScholar(scholarId);
    for (Contribution contribution : rows.subList(0, Math.min(capped, rows.size()))) {
      items.add(view(contribution, achievements.findById(contribution.achievementId())));
    }
    return items;
  }

  /**
   * 导出「我的成果」为 CSV。
   *
   * <p>与列表接口的分工：列表有分页上限（界面上一次只显示一屏），导出必须是完整的——研究者拿
   * 这份文件去做年度考核或职称材料，缺行比报错更难发现。因此这里不分页，但设一个明确的硬上限：
   * 超过就**拒绝并说明**，不静默截断。一份少了一半的成果清单，当事人可能一年后才发现。
   *
   * <p>权限与列表完全相同（能力 + 门户归属），并且只导出本人可见的关系行——包括待核验与争议行。
   * 它们带 {@code 计入合作网络=否} 列：导出的是「我与这条成果的关系记录」，不是「我确认的成果」，
   * 把未确认的行悄悄剔掉，等于让本人看不到自己需要处理的那部分。
   */
  @Transactional(readOnly = true)
  public byte[] exportOwnedContributionsCsv(ActorContext actor, String scholarId) {
    Capabilities.require(actor, RoleNames.CAP_PORTAL_MAINTAIN);
    portal.requirePortalAuthority(actor, scholarId);
    List<Contribution> rows = contributions.listByScholar(scholarId);
    if (rows.size() > MAX_EXPORT_ROWS) {
      throw BusinessException.invalidArgument(
          "该门户下参与关系共 "
              + rows.size()
              + " 条，超过单次导出上限 "
              + MAX_EXPORT_ROWS
              + " 条。这是数据规模提示，不是可忽略的警告——请联系平台管理员改用后台导出，"
              + "而不是接受一份被截断的清单");
    }
    StringBuilder csv = Csv.begin();
    Csv.appendRow(
        csv, "关系标识", "成果标识", "成果类型", "题名", "发表年份", "数据性质", "生效状态", "本人角色", "署名序", "关系状态", "计入合作网络");
    for (Contribution contribution : rows) {
      Achievement achievement = achievements.findById(contribution.achievementId()).orElse(null);
      Csv.appendRow(
          csv,
          contribution.id(),
          contribution.achievementId(),
          achievement == null ? "" : achievement.type().label(),
          achievement == null ? "" : achievement.title(),
          achievement == null || achievement.publicationYear().year() == null
              ? ""
              : String.valueOf(achievement.publicationYear().year()),
          achievement == null ? "" : achievement.dataNature().name(),
          achievement == null ? "" : achievement.effectiveStatus().name(),
          contribution.role(),
          contribution.authorOrder() == null ? "" : String.valueOf(contribution.authorOrder()),
          contribution.relationState().name(),
          contribution.qualifiedForGraph() ? "是" : "否");
    }
    return Csv.toBytes(csv);
  }

  /** 本人确认参与关系：AUTO → SELF_CONFIRMED。重复确认返回当前状态，不报错也不重复写审计。 */
  @Transactional
  public Map<String, Object> confirmByOwner(ActorContext actor, String contributionId) {
    Contribution contribution = loadOwned(actor, contributionId);
    RelationState current = contribution.relationState();
    if (current == RelationState.SELF_CONFIRMED) {
      return withNote(view(contribution, achievementOf(contribution)), "该关系此前已由本人确认，本次未做变更");
    }
    requireTransitionable(current, "确认");
    transition(contribution, current, RelationState.SELF_CONFIRMED);
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "RELATION_SELF_CONFIRMED",
            "CONTRIBUTION",
            contribution.id(),
            Map.of("relationState", current.name()),
            Map.of("relationState", RelationState.SELF_CONFIRMED.name()),
            "学者本人确认参与关系"));
    return finish(contribution, current, RelationState.SELF_CONFIRMED, "已确认该关系");
  }

  /**
   * 本人提出异议：AUTO / SELF_CONFIRMED → DISPUTED。
   *
   * <p>异议必须写理由：治理端要以它为依据判断是「关联错了」还是「本人不认这条成果」。允许无理由
   * 的异议等于让一个按钮就能把他人成果从公开关系里拿掉，且不留任何可核对的依据。
   */
  @Transactional
  public Map<String, Object> disputeByOwner(
      ActorContext actor, String contributionId, String reason) {
    Contribution contribution = loadOwned(actor, contributionId);
    String note = reason == null ? "" : reason.trim();
    if (note.length() < 4) {
      throw BusinessException.invalidArgument("请说明提出异议的理由（至少 4 个字），治理端需要据此裁决");
    }
    RelationState current = contribution.relationState();
    if (current == RelationState.DISPUTED) {
      return withNote(view(contribution, achievementOf(contribution)), "该关系已处于争议状态，本次未做变更");
    }
    requireTransitionable(current, "提出异议");
    transition(contribution, current, RelationState.DISPUTED);
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "RELATION_DISPUTED",
            "CONTRIBUTION",
            contribution.id(),
            Map.of("relationState", current.name()),
            Map.of("relationState", RelationState.DISPUTED.name(), "reason", note),
            note));
    return finish(contribution, current, RelationState.DISPUTED, "已提出异议，该关系在裁决前不计入确定合作");
  }

  /** 本人撤回异议：DISPUTED → AUTO。撤回异议不等于确认关系，因此回到自动关联而不是已确认。 */
  @Transactional
  public Map<String, Object> withdrawDisputeByOwner(ActorContext actor, String contributionId) {
    Contribution contribution = loadOwned(actor, contributionId);
    RelationState current = contribution.relationState();
    if (current != RelationState.DISPUTED) {
      throw BusinessException.businessRule("当前关系不处于争议状态，无需撤回异议");
    }
    transition(contribution, current, RelationState.AUTO);
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "RELATION_DISPUTE_WITHDRAWN",
            "CONTRIBUTION",
            contribution.id(),
            Map.of("relationState", current.name()),
            Map.of("relationState", RelationState.AUTO.name()),
            "本人撤回异议"));
    return finish(contribution, current, RelationState.AUTO, "已撤回异议，关系回到自动关联");
  }

  /**
   * 治理端裁决。
   *
   * <p>三种结论的含义刻意分开：
   *
   * <ul>
   *   <li>{@code EXCLUDE}：判定该参与关系不成立 → EXCLUDED，此后不计入确定关系，也不建边；
   *   <li>{@code CONFIRM}：核验后确认参与关系成立 → SELF_CONFIRMED，这是唯一能把待核验关系
   *       升为确定关系的路径；
   *   <li>{@code RESTORE}：撤销此前的排除或争议结论 → AUTO，回到自动关联而不是直接当成已确认。
   * </ul>
   */
  @Transactional
  public Map<String, Object> resolveByAdmin(
      ActorContext actor, String contributionId, String decision, String reason) {
    Capabilities.require(actor, RoleNames.CAP_GOVERNANCE_MANAGE);
    Contribution contribution =
        contributions
            .findContributionById(contributionId)
            .orElseThrow(() -> BusinessException.notFound("参与关系不存在"));
    String note = reason == null ? "" : reason.trim();
    if (note.isBlank()) {
      throw BusinessException.invalidArgument("裁决必须写明理由，事后要能回答这条关系为什么被改");
    }
    RelationState current = contribution.relationState();
    RelationState target =
        switch (decision == null ? "" : decision.trim().toUpperCase()) {
          case "EXCLUDE" -> RelationState.EXCLUDED;
          case "CONFIRM" -> RelationState.SELF_CONFIRMED;
          case "RESTORE" -> RelationState.AUTO;
          default -> throw BusinessException.invalidArgument("未知裁决结论：" + decision);
        };
    if (current == target) {
      return withNote(view(contribution, achievementOf(contribution)), "关系已处于该结论，本次未做变更");
    }
    boolean allowed =
        switch (target) {
          case EXCLUDED -> current != RelationState.EXCLUDED;
          case SELF_CONFIRMED -> current != RelationState.EXCLUDED;
          case AUTO -> current == RelationState.EXCLUDED || current == RelationState.DISPUTED;
          default -> false;
        };
    if (!allowed) {
      throw BusinessException.businessRule(
          "不能把关系从 " + current.name() + " 改为 " + target.name() + "；请选择与当前状态相符的裁决");
    }
    transition(contribution, current, target);
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "RELATION_RESOLVED",
            "CONTRIBUTION",
            contribution.id(),
            Map.of("relationState", current.name()),
            Map.of("relationState", target.name(), "decision", target.name()),
            note));
    // 通知该学者的认领账号（若有）：关系处置直接影响「本人确认 / 待核验 / 已排除」的公开语义，
    // 当事人不该靠刷新列表发现（ADR-003 §3 第四行）。
    notifications.sendToScholarClaimants(
        List.of(contribution.scholarId()),
        Notification.KIND_RELATION_DECIDED,
        "参与关系已裁决",
        "你管理的学者档案与成果「"
            + achievementTitle(contribution.achievementId())
            + "」的参与关系已由治理端裁决："
            + relationStateLabel(current)
            + " → "
            + relationStateLabel(target)
            + "。理由："
            + note,
        "ACHIEVEMENT",
        contribution.achievementId());
    return finish(contribution, current, target, "裁决已生效：" + target.name());
  }

  /** 关系状态的用户可读措辞。 */
  private static String relationStateLabel(RelationState state) {
    return switch (state) {
      case AUTO -> "自动关联";
      case SELF_CONFIRMED -> "本人确认";
      case PENDING_VERIFY -> "待核验";
      case DISPUTED -> "争议中";
      case EXCLUDED -> "已排除";
    };
  }

  /** 成果标题的宽松取值：标题缺失（如测试替身没建实体）时退回成果 id，不因此让裁决失败。 */
  private String achievementTitle(String achievementId) {
    return achievements.findById(achievementId).map(Achievement::title).orElse(achievementId);
  }

  /** 治理端：当前处于争议状态的关系，按最近变化排序。 */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> listDisputed(ActorContext actor, int limit) {
    Capabilities.require(actor, RoleNames.CAP_GOVERNANCE_MANAGE);
    int capped = Math.min(limit <= 0 ? DEFAULT_LIST_SIZE : limit, MAX_LIST_SIZE);
    List<Map<String, Object>> items = new ArrayList<>();
    for (Contribution contribution :
        contributions.listByRelationState(RelationState.DISPUTED, capped)) {
      items.add(view(contribution, achievements.findById(contribution.achievementId())));
    }
    return items;
  }

  // ---------------------------------------------------------------------------
  // 内部
  // ---------------------------------------------------------------------------

  private Contribution loadOwned(ActorContext actor, String contributionId) {
    Capabilities.require(actor, RoleNames.CAP_PORTAL_MAINTAIN);
    Contribution contribution =
        contributions
            .findContributionById(contributionId)
            .orElseThrow(() -> BusinessException.notFound("参与关系不存在"));
    // 对象级授权：平台管理员身份不构成「可以替某个学者确认关系」的理由，必须持有该门户的授权。
    portal.requirePortalAuthority(actor, contribution.scholarId());
    return contribution;
  }

  /** 本人可操作的状态。已被排除的关系只能由治理端恢复，本人不能自行推翻裁决。 */
  private void requireTransitionable(RelationState current, String action) {
    switch (current) {
      case AUTO, SELF_CONFIRMED -> {
        // 允许
      }
      case PENDING_VERIFY ->
          throw BusinessException.businessRule("该关系处于待核验状态，本人的" + action + "不能替代证据核验；请先由治理端核验");
      case DISPUTED -> throw BusinessException.businessRule("该关系处于争议状态，请先撤回异议或等待治理端裁决");
      case EXCLUDED -> throw BusinessException.businessRule("该关系已被判定不成立，如需恢复请联系治理端");
      default -> throw BusinessException.businessRule("当前状态不支持该操作：" + current.name());
    }
  }

  private void transition(Contribution contribution, RelationState from, RelationState to) {
    int affected = contributions.transitionRelationState(contribution.id(), from, to);
    if (affected != 1) {
      // 0 行不是「没影响」，而是「我看到的旧状态已经不是现在的事实」。
      throw BusinessException.versionConflict("关系状态已被其他操作改变，请刷新后重试");
    }
  }

  private Map<String, Object> finish(
      Contribution contribution, RelationState from, RelationState to, String note) {
    Optional<Achievement> achievement = achievementOf(contribution);
    if (achievement.isPresent()) {
      // 关系状态是公开投影的一部分（贡献者列表与共著网络都读它），迁移后必须重新外发；
      // 不重发的话，公开视图会继续按旧状态把争议关系显示成确定合作。
      catalogChanges.publish(
          achievement.get(), Math.max(contribution.version(), achievement.get().version()));
    } else {
      log.warn(
          "关系状态已迁移，但读不到所属成果，未外发目录变更 contributionId={} achievementId={}",
          contribution.id(),
          contribution.achievementId());
    }
    Contribution refreshed =
        contributions.findContributionById(contribution.id()).orElse(contribution);
    Map<String, Object> view = view(refreshed, achievement);
    view.put("previousState", from.name());
    return withNote(view, note);
  }

  private Optional<Achievement> achievementOf(Contribution contribution) {
    return achievements.findById(contribution.achievementId());
  }

  private static Map<String, Object> withNote(Map<String, Object> view, String note) {
    view.put("note", note);
    return view;
  }

  private Map<String, Object> view(Contribution contribution, Optional<Achievement> achievement) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("contributionId", contribution.id());
    view.put("scholarId", contribution.scholarId());
    view.put("achievementId", contribution.achievementId());
    view.put("role", contribution.role());
    view.put("authorOrder", contribution.authorOrder());
    view.put("relationState", contribution.relationState().name());
    view.put("qualified", contribution.qualifiedForGraph());
    view.put("stateNote", stateNote(contribution.relationState()));
    view.put("version", contribution.version());
    achievement.ifPresent(
        a -> {
          view.put("achievementType", a.type().name());
          view.put("achievementTypeLabel", a.type().label());
          view.put("title", a.title());
          view.put("publicationYear", a.publicationYear().year());
          view.put("dataNature", a.dataNature().name());
          view.put("effectiveStatus", a.effectiveStatus().name());
        });
    // 当前可执行的本人动作随记录一起给出：不可行的处置不显示成可点的按钮。
    view.put("availableActions", availableActions(contribution.relationState()));
    return view;
  }

  private static List<String> availableActions(RelationState state) {
    return switch (state) {
      case AUTO -> List.of("CONFIRM", "DISPUTE");
      case SELF_CONFIRMED -> List.of("DISPUTE");
      case DISPUTED -> List.of("WITHDRAW_DISPUTE");
      case PENDING_VERIFY, EXCLUDED -> List.of();
    };
  }

  private static String stateNote(RelationState state) {
    return switch (state) {
      case AUTO -> "系统按稳定标识建立的关联，尚未经本人确认；已计入确定关系";
      case SELF_CONFIRMED -> "本人已确认的参与关系；计入确定关系";
      case PENDING_VERIFY -> "证据不足，等待核验；不计入确定关系，也不建合作边";
      case DISPUTED -> "存在争议，等待裁决；裁决前不计入确定关系";
      case EXCLUDED -> "经审核判定该参与关系不成立；不计入确定关系";
    };
  }
}
