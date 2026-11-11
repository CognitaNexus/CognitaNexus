package com.cognitanexus.ingestion.collect.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.ingestion.collect.domain.CollectModels.SourceRunConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 来源业务更新周期（WB-13）。
 *
 * <p>这个类型回答的问题只有一个：<b>这个来源现在该不该按业务节奏跑一次</b>。它刻意不读调度间隔、
 * 不读任务退避——那两件事分别属于服务实例参数与任务自身状态。用例要守住的也是这条边界：
 * 未设定周期必须等于「不改变既有行为」，否则一次迁移会把所有存量来源的采集停掉。
 */
class SourceRunConfigUpdatePlanTest {

  private static SourceRunConfig config(Integer newRecord, Integer fieldRefresh, Instant nextDue) {
    return new SourceRunConfig(
        "src_1",
        "crossref",
        "Crossref",
        AdapterKind.CROSSREF,
        "https://api.crossref.org",
        "graph",
        20,
        List.of("title"),
        "v1",
        "APPROVED",
        null,
        60,
        true,
        1,
        newRecord,
        fieldRefresh,
        "来源文档声明季度更新",
        nextDue);
  }

  @Test
  @DisplayName("未设定周期：不算「有更新计划」，且此刻就到期（不改变既有行为）")
  void noPlanMeansDueAndNotPlanned() {
    SourceRunConfig cfg = config(null, null, null);
    assertThat(cfg.hasUpdatePlan()).isFalse();
    assertThat(cfg.dueAt(Instant.now())).as("让未设定周期反而挡住任务，会把存量来源的采集停掉——那不是保守，是故障").isTrue();
    assertThat(cfg.nextDueAfter(Instant.now())).isNull();
  }

  @Test
  @DisplayName("已设定周期：未到点不跑，到点才跑")
  void planGatesByDueTime() {
    Instant now = Instant.parse("2026-10-17T12:00:00Z");
    assertThat(config(1440, null, now.plusSeconds(60)).dueAt(now)).isFalse();
    assertThat(config(1440, null, now.minusSeconds(1)).dueAt(now)).isTrue();
    assertThat(config(1440, null, now).dueAt(now)).as("正好到期时应放行").isTrue();
  }

  @Test
  @DisplayName("下次到期时间取两个周期里较近的那个")
  void nextDueUsesTheNearerInterval() {
    Instant from = Instant.parse("2026-10-17T12:00:00Z");
    assertThat(config(1440, 60, from).nextDueAfter(from)).isEqualTo(from.plusSeconds(3600));
    assertThat(config(60, 1440, from).nextDueAfter(from)).isEqualTo(from.plusSeconds(3600));
    assertThat(config(null, 120, from).nextDueAfter(from)).isEqualTo(from.plusSeconds(7200));
    assertThat(config(20160, null, from).nextDueAfter(from))
        .as("两周：分钟换算不能退化成小时或天")
        .isEqualTo(from.plusSeconds(14L * 24 * 3600));
  }

  @Test
  @DisplayName("有更新计划与「到没到期」是两件事：计划可以存在而此刻未到期")
  void plannedButNotYetDue() {
    Instant now = Instant.parse("2026-10-17T12:00:00Z");
    SourceRunConfig cfg = config(1440, null, now.plusSeconds(600));
    assertThat(cfg.hasUpdatePlan()).isTrue();
    assertThat(cfg.dueAt(now)).isFalse();
  }
}
