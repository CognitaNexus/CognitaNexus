package com.cognitanexus.core.governance.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 治理域持久化：去重候选、合并台账、异常，以及合并所需的实体重定向写入。
 *
 * <p>依据：架构 V0.2 §5.2、§5.4。候选状态可变，台账只追加；数据库层对 gov_merge_ledger
 * 另有 REVOKE 兜底，业务代码无法改写历史。
 */
@Mapper
public interface GovernanceMapper {

  // ---------------------------------------------------------------------------
  // 去重候选
  // ---------------------------------------------------------------------------

  record CandidateRow(
      String id,
      String achievementType,
      String leftAchievementId,
      String rightAchievementId,
      String evidenceJson,
      String status,
      Instant createdAt,
      Instant resolvedAt,
      String resolvedBy,
      String resolutionReason,
      String rebasedToId) {}

  /**
   * 登记候选。
   *
   * <p>左右顺序在写入前已规范化为字典序，使 (left, right) 与 (right, left) 命中同一条唯一约束，
   * 不会因为比较方向不同而生成两条重复候选。
   */
  @Insert(
      """
      INSERT INTO gov_dedup_candidate
        (id, achievement_type, left_achievement_id, right_achievement_id, evidence, status)
      VALUES
        (#{id}, #{achievementType}, #{leftAchievementId}, #{rightAchievementId},
         #{evidenceJson}::jsonb, 'OPEN')
      ON CONFLICT (left_achievement_id, right_achievement_id) DO NOTHING
      """)
  int insertCandidateIfAbsent(
      @Param("id") String id,
      @Param("achievementType") String achievementType,
      @Param("leftAchievementId") String leftAchievementId,
      @Param("rightAchievementId") String rightAchievementId,
      @Param("evidenceJson") String evidenceJson);

