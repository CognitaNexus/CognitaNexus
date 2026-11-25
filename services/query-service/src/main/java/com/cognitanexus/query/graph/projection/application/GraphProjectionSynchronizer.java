package com.cognitanexus.query.graph.projection.application;

import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.projection.domain.GraphProjectedWork;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把 PG 读投影中的一份成果写入图（Backfill、事件增量与定向修复共用同一实现）。
 *
 * <p>为什么只有一份实现：三条入口若各自拼一遍「写学者 → 写成果 → 判定物化 → 失败怎么办」，
 * 迟早出现「回填写了机构、增量没写」这类只在某条路径上成立的差异，而带筛选的查询会因此
 * 在图上比 PG 少一批成果（ADR-005 §6.2 的筛选等价前提）。收敛到一处之后，三张表到图属性的映射
 * 只有一种定义。
 *
 * <p>幂等与顺序（ADR-005 §5.1）：
 *
 * <ul>
 *   <li>成果按 {@code sourceVersion}（PG {@code aggregate_version}）整体替换，版本不更新则
 *       {@code STALE_IGNORED} 且无副作用；
 *   <li>重复投递同一事件的结果与一次相同（先删后建 + 版本守卫），不靠「强度加一」累计；
 *   <li>本类<b>不</b>承担同一成果的并发互斥：调用方必须保证同一 {@code workId} 同时只有一个
 *       消费者（ADR-005 §5.1 的前置条件）。
 * </ul>
 *
 * <p>失败处理：可重试错误按策略退避重试；不可重试错误立刻返回 PERMANENT。单条失败<b>不会</b>
 * 抛出，而是作为结果返回，让调用方决定「继续下一批并统计」还是「超过阈值中止」——回填因为
 * 一条脏数据整批中止，会让真正的问题（这条数据）永远轮不到修复。
 */
public class GraphProjectionSynchronizer {

  private static final Logger log = LoggerFactory.getLogger(GraphProjectionSynchronizer.class);

  private final GraphProjectionSource source;
  private final GraphProjectionStore store;
  private final GraphProjectionRetryPolicy retryPolicy;
  private final LongConsumer sleeper;

  public GraphProjectionSynchronizer(GraphProjectionSource source, GraphProjectionStore store) {
    this(source, store, GraphProjectionRetryPolicy.defaults(), millis -> sleepQuietly(millis));
  }

  public GraphProjectionSynchronizer(
      GraphProjectionSource source,
      GraphProjectionStore store,
      GraphProjectionRetryPolicy retryPolicy,
      LongConsumer sleeper) {
    if (source == null || store == null) {
      throw new IllegalArgumentException("图投影同步需要 PG 数据源与图写端口");
    }
    this.source = source;
    this.store = store;
    this.retryPolicy = retryPolicy == null ? GraphProjectionRetryPolicy.defaults() : retryPolicy;
    this.sleeper = sleeper == null ? millis -> sleepQuietly(millis) : sleeper;
  }

  /**
   * 按标识同步一条成果：先从 PG 取完整视图，再写给图库。
   *
   * @return empty 表示 PG 中已不存在该成果（不回写墓碑；孤立节点由对账的定向修复显式退场）
   */
  public Optional<GraphSyncOutcome> syncWork(String generation, String workId) {
    Optional<GraphProjectedWork> work = source.loadWork(generation, workId);
    if (work.isEmpty()) {
      log.debug("PG 未找到成果，跳过图投影 generation={} workId={}", generation, workId);
      return Optional.empty();
    }
    return Optional.of(apply(work.get()));
  }

  /** 批量同步已经取回的成果（回填路径：本批四表已在同一读取里拼装好）。 */
  public GraphSyncBatch syncWorks(List<GraphProjectedWork> works) {
    if (works == null || works.isEmpty()) {
      return GraphSyncBatch.empty();
    }
    List<GraphSyncOutcome> outcomes = new ArrayList<>(works.size());
    for (GraphProjectedWork work : works) {
      outcomes.add(apply(work));
    }
    return new GraphSyncBatch(outcomes);
  }

  /**
   * 应用一条成果，带有限重试与错误分类。
   *
   * <p>不合格成果走 {@code retireWork}：留下墓碑并删除物化合作边。仅靠版本守卫不够——守卫只
   * 拒绝旧版本，不会删除已经写下的边（ADR-005 §7）。
   */
  public GraphSyncOutcome apply(GraphProjectedWork work) {
    if (work == null) {
      throw new IllegalArgumentException("待投影成果不能为空");
    }
    String note = "";
    int attempt = 0;
    while (true) {
      attempt++;
      try {
        GraphSyncOutcome.Action action = writeOnce(work);
        return new GraphSyncOutcome(
            work.workId(), action, work.sourceVersion(), work.authorships().size(), attempt, note);
      } catch (RuntimeException ex) {
        GraphProjectionErrorClassifier.Kind kind = GraphProjectionErrorClassifier.classify(ex);
        note = kind.name() + ": " + ex.getClass().getSimpleName() + ": " + safeMessage(ex);
        if (kind == GraphProjectionErrorClassifier.Kind.PERMANENT
            || attempt >= retryPolicy.maxAttempts()) {
          GraphSyncOutcome.Action action =
              kind == GraphProjectionErrorClassifier.Kind.PERMANENT
                  ? GraphSyncOutcome.Action.FAILED_PERMANENT
                  : GraphSyncOutcome.Action.FAILED_RETRYABLE;
          log.warn(
              "图投影写入失败 workId={} action={} attempts={} kind={} error={}",
              work.workId(),
              action,
              attempt,
              kind,
              safeMessage(ex));
          return new GraphSyncOutcome(
              work.workId(),
              action,
              work.sourceVersion(),
              work.authorships().size(),
              attempt,
              note);
        }
        Duration backoff = retryPolicy.backoffFor(attempt);
        log.debug(
            "图投影写入失败将重试 workId={} attempt={} waitMs={} error={}",
            work.workId(),
            attempt,
            backoff.toMillis(),
            safeMessage(ex));
        sleeper.accept(backoff.toMillis());
      }
    }
  }

  /** 实际写入：先保证学者节点，再按成果整体替换。 */
  private GraphSyncOutcome.Action writeOnce(GraphProjectedWork work) {
    if (!work.eligible()) {
      boolean applied = store.retireWork(work.workId(), work.sourceVersion());
      return applied ? GraphSyncOutcome.Action.RETIRED : GraphSyncOutcome.Action.STALE_IGNORED;
    }
    if (!work.scholars().isEmpty()) {
      // 学者先写：MERGE 会在 AUTHored 语句里兜底创建无姓名节点，先 upsert 能让姓名/领域/机构
      // 与本成果的参与关系同版本落库。姓名为空的学者不在 scholars 里，避免空名覆盖已有姓名。
      store.upsertScholars(work.scholars());
    }
    boolean applied = store.replaceWork(work.toWorkNode(), work.authorships());
    return applied ? GraphSyncOutcome.Action.APPLIED : GraphSyncOutcome.Action.STALE_IGNORED;
  }

  /** 异常消息可能包含 Cypher/主机信息；日志与断点只保留首段并截断。 */
  private static String safeMessage(RuntimeException ex) {
    String message = ex.getMessage();
    if (message == null || message.isBlank()) {
      return "无消息";
    }
    String firstLine = message.split("\\R", 2)[0].trim();
    return firstLine.length() > 200 ? firstLine.substring(0, 200) + "…" : firstLine;
  }

  private static void sleepQuietly(long millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("图投影重试等待被中断", interrupted);
    }
  }
}
