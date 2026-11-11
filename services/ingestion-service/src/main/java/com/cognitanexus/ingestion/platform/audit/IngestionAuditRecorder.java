package com.cognitanexus.ingestion.platform.audit;

import com.cognitanexus.ingestion.platform.persistence.JsonCodec;
import com.cognitanexus.ingestion.platform.security.ActorContext;
import com.cognitanexus.ingestion.platform.util.Identifiers;
import com.cognitanexus.ingestion.platform.web.TraceIdHolder;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 采集管理操作审计。
 *
 * <p>依据：架构 V0.2 §5.4、§4.2。审计与业务写入同事务（{@code MANDATORY}）：若允许审计在独立
 * 事务中提交，一次回滚的业务操作会留下「已发生」的假记录；反之若审计失败能静默忽略，就会出现
 * 有变更没留痕。两者都会让事后追责失去意义。
 *
 * <p>调用方必须已开启事务。这要求审计调用点与事务方法在同一事务边界内——如果写在 {@code
 * @Transactional} 方法之外，会直接抛异常而不是静默丢弃，问题在开发阶段就会暴露。
 */
@Component
public class IngestionAuditRecorder {

  private final AuditMapper mapper;
  private final JsonCodec json;

  public IngestionAuditRecorder(AuditMapper mapper, JsonCodec json) {
    this.mapper = mapper;
    this.json = json;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      ActorContext actor,
      String action,
      String targetType,
      String targetId,
      Map<String, Object> beforeValue,
      Map<String, Object> afterValue,
      String reason) {
    mapper.insert(
        Identifiers.next("iaud"),
        actor == null ? null : actor.accountId(),
        actor == null ? "SYSTEM" : actor.username(),
        action,
        targetType,
        targetId,
        beforeValue == null ? null : json.write(beforeValue),
        afterValue == null ? null : json.write(afterValue),
        reason,
        TraceIdHolder.current());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void recordSystem(String action, String targetType, String targetId, String reason) {
    record(null, action, targetType, targetId, null, null, reason);
  }
}
