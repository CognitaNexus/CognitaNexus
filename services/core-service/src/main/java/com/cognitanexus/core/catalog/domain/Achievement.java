package com.cognitanexus.core.catalog.domain;

import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.Visibility;
import java.time.Instant;

/**
 * 成果规范实体。
 *
 * <p>依据：架构 V0.2 §5.1、§5.4。一个成果根据类型恰好拥有一个对应扩展（论文/专利/项目/获奖），
 * 不同时拥有多种；类型与扩展的一致性由触发器在提交时校验。
 *
 * <p>数据性质 REAL/SIMULATED/MIXED 互斥且不允许默认 REAL：容量测试合成数据必须显式标为
 * SIMULATED，否则会被默认现实统计当成真实收录。
 *
 * @param mergedIntoId 被合并到哪个成果；非 null 表示本实体是重定向源，旧链接必须跳到新实体
 */
public record Achievement(
    String id,
    AchievementType type,
    String title,
    String titleNorm,
    DataNature dataNature,
    Visibility visibility,
    EffectiveStatus effectiveStatus,
    PublicationYear publicationYear,
    String mergedIntoId,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public boolean merged() {
    return mergedIntoId != null;
  }

  /** 是否具备进入默认现实视图的资格：真实、公开、生效且未被合并。 */
  public boolean eligibleForRealityView() {
    return dataNature.eligibleForRealityView()
        && visibility.publiclyVisible()
        && effectiveStatus.publiclyEligible()
        && !merged();
  }

  public Achievement withVersion(long newVersion) {
    return new Achievement(
        id,
        type,
        title,
        titleNorm,
        dataNature,
        visibility,
        effectiveStatus,
        publicationYear,
        mergedIntoId,
        newVersion,
        createdAt,
        updatedAt);
  }
}
