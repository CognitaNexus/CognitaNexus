package com.cognitanexus.core.taxonomy.infrastructure;

import com.cognitanexus.core.taxonomy.application.TopicInferenceRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 领域推断仓储实现。薄适配层：应用层只看到端口，SQL 全部留在 Mapper 里。 */
@Repository
public class TaxonomyRepositoryAdapter implements TopicInferenceRepository {

  private final TaxonomyMapper mapper;

  public TaxonomyRepositoryAdapter(TaxonomyMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<PendingAchievement> listPending(int limit, String ruleVersion) {
    return mapper.listPending(limit, ruleVersion);
  }

  @Override
  public long countPending(String ruleVersion) {
    return mapper.countPending(ruleVersion);
  }

  @Override
  public int linkInferred(
      String achievementId,
      String topicId,
      String ruleVersion,
      BigDecimal confidence,
      String evidence) {
    return mapper.linkInferred(achievementId, topicId, ruleVersion, confidence, evidence);
  }

  @Override
  public int deleteInferredOfOtherVersions(String achievementId, String ruleVersion) {
    return mapper.deleteInferredOtherVersions(achievementId, ruleVersion);
  }

  @Override
  public int upsertInference(
      String achievementId,
      String ruleVersion,
      String outcome,
      int assignedCount,
      BigDecimal bestConfidence,
      String evidence) {
    return mapper.upsertInference(
        achievementId, ruleVersion, outcome, assignedCount, bestConfidence, evidence);
  }

  @Override
  public List<KindCount> countByKind() {
    return mapper.countByKind();
  }

  @Override
  public List<OutcomeCount> countByOutcome(String ruleVersion) {
    return mapper.countByOutcome(ruleVersion);
  }

  @Override
  public long countInferredRows(String ruleVersion) {
    return mapper.countInferredRows(ruleVersion);
  }

  @Override
  public List<InferenceRecord> listUnresolved(String ruleVersion, int limit) {
    return mapper.listUnresolved(ruleVersion, limit);
  }

  @Override
  public long countAchievementsWithoutTopic() {
    return mapper.countAchievementsWithoutTopic();
  }
}
