package com.cognitanexus.core.governance.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动发布判定。
 *
 * <p>依据：架构 V0.2 §6.2（只有治理通过或人工审批才能发布规范实体）、BR-IMP-08。
 *
 * <p>本类只回答「是否可以不经人工直接发布」，判定条件刻意保守：
 *
 * <ol>
 *   <li>必须带有来源提供的稳定标识（DOI / 申请号 / 项目号），因为稳定标识是跨源同一性的唯一可靠依据；
 *   <li>该标识在注册表中未被其他规范实体占用（占用说明存在冲突，须人工）；
 *   <li>不存在同标题的开放候选（相似即存疑，存疑即不自动）；
 *   <li>数据性质必须为 REAL：合成数据不得冒充真实收录；
 *   <li>来源的展示许可必须仍然有效（ADR-001 方案 A）。撤权之后仍在采集的来源会继续送来新记录，
 *       这些成果不能因为「DOI 正常、无相似候选」就自动公开——那等于撤权只在当下拦住存量，
 *       下一次采集就把许可又踩回来了。
 * </ol>
 *
 * <p>不满足时返回理由列表，调用方据此把实体留在 PENDING_GOVERNANCE 并登记候选或异常；
 * 不满足 <b>不等于</b>实体无效。
 */
public final class AutoPublishPolicy {

  private AutoPublishPolicy() {}

  /**
   * 判定结果。
   *
   * @param publishable 是否可自动发布
   * @param reasons 不可自动发布的原因；可发布时为空列表
   */
  public record Decision(boolean publishable, List<String> reasons) {

    public Decision {
      reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static Decision allow() {
      return new Decision(true, List.of());
    }
  }

  public static Decision evaluate(Inputs inputs) {
    List<String> reasons = new ArrayList<>();
    if (!inputs.hasStableIdentifier()) {
      reasons.add("缺少来源提供的稳定标识，无法确认跨源同一性");
    }
    if (inputs.identifierConflicted()) {
      reasons.add("稳定标识已被其他规范实体占用，须人工裁定");
    }
    if (inputs.hasOpenSimilarCandidate()) {
      reasons.add("存在相似标题候选，须人工比对年份、参与者与类型");
    }
    if (!inputs.dataNature().equals("REAL")) {
      reasons.add("数据性质为 " + inputs.dataNature() + "，不得进入默认现实视图");
    }
    if (!inputs.sourceDisplayLicenseGranted()) {
      reasons.add("来源展示许可已撤销，新成果不得自动公开");
    }
    return reasons.isEmpty() ? Decision.allow() : new Decision(false, reasons);
  }

  /**
   * 判定输入。
   *
   * @param dataNature 数据性质名称（REAL/SIMULATED/MIXED）
   * @param sourceDisplayLicenseGranted 来源当前是否仍有展示许可；撤销后新成果一律不自动公开
   */
  public record Inputs(
      boolean hasStableIdentifier,
      boolean identifierConflicted,
      boolean hasOpenSimilarCandidate,
      String dataNature,
      boolean sourceDisplayLicenseGranted) {}
}
