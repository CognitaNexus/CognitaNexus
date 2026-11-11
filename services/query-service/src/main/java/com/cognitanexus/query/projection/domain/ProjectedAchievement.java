package com.cognitanexus.query.projection.domain;

import java.time.LocalDate;

/**
 * 成果公开读投影。
 *
 * <p>依据：架构 V0.2 §5.4、§9.2。字段全部来自 core 的公开投影载荷，不做推导、不做补全：
 *
 * <ul>
 *   <li>publicationYear 可为 null。来源未给出年份时不得补 1 月 1 日或当前时间，也不得补一个
 *       看起来合理的年份；缺失在统计中单列，不参与年份排名；
 *   <li>citationCount 与 citationSource 成对保留。不同来源的引用量不可相加制造「总引用」；
 *   <li>dataNature 与 core 一致，REAL/SIMULATED/MIXED 互斥，不允许默认 REAL。
 * </ul>
 */
public record ProjectedAchievement(
    String achievementId,
    String type,
    String title,
    String titleNorm,
    String dataNature,
    String visibility,
    String effectiveStatus,
    String integrityStatus,
    Integer publicationYear,
    String doi,
    String venue,
    Long citationCount,
    String citationSource,
    LocalDate publishedDate,
    String abstractText,
    long aggregateVersion,
    long revision) {

  /**
   * 是否有生效的诚信声明（关注 / 更正 / 撤稿）。
   *
   * <p>它不影响 {@link #publiclyVisible()}：撤稿成果仍然可检索，学术记录需要可查证。若在这里把它
   * 判成不可见，撤稿就等于悄悄下架，引用过它的人再也查不到发生了什么。
   */
  public boolean hasIntegrityNotice() {
    return integrityStatus != null && !"NONE".equals(integrityStatus);
  }

  public boolean retracted() {
    return "RETRACTION".equals(integrityStatus);
  }

  /** 是否满足默认现实视图的资格：真实、公开、生效。 */
  public boolean eligibleForRealityView() {
    return "REAL".equals(dataNature)
        && "PUBLIC".equals(visibility)
        && "EFFECTIVE".equals(effectiveStatus);
  }

  /**
   * 是否对公开读可见：公开且已生效。
   *
   * <p>与 {@link #eligibleForRealityView()} 的区别是不看数据性质：显式演示作用域下，被标记为
   * 模拟或混合的公开条目仍应能看到自己的作者与领域，否则演示模式会变成一堆空壳卡片。
   */
  public boolean publiclyVisible() {
    return "PUBLIC".equals(visibility) && "EFFECTIVE".equals(effectiveStatus);
  }
}
