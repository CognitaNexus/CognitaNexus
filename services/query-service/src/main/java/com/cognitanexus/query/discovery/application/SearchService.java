package com.cognitanexus.query.discovery.application;

import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.query.discovery.infrastructure.SearchMapper;
import com.cognitanexus.query.platform.scope.DataNatureScope;
import com.cognitanexus.query.projection.application.ProjectionProperties;
import com.cognitanexus.sharedkernel.BusinessException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 检索用例。
 *
 * <p>依据：架构 V0.2 §9.1。
 *
 * <p>请求处理顺序不可颠倒：
 *
 * <ol>
 *   <li>先做公开资格与版本屏障校验。放在最前是因为它决定「现在能不能回答」；放在后面则会在已经
 *       读出旧内容之后才拒绝，白做一轮计算，也给了旧内容出现在日志或缓存里的机会；
 *   <li>校验分页与排序参数，超出交互上限的深翻页明确拒绝并提示缩小范围，而不是返回空列表
 *       假装没有更多结果；
 *   <li>校验 queryVersion：与「条件摘要 + 已发布修订」不符时返回 409，让前端保留条件并刷新，
 *       而不是在数据已经变化的情况下继续沿用旧分页（那会不重不漏地跨过两个快照）；
 *   <li>在一个只读快照内先取总数再取当页，两者使用同一个 generation 与同一组条件。
 * </ol>
 */
@Service
public class SearchService {

  /** 检索规则版本。匹配与排序语义变化时必须提升，客户端据此判断结果可比性。 */
  public static final String RULE_VERSION = "SEARCH_V1";

  private final SearchMapper mapper;
  private final PublicBarrierGuard guard;
  private final ProjectionProperties properties;

  public SearchService(
      SearchMapper mapper, PublicBarrierGuard guard, ProjectionProperties properties) {
    this.mapper = mapper;
    this.guard = guard;
    this.properties = properties;
  }

