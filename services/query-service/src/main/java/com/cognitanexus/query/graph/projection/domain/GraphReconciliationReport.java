package com.cognitanexus.query.graph.projection.domain;

import java.time.Instant;
import java.util.List;

/**
 * PG 权威读表与 Neo4j 图投影的对账报告（ADR-005 §6.2、§8.2）。
 *
 * <p>为什么这些数字必须成组出现而不是只报「一致/不一致」：每个计数都对应一条独立的映射规则
 * （成果合格性、参与关系资格、作者对物化阈值、水位前进），一次只报总数会让「成果数对上了、
 * 边多了一倍」这种差异被整体判断掩盖。分开报，差异类型直接指出哪条规则被破坏。
 *
 * <p>{@code graphProjectedVersion < pgPublishedRevision} 是 WARN 而不是 ERROR：水位落后是
 * 「图暂时不能用」，读路径按 ADR-005 §7 降级到 PG，数据本身可能完全自洽；而计数不同意味着
 * 同一口径下两边内容不同，图即使「追平了」也不可信，必须是 ERROR。
 *
 * <p>{@code scannedWorks} / {@code sampledWorks} 与 {@code scanTruncated} 让报告自带边界说明：
 * 只扫描了前 N 条的对账不能被读成「全库一致」。这类范围说明用 INFO 记，不隐去。
 */
public record GraphReconciliationReport(
    String scope,
    String generation,
    String dataOrigin,
    long pgEligibleWorks,
    long graphPublicWorks,
    long pgScholars,
    long graphScholars,
    long pgAuthoredRelations,
    long graphAuthoredRelations,
    long pgMaterializedPairs,
    long graphMaterializedPairs,
    long pgPublishedRevision,
    long graphProjectedVersion,
    int scannedWorks,
    int sampledWorks,
    boolean scanTruncated,
    List<String> missingWorks,
    List<String> orphanedWorks,
    List<GraphReconciliationFinding> findings,
    Instant completedAt) {

  public GraphReconciliationReport {
    if (scope == null || scope.isBlank()) {
      throw new IllegalArgumentException("对账报告必须记录作用域");
    }
    dataOrigin = dataOrigin == null || dataOrigin.isBlank() ? "ALL" : dataOrigin;
    missingWorks = missingWorks == null ? List.of() : List.copyOf(missingWorks);
    orphanedWorks = orphanedWorks == null ? List.of() : List.copyOf(orphanedWorks);
    findings = findings == null ? List.of() : List.copyOf(findings);
    completedAt = completedAt == null ? Instant.EPOCH : completedAt;
  }

  /** 是否存在必须修复的差异（计数不一致、证据不一致、缺边/多边）。 */
  public boolean hasDrift() {
    return findings.stream().anyMatch(GraphReconciliationFinding::blocking);
  }

  /** 图的投影水位是否落后于 PG 已发布修订；落后时读路径必须走 PG 降级。 */
  public boolean watermarkBehind() {
    return graphProjectedVersion < pgPublishedRevision;
  }

  /** 可以安全宣称「图与 PG 在同一口径下一致」：没有阻塞差异，且水位不落后。 */
  public boolean consistent() {
    return !hasDrift() && !watermarkBehind();
  }

  /** 报告里各等级的差异条数，供指标与告警使用。 */
  public long countBySeverity(GraphReconciliationFinding.Severity severity) {
    return findings.stream().filter(finding -> finding.severity() == severity).count();
  }

  /** 一行摘要（不得包含凭据/主机信息）。 */
  public String summary() {
    return String.format(
        "reconcile[scope=%s, generation=%s, origin=%s] works %d/%d, scholars %d/%d, "
            + "authored %d/%d, pairs %d/%d, revision %d/%d, scanned=%d, sampled=%d, "
            + "errors=%d, warnings=%d, truncated=%s",
        scope,
        generation,
        dataOrigin,
        pgEligibleWorks,
        graphPublicWorks,
        pgScholars,
        graphScholars,
        pgAuthoredRelations,
        graphAuthoredRelations,
        pgMaterializedPairs,
        graphMaterializedPairs,
        pgPublishedRevision,
        graphProjectedVersion,
        scannedWorks,
        sampledWorks,
        countBySeverity(GraphReconciliationFinding.Severity.ERROR),
        countBySeverity(GraphReconciliationFinding.Severity.WARN),
        scanTruncated);
  }
}
