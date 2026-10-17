package com.cognitanexus.core.portal.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.FieldProvenanceRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ScholarRepository;
import com.cognitanexus.core.catalog.domain.FieldProvenance;
import com.cognitanexus.core.catalog.domain.Scholar;
import com.cognitanexus.core.identity.application.IdentityRepositories.PortalAuthorityRepository;
import com.cognitanexus.core.identity.domain.PortalAuthority;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.event.EventTypes;
import com.cognitanexus.core.platform.event.OutboxEvent;
import com.cognitanexus.core.platform.event.OutboxStore;
import com.cognitanexus.core.platform.idempotency.IdempotencyGuard;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.Capabilities;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.core.platform.util.PageRequest;
import com.cognitanexus.core.portal.domain.ClaimEvidence;
import com.cognitanexus.core.portal.domain.ClaimRequest;
import com.cognitanexus.core.portal.domain.PortalChangeRequest;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 门户流程：认领申请 → 审批 → 授权 → 审计 → 事件，以及门户信息变更。
 *
 * <p>依据：架构 V0.2 §4.4、§6.1、AC-IMPL-FR05-01/02/03、AC-IMPL-FR06-01/02。
 *
 * <p>为什么认领与授权留在核心服务：批准动作同时需要检查账号能力、申请状态、竞争申请、门户授权唯一性、
 * 审计与对外事件，这些必须在同一个本地事务内成立。把它们拆到「身份服务」只会制造分布式提交，
 * 而用 HTTP 调用塞进数据库事务并不能获得原子性（架构 §4.4）。
 *
 * <p>核心业务语义：认领通过只授予门户管理权，<b>不</b>确认该学者的全部成果真实性，也不改变任何
 * 参与关系的状态。
 */
@Service
public class PortalService {

  private static final Logger log = LoggerFactory.getLogger(PortalService.class);

  private static final int MAX_STATEMENT_LENGTH = 2000;

  private final PortalRepositories.ClaimRequestRepository claims;
  private final PortalRepositories.PortalChangeRequestRepository changes;
  private final PortalAuthorityRepository authorities;
  private final ScholarRepository scholars;
  private final FieldProvenanceRepository provenance;
  private final EvidenceStorage evidenceStorage;
  private final IdempotencyGuard idempotency;
  private final OutboxStore outbox;
  private final AuditRecorder audit;

  public PortalService(
      PortalRepositories.ClaimRequestRepository claims,
      PortalRepositories.PortalChangeRequestRepository changes,
      PortalAuthorityRepository authorities,
      ScholarRepository scholars,
      FieldProvenanceRepository provenance,
      EvidenceStorage evidenceStorage,
      IdempotencyGuard idempotency,
      OutboxStore outbox,
      AuditRecorder audit) {
    this.claims = claims;
    this.changes = changes;
    this.authorities = authorities;
    this.scholars = scholars;
    this.provenance = provenance;
    this.evidenceStorage = evidenceStorage;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.audit = audit;
  }

  // ===========================================================================
  // 认领
  // ===========================================================================

