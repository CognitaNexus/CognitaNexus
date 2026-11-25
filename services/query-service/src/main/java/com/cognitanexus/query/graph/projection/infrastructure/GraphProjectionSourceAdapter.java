package com.cognitanexus.query.graph.projection.infrastructure;

import com.cognitanexus.query.graph.domain.GraphAuthorship;
import com.cognitanexus.query.graph.domain.GraphScholarNode;
import com.cognitanexus.query.graph.projection.application.GraphProjectionSource;
import com.cognitanexus.query.graph.projection.domain.GraphProjectedWork;
import com.cognitanexus.query.graph.projection.infrastructure.GraphProjectionSourceMapper.ContributionRow;
import com.cognitanexus.query.graph.projection.infrastructure.GraphProjectionSourceMapper.OrgRow;
import com.cognitanexus.query.graph.projection.infrastructure.GraphProjectionSourceMapper.QualifiedRow;
import com.cognitanexus.query.graph.projection.infrastructure.GraphProjectionSourceMapper.TopicRow;
import com.cognitanexus.query.graph.projection.infrastructure.GraphProjectionSourceMapper.WorkRow;
import com.cognitanexus.query.platform.persistence.JsonCodec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link GraphProjectionSource} 的 PostgreSQL 实现：把四张读投影表拼装为 {@link GraphProjectedWork}。
 *
 * <p>三条映射规则在这里定义，其余代码只能复用不能被替代：
 *
 * <ol>
 *   <li><b>合格性</b>：{@code visibility='PUBLIC' AND effective_status='EFFECTIVE'} 才进图。数据性质
 *       不参与合格性判断——模拟成果同样进图，只在查询时按 {@code data_origin} 隔离。把模拟成果
 *       挡在图外，会让「演示口径」与「真实口径」在图与 PG 上变成两种规则；
 *   <li><b>参与资格</b>：只有 {@code AUTO} / {@code SELF_CONFIRMED} 建边。{@link GraphAuthorship}
 *       构造器会拒绝其它状态，因此一次数据异常会让该成果的同步失败（PERMANENT）而不是静默剔除；
 *   <li><b>领域/机构</b>：完整集合（去重排序），既不是「第一个」，也不是学者档案的当前机构。
 *       这是 ADR-005 §6.2 筛选等价的前提。
 * </ol>
 *
 * <p>已知口径限制：PG 没有「学者级数据性质」，{@code Scholar.data_origin} 用该成果的
 * {@code data_nature} 标注，仅用于展示；所有筛选都以成果/边上的 {@code data_origin} 为准。
 * 同一学者同时出现在真实与模拟成果中时，其节点上的标注取决于最后一次写入，这不影响查询结果。
 */
public class GraphProjectionSourceAdapter implements GraphProjectionSource {

  /** 单次 IN 列表上限：避免一条 SQL 携带上千个参数（PostgreSQL 参数上限与计划时间都会受影响）。 */
  private static final int IN_CHUNK = 500;

  private final GraphProjectionSourceMapper mapper;
  private final JsonCodec json;

  public GraphProjectionSourceAdapter(GraphProjectionSourceMapper mapper) {
    this(mapper, new JsonCodec(new com.fasterxml.jackson.databind.ObjectMapper()));
  }

  public GraphProjectionSourceAdapter(GraphProjectionSourceMapper mapper, JsonCodec json) {
    this.mapper = mapper;
    this.json = json;
  }

  @Override
  public Optional<Stage> readStage(String consumerId) {
    GraphProjectionSourceMapper.StageRow row = mapper.readStage(consumerId);
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(new Stage(row.generation(), row.publishedRevision()));
  }

