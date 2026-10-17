package com.cognitanexus.ingestion.collect.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 采集域持久化。
 *
 * <p>依据：编码规范 §5.1。所有列显式列出并显式别名，既满足「禁止 SELECT *」，也让行对象的
 * 构造器装配不依赖列顺序。必须使用参数绑定 #{}；动态 SQL 只用于固定的状态枚举。
 *
 * <p>租约相关语句统一使用数据库时钟 {@code now()}：多实例部署时各机器本地时间不同步，
 * 用应用侧时间判断租约归属会造成同一任务被两个实例同时处理。
 */
@Mapper
public interface CollectMapper {

  // ---------------------------------------------------------------------------
  // 来源运行配置
  // ---------------------------------------------------------------------------

  record SourceRunConfigRow(
      String id,
      String sourceCode,
      String displayName,
      String adapterKind,
      String baseEndpoint,
      String queryExpression,
      int pageSize,
      // text[] 由 StringArrayTypeHandler 映射，避免在两侧做数组字面量与 JSON 的字符串互转
      String[] allowedFields,
      String configVersion,
      String licenseReviewState,
      String licenseNote,
      int rateLimitPerMinute,
      boolean enabled,
      int syncedConfigVersion,
      Integer newRecordIntervalMinutes,
      Integer fieldRefreshIntervalMinutes,
      String updatePlanNote,
      Instant nextDueAt) {}

  @Select(
      """
      SELECT id, source_code, display_name, adapter_kind, base_endpoint, query_expression,
             page_size, allowed_fields, config_version,
             license_review_state, license_note, rate_limit_per_minute, enabled,
             synced_config_version, new_record_interval_minutes, field_refresh_interval_minutes,
             update_plan_note, next_due_at
      FROM ing_source_run_config WHERE source_code = #{sourceCode}
      """)
  Optional<SourceRunConfigRow> findConfigByCode(@Param("sourceCode") String sourceCode);

  @Select(
      """
      SELECT id, source_code, display_name, adapter_kind, base_endpoint, query_expression,
             page_size, allowed_fields, config_version,
             license_review_state, license_note, rate_limit_per_minute, enabled,
             synced_config_version, new_record_interval_minutes, field_refresh_interval_minutes,
             update_plan_note, next_due_at
      FROM ing_source_run_config WHERE id = #{id}
      """)
  Optional<SourceRunConfigRow> findConfigById(@Param("id") String id);

  @Select(
      """
      SELECT id, source_code, display_name, adapter_kind, base_endpoint, query_expression,
             page_size, allowed_fields, config_version,
             license_review_state, license_note, rate_limit_per_minute, enabled,
             synced_config_version, new_record_interval_minutes, field_refresh_interval_minutes,
             update_plan_note, next_due_at
      FROM ing_source_run_config ORDER BY source_code
      """)
  List<SourceRunConfigRow> listConfigs();

  @Insert(
      """
      INSERT INTO ing_source_run_config
        (id, source_code, display_name, adapter_kind, base_endpoint, query_expression, page_size,
         allowed_fields, config_version, license_review_state, license_note,
         rate_limit_per_minute, enabled, synced_config_version)
      VALUES
        (#{id}, #{sourceCode}, #{displayName}, #{adapterKind}, #{baseEndpoint}, #{queryExpression},
         #{pageSize}, #{allowedFields}::text[], #{configVersion}, #{licenseReviewState},
         #{licenseNote}, #{rateLimitPerMinute}, #{enabled}, #{syncedConfigVersion})
      ON CONFLICT (source_code) DO NOTHING
      """)
  int insertConfig(
      @Param("id") String id,
      @Param("sourceCode") String sourceCode,
      @Param("displayName") String displayName,
      @Param("adapterKind") String adapterKind,
      @Param("baseEndpoint") String baseEndpoint,
      @Param("queryExpression") String queryExpression,
      @Param("pageSize") int pageSize,
      @Param("allowedFields") String[] allowedFields,
      @Param("configVersion") String configVersion,
      @Param("licenseReviewState") String licenseReviewState,
      @Param("licenseNote") String licenseNote,
      @Param("rateLimitPerMinute") int rateLimitPerMinute,
      @Param("enabled") boolean enabled,
      @Param("syncedConfigVersion") int syncedConfigVersion);

