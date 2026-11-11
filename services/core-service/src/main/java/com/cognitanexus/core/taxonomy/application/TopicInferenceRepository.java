package com.cognitanexus.core.taxonomy.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 领域推断的持久化端口。
 *
 * <p>与 {@code TopicRepository} 分开的理由：那一个是「领域规范词与归属」的常规读写，服务于
 * 采集入库；这一组操作只在批量推断与管理端出现，且带有明确的派生语义（可删、可重算、带版本）。
 * 合成一个接口会让采集路径也拿到「删掉某成果的全部推断归属」这种能力，而它没有理由拥有。
 */
public interface TopicInferenceRepository {

  /** 待推断的成果文本。venue/abstractText 允许为 null：缺失就是缺失，不编造。 */
  record PendingAchievement(
      String achievementId, String title, String venue, String abstractText) {}

  /** 一条推断尝试。outcome 取 ASSIGNED / UNRESOLVED。 */
  record InferenceRecord(
      String achievementId,
      String outcome,
      int assignedCount,
      BigDecimal bestConfidence,
      String evidence,
      Instant inferredAt) {}

  /** 归属来源盘点。rowCount 是按归属行的口径，achievementCount 是按成果去的重口径。 */
  record KindCount(String kind, long rowCount, long achievementCount) {}

  /** 推断结果盘点。 */
  record OutcomeCount(String outcome, long achievements) {}

  /** 取一批待推断的成果：无来源分类、且当前规则版本尚未尝试过。 */
  List<PendingAchievement> listPending(int limit, String ruleVersion);

  /** 待推断总数。批量推进后它应当恰好减少「本轮处理条数」，是判断推断是否真在前进的依据。 */
  long countPending(String ruleVersion);

  /** 写一条领域归属，归属来源固定为 INFERRED。已存在同名归属时返回 0。 */
  int linkInferred(
      String achievementId,
      String topicId,
      String ruleVersion,
      BigDecimal confidence,
      String evidence);

  /**
   * 删除该成果由其它规则版本落下的推断归属。
   *
   * <p>换词表版本重算时先清旧版本：同一成果上若同时挂着两版推断，读起来像是它有两个来源，
   * 而实际上只是一次重算没收干净。
   */
  int deleteInferredOfOtherVersions(String achievementId, String ruleVersion);

  /** 记一条推断尝试（幂等：同成果同版本覆盖）。 */
  int upsertInference(
      String achievementId,
      String ruleVersion,
      String outcome,
      int assignedCount,
      BigDecimal bestConfidence,
      String evidence);

  List<KindCount> countByKind();

  List<OutcomeCount> countByOutcome(String ruleVersion);

  long countInferredRows(String ruleVersion);

  /** 待分类清单：当前规则版本下判不出领域的成果。 */
  List<InferenceRecord> listUnresolved(String ruleVersion, int limit);

  /** 一条领域归属都没有的成果数，含尚未推断的与判不出的。 */
  long countAchievementsWithoutTopic();
}
