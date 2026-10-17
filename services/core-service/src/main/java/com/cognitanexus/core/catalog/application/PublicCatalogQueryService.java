package com.cognitanexus.core.catalog.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ContributionRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ScholarRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.TopicRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.TopicRepository.TopicAssignment;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.Contribution;
import com.cognitanexus.core.catalog.domain.Scholar;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.RelationState;
import com.cognitanexus.sharedkernel.Visibility;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公开详情读取。
 *
 * <p>依据：架构 V0.2 §5.4、§10.1。公开详情只返回当前可公开对象；被合并实体的标识跳到最终实体，
 * 不返回旧内容让旧链接指向错误对象。
 *
 * <p>参与关系按状态如实呈现：只有 AUTO 与 SELF_CONFIRMED 显示为确定归属，待核验/争议/已排除必须
 * 在响应中标注状态，前端不得把它们渲染成已确认合作。
 */
@Service
public class PublicCatalogQueryService {

  private final AchievementRepository achievements;
  private final ScholarRepository scholars;
  private final ContributionRepository contributions;
  private final TopicRepository topics;

  public PublicCatalogQueryService(
      AchievementRepository achievements,
      ScholarRepository scholars,
      ContributionRepository contributions,
      TopicRepository topics) {
    this.achievements = achievements;
    this.scholars = scholars;
    this.contributions = contributions;
    this.topics = topics;
  }

  /**
   * 成果详情。
   *
   * <p>被合并的旧标识要跳到链的终点，但<b>跳转不能绕过公开资格</b>：早先的实现解析出幸存实体后
   * 直接组装响应，于是任何拿到旧标识的人都能读到一条仍处于待治理或受限状态的内容——重定向成了
   * 一条公开读的旁路。资格判定必须作用在「最终要返回的那个实体」上。
   *
   * <p>跳转后的响应与直接打开终点实体完全同构（扩展字段、领域、参与关系都在），只多出
   * redirectedFrom/redirectNote 两个说明字段。只给 baseView 会让旧链接打开一个空壳页面。
   */
  @Transactional(readOnly = true)
  public Map<String, Object> achievementDetail(String achievementId) {
    Achievement achievement =
        achievements
            .findById(achievementId)
            .orElseThrow(() -> BusinessException.notFound("成果不存在或尚不可见"));

    String redirectedFrom = null;
    Achievement target = achievement;
    if (achievement.merged()) {
      String survivor =
          achievements.resolveSurvivorId(achievementId).orElse(achievement.mergedIntoId());
      target =
          achievements
              .findById(survivor)
              .orElseThrow(() -> BusinessException.notFound("成果不存在或尚不可见"));
      redirectedFrom = achievementId;
    }

    if (!target.visibility().publiclyVisible() || !target.effectiveStatus().publiclyEligible()) {
      // 不存在与不可见对外不可区分，避免通过状态码探测受限对象
      throw BusinessException.notFound("成果不存在或尚不可见");
    }

    Map<String, Object> view = detailViewOf(target);
    if (redirectedFrom != null) {
      view.put("redirectedFrom", redirectedFrom);
      view.put("redirectNote", "该成果已被合并，本响应指向合并后的实体");
    }
    return view;
  }

