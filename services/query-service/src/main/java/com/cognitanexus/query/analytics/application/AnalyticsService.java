package com.cognitanexus.query.analytics.application;

import com.cognitanexus.query.analytics.infrastructure.AnalyticsMapper;
import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.query.platform.scope.DataNatureScope;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 统计用例。
 *
 * <p>依据：架构 V0.2 §9.2。响应必须自带口径：范围、时间窗、数据性质、计数规则、规则版本、发布版本
 * 与更新时间。没有口径的统计数字会被当作可比较的结论使用，而本平台的统计口径与常见理解并不一致
 * （机构按发生时机构、领域可多选且合计可超过总量、引用量不跨来源相加），所以口径必须随数据一起返回。
 *
 * <p>默认只统计真实合格数据（REAL + 公开 + 生效）。模拟数据查询必须由调用方显式要求，并在响应中
 * 明确标注演示口径。
 */
@Service
public class AnalyticsService {

  /** 统计规则版本。口径变化时必须提升。 */
  public static final String RULE_VERSION = "ANALYTICS_V1";

  /**
   * 领域洞察的规则版本。
   *
   * <p>与总览分开：领域洞察引入了「领域内机构/学者排名只统计该领域的合格关系」「代表成果按年份
   * 优先排序」这些专属口径，共用总览的版本号会让复核者以为两者可用同一把尺子核对。
   */
  public static final String TOPIC_RULE_VERSION = "TOPIC_INSIGHT_V1";

  private static final int TOP_LIMIT = 20;
  private static final int TOPIC_RANK_LIMIT = 10;
  private static final String NATURE_ALL = "ALL";

  private final AnalyticsMapper mapper;
  private final PublicBarrierGuard guard;

  public AnalyticsService(AnalyticsMapper mapper, PublicBarrierGuard guard) {
    this.mapper = mapper;
    this.guard = guard;
  }

  public Map<String, Object> overview(String dataNature) {
    PublicBarrierGuard.BarrierSnapshot barrier = guard.assertPubliclyServed();
    String generation = barrier.activeGeneration();
    String nature = normalizeNature(dataNature);

    long achievements = mapper.countAchievements(generation, nature);
    long scholars = mapper.countScholars(generation, nature);
    long contributions = mapper.countContributions(generation, nature);
    long pairs = mapper.countCoauthorPairs(generation, nature);
    long unknownOrg = mapper.countUnknownOrganization(generation, nature);

    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("achievements", achievements);
    totals.put("scholarsWithQualifiedRelation", scholars);
    totals.put("contributions", contributions);
    totals.put("coauthorPairs", pairs);
    totals.put("achievementsWithUnknownOrganization", unknownOrg);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("totals", totals);
    data.put("byType", rows(mapper.byType(generation, nature), Map.of()));
    data.putAll(byYear(mapper.byYear(generation, nature)));
    data.put("byNature", rows(mapper.byNature(generation), Map.of()));
    data.put(
        "topOrganizations", rows(mapper.topOrganizations(generation, nature, TOP_LIMIT), Map.of()));
    data.put("topTopics", topics(mapper.topTopics(generation, nature, TOP_LIMIT)));
    data.put("topVenues", rows(mapper.topVenues(generation, nature, TOP_LIMIT), Map.of()));
    data.put("citationsBySource", citations(mapper.citationsBySource(generation, nature)));

    data.put("scope", scope(nature));
    data.put("countingRules", countingRules());
    data.put("ruleVersion", RULE_VERSION);
    data.put("publishedRevision", barrier.publishedRevision());
    data.put("generatedAt", Instant.now());
    return data;
  }

