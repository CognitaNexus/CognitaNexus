package com.cognitanexus.core.catalog.domain;

import com.cognitanexus.sharedkernel.DataNature;
import java.time.Instant;

/**
 * 学者档案。
 *
 * <p>依据：架构 V0.2 §5.4、SRS 5.4。学者档案可以先于账号存在；认领通过只授予门户管理权，
 * 不等于身份证明或成果真实性背书，因此 {@code claimedStatus} 与授权是两件事。
 *
 * @param organizationName 当前任职机构。成果发生时的机构归属另存于参与关系，不互相覆盖
 */
public record Scholar(
    String id,
    String displayName,
    String nameNorm,
    DataNature dataNature,
    ClaimedStatus claimedStatus,
    String organizationName,
    String researchSummary,
    String mergedIntoId,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public boolean merged() {
    return mergedIntoId != null;
  }

  public Scholar withClaimedStatus(ClaimedStatus status) {
    return new Scholar(
        id,
        displayName,
        nameNorm,
        dataNature,
        status,
        organizationName,
        researchSummary,
        mergedIntoId,
        version,
        createdAt,
        updatedAt);
  }

  /** 认领状态。 */
  public enum ClaimedStatus {
    UNCLAIMED,
    CLAIM_PENDING,
    CLAIMED,
    CLAIM_REJECTED;

    public static ClaimedStatus of(String raw) {
      for (ClaimedStatus value : values()) {
        if (value.name().equals(raw)) {
          return value;
        }
      }
      throw new IllegalArgumentException("未知认领状态: " + raw);
    }
  }
}