  @Update(
      """
      UPDATE ing_source_run_config
      SET enabled = #{enabled}, updated_at = now()
      WHERE id = #{id}
      """)
  int setConfigEnabled(@Param("id") String id, @Param("enabled") boolean enabled);

  /**
   * 同步 core 侧的权威配置版本与字段白名单。
   *
   * <p>只有版本确实变化时才更新，使重复同步不产生无意义的写放大；返回受影响行数供调用方判断
   * 「本次是否发生了同步」。
   */
  @Update(
      """
      UPDATE ing_source_run_config
      SET allowed_fields = #{allowedFields}::text[],
          synced_config_version = #{configVersion},
          config_version = #{configVersion}::text,
          updated_at = now()
      WHERE id = #{id} AND synced_config_version <> #{configVersion}
      """)
  int syncAuthoritativeConfig(
      @Param("id") String id,
      @Param("allowedFields") String[] allowedFields,
      @Param("configVersion") int configVersion);

  @Update(
      """
      UPDATE ing_source_run_config
      SET query_expression = COALESCE(#{queryExpression}, query_expression),
          page_size = COALESCE(#{pageSize}, page_size),
          license_review_state = COALESCE(#{licenseReviewState}, license_review_state),
          license_note = COALESCE(#{licenseNote}, license_note),
          updated_at = now()
      WHERE id = #{id}
      """)
  int updateRunParameters(
      @Param("id") String id,
      @Param("queryExpression") String queryExpression,
      @Param("pageSize") Integer pageSize,
      @Param("licenseReviewState") String licenseReviewState,
      @Param("licenseNote") String licenseNote);

  /**
   * 设定来源的业务更新周期。
   *
   * <p>设定后立刻到期（{@code next_due_at = now()}）：改周期的人期望它马上按新节奏跑一次，
   * 而不是等一个按旧周期算出来的时刻。两列都传 null 表示撤销周期，此时 {@code next_due_at}
   * 回到 NULL，行为退回「只由人工创建任务驱动」。
   *
   * <p>理由（{@code update_plan_note}）由库级 CHECK 强制非空，见 V8 迁移；这里不做第二份校验，
   * 但服务层会先给出可读的错误信息，而不是把约束异常直接抛给调用方。
   */
  @Update(
      """
      UPDATE ing_source_run_config
      SET new_record_interval_minutes = #{newRecordIntervalMinutes},
          field_refresh_interval_minutes = #{fieldRefreshIntervalMinutes},
          update_plan_note = #{note},
          next_due_at = CASE
                          WHEN #{newRecordIntervalMinutes} IS NULL
                           AND #{fieldRefreshIntervalMinutes} IS NULL THEN NULL
                          ELSE now()
                        END,
          updated_at = now()
      WHERE id = #{id}
      """)
  int updateUpdatePlan(
      @Param("id") String id,
      @Param("newRecordIntervalMinutes") Integer newRecordIntervalMinutes,
      @Param("fieldRefreshIntervalMinutes") Integer fieldRefreshIntervalMinutes,
      @Param("note") String note);

