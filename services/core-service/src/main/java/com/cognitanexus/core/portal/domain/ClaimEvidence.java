package com.cognitanexus.core.portal.domain;

import java.time.Instant;

/**
 * 认领材料元数据。
 *
 * <p>依据：架构 V0.2 §10.4。文件本体保存在受控卷，文件名为随机内部标识，不使用上传文件名拼路径，
 * 也不映射为可公开访问的目录；数据库只保存元数据与内容哈希。
 *
 * <p>真实材料的保留期限与删除授权尚未确认（OQ03），因此当前只允许限定类型与大小，不宣称已满足
 * 合规保留要求。
 */
public record ClaimEvidence(
    String id,
    String claimRequestId,
    String fileRef,
    String contentHash,
    String contentType,
    long sizeBytes,
    Instant createdAt) {

  /** 单文件上限 10 MiB。 */
  public static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;
}
