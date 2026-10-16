package com.cognitanexus.query.analytics.application;

import com.cognitanexus.query.analytics.infrastructure.AnalyticsMapper;
import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.query.platform.scope.DataNatureScope;
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

  private static final int TOP_LIMIT = 20;
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
    data.put("topTopics", rows(mapper.topTopics(generation, nature, TOP_LIMIT), Map.of()));
    data.put("topVenues", rows(mapper.topVenues(generation, nature, TOP_LIMIT), Map.of()));
    data.put("citationsBySource", citations(mapper.citationsBySource(generation, nature)));

    data.put("scope", scope(nature));
    data.put("countingRules", countingRules());
    data.put("ruleVersion", RULE_VERSION);
    data.put("publishedRevision", barrier.publishedRevision());
    data.put("generatedAt", Instant.now());
    return data;
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
    rules.put("topics", "领域为多对多，单项计数互不排斥，多选合计可超过总量");
    rules.put("citations", "按来源分别展示，不跨来源相加，不提供总引用");
    rules.put("coauthorPairs", "仅真实、公开、生效、关系合格的共同论文建边，边强度为不同合格论文数");
    return rules;
  }

  /** 委托共享规则，不在此另立一份。 */
  private static String normalizeNature(String raw) {
    return DataNatureScope.normalize(raw);
  }
}