  /**
   * 一次按周期的执行收尾后，把下次到期时间前移。
   *
   * <p>只写「更晚」的值：并发收尾（两个实例的相同任务）里较旧的推算不得把到期时间拉回过去，
   * 否则同一条业务周期会被执行两次。传 null 表示未设定周期，直接置空。
   */
  @Update(
      """
      UPDATE ing_source_run_config
      SET next_due_at = CASE
                          WHEN #{nextDueAt}::timestamptz IS NULL THEN NULL
                          WHEN next_due_at IS NULL THEN #{nextDueAt}::timestamptz
                          WHEN #{nextDueAt}::timestamptz > next_due_at THEN #{nextDueAt}::timestamptz
                          ELSE next_due_at
                        END,
          updated_at = now()
      WHERE id = #{id}
      """)
  int advanceNextDueAt(@Param("id") String id, @Param("nextDueAt") Instant nextDueAt);

  // ---------------------------------------------------------------------------
  // 采集任务
  // ---------------------------------------------------------------------------

  record JobRow(
      String id,
      String sourceConfigId,
      String jobKey,
      String status,
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
      Instant nextAttemptAt,
      int retryCount) {}

  @Select(
      """
      SELECT id, source_config_id, job_key, status, cursor, checkpoint, total_fetched,
             total_staged, total_handed_off, total_rejected, lease_owner, lease_until,
             last_error, created_by, consecutive_failures, next_attempt_at, retry_count
      FROM ing_collect_job WHERE id = #{id}
      """)
  Optional<JobRow> findJobById(@Param("id") String id);

  @Select(
      """
      SELECT id, source_config_id, job_key, status, cursor, checkpoint, total_fetched,
             total_staged, total_handed_off, total_rejected, lease_owner, lease_until,
             last_error, created_by, consecutive_failures, next_attempt_at, retry_count
      FROM ing_collect_job WHERE source_config_id = #{sourceConfigId} AND job_key = #{jobKey}
      """)
  Optional<JobRow> findJobByKey(
      @Param("sourceConfigId") String sourceConfigId, @Param("jobKey") String jobKey);

  @Select(
      """
      SELECT id, source_config_id, job_key, status, cursor, checkpoint, total_fetched,
             total_staged, total_handed_off, total_rejected, lease_owner, lease_until,
             last_error, created_by, consecutive_failures, next_attempt_at, retry_count
      FROM ing_collect_job
      ORDER BY created_at DESC
      LIMIT #{limit}
      """)
  List<JobRow> listJobs(@Param("limit") int limit);

  @Select(
      """
      SELECT id, source_config_id, job_key, status, cursor, checkpoint, total_fetched,
             total_staged, total_handed_off, total_rejected, lease_owner, lease_until,
             last_error, created_by, consecutive_failures, next_attempt_at, retry_count
      FROM ing_collect_job
      WHERE source_config_id = #{sourceConfigId}
      ORDER BY created_at DESC
      LIMIT #{limit}
      """)
  List<JobRow> listJobsBySource(
      @Param("sourceConfigId") String sourceConfigId, @Param("limit") int limit);

  @Insert(
      """
      INSERT INTO ing_collect_job (id, source_config_id, job_key, status, created_by)
      VALUES (#{id}, #{sourceConfigId}, #{jobKey}, 'PENDING', #{createdBy})
      ON CONFLICT (source_config_id, job_key) DO NOTHING
      """)
  int insertJob(
      @Param("id") String id,
      @Param("sourceConfigId") String sourceConfigId,
      @Param("jobKey") String jobKey,
      @Param("createdBy") String createdBy);

  /**
   * 领取任务租约。
   *
   * <p>条件同时包含「租约已过期或被自己持有」与「状态可领取」。使用 {@code FOR UPDATE SKIP
   * LOCKED} 让多个实例并发领取时各取一个而互不阻塞。受影响行数为 1 才代表领取成功。
   */
  @Update(
      """
      WITH candidate AS (
        SELECT id FROM ing_collect_job
        WHERE id = #{jobId}
          AND status IN ('PENDING', 'RUNNING')
          AND (lease_until IS NULL OR lease_until < now() OR lease_owner = #{owner})
        FOR UPDATE SKIP LOCKED
      )
      UPDATE ing_collect_job j
      SET lease_owner = #{owner},
          lease_until = now() + (interval '1 second' * #{leaseSeconds}),
          status = 'RUNNING',
          updated_at = now()
      FROM candidate c
      WHERE j.id = c.id
      """)
  int claimLease(
      @Param("jobId") String jobId,
      @Param("owner") String owner,
      @Param("leaseSeconds") int leaseSeconds);