  /**
   * 单个领域的洞察。
   *
   * <p>为什么要有这个端点：总览里的领域榜只给出「哪些领域大」，回答不了「这个领域里是谁在做、
   * 什么时候集中、发在哪里」。任务书要求的「科研领域热点分析」和「机构成果分类排名」正是这两问，
   * 它们需要以<b>领域</b>为主语重新聚合一次，而不是把总览的排行榜换个顺序展示。
   *
   * <p>三处口径必须写进响应：①领域是推断出来的还是来源给的（决定这份统计该被信多少）；
   * ②机构与学者排名只统计本领域内的成果，不是全局排名；③代表成果按年份优先排序，引用量只在
   * 同年内可比（不同来源的引用量口径不同）。
   *
   * @throws BusinessException {@code INVALID_ARGUMENT} 领域标识为空；{@code NOT_FOUND} 当前口径下
   *     该领域没有可公开的成果
   */
  public Map<String, Object> topicInsight(String topicId, String dataNature) {
    if (topicId == null || topicId.isBlank()) {
      throw BusinessException.invalidArgument("领域标识不能为空");
    }
    PublicBarrierGuard.BarrierSnapshot barrier = guard.assertPubliclyServed();
    String generation = barrier.activeGeneration();
    String nature = normalizeNature(dataNature);
    String id = topicId.trim();

    AnalyticsMapper.TopicHeaderRow header = mapper.topicHeader(generation, id, nature);
    // 表头是聚合查询（count/max），即使一行成果都没有也返回一条全零行而不是 null，
    // 所以存在性判据只能是「成果数为 0」，判 null 永远不成立，404 也就永远不会触发。
    if (header == null || header.achievements() == 0) {
      // 不区分「领域不存在」与「该领域没有公开成果」：区分二者等于向外确认了一条尚未公开的
      // 成果的存在。公开读路径只回答它被允许回答的那部分，用户从分面点进来时不会走到这里。
      throw new BusinessException(
          ErrorCode.NOT_FOUND,
          "领域 " + id + " 在当前数据性质口径下没有可公开的成果（领域可能不存在，或相关成果尚未公开）");
    }

    Map<String, Object> topic = new LinkedHashMap<>();
    topic.put("topicId", id);
    topic.put("canonicalName", header.canonicalName());
    // 名称可能缺失（投影时来源未给），缺失与空串在报文上无法区分，故另给布尔。
    topic.put(
        "canonicalNameKnown", header.canonicalName() != null && !header.canonicalName().isBlank());

    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("achievements", header.achievements());
    totals.put("sourceProvided", header.sourceAmount());
    totals.put("systemInferred", header.inferredAmount());
    totals.put(
        "scholarsWithQualifiedRelation", mapper.topicScholarCount(generation, id, nature));
    totals.put(
        "achievementsWithUnknownOrganization",
        mapper.topicUnknownOrganization(generation, id, nature));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("topic", topic);
    data.put("totals", totals);
    data.putAll(byYear(mapper.topicByYear(generation, id, nature)));
    data.put("byType", rows(mapper.topicByType(generation, id, nature), Map.of()));
    data.put(
        "topOrganizations",
        rows(mapper.topicOrganizations(generation, id, nature, TOPIC_RANK_LIMIT), Map.of()));
    data.put("topScholars", scholars(mapper.topicScholars(generation, id, nature, TOPIC_RANK_LIMIT)));
    data.put("topVenues", rows(mapper.topicVenues(generation, id, nature, TOPIC_RANK_LIMIT), Map.of()));
    data.put(
        "representativeAchievements",
        achievements(mapper.topicRepresentative(generation, id, nature, TOPIC_RANK_LIMIT)));
    data.put("scope", topicScope(nature, id));
    data.put("countingRules", topicCountingRules());
    data.put("ruleVersion", TOPIC_RULE_VERSION);
    data.put("publishedRevision", barrier.publishedRevision());
    data.put("generatedAt", Instant.now());
    return data;
  }