  /** 按实体组装详情视图。调用方已确认该实体具备公开资格。 */
  private Map<String, Object> detailViewOf(Achievement achievement) {
    String achievementId = achievement.id();
    Map<String, Object> view = baseView(achievement);
    achievements
        .findPaper(achievementId)
        .ifPresent(
            paper -> {
              Map<String, Object> detail = new LinkedHashMap<>();
              detail.put("doi", paper.doi());
              detail.put("venue", paper.venue());
              detail.put(
                  "publishedDate",
                  paper.publishedDate() == null ? null : paper.publishedDate().toString());
              detail.put("preprintStatus", paper.preprintStatus());
              detail.put("citationCount", paper.citationCount());
              detail.put("citationSource", paper.citationSource());
              detail.put("abstractText", paper.abstractText());
              detail.put(
                  "citationNote",
                  paper.citationCount() == null ? "来源未提供引用量" : "引用量按单一来源展示，不与其它来源相加");
              view.put("paper", detail);
            });
    achievements
        .findPatent(achievementId)
        .ifPresent(
            patent -> {
              Map<String, Object> detail = new LinkedHashMap<>();
              detail.put("applicationNo", patent.applicationNo());
              detail.put("publicationNo", patent.publicationNo());
              detail.put("ipcCodes", patent.ipcCodes());
              detail.put("applicantNames", patent.applicantNames());
              detail.put(
                  "filingDate",
                  patent.filingDate() == null ? null : patent.filingDate().toString());
              // 公开日必须一并给出：它与申请日是两个不同事实，只给申请日会让详情页
              // 把「申请」误读成「授权公开」。
              detail.put(
                  "publicationDate",
                  patent.publicationDate() == null ? null : patent.publicationDate().toString());
              view.put("patent", detail);
            });
    achievements
        .findProject(achievementId)
        .ifPresent(
            project -> {
              Map<String, Object> detail = new LinkedHashMap<>();
              detail.put("projectNo", project.projectNo());
              detail.put("leaderName", project.leaderName());
              detail.put("fundingAgency", project.fundingAgency());
              detail.put("projectCategory", project.projectCategory());
              // 不展示经费金额：默认不采集也不公开（BR-IMP-11）
              detail.put("fundingNote", "本平台不展示经费金额");
              view.put("project", detail);
            });
    achievements
        .findAward(achievementId)
        .ifPresent(
            award -> {
              Map<String, Object> detail = new LinkedHashMap<>();
              detail.put("awardName", award.awardName());
              detail.put("awardYear", award.awardYear());
              detail.put("awardLevel", award.awardLevel());
              detail.put("grantingBody", award.grantingBody());
              view.put("award", detail);
            });

    view.put("topics", topicViews(achievementId));
    view.put("contributors", contributorViews(achievementId));
    List<Map<String, Object>> sources = sourceViews(achievementId);
    view.put("sources", sources);
    view.put("sourceCount", sources.size());
    return view;
  }

  /**
   * 可公开的来源清单（FR02）。
   *
   * <p>只列许可仍为 {@code GRANTED} 的来源：撤权之后那条来源不再构成「可公开依据」，
   * 把它继续列在公开详情里会让人以为该成果仍由它背书。撤权的历史留在治理台账与审计里，
   * 不属于公开视图。
   *
   * <p>同时给出 {@code sourceCount}：前端要能说「本条元数据由 N 处来源交叉确认」，
   * 而不是只凭列表长度自己数——列表可能因为过滤而变短，数字应当说明它数的是什么。
   */
  private List<Map<String, Object>> sourceViews(String achievementId) {
    List<Map<String, Object>> views = new ArrayList<>();
    for (AchievementRepository.SourceRef source : achievements.sourcesOf(achievementId)) {
      if (!"GRANTED".equals(source.displayLicenseStatus())) {
        continue;
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("sourceCode", source.sourceCode());
      view.put("displayName", source.displayName());
      view.put("sourceRecordId", source.sourceRecordId());
      // 外链可能为空——来源没给地址时前端显示「来源未提供可跳转地址」，
      // 不许用 base_url 拼一个看起来能点、实际 404 的链接（FR02 只承诺跳原站）。
      view.put("externalUrl", source.externalUrl());
      view.put("licenseScope", source.licenseScope());
      view.put("dataNature", source.dataNature());
      view.put(
          "sourceUpdatedAt",
          source.sourceUpdatedAt() == null ? null : source.sourceUpdatedAt().toString());
      view.put("receivedAt", source.receivedAt() == null ? null : source.receivedAt().toString());
      views.add(view);
    }
    return views;
  }

  /** 学者门户详情。未被认领的档案同样可读（AC-IMPL-FR03-01）。 */
  @Transactional(readOnly = true)
  public Map<String, Object> scholarDetail(String scholarId) {
    Scholar scholar =
        scholars.findById(scholarId).orElseThrow(() -> BusinessException.notFound("学者不存在"));
    if (scholar.merged()) {
      String survivor = scholars.resolveSurvivorId(scholarId).orElse(scholar.mergedIntoId());
      Scholar target =
          scholars.findById(survivor).orElseThrow(() -> BusinessException.notFound("学者不存在"));
      // 与成果同理：跳转要落到终点的完整视图，而不是一个只有姓名与单位的空壳。
      Map<String, Object> view = scholarBaseView(target);
      view.put("contributions", scholarContributionViews(target.id()));
      view.put("redirectedFrom", scholarId);
      view.put("redirectNote", "该档案已被合并，本响应指向合并后的实体");
      return view;
    }
    Map<String, Object> view = scholarBaseView(scholar);
    view.put("contributions", scholarContributionViews(scholarId));
    return view;
  }

  private Map<String, Object> baseView(Achievement achievement) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", achievement.id());
    view.put("type", achievement.type().name());
    view.put("typeLabel", achievement.type().label());
    view.put("title", achievement.title());
    view.put("dataNature", achievement.dataNature().name());
    view.put("visibility", achievement.visibility().name());
    view.put("effectiveStatus", achievement.effectiveStatus().name());
    view.put("publicationYear", achievement.publicationYear().year());
    view.put(
        "yearPrecision",
        achievement.publicationYear().precision() == null
            ? null
            : achievement.publicationYear().precision().name());
    view.put("version", achievement.version());
    view.put(
        "dataNatureNote",
        achievement.dataNature().eligibleForRealityView() ? null : "该条目的数据性质不是真实来源数据，不计入默认现实统计");
    return view;
  }

