package com.cognitanexus.core.catalog.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 引用网络持久化：参考文献观测、引用条目、以及「还该补哪几篇」的候选清单。
 *
 * <p>依据：架构 V0.2 §5.1、SRS FR06。三条 SQL 原则：
 *
 * <ol>
 *   <li>「站内」一律以 {@code visibility='PUBLIC' AND effective_status='EFFECTIVE' AND
 *       merged_into_id IS NULL} 判定，不信任调用方传来的可见性；合并后的旧实体不能作为边的端点，
 *       否则同一篇成果会在图上出现两次；
 *   <li>计数与明细用同一套连接与过滤条件。分开写两份时，明细少一条而计数不少，页面就会出现
 *       「写着 3 条、只列出 2 条」这种没人能解释的现象；
 *   <li>重采参考文献是整体替换（先删后插），因为引用列表是快照而不是增量；
 *       「这篇采过没有」由 {@code core_citation_observation} 单独承担。
 * </ol>
 */
@Mapper
public interface CitationMapper {

  record ObservationRow(
      String achievementId,
      String provider,
      String providerWorkId,
      Integer referencesTotal,
      int referencesListed,
      String outcome,
      Instant fetchedAt) {}

  record ReferenceRow(
      String id,
      String citingAchievementId,
      String citedKind,
      String citedValueNorm,
      String citedAchievementId,
      String resolution,
      Instant observedAt) {}

  record CitedWorkRow(
      String achievementId,
      String title,
      String type,
      Integer publicationYear,
      String yearPrecision,
      String venue,
      String doi) {}

  record PendingWorkRow(String achievementId, String doi, Integer citationCount) {}

  @Select(
      """
      SELECT achievement_id AS achievementId, provider AS provider,
             provider_work_id AS providerWorkId, references_total AS referencesTotal,
             references_listed AS referencesListed, outcome AS outcome, fetched_at AS fetchedAt
      FROM core_citation_observation WHERE achievement_id = #{achievementId}
      """)
  ObservationRow findObservation(@Param("achievementId") String achievementId);

  /**
   * 记录「这一篇的参考文献被采到过」。
   *
   * <p>用 upsert 而不是 insert：重采同一篇必须更新计数与时刻，而不是因为主键冲突整批失败。
   * 但 {@code created_at} 保持首次值——它回答的是「第一次采到是什么时候」，与本次采集无关。
   */
  @Insert(
      """
      INSERT INTO core_citation_observation
        (achievement_id, provider, provider_work_id, references_total, references_listed,
         outcome, fetched_at)
      VALUES
        (#{achievementId}, #{provider}, #{providerWorkId}, #{referencesTotal},
         #{referencesListed}, #{outcome}, #{fetchedAt})
      ON CONFLICT (achievement_id) DO UPDATE SET
        provider = EXCLUDED.provider,
        provider_work_id = EXCLUDED.provider_work_id,
        references_total = EXCLUDED.references_total,
        references_listed = EXCLUDED.references_listed,
        outcome = EXCLUDED.outcome,
        fetched_at = EXCLUDED.fetched_at
      """)
  int upsertObservation(
      @Param("achievementId") String achievementId,
      @Param("provider") String provider,
      @Param("providerWorkId") String providerWorkId,
      @Param("referencesTotal") Integer referencesTotal,
      @Param("referencesListed") int referencesListed,
      @Param("outcome") String outcome,
      @Param("fetchedAt") Instant fetchedAt);

  @Delete(
      "DELETE FROM core_citation_reference WHERE citing_achievement_id = #{citingAchievementId}")
  int deleteReferencesOf(@Param("citingAchievementId") String citingAchievementId);

  @Insert(
      """
      INSERT INTO core_citation_reference
        (id, citing_achievement_id, cited_kind, cited_value_norm, cited_achievement_id,
         resolution, observed_at, resolved_at)
      VALUES
        (#{id}, #{citingAchievementId}, #{citedKind}, #{citedValueNorm}, #{citedAchievementId},
         #{resolution}, #{observedAt}, #{resolvedAt})
      ON CONFLICT (citing_achievement_id, cited_kind, cited_value_norm) DO NOTHING
      """)
  int insertReference(
      @Param("id") String id,
      @Param("citingAchievementId") String citingAchievementId,
      @Param("citedKind") String citedKind,
      @Param("citedValueNorm") String citedValueNorm,
      @Param("citedAchievementId") String citedAchievementId,
      @Param("resolution") String resolution,
      @Param("observedAt") Instant observedAt,
      @Param("resolvedAt") Instant resolvedAt);

