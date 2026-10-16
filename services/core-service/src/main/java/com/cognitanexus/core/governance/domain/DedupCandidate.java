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
    String resolutionReason) {

  public enum Status {
    OPEN,
    MERGED,
    REJECTED;

    public static Status of(String raw) {
      for (Status value : values()) {
        if (value.name().equals(raw)) {
          return value;
        }
      }
      throw new IllegalArgumentException("未知候选状态: " + raw);
    }
  }
}
