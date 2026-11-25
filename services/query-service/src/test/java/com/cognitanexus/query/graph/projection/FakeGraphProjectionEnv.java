package com.cognitanexus.query.graph.projection;

import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.domain.GraphAuthorship;
import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import com.cognitanexus.query.graph.domain.GraphScholarNode;
import com.cognitanexus.query.graph.domain.GraphWorkNode;
import com.cognitanexus.query.graph.infrastructure.GraphStoreUnavailableException;
import com.cognitanexus.query.graph.projection.application.GraphProjectionCheckpointStore;
import com.cognitanexus.query.graph.projection.application.GraphProjectionInspector;
import com.cognitanexus.query.graph.projection.application.GraphProjectionSource;
import com.cognitanexus.query.graph.projection.domain.GraphProjectedWork;
import com.cognitanexus.query.graph.projection.domain.GraphProjectionCheckpoint;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 图投影单元测试用的内存实现。
 *
 * <p>为什么不用 Mockito 打桩而是写一个真正的内存实现：被测逻辑的核心恰恰是
 * 「版本守卫、只前进水位、先删后建、断点」这些<b>状态迁移</b>。用 {@code when(...).thenReturn(...)}
 * 只能证明代码调用了一个方法，证明不了「重复投递第二次不会产生第二份边」。这里的假实现复刻真实
 * 存储的这几条语义，并额外支持注入故障与漂移，让失败路径也可重复构造。
 *
 * <p>假实现不追求完整：只实现本 PR 用到的端口方法；任何未实现行为都会在测试中立刻暴露（抛异常），
 * 而不是静默返回默认值。
 */
public final class FakeGraphProjectionEnv {

  /** 一条已写入图的成果。{@code authorships} 为空表示墓碑（不合格成果）。 */
  public record StoredWork(GraphWorkNode work, List<GraphAuthorship> authorships) {

    boolean materialized(int threshold) {
      return work.isPublic() && authorships.size() >= 2 && authorships.size() <= threshold;
    }
  }

  public static final class FakeStore implements GraphProjectionStore {

    public final Map<String, StoredWork> works = new LinkedHashMap<>();
    public final List<String> calls = new ArrayList<>();
    public final List<GraphScholarNode> scholars = new ArrayList<>();
    public GraphProjectionMeta meta;

    /** 指定成果的「瞬态失败剩余次数」：每次写入消耗一次并抛可重试异常。 */
    public final Map<String, Integer> transientFailures = new HashMap<>();

    /** 指定成果的永久失败：抛 {@link IllegalArgumentException}（不可重试）。 */
    public final Set<String> permanentFailures = new LinkedHashSet<>();

    public FakeStore(String scope, long projectedVersion, String generation) {
      this.meta =
          new GraphProjectionMeta(scope, projectedVersion, projectedVersion, generation, null);
    }

    @Override
    public int upsertScholars(Collection<GraphScholarNode> scholars) {
      calls.add("upsertScholars:" + scholars.size());
      this.scholars.addAll(scholars);
      return scholars.size();
    }

    @Override
    public boolean replaceWork(GraphWorkNode work, List<GraphAuthorship> authorships) {
      maybeFail(work.workId(), "replaceWork");
      StoredWork existing = works.get(work.workId());
      if (existing != null && existing.work().sourceVersion() > work.sourceVersion()) {
        return false;
      }
      works.put(work.workId(), new StoredWork(work, List.copyOf(authorships)));
      calls.add("replaceWork:" + work.workId() + ":v" + work.sourceVersion());
      return true;
    }

    @Override
    public boolean retireWork(String workId, long sourceVersion) {
      maybeFail(workId, "retireWork");
      StoredWork existing = works.get(workId);
      if (existing != null && existing.work().sourceVersion() > sourceVersion) {
        return false;
      }
      GraphWorkNode tombstone =
          new GraphWorkNode(
              workId,
              existing == null ? "" : existing.work().title(),
              false,
              existing == null ? "REAL" : existing.work().dataOrigin(),
              sourceVersion,
              existing == null ? List.of() : existing.work().topicIds(),
              existing == null ? List.of() : existing.work().organizations());
      works.put(workId, new StoredWork(tombstone, List.of()));
      calls.add("retireWork:" + workId + ":v" + sourceVersion);
      return true;
    }

