package com.cognitanexus.core.platform.idempotency;

import java.time.Instant;

/**
 * 幂等记录。
 *
 * <p>依据：架构 V0.2 §10.4。记录与业务结果同事务提交，不只存 Redis；相同键同内容返回原结果，
 * 相同键不同内容返回 409。
 *
 * @param requestHash 规范化后的请求哈希。同一键只允许对应一个哈希；不匹配即冲突。
 */
public record IdempotencyRecord(
    String id,
    String actorId,
    String operation,
    String idempotencyKey,
    String requestHash,
    String resultReference,
    Instant createdAt,
    Instant expiresAt) {}
