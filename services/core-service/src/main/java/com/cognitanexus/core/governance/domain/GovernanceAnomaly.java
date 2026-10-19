package com.cognitanexus.core.governance.domain;

import java.time.Instant;
import java.util.Map;

/**
 * 治理异常。
 *
 * <p>依据：架构 V0.2 §6.2、AC-IMPL-FR08-01。异常不直接成为公开成果；待核验与异常数量必须在
 * 任务结果中单列披露，不能把失败伪装成「已处理」。
 */
public record GovernanceAnomaly(
    String id,
    String kind,
    String sourceRecordId,
    String entityId,
    Map<String, Object> detail,
    String status,
    Instant createdAt,
    Instant resolvedAt) {

  /** 异常种类。名称进入数据库，避免使用会被误解为业务结论的描述。 */
  public static final String KIND_STRUCTURE_INVALID = "STRUCTURE_INVALID";

  public static final String KIND_IDENTIFIER_CONFLICT = "IDENTIFIER_CONFLICT";
  public static final String KIND_MISSING_REQUIRED_FIELD = "MISSING_REQUIRED_FIELD";
  public static final String KIND_SOURCE_DISABLED = "SOURCE_DISABLED";
  public static final String KIND_UNMAPPED_CATEGORY = "UNMAPPED_CATEGORY";

  // 以下由数据质量巡检（DataQualityScanService）产出。前两类逐条登记（每条都要单独处置），
  // 其余四类按「一类一条」聚合登记：它们是同一口径下的批量缺陷，逐条登记只会把待办列表淹掉，
  // 而处置动作本身也是批量的。

  /** 年份越界：年份落在合理区间之外（数据源把非年份写进了年份字段）。逐条登记。 */
  public static final String KIND_YEAR_OUT_OF_RANGE = "YEAR_OUT_OF_RANGE";

  /** 领域归属冲突：同一成果同一领域同时存在来源给定与推断两种归属。逐条登记。 */
  public static final String KIND_TOPIC_KIND_CONFLICT = "TOPIC_KIND_CONFLICT";

  /** 已生效成果没有任何领域归属（分面与领域统计会漏计）。聚合登记。 */
  public static final String KIND_MISSING_TOPIC = "MISSING_TOPIC";

  /** 已生效成果没有任何参与关系（公开详情会显示为空作者列表）。聚合登记。 */
  public static final String KIND_NO_CONTRIBUTOR = "NO_CONTRIBUTOR";

  /** 已生效论文没有登记任何稳定标识。聚合登记。 */
  public static final String KIND_PAPER_WITHOUT_IDENTIFIER = "PAPER_WITHOUT_IDENTIFIER";

  /** 同名学者聚集：同名但未被合并的学者达到阈值。聚合登记，<b>不代表同人</b>。 */
  public static final String KIND_DUPLICATE_SCHOLAR_NAME = "DUPLICATE_SCHOLAR_NAME";

  /**
   * 同名归并待核验聚集：一条学者记录上挂着多条待核验关系。
   *
   * <p>这是本平台真实的同名风险所在：姓名相同但并不确定是同一人时，接入路径不会新建学者、
   * 也不会直接确认为同一人，而是复用该学者并把关系标为待核验。于是一个人名记录下可能同时挂着
   * 好几位不同学者的成果，且这些关系都不会进入共著网络。聚合登记。
   */
  public static final String KIND_AMBIGUOUS_SCHOLAR_NAME = "AMBIGUOUS_SCHOLAR_NAME";

  /**
   * 稳定标识一致但证据矛盾：同一 DOI／申请号等标识命中了既有成果，但本条记录的题名、年份、作者或类型与之明显不一致。
   *
   * <p>为什么不能沿用 {@link #KIND_IDENTIFIER_CONFLICT}：那条是「同一标识被两个实体抢注」，处置动作是确定归并方向；
   * 这条是「标识一致、内容像两篇不同的东西」，多半是上游 DOI 填错、复用或被劫持，处置动作是核实来源而不是合并。
   * 两者混在一个类型里，处置人无法判断该看哪一边。
   *
   * <p>登记本条时既有成果的字段与指标一律不被改写：矛盾记录的题名、年份、引用量都可能属于另一篇文献。
   */
  public static final String KIND_METADATA_CONFLICT = "METADATA_CONFLICT";

  /**
   * 公众纠错报告：站外访客在成果详情页指出「这条记录有错」。
   *
   * <p>为什么它必须进待办而不是直接改数据：报告来自匿名访客，它提供的是「线索」而不是「结论」。
   * 本平台的红线是来源原值不可被人工修订覆盖、自动更新不得覆盖人工审核值，若让一条匿名请求直接
   * 改写字段，就等于开了一个绕过全部溯源与审核的写入通道。因此这条只登记为待办，由治理人员核实
   * 后再走既有的修订与裁决路径。
   *
   * <p>为什么用逐条登记（不是聚合）：每一条报告指向一处具体错误，处置动作也是逐条核实，
   * 聚合只会变成「一类一条」，丢掉具体是哪条成果、错在哪。
   */
  public static final String KIND_PUBLIC_CORRECTION_REPORT = "PUBLIC_CORRECTION_REPORT";

  private static final Map<String, String> LABELS =
      Map.ofEntries(
          Map.entry(KIND_STRUCTURE_INVALID, "结构不合法"),
          Map.entry(KIND_IDENTIFIER_CONFLICT, "稳定标识冲突"),
          Map.entry(KIND_METADATA_CONFLICT, "标识一致但证据矛盾"),
          Map.entry(KIND_PUBLIC_CORRECTION_REPORT, "公众纠错报告"),
          Map.entry(KIND_MISSING_REQUIRED_FIELD, "缺少必填字段"),
          Map.entry(KIND_SOURCE_DISABLED, "来源已停用"),
          Map.entry(KIND_UNMAPPED_CATEGORY, "来源分类无法映射"),
          Map.entry(KIND_YEAR_OUT_OF_RANGE, "年份越界"),
          Map.entry(KIND_TOPIC_KIND_CONFLICT, "领域归属冲突"),
          Map.entry(KIND_MISSING_TOPIC, "缺少领域归属"),
          Map.entry(KIND_NO_CONTRIBUTOR, "缺少参与关系"),
          Map.entry(KIND_PAPER_WITHOUT_IDENTIFIER, "论文缺少稳定标识"),
          Map.entry(KIND_DUPLICATE_SCHOLAR_NAME, "同名学者聚集"),
          Map.entry(KIND_AMBIGUOUS_SCHOLAR_NAME, "同名待核验聚集"));

  /**
   * 异常种类的中文标签。
   *
   * <p>只用于展示。未知种类原样返回代码而不是「其它」：把不认识的种类折进一个笼统分类，
   * 会让新引入的检测项在界面上彻底消失。
   */
  public static String label(String kind) {
    if (kind == null) {
      return "";
    }
    return LABELS.getOrDefault(kind, kind);
  }
}