  /** 提交认领申请。 */
  @Transactional
  public ClaimRequest submitClaim(
      ActorContext actor, String scholarId, String statement, String idempotencyKey) {
    Capabilities.require(actor, RoleNames.CAP_CLAIM_SUBMIT);
    String text = requireStatement(statement);

    Scholar scholar =
        scholars.findById(scholarId).orElseThrow(() -> BusinessException.notFound("学者不存在"));
    if (scholar.merged()) {
      throw BusinessException.businessRule("该学者档案已被合并，请对最终实体提交认领");
    }

    IdempotencyGuard.Reservation reservation =
        idempotency.reserve(
            actor.accountIdValue(), "CLAIM_SUBMIT", idempotencyKey, scholarId + "|" + text);
    if (reservation.isReplay()) {
      Optional<ClaimRequest> existing =
          claims.findClaimById(String.valueOf(reservation.resultReference()));
      if (existing.isPresent()) {
        return existing.get();
      }
    }

    Optional<ClaimRequest> alreadyOpen = claims.findOpenClaim(actor.accountIdValue(), scholarId);
    if (alreadyOpen.isPresent()) {
      return alreadyOpen.get();
    }

    String claimId = Identifiers.next("clm");
    claims.insert(
        new ClaimRequest(
            claimId,
            actor.accountIdValue(),
            scholarId,
            ClaimRequest.Status.PENDING_REVIEW,
            text,
            Instant.now(),
            null,
            null,
            null,
            idempotencyKey,
            1L));

    // 认领状态只是「有人主张」，不等于已认领；迁不动说明状态已变，不覆盖
    scholars.transitionClaimedStatus(
        scholarId,
        Scholar.ClaimedStatus.UNCLAIMED.name(),
        Scholar.ClaimedStatus.CLAIM_PENDING.name());

    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "CLAIM_SUBMITTED",
            "CLAIM_REQUEST",
            claimId,
            null,
            Map.of("scholarId", scholarId, "status", "PENDING_REVIEW"),
            "提交认领申请"));

    idempotency.attachResult(actor.accountIdValue(), "CLAIM_SUBMIT", idempotencyKey, claimId);
    log.info("认领申请已提交 claimId={} scholarId={}", claimId, scholarId);
    return claims.findClaimById(claimId).orElseThrow();
  }

  /** 追加认领材料。文件先落受控卷并校验类型，再把元数据与哈希登记入库。 */
  @Transactional
  public ClaimEvidence attachEvidence(
      ActorContext actor,
      String claimId,
      String originalFilename,
      String contentType,
      byte[] content) {
    ClaimRequest claim = loadOpenClaim(actor, claimId);
    ClaimEvidence evidence =
        evidenceStorage.store(claim.id(), originalFilename, contentType, content);
    claims.insertEvidence(evidence);
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "CLAIM_EVIDENCE_ATTACHED",
            "CLAIM_REQUEST",
            claimId,
            null,
            Map.of(
                "evidenceId",
                evidence.id(),
                "contentHash",
                evidence.contentHash(),
                "sizeBytes",
                evidence.sizeBytes()),
            "追加认领材料元数据，材料原文不入审计"));
    return evidence;
  }

  /** 补证后重新提交，回到待审状态。 */
  @Transactional
  public ClaimRequest resubmit(ActorContext actor, String claimId, String statement) {
    ClaimRequest claim =
        claims.findClaimById(claimId).orElseThrow(() -> BusinessException.notFound("申请不存在"));
    requireApplicantOrAdmin(actor, claim);
    if (claim.status() != ClaimRequest.Status.NEED_MORE_EVIDENCE) {
      throw BusinessException.versionConflict("只有需要补证的申请可以重新提交");
    }
    int affected = claims.resubmitClaim(claimId, requireStatement(statement), claim.version());
    if (affected != 1) {
      throw BusinessException.versionConflict("申请状态已被其他操作改变，请刷新后重试");
    }
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor, "CLAIM_RESUBMITTED", "CLAIM_REQUEST", claimId, null, null, "补充材料后重新提交"));
    return claims.findClaimById(claimId).orElseThrow();
  }

  /**
   * 裁决认领申请。
   *
   * <p>结果是<b>返回值</b>而不是异常：竞争认领被判为争议时，状态已经合法地落到 DISPUTED，
   * 若用异常表达会让这次状态更新一起回滚，反而丢失了裁决痕迹。
   */
  @Transactional
  public DecisionResult decideClaim(
      ActorContext actor, String claimId, String decision, String reason) {
    Capabilities.require(actor, RoleNames.CAP_CLAIM_REVIEW);
    ClaimRequest claim =
        claims.findClaimById(claimId).orElseThrow(() -> BusinessException.notFound("申请不存在"));
    if (!claim.status().open()) {
      throw BusinessException.versionConflict("该申请已有结论，不能重复裁决");
    }
    String action = decision == null ? "" : decision.toUpperCase(java.util.Locale.ROOT);
    String normalizedReason = requireReason(reason);
    Instant now = Instant.now();

    switch (action) {
      case "NEED_MORE_EVIDENCE" -> {
        transition(claim, ClaimRequest.Status.NEED_MORE_EVIDENCE, actor, normalizedReason, now);
        audit.record(
            AuditRecorder.AuditEntry.of(
                actor,
                "CLAIM_NEED_MORE_EVIDENCE",
                "CLAIM_REQUEST",
                claimId,
                null,
                null,
                normalizedReason));
        return new DecisionResult("NEED_MORE_EVIDENCE", null, "已要求申请人补充材料");
      }
      case "REJECT" -> {
        transition(claim, ClaimRequest.Status.REJECTED, actor, normalizedReason, now);
        scholars.transitionClaimedStatus(
            claim.scholarId(),
            Scholar.ClaimedStatus.CLAIM_PENDING.name(),
            Scholar.ClaimedStatus.CLAIM_REJECTED.name());
        audit.record(
            AuditRecorder.AuditEntry.of(
                actor, "CLAIM_REJECTED", "CLAIM_REQUEST", claimId, null, null, normalizedReason));
        return new DecisionResult("REJECTED", null, "认领申请已驳回");
      }
      case "DISPUTE" -> {
        transition(claim, ClaimRequest.Status.DISPUTED, actor, normalizedReason, now);
        audit.record(
            AuditRecorder.AuditEntry.of(
                actor, "CLAIM_DISPUTED", "CLAIM_REQUEST", claimId, null, null, normalizedReason));
        return new DecisionResult("DISPUTED", null, "已标记争议，等待进一步证据");
      }
      case "APPROVE" -> {
        return approve(actor, claim, normalizedReason, now);
      }
      default ->
          throw BusinessException.invalidArgument(
              "decision 只支持 APPROVE / REJECT / NEED_MORE_EVIDENCE / DISPUTE");
    }
  }

  private DecisionResult approve(
      ActorContext actor, ClaimRequest claim, String reason, Instant now) {
    Optional<PortalAuthority> existing = authorities.findActiveByScholar(claim.scholarId());
    if (existing.isPresent()) {
      PortalAuthority holder = existing.get();
      if (holder.accountId().equals(claim.accountId())) {
        // 同一账号已是该门户管理者：批准是幂等的，不产生第二条授权
        transition(claim, ClaimRequest.Status.APPROVED, actor, reason, now);
        return new DecisionResult("APPROVED", holder.id(), "该账号已持有本门户管理权，本次批准不新增授权");
      }
      // 竞争认领：不转移原授权，保留申请并标记争议（AC-IMPL-FR05-03）
      transition(claim, ClaimRequest.Status.DISPUTED, actor, "竞争认领：" + reason, now);
      audit.record(
          AuditRecorder.AuditEntry.of(
              actor,
              "CLAIM_COMPETITION_DETECTED",
              "CLAIM_REQUEST",
              claim.id(),
              null,
              Map.of(
                  "incumbentAuthorityId",
                  holder.id(),
                  "incumbentAccountId",
                  holder.accountId(),
                  "detection",
                  "PRE_CHECK"),
              reason));
      return new DecisionResult("DISPUTED", holder.id(), "该门户已有生效授权，竞争认领不转移原授权，已标记争议");
    }

    String authorityId = Identifiers.next("pta");
    boolean granted =
        authorities.insertActive(
            PortalAuthority.active(
                authorityId, claim.accountId(), claim.scholarId(), claim.id(), now),
            actor.accountIdValue());
    if (!granted) {
      // 并发插入被部分唯一索引拦下：同样按竞争认领处理，不覆盖先到者。
      // 这条路径没有「先查到的持有者」可写进证据，但同样要留审计：只留下「申请变成 DISPUTED」
      // 这一个结果，事后无法区分「当时确有并发」与「有人手工改判」，也无法解释为什么没有第二条授权。
      transition(claim, ClaimRequest.Status.DISPUTED, actor, "并发竞争认领：" + reason, now);
      audit.record(
          AuditRecorder.AuditEntry.of(
              actor,
              "CLAIM_COMPETITION_DETECTED",
              "CLAIM_REQUEST",
              claim.id(),
              null,
              Map.of(
                  "incumbentAuthorityId", "",
                  "incumbentAccountId", "",
                  "detection", "UNIQUE_INDEX",
                  "constraint", "uk_iam_portal_authority_active"),
              reason));
      return new DecisionResult("DISPUTED", null, "并发竞争认领，未授予授权");
    }

    transition(claim, ClaimRequest.Status.APPROVED, actor, reason, now);
    scholars.transitionClaimedStatus(
        claim.scholarId(),
        Scholar.ClaimedStatus.CLAIM_PENDING.name(),
        Scholar.ClaimedStatus.CLAIMED.name());
    scholars.transitionClaimedStatus(
        claim.scholarId(),
        Scholar.ClaimedStatus.UNCLAIMED.name(),
        Scholar.ClaimedStatus.CLAIMED.name());

    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "PORTAL_AUTHORITY_GRANTED",
            "PORTAL_AUTHORITY",
            authorityId,
            null,
            Map.of(
                "accountId",
                claim.accountId(),
                "scholarId",
                claim.scholarId(),
                "claimId",
                claim.id()),
            reason));
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor, "CLAIM_APPROVED", "CLAIM_REQUEST", claim.id(), null, null, reason));

    outbox.append(
        OutboxEvent.builder(
                EventTypes.PORTAL_AUTHORITY_GRANTED, EventTypes.AGG_PORTAL_AUTHORITY, authorityId)
            .put("authorityId", authorityId)
            .put("accountId", claim.accountId())
            .put("scholarId", claim.scholarId())
            .put("claimId", claim.id()));

    log.info("认领批准 authorityId={} scholarId={}", authorityId, claim.scholarId());
    return new DecisionResult("APPROVED", authorityId, "已批准并授予门户管理权");
  }

  private void transition(
      ClaimRequest claim,
      ClaimRequest.Status status,
      ActorContext actor,
      String reason,
      Instant at) {
    int affected =
        claims.decideClaim(
            claim.id(), status.name(), actor.accountIdValue(), reason, at, claim.version());
    if (affected != 1) {
      throw BusinessException.versionConflict("申请状态已被其他操作改变，请刷新后重试");
    }
  }

  @Transactional(readOnly = true)
  public List<ClaimRequest> listMyClaims(ActorContext actor, int limit) {
    if (actor == null) {
      throw new BusinessException(ErrorCode.AUTH_REQUIRED, "请先登录");
    }
    return claims.listClaimsByAccount(actor.accountIdValue(), Math.min(Math.max(limit, 1), 100));
  }

  @Transactional(readOnly = true)
  public List<ClaimRequest> listClaimsForReview(ActorContext actor, PageRequest page) {
    Capabilities.require(actor, RoleNames.CAP_CLAIM_REVIEW);
    return claims.listOpenClaims(page.size(), page.offset());
  }

  @Transactional(readOnly = true)
  public long countOpenClaims() {
    return claims.countOpenClaims();
  }

  /** 材料下载：仅申请人与具备审核能力者可读，且必须经过核心服务鉴权代理（A08）。 */
  @Transactional(readOnly = true)
  public EvidenceDownload downloadEvidence(ActorContext actor, String evidenceId) {
    ClaimEvidence evidence =
        claims.findEvidence(evidenceId).orElseThrow(() -> BusinessException.notFound("材料不存在"));
    ClaimRequest claim =
        claims
            .findClaimById(evidence.claimRequestId())
            .orElseThrow(() -> BusinessException.notFound("材料所属申请不存在"));
    requireApplicantOrAdmin(actor, claim);
    byte[] content = evidenceStorage.read(evidence.fileRef());
    return new EvidenceDownload(evidence, content);
  }

  /**
   * 按申请读取材料元信息。
   *
   * <p>材料本体仍只能经 {@link #downloadEvidence} 鉴权下载；这里给的是「这份申请有哪些材料、
   * 分别是什么类型、多大、什么时候传的」，让审核者能在页面上判断该看哪一份，而不是拿到一堆
   * 没有标识的附件。申请人本人与具备审核能力者可见，权限判断复用下载路径的同一条规则。
   */
  @Transactional(readOnly = true)
  public List<ClaimEvidence> listEvidenceForClaim(ActorContext actor, String claimId) {
    ClaimRequest claim =
        claims.findClaimById(claimId).orElseThrow(() -> BusinessException.notFound("申请不存在"));
    requireApplicantOrAdmin(actor, claim);
    return claims.listEvidence(claimId);
  }

  /**
   * 申请人本人批量取材料元信息。
   *
   * <p>只返回本账号名下申请的材料：入参的 claimId 可能来自前端，不能因为「是页面传的」就采信。
   * 与下载接口同一条规则——不是本人、也没有审核能力，就拿不到这份材料的存在性。
   */
  @Transactional(readOnly = true)
  public Map<String, List<ClaimEvidence>> evidenceOfClaimsForApplicant(
      ActorContext actor, List<String> claimIds) {
    if (actor == null) {
      throw new BusinessException(ErrorCode.AUTH_REQUIRED, "请先登录");
    }
    Map<String, List<ClaimEvidence>> grouped = new LinkedHashMap<>();
    if (claimIds.isEmpty()) {
      return grouped;
    }
    List<ClaimRequest> mine = claims.listClaimsByAccount(actor.accountIdValue(), 100);
    java.util.Set<String> ownIds = new java.util.HashSet<>();
    for (ClaimRequest claim : mine) {
      ownIds.add(claim.id());
    }
    for (String claimId : claimIds) {
      if (ownIds.contains(claimId)) {
        grouped.put(claimId, claims.listEvidence(claimId));
      }
    }
    return grouped;
  }

  /**
   * 审核队列批量取材料。
   *
   * <p>审核页一次列出多条申请，逐条再发一次请求会让「这一页有没有材料」变成 N+1 次往返，
   * 且中途失败时页面会呈现一半有材料一半空白的假象。审核能力在这里显式校验一次。
   */
  @Transactional(readOnly = true)
  public Map<String, List<ClaimEvidence>> evidenceOfClaimsForReview(
      ActorContext actor, List<String> claimIds) {
    Capabilities.require(actor, RoleNames.CAP_CLAIM_REVIEW);
    Map<String, List<ClaimEvidence>> grouped = new LinkedHashMap<>();
    for (String claimId : claimIds) {
      grouped.put(claimId, claims.listEvidence(claimId));
    }
    return grouped;
  }

  // ===========================================================================
  // 门户信息变更
  // ===========================================================================

  /**
   * 提交门户信息变更。
   *
   * <p>影响身份/归属或来源事实的字段进入待审，不立即生效——待审版本绝不覆盖已公开版本
   * （AC-IMPL-FR06-01）；展示顺序与领域集合这类不影响事实的字段可直接生效。
   */
  @Transactional
  public PortalChangeRequest submitChange(
      ActorContext actor, String scholarId, String fieldName, String proposedValue) {
    Capabilities.require(actor, RoleNames.CAP_PORTAL_MAINTAIN);
    requirePortalAuthority(actor, scholarId);
    PortalChangeRequest.Field field = PortalChangeRequest.Field.of(fieldName);
    if (proposedValue == null || proposedValue.isBlank()) {
      throw BusinessException.invalidArgument("变更后的值不能为空");
    }
    if (proposedValue.length() > 2000) {
      throw BusinessException.invalidArgument("变更内容过长");
    }
    Scholar scholar =
        scholars.findById(scholarId).orElseThrow(() -> BusinessException.notFound("学者不存在"));

    if (field.requiresReview()) {
      Optional<PortalChangeRequest> open = changes.findOpenChange(scholarId, field.name());
      if (open.isPresent()) {
        return open.get();
      }
    }

    String currentValue = currentValueOf(scholar, field);
    boolean requiresReview = field.requiresReview();
    String changeId = Identifiers.next("pcr");
    PortalChangeRequest request =
        new PortalChangeRequest(
            changeId,
            actor.accountIdValue(),
            scholarId,
            field,
            currentValue,
            proposedValue,
            requiresReview,
            requiresReview
                ? PortalChangeRequest.Status.PENDING_REVIEW
                : PortalChangeRequest.Status.APPROVED,
            Instant.now(),
            requiresReview ? null : Instant.now(),
            requiresReview ? null : actor.accountIdValue(),
            requiresReview ? null : "无需审批的字段，直接生效",
            1L);
    changes.insert(request);

    if (!requiresReview) {
      applyField(scholarId, field, proposedValue, FieldProvenance.ValueLevel.MANUAL);
    }

    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            requiresReview ? "PORTAL_CHANGE_SUBMITTED" : "PORTAL_CHANGE_APPLIED",
            "PORTAL_CHANGE_REQUEST",
            changeId,
            Map.of("fieldName", field.name(), "currentValue", String.valueOf(currentValue)),
            Map.of("fieldName", field.name(), "proposedValue", proposedValue),
            requiresReview ? "提交待审变更" : "无需审批字段直接生效"));
    return request;
  }

  @Transactional
  public PortalChangeRequest decideChange(
      ActorContext actor, String changeId, boolean approve, String reason) {
    Capabilities.require(actor, RoleNames.CAP_CHANGE_REVIEW);
    PortalChangeRequest request =
        changes.findChangeById(changeId).orElseThrow(() -> BusinessException.notFound("变更申请不存在"));
    if (request.status() != PortalChangeRequest.Status.PENDING_REVIEW) {
      throw BusinessException.versionConflict("该变更已有结论，请刷新后重试");
    }
    String normalizedReason = requireReason(reason);
    PortalChangeRequest.Status target =
        approve ? PortalChangeRequest.Status.APPROVED : PortalChangeRequest.Status.REJECTED;
    int affected =
        changes.decideChange(
            changeId,
            target.name(),
            actor.accountIdValue(),
            normalizedReason,
            Instant.now(),
            request.version());
    if (affected != 1) {
      throw BusinessException.versionConflict("变更状态已被其他操作改变，请刷新后重试");
    }
    if (approve) {
      // 审核通过的值写为 REVIEWED 级：后续来源自动更新不得覆盖
      applyField(
          request.scholarId(),
          request.field(),
          request.proposedValue(),
          FieldProvenance.ValueLevel.REVIEWED);
    }
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            approve ? "PORTAL_CHANGE_APPROVED" : "PORTAL_CHANGE_REJECTED",
            "PORTAL_CHANGE_REQUEST",
            changeId,
            Map.of(
                "fieldName",
                request.field().name(),
                "currentValue",
                String.valueOf(request.currentValue())),
            Map.of(
                "fieldName",
                request.field().name(),
                "proposedValue",
                String.valueOf(request.proposedValue())),
            normalizedReason));
    return changes.findChangeById(changeId).orElseThrow();
  }

  @Transactional(readOnly = true)
  public List<PortalChangeRequest> listMyChanges(ActorContext actor, int limit) {
    if (actor == null) {
      throw new BusinessException(ErrorCode.AUTH_REQUIRED, "请先登录");
    }
    return changes.listChangesByAccount(actor.accountIdValue(), Math.min(Math.max(limit, 1), 100));
  }

  @Transactional(readOnly = true)
  public List<PortalChangeRequest> listPendingChanges(ActorContext actor, PageRequest page) {
    Capabilities.require(actor, RoleNames.CAP_CHANGE_REVIEW);
    return changes.listPendingChanges(page.size(), page.offset());
  }

  @Transactional(readOnly = true)
  public long countPendingChanges() {
    return changes.countPendingChanges();
  }

  /** 我的门户：当前账号持有的生效授权。 */
  @Transactional(readOnly = true)
  public List<PortalAuthority> myPortals(ActorContext actor) {
    if (actor == null) {
      throw new BusinessException(ErrorCode.AUTH_REQUIRED, "请先登录");
    }
    return authorities.listActiveByAccount(actor.accountIdValue());
  }

  /**
   * 对象级授权校验。
   *
   * <p>平台管理员可以管理来源与治理，但不能凭身份直接改写任意学者的门户：缺少对象级授权时
   * 一律拒绝，避免「管理员身份 = 全部数据可写」（A05）。
   */
  public void requirePortalAuthority(ActorContext actor, String scholarId) {
    if (actor == null) {
      throw new BusinessException(ErrorCode.AUTH_REQUIRED, "请先登录");
    }
    if (authorities.findActiveByAccountAndScholar(actor.accountIdValue(), scholarId).isEmpty()) {
      throw BusinessException.accessDenied("当前账号不是该学者门户的管理者");
    }
  }

  private void applyField(
      String scholarId,
      PortalChangeRequest.Field field,
      String value,
      FieldProvenance.ValueLevel level) {
    switch (field) {
      case RESEARCH_SUMMARY -> {
        scholars.updateResearchSummary(scholarId, value);
        provenance.upsertReviewed("SCHOLAR", scholarId, "researchSummary", level.name(), value);
      }
      case ORGANIZATION_NAME -> {
        scholars.updateOrganizationName(scholarId, value);
        provenance.upsertReviewed("SCHOLAR", scholarId, "organizationName", level.name(), value);
      }
      case DISPLAY_ORDER, TOPIC_SET ->
          provenance.upsertReviewed("SCHOLAR", scholarId, field.name(), level.name(), value);
    }
  }

  private String currentValueOf(Scholar scholar, PortalChangeRequest.Field field) {
    return switch (field) {
      case RESEARCH_SUMMARY -> scholar.researchSummary();
      case ORGANIZATION_NAME -> scholar.organizationName();
      case DISPLAY_ORDER, TOPIC_SET -> null;
    };
  }

  private ClaimRequest loadOpenClaim(ActorContext actor, String claimId) {
    ClaimRequest claim =
        claims.findClaimById(claimId).orElseThrow(() -> BusinessException.notFound("申请不存在"));
    requireApplicantOrAdmin(actor, claim);
    if (!claim.status().open()) {
      throw BusinessException.versionConflict("该申请已有结论，不能再追加材料");
    }
    return claim;
  }

  private void requireApplicantOrAdmin(ActorContext actor, ClaimRequest claim) {
    if (actor == null) {
      throw new BusinessException(ErrorCode.AUTH_REQUIRED, "请先登录");
    }
    boolean applicant = claim.accountId().equals(actor.accountIdValue());
    boolean reviewer = Capabilities.has(actor, RoleNames.CAP_CLAIM_REVIEW);
    if (!applicant && !reviewer) {
      // 非本人且无权审核者不得读取认领材料（A08）
      throw BusinessException.accessDenied("无权查看该认领申请或材料");
    }
  }

  private String requireStatement(String statement) {
    String text = statement == null ? "" : statement.trim();
    if (text.isEmpty()) {
      throw BusinessException.invalidArgument("认领说明不能为空");
    }
    if (text.length() > MAX_STATEMENT_LENGTH) {
      throw BusinessException.invalidArgument("认领说明不得超过 " + MAX_STATEMENT_LENGTH + " 字");
    }
    return text;
  }

  private String requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw BusinessException.invalidArgument("必须说明理由");
    }
    return reason.length() > 500 ? reason.substring(0, 500) : reason;
  }

  /**
   * 裁决结果。
   *
   * @param status 终态或需要的下一步
   * @param authorityId 批准时产生的授权标识
   */
  public record DecisionResult(String status, String authorityId, String message) {

    public Map<String, Object> toView() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("status", status);
      view.put("authorityId", authorityId);
      view.put("message", message);
      return view;
    }
  }

  /** 材料下载载荷。 */
  public record EvidenceDownload(ClaimEvidence evidence, byte[] content) {}
}