  /** 本篇引用的、本平台内可见的成果。 */
  @Select(
      """
      SELECT target.id AS achievementId, target.title AS title, target.type AS type,
             target.publication_year AS publicationYear, target.year_precision AS yearPrecision,
             paper.venue AS venue, paper.doi AS doi
      FROM core_citation_reference r
      JOIN core_achievement a ON a.id = r.citing_achievement_id
      JOIN core_achievement target ON target.id = r.cited_achievement_id
      LEFT JOIN core_paper paper ON paper.achievement_id = target.id
      WHERE r.citing_achievement_id = #{citingAchievementId}
        AND r.resolution = 'RESOLVED'
        AND target.id <> r.citing_achievement_id
        AND target.visibility = 'PUBLIC' AND target.effective_status = 'EFFECTIVE'
        AND target.merged_into_id IS NULL
      ORDER BY target.publication_year DESC NULLS LAST, target.title
      """)
  List<CitedWorkRow> listResolvedReferences(
      @Param("citingAchievementId") String citingAchievementId);

  /** 引用了本篇的、本平台内可见的成果。 */
  @Select(
      """
      SELECT target.id AS achievementId, target.title AS title, target.type AS type,
             target.publication_year AS publicationYear, target.year_precision AS yearPrecision,
             paper.venue AS venue, paper.doi AS doi
      FROM core_citation_reference r
      JOIN core_achievement target ON target.id = r.citing_achievement_id
      LEFT JOIN core_paper paper ON paper.achievement_id = target.id
      WHERE r.cited_achievement_id = #{citedAchievementId}
        AND r.resolution = 'RESOLVED'
        AND target.id <> r.cited_achievement_id
        AND target.visibility = 'PUBLIC' AND target.effective_status = 'EFFECTIVE'
        AND target.merged_into_id IS NULL
      ORDER BY target.publication_year DESC NULLS LAST, target.title
      """)
  List<CitedWorkRow> listCitedBy(@Param("citedAchievementId") String citedAchievementId);

  /**
   * 本篇引用条目按解析结果计数。
   *
   * <p>自引单列：一篇论文引用自己（勘误、续篇常这样写）不是网络里的边，混进「引用了 N 篇本平台
   * 成果」会让人以为有 N 个不同的对象。
   */
  @Select(
      """
      SELECT
        count(*) FILTER (WHERE resolution = 'RESOLVED' AND cited_achievement_id <> citing_achievement_id)
          AS resolved,
        count(*) FILTER (WHERE resolution = 'EXTERNAL') AS external,
        count(*) FILTER (WHERE resolution = 'RESOLVED' AND cited_achievement_id = citing_achievement_id)
          AS selfReferences
      FROM core_citation_reference WHERE citing_achievement_id = #{citingAchievementId}
      """)
  ResolutionCountsRow countReferencesOf(@Param("citingAchievementId") String citingAchievementId);

  record ResolutionCountsRow(int resolved, int external, int selfReferences) {}

  /**
   * 还剩多少个「注册表里已有、边却仍是 EXTERNAL」的键。
   *
   * <p>用来判断重新解析是否真的推进了：只看「本轮解析了多少行」无法区分「已经扫完」与
   * 「一直扫同一批」，两者都表现为调用成功。
   */
  @Select(
      """
      SELECT count(*) FROM (
        SELECT 1 FROM core_citation_reference r
        JOIN core_identifier_registry i
          ON i.identifier_kind = r.cited_kind
         AND i.normalized_value = r.cited_value_norm
         AND i.entity_type = 'ACHIEVEMENT'
         AND i.scope = ''
        WHERE r.resolution = 'EXTERNAL'
        GROUP BY r.cited_kind, r.cited_value_norm
      ) k
      """)
  int countUnresolvedKeys();

