package com.cognitanexus.core.governance.application;

import com.cognitanexus.core.catalog.application.AdminCatalogService;
import com.cognitanexus.core.platform.security.ActorContext;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 候选裁决的编排：先裁决，再重评估受影响成果的发布资格。
 *
 * <p>为什么单独一层：裁决属于治理模块，发布属于目录模块，而目录模块已经依赖治理模块
 * （待治理成果的发布要读候选）。反过来在治理里调用目录会形成循环依赖，所以把「裁决 + 重评估」
 * 这个跨模块的动作提到编排层，由它同时依赖两者。
 *
 * <p><b>两步刻意不放在同一个事务里。</b>裁决是一个独立成立的结论——「这两条不是同一条」并不
 * 因为随后能不能发布而改变；重评估只是顺带解除一个成因。合成一个大事务会带来两个坏处：
 * 批量裁决 200 条时整个请求持锁时间成倍增长；重评估里任何一个意外都会把已经做出的裁决一起
 * 回滚掉，而裁决是靠人现场判断产出的，不该被下游的发布判定牵连。反过来，重评估是可重跑的：
 * 少发布一次只是晚一点进公开目录，不会产生错误的公开——这是两者代价不对称的方向。
 */
@Service
public class GovernanceResolutionWorkflow {

  private final GovernanceService governance;
  private final AdminCatalogService catalog;

  public GovernanceResolutionWorkflow(GovernanceService governance, AdminCatalogService catalog) {
    this.governance = governance;
    this.catalog = catalog;
  }

  /**
   * 单条裁决 + 重评估。
   *
   * @param action MERGE 或 REJECT
   */
  public Outcome resolve(
      String candidateId, String action, String survivorId, String reason, ActorContext actor) {
    List<String> affected =
        governance.resolveCandidate(candidateId, action, survivorId, reason, actor);
    List<String> released =
        catalog.recheckReleaseAfterResolution(affected, actor, noteOf(candidateId, reason));
    return new Outcome(candidateId, affected.size(), released);
  }

  /**
   * 批量驳回 + 对全部受影响成果重评估一次。
   *
   * <p>重评估在整批裁决之后统一做一遍，而不是每条候选之后各做一遍：同一成果可能同时出现在
   * 多条候选里，逐条重评估会让它在还没被清空候选时被反复读取，既浪费又读到的中间状态没有意义。
   */
  public BatchOutcome batchReject(List<String> candidateIds, String reason, ActorContext actor) {
    GovernanceService.BatchResolveOutcome resolved =
        governance.batchReject(candidateIds, reason, actor);
    List<String> released =
        catalog.recheckReleaseAfterResolution(
            resolved.affectedAchievementIds(), actor, noteOf("批量驳回", reason));
    return new BatchOutcome(
        resolved.requested(), resolved.resolved(), resolved.skipped(), released);
  }

  /**
   * 按相似度档批量驳回 + 重评估。
   *
   * <p>与 {@link #batchReject} 的唯一区别是「候选从哪来」：那边由调用方给标识，这边按分档阈值取
   * 一批。后续的重评估必须完全一致——裁决解除的是「待治理」的成因之一，成果能否随之公开仍由
   * 重评估按既定条件判，不由批量入口直接决定。
   */
  public BatchOutcome batchRejectByBucket(
      String bucket, int limit, String reason, ActorContext actor) {
    GovernanceService.BatchResolveOutcome resolved =
        governance.batchRejectByBucket(bucket, limit, reason, actor);
    List<String> released =
        catalog.recheckReleaseAfterResolution(
            resolved.affectedAchievementIds(), actor, noteOf("按档批量驳回", reason));
    return new BatchOutcome(
        resolved.requested(), resolved.resolved(), resolved.skipped(), released);
  }

  private static String noteOf(String source, String reason) {
    String base = reason == null || reason.isBlank() ? "候选裁决" : reason;
    return source + "后重评估发布：" + base;
  }

  /**
   * 候选改判 + 重评估。
   *
   * <p>改判改变了「哪些成果处于待治理」：原候选关闭、承接候选登记，两端集合都会变，因此重评估必须
   * 覆盖原两端与最终两端，而不是只覆盖其中一边。理由与 {@link #resolve} 相同——改判本身是一个独立
   * 成立的结论（这个问题该由谁答），不该因为随后能不能发布而回滚。
   */
  public RebaseOutcome rebase(String candidateId, String reason, ActorContext actor) {
    GovernanceService.RebaseOutcome outcome =
        governance.rebaseCandidate(candidateId, reason, actor);
    List<String> released =
        catalog.recheckReleaseAfterResolution(
            outcome.affectedAchievementIds(), actor, noteOf("候选改判", reason));
    return new RebaseOutcome(
        outcome.candidateId(),
        outcome.status(),
        outcome.rebasedToId(),
        outcome.targetExisted(),
        outcome.finalLeftId(),
        outcome.finalRightId(),
        released);
  }

  /**
   * 改判结果。
   *
   * @param status 原候选的最终状态：{@code MERGED}（两端归并到同一实体）或 {@code REBASED}
   * @param rebasedToId 承接候选；原候选直接判为已合并时为 null
   * @param targetExisted 承接候选是既有候选，而不是本次新建的
   * @param released 因本次改判而进入公开目录的成果
   */
  public record RebaseOutcome(
      String candidateId,
      String status,
      String rebasedToId,
      boolean targetExisted,
      String finalLeftId,
      String finalRightId,
      List<String> released) {

    public RebaseOutcome {
      released = List.copyOf(released);
    }
  }

  /**
   * 单条裁决的结果。
   *
   * @param affectedCount 参与重评估的成果数（含被合并方，它会被重评估的跳过条件挡下）
   * @param released 本次真正进入公开目录的成果
   */
  public record Outcome(String candidateId, int affectedCount, List<String> released) {

    public Outcome {
      released = List.copyOf(released);
    }
  }

  /**
   * 批量裁决的结果。
   *
   * @param requested 请求裁决的候选数
   * @param resolved 实际裁决成功数
   * @param skipped 未处理的候选与原因（不存在、已裁决、状态冲突）
   * @param released 因本次裁决而进入公开目录的成果
   */
  public record BatchOutcome(
      int requested, int resolved, List<String> skipped, List<String> released) {

    public BatchOutcome {
      skipped = List.copyOf(skipped);
      released = List.copyOf(released);
    }
  }
}