  @Select(
      """
      SELECT id AS id, achievement_type AS achievementType,
             left_achievement_id AS leftAchievementId,
             right_achievement_id AS rightAchievementId,
             evidence::text AS evidenceJson, status AS status, created_at AS createdAt,
             resolved_at AS resolvedAt, resolved_by AS resolvedBy,
             resolution_reason AS resolutionReason, rebased_to_id AS rebasedToId
      FROM gov_dedup_candidate
      WHERE status = #{status}
      ORDER BY created_at DESC, id
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<CandidateRow> listCandidates(
      @Param("status") String status, @Param("limit") int limit, @Param("offset") int offset);

  @Select(
      """
      SELECT count(*) FROM gov_dedup_candidate WHERE status = #{status}
      """)
  long countCandidates(@Param("status") String status);

  // ---------------------------------------------------------------------------
  // 治理工作台：候选的筛选、排序与分档
  // ---------------------------------------------------------------------------

  /**
   * 候选列表（带相似度）。
   *
   * <p>相似度作为独立列返回，而不是只留在 evidence 里：待办列表要按它排序、要按它分档，
   * 让前端去解析 JSON 才能排序，等于把「可信度」这件核心信息变成展示细节。
   *
   * <p>排序走 {@code <choose>} 白名单分支而不是拼接字符串：拼接会把排序列变成注入面，
   * 而白名单分支在取值非法时落到默认排序——这里的默认是「相似度降序」，
   * 也就是「最可能重复的排在最前」，与治理人员的处理顺序一致。
   */
  @Select(
      """
      <script>
      SELECT id AS id, achievement_type AS achievementType,
             left_achievement_id AS leftAchievementId,
             right_achievement_id AS rightAchievementId,
             evidence::text AS evidenceJson, status AS status, created_at AS createdAt,
             resolved_at AS resolvedAt, resolved_by AS resolvedBy,
             resolution_reason AS resolutionReason, rebased_to_id AS rebasedToId,
             coalesce((evidence->>'similarity')::numeric, 0) AS similarity
      FROM gov_dedup_candidate
      WHERE status = #{status}
        <if test="minSimilarity != null">
          AND coalesce((evidence->>'similarity')::numeric, 0) &gt;= #{minSimilarity}
        </if>
      ORDER BY
        <choose>
          <when test="sortBy == 'created_asc'">created_at ASC, id</when>
          <when test="sortBy == 'created_desc'">created_at DESC, id</when>
          <when test="sortBy == 'type_asc'">achievement_type ASC, coalesce((evidence->>'similarity')::numeric, 0) DESC, id</when>
          <otherwise>coalesce((evidence->>'similarity')::numeric, 0) DESC, created_at ASC, id</otherwise>
        </choose>
      LIMIT #{limit} OFFSET #{offset}
      </script>
      """)
  List<CandidateListRow> listCandidatesFiltered(
      @Param("status") String status,
      @Param("minSimilarity") Double minSimilarity,
      @Param("sortBy") String sortBy,
      @Param("limit") int limit,
      @Param("offset") int offset);

  @Select(
      """
      <script>
      SELECT count(*) FROM gov_dedup_candidate
      WHERE status = #{status}
        <if test="minSimilarity != null">
          AND coalesce((evidence->>'similarity')::numeric, 0) &gt;= #{minSimilarity}
        </if>
      </script>
      """)
  long countCandidatesFiltered(
      @Param("status") String status, @Param("minSimilarity") Double minSimilarity);

  /**
   * 按相似度分档计数。
   *
   * <p>分档的意义在于「先做什么」：相似度为 1 的候选（同标题不同标识）与刚好过线的候选在
   * 处理方式上完全不同——前者多为重复收录，后者多为无关论文的关键词巧合。只有一个总数时，
   * 治理人员只能从第一条开始逐条看，而这正是积压会持续增长的原因。
   *
   * @param highLow 高置信下限（含）
   * @param midLow 中置信下限（含）
   */
  @Select(
      """
      <script>
      SELECT
        count(*) FILTER (WHERE sim >= #{highLow}) AS highCount,
        count(*) FILTER (WHERE sim >= #{midLow} AND sim &lt; #{highLow}) AS midCount,
        count(*) FILTER (WHERE sim &lt; #{midLow}) AS lowCount,
        count(*) AS total
      FROM (
        SELECT coalesce((evidence->>'similarity')::numeric, 0) AS sim
        FROM gov_dedup_candidate WHERE status = #{status}
      ) t
      </script>
      """)
  CandidateBuckets countCandidateBuckets(
      @Param("status") String status,
      @Param("highLow") double highLow,
      @Param("midLow") double midLow);

  /**
   * 取某一相似度档里最靠前的若干候选标识。
   *
   * <p>存在的理由只有一个：积压到两万条时，「逐页勾选」本身就是不可完成的动作，而分档计数只回答
   * 「哪一档多」不回答「怎么动手」。这里按同一套阈值把一档的候选取出来，交给批量驳回。
   *
   * <p>只取出标识、不取出整行：批量的下一步是逐条独立裁决（每条都要判在不在、是否已被他人处理），
   * 这一步必须复用单条裁决的路径，不能为了快而写一条 UPDATE。排序带 {@code id} 兜底，
   * 保证同样的条件每次取到同一批，否则「驳回前 200 条」在两次调用之间会漂移。
   */
  @Select(
      """
      <script>
      SELECT t.id FROM (
        SELECT id, coalesce((evidence->>'similarity')::numeric, 0) AS sim
        FROM gov_dedup_candidate WHERE status = #{status}
      ) t
      <choose>
        <when test="bucket == 'HIGH'">WHERE t.sim &gt;= #{highLow}</when>
        <when test="bucket == 'MID'">WHERE t.sim &gt;= #{midLow} AND t.sim &lt; #{highLow}</when>
        <otherwise>WHERE t.sim &lt; #{midLow}</otherwise>
      </choose>
      ORDER BY t.sim DESC, t.id
      LIMIT #{limit}
      </script>
      """)
  List<String> listCandidateIdsByBucket(
      @Param("status") String status,
      @Param("bucket") String bucket,
      @Param("highLow") double highLow,
      @Param("midLow") double midLow,
      @Param("limit") int limit);

  /** 候选列表行：实体字段 + 相似度（相似度单独成列，便于排序与分档）。 */
  record CandidateListRow(
      String id,
      String achievementType,
      String leftAchievementId,
      String rightAchievementId,
      String evidenceJson,
      String status,
      Instant createdAt,
      Instant resolvedAt,
      String resolvedBy,
      String resolutionReason,
      String rebasedToId,
      Double similarity) {

    /** 剥掉相似度列，回到基础候选行：相似度是查询附带值，不属于候选本身。 */
    CandidateRow toRow() {
      return new CandidateRow(
          id,
          achievementType,
          leftAchievementId,
          rightAchievementId,
          evidenceJson,
          status,
          createdAt,
          resolvedAt,
          resolvedBy,
          resolutionReason,
          rebasedToId);
    }
  }

  /** 相似度分档计数。 */
  record CandidateBuckets(long highCount, long midCount, long lowCount, long total) {}

  /**
   * 候选两端成果的实时状态。
   *
   * <p>用 {@code = ANY} 一次取一批，且由调用方保证标识数量与一页候选相当（几十条）。不用
   * {@code IN} 拼接字符串：待办列表的标识来自数据库，但仍然没有理由让取值走进 SQL 文本。
   *
   * <p>年份连同精度一起取：{@code year_precision} 为 {@code UNKNOWN} 时年份为 null，
   * 只有把精度也拿回来才分得清「不知道年份」与「这一行没有年份字段」。
   */
  @Select(
      """
      SELECT id AS id, type AS achievementType, title AS title, data_nature AS dataNature,
             visibility AS visibility, effective_status AS effectiveStatus,
             merged_into_id AS mergedIntoId, publication_year AS publicationYear,
             year_precision AS yearPrecision
      FROM core_achievement
      WHERE id = ANY (#{ids})
      """)
  List<AchievementStateRow> findAchievementStates(@Param("ids") String[] ids);

  /** 成果实时状态行。 */
  record AchievementStateRow(
      String id,
      String achievementType,
      String title,
      String dataNature,
      String visibility,
      String effectiveStatus,
      String mergedIntoId,
      Integer publicationYear,
      String yearPrecision) {}

  @Select(
      """
      SELECT id AS id, achievement_type AS achievementType,
             left_achievement_id AS leftAchievementId,
             right_achievement_id AS rightAchievementId,
             evidence::text AS evidenceJson, status AS status, created_at AS createdAt,
             resolved_at AS resolvedAt, resolved_by AS resolvedBy,
             resolution_reason AS resolutionReason, rebased_to_id AS rebasedToId
      FROM gov_dedup_candidate WHERE id = #{id}
      """)
  Optional<CandidateRow> findCandidate(@Param("id") String id);

  /**
   * 两条实体之间的候选，不限状态、不分左右顺序。
   *
   * <p>改判前必须先问一句「最终实体这一对是不是已经被问过」：候选在 (左,右) 上有唯一约束，
   * 若已存在一条同样的候选，改判应当把问题指向它而不是新建——否则新建会被唯一约束挡下，
   * 而改判会失败在「一条已经问过的问题上」。
   *
   * <p>取最早登记的一条（created_at, id）：同一对实体在正常路径上只会有一条候选，出现多条只
   * 可能是历史数据，此时指向最早的那条与「问题最早是什么时候提出的」一致。
   */
  @Select(
      """
      SELECT id AS id, achievement_type AS achievementType,
             left_achievement_id AS leftAchievementId,
             right_achievement_id AS rightAchievementId,
             evidence::text AS evidenceJson, status AS status, created_at AS createdAt,
             resolved_at AS resolvedAt, resolved_by AS resolvedBy,
             resolution_reason AS resolutionReason, rebased_to_id AS rebasedToId
      FROM gov_dedup_candidate
      WHERE (left_achievement_id = #{leftId} AND right_achievement_id = #{rightId})
         OR (left_achievement_id = #{rightId} AND right_achievement_id = #{leftId})
      ORDER BY created_at, id
      LIMIT 1
      """)
  Optional<CandidateRow> findCandidateBetween(
      @Param("leftId") String leftId, @Param("rightId") String rightId);

  @Select(
      """
      SELECT count(*) FROM gov_dedup_candidate
      WHERE status = 'OPEN'
        AND (left_achievement_id = #{achievementId} OR right_achievement_id = #{achievementId})
      """)
  long countOpenForAchievement(@Param("achievementId") String achievementId);

  /**
   * 两条实体之间的 OPEN 候选数（不分左右顺序）。
   *
   * <p>单独一条查询而不是把候选列表捞回来在内存里筛：待治理候选在最坏情况下是万级，为了回答
   * 「这两条之间还有几条候选」把全表拉进应用层，会让一次只读预览变成一次全量扫描。
   */
  @Select(
      """
      SELECT count(*) FROM gov_dedup_candidate
      WHERE status = 'OPEN'
        AND ((left_achievement_id = #{leftId} AND right_achievement_id = #{rightId})
          OR (left_achievement_id = #{rightId} AND right_achievement_id = #{leftId}))
      """)
  long countOpenBetween(@Param("leftId") String leftId, @Param("rightId") String rightId);

  /** 裁决候选。条件 status='OPEN' 保证重复裁决返回 0 行影响而不是覆盖既有结论。 */
  @Update(
      """
      UPDATE gov_dedup_candidate
      SET status = #{status}, resolved_at = now(), resolved_by = #{resolvedBy},
          resolution_reason = #{reason}
      WHERE id = #{id} AND status = 'OPEN'
      """)
  int resolveCandidate(
      @Param("id") String id,
      @Param("status") String status,
      @Param("resolvedBy") String resolvedBy,
      @Param("reason") String reason);

  /**
   * 把候选改判到另一条候选。
   *
   * <p>状态与承接指针在同一条语句里写入：分开写会留下一个「已标记改判、但看不出改判到哪」的
   * 中间态，而这条中间态恰好是治理人员最需要查证的信息。条件 status='OPEN' 与单条裁决一致，
   * 重复改判返回 0 行影响而不是覆盖既有结论。
   */
  @Update(
      """
      UPDATE gov_dedup_candidate
      SET status = 'REBASED', resolved_at = now(), resolved_by = #{resolvedBy},
          resolution_reason = #{reason}, rebased_to_id = #{rebasedToId}
      WHERE id = #{id} AND status = 'OPEN'
      """)
  int resolveCandidateRebased(
      @Param("id") String id,
      @Param("resolvedBy") String resolvedBy,
      @Param("reason") String reason,
      @Param("rebasedToId") String rebasedToId);

  /**
   * 随合并一并裁决两条实体之间的 OPEN 候选。
   *
   * <p>为什么必须有这一步：候选问的是「这两条是不是同一条」，而合并就是对这个问题的回答。
   * 合并完成后若候选仍留在 OPEN 里，它就变成一条谁也处理不掉的死条目——再点合并会被
   * 「已被合并过」拒绝，点驳回又是在回答一个已经被回答过的问题。而工作台按相似度降序，
   * 这类候选的相似度通常最高，于是它们会一直排在最前面，把真正待判断的候选挤到后面。
   *
   * <p>条件 status='OPEN' 与单条裁决一致：已经有人下过结论的候选不被覆盖。
   */
  @Update(
      """
      UPDATE gov_dedup_candidate
      SET status = #{status}, resolved_at = now(), resolved_by = #{resolvedBy},
          resolution_reason = #{reason}
      WHERE status = 'OPEN'
        AND ((left_achievement_id = #{leftId} AND right_achievement_id = #{rightId})
          OR (left_achievement_id = #{rightId} AND right_achievement_id = #{leftId}))
      """)
  int resolveOpenCandidatesBetween(
      @Param("leftId") String leftId,
      @Param("rightId") String rightId,
      @Param("status") String status,
      @Param("resolvedBy") String resolvedBy,
      @Param("reason") String reason);

  // ---------------------------------------------------------------------------
  // 合并台账
  // ---------------------------------------------------------------------------

  @Insert(
      """
      INSERT INTO gov_merge_ledger
        (id, operation, survivor_id, merged_id, entity_type, snapshot, reason, operator_id)
      VALUES
        (#{id}, #{operation}, #{survivorId}, #{mergedId}, #{entityType},
         #{snapshotJson}::jsonb, #{reason}, #{operatorId})
      """)
  void insertLedger(
      @Param("id") String id,
      @Param("operation") String operation,
      @Param("survivorId") String survivorId,
      @Param("mergedId") String mergedId,
      @Param("entityType") String entityType,
      @Param("snapshotJson") String snapshotJson,
      @Param("reason") String reason,
      @Param("operatorId") String operatorId);

  record LedgerRow(
      String id,
      String operation,
      String survivorId,
      String mergedId,
      String entityType,
      String snapshotJson,
      String reason,
      String operatorId,
      Instant occurredAt) {}

  @Select(
      """
      SELECT id AS id, operation AS operation, survivor_id AS survivorId,
             merged_id AS mergedId, entity_type AS entityType, snapshot::text AS snapshotJson,
             reason AS reason, operator_id AS operatorId, occurred_at AS occurredAt
      FROM gov_merge_ledger
      WHERE merged_id = #{entityId} OR survivor_id = #{entityId}
      ORDER BY occurred_at DESC, id
      LIMIT #{limit}
      """)
  List<LedgerRow> listLedgerByEntity(@Param("entityId") String entityId, @Param("limit") int limit);

  // ---------------------------------------------------------------------------
  // 异常
  // ---------------------------------------------------------------------------

  @Insert(
      """
      INSERT INTO gov_anomaly (id, kind, source_record_id, entity_id, detail, status)
      VALUES (#{id}, #{kind}, #{sourceRecordId}, #{entityId}, #{detailJson}::jsonb, 'OPEN')
      """)
  void insertAnomaly(
      @Param("id") String id,
      @Param("kind") String kind,
      @Param("sourceRecordId") String sourceRecordId,
      @Param("entityId") String entityId,
      @Param("detailJson") String detailJson);

  record AnomalyRow(
      String id,
      String kind,
      String sourceRecordId,
      String entityId,
      String detailJson,
      String status,
      Instant createdAt,
      Instant resolvedAt) {}

  @Select(
      """
      SELECT id AS id, kind AS kind, source_record_id AS sourceRecordId, entity_id AS entityId,
             detail::text AS detailJson, status AS status, created_at AS createdAt,
             resolved_at AS resolvedAt
      FROM gov_anomaly
      WHERE status = #{status}
      ORDER BY created_at DESC, id
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<AnomalyRow> listAnomalies(
      @Param("status") String status, @Param("limit") int limit, @Param("offset") int offset);

  @Select("SELECT count(*) FROM gov_anomaly WHERE status = #{status}")
  long countAnomalies(@Param("status") String status);

  @Update(
      """
      UPDATE gov_anomaly SET status = 'RESOLVED', resolved_at = now()
      WHERE id = #{id} AND status = 'OPEN'
      """)
  int resolveAnomaly(@Param("id") String id);

  // ---------------------------------------------------------------------------
  // 数据质量巡检（只读探测 + 异常去重写入）
  // ---------------------------------------------------------------------------
  //
  // 探测查询一律显式给出 LIMIT：巡检跑在业务库上，没有上限的「扫全表」会在数据量涨上来之后
  // 变成一次事故。上限触发时报告里会写明「已截断」，不把截断后的数字当成全量结论。

  /** 年份越界：合法区间 [1800, 今年+1]。明年允许出现（跨年预发布），更远的一律视为字段污染。 */
  @Select(
      """
      SELECT id FROM core_achievement
      WHERE publication_year IS NOT NULL
        AND (publication_year < 1800 OR publication_year > extract(year from now()) + 1)
      ORDER BY id
      LIMIT #{limit}
      """)
  List<String> yearOutOfRangeIds(@Param("limit") int limit);

  @Select(
      """
      SELECT id, title, type, publication_year AS publicationYear
      FROM core_achievement
      WHERE publication_year IS NOT NULL
        AND (publication_year < 1800 OR publication_year > extract(year from now()) + 1)
      ORDER BY id
      LIMIT #{limit}
      """)
  List<AnomalySampleRow> yearOutOfRangeSamples(@Param("limit") int limit);

  /**
   * 领域归属冲突：同一成果同一领域既有来源给定、又有推断归属。
   *
   * <p>来源分类在场时推断归属必须已经被撤销，这是既定的覆盖规则。仍然并存说明出现过「先推断、
   * 后补来源」而撤销那一步没跑到的路径，需要人工核查——不能由巡检自行删除：那会掩盖一条真实发生
   * 过的漏撤销。
   */
  @Select(
      """
      SELECT DISTINCT t.achievement_id
      FROM core_achievement_topic t
      WHERE t.assignment_kind = 'INFERRED'
        AND EXISTS (
          SELECT 1 FROM core_achievement_topic s
          WHERE s.achievement_id = t.achievement_id AND s.topic_id = t.topic_id
            AND s.assignment_kind = 'SOURCE')
      ORDER BY t.achievement_id
      LIMIT #{limit}
      """)
  List<String> topicKindConflictIds(@Param("limit") int limit);

  @Select(
      """
      SELECT DISTINCT a.id, a.title, a.type, a.publication_year AS publicationYear
      FROM core_achievement a
      JOIN core_achievement_topic t ON t.achievement_id = a.id AND t.assignment_kind = 'INFERRED'
      WHERE EXISTS (
          SELECT 1 FROM core_achievement_topic s
          WHERE s.achievement_id = t.achievement_id AND s.topic_id = t.topic_id
            AND s.assignment_kind = 'SOURCE')
      ORDER BY a.id
      LIMIT #{limit}
      """)
  List<AnomalySampleRow> topicKindConflictSamples(@Param("limit") int limit);

  /** 已生效成果按类型统计「没有任何领域归属」的条数。 */
  @Select(
      """
      SELECT a.type AS bucket, count(*) AS total
      FROM core_achievement a
      WHERE a.effective_status = 'EFFECTIVE' AND a.merged_into_id IS NULL
        AND NOT EXISTS (SELECT 1 FROM core_achievement_topic t WHERE t.achievement_id = a.id)
      GROUP BY a.type
      ORDER BY count(*) DESC
      """)
  List<AnomalyBucketRow> effectiveWithoutTopicByType();

  @Select(
      """
      SELECT a.id, a.title, a.type, a.publication_year AS publicationYear
      FROM core_achievement a
      WHERE a.effective_status = 'EFFECTIVE' AND a.merged_into_id IS NULL
        AND NOT EXISTS (SELECT 1 FROM core_achievement_topic t WHERE t.achievement_id = a.id)
      ORDER BY a.id
      LIMIT #{limit}
      """)
  List<AnomalySampleRow> effectiveWithoutTopicSamples(@Param("limit") int limit);

  @Select(
      """
      SELECT a.type AS bucket, count(*) AS total
      FROM core_achievement a
      WHERE a.effective_status = 'EFFECTIVE' AND a.merged_into_id IS NULL
        AND NOT EXISTS (SELECT 1 FROM core_contribution c WHERE c.achievement_id = a.id)
      GROUP BY a.type
      ORDER BY count(*) DESC
      """)
  List<AnomalyBucketRow> effectiveWithoutContributorByType();

  @Select(
      """
      SELECT a.id, a.title, a.type, a.publication_year AS publicationYear
      FROM core_achievement a
      WHERE a.effective_status = 'EFFECTIVE' AND a.merged_into_id IS NULL
        AND NOT EXISTS (SELECT 1 FROM core_contribution c WHERE c.achievement_id = a.id)
      ORDER BY a.id
      LIMIT #{limit}
      """)
  List<AnomalySampleRow> effectiveWithoutContributorSamples(@Param("limit") int limit);

  /**
   * 已生效论文没有登记任何稳定标识。
   *
   * <p>只查论文：获奖与项目本来就没有 DOI 这类标识，把它们算进来会让「缺少标识」变成一个
   * 永远清不掉的数字。
   */
  @Select(
      """
      SELECT a.type AS bucket, count(*) AS total
      FROM core_achievement a
      WHERE a.type = 'PAPER' AND a.effective_status = 'EFFECTIVE' AND a.merged_into_id IS NULL
        AND NOT EXISTS (
          SELECT 1 FROM core_identifier_registry r
          WHERE r.entity_type = 'ACHIEVEMENT' AND r.entity_id = a.id)
      GROUP BY a.type
      """)
  List<AnomalyBucketRow> papersWithoutIdentifierByType();

  @Select(
      """
      SELECT a.id, a.title, a.type, a.publication_year AS publicationYear
      FROM core_achievement a
      WHERE a.type = 'PAPER' AND a.effective_status = 'EFFECTIVE' AND a.merged_into_id IS NULL
        AND NOT EXISTS (
          SELECT 1 FROM core_identifier_registry r
          WHERE r.entity_type = 'ACHIEVEMENT' AND r.entity_id = a.id)
      ORDER BY a.id
      LIMIT #{limit}
      """)
  List<AnomalySampleRow> papersWithoutIdentifierSamples(@Param("limit") int limit);

  /** 同名学者分组：同名人数达到阈值的姓名。 */
  @Select(
      """
      SELECT name_norm AS nameNorm, count(*) AS total
      FROM core_scholar
      WHERE merged_into_id IS NULL AND name_norm IS NOT NULL AND name_norm <> ''
      GROUP BY name_norm
      HAVING count(*) >= #{minCount}
      ORDER BY count(*) DESC, name_norm
      LIMIT #{limit}
      """)
  List<AnomalyNameGroupRow> duplicateScholarNames(
      @Param("minCount") int minCount, @Param("limit") int limit);

  @Select(
      """
      SELECT id, display_name AS displayName, organization_name AS organizationName,
             claimed_status AS claimedStatus
      FROM core_scholar
      WHERE merged_into_id IS NULL AND name_norm = #{nameNorm}
      ORDER BY id
      LIMIT #{limit}
      """)
  List<AnomalyScholarRow> scholarsByNameNorm(
      @Param("nameNorm") String nameNorm, @Param("limit") int limit);

  /**
   * 待核验关系聚集的学者：一条学者记录上挂着的待核验关系达到阈值。
   *
   * <p>这些是「姓名相同但不确定是同一人」的真实落点：关系确实存在、也确实指向这条学者记录，
   * 却不参与确定合作边，也不构成任何可公开的合作结论。
   */
  @Select(
      """
      SELECT c.scholar_id AS scholarId, s.display_name AS displayName, count(*) AS pending
      FROM core_contribution c
      JOIN core_scholar s ON s.id = c.scholar_id
      WHERE c.relation_state = 'PENDING_VERIFY' AND s.merged_into_id IS NULL
      GROUP BY c.scholar_id, s.display_name
      HAVING count(*) >= #{minCount}
      ORDER BY count(*) DESC, c.scholar_id
      LIMIT #{limit}
      """)
  List<AmbiguousScholarRow> ambiguousScholars(
      @Param("minCount") int minCount, @Param("limit") int limit);

  @Select(
      """
      SELECT count(*) FROM (
        SELECT c.scholar_id
        FROM core_contribution c
        JOIN core_scholar s ON s.id = c.scholar_id
        WHERE c.relation_state = 'PENDING_VERIFY' AND s.merged_into_id IS NULL
        GROUP BY c.scholar_id
        HAVING count(*) >= #{minCount}) x
      """)
  long countAmbiguousScholars(@Param("minCount") int minCount);

  @Select(
      """
      SELECT count(*)
      FROM core_contribution c
      JOIN core_scholar s ON s.id = c.scholar_id
      WHERE c.relation_state = 'PENDING_VERIFY' AND s.merged_into_id IS NULL
        AND c.scholar_id IN (
          SELECT c2.scholar_id
          FROM core_contribution c2
          WHERE c2.relation_state = 'PENDING_VERIFY'
          GROUP BY c2.scholar_id
          HAVING count(*) >= #{minCount})
      """)
  long countPendingRelationsOfAmbiguous(@Param("minCount") int minCount);

  /** 按去重键找同类未处理异常：让同一个问题在多次巡检之间只占一条待办。 */
  @Select(
      """
      SELECT id FROM gov_anomaly
      WHERE kind = #{kind} AND status = 'OPEN' AND detail->>'dedupeKey' = #{dedupeKey}
      ORDER BY created_at DESC
      LIMIT 1
      """)
  String findOpenAnomalyId(@Param("kind") String kind, @Param("dedupeKey") String dedupeKey);

  /** 复核既有异常：把 detail 换成本次巡检的事实，不新增待办。 */
  @Update(
      """
      UPDATE gov_anomaly SET detail = #{detailJson}::jsonb
      WHERE id = #{id} AND status = 'OPEN'
      """)
  int refreshAnomalyDetail(@Param("id") String id, @Param("detailJson") String detailJson);

  /**
   * 按去重键取整条未处理异常。
   *
   * <p>与 {@link #findOpenAnomalyId} 的区别是它返回完整行：公众纠错的重复提交要在既有 detail 上
   * 累加计数并保留首次提交时间，只拿到 id 就得再查一次，而在「取 id → 读详情 → 写详情」之间
   * 还夹着一个并发窗口。
   */
  @Select(
      """
      SELECT id AS id, kind AS kind, source_record_id AS sourceRecordId, entity_id AS entityId,
             detail::text AS detailJson, status AS status, created_at AS createdAt,
             resolved_at AS resolvedAt
      FROM gov_anomaly
      WHERE kind = #{kind} AND status = 'OPEN' AND detail->>'dedupeKey' = #{dedupeKey}
      ORDER BY created_at DESC
      LIMIT 1
      """)
  AnomalyRow findAnomalyByDedupeKey(
      @Param("kind") String kind, @Param("dedupeKey") String dedupeKey);

  /** 某一类未处理异常条数。用于把公众纠错从总异常数里单独披露。 */
  @Select("SELECT count(*) FROM gov_anomaly WHERE status = 'OPEN' AND kind = #{kind}")
  long countOpenAnomaliesOfKind(@Param("kind") String kind);

  @Select(
      """
      SELECT id, entity_id AS entityId FROM gov_anomaly
      WHERE kind = #{kind} AND status = 'OPEN'
      ORDER BY created_at
      LIMIT #{limit}
      """)
  List<AnomalyKeyRow> openAnomaliesOfKind(@Param("kind") String kind, @Param("limit") int limit);

  record AnomalySampleRow(String id, String title, String type, Integer publicationYear) {}

  record AnomalyBucketRow(String bucket, long total) {}

  record AnomalyNameGroupRow(String nameNorm, long total) {}

  record AnomalyScholarRow(
      String id, String displayName, String organizationName, String claimedStatus) {}

  record AnomalyKeyRow(String id, String entityId) {}

  record AmbiguousScholarRow(String scholarId, String displayName, long pending) {}

  // ---------------------------------------------------------------------------
  // 合并所需的实体操作
  // ---------------------------------------------------------------------------

  /** 标记被合并实体并建立重定向。条件 merged_into_id IS NULL 防止二次合并覆盖首次重定向。 */
  @Update(
      """
      UPDATE core_achievement
      SET merged_into_id = #{survivorId}, effective_status = 'WITHDRAWN',
          version = version + 1, updated_at = now()
      WHERE id = #{mergedId} AND merged_into_id IS NULL AND id <> #{survivorId}
      """)
  int markAchievementMerged(
      @Param("mergedId") String mergedId, @Param("survivorId") String survivorId);

  @Update(
      """
      UPDATE core_scholar
      SET merged_into_id = #{survivorId}, version = version + 1, updated_at = now()
      WHERE id = #{mergedId} AND merged_into_id IS NULL AND id <> #{survivorId}
      """)
  int markScholarMerged(@Param("mergedId") String mergedId, @Param("survivorId") String survivorId);

  @Select(
      """
      SELECT achievement_id FROM core_achievement_source WHERE id = #{id}
      """)
  Optional<String> findSourceLinkAchievement(@Param("id") String id);

  @Update(
      """
      UPDATE core_achievement_source SET achievement_id = #{survivorId}
      WHERE achievement_id = #{mergedId}
        AND NOT EXISTS (
          SELECT 1 FROM core_achievement_source s2
          WHERE s2.achievement_id = #{survivorId} AND s2.source_record_id = core_achievement_source.source_record_id
        )
      """)
  int moveSourceLinks(@Param("mergedId") String mergedId, @Param("survivorId") String survivorId);

  @Update(
      """
      UPDATE core_achievement_topic SET achievement_id = #{survivorId}
      WHERE achievement_id = #{mergedId}
        AND NOT EXISTS (
          SELECT 1 FROM core_achievement_topic t2
          WHERE t2.achievement_id = #{survivorId} AND t2.topic_id = core_achievement_topic.topic_id
        )
      """)
  int moveTopicLinks(@Param("mergedId") String mergedId, @Param("survivorId") String survivorId);

  /**
   * 把被合并成果的参与关系迁到幸存实体。
   *
   * <p>先补插再删除：同一学者若在两个实体上都有关系，唯一约束会让补插跳过该行，
   * 随后删除旧行就完成了「保留一条、去掉重复」的效果，不会为同一份贡献重复计数。
   * 主键在 SQL 内生成（gen_random_uuid 为 PostgreSQL 13+ 内置），避免在应用侧逐行往返。
   */
  @Insert(
      """
      INSERT INTO core_contribution
        (id, scholar_id, achievement_id, role, author_order, relation_state, source_record_id, version)
      SELECT 'ctr_' || replace(gen_random_uuid()::text, '-', ''),
             scholar_id, #{survivorId}, role, author_order, relation_state, source_record_id, version
      FROM core_contribution
      WHERE achievement_id = #{mergedId}
      ON CONFLICT (scholar_id, achievement_id) DO NOTHING
      """)
  int insertMovedContributions(
      @Param("mergedId") String mergedId, @Param("survivorId") String survivorId);

  @Delete(
      """
      DELETE FROM core_contribution WHERE achievement_id = #{mergedId}
      """)
  int deleteContributionsOf(@Param("mergedId") String mergedId);

  /**
   * 把被合并成果的贡献所属机构搬到存活成果的对应贡献上。
   *
   * <p>为什么必须搬、而不能直接随贡献一起删：{@code core_contribution_org.contribution_id} 是
   * ON DELETE RESTRICT，删贡献前不处理子表就**违反外键**；而机构归属是「成果发生时」的事实
   * （BR-IMP-06），合并去重不该把它丢掉。
   *
   * <p>关联键取 scholar_id 而不是贡献主键：合并后贡献行是新生成的，
   * {@link #insertMovedContributions} 保证存活成果下已有该学者的贡献行；两个成果本来就有
   * 同一学者时它会命中已有那一条，机构正好并过去。两边都记了同一机构则由主键冲突吸收。
   *
   * <p>漏掉这一段的后果不是「少一条机构」而是**整个合并事务失败**：本地库 27331 个成果里有
   * 21755 个（80%）的贡献带机构归属，待合并成果落在这个区间就必然 500。
   */
  @Insert(
      """
      INSERT INTO core_contribution_org (contribution_id, organization_id)
      SELECT sc.id, co.organization_id
      FROM core_contribution_org co
      JOIN core_contribution mc ON mc.id = co.contribution_id
      JOIN core_contribution sc
        ON sc.achievement_id = #{survivorId}
       AND sc.scholar_id = mc.scholar_id
      WHERE mc.achievement_id = #{mergedId}
      ON CONFLICT (contribution_id, organization_id) DO NOTHING
      """)
  int moveContributionOrgs(
      @Param("mergedId") String mergedId, @Param("survivorId") String survivorId);

  /** 删掉被合并成果残留的机构归属。搬移后理论上已空，仍显式清理：留着就会挡住下一步的删除。 */
  @Delete(
      """
      DELETE FROM core_contribution_org
      WHERE contribution_id IN (
        SELECT id FROM core_contribution WHERE achievement_id = #{mergedId}
      )
      """)
  int deleteContributionOrgsOf(@Param("mergedId") String mergedId);

  /** 参与关系快照，用于台账记录合并前后的关系归属。 */
  @Select(
      """
      SELECT c.id || '|' || c.scholar_id || '|' || c.relation_state
      FROM core_contribution c
      WHERE c.achievement_id = #{achievementId}
      ORDER BY c.id
      """)
  List<String> snapshotContributions(@Param("achievementId") String achievementId);

  /** 某成果的参与学者（去重）。合并通知用它定位接收者，必须在 moveContributions 之前调用。 */
  @Select(
      """
      SELECT DISTINCT c.scholar_id
      FROM core_contribution c
      WHERE c.achievement_id = #{achievementId}
      """)
  List<String> listScholarIdsOfAchievement(@Param("achievementId") String achievementId);

  @Select(
      """
      SELECT cas.id || '|' || cas.source_record_id || '|' || cas.source_id
      FROM core_achievement_source cas
      WHERE cas.achievement_id = #{achievementId}
      ORDER BY cas.id
      """)
  List<String> snapshotSources(@Param("achievementId") String achievementId);

  /** 学者视角的参与关系快照：按 scholar_id 而不是 achievement_id 取值。 */
  @Select(
      """
      SELECT c.id || '|' || c.achievement_id || '|' || c.relation_state
      FROM core_contribution c
      WHERE c.scholar_id = #{scholarId}
      ORDER BY c.id
      """)
  List<String> snapshotScholarContributions(@Param("scholarId") String scholarId);

  // ---------------------------------------------------------------------------
  // 学者合并所需的实体操作
  // ---------------------------------------------------------------------------

  @Insert(
      """
      INSERT INTO core_contribution
        (id, scholar_id, achievement_id, role, author_order, relation_state, source_record_id, version)
      SELECT 'ctr_' || replace(gen_random_uuid()::text, '-', ''),
             #{survivorScholarId}, achievement_id, role, author_order, relation_state, source_record_id, version
      FROM core_contribution
      WHERE scholar_id = #{mergedScholarId}
      ON CONFLICT (scholar_id, achievement_id) DO NOTHING
      """)
  int insertMovedScholarContributions(
      @Param("mergedScholarId") String mergedScholarId,
      @Param("survivorScholarId") String survivorScholarId);

  @Delete(
      """
      DELETE FROM core_contribution WHERE scholar_id = #{mergedScholarId}
      """)
  int deleteContributionsOfScholar(@Param("mergedScholarId") String mergedScholarId);

  /** 学者视角的机构搬移：同一个成果下把被合并学者的机构并到存活学者的贡献上。 */
  @Insert(
      """
      INSERT INTO core_contribution_org (contribution_id, organization_id)
      SELECT sc.id, co.organization_id
      FROM core_contribution_org co
      JOIN core_contribution mc ON mc.id = co.contribution_id
      JOIN core_contribution sc
        ON sc.achievement_id = mc.achievement_id
       AND sc.scholar_id = #{survivorScholarId}
      WHERE mc.scholar_id = #{mergedScholarId}
      ON CONFLICT (contribution_id, organization_id) DO NOTHING
      """)
  int moveContributionOrgsOfScholar(
      @Param("mergedScholarId") String mergedScholarId,
      @Param("survivorScholarId") String survivorScholarId);

  @Delete(
      """
      DELETE FROM core_contribution_org
      WHERE contribution_id IN (
        SELECT id FROM core_contribution WHERE scholar_id = #{mergedScholarId}
      )
      """)
  int deleteContributionOrgsOfScholar(@Param("mergedScholarId") String mergedScholarId);

  @Update(
      """
      UPDATE core_scholar_name_variant SET scholar_id = #{survivorScholarId}
      WHERE scholar_id = #{mergedScholarId}
        AND NOT EXISTS (
          SELECT 1 FROM core_scholar_name_variant v2
          WHERE v2.scholar_id = #{survivorScholarId}
            AND v2.variant_norm = core_scholar_name_variant.variant_norm
        )
      """)
  int moveNameVariants(
      @Param("mergedScholarId") String mergedScholarId,
      @Param("survivorScholarId") String survivorScholarId);

  @org.apache.ibatis.annotations.Delete(
      """
      DELETE FROM core_scholar_name_variant WHERE scholar_id = #{mergedScholarId}
      """)
  int deleteNameVariantsOf(@Param("mergedScholarId") String mergedScholarId);

  @Select(
      """
      SELECT id FROM iam_portal_authority
      WHERE scholar_id = #{scholarId} AND status = 'ACTIVE'
      """)
  Optional<String> findActiveAuthorityId(@Param("scholarId") String scholarId);

  /**
   * 把被合并学者门户上的生效授权转到幸存门户。
   *
   * <p>只在幸存门户当前没有生效授权时才转移：若两边都有授权，说明存在两个不同的管理者，
   * 自动转移会改变访问控制结论，必须留给人工裁决，因此这里返回 0 行影响。
   */
  @Update(
      """
      UPDATE iam_portal_authority SET scholar_id = #{survivorScholarId}
      WHERE scholar_id = #{mergedScholarId} AND status = 'ACTIVE'
        AND NOT EXISTS (
          SELECT 1 FROM iam_portal_authority a2
          WHERE a2.scholar_id = #{survivorScholarId} AND a2.status = 'ACTIVE'
        )
      """)
  int moveActiveAuthority(
      @Param("mergedScholarId") String mergedScholarId,
      @Param("survivorScholarId") String survivorScholarId);

  @Update(
      """
      UPDATE iam_portal_authority
      SET status = 'REVOKED', revoked_at = now(), revoke_reason = #{reason}
      WHERE scholar_id = #{mergedScholarId} AND status = 'ACTIVE'
      """)
  int revokeAuthorityOfScholar(
      @Param("mergedScholarId") String mergedScholarId, @Param("reason") String reason);

  @Select("SELECT count(*) FROM gov_dedup_candidate WHERE status = 'OPEN'")
  long countOpenCandidates();

  @Select("SELECT count(*) FROM gov_anomaly WHERE status = 'OPEN'")
  long countOpenAnomalies();

  // ---------------------------------------------------------------------------
  // 合并影响预览
  // ---------------------------------------------------------------------------
  //
  // 「相似标题不得直接合并」意味着合并在本平台上永远是一次人工决定。人工决定的唯一依据是
  // 「合并后会发生什么」，而合并的后果大部分是看不见的：被合并实体的元数据被丢弃、同一学者的
  // 两条参与关系被合并成一条、来源归属因重复而不再挂接。这些都只写在台账快照里，事后才可读。
  // 下面这组查询把后果提前取出来，使「看清再决定」成为可能，而不是「先合并再看台账」。

  /** 参与关系预览行。带学者姓名，使「谁的哪条关系会迁移」可直接阅读，不必再去建映射。 */
  record PreviewContributionRow(
      String scholarId, String scholarName, String relationState, String role) {}

  @Select(
      """
      SELECT c.scholar_id AS scholarId, coalesce(s.display_name, '') AS scholarName,
             c.relation_state AS relationState, c.role AS role
      FROM core_contribution c
      LEFT JOIN core_scholar s ON s.id = c.scholar_id
      WHERE c.achievement_id = #{achievementId}
      ORDER BY c.scholar_id
      """)
  List<PreviewContributionRow> previewContributions(@Param("achievementId") String achievementId);

  record PreviewSourceRow(String sourceId, String sourceRecordId) {}

  @Select(
      """
      SELECT cas.source_id AS sourceId, cas.source_record_id AS sourceRecordId
      FROM core_achievement_source cas
      WHERE cas.achievement_id = #{achievementId}
      ORDER BY cas.source_id, cas.source_record_id
      """)
  List<PreviewSourceRow> previewSourceLinks(@Param("achievementId") String achievementId);

  @Select(
      """
      SELECT t.topic_id FROM core_achievement_topic t
      WHERE t.achievement_id = #{achievementId}
      ORDER BY t.topic_id
      """)
  List<String> previewTopicIds(@Param("achievementId") String achievementId);

  record PreviewIdentifierRow(String identifierKind, String scope, String normalizedValue) {}

  @Select(
      """
      SELECT r.identifier_kind AS identifierKind, r.scope AS scope,
             r.normalized_value AS normalizedValue
      FROM core_identifier_registry r
      WHERE r.entity_type = #{entityType} AND r.entity_id = #{entityId}
      ORDER BY r.identifier_kind, r.scope, r.normalized_value
      """)
  List<PreviewIdentifierRow> previewIdentifiers(
      @Param("entityType") String entityType, @Param("entityId") String entityId);

  /**
   * 把被合并实体的稳定标识改指幸存实体。
   *
   * <p>唯一约束在 {@code (identifier_kind, scope, normalized_value)} 上、不按实体划分，所以同一条
   * 标识不可能同时挂在两个实体上，改指不会冲突。
   *
   * <p>不转移会留下一个看不见的坑：标识仍指向已撤回实体，于是重复入库时标识查询命中一个
   * WITHDRAWN 实体，而幸存实体则可能因为「没有稳定标识」永远不满足自动发布条件。
   */
  @Update(
      """
      UPDATE core_identifier_registry SET entity_id = #{survivorId}
      WHERE entity_type = #{entityType} AND entity_id = #{mergedId}
      """)
  int moveIdentifiers(
      @Param("entityType") String entityType,
      @Param("mergedId") String mergedId,
      @Param("survivorId") String survivorId);
}
