package com.cognitanexus.core.platform.audit;

import com.cognitanexus.core.platform.persistence.JsonCodec;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import org.springframework.stereotype.Component;

/**
 * 审计端口的事务内实现。
 *
 * <p>依据：架构 V0.2 §6.1。审计与业务同库同事务，不需要分布式提交；事务回滚时审计一起回滚，
 * 因此不存在「业务失败但留下成功审计」的假痕迹。
 */
@Component
public class JdbcAuditRecorder implements AuditRecorder {

  private final AuditMapper auditMapper;
  private final JsonCodec json;

  public JdbcAuditRecorder(AuditMapper auditMapper, JsonCodec json) {
    this.auditMapper = auditMapper;
    this.json = json;
  }

  @Override
  public void record(AuditEntry entry) {
    auditMapper.insert(
        Identifiers.next("aud"),
        entry.actorAccountId(),
        entry.actorUsername(),
        entry.action(),
        entry.targetType(),
        entry.targetId(),
        json.write(entry.beforeValue()),
        json.write(entry.afterValue()),
        entry.reason(),
        TraceIdHolder.current());
  }
}
