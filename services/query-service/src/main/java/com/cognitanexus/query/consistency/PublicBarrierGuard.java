package com.cognitanexus.query.consistency;

import com.cognitanexus.query.corelink.CoreInternalClient;
import com.cognitanexus.query.projection.application.ProjectionRepositories.InboxStore;
import com.cognitanexus.query.projection.domain.ConsumerProgress;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import org.springframework.stereotype.Component;

/**
 * 公开资格与版本屏障。
 *
 * <p>依据：架构 V0.2 §7.2。规则只有两条，但都必须无条件执行：
 *
 * <ol>
 *   <li>本地发布水位落后于 core 的 {@code requiredPublicRevision} 时返回 503
 *       {@code VIEW_UPDATING}，不得继续返回旧缓存、旧计数或旧路径证据；
 *   <li>core 不可达时校验<b>失败</b>，查询不假装已经核实公开资格。
 * </ol>
 *
 * <p>为什么不做本地缓存来省掉这次调用：架构明确把「每次公开请求读取权威值」写成要求，并把这个
 * 保守的全局屏障列为本期明确的安全取舍（更新期间短暂不可用）。加一层哪怕几百毫秒的缓存，都会让
 * 「撤销后新发起的请求必须拒绝旧版本」这条线性化要求失效——撤销刚提交、读到的却还是缓存里的
 * 旧水位，于是已撤下的成果继续对外可见。要换性能必须另写设计，不能在实现里悄悄加缓存。
 */
@Component
public class PublicBarrierGuard {

  private final CoreInternalClient core;
  private final InboxStore inbox;
  private final String consumerId;

  public PublicBarrierGuard(
      CoreInternalClient core,
      InboxStore inbox,
      com.cognitanexus.query.projection.application.ProjectionProperties properties) {
    this.core = core;
    this.inbox = inbox;
    this.consumerId = properties.consumerId();
  }

