package com.cognitanexus.core.platform.audit;

import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.Capabilities;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.util.PageRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计查询。
 *
 * <p>依据：AC-IMPL-FR14-02、架构 §12.4。审计读取本身也是受保护操作，需要审计读取能力；
 * 返回内容只含操作者、对象、时间、前后值与理由，不含材料原文与凭据。
 */
@Service
public class AuditQueryService {

  private final AuditMapper mapper;

  public AuditQueryService(AuditMapper mapper) {
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> search(
      ActorContext actor,
      String targetType,
      String targetId,
      String actorAccountId,
      PageRequest page) {
    Capabilities.require(actor, RoleNames.CAP_AUDIT_READ);
    return mapper.search(targetType, targetId, actorAccountId, page.size(), page.offset()).stream()
        .map(this::toView)
        .toList();
  }

  @Transactional(readOnly = true)
  public long count(ActorContext actor, String targetType, String targetId, String actorAccountId) {
    Capabilities.require(actor, RoleNames.CAP_AUDIT_READ);
    return mapper.count(targetType, targetId, actorAccountId);
  }

  private Map<String, Object> toView(AuditMapper.AuditRow row) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", row.id());
    view.put("actorAccountId", row.actorAccountId());
    view.put("actorUsername", row.actorUsername());
    view.put("action", row.action());
    view.put("targetType", row.targetType());
    view.put("targetId", row.targetId());
    view.put("beforeValue", row.beforeValue());
    view.put("afterValue", row.afterValue());
    view.put("reason", row.reason());
    view.put("occurredAt", row.occurredAt());
    view.put("traceId", row.traceId());
    return view;
  }
}
