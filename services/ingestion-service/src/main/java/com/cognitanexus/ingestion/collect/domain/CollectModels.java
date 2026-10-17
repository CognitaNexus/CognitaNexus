package com.cognitanexus.ingestion.collect.domain;

import com.cognitanexus.sharedkernel.DataNature;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 采集域实体与适配器输出类型。
 *
 * <p>依据：架构 V0.2 §4.2、§6.2。这里的类型只描述采集侧的本地事实，不与 core 的实体共享：
 * 跨服务共享 Java 类型会让两个服务被迫同步升级，而交接契约是 HTTP + 规范化记录。
 */
public final class CollectModels {

  private CollectModels() {}

  /**
   * 采集运行配置（core 侧来源配置的执行副本）。
   *
   * <p>后四个字段描述的是<b>业务更新计划</b>，与「调度轮询间隔」（服务实例参数）和「失败退避」
   * （任务自己的 {@code nextAttemptAt}）是三件不同的事，不能互相推断：
   *
   * <ul>
   *   <li>{@code newRecordIntervalMinutes}：多久去发现一批新记录；
   *   <li>{@code fieldRefreshIntervalMinutes}：已入库成果的历史字段与引用量多久刷新一次；
   *   <li>{@code updatePlanNote}：上面两个数字的取值依据（来源声明频率／许可条款／人工评估）；
   *   <li>{@code nextDueAt}：下一次允许按业务周期执行的时刻。
   * </ul>
   *
   * <p>两个周期都可以为 {@code null}，{@code null} 的含义是「未设定」，此时不按周期排期，
   * 任务仍由人工创建驱动——与引入更新计划之前的行为一致。不允许因为「一般一个月更新一次」
   * 之类说法就填一个默认值：没有出处的周期在下一次评审时无法判断是否还成立。
   */
  public record SourceRunConfig(
      String id,
      String sourceCode,
      String displayName,
      AdapterKind adapterKind,
      String baseEndpoint,
      String queryExpression,
      int pageSize,
      List<String> allowedFields,
      String configVersion,
      String licenseReviewState,
      String licenseNote,
      int rateLimitPerMinute,
      boolean enabled,
      int syncedConfigVersion,
      Integer newRecordIntervalMinutes,
      Integer fieldRefreshIntervalMinutes,
      String updatePlanNote,
      Instant nextDueAt) {

    public boolean licenseApproved() {
      return "APPROVED".equals(licenseReviewState);
    }

    /**
     * 是否允许执行抓取。
     *
     * <p>三个条件缺一不可：本地开关打开、许可已批准、且本地副本的配置版本与 core 权威版本一致。
     * 最后一条针对「core 改了白名单但本地还没同步」的窗口：此时继续按旧白名单抓取，会持久化
     * 新白名单之外的字段。
     */
    public boolean runnable(int authoritativeConfigVersion) {
      return enabled && licenseApproved() && syncedConfigVersion == authoritativeConfigVersion;
    }

    /** 是否设定了业务更新周期。未设定时任务只能由人工创建驱动。 */
    public boolean hasUpdatePlan() {
      return newRecordIntervalMinutes != null || fieldRefreshIntervalMinutes != null;
    }

    /**
     * 按业务周期此刻是否到期。
     *
     * <p>未设定周期时恒为到期：这是「本特性不改变既有行为」的落点。让未设定周期反而挡住任务，
     * 会把所有存量来源的采集停掉——那不是保守，是故障。
     */
    public boolean dueAt(Instant now) {
      return nextDueAt == null || !nextDueAt.isAfter(now);
    }

    /**
     * 从给定时刻推算下一次到期时刻；未设定周期时返回 {@code null}。
     *
     * <p>取两个周期里较近的那个：新记录的发现不能被字段刷新拖慢，反之亦然。两者都设定时，
     * 较近的周期决定节奏，另一项在它真正到期之前不会执行——具体执行哪一项由任务键区分，
     * 本方法只回答「什么时候该再跑」。
     */
    public Instant nextDueAfter(Instant from) {
      Long minutes = null;
      if (newRecordIntervalMinutes != null) {
        minutes = newRecordIntervalMinutes.longValue();
      }
      if (fieldRefreshIntervalMinutes != null
          && (minutes == null || fieldRefreshIntervalMinutes < minutes)) {
        minutes = fieldRefreshIntervalMinutes.longValue();
      }
      return minutes == null ? null : from.plusSeconds(minutes * 60L);
    }
  }

  /**
   * 采集任务。
   *
   * <p>不含「单轮最多抓几页」：那是运行参数（{@code cn.collect.max-pages-per-run}），属于服务
   * 实例的配置，不是任务自身的状态。放进任务是上一版遗留的字段，从未被写入也未被读取。
   */
  public record CollectJob(
      String id,
      String sourceConfigId,
      String jobKey,
      JobStatus status,
      String cursor,
      String checkpoint,
      int totalFetched,
      int totalStaged,
      int totalHandedOff,
      int totalRejected,
      String leaseOwner,
      Instant leaseUntil,
      String lastError,
      String createdBy,
      int consecutiveFailures,
      Instant nextAttemptAt) {}

  /** 交接暂存记录。 */
  public record StagedRecord(
      String id,
      String jobId,
      String sourceConfigId,
      String sourceRecordId,
      String contentFingerprint,
      String payloadJson,
      DataNature dataNature,
      Instant sourceUpdatedAt,
      LocalDate sourcePublishedAt,
      Instant fetchedAt,
      HandoffState handoffState,
      String handoffBatchId,
      int handoffAttempts,
      String lastError,
      String coreReceiptStatus,
      String coreAchievementId) {}

  /**
   * 适配器抓取到的一条记录。
   *
   * <p>字段与 core 的 {@code NormalizedRecord} 对应：适配器负责把各来源的字段差异抹平到这里，
   * core 只处理这一种结构，因此新增来源不需要改动 core 的规范化逻辑。
   *
   * @param sourceRecordId 来源侧稳定标识（Crossref 用 DOI，OpenAlex 用作品 ID）
   * @param payload 规范化载荷，键名须与 core 的 NormalizedRecord 一致
   * @param dataNature 数据性质；由适配器按来源真实性质显式给出，不允许缺省
   */
  public record CollectedRecord(
      String sourceRecordId,
      Map<String, Object> payload,
      DataNature dataNature,
      Instant sourceUpdatedAt,
      LocalDate sourcePublishedAt,
      Instant fetchedAt) {}

  /**
   * 一页抓取结果。
   *
   * @param records 本页记录
   * @param nextCursor 下一页游标；为空表示已到末页
   * @param totalAvailable 来源侧报告的可用总数；未知时为 null，<b>不得</b>填 0
   */
  public record FetchPage(
      List<CollectedRecord> records, String nextCursor, Integer totalAvailable) {

    public FetchPage {
      records = records == null ? List.of() : List.copyOf(records);
    }

    public boolean lastPage() {
      return nextCursor == null || nextCursor.isBlank();
    }
  }
}
