package com.cognitanexus.query.projection.application;

import com.cognitanexus.query.projection.application.ProjectionRepositories.InboxStore;
import com.cognitanexus.query.projection.domain.ConsumerProgress;
import com.cognitanexus.query.projection.domain.InboxEventRecord;
import com.cognitanexus.sharedkernel.BusinessException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 投影发布器。
 *
 * <p>依据：架构 V0.2 §7.1「query 按连续修订处理事件」、§6.3「只推进连续修订」。
 *
 * <p>处理顺序严格按 catalogRevision 升序，且只从「下一个待发布修订」开始。这条规则带来一个看起来
 * 保守的后果：前一条事件被隔离或反复失败时，后面的修订会全部积压，公开查询的发布水位停住，
 * 屏障校验会因此返回 503。这是<b>刻意</b>的——跳过一条事件继续前进，就等于在「全量同步」的名义下
 * 丢掉一条变更，而外部完全看不出来。积压会被一致性接口与日志显式报告。
 */
@Component
public class ProjectionPublisher {

  private static final Logger log = LoggerFactory.getLogger(ProjectionPublisher.class);

  private final InboxStore inbox;
  private final ProjectionPreparer preparer;
  private final ProjectionUnitOfWork unitOfWork;
  private final ProjectionProperties properties;

  /** 上一次已经报告过的空洞特征（已发布水位:最早收到修订），用于抑制重复告警。 */
  private volatile String reportedGap;

  ProjectionPublisher(
      InboxStore inbox,
      ProjectionPreparer preparer,
      ProjectionUnitOfWork unitOfWork,
      ProjectionProperties properties) {
    this.inbox = inbox;
    this.preparer = preparer;
    this.unitOfWork = unitOfWork;
    this.properties = properties;
  }

  /** 一轮发布的计数结果。 */
  public record PublishOutcome(
      int applied, int isolated, int failed, boolean deferred, long publishedRevision) {

    static PublishOutcome empty(long publishedRevision) {
      return new PublishOutcome(0, 0, 0, false, publishedRevision);
    }
  }

  public PublishOutcome publishPending(String consumerId, int maxEvents) {
    ConsumerProgress progress = inbox.readProgress(consumerId);
    long published = progress.publishedRevision();
    int applied = 0;
    int isolated = 0;
    int failed = 0;
    boolean deferred = false;

    for (int i = 0; i < maxEvents; i++) {
      List<InboxEventRecord> pending = inbox.listPending(consumerId, 1);
      if (pending.isEmpty()) {
        break;
      }
      InboxEventRecord event = pending.get(0);

      // 迟到事件：revision 已不高于已发布水位，说明这一修订的内容早已进过投影。
      //
      // 单调水位要求「下一条必须正好是 published+1」，所以这类行一旦排在队首，
      // 每个周期都会被判成「等待修订 X」，水位静默停住、不报错也不改状态——
      // 线上实际发生过：重放补投后 1426 条历史事件堵住队头，投影停了 10 分钟无任何日志。
      // 这里直接标记已应用并继续，不参与「应用」计数。
      if (event.catalogRevision() <= published) {
        inbox.markApplied(event.id());
        continue;
      }

      ProjectionPreparer.Prepared prepared;
      try {
        prepared = preparer.prepare(event);
      } catch (BusinessException ex) {
        // 依赖不可用与「事件本身有问题」必须分开：前者不改任何状态，等下一个调度周期重试即可；
        // 若按失败计数，一次 core 抖动会把一条正常事件推向 FAILED 并永久停住水位。
        log.warn(
            "准备投影所需的外部数据失败，本轮停止 revision={} code={} message={}",
            event.catalogRevision(),
            ex.errorCode(),
            ex.getMessage());
        return new PublishOutcome(applied, isolated, failed, true, published);
      }

      ProjectionUnitOfWork.ApplyResult result;
      try {
        result = unitOfWork.applyOne(consumerId, event, prepared.survivorProjection());
      } catch (RuntimeException ex) {
        // 投影应用本身失败：记一次尝试并退避，不推进水位
        inbox.markFailed(event.id(), describe(ex), properties.failureBackoffSeconds());
        log.error(
            "投影应用失败 revision={} eventId={} cause={}",
            event.catalogRevision(),
            event.eventId(),
            describe(ex),
            ex);
        return new PublishOutcome(applied, isolated, failed + 1, true, published);
      }

      switch (result.status()) {
        case APPLIED -> {
          applied++;
          published = result.publishedRevision();
        }
        case ISOLATED -> {
          isolated++;
          log.error(
              "事件被隔离，发布水位停在此处等待人工处理 revision={} eventId={} reason={}",
              event.catalogRevision(),
              event.eventId(),
              result.note());
          return new PublishOutcome(applied, isolated, failed, true, published);
        }
        case DEFERRED -> {
          deferred = true;
          log.debug("发布推进暂停：{}", result.note());
          // 单调「还没轮到」有两种成因，处置完全不同，必须分开报：一种是前序修订正在退避重试
          // （会自己好），另一种是前序修订从未到达（永远不会好）。后者不会抛错、不会改状态，
          // 水位静默停住，只有主动算一下「已发布水位之后最早收到的是哪条」才能识别。
          reportGapIfAny(consumerId, published);
          return new PublishOutcome(applied, isolated, failed, true, published);
        }
      }
    }
    return new PublishOutcome(applied, isolated, failed, deferred, published);
  }

  /**
   * 修订空洞告警：已发布水位之后最早收到的事件修订号若大于「期望的下一条」，说明中间那段
   * 从未到达本服务。
   *
   * <p>只在水位或空洞边界变化时打一条 ERROR，不每轮重复：调度周期是秒级，每次都打会把日志淹掉，
   * 反而让真正需要看见的那一行消失。水位一旦推进或空洞边界变化，说明情况已经变了，再报一次。
   */
  private void reportGapIfAny(String consumerId, long published) {
    java.util.OptionalLong lowest = inbox.firstReceivedRevisionAfter(consumerId, published);
    if (lowest.isEmpty() || lowest.getAsLong() <= published + 1) {
      reportedGap = null;
      return;
    }
    long lowestValue = lowest.getAsLong();
    String signature = published + ":" + lowestValue;
    if (signature.equals(reportedGap)) {
      return;
    }
    reportedGap = signature;
    log.error(
        "检测到修订空洞：已发布水位 {}，下一条应为 {}，但已收到的最早事件是 {}，中间 {} 条从未到达。"
            + "增量发布无法自行恢复；若 core 侧该区间为死信，确认消费者可达后调用 "
            + "POST /internal/v1/outbox/requeue-failed?consumerId={} 重投，否则改用全量重建。",
        published,
        published + 1,
        lowestValue,
        lowestValue - published - 1,
        "query");
  }

  private static String describe(Throwable ex) {
    Throwable root = ex;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String message = root.getMessage();
    String text = root.getClass().getSimpleName() + ": " + (message == null ? "" : message);
    return text.length() > 400 ? text.substring(0, 400) : text;
  }

  /** 供维护入口在重建前把已接收事件全部应用到当前代。 */
  public PublishOutcome drain(String consumerId) {
    return publishPending(consumerId, properties.publishBatchPerBatch());
  }
}