  /** 延长租约：只在仍由本人持有时生效，避免把别人刚接管的租约抢回来。 */
  @Update(
      """
      UPDATE ing_collect_job
      SET lease_until = now() + (interval '1 second' * #{leaseSeconds}), updated_at = now()
      WHERE id = #{jobId} AND lease_owner = #{owner}
      """)
  int renewLease(
      @Param("jobId") String jobId,
      @Param("owner") String owner,
      @Param("leaseSeconds") int leaseSeconds);

  /** 释放租约但不改状态：把「可被其他实例接管」与「任务是否结束」两件事分开。 */
  @Update(
      """
      UPDATE ing_collect_job
      SET lease_owner = NULL, lease_until = NULL, updated_at = now()
      WHERE id = #{jobId} AND lease_owner = #{owner}
      """)
  int releaseLease(@Param("jobId") String jobId, @Param("owner") String owner);

  @Update(
      """
      UPDATE ing_collect_job
      SET cursor = #{cursor},
          total_fetched = total_fetched + #{fetchedDelta},
          total_staged = total_staged + #{stagedDelta},
          total_rejected = total_rejected + #{rejectedDelta},
          updated_at = now()
      WHERE id = #{jobId}
      """)
  int recordFetchProgress(
      @Param("jobId") String jobId,
      @Param("cursor") String cursor,
      @Param("fetchedDelta") int fetchedDelta,
      @Param("stagedDelta") int stagedDelta,
      @Param("rejectedDelta") int rejectedDelta);

  /**
   * 前移检查点。
   *
   * <p>条件 {@code checkpoint IS DISTINCT FROM #{checkpoint}} 之外还要求新游标与当前不同，
   * 且状态仍在运行中：检查点只在前移时更新，任何回退请求都会被这条 WHERE 拦掉。
   */
  @Update(
      """
      UPDATE ing_collect_job
      SET checkpoint = #{checkpoint},
          total_handed_off = total_handed_off + #{handedOffDelta},
          updated_at = now()
      WHERE id = #{jobId}
        AND (checkpoint IS NULL OR checkpoint <> #{checkpoint})
      """)
  int advanceCheckpoint(
      @Param("jobId") String jobId,
      @Param("checkpoint") String checkpoint,
      @Param("handedOffDelta") int handedOffDelta);

  @Update(
      """
      UPDATE ing_collect_job
      SET status = #{status},
          last_error = #{lastError},
          finished_at = CASE WHEN #{status} IN ('SUCCEEDED', 'FAILED', 'STOPPED')
                              THEN now() ELSE finished_at END,
          updated_at = now()
      WHERE id = #{jobId}
      """)
  int updateJobStatus(
      @Param("jobId") String jobId,
      @Param("status") String status,
      @Param("lastError") String lastError);

