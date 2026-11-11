package com.cognitanexus.query.projection.infrastructure;

import com.cognitanexus.query.projection.application.ProjectionRepositories.ReadModelStore;
import com.cognitanexus.query.projection.domain.ProjectedAchievement;
import com.cognitanexus.query.projection.domain.ProjectedContribution;
import com.cognitanexus.query.projection.domain.ProjectedTopic;
import com.cognitanexus.query.projection.domain.ScholarPair;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 读模型端口的 MyBatis 实现。
 *
 * <p>依据：编码规范 §2.2。适配器负责领域记录与持久化行之间的翻译，并把「派生集合先清后写」这个
 * 顺序固定下来：顺序一旦散落到各调用点，就会出现某一处忘了清空而把两批数据叠加的情况。
 */
@Repository
public class ReadModelStoreAdapter implements ReadModelStore {

  private final ReadModelMapper mapper;

  public ReadModelStoreAdapter(ReadModelMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void upsertAchievement(String generation, ProjectedAchievement achievement) {
    mapper.upsertAchievement(generation, toRow(achievement));
  }

  @Override
  public void deleteAchievement(String generation, String achievementId) {
    mapper.deleteAchievement(generation, achievementId);
  }

  @Override
  public int restrictAchievement(String generation, String achievementId, long revision) {
    return mapper.restrictAchievement(generation, achievementId, revision);
  }

  @Override
  public int restoreAchievement(String generation, String achievementId, long revision) {
    return mapper.restoreAchievement(generation, achievementId, revision);
  }

  @Override
  public void replaceContributions(
      String generation, String achievementId, List<ProjectedContribution> contributions) {
    // 先清空：事件载荷给的是该成果参与关系的完整集合，不清空就会把两批数据叠加，
    // 表现为作者列表里出现重复或已移除的作者。
    mapper.deleteContributionsOfAchievement(generation, achievementId);
    mapper.deleteContributionOrgsOfAchievement(generation, achievementId);

    List<ProjectedContribution> rows = contributions == null ? List.of() : contributions;
    if (rows.isEmpty()) {
      return;
    }
    mapper.insertContributions(
        generation,
        rows.stream()
            .map(
                item ->
                    new ReadModelMapper.ContributionRow(
                        item.achievementId(),
                        item.scholarId(),
                        item.scholarName(),
                        item.role(),
                        item.authorOrder(),
                        item.relationState()))
            .toList());

    List<ReadModelMapper.ContributionOrgRow> orgs = new ArrayList<>();
    for (ProjectedContribution item : rows) {
      for (String organization : item.organizations()) {
        if (organization != null && !organization.isBlank()) {
          orgs.add(
              new ReadModelMapper.ContributionOrgRow(
                  item.achievementId(), item.scholarId(), organization));
        }
      }
    }
    if (!orgs.isEmpty()) {
      mapper.insertContributionOrgs(generation, orgs);
    }
  }

  @Override
  public void replaceTopics(String generation, String achievementId, List<ProjectedTopic> topics) {
    mapper.deleteTopics(generation, achievementId);
    List<ProjectedTopic> rows = topics == null ? List.of() : topics;
    if (rows.isEmpty()) {
      return;
    }
    mapper.insertTopics(
        generation,
        rows.stream()
            .map(
                item ->
                    new ReadModelMapper.TopicRow(
                        achievementId,
                        item.topicId(),
                        item.canonicalName(),
                        item.assignmentKind(),
                        item.confidence() == null
                            ? null
                            : java.math.BigDecimal.valueOf(item.confidence())))
            .toList());
  }

  @Override
  public void replaceEdges(String generation, String achievementId, List<ScholarPair> pairs) {
    // 先移除本论文贡献的全部旧边再插入新边（架构 §9.3）：靠累计加一无法处理作者被移除的情况，
    // 会让边强度只增不减。
    mapper.deleteEdges(generation, achievementId);
    List<ScholarPair> rows = pairs == null ? List.of() : pairs;
    if (rows.isEmpty()) {
      return;
    }
    mapper.insertEdges(
        generation,
        achievementId,
        rows.stream()
            .map(pair -> new ReadModelMapper.EdgeRow(pair.scholarA(), pair.scholarB()))
            .toList());
  }

  @Override
  public void deleteEdges(String generation, String achievementId) {
    mapper.deleteEdges(generation, achievementId);
  }

  @Override
  public int rewriteScholarContributions(
      String generation, String mergedId, String survivorId, String survivorName) {
    // 顺序不可交换：机构迁移要先于参与关系迁移执行，否则「幸存者是否已在该成果上有关系」的判断
    // 会被刚刚插入的行变成恒真，机构归属就永远不会迁移。
    mapper.insertMovedScholarOrgs(generation, survivorId, mergedId);
    int moved =
        mapper.insertMovedScholarContributions(generation, survivorId, survivorName, mergedId);
    mapper.deleteContributionsOfScholar(generation, mergedId);
    mapper.deleteContributionOrgsOfScholar(generation, mergedId);
    return moved;
  }

  @Override
  public int updateScholarName(String generation, String scholarId, String scholarName) {
    return mapper.updateScholarName(generation, scholarId, scholarName);
  }

  @Override
  public List<String> listAchievementIdsOfScholar(String generation, String scholarId) {
    return mapper.listAchievementIdsOfScholar(generation, scholarId);
  }

  @Override
  public Optional<ProjectedAchievement> findAchievement(String generation, String achievementId) {
    return Optional.ofNullable(mapper.findAchievement(generation, achievementId))
        .map(ReadModelStoreAdapter::toDomain);
  }

  @Override
  public List<ProjectedContribution> listContributions(String generation, String achievementId) {
    return mapper.listContributions(generation, achievementId).stream()
        .map(
            row ->
                new ProjectedContribution(
                    row.achievementId(),
                    row.scholarId(),
                    row.scholarName(),
                    row.role(),
                    row.authorOrder(),
                    row.relationState(),
                    organizationsOf(generation, achievementId, row.scholarId())))
        .toList();
  }

  @Override
  public void recordRedirect(String entityType, String oldId, String newId, long revision) {
    mapper.upsertRedirect(entityType, oldId, newId, revision);
  }

  /**
   * 单个参与关系的发生时机构。
   *
   * <p>这里逐条查询而不是批量取回后分组，是因为调用点只有两处：投影内部自己构造贡献列表时用的
   * 是事件载荷里带来的机构，不走本方法；本方法服务于测试与运维核对。为了一个非热点路径引入
   * 批量装配会掩盖真实的热点查询形态。
   */
  private List<String> organizationsOf(String generation, String achievementId, String scholarId) {
    return mapper.listOrganizations(generation, achievementId, scholarId);
  }

  private static ReadModelMapper.AchievementRow toRow(ProjectedAchievement achievement) {
    Integer year = achievement.publicationYear();
    LocalDate publishedDate = achievement.publishedDate();
    return new ReadModelMapper.AchievementRow(
        achievement.achievementId(),
        achievement.type(),
        achievement.title(),
        achievement.titleNorm(),
        achievement.dataNature(),
        achievement.visibility(),
        achievement.effectiveStatus(),
        achievement.integrityStatus(),
        year,
        achievement.doi(),
        achievement.venue(),
        achievement.citationCount(),
        achievement.citationSource(),
        publishedDate,
        achievement.abstractText(),
        achievement.aggregateVersion(),
        achievement.revision());
  }

  private static ProjectedAchievement toDomain(ReadModelMapper.AchievementRow row) {
    return new ProjectedAchievement(
        row.achievementId(),
        row.type(),
        row.title(),
        row.titleNorm(),
        row.dataNature(),
        row.visibility(),
        row.effectiveStatus(),
        row.integrityStatus(),
        row.publicationYear(),
        row.doi(),
        row.venue(),
        row.citationCount(),
        row.citationSource(),
        row.publishedDate(),
        row.abstractText(),
        row.aggregateVersion(),
        row.revision());
  }
}
