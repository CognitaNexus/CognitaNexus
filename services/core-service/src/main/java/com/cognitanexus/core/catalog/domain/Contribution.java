package com.cognitanexus.core.catalog.domain;

import com.cognitanexus.sharedkernel.RelationState;
import java.time.Instant;

/**
 * 学者对成果的参与关系。
 *
 * <p>依据：架构 V0.2 §5.3、§5.4。一个学者对同一成果只有一条参与关系；多角色在 role 子结构表达，
 * 不为每个角色重复计数，否则统计会把同一份贡献算多次。
 *
 * <p>关系状态独立存储：认领通过不会使该学者的全部成果变为已确认（SRS 5.2）；
 * 只有 AUTO 与 SELF_CONFIRMED 参与确定合作边（BR-IMP-08）。
 *
 * @param authorOrder 作者序，从 1 开始；缺失时为 null，不用 0 冒充「第一作者」
 */
public record Contribution(
    String id,
    String scholarId,
    String achievementId,
    String role,
    Integer authorOrder,
    RelationState relationState,
    String sourceRecordId,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  /** 是否可作为确定合作边的一部分。 */
  public boolean qualifiedForGraph() {
    return relationState.qualified();
  }

  public enum Role {
    AUTHOR,
    INVENTOR,
    LEADER,
    PARTICIPANT,
    AWARDEE;

    public static Role of(String raw) {
      for (Role value : values()) {
        if (value.name().equals(raw)) {
          return value;
        }
      }
      throw new IllegalArgumentException("未知参与角色: " + raw);
    }
  }
}
