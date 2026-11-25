package com.cognitanexus.query.graph.domain;

import java.util.Set;

/**
 * 一条「学者—成果」参与关系（{@code AUTHORED} 边）。
 *
 * <p>只有 {@link #QUALIFIED_STATES} 中的关系状态允许进入图投影。待核验（PENDING_VERIFY）、争议
 * （DISPUTED）与已排除（EXCLUDED）的参与关系不建边、也不建合作边——它们不是确定事实
 * （BR-IMP-08、架构 §9.3）。这不代表「先放着以后再说」：投影侧必须显式过滤，图存储侧对非合格
 * 状态直接拒绝写入，避免某次批量导入把未确认作者悄悄变成确定合作者。
 */
public record GraphAuthorship(String scholarId, String relationState, Integer authorOrder) {

  /** 参与确定合作关系的唯一两种状态，与 {@code qry_contribution.relation_state} 口径一致。 */
  public static final Set<String> QUALIFIED_STATES = Set.of("AUTO", "SELF_CONFIRMED");

  public GraphAuthorship {
    scholarId = GraphIdentifiers.require(scholarId, "scholarId");
    if (relationState == null || !QUALIFIED_STATES.contains(relationState)) {
      throw new IllegalArgumentException(
          "图投影不接受非合格参与关系：" + relationState + "（只允许 " + QUALIFIED_STATES + "）");
    }
    if (authorOrder != null && authorOrder < 1) {
      throw new IllegalArgumentException("authorOrder 若存在必须 >= 1，实际为 " + authorOrder);
    }
  }
}