  /**
   * 把失败的任务放回队列（人工重试）。
   *
   * <p>只改「能不能再被领取」这件事，<b>不动游标、不动检查点、不动任何 total_* 计数</b>：
   * 检查点记录的是「已确认交接的位置」，重试从那里继续，因此已经交接过的那批记录不会被重复
   * 交付（core 侧还有幂等键兜底，但正确性不该依赖下游兜底）。AC-IMPL-FR08-02 要求的
   * 「历史成功记录保留、不新增重复实体、能定位失败点」三条都由这一点支撑。
   *
   * <p>为什么同时清空 {@code lease_owner}/{@code lease_until}：失败时租约可能还没到期，
   * 不清空的话重试会等到租约自然过期才被领取，人看到的是「点了重试但没反应」。
   * 为什么清空 {@code next_attempt_at}：见 V9 迁移注释——重试是有意的重来，不该被上一轮退避挡住。
   *
   * <p>{@code retry_count + 1} 与状态变更在同一条 UPDATE 里：分开写会在两次写入之间留下
   * 「已重新排队但次数没加」的窗口，统计与审计就对不上。
   */
  @Update(
      """
      UPDATE ing_collect_job
      SET status = 'PENDING',
          last_error = NULL,
          next_attempt_at = NULL,
          lease_owner = NULL,
          lease_until = NULL,
          finished_at = NULL,
          retry_count = retry_count + 1,
          updated_at = now()
      WHERE id = #{jobId} AND status = 'FAILED'
      """)
  int requeueFailedJob(@Param("jobId") String jobId);

  @Update(
      """
      UPDATE ing_collect_job
      SET last_error = #{lastError}, updated_at = now()
      WHERE id = #{jobId}
      """)
  int recordJobError(@Param("jobId") String jobId, @Param("lastError") String lastError);

  /**
   * 记录一次抓取失败并安排下一次尝试时刻。
   *
   * <p>{@code consecutive_failures + 1} 与 {@code next_attempt_at} 必须在同一条 UPDATE 里算：
   * 分两条写会让「失败次数已加、退避时刻没设」成为一个可观察的中间状态，此时取任务的查询会把
   * 它当成可立刻重试，退避就整个失效了。
   *
   * <p>退避时长由调用方按运行参数算好后传入（秒）。不在这里写死公式：公式要读配置，SQL 读不到。
   */
  @Update(
      """
      UPDATE ing_collect_job
      SET consecutive_failures = consecutive_failures + 1,
          next_attempt_at = now() + make_interval(secs => #{backoffSeconds}),
          last_error = #{lastError},
          updated_at = now()
      WHERE id = #{jobId}
      """)
  int recordFetchFailure(
      @Param("jobId") String jobId,
      @Param("lastError") String lastError,
      @Param("backoffSeconds") int backoffSeconds);

  /**
   * 抓取恢复正常：连续失败计数归零、退避窗口取消。
   *
   * <p>要与抓取成功在同一次本地事务里完成（调用方走 {@code CollectUnitOfWork}）。只重置计数
   * 而不清 {@code next_attempt_at} 会让任务带着残留的等待时间，看起来像「成功之后反而被罚停」。
   */
  @Update(
      """
      UPDATE ing_collect_job
      SET consecutive_failures = 0,
          next_attempt_at = NULL,
          updated_at = now()
      WHERE id = #{jobId} AND (consecutive_failures <> 0 OR next_attempt_at IS NOT NULL)
      """)
  int clearFetchFailures(@Param("jobId") String jobId);

  @Select(
      """
      SELECT count(*) FROM ing_collect_job
      WHERE status IN ('PENDING', 'RUNNING')
      """)
  long countOpenJobs();

  // ---------------------------------------------------------------------------
  // 交接暂存
  // ---------------------------------------------------------------------------

  record StagedRow(
      String id,
      String jobId,
      String sourceConfigId,
      String sourceRecordId,
      String contentFingerprint,
      String payloadJson,
      String dataNature,
      Instant sourceUpdatedAt,
      LocalDate sourcePublishedAt,
      Instant fetchedAt,
      String handoffState,
      String handoffBatchId,
      int handoffAttempts,
      String lastError,
      String coreReceiptStatus,
      String coreAchievementId) {}