    @Override
    public Optional<GraphProjectionMeta> readMeta(String scope) {
      if (meta == null || !meta.scope().equals(scope)) {
        return Optional.empty();
      }
      return Optional.of(meta);
    }

    @Override
    public long advanceMeta(GraphProjectionMeta candidate) {
      calls.add("advanceMeta:v" + candidate.projectedVersion());
      if (meta == null || meta.projectedVersion() < candidate.projectedVersion()) {
        meta = candidate;
      }
      return meta.projectedVersion();
    }

    @Override
    public void resetAllForRebuild(String initialScope) {
      if (meta != null && !meta.scope().equals(initialScope)) {
        throw new IllegalStateException("拒绝整库清空：存在其他作用域 " + meta.scope());
      }
      calls.add("resetAllForRebuild:" + initialScope);
      works.clear();
      meta = GraphProjectionMeta.empty(initialScope);
    }

    private void maybeFail(String workId, String operation) {
      Integer remaining = transientFailures.get(workId);
      if (remaining != null && remaining > 0) {
        transientFailures.put(workId, remaining - 1);
        throw new GraphStoreUnavailableException("图库抖动（" + operation + "）");
      }
      if (permanentFailures.contains(workId)) {
        throw new IllegalArgumentException("参与关系非法（测试注入）");
      }
    }
  }

  /** 构造一份合格成果：作者按传入顺序持有 AUTO 关系，姓名由标识派生，便于断言。 */
  public static GraphProjectedWork work(String workId, long version, String... scholarIds) {
    return work(workId, version, true, "REAL", List.of(), List.of(), scholarIds);
  }

  public static GraphProjectedWork work(
      String workId,
      long version,
      boolean eligible,
      String dataOrigin,
      List<String> topicIds,
      List<String> organizations,
      String... scholarIds) {
    List<GraphAuthorship> authorships = new ArrayList<>();
    List<GraphScholarNode> scholars = new ArrayList<>();
    int order = 1;
    for (String scholarId : scholarIds) {
      authorships.add(new GraphAuthorship(scholarId, "AUTO", order));
      scholars.add(
          new GraphScholarNode(scholarId, "name-" + scholarId, null, null, dataOrigin, version));
      order++;
    }
    return new GraphProjectedWork(
        workId,
        "title-" + workId,
        eligible,
        dataOrigin,
        version,
        topicIds,
        organizations,
        authorships,
        scholars);
  }

  public static final class FakeCheckpointStore implements GraphProjectionCheckpointStore {

    public final Map<String, GraphProjectionCheckpoint> saved = new HashMap<>();

    @Override
    public Optional<GraphProjectionCheckpoint> find(String scope) {
      return Optional.ofNullable(saved.get(scope));
    }

    @Override
    public int save(GraphProjectionCheckpoint checkpoint) {
      saved.put(checkpoint.scope(), checkpoint);
      return 1;
    }
  }

  /** 内存 PG 读投影：works 即权威表，事件按修订排序。 */
  public static final class FakeSource implements GraphProjectionSource {

    public final Map<String, GraphProjectedWork> works = new LinkedHashMap<>();
    public final List<AppliedEvent> events = new ArrayList<>();
    public final List<String> calls = new ArrayList<>();
    public Stage stage;
    public int pageCalls;
    public RuntimeException failOnPageCall;
    public int failOnPageNumber = -1;

    public FakeSource(String generation, long publishedRevision) {
      this.stage = new Stage(generation, publishedRevision);
    }

    public FakeSource add(GraphProjectedWork work) {
      works.put(work.workId(), work);
      return this;
    }

    public FakeSource addEvent(AppliedEvent event) {
      events.add(event);
      events.sort(
          Comparator.comparingLong(AppliedEvent::catalogRevision)
              .thenComparing(AppliedEvent::eventId));
      return this;
    }

    @Override
    public Optional<Stage> readStage(String consumerId) {
      return Optional.ofNullable(stage);
    }

    @Override
    public List<GraphProjectedWork> pageWorks(String generation, String afterWorkId, int limit) {
      calls.add("pageWorks:" + afterWorkId + ":" + limit);
      pageCalls++;
      if (failOnPageCall != null && (failOnPageNumber <= 0 || pageCalls == failOnPageNumber)) {
        RuntimeException failure = failOnPageCall;
        failOnPageCall = null;
        throw failure;
      }
      return works.values().stream()
          .filter(work -> afterWorkId == null || work.workId().compareTo(afterWorkId) > 0)
          .sorted(Comparator.comparing(GraphProjectedWork::workId))
          .limit(limit)
          .toList();
    }

