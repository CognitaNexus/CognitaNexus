package com.cognitanexus.core.governance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 自动发布判定（消歧与去重的前置门）的行为约束。
 *
 * <p>依据：FR08（数据治理）、BR-IMP-08（自动更新不得覆盖人工审核值 / 相似标题不得直接合并）、
 * 架构 V0.2 §6.2。
 *
 * <p>本类只回答「能否不经人工直接发布」。测试的重点不是「什么情况下放过」，而是
 * <b>什么情况下必须拦</b>——拦不住的代价是把未经确认或冲突的实体当成已确认结论对外展示。
 */
class AutoPublishPolicyTest {

  private static AutoPublishPolicy.Inputs inputs(
      boolean hasIdentifier, boolean conflicted, boolean similarCandidate, String nature) {
    return new AutoPublishPolicy.Inputs(hasIdentifier, conflicted, similarCandidate, nature);
  }

  /** 全部条件都满足的基线输入，各用例只改动一个维度。 */
  private static AutoPublishPolicy.Inputs clean() {
    return inputs(true, false, false, "REAL");
  }

  @Test
  @DisplayName("四个条件全部满足才允许自动发布")
  void allConditionsMet() {
    AutoPublishPolicy.Decision decision = AutoPublishPolicy.evaluate(clean());
    assertThat(decision.publishable()).isTrue();
    assertThat(decision.reasons()).isEmpty();
  }

  @Test
  @DisplayName("无稳定标识必须拦：没有 DOI/申请号/项目号就没有跨源同一性的可靠依据")
  void missingStableIdentifierBlocks() {
    AutoPublishPolicy.Decision decision =
        AutoPublishPolicy.evaluate(inputs(false, false, false, "REAL"));
    assertThat(decision.publishable()).isFalse();
    assertThat(decision.reasons()).hasSize(1);
    assertThat(decision.reasons().get(0)).contains("稳定标识");
  }

  @Test
  @DisplayName("标识被其他实体占用必须拦：标识冲突是同一性冲突，只能人工裁定")
  void identifierConflictBlocks() {
    AutoPublishPolicy.Decision decision =
        AutoPublishPolicy.evaluate(inputs(true, true, false, "REAL"));
    assertThat(decision.publishable()).isFalse();
    assertThat(decision.reasons().get(0)).contains("占用");
  }

  @Test
  @DisplayName("存在相似标题候选必须拦：相似即存疑，存疑不得自动合并（BR-IMP-02/03）")
  void similarCandidateBlocks() {
    AutoPublishPolicy.Decision decision =
        AutoPublishPolicy.evaluate(inputs(true, false, true, "REAL"));
    assertThat(decision.publishable()).isFalse();
    assertThat(decision.reasons().get(0)).contains("相似标题");
  }

  @Test
  @DisplayName("非 REAL 数据性质必须拦：模拟数据不得冒充真实收录")
  void nonRealNatureBlocks() {
    AutoPublishPolicy.Decision simulated =
        AutoPublishPolicy.evaluate(inputs(true, false, false, "SIMULATED"));
    assertThat(simulated.publishable()).isFalse();
    assertThat(simulated.reasons().get(0)).contains("SIMULATED");

    AutoPublishPolicy.Decision mixed =
        AutoPublishPolicy.evaluate(inputs(true, false, false, "MIXED"));
    assertThat(mixed.publishable()).isFalse();
    assertThat(mixed.reasons().get(0)).contains("MIXED");
  }

  @Test
  @DisplayName("多个条件不满足时逐条给出理由，不合并成一句话——审核人需要知道分别缺什么")
  void allReasonsCollected() {
    AutoPublishPolicy.Decision decision =
        AutoPublishPolicy.evaluate(inputs(false, true, true, "SIMULATED"));
    assertThat(decision.publishable()).isFalse();
    assertThat(decision.reasons()).hasSize(4);
    assertThat(decision.reasons())
        .anyMatch(reason -> reason.contains("稳定标识"))
        .anyMatch(reason -> reason.contains("占用"))
        .anyMatch(reason -> reason.contains("相似标题"))
        .anyMatch(reason -> reason.contains("SIMULATED"));
  }

  @Test
  @DisplayName("「不可自动发布」不等于「无效」：决策里只有可否自动发布，不携带作废语义")
  void blockedIsNotInvalid() {
    AutoPublishPolicy.Decision decision =
        AutoPublishPolicy.evaluate(inputs(false, false, false, "REAL"));
    assertThat(decision.publishable()).isFalse();
    // 理由列表必须非空，调用方据此把实体留在 PENDING_GOVERNANCE 而不是丢弃。
    assertThat(decision.reasons()).isNotEmpty();
  }

  @Test
  @DisplayName("理由列表不可变：判定结果会被多线程读取，调用方不得原地修改")
  void reasonsImmutable() {
    AutoPublishPolicy.Decision decision =
        AutoPublishPolicy.evaluate(inputs(false, false, false, "REAL"));
    assertThat(decision.reasons()).isUnmodifiable();
  }

  @Test
  @DisplayName("reasons 传 null 时归一为空列表，不抛异常")
  void nullReasonsNormalized() {
    AutoPublishPolicy.Decision decision = new AutoPublishPolicy.Decision(true, null);
    assertThat(decision.reasons()).isEmpty();
  }

  @Test
  @DisplayName("allow() 是「可发布且无理由」的唯一表达，避免各处构造不一致")
  void allowFactory() {
    AutoPublishPolicy.Decision decision = AutoPublishPolicy.Decision.allow();
    assertThat(decision.publishable()).isTrue();
    assertThat(decision.reasons()).isEqualTo(List.of());
  }
}