  private static List<Map<String, Object>> scholars(List<AnalyticsMapper.ScholarRow> rows) {
    List<Map<String, Object>> result = new ArrayList<>(rows.size());
    for (AnalyticsMapper.ScholarRow row : rows) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("scholarId", row.scholarId());
      view.put("name", row.name());
      view.put("nameKnown", row.name() != null && !row.name().isBlank());
      view.put("achievements", row.achievements());
      result.add(view);
    }
    return result;
  }

  private static List<Map<String, Object>> achievements(
      List<AnalyticsMapper.TopicAchievementRow> rows) {
    List<Map<String, Object>> result = new ArrayList<>(rows.size());
    for (AnalyticsMapper.TopicAchievementRow row : rows) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("achievementId", row.achievementId());
      view.put("title", row.title());
      view.put("publicationYear", row.publicationYear());
      view.put("publicationYearKnown", row.publicationYear() != null);
      view.put("citationCount", row.citationCount());
      view.put("citationCountKnown", row.citationCount() != null);
      view.put("citationSource", row.citationSource());
      view.put("citationSourceKnown", row.citationSource() != null);
      result.add(view);
    }
    return result;
  }

  private static Map<String, Object> topicScope(String nature, String topicId) {
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("dataNature", DataNatureScope.label(nature));
    scope.put("effectiveStatus", "EFFECTIVE");
    scope.put("visibility", "PUBLIC");
    scope.put("topicId", topicId);
    scope.put("note", "本页只统计该领域下的公开生效成果；" + DataNatureScope.note(nature));
    return scope;
  }

  /**
   * 领域洞察的口径说明。
   *
   * <p>与总览分开列，因为这里最容易被误读的是三件事：把领域内的排名当成全局排名、把推断出来的
   * 领域当成来源声明、把不同来源的引用量横向比较。口径必须写在数字旁边。
   */
  private static Map<String, Object> topicCountingRules() {
    Map<String, Object> rules = new LinkedHashMap<>();
    rules.put("achievements", "按去重成果实体计数，只含该领域下的公开生效成果");
    rules.put(
        "topics",
        "领域为多对多，sourceProvided 为来源元数据给出的分类，systemInferred 为本系统按受控词表推断，"
            + "两者之和等于该领域的成果总数");
    rules.put(
        "organizations",
        "只统计该领域成果的发生时机构，同成果同机构只计一次，跨机构各计一次；未知机构单列不计入排名");
    rules.put(
        "scholars", "只统计该领域成果上关系为 AUTO 与 SELF_CONFIRMED 的学者；待核验/争议/已排除不计入");
    rules.put("venues", "按领域内成果数计，不是期刊总发文量");
    rules.put(
        "representativeAchievements",
        "按发表年份降序、同年再按引用量降序；引用量按来源分别给出，不同来源之间不可比较");
    return rules;
  }

  private static Map<String, Object> byYear(List<AnalyticsMapper.YearRow> rows) {
    List<Map<String, Object>> known = new ArrayList<>();
    long unknown = 0;
    for (AnalyticsMapper.YearRow row : rows) {
      if (row.year() == null) {
        // 年份未知单列：不补 1 月 1 日，也不并入任何年份（架构 §5.4、§9.2）
        unknown = row.amount();
        continue;
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("year", row.year());
      view.put("amount", row.amount());
      known.add(view);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("byYear", known);
    result.put("achievementsWithUnknownYear", unknown);
    return result;
  }

  /**
   * 领域热点榜单。
   *
   * <p>按归属来源拆开展示，而不是只给一个总数：这份榜单里的领域有多少来自来源元数据、多少是
   * 本系统按词表推出来的，直接决定读者该给它多少信任。混在一起会让榜单看起来比实际更有把握。
   */
  private static List<Map<String, Object>> topics(List<AnalyticsMapper.TopicCountRow> rows) {
    List<Map<String, Object>> result = new ArrayList<>(rows.size());
    for (AnalyticsMapper.TopicCountRow row : rows) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("topicId", row.bucket());
      view.put("canonicalName", row.canonicalName());
      view.put(
          "canonicalNameKnown", row.canonicalName() != null && !row.canonicalName().isBlank());
      view.put("achievements", row.amount());
      view.put("sourceProvided", row.sourceAmount());
      view.put("systemInferred", row.inferredAmount());
      result.add(view);
    }
    return result;
  }

  private static List<Map<String, Object>> rows(
      List<AnalyticsMapper.CountRow> rows, Map<String, Object> extra) {
    List<Map<String, Object>> result = new ArrayList<>(rows.size());
    for (AnalyticsMapper.CountRow row : rows) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("key", row.bucket());
      view.put("achievements", row.amount());
      view.putAll(extra);
      result.add(view);
    }
    return result;
  }

  private static List<Map<String, Object>> citations(List<AnalyticsMapper.CitationRow> rows) {
    List<Map<String, Object>> result = new ArrayList<>(rows.size());
    for (AnalyticsMapper.CitationRow row : rows) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("citationSource", row.citationSource());
      view.put("achievementsWithCitation", row.achievementCount());
      view.put("citationSum", row.citationSum());
      result.add(view);
    }
    return result;
  }

  private static Map<String, Object> scope(String nature) {
    Map<String, Object> scope = new LinkedHashMap<>();
    // 回显生效范围而非请求参数：缺省时生效范围是 REAL，回显 null 会让字段消失，
    // 读者因而无法判断这份统计是真实口径还是演示口径。
    scope.put("dataNature", DataNatureScope.label(nature));
    scope.put("effectiveStatus", "EFFECTIVE");
    scope.put("visibility", "PUBLIC");
    scope.put("timeWindow", "全量（无时间窗过滤）");
    scope.put("note", DataNatureScope.note(nature));
    return scope;
  }

  private static Map<String, Object> countingRules() {
    Map<String, Object> rules = new LinkedHashMap<>();
    rules.put("achievements", "按去重成果实体计数");
    rules.put("scholarsWithQualifiedRelation", "只计 AUTO 与 SELF_CONFIRMED 关系；待核验/争议/已排除不计入");
    rules.put("organizations", "按成果发生时机构计数，同成果同机构只计一次，跨机构各计一次；未知机构单列");
    rules.put(
        "topics",
        "领域为多对多，单项计数互不排斥，多选合计可超过总量；sourceProvided 为来源元数据给出的分类，"
            + "systemInferred 为本系统按受控词表推断，两者之和等于该项总量");
    rules.put("citations", "按来源分别展示，不跨来源相加，不提供总引用");
    rules.put("coauthorPairs", "仅真实、公开、生效、关系合格的共同论文建边，边强度为不同合格论文数");
    return rules;
  }

  /** 委托共享规则，不在此另立一份。 */
  private static String normalizeNature(String raw) {
    return DataNatureScope.normalize(raw);
  }
}