    @Override
    public List<GraphProjectedWork> loadWorks(String generation, Collection<String> workIds) {
      List<GraphProjectedWork> found = new ArrayList<>();
      for (String id : new TreeSet<>(workIds)) {
        GraphProjectedWork work = works.get(id);
        if (work != null) {
          found.add(work);
        }
      }
      return found;
    }

    @Override
    public Optional<GraphProjectedWork> loadWork(String generation, String workId) {
      return Optional.ofNullable(works.get(workId));
    }

    @Override
    public long countEligibleWorks(String generation, String dataOrigin) {
      return eligible(dataOrigin).size();
    }

    @Override
    public long countQualifiedContributions(String generation, String dataOrigin) {
      return eligible(dataOrigin).stream().mapToLong(work -> work.authorships().size()).sum();
    }

    @Override
    public long countContributors(String generation, String dataOrigin) {
      Set<String> scholars = new LinkedHashSet<>();
      for (GraphProjectedWork work : eligible(dataOrigin)) {
        work.authorships().forEach(authorship -> scholars.add(authorship.scholarId()));
      }
      return scholars.size();
    }

    @Override
    public long countMaterializedPairs(String generation, int threshold, String dataOrigin) {
      long pairs = 0;
      for (GraphProjectedWork work : eligible(dataOrigin)) {
        int authors = work.authorships().size();
        if (authors >= 2 && authors <= threshold) {
          pairs += (long) authors * (authors - 1) / 2;
        }
      }
      return pairs;
    }

    @Override
    public List<String> pageEligibleWorkIds(
        String generation, String afterWorkId, int limit, String dataOrigin) {
      return eligible(dataOrigin).stream()
          .map(GraphProjectedWork::workId)
          .filter(id -> afterWorkId == null || id.compareTo(afterWorkId) > 0)
          .sorted()
          .limit(limit)
          .toList();
    }

    @Override
    public List<ContributionView> loadQualifiedContributions(
        String generation, Collection<String> workIds) {
      List<ContributionView> views = new ArrayList<>();
      for (String id : new TreeSet<>(workIds)) {
        GraphProjectedWork work = works.get(id);
        if (work == null) {
          continue;
        }
        work.authorships()
            .forEach(authorship -> views.add(new ContributionView(id, authorship.scholarId())));
      }
      return views;
    }

    @Override
    public List<AppliedEvent> listAppliedEventsAfter(String consumerId, long revision, int limit) {
      return events.stream()
          .filter(event -> event.catalogRevision() > revision)
          .limit(limit)
          .toList();
    }

    @Override
    public List<String> achievementIdsOfScholar(String generation, String scholarId) {
      List<String> ids = new ArrayList<>();
      works.values().stream()
          .filter(
              work ->
                  work.authorships().stream()
                      .anyMatch(authorship -> authorship.scholarId().equals(scholarId)))
          .forEach(work -> ids.add(work.workId()));
      return ids;
    }

    private List<GraphProjectedWork> eligible(String dataOrigin) {
      return works.values().stream()
          .filter(GraphProjectedWork::eligible)
          .filter(work -> dataOrigin == null || work.dataOrigin().equals(dataOrigin))
          .sorted(Comparator.comparing(GraphProjectedWork::workId))
          .toList();
    }
  }

  /** 由图状态派生计数的检视器；可注入「图侧漂移」来验证对账能不能发现它。 */
  public static final class FakeInspector implements GraphProjectionInspector {

    public final FakeStore store;
    public final int threshold;

    /** 图上「看不见」的成果（模拟缺失）。 */
    public final Set<String> hidden = new LinkedHashSet<>();

    /** 图上多出来的成果（模拟孤立节点/幽灵边）。 */
    public final Set<String> extra = new LinkedHashSet<>();

    /** 覆盖某些成果的物化作者对集合（模拟边漂移）。 */
    public final Map<String, Set<String>> pairOverrides = new HashMap<>();

    /** 覆盖某些成果的标题（模拟证据过期）。 */
    public final Map<String, String> titleOverrides = new HashMap<>();

