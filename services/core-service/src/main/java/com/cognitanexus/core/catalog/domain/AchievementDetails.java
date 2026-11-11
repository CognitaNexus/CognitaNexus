package com.cognitanexus.core.catalog.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 四类成果的类型扩展。
 *
 * <p>依据：架构 V0.2 §5.1、需求基线 5.1。四种扩展结构差异较大，且各自有独立的稳定标识归一列，
 * 因此并列定义在同一文件内便于对照类型一致性约束；它们互斥，同一成果只会有其中一个非 null。
 */
public final class AchievementDetails {

  private AchievementDetails() {}

  /**
   * 论文扩展。
   *
   * <p>{@code publishedDate} 是真正的日期（来源给出完整日期时才有值）；只有年份时该字段留空，
   * 由 {@link Achievement#publicationYear()} 承载，避免补 1 月 1 日。
   *
   * @param citationCount 引用量按来源展示，不同来源不相加制造总引用
   * @param citationSource 引用量来源，缺省时引用量不得单独展示
   */
  public record Paper(
      String achievementId,
      String doi,
      String doiNorm,
      String venue,
      LocalDate publishedDate,
      String preprintStatus,
      Integer citationCount,
      String citationSource,
      String abstractText) {}

  /**
   * 专利扩展。
   *
   * <p>申请号在自身作用域内归一匹配；公开号不同不按标题合并（需求基线 5.1）。
   */
  public record Patent(
      String achievementId,
      String applicationNo,
      String applicationNoNorm,
      String publicationNo,
      List<String> ipcCodes,
      List<String> applicantNames,
      LocalDate filingDate,
      LocalDate publicationDate) {}

  /**
   * 科研项目扩展。
   *
   * <p>不默认采集或公开经费金额（SRS 5.1、BR-IMP-11）；本记录刻意不包含金额字段。
   */
  public record ResearchProject(
      String achievementId,
      String projectNo,
      String projectNoNorm,
      String leaderName,
      String fundingAgency,
      String projectCategory,
      LocalDate startDate,
      LocalDate endDate) {}

  /**
   * 获奖扩展。
   *
   * <p>获奖人通过参与关系承载，本表只描述奖项本身（SRS 5.1）。
   */
  public record Award(
      String achievementId,
      String awardName,
      Integer awardYear,
      String awardLevel,
      String grantingBody) {}
}