  private Map<String, Object> scholarBaseView(Scholar scholar) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", scholar.id());
    view.put("displayName", scholar.displayName());
    view.put("organizationName", scholar.organizationName());
    view.put("researchSummary", scholar.researchSummary());
    view.put("claimedStatus", scholar.claimedStatus().name());
    view.put("dataNature", scholar.dataNature().name());
    view.put("version", scholar.version());
    view.put(
        "claimNote",
        scholar.claimedStatus() == Scholar.ClaimedStatus.CLAIMED
            ? "门户已有管理者；认领仅授予管理权，不代表成果真实性背书"
            : null);
    return view;
  }

  private List<Map<String, Object>> topicViews(String achievementId) {
    List<Map<String, Object>> views = new ArrayList<>();
    for (TopicAssignment assignment : topics.listAssignmentsOf(achievementId)) {
      topics
          .findTopic(assignment.topicId())
          .ifPresent(
              topic -> {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("id", topic.id());
                view.put("canonicalName", topic.canonicalName());
                view.put("sourceCategory", topic.sourceCategory());
                // 归属来源必须随详情一并给出：公开详情页与检索结果读到的是同一个领域，
                // 若这里不标是来源分类还是系统推断，界面就只能两处都不标。
                view.put("assignmentKind", assignment.assignmentKind());
                if (assignment.confidence() != null) {
                  view.put("confidence", assignment.confidence().doubleValue());
                }
                views.add(view);
              });
    }
    return views;
  }

  private List<Map<String, Object>> contributorViews(String achievementId) {
    List<Map<String, Object>> views = new ArrayList<>();
    for (Contribution contribution : contributions.listByAchievement(achievementId)) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("scholarId", contribution.scholarId());
      view.put("role", contribution.role());
      view.put("authorOrder", contribution.authorOrder());
      view.put("relationState", contribution.relationState().name());
      view.put("qualified", contribution.qualifiedForGraph());
      view.put("relationNote", qualifiedNote(contribution.relationState()));
      scholars
          .findById(contribution.scholarId())
          .ifPresent(
              scholar -> {
                view.put("displayName", scholar.displayName());
                view.put("organizationName", scholar.organizationName());
              });
      views.add(view);
    }
    return views;
  }

  private List<Map<String, Object>> scholarContributionViews(String scholarId) {
    List<Map<String, Object>> views = new ArrayList<>();
    for (Contribution contribution : contributions.listByScholar(scholarId)) {
      Achievement achievement = achievements.findById(contribution.achievementId()).orElse(null);
      if (achievement == null
          || !achievement.visibility().publiclyVisible()
          || !achievement.effectiveStatus().publiclyEligible()
          || achievement.merged()) {
        continue;
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("achievementId", achievement.id());
      view.put("title", achievement.title());
      view.put("type", achievement.type().name());
      view.put("publicationYear", achievement.publicationYear().year());
      view.put("relationState", contribution.relationState().name());
      view.put("qualified", contribution.qualifiedForGraph());
      view.put("relationNote", qualifiedNote(contribution.relationState()));
      views.add(view);
    }
    return views;
  }

  private String qualifiedNote(RelationState state) {
    return switch (state) {
      case AUTO -> "由稳定标识与相容证据自动建立的参与关系";
      case SELF_CONFIRMED -> "本人已确认的参与关系";
      case PENDING_VERIFY -> "待核验：证据不足，不作为确定合作";
      case DISPUTED -> "存在争议：不作为确定合作";
      case EXCLUDED -> "已排除：经审核判定该参与关系不成立";
    };
  }

  /** 公开目录资格自检：用于管理端核对可见性问题。 */
  @Transactional(readOnly = true)
  public boolean publiclyVisible(String achievementId) {
    return achievements
        .findById(achievementId)
        .filter(a -> a.visibility() == Visibility.PUBLIC)
        .filter(a -> a.effectiveStatus().publiclyEligible())
        .filter(a -> !a.merged())
        .isPresent();
  }
}
