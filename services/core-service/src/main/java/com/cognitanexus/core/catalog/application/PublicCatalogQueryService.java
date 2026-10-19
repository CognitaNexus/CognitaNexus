package com.cognitanexus.core.catalog.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ContributionRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ScholarRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.TopicRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.TopicRepository.TopicAssignment;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.AchievementDetails;
import com.cognitanexus.core.catalog.domain.Contribution;
import com.cognitanexus.core.catalog.domain.Scholar;
import com.cognitanexus.core.catalog.domain.ScholarProfile;
import com.cognitanexus.core.integrity.application.IntegrityNoticeRepository;
import com.cognitanexus.core.integrity.application.IntegrityNoticeViews;
import com.cognitanexus.core.integrity.domain.IntegrityNotice;
import com.cognitanexus.core.integrity.domain.IntegritySummary;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.RelationState;
import com.cognitanexus.sharedkernel.Visibility;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

  /** 主要合作者列表的展示上限。上限本身就是一种口径声明：这是「主要合作者」，不是完整名单。 */
  private static final int COAUTHOR_LIMIT = 10;

  private final AchievementRepository achievements;
  private final ScholarRepository scholars;
  private final ContributionRepository contributions;
  private final TopicRepository topics;
  private final IntegrityNoticeRepository integrityNotices;

  public PublicCatalogQueryService(
      AchievementRepository achievements,
      ScholarRepository scholars,
      ContributionRepository contributions,
      TopicRepository topics,
      IntegrityNoticeRepository integrityNotices) {
    this.achievements = achievements;
    this.scholars = scholars;
    this.contributions = contributions;
    this.topics = topics;
    this.integrityNotices = integrityNotices;
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
    PublicTarget target = requirePublicTarget(achievementId);
    Map<String, Object> view = detailViewOf(target.achievement());
    // 诚信声明是公开信息：读者有权在打开详情的第一屏就看到「这条成果是否仍然成立」。
    // 汇总与明细一起给，前端不必为了显示一个标记再发一次请求。
    attachIntegrity(view, target.achievement().id());
    if (target.redirectedFrom() != null) {
      view.put("redirectedFrom", target.redirectedFrom());
      view.put("redirectNote", "该成果已被合并，本响应指向合并后的实体");
    }
    return view;
  }

  /**
   * 解析合并重定向并校验公开资格，返回最终要返回的实体。
   *
   * <p>抽成独立方法是因为引用导出（{@code CatalogCitationService}）需要<b>完全相同的判定</b>。这套
   * 判定里有两个曾经出过问题的点——重定向必须跳到链的终点、公开资格必须作用在终点实体上；两处各写
   * 一份的话，修好一处漏掉另一处就留下一条公开读的旁路，而旁路平时看不出来，只有在「拿旧标识读一条
   * 正被撤下的内容」时才暴露。
   */
  PublicTarget requirePublicTarget(String achievementId) {
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
    return new PublicTarget(target, redirectedFrom);
  }

  /** 公开读的解析结果：最终实体，以及发生过合并重定向时请求进来的旧标识。 */
  public record PublicTarget(Achievement achievement, String redirectedFrom) {}

  /**
   * 追加诚信声明。
   *
   * <p>已撤销的声明也一并返回，但 {@code active=false}：撤销是学术记录的一部分，读者看到
   * 「曾有撤稿声明、后经复核撤销」与只看到「无声明」是完全不同的信息。前端自行决定是否折叠。
   */
  private void attachIntegrity(Map<String, Object> view, String achievementId) {
    List<IntegrityNotice> notices = integrityNotices.findByAchievement(achievementId);
    view.put("integrity", IntegrityNoticeViews.of(IntegritySummary.of(notices)));
    view.put("integrityNotices", notices.stream().map(IntegrityNoticeViews::of).toList());
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
      view.put("metrics", scholarMetricsOf(target.id()));
      view.put("redirectedFrom", scholarId);
      view.put("redirectNote", "该档案已被合并，本响应指向合并后的实体");
      return view;
    }
    Map<String, Object> view = scholarBaseView(scholar);
    view.put("contributions", scholarContributionViews(scholarId));
    view.put("metrics", scholarMetricsOf(scholarId));
    return view;
  }

  /**
   * 学者档案页的客观指标：年份分布与主要合作者。
   *
   * <p><b>只披露计数，不给任何质量结论。</b>没有 h-index、影响因子、综合评分——本平台采集的
   * 引用量来自单一来源且常常缺失，用它算出一串看起来权威的数字，等于把「来源没提供的指标」
   * 加工成「平台的评价」。红线里写的是「不得把模拟或推断当作真实结论」，指标同理。
   *
   * <p>口径统一为「关系合格 + 成果公开生效未合并」，与 {@code contributions} 列表一致：
   * 若这块数字与下方列表的条数对不上，读者只会有一个结论——数据不可信。
   */
  private Map<String, Object> scholarMetricsOf(String scholarId) {
    List<ContributionRepository.YearAmount> years = contributions.yearDistributionOf(scholarId);
    long undated = contributions.countUndatedWorksOf(scholarId);
    long retracted = contributions.countRetractedWorksOf(scholarId);
    long known = years.stream().mapToLong(ContributionRepository.YearAmount::amount).sum();
    List<ContributionRepository.CoauthorCount> coauthors =
        contributions.topCoauthorsOf(scholarId, COAUTHOR_LIMIT);

    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("worksTotal", known + undated);
    metrics.put("worksWithYear", known);
    metrics.put("worksWithoutYear", undated);
    // 撤稿成果单列：不从 worksTotal 里扣除（见 CatalogRepositories 的同名方法注释）。
    metrics.put("worksRetracted", retracted);
    List<Map<String, Object>> byYear = new ArrayList<>();
    for (ContributionRepository.YearAmount row : years) {
      Map<String, Object> bucket = new LinkedHashMap<>();
      bucket.put("year", row.year());
      bucket.put("amount", row.amount());
      byYear.add(bucket);
    }
    metrics.put("byYear", byYear);
    List<Map<String, Object>> coauthorViews = new ArrayList<>();
    for (ContributionRepository.CoauthorCount row : coauthors) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("scholarId", row.scholarId());
      item.put("displayName", row.displayName());
      item.put("organizationName", row.organizationName());
      item.put("sharedAchievements", row.sharedAchievements());
      coauthorViews.add(item);
    }
    metrics.put("topCoauthors", coauthorViews);
    metrics.put("coauthorLimit", COAUTHOR_LIMIT);
    metrics.put("citations", citationMetricsOf(scholarId));
    metrics.put(
        "basis", "仅统计参与关系为「自动建立」或「本人确认」、且成果公开、生效、未被合并的记录；" + "待核验与存在争议的关系不计入，年份未知的成果单列而不折算到任一年份");
    metrics.put(
        "note", "这里只有可核对的计数，没有 h-index、影响因子或任何综合评分：引用量本身来自单一来源且常常缺失，" + "用它派生评级等于把来源没提供的结论说成平台的结论");
    return metrics;
  }

  /**
   * 引用量区块：累计、篇均、按发表年份的分布、来源分布。
   *
   * <p><b>三条不能含糊的事实，界面上必须原样说出来：</b>
   *
   * <ol>
   *   <li><b>缺失不是 0。</b>来源没有提供引用量的成果，既不补 0 也不算进分母。篇均的分母是
   *       「确有引用量记录的成果数」，页面同时给出缺失条数——否则一个「篇均 3.2」会被读成
   *       「平均每篇被引 3.2 次」，而它其实只是「有数据的那部分平均 3.2 次」。
   *   <li><b>这不是「每年新增引用」。</b>平台只有一个随时间滚动的当前累计值，没有按年份的
   *       引用增长历史。按发表年份分组得到的是「各年发表的成果如今累计被引多少」，把它读成
   *       「引用量逐年增长/衰退」是错的，因此字段名、图表标题与说明都用「按发表年份」而不是
   *       「引用趋势」。
   *   <li><b>跨来源不可严格相加。</b>Crossref 与 OpenAlex 的计数口径不同，平台每篇只取一个
   *       来源的值；把它们加起来得到的数只能作为量级参考，页面必须给出每个来源各覆盖多少篇。
   * </ol>
   *
   * <p>刻意不派生 h-index、影响因子或任何综合评分：这些指标需要完整、可比的引用数据集，
   * 而这里的覆盖是有缺失的、来源是混杂的。缺数据时算出来的「高水平指标」不是观察，是编造。
   */
  private Map<String, Object> citationMetricsOf(String scholarId) {
    ContributionRepository.CitationSummary summary = contributions.citationSummaryOf(scholarId);
    List<ContributionRepository.CitationYear> years = contributions.citationByYearOf(scholarId);
    List<ContributionRepository.CitationSourceCount> sources =
        contributions.citationSourcesOf(scholarId);

    long works = Math.max(0L, summary.works());
    long withCitation = Math.max(0L, summary.withCitation());
    // 缺失 = 总数 - 有引用量记录数。其中一部分是类型上就没有引用量的（专利/项目/获奖），
    // 分开说清楚：把它们混成一个「缺失 570 篇」，读者会以为数据质量有大问题。
    long missing = Math.max(0L, works - withCitation);
    long nonPaper = Math.min(missing, Math.max(0L, summary.nonPaperWorks()));

    Map<String, Object> block = new LinkedHashMap<>();
    block.put("worksTotal", works);
    block.put("worksWithCitation", withCitation);
    block.put("worksMissingCitation", missing);
    block.put("worksNotCitable", nonPaper);
    block.put("worksPaperMissingCitation", missing - nonPaper);
    block.put("totalCitations", Math.max(0L, summary.totalCitations()));
    block.put("maxWorkCitations", Math.max(0L, summary.maxCitations()));
    // 篇均只对有数据的成果求平均；一条数据都没有时给 null 而不是 0——0 会被读成「平均没人引用」。
    block.put(
        "averagePerWork",
        withCitation == 0
            ? null
            : BigDecimal.valueOf(summary.totalCitations())
                .divide(BigDecimal.valueOf(withCitation), 2, RoundingMode.HALF_UP)
                .doubleValue());
    block.put(
        "coveragePercent",
        works == 0
            ? null
            : BigDecimal.valueOf(withCitation * 100L)
                .divide(BigDecimal.valueOf(works), 1, RoundingMode.HALF_UP)
                .doubleValue());
    // 服务端统一按 non_null 序列化，null 字段会整个消失。因此凡是用「null 表示不可计算」的
    // 地方，都配一条永不缺失的说明：前端看到字段不在时才知道那是「没有数据可算」，
    // 而不是「后端忘了返回」。
    block.put(
        "averageNote",
        withCitation == 0 ? "暂无任何引用量记录，篇均无法计算；不按 0 计入" : "篇均只除以确有引用量记录的成果数，缺失的成果不进分母");

    List<Map<String, Object>> byYear = new ArrayList<>();
    for (ContributionRepository.CitationYear row : years) {
      Map<String, Object> bucket = new LinkedHashMap<>();
      bucket.put("year", row.year());
      bucket.put("works", row.works());
      bucket.put("worksWithCitation", row.withCitation());
      bucket.put("citations", row.citations());
      // 该年的篇均，同样只除以有数据的篇数；该年一条数据都没有则给 null。
      bucket.put(
          "averagePerWork",
          row.withCitation() == 0
              ? null
              : BigDecimal.valueOf(row.citations())
                  .divide(BigDecimal.valueOf(row.withCitation()), 2, RoundingMode.HALF_UP)
                  .doubleValue());
      byYear.add(bucket);
    }
    block.put("byYear", byYear);

    List<Map<String, Object>> sourceViews = new ArrayList<>();
    for (ContributionRepository.CitationSourceCount row : sources) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("source", row.source());
      item.put("works", row.works());
      sourceViews.add(item);
    }
    block.put("sources", sourceViews);

    block.put(
        "basis", "只统计参与关系为「自动建立」或「本人确认」、成果公开生效且未被合并的记录；" + "引用量取每篇论文的单一来源当前累计值，来源未提供的不补 0、不计入篇均分母");
    block.put(
        "trendNote",
        "按发表年份分组展示的是「各年发表的成果如今累计被引多少」，不是「每年新增引用」：" + "平台没有按年份的引用增长历史，把这条曲线读成逐年走势会得到错误结论");
    block.put(
        "sourceNote",
        "Crossref 与 OpenAlex 的计数口径不同，同一篇论文在两个来源下可以是两个数字；" + "平台每篇只取一个来源，因此跨来源求和只能作为量级参考");
    block.put(
        "metricNote",
        "不提供 h-index、影响因子或任何综合评分：这些指标要求完整可比的引用数据集，" + "而这里的覆盖有缺失、来源不一致，据此算出的评级不是平台观察到的事实");
    return block;
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

    // 自述区块（简介 / 主页 / 头像）与置顶代表作。
    // 为什么放在 baseView 里：合并重定向后的响应要「与直接打开终点完全同构」，
    // 若只在非重定向分支加这两块，旧链接打开的页面就会缺简介与置顶。
    ScholarProfile profile =
        scholars.findProfile(scholar.id()).orElse(ScholarProfile.empty(scholar.id()));
    Map<String, Object> profileView = new LinkedHashMap<>();
    profileView.put("homepageUrl", profile.homepageUrl());
    profileView.put("avatarUrl", profile.avatarUrl());
    profileView.put(
        "profileUpdatedAt",
        profile.profileUpdatedAt() == null ? null : profile.profileUpdatedAt().toString());
    profileView.put("note", "简介、主页与头像由本人填写，平台不代学者背书；显示名与机构经审核后变更");
    view.put("profile", profileView);
    view.put("featured", featuredViewOf(scholar.id()));
    return view;
  }

  /**
   * 公开页的置顶代表作。
   *
   * <p>逐条过 {@link #requirePublicTarget}：置顶是一条指向成果的边，成果本身可能事后被撤下或合并。
   * 不做这一步，档案页就会给出一个点进去 404 的链接——而且不会有人报错，只会觉得平台坏了。
   * 已被隐藏的条目计数上报，让「我明明置顶了三条」有处可查。
   */
  private Map<String, Object> featuredViewOf(String scholarId) {
    List<Map<String, Object>> items = new ArrayList<>();
    int unavailable = 0;
    for (String achievementId : scholars.listFeatured(scholarId)) {
      Achievement achievement;
      try {
        achievement = requirePublicTarget(achievementId).achievement();
      } catch (BusinessException ex) {
        unavailable++;
        continue;
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", achievement.id());
      item.put("type", achievement.type().name());
      item.put("typeLabel", achievement.type().label());
      item.put("title", achievement.title());
      item.put("publicationYear", achievement.publicationYear().year());
      achievements
          .findPaper(achievement.id())
          .ifPresent(
              (AchievementDetails.Paper paper) -> {
                item.put("venue", paper.venue());
                item.put("citationCount", paper.citationCount());
                item.put("citationSource", paper.citationSource());
              });
      items.add(item);
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("items", items);
    view.put("unavailableCount", unavailable);
    view.put("note", "由本人置顶的代表作；已不可公开或已被合并的条目自动隐藏并计入 unavailableCount");
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