    public FakeInspector(FakeStore store, int threshold) {
      this.store = store;
      this.threshold = threshold;
    }

    @Override
    public GraphCounts counts(String dataOrigin) {
      List<StoredWork> publicWorks = publicWorks(dataOrigin);
      Set<String> scholars = new LinkedHashSet<>();
      long authored = 0;
      long edges = 0;
      for (StoredWork stored : publicWorks) {
        for (GraphAuthorship authorship : stored.authorships()) {
          scholars.add(authorship.scholarId());
          authored++;
        }
        if (stored.materialized(threshold)) {
          edges += (long) stored.authorships().size() * (stored.authorships().size() - 1) / 2;
        }
      }
      // extra 只影响「标识集合」（孤立节点），计数一律由图状态派生，避免同一节点被算两次。
      return new GraphCounts(publicWorks.size(), scholars.size(), authored, edges);
    }

    @Override
    public List<String> publicWorkIds(int limit) {
      Set<String> unique = new LinkedHashSet<>(publicIds());
      unique.addAll(extra);
      List<String> ids = new ArrayList<>(unique);
      ids.sort(String::compareTo);
      return ids.stream().limit(Math.max(limit, 0)).toList();
    }

    @Override
    public Set<String> publicWorkIdsAmong(Collection<String> workIds) {
      Set<String> found = new LinkedHashSet<>(publicIds());
      found.addAll(extra);
      found.retainAll(workIds);
      return found;
    }

    @Override
    public Map<String, Set<String>> materializedPairsOf(Collection<String> workIds) {
      Map<String, Set<String>> result = new HashMap<>();
      for (String id : workIds) {
        if (pairOverrides.containsKey(id)) {
          result.put(id, pairOverrides.get(id));
          continue;
        }
        StoredWork stored = store.works.get(id);
        if (stored == null || hidden.contains(id) || !stored.materialized(threshold)) {
          result.put(id, Set.of());
          continue;
        }
        result.put(id, pairKeys(stored.authorships()));
      }
      return result;
    }

    @Override
    public Map<String, String> workTitles(Collection<String> workIds) {
      Map<String, String> titles = new HashMap<>();
      for (String id : workIds) {
        if (titleOverrides.containsKey(id)) {
          titles.put(id, titleOverrides.get(id));
          continue;
        }
        StoredWork stored = store.works.get(id);
        if (stored != null && !hidden.contains(id)) {
          titles.put(id, stored.work().title());
        }
      }
      return titles;
    }

    @Override
    public Map<String, Set<String>> evidenceTitlesOf(Collection<String> workIds) {
      Map<String, Set<String>> titles = new HashMap<>();
      for (String id : workIds) {
        StoredWork stored = store.works.get(id);
        if (stored == null || hidden.contains(id) || !stored.materialized(threshold)) {
          continue;
        }
        String expected = titleOverrides.getOrDefault(id, stored.work().title());
        titles.put(id, Set.of(expected));
      }
      return titles;
    }

    private List<StoredWork> publicWorks(String dataOrigin) {
      return store.works.entrySet().stream()
          .filter(entry -> !hidden.contains(entry.getKey()))
          .map(Map.Entry::getValue)
          .filter(stored -> stored.work().isPublic())
          .filter(stored -> dataOrigin == null || dataOrigin.equals(stored.work().dataOrigin()))
          .toList();
    }

    private Set<String> publicIds() {
      Set<String> ids = new LinkedHashSet<>();
      for (Map.Entry<String, StoredWork> entry : store.works.entrySet()) {
        if (entry.getValue().work().isPublic() && !hidden.contains(entry.getKey())) {
          ids.add(entry.getKey());
        }
      }
      return ids;
    }

    private static Set<String> pairKeys(List<GraphAuthorship> authorships) {
      List<String> scholars = new ArrayList<>();
      for (GraphAuthorship authorship : authorships) {
        if (!scholars.contains(authorship.scholarId())) {
          scholars.add(authorship.scholarId());
        }
      }
      scholars.sort(String::compareTo);
      Set<String> keys = new TreeSet<>();
      for (int i = 0; i < scholars.size(); i++) {
        for (int j = i + 1; j < scholars.size(); j++) {
          keys.add(scholars.get(i) + "\u0001" + scholars.get(j));
        }
      }
      return keys;
    }
  }
}