  /**
   * 成果检索。
   *
   * <p>必须在一个 REPEATABLE READ 只读事务里完成：一次请求要读水位、总数、列表、作者和领域，
   * 前后共五组 SQL。投影是在同一 generation 内逐事件提交的，固定 generation 并不能固定修订——
   * 默认的 READ COMMITTED 让每条语句各取一个新快照，中间若有新事件提交，就会出现
   * 「total 是 1、items 是 2，却标着同一个 queryVersion」的响应。
   *
   * <p>只加 {@code readOnly=true} 不够：它只声明不写，不改变可见性规则。这里的隔离级别才是
   * 「同一快照」的实际来源。事务是单请求级、只读、立即结束，不持有跨请求快照。
   *
   * <p>公开资格屏障仍在本事务内第一步读取：撤权发生后新发起的请求看到的仍是同一次快照内的
   * 屏障结果，不会出现「屏障用旧值放行、列表用新值拒绝」的错位。
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> search(SearchQuery query) {
    PublicBarrierGuard.BarrierSnapshot barrier = guard.assertPubliclyServed();
    validatePaging(query);
    validateSort(query);
    assertVersion(query, barrier);
    // 回显给调用方的版本必须与校验用的是同一个值，否则前端拿到的 token 与下一次比较的对象不同。
    String expectedVersion = query.versionFor(barrier.publishedRevision());

    SearchCriteria criteria = criteriaOf(query);
    String generation = barrier.activeGeneration();

    long total = mapper.countHits(generation, criteria);
    List<SearchMapper.AchievementHit> hits =
        mapper.listHits(generation, criteria, query.size(), query.offset());
    List<String> ids = hits.stream().map(SearchMapper.AchievementHit::achievementId).toList();

    Map<String, List<String>> authors = new LinkedHashMap<>();
    if (!ids.isEmpty()) {
      for (SearchMapper.NameRow row : mapper.listAuthorNames(generation, ids)) {
        authors.computeIfAbsent(row.achievementId(), key -> new ArrayList<>()).add(row.name());
      }
    }
    Map<String, List<Map<String, Object>>> topics = new LinkedHashMap<>();
    if (!ids.isEmpty()) {
      for (SearchMapper.TopicRow row : mapper.listTopics(generation, ids)) {
        topics.computeIfAbsent(row.achievementId(), key -> new ArrayList<>()).add(topicView(row));
      }
    }

    List<Map<String, Object>> items = new ArrayList<>(hits.size());
    for (SearchMapper.AchievementHit hit : hits) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", hit.achievementId());
      item.put("type", hit.type());
      item.put("title", hit.title());
      item.put("dataNature", hit.dataNature());
      item.put("visibility", hit.visibility());
      item.put("effectiveStatus", hit.effectiveStatus());
      item.put("publicationYear", hit.publicationYear());
      item.put("doi", hit.doi());
      item.put("venue", hit.venue());
      item.put("citationCount", hit.citationCount());
      item.put("citationSource", hit.citationSource());
      item.put("authors", authors.getOrDefault(hit.achievementId(), List.of()));
      item.put("topics", topics.getOrDefault(hit.achievementId(), List.of()));
      item.put("relevance", hit.relevance());
      items.add(item);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("page", query.page());
    data.put("size", query.size());
    data.put("total", total);
    data.put("totalRelation", "exact");
    data.put("queryVersion", expectedVersion);
    data.put("ruleVersion", RULE_VERSION);
    data.put("publishedRevision", barrier.publishedRevision());
    data.put("generatedAt", Instant.now());
    data.put("appliedSort", normalizedSort(query.sort()));
    data.put("maxOffset", properties.searchMaxOffset());
    // 作用域必须回显生效值而不是回显请求参数：参数缺省时生效范围是 REAL，
    // 直接回显 null 会让这个字段从响应里消失，调用方无从判断自己拿到的是什么口径。
    String effectiveNature = normalizeNature(query.dataNature());
    data.put("dataNatureScope", DataNatureScope.label(effectiveNature));
    data.put("scopeNote", natureNote(effectiveNature));
    return data;
  }

  /** 学者检索：只统计合格关系在公开生效成果上的出现次数。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> searchScholars(SearchQuery query) {
    PublicBarrierGuard.BarrierSnapshot barrier = guard.assertPubliclyServed();
    validatePaging(query);
    // 学者与成果对「过期 token」必须采取同一策略：成果拒绝、学者放行，会让前端只有在学者页
    // 才能看到「版本不一致却翻页成功」的组合，而那一页恰恰也是分页的。
    assertVersion(query, barrier);
    if (query.sort() != null && !SearchQuery.SORT_SCHOLAR_ACHIEVEMENTS_DESC.equals(query.sort())) {
      // 支撑排序的 SQL 只有一种：按公开合格成果数降序、同数按学者标识稳定排序。
      // 接受一个不生效的排序参数，比直接拒绝更糟——用户会以为结果已按他选的顺序排列。
      throw com.cognitanexus.sharedkernel.BusinessException.invalidArgument(
          "学者检索当前只支持 " + SearchQuery.SORT_SCHOLAR_ACHIEVEMENTS_DESC + "（按公开成果数降序）");
    }
    SearchCriteria criteria = criteriaOf(query);
    String generation = barrier.activeGeneration();
    long total = mapper.countScholars(generation, criteria);
    List<Map<String, Object>> items = new ArrayList<>();
    for (SearchMapper.ScholarHit hit :
        mapper.searchScholars(generation, criteria, query.size(), query.offset())) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("scholarId", hit.scholarId());
      item.put("scholarName", hit.scholarName());
      item.put("qualifiedAchievementCount", hit.achievementCount());
      item.put("firstYear", hit.firstYear());
      items.add(item);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("page", query.page());
    data.put("size", query.size());
    data.put("total", total);
    data.put("totalRelation", "exact");
    data.put("queryVersion", query.versionFor(barrier.publishedRevision()));
    data.put("ruleVersion", RULE_VERSION);
    data.put("publishedRevision", barrier.publishedRevision());
    data.put("generatedAt", Instant.now());
    data.put("countingNote", "只统计 AUTO 与 SELF_CONFIRMED 关系在公开生效成果上的成果数；待核验与已排除关系不计入");
    return data;
  }

  /** 筛选候选：领域与机构。机构名来自成果发生时的机构归属。 */
  public Map<String, Object> facets(String dataNature, int limit) {
    PublicBarrierGuard.BarrierSnapshot barrier = guard.assertPubliclyServed();
    String generation = barrier.activeGeneration();
    String nature = normalizeNature(dataNature);
    int safeLimit = Math.min(Math.max(limit, 1), 500);
    Map<String, Object> data = new LinkedHashMap<>();
    List<Map<String, Object>> topicViews = new ArrayList<>();
    for (SearchMapper.TopicFacet facet : mapper.listTopicFacets(generation, nature, safeLimit)) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("topicId", facet.topicId());
      view.put("canonicalName", facet.canonicalName());
      view.put("achievementCount", facet.achievementCount());
      // 来源与推断分开计数：合计是总量，但读者要能看出这份量里有多少是元数据事实、
      // 有多少是本系统按词表推出来的。只给总数会让分面看起来比实际更有把握。
      view.put("sourceProvided", facet.sourceCount());
      view.put("systemInferred", facet.inferredCount());
      topicViews.add(view);
    }
    data.put("topics", topicViews);
    data.put("organizations", mapper.listOrganizationFacets(generation, nature, safeLimit));
    data.put("dataNatureScope", DataNatureScope.label(nature));
    data.put("scopeNote", natureNote(nature));
    return data;
  }

  private void validatePaging(SearchQuery query) {
    if (query.page() < 1) {
      throw BusinessException.invalidArgument("页码必须从 1 开始");
    }
    int maxSize = properties.searchMaxPageSize();
    if (query.size() < 1 || query.size() > maxSize) {
      throw BusinessException.invalidArgument("每页条数必须在 1 到 " + maxSize + " 之间");
    }
    if (query.offset() > properties.searchMaxOffset()) {
      throw BusinessException.invalidArgument(
          "交互式翻页偏移上限为 " + properties.searchMaxOffset() + " 条，请缩小检索范围（例如增加年份或领域条件）而不是继续深翻页");
    }
  }

  /**
   * 成果检索的排序白名单。
   *
   * <p>与分页校验分开：学者检索只有一种受支持的排序，两套白名单混在同一个方法里会让
   * 「学者传 achievements_desc」被成果白名单拒掉，错误信息还指向一堆没有意义的取值。
   */
  private static void validateSort(SearchQuery query) {
    if (query.sort() != null && !SearchQuery.allowedSorts().contains(query.sort())) {
      throw BusinessException.invalidArgument(
          "排序字段必须是以下之一：" + String.join("、", SearchQuery.allowedSorts()));
    }
  }

  /**
   * 校验分页携带的查询版本。
   *
   * <p>两个检索入口共用同一条判据：条件摘要或发布修订任一变化，旧 token 都作废。
   */
  private static void assertVersion(SearchQuery query, PublicBarrierGuard.BarrierSnapshot barrier) {
    String expectedVersion = query.versionFor(barrier.publishedRevision());
    if (query.queryVersion() != null
        && !query.queryVersion().isBlank()
        && !expectedVersion.equals(query.queryVersion())) {
      throw new BusinessException(
          com.cognitanexus.sharedkernel.ErrorCode.QUERY_VERSION_CHANGED,
          "查询版本已变化（条件或已发布数据发生变更）。请保留当前条件重新发起第一页请求。" + "服务端不会在版本不一致时继续分页，以免把两个快照的结果混在同一个列表里");
    }
  }

  private static SearchCriteria criteriaOf(SearchQuery query) {
    return new SearchCriteria(
        query.keywordNorm(),
        SearchQuery.likePatternOf(query.keywordNorm()),
        query.type(),
        query.yearFrom(),
        query.yearTo(),
        query.topicId(),
        query.organization(),
        normalizeNature(query.dataNature()),
        query.sort());
  }

  /**
   * 数据性质范围归一。
   *
   * <p>返回 null 表示不限制（显式演示口径）。
   *
   * <p><b>缺省必须是 REAL，不能是「不限制」。</b>这两者在只有真实数据的库上表现完全一样，
   * 所以很容易写错也测不出来；一旦模拟数据入库，差异立刻变成内容层面的错误：默认的公开检索
   * 会把模拟成果混进真实结果里，而调用方从响应上也看不出自己拿到的是什么口径。判据用
   * 「调用方是否显式要求扩大范围」而不是「参数是否为空」——只有显式 ALL 才放宽。
   */
  private static String normalizeNature(String raw) {
    return DataNatureScope.normalize(raw);
  }

  /**
   * 把生效范围写成一句话，供响应携带。
   *
   * <p>作用域字段必须<b>总是</b>出现在响应里：省略它等于让调用方无法判断自己拿到的是真实口径
   * 还是演示口径。在一个把「模拟数据不得冒充真实」写进红线的系统里，「没说」会被读成「是真实的」。
   */
  private static String natureNote(String scope) {
    return DataNatureScope.note(scope);
  }

  private static String normalizedSort(String sort) {
    return sort == null || sort.isBlank() ? SearchQuery.SORT_RELEVANCE : sort;
  }

  private static Map<String, Object> topicView(SearchMapper.TopicRow row) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("topicId", row.topicId());
    view.put("canonicalName", row.canonicalName());
    // 检索结果卡片与详情页读的是同一套领域，标注必须一起给：结果列表里不标，用户就要点进去
    // 才知道这个领域是来源给的还是系统推断的——而列表恰恰是他最先看到的地方。
    view.put("assignmentKind", row.assignmentKind());
    if (row.confidence() != null) {
      view.put("confidence", row.confidence());
    }
    return view;
  }
}
