package com.cognitanexus.ingestion.collect.domain;

/**
 * 交接状态。
 *
 * <p>依据：架构 V0.2 §6.2。只在 core 明确 ACK 之后才置为 HANDED_OFF 并推进检查点；
 * FAILED 表示已被 core 明确拒绝或重试超过上限，需要人工处理，而不是继续自动重试。
 */
public enum HandoffState {
  STAGED,
  HANDED_OFF,
  FAILED;
}