  @Insert(
      """
      INSERT INTO ing_staged_record
        (id, job_id, source_config_id, source_record_id, content_fingerprint, payload, data_nature,
         source_updated_at, source_published_at, fetched_at, handoff_state)
      VALUES
        (#{id}, #{jobId}, #{sourceConfigId}, #{sourceRecordId}, #{contentFingerprint},
         #{payload}::jsonb, #{dataNature}, #{sourceUpdatedAt}, #{sourcePublishedAt},
         #{fetchedAt}, 'STAGED')
      ON CONFLICT (source_config_id, source_record_id, content_fingerprint) DO NOTHING
      """)
  int insertStagedIfAbsent(
      @Param("id") String id,
      @Param("jobId") String jobId,
      @Param("sourceConfigId") String sourceConfigId,
      @Param("sourceRecordId") String sourceRecordId,
      @Param("contentFingerprint") String contentFingerprint,
      @Param("payload") String payload,
      @Param("dataNature") String dataNature,
      @Param("sourceUpdatedAt") Instant sourceUpdatedAt,
      @Param("sourcePublishedAt") LocalDate sourcePublishedAt,
      @Param("fetchedAt") Instant fetchedAt);

  // 取件按 job_id 而非 source_config_id：
  // 暂存去重是按来源的（见 uk_ing_staged_handoff_key），所以同来源的兄弟任务重复抓到的记录
  // 只会留下先到的那一行、并归先到的那个任务所有。若取件也按来源过滤，任一任务都会把兄弟
  // 任务的暂存记录领走、挂到自己的批次上，于是「交接数」记在没抓过这些记录的任务头上，
  // 出现交接多于暂存的假账，而真正抓到它们的任务反而永远等不到自己的记录被交接。
  // 批次的 job_id 取自发起任务，因此取件与批次必须用同一个归属维度。
  @Select(
      """
      SELECT id, job_id, source_config_id, source_record_id, content_fingerprint,
             payload::text AS payload_json, data_nature, source_updated_at, source_published_at,
             fetched_at, handoff_state, handoff_batch_id, handoff_attempts, last_error,
             core_receipt_status, core_achievement_id
      FROM ing_staged_record
      WHERE job_id = #{jobId} AND handoff_state = 'STAGED'
      ORDER BY fetched_at, id
      LIMIT #{limit}
      """)
  List<StagedRow> listPendingForHandoff(@Param("jobId") String jobId, @Param("limit") int limit);

  @Select(
      """
      SELECT id, job_id, source_config_id, source_record_id, content_fingerprint,
             payload::text AS payload_json, data_nature, source_updated_at, source_published_at,
             fetched_at, handoff_state, handoff_batch_id, handoff_attempts, last_error,
             core_receipt_status, core_achievement_id
      FROM ing_staged_record WHERE handoff_batch_id = #{batchId} ORDER BY id
      """)
  List<StagedRow> listByBatch(@Param("batchId") String batchId);

  @Update(
      """
      UPDATE ing_staged_record
      SET handoff_state = 'HANDED_OFF',
          handoff_batch_id = #{batchId},
          core_receipt_status = #{receiptStatus},
          core_achievement_id = #{achievementId},
          last_error = NULL
      WHERE id = #{id}
      """)
  int markHandedOff(
      @Param("id") String id,
      @Param("batchId") String batchId,
      @Param("receiptStatus") String receiptStatus,
      @Param("achievementId") String achievementId);

  @Update(
      """
      UPDATE ing_staged_record
      SET handoff_state = 'FAILED',
          handoff_attempts = handoff_attempts + 1,
          last_error = #{error}
      WHERE id = #{id}
      """)
  int markStagedFailed(@Param("id") String id, @Param("error") String error);

  @Update(
      """
      UPDATE ing_staged_record
      SET handoff_attempts = handoff_attempts + 1, last_error = NULL
      WHERE id = #{id}
      """)
  int incrementStagedAttempt(@Param("id") String id);

  /**
   * 各交接状态的计数行。
   *
   * <p>列一律显式别名成无下划线的名字：映射开了
   * {@code map-underscore-to-camel-case}，若把列名交给默认规则处理，返回类型一旦是
   * {@code Map} 就会在「键名是否被驼峰化」这点上依赖 MyBatis 版本行为。别名成单段小写名后
   * 两种规则下取值一致，不再有歧义。
   */
  record StateCountRow(String state, long amount) {}

