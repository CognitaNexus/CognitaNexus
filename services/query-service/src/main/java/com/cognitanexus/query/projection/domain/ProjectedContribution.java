package com.cognitanexus.query.projection.domain;

import com.cognitanexus.sharedkernel.RelationState;
import java.util.List;

/**
 * 参与关系读投影。
 *
 * <p>依据：架构 V0.2 §5.4、§9.3、BR-IMP-08。organizations 是<b>成果发生时</b>的机构归属，
 * 与学者档案上的当前任职机构是两件事：用当前机构回填历史成果的机构统计，会让每次任职变动
 * 改写历史统计，而这类改写无法从结果上看出。
 *
 * @param relationState 只有 AUTO 与 SELF_CONFIRMED 参与确定合作边
 */
public record ProjectedContribution(
    String achievementId,
    String scholarId,
    String scholarName,
    String role,
    Integer authorOrder,
    String relationState,
    List<String> organizations) {

  public ProjectedContribution {
    organizations = organizations == null ? List.of() : List.copyOf(organizations);
  }

  /** 是否为可用于确定合作边的合格关系。未知状态一律视为不合格。 */
  public boolean qualifiedForGraph() {
    for (RelationState state : RelationState.values()) {
      if (state.name().equals(relationState)) {
        return state.qualified();
      }
    }
    return false;
  }
}
