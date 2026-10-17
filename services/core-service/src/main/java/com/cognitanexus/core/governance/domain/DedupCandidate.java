package com.cognitanexus.core.governance.domain;

import java.time.Instant;
import java.util.Map;

/**
 * 去重候选。
 *
 * <p>依据：架构 V0.2 §5.3、AC-IMPL-FR09-02。仅标题相似 <b>不得</b>自动合并，只产生候选；
 * 判定还须比对年份、参与者与类型。候选与规范实体分开存储，正是为了让「疑似」不能直接变成「已合并」。
 *
 * @param evidence 生成候选的证据快照（相似度、比对到的字段与结论），保留在库中以便复核
 * @param rebasedToId 改判后承接本条候选问题的候选标识；仅当 {@link Status#REBASED} 时非空
 */
public record DedupCandidate(
    String id,
    String achievementType,
    String leftAchievementId,
    String rightAchievementId,
    Map<String, Object> evidence,
    Status status,
    Instant createdAt,
    Instant resolvedAt,
    String resolvedBy,
    String resolutionReason,
    String rebasedToId) {

  /**
   * 候选状态。
   *
   * <p>{@link #MERGED} 与 {@link #REJECTED} 是「比较过了」的两种结论；
   * {@link #REBASED} 是「还没比较，换了一条候选来问」——它存在的原因是合并会改变候选的端点，
   * 端点被并走的候选既不能再合并、也不该被驳回（驳回等于宣称比较过）。
   */
  public enum Status {
    OPEN,
    MERGED,
    REJECTED,
    REBASED;

    public static Status of(String raw) {
      for (Status value : values()) {
        if (value.name().equals(raw)) {
          return value;
        }
      }
      throw new IllegalArgumentException("未知候选状态: " + raw);
    }

    /** 是否已离开待办：工作台的待办一律只取 {@code OPEN}。 */
    public boolean terminal() {
      return this != OPEN;
    }
  }

  /** 改判的承接候选；未改判时为 null。 */
  public String rebasedToIdOrNull() {
    return status == Status.REBASED ? rebasedToId : null;
  }
}