  /**
   * 一次性把「注册表里已有、边却仍是 EXTERNAL」的前 N 个标识键升级成 RESOLVED。
   *
   * <p>为什么不用「先取键、再逐键更新」的两段式：那样每个键要一次 SELECT 加一次 UPDATE，
   * 2000 个键就是 4000 次往返，实测超过调用方 20 秒的读超时，整步以 DEPENDENCY_UNAVAILABLE
   * 报错——而它其实什么都没错，只是慢。改成一条语句后，扫描、匹配、更新都在库内完成。
   *
   * <p>三个计数在同一个语句里返回，必须在同一条语句里：分开统计会受并发写入影响，
   * 出现「扫了 2000 个、解析了 2001 个」这种对不上的结果。
   *
   * <p>{@code LIMIT} 只框住「键」，不框「行」：一个被引 227 次的 DOI 会一次升级 227 行边。
   * 按行限流会让高被引的那批永远排在后面——它们恰恰最该先连起来。
   */
  @Select(
      """
      WITH target AS (
        SELECT r.cited_kind, r.cited_value_norm
        FROM core_citation_reference r
        JOIN core_identifier_registry i
          ON i.identifier_kind = r.cited_kind
         AND i.normalized_value = r.cited_value_norm
         AND i.entity_type = 'ACHIEVEMENT'
         AND i.scope = ''
        WHERE r.resolution = 'EXTERNAL'
        GROUP BY r.cited_kind, r.cited_value_norm
        ORDER BY r.cited_kind, r.cited_value_norm
        LIMIT #{limit}
      ), upd AS (
        UPDATE core_citation_reference r
        SET cited_achievement_id = i.entity_id,
            resolution = 'RESOLVED',
            resolved_at = #{resolvedAt}
        FROM core_identifier_registry i
        JOIN target t
          ON t.cited_kind = i.identifier_kind
         AND t.cited_value_norm = i.normalized_value
        WHERE i.entity_type = 'ACHIEVEMENT'
          AND i.scope = ''
          AND r.cited_kind = t.cited_kind
          AND r.cited_value_norm = t.cited_value_norm
          AND r.resolution = 'EXTERNAL'
        RETURNING r.cited_kind, r.cited_value_norm
      )
      SELECT (SELECT count(*) FROM target) AS keysScanned,
             (SELECT count(DISTINCT (cited_kind, cited_value_norm)) FROM upd) AS keysResolved,
             (SELECT count(*) FROM upd) AS rowsResolved
      """)
  ResolveBatchRow resolveBatch(@Param("limit") int limit, @Param("resolvedAt") Instant resolvedAt);

  /** 一次批量重新解析的计数。 */
  record ResolveBatchRow(int keysScanned, int keysResolved, int rowsResolved) {}

  @Update(
      """
      UPDATE core_citation_reference
      SET cited_achievement_id = #{citedAchievementId}, resolution = 'RESOLVED',
          resolved_at = #{resolvedAt}
      WHERE cited_kind = #{citedKind} AND cited_value_norm = #{citedValueNorm}
        AND resolution = 'EXTERNAL'
      """)
  int resolveByKey(
      @Param("citedKind") String citedKind,
      @Param("citedValueNorm") String citedValueNorm,
      @Param("citedAchievementId") String citedAchievementId,
      @Param("resolvedAt") Instant resolvedAt);

  /** 已采集参考文献的成果数（只算来源确实返回了的那一类）。 */
  @Select("SELECT count(*) FROM core_citation_observation WHERE outcome = 'OBSERVED'")
  int countObservedWorks();

  /** 来源按标识查不到的成果数：这是缺口而不是「零引用」，页面必须分开说。 */
  @Select("SELECT count(*) FROM core_citation_observation WHERE outcome = 'NOT_FOUND'")
  int countNotFoundWorks();

  /** 本平台可见论文总数：覆盖率的分母，必须与「被引」用的是同一个资格口径。 */
  @Select(
      """
      SELECT count(*) FROM core_achievement a
      WHERE a.type = 'PAPER' AND a.visibility = 'PUBLIC' AND a.effective_status = 'EFFECTIVE'
        AND a.merged_into_id IS NULL
      """)
  int countPublicPapers();

