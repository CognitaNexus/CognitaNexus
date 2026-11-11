package com.cognitanexus.core.catalog.domain;

import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.DataNature;
import java.util.List;

/**
 * 被引用对象：一份引用文本所需的全部事实。
 *
 * <p>这是一个<b>快照</b>，由应用层从成果、类型扩展、参与关系、诚信声明四处装配而来，格式化器
 * 只读它、不再回查任何仓储。这样做的原因是可测性：引用文本的正确性由「给定这些事实，文本必须长
 * 这样」决定，把它和数据库纠缠在一起就只能靠接口测试间接验证，而一个冒号、一个空格的错误恰恰
 * 是最需要逐条断言的。
 *
 * <p>四类成果的差异用<b>显式可空字段</b>表达而不是塞一个 {@code Map<String,Object>}：字段名本身就是
 * 文档，读者一眼能看出「专利引用需要公开号、项目引用需要资助机构」，而 Map 要到格式化器内部才能
 * 看出用了哪些键。
 *
 * @param venue 论文的刊名／会议名；其它类型为 null
 * @param doi 论文 DOI；其它类型为 null
 * @param number 专利的公开号（缺失时退化为申请号）、项目的项目编号；论文与获奖为 null
 * @param issuingBody 项目的资助机构、获奖的授奖机构、专利的申请人（来源给出时）；论文为 null
 * @param awardLevel 获奖级别（国家级／省部级等）；仅获奖使用
 * @param integrityStatus 诚信汇总状态码，{@code NONE} 表示无生效声明
 * @param year 发表／公开／立项／获奖年份；未知时为 null，格式化器按各自规范写「年份不详」而非编造
 */
public record CitationSubject(
    String achievementId,
    AchievementType type,
    String title,
    Integer year,
    String venue,
    String doi,
    String number,
    String issuingBody,
    String awardLevel,
    List<CitationAuthor> authors,
    String integrityStatus,
    String integrityLabel,
    DataNature dataNature) {

  public CitationSubject {
    authors = authors == null ? List.of() : List.copyOf(authors);
  }

  public boolean yearKnown() {
    return year != null;
  }

  public boolean retracted() {
    return "RETRACTION".equals(integrityStatus);
  }

  /** 是否有任何生效的诚信声明（撤稿／更正／关注）。 */
  public boolean noticed() {
    return integrityStatus != null && !"NONE".equals(integrityStatus);
  }

  public boolean realData() {
    return dataNature != null && dataNature.eligibleForRealityView();
  }
}