  /**
   * 校验当前是否可以对外提供公开查询结果。
   *
   * @throws BusinessException {@code VIEW_UPDATING}（视图落后）或 {@code DEPENDENCY_UNAVAILABLE}
   *     （core 不可达 / 令牌未配置）
   */
  public BarrierSnapshot assertPubliclyServed() {
    ConsumerProgress progress = inbox.readProgress(consumerId);
    CoreInternalClient.PublicBarrier barrier;
    try {
      barrier = core.readBarrier();
    } catch (BusinessException ex) {
      // core 不可用时绝不假定「没有收紧」，否则一次依赖故障就变成一次内容泄露窗口。
      throw new BusinessException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "无法读取权威公开水位，本次请求无法核实公开资格，请稍后重试");
    }
    if (progress.publishedRevision() < barrier.requiredPublicRevision()) {
      throw new BusinessException(
          ErrorCode.VIEW_UPDATING,
          "公开视图正在更新：已发布修订 "
              + progress.publishedRevision()
              + " 落后于权威要求 "
              + barrier.requiredPublicRevision()
              + "，请稍后重试");
    }
    return new BarrierSnapshot(
        progress.publishedRevision(),
        progress.receivedRevision(),
        barrier.catalogRevision(),
        barrier.requiredPublicRevision(),
        progress.activeGeneration());
  }

  /** 供一致性接口展示，不做拒绝判断。 */
  public Map<String, Object> describe() {
    Map<String, Object> view = new LinkedHashMap<>();
    ConsumerProgress progress = inbox.readProgress(consumerId);
    view.put("activeGeneration", progress.activeGeneration());
    view.put("receivedRevision", progress.receivedRevision());
    view.put("highestSeenRevision", progress.highestSeenRevision());
    view.put("publishedRevision", progress.publishedRevision());
    view.put("receivedAt", progress.receivedAt());
    view.put("publishedAt", progress.publishedAt());
    view.put("receiveLag", progress.receiveLag());
    view.put("publishLag", progress.publishLag());
    // 按状态分列的计数写成独立键，逐个 put：用流式收集器会因键值类型推断而落成 Map<Object,Object>，
    // 与 Map<String,Object> 的 putAll 不兼容，编译期就会拦住，但错误信息指向 putAll 而非根因。
    inbox
        .countByState(consumerId)
        .forEach((state, amount) -> view.put("state" + capitalize(state), amount));
    view.put("oldestIsolatedRevision", inbox.oldestIsolatedRevision(consumerId));
    view.put("revisionGap", describeRevisionGap(progress));
    try {
      CoreInternalClient.PublicBarrier barrier = core.readBarrier();
      view.put("coreCatalogRevision", barrier.catalogRevision());
      view.put("requiredPublicRevision", barrier.requiredPublicRevision());
      view.put("coreReachable", true);
      view.put("serving", progress.publishedRevision() >= barrier.requiredPublicRevision());
      view.put(
          "lagBehindCoreRevision",
          Math.max(0, barrier.catalogRevision() - progress.publishedRevision()));
    } catch (BusinessException ex) {
      // 这里如实报告「core 不可达」，不填 0 充数：0 会被读成「完全同步」。
      view.put("coreReachable", false);
      view.put("serving", false);
      view.put("unavailableReason", ex.getMessage());
    }
    view.put(
        "note",
        "publishedRevision 是投影已发布的连续修订；catalogRevision 是 core 的提交次序。"
            + "两者差距是正常的传播延迟，只有落后 requiredPublicRevision 才代表公开资格尚未核实。");
    return view;
  }

  /**
   * 读出「已发布水位之后最早的已接收事件」与期望修订之间的差。
   *
   * <p>连续门控要求 N+1 先到才能发布 N+1。若权威侧某条事件被永久判死（投递重试耗尽），本地会
   * 先收到 N+k 而永远等不到 N+1：门控不会报错，它只是不停地把新事件判为「还没轮到」，水位静默
   * 停在 N。把这个空洞算出来，让「看起来在忙」变成「明确坏了」，并给出唯一两种恢复动作。
   */
  private Map<String, Object> describeRevisionGap(ConsumerProgress progress) {
    long published = progress.publishedRevision();
    Map<String, Object> gap = new LinkedHashMap<>();
    gap.put("expectedNextRevision", published + 1);
    OptionalLong lowest = inbox.firstReceivedRevisionAfter(consumerId, published);
    if (lowest.isEmpty()) {
      gap.put("lowestReceivedRevision", null);
      gap.put("missingRevisions", 0);
      gap.put("blocked", false);
      gap.put("detail", "已发布水位之后尚无已接收事件，属正常空闲，不代表异常");
      return gap;
    }
    long lowestValue = lowest.getAsLong();
    long missing = lowestValue - published - 1;
    gap.put("lowestReceivedRevision", lowestValue);
    gap.put("missingRevisions", Math.max(0, missing));
    gap.put("blocked", missing > 0);
    if (missing > 0) {
      gap.put(
          "detail",
          "修订 "
              + (published + 1)
              + " 至 "
              + (lowestValue - 1)
              + " 共 "
              + missing
              + " 条事件从未到达本服务，增量发布无法自行恢复");
      gap.put(
          "recovery",
          "先确认 core 侧该区间是否为死信：GET /internal/v1/outbox/dead-letters?consumerId=query；"
              + "确认消费者已可达后 POST /internal/v1/outbox/requeue-failed?consumerId=query 重投；"
              + "若该区间已超出投递留存范围，改用全量重建（POST /internal/v1/maintenance/rebuild）以 core 快照为基线补齐");
    } else {
      gap.put("detail", "修订连续，无空洞；水位停滞若发生，属尚未到重试时刻或被隔离事件阻塞");
    }
    return gap;
  }

  private static String capitalize(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    return value.charAt(0) + value.substring(1).toLowerCase();
  }

  /** 一次屏障校验的结论。 */
  public record BarrierSnapshot(
      long publishedRevision,
      long receivedRevision,
      long coreCatalogRevision,
      long requiredPublicRevision,
      String activeGeneration) {}
}