  @Override
  public List<GraphProjectedWork> pageWorks(String generation, String afterWorkId, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("分页大小必须为正：" + limit);
    }
    return assemble(generation, mapper.pageWorks(generation, afterWorkId, limit));
  }

  @Override
  public List<GraphProjectedWork> loadWorks(
      String generation, java.util.Collection<String> workIds) {
    Set<String> ids = distinctIds(workIds);
    if (ids.isEmpty()) {
      return List.of();
    }
    List<WorkRow> rows = new ArrayList<>();
    for (List<String> chunk : chunks(ids)) {
      rows.addAll(mapper.loadWorks(generation, chunk));
    }
    return assemble(generation, rows);
  }

  @Override
  public Optional<GraphProjectedWork> loadWork(String generation, String workId) {
    if (workId == null || workId.isBlank()) {
      return Optional.empty();
    }
    List<GraphProjectedWork> works = loadWorks(generation, List.of(workId.trim()));
    return works.isEmpty() ? Optional.empty() : Optional.of(works.get(0));
  }

  @Override
  public long countEligibleWorks(String generation, String dataOrigin) {
    return mapper.countEligibleWorks(generation, dataOrigin);
  }

  @Override
  public long countQualifiedContributions(String generation, String dataOrigin) {
    return mapper.countQualifiedContributions(generation, dataOrigin);
  }

  @Override
  public long countContributors(String generation, String dataOrigin) {
    return mapper.countContributors(generation, dataOrigin);
  }

  @Override
  public long countMaterializedPairs(String generation, int threshold, String dataOrigin) {
    return mapper.countMaterializedPairs(generation, threshold, dataOrigin);
  }

  @Override
  public List<String> pageEligibleWorkIds(
      String generation, String afterWorkId, int limit, String dataOrigin) {
    return mapper.pageEligibleWorkIds(generation, afterWorkId, limit, dataOrigin).stream()
        .map(GraphProjectionSourceMapper.IdRow::workId)
        .toList();
  }

  @Override
  public List<ContributionView> loadQualifiedContributions(
      String generation, java.util.Collection<String> workIds) {
    Set<String> ids = distinctIds(workIds);
    if (ids.isEmpty()) {
      return List.of();
    }
    List<ContributionView> views = new ArrayList<>();
    for (List<String> chunk : chunks(ids)) {
      for (QualifiedRow row : mapper.loadQualifiedContributions(generation, chunk)) {
        views.add(new ContributionView(row.achievementId(), row.scholarId()));
      }
    }
    return views;
  }

  @Override
  public List<AppliedEvent> listAppliedEventsAfter(String consumerId, long revision, int limit) {
    return mapper.listAppliedEventsAfter(consumerId, revision, limit).stream()
        .map(
            row ->
                new AppliedEvent(
                    row.id(),
                    row.eventType(),
                    row.catalogRevision(),
                    json.readMap(row.payloadJson())))
        .toList();
  }

  @Override
  public List<String> achievementIdsOfScholar(String generation, String scholarId) {
    return mapper.achievementIdsOfScholar(generation, scholarId).stream()
        .map(GraphProjectionSourceMapper.IdRow::workId)
        .toList();
  }

  /**
   * 把成果行与四张派生表拼装为完整视图。
   *
   * <p>批量取派生表而不是逐条查：回填每批 500 条成果，逐条查会变成 1500 次往返，一批的耗时
   * 从几十毫秒涨到几十秒，而超时与重试又会把「慢」放大成「失败」。
   */
  private List<GraphProjectedWork> assemble(String generation, List<WorkRow> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    List<String> ids = new ArrayList<>(rows.size());
    for (WorkRow row : rows) {
      ids.add(row.workId());
    }
    Map<String, List<ContributionRow>> contributions = new HashMap<>();
    Map<String, Set<String>> topics = new HashMap<>();
    Map<String, Set<String>> organizations = new HashMap<>();
    for (List<String> chunk : chunks(ids)) {
      for (ContributionRow row : mapper.loadContributions(generation, chunk)) {
        contributions.computeIfAbsent(row.achievementId(), key -> new ArrayList<>()).add(row);
      }
      for (TopicRow row : mapper.loadTopics(generation, chunk)) {
        topics
            .computeIfAbsent(row.achievementId(), key -> new LinkedHashSet<>())
            .add(row.topicId());
      }
      for (OrgRow row : mapper.loadOrganizations(generation, chunk)) {
        organizations
            .computeIfAbsent(row.achievementId(), key -> new LinkedHashSet<>())
            .add(row.organization());
      }
    }

    List<GraphProjectedWork> works = new ArrayList<>(rows.size());
    for (WorkRow row : rows) {
      boolean eligible =
          "PUBLIC".equals(row.visibility()) && "EFFECTIVE".equals(row.effectiveStatus());
      List<GraphAuthorship> authorships = new ArrayList<>();
      Map<String, String> scholarNames = new LinkedHashMap<>();
      for (ContributionRow contribution : contributions.getOrDefault(row.workId(), List.of())) {
        if (!GraphAuthorship.QUALIFIED_STATES.contains(contribution.relationState())) {
          // 未确认/争议/已排除的关系不进图，也不建合作边（BR-IMP-08）。
          continue;
        }
        authorships.add(
            new GraphAuthorship(
                contribution.scholarId(),
                contribution.relationState(),
                contribution.authorOrder()));
        if (contribution.scholarName() != null && !contribution.scholarName().isBlank()) {
          scholarNames.putIfAbsent(contribution.scholarId(), contribution.scholarName().trim());
        }
      }
      List<GraphScholarNode> scholars = new ArrayList<>(scholarNames.size());
      for (Map.Entry<String, String> entry : scholarNames.entrySet()) {
        scholars.add(
            new GraphScholarNode(
                entry.getKey(),
                entry.getValue(),
                null,
                null,
                row.dataNature(),
                row.aggregateVersion()));
      }
      works.add(
          new GraphProjectedWork(
              row.workId(),
              row.title(),
              eligible,
              row.dataNature(),
              row.aggregateVersion(),
              List.copyOf(topics.getOrDefault(row.workId(), Set.<String>of())),
              List.copyOf(organizations.getOrDefault(row.workId(), Set.<String>of())),
              authorships,
              scholars));
    }
    return works;
  }

  private static Set<String> distinctIds(java.util.Collection<String> ids) {
    Set<String> distinct = new LinkedHashSet<>();
    if (ids != null) {
      for (String id : ids) {
        if (id != null && !id.isBlank()) {
          distinct.add(id.trim());
        }
      }
    }
    return distinct;
  }

  private static List<List<String>> chunks(java.util.Collection<String> ids) {
    List<String> list = new ArrayList<>(new LinkedHashSet<>(ids));
    List<List<String>> chunks = new ArrayList<>();
    for (int start = 0; start < list.size(); start += IN_CHUNK) {
      chunks.add(list.subList(start, Math.min(start + IN_CHUNK, list.size())));
    }
    return chunks;
  }
}
