package com.cognitanexus.core.catalog.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.TopicRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.TopicRepository.TopicAssignment;
import com.cognitanexus.core.catalog.domain.Contribution;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 公开投影载荷装配。
 *
 * <p>依据：架构 V0.2 §7.1、§7.3、§9.2。
 *
 * <p>存在理由：同一份公开投影有三个出口——Outbox 事件载荷、导出快照、以及未来可能的重建回放。
 * 若它们各自现场拼装，迟早会出现「回放事件得到的投影」与「导出快照」字段不一致；这类偏差只在
 * 切换 generation 时才暴露，定位成本极高。因此把口径收敛到这一个装配点。
 *
 * <p>本装配器只读，不做任何授权判断：调用方必须已经确认该成果可公开。
 */
@Component
class PublicProjectionAssembler {

  private final CatalogRepositories.ContributionRepository contributions;
  private final CatalogRepositories.ScholarRepository scholars;
  private final TopicRepository topics;

  PublicProjectionAssembler(
      CatalogRepositories.ContributionRepository contributions,
      CatalogRepositories.ScholarRepository scholars,
      TopicRepository topics) {
    this.contributions = contributions;
    this.scholars = scholars;
    this.topics = topics;
  }

  /**
   * 参与关系投影。
   *
   * <p>organizations 是该学者<b>在成果发生时</b>的机构，不是当前任职机构（架构 §5.1、BR-IMP-06）；
   * 未知时给空列表，不猜测。
   */
  List<Map<String, Object>> contributionsOf(String achievementId) {
    List<Map<String, Object>> views = new ArrayList<>();
    for (Contribution contribution : contributions.listByAchievement(achievementId)) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("scholarId", contribution.scholarId());
      view.put("role", contribution.role());
      view.put("authorOrder", contribution.authorOrder());
      view.put("relationState", contribution.relationState().name());
      scholars
          .findById(contribution.scholarId())
          .ifPresent(s -> view.put("scholarName", s.displayName()));
      view.put("organizations", contributions.listOrganizationNames(contribution.id()));
      views.add(view);
    }
    return views;
  }

  /**
   * 领域投影。
   *
   * <p>带规范名而不只带 id：查询侧按领域聚合与展示时若只有 id，就必须回头调 core 解析名称，
   * 使读路径依赖权威服务，既放大耦合又让「投影可独立回答查询」这一前提失效。
   *
   * <p>带 assignmentKind 同样不是可选项：领域分类会进入检索分面与领域热点排名，若投影把来源
   * 分类与系统推断抹成同一个样子，读者就无从判断某个排名是元数据事实还是本系统的规则产出。
   * {@code confidence} 只在推断归属上出现——来源给出的分类没有置信度这个概念，补一个数字
   * 只会在界面上凭空多出一个看似成立的指标。
   */
  List<Map<String, Object>> topicsOf(String achievementId) {
    List<Map<String, Object>> views = new ArrayList<>();
    for (TopicAssignment assignment : topics.listAssignmentsOf(achievementId)) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", assignment.topicId());
      topics.findTopic(assignment.topicId()).ifPresent(t -> view.put("canonicalName", t.canonicalName()));
      view.put("assignmentKind", assignment.assignmentKind());
      if (assignment.confidence() != null) {
        view.put("confidence", assignment.confidence().doubleValue());
      }
      views.add(view);
    }
    return views;
  }

  /** 只保留领域 id 的平铺列表，供不需要名称的调用点使用。 */
  List<String> topicIdsOf(String achievementId) {
    return topics.listTopicIdsOf(achievementId);
  }
}
