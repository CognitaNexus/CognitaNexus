package com.cognitanexus.core.platform.idempotency;

import com.cognitanexus.core.platform.util.Fingerprints;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理幂等守卫。
 *
 * <p>依据：架构 V0.2 §10.4、编码规范第 6 节。敏感写请求在本地事务内预留
 * <code>actorId + operation + idempotencyKey</code>：
 *
 * <ul>
 *   <li>首次占用 → 继续执行业务，事务提交后记录与业务结果同时可见；
 *   <li>重复且请求哈希一致 → 返回原结果，不重复执行业务；
 *   <li>重复但请求哈希不同 → 409，说明客户端复用了键却改了内容。
 * </ul>
 *
 * <p>过期窗口默认为 24 小时，长于约定的客户端重试窗口；过期后按业务 ID 查询状态。
 */
@Component
public class IdempotencyGuard {

  /** 幂等记录默认存活时长（小时）。 */
  public static final int DEFAULT_TTL_HOURS = 24;

  private final IdempotencyMapper mapper;

  public IdempotencyGuard(IdempotencyMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * 预留或复用幂等键。
   *
   * <p>必须在调用方事务内执行（MANDATORY）：否则记录会先于业务提交，业务回滚后客户端重试被误判为重放。
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Reservation reserve(
      String actorId, String operation, String idempotencyKey, String canonicalRequest) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return new Reservation(Status.NOT_PROVIDED, null);
    }
    String requestHash = Fingerprints.sha256Hex(canonicalRequest == null ? "" : canonicalRequest);
    int inserted =
        mapper.insert(
            Identifiers.next("idem"),
            actorId,
            operation,
            idempotencyKey,
            requestHash,
            null,
            DEFAULT_TTL_HOURS);
    if (inserted == 1) {
      return new Reservation(Status.NEW, null);
    }
    IdempotencyMapper.Row existing =
        mapper
            .find(actorId, operation, idempotencyKey)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "幂等记录状态不一致"));
    if (!existing.requestHash().equals(requestHash)) {
      throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "同一幂等键已用于不同内容，请更换键或核对请求");
    }
    return new Reservation(Status.REPLAY, existing.resultReference());
  }

  /** 业务结果产生后回填引用；仍在调用方事务内。 */
  @Transactional(propagation = Propagation.MANDATORY)
  public void attachResult(
      String actorId, String operation, String idempotencyKey, String resultReference) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return;
    }
    mapper.attachResult(actorId, operation, idempotencyKey, resultReference);
  }

  public Optional<String> existingResult(String actorId, String operation, String idempotencyKey) {
    return mapper
        .find(actorId, operation, idempotencyKey)
        .map(IdempotencyMapper.Row::resultReference);
  }

  public enum Status {
    /** 本次是首次占用，需要继续执行业务。 */
    NEW,
    /** 已有同内容记录，直接返回原结果。 */
    REPLAY,
    /** 客户端未提供幂等键，按普通请求处理。 */
    NOT_PROVIDED
  }

  /**
   * 预留结果。
   *
   * @param resultReference 重放时可用于定位原对象的业务标识
   */
  public record Reservation(Status status, String resultReference) {

    public boolean shouldExecute() {
      return status == Status.NEW || status == Status.NOT_PROVIDED;
    }

    public boolean isReplay() {
      return status == Status.REPLAY;
    }
  }
}