  /**
   * 还没有参考文献观测的可见论文，按来源报告的引用量从高到低。
   *
   * <p>用 LEFT JOIN … IS NULL 而不是 NOT IN：后者在子查询含 NULL 时会把整个条件判成未知，
   * 结果是「一篇都补不到」而查询本身不报错。
   *
   * <p>两个可选过滤（题名关键字、领域）用于「集中补齐一个子领域」，因为引用网络的边只有两端
   * 都在库内时才会出现：随机补 1 万篇得到的边远少于集中补同一领域的 2000 篇。
   */
  @Select(
      """
      <script>
      SELECT a.id AS achievementId, p.doi AS doi, p.citation_count AS citationCount
      FROM core_achievement a
      JOIN core_paper p ON p.achievement_id = a.id
      LEFT JOIN core_citation_observation o ON o.achievement_id = a.id
      <if test="topicId != null">
        JOIN core_achievement_topic t ON t.achievement_id = a.id AND t.topic_id = #{topicId}
      </if>
      WHERE a.visibility = 'PUBLIC' AND a.effective_status = 'EFFECTIVE'
        AND a.merged_into_id IS NULL AND a.type = 'PAPER'
        AND p.doi_norm IS NOT NULL AND o.achievement_id IS NULL
      <if test="search != null">
        AND a.title_norm LIKE CONCAT('%', #{search}, '%')
      </if>
      ORDER BY p.citation_count DESC NULLS LAST, a.id
      LIMIT #{limit}
      </script>
      """)
  List<PendingWorkRow> listPendingWorks(
      @Param("limit") int limit, @Param("search") String search, @Param("topicId") String topicId);

  /** 待补总数（同一过滤条件）。用于「还剩多少篇要补」，不能只靠本批条数推断。 */
  @Select(
      """
      <script>
      SELECT count(*)
      FROM core_achievement a
      JOIN core_paper p ON p.achievement_id = a.id
      LEFT JOIN core_citation_observation o ON o.achievement_id = a.id
      <if test="topicId != null">
        JOIN core_achievement_topic t ON t.achievement_id = a.id AND t.topic_id = #{topicId}
      </if>
      WHERE a.visibility = 'PUBLIC' AND a.effective_status = 'EFFECTIVE'
        AND a.merged_into_id IS NULL AND a.type = 'PAPER'
        AND p.doi_norm IS NOT NULL AND o.achievement_id IS NULL
      <if test="search != null">
        AND a.title_norm LIKE CONCAT('%', #{search}, '%')
      </if>
      </script>
      """)
  int countPendingWorks(@Param("search") String search, @Param("topicId") String topicId);

  /**
   * 高频「本站还没有」的外部被引标识，按被引次数降序。
   *
   * <p>这是引文反向回填的选样依据：正向补水只能让「站内论文引用了谁」这件事被记录下来，边却因为
   * 另一端不在库内而全部落在 EXTERNAL。要让边真正连起来，只能反过来把被引最多的外部文献收进来。
   *
   * <p>「本站还没有」不能只看 {@code core_paper.doi_norm}：外部标识里大半是 OpenAlex 作品号
   * （实测 66 万条 OPENALEX_ID 对 12 万条 DOI），它不存在于 core_paper 的任何列。统一查
   * {@code core_identifier_registry} 才能把两种标识都判对，否则会把已经收过的作品再抓一遍。
   *
   * <p>用 NOT EXISTS 而不是 NOT IN：子查询含 NULL 时 NOT IN 会把整个条件判成未知，结果一条都取不到，
   * 而查询本身不报错。
   */
  @Select(
      """
      SELECT r.cited_kind AS citedKind, r.cited_value_norm AS citedValueNorm, count(*) AS refs
      FROM core_citation_reference r
      WHERE r.resolution = 'EXTERNAL' AND r.cited_kind = #{kind}
        AND r.cited_value_norm IS NOT NULL
        AND NOT EXISTS (
          SELECT 1 FROM core_identifier_registry i
          WHERE i.identifier_kind = r.cited_kind AND i.normalized_value = r.cited_value_norm
        )
      GROUP BY r.cited_kind, r.cited_value_norm
      ORDER BY count(*) DESC, r.cited_value_norm
      LIMIT #{limit}
      """)
  List<BackfillCandidateRow> listBackfillCandidates(
      @Param("kind") String kind, @Param("limit") int limit);

  /** 同一口径下还剩多少个可回填标识：用来回答「还要跑几轮」，不能靠本批条数推断。 */
  @Select(
      """
      SELECT count(DISTINCT r.cited_value_norm)
      FROM core_citation_reference r
      WHERE r.resolution = 'EXTERNAL' AND r.cited_kind = #{kind}
        AND r.cited_value_norm IS NOT NULL
        AND NOT EXISTS (
          SELECT 1 FROM core_identifier_registry i
          WHERE i.identifier_kind = r.cited_kind AND i.normalized_value = r.cited_value_norm
        )
      """)
  int countBackfillCandidates(@Param("kind") String kind);

  record BackfillCandidateRow(String citedKind, String citedValueNorm, Integer refs) {}
}