  @Select(
      """
      SELECT handoff_state AS state, count(*) AS amount FROM ing_staged_record
      WHERE source_config_id = #{sourceConfigId} GROUP BY handoff_state
      """)
  List<StateCountRow> countStagedByState(@Param("sourceConfigId") String sourceConfigId);

  @Select("SELECT count(*) FROM ing_staged_record")
  long countStagedAll();

  /**
   * 把一批暂存记录挂到批次上。
   *
   * <p>必要性：批次与暂存记录是两张表，若只在「交接成功」时才回写 {@code handoff_batch_id}，
   * 那么批次刚建好、还没交接之前，按批次查记录会得到空集合——交接流程会先把批次判成
   * 「批次与记录不一致」的异常，整条链路任何一批都走不通。因此关联必须发生在建批次的那一刻。
   *
   * <p>只更新仍处于 STAGED 的行：已 HANDED_OFF 或 FAILED 的记录不应被后来的批次改写归属。
   */
  @Update(
      """
      UPDATE ing_staged_record
      SET handoff_batch_id = #{batchId}
      WHERE id = ANY(#{ids}::text[]) AND handoff_state = 'STAGED'
      """)
  int assignBatch(@Param("batchId") String batchId, @Param("ids") String[] ids);

  // ---------------------------------------------------------------------------
  // 交接批次
  // ---------------------------------------------------------------------------

  record BatchRow(
      String id,
      String jobId,
      String batchKey,
      String status,
      int recordCount,
      int attempts,
      Instant nextAttemptAt,
      Instant ackedAt,
      String lastError,
      String coreReceiptJson,
      Instant createdAt) {}

  @Insert(
      """
      INSERT INTO ing_handoff_batch (id, job_id, batch_key, status, record_count)
      VALUES (#{id}, #{jobId}, #{batchKey}, 'PENDING', #{recordCount})
      ON CONFLICT (batch_key) DO NOTHING
      """)
  int insertBatch(
      @Param("id") String id,
      @Param("jobId") String jobId,
      @Param("batchKey") String batchKey,
      @Param("recordCount") int recordCount);

  @Select(
      """
      SELECT id, job_id, batch_key, status, record_count, attempts, next_attempt_at, acked_at,
             last_error, core_receipt::text AS core_receipt_json, created_at
      FROM ing_handoff_batch WHERE batch_key = #{batchKey}
      """)
  Optional<BatchRow> findBatchByKey(@Param("batchKey") String batchKey);

  @Select(
      """
      SELECT id, job_id, batch_key, status, record_count, attempts, next_attempt_at, acked_at,
             last_error, core_receipt::text AS core_receipt_json, created_at
      FROM ing_handoff_batch
      WHERE job_id = #{jobId} AND status = 'PENDING' AND next_attempt_at <= now()
      ORDER BY created_at
      LIMIT 1
      """)
  Optional<BatchRow> findNextPendingBatch(@Param("jobId") String jobId);

  @Select(
      """
      SELECT id, job_id, batch_key, status, record_count, attempts, next_attempt_at, acked_at,
             last_error, core_receipt::text AS core_receipt_json, created_at
      FROM ing_handoff_batch WHERE job_id = #{jobId} ORDER BY created_at DESC LIMIT #{limit}
      """)
  List<BatchRow> listBatchesByJob(@Param("jobId") String jobId, @Param("limit") int limit);

  @Select("SELECT count(*) FROM ing_handoff_batch WHERE status = 'PENDING'")
  long countPendingBatches();

  @Select("SELECT count(*) FROM ing_handoff_batch WHERE status = 'FAILED'")
  long countFailedBatches();

  @Update(
      """
      UPDATE ing_handoff_batch
      SET status = 'ACKED', acked_at = now(), attempts = attempts + 1,
          core_receipt = #{receipt}::jsonb, last_error = NULL
      WHERE id = #{id}
      """)
  int markBatchAcked(@Param("id") String id, @Param("receipt") String receipt);

  @Update(
      """
      UPDATE ing_handoff_batch
      SET status = #{status},
          attempts = attempts + 1,
          next_attempt_at = now() + (interval '1 second' * #{backoffSeconds}),
          last_error = #{error}
      WHERE id = #{id}
      """)
  int markBatchFailed(
      @Param("id") String id,
      @Param("status") String status,
      @Param("error") String error,
      @Param("backoffSeconds") int backoffSeconds);

  // ---------------------------------------------------------------------------
  // 运行日志
  // ---------------------------------------------------------------------------

  @Insert(
      """
      INSERT INTO ing_fetch_log
        (id, job_id, source_config_id, phase, outcome, fetched_count, staged_count,
         rejected_count, detail, trace_id)
      VALUES
        (#{id}, #{jobId}, #{sourceConfigId}, #{phase}, #{outcome}, #{fetchedCount},
         #{stagedCount}, #{rejectedCount}, #{detail}::jsonb, #{traceId})
      """)
  int insertLog(
      @Param("id") String id,
      @Param("jobId") String jobId,
      @Param("sourceConfigId") String sourceConfigId,
      @Param("phase") String phase,
      @Param("outcome") String outcome,
      @Param("fetchedCount") Integer fetchedCount,
      @Param("stagedCount") Integer stagedCount,
      @Param("rejectedCount") Integer rejectedCount,
      @Param("detail") String detail,
      @Param("traceId") String traceId);

  record LogRow(
      String id,
      String jobId,
      String phase,
      String outcome,
      Integer fetchedCount,
      Integer stagedCount,
      Integer rejectedCount,
      String detailJson,
      Instant occurredAt) {}

  @Select(
      """
      SELECT id, job_id, phase, outcome, fetched_count, staged_count, rejected_count,
             detail::text AS detail_json, occurred_at
      FROM ing_fetch_log WHERE job_id = #{jobId} ORDER BY occurred_at DESC LIMIT #{limit}
      """)
  List<LogRow> listLogsByJob(@Param("jobId") String jobId, @Param("limit") int limit);

  // ---------------------------------------------------------------------------
  // 后台执行器
  // ---------------------------------------------------------------------------

  /**
   * 可执行任务的标识，按最近未被处理的时间升序。
   *
   * <p>按 {@code updated_at} 升序而不是 {@code created_at}：每次推进都会刷新 {@code updated_at}，
   * 因此刚处理过的任务自动排到队尾，落后的任务优先获得执行机会。若按创建时间排序，一个长期运行的
   * 任务会一直占据队首，后面的任务永远轮不到。
   *
   * <p>不含 PAUSED：暂停是管理员的显式决定，后台执行器不得把它自动恢复。
   *
   * <p>业务周期门控（{@code next_due_at}）只挡「还没开始的执行」，不挡「已经抓过东西的执行」：
   * 一个来源若有两个任务，其中一个完成时会把到期时间推到下一个周期，此时另一个正在翻页的任务
   * 若也被挡住，就会停在半路——周期管的是「多久开一次新一轮」，不是「一轮能不能做完」。
   */
  @Select(
      """
      SELECT j.id FROM ing_collect_job j
      JOIN ing_source_run_config c ON c.id = j.source_config_id
      WHERE j.status IN ('PENDING', 'RUNNING')
        AND (j.next_attempt_at IS NULL OR j.next_attempt_at <= now())
        AND (j.total_fetched > 0 OR c.next_due_at IS NULL OR c.next_due_at <= now())
      ORDER BY j.updated_at, j.id
      LIMIT #{limit}
      """)
  List<String> listRunnableJobIds(@Param("limit") int limit);
}
