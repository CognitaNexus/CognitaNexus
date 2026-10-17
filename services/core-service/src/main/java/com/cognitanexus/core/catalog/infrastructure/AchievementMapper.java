package com.cognitanexus.core.catalog.infrastructure;

import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.AchievementDetails;
import com.cognitanexus.core.catalog.domain.EffectiveStatus;
import com.cognitanexus.core.catalog.domain.PublicationYear;
import com.cognitanexus.core.catalog.domain.YearPrecision;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.Visibility;
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
 * 成果域持久化：成果主表、四类类型扩展、来源关联、稳定标识注册、字段溯源。
 *
 * <p>依据：架构 V0.2 §5.1、§5.3。
 *
 * <p>列的选择有几处刻意的处理：
 *
 * <ul>
 *   <li>publication_year 是 smallint，显式转 int 后再映射，避免由驱动返回 Short 造成装配失败；
 *   <li>所有列显式别名成行对象的构造参数名，不依赖下划线转驼峰的隐式行为；
 *   <li>text[] 列通过自定义类型处理器读取，不用 getString 读数组字面量。
 * </ul>
 */
@Mapper
public interface AchievementMapper {

  /**
   * 成果行的查询投影。
   *
   * <p>这里刻意不含 similarity：这个行类型被 findById、待审列表、公开导出等多个查询共用，
   * 而它们的结果集里没有相似度列。构造器自动映射把「列缺失」当作硬失败，不是可选字段，
   * 给共用行类型加一个只有个别查询才有的列，会让其余查询整体报错。
   * 相似度只出现在 {@link SimilarTitleRow} 里。
   */
  record AchievementRow(
      String id,
      String type,
      String title,
      String titleNorm,
      String dataNature,
      String visibility,
      String effectiveStatus,
      Integer publicationYear,
      String yearPrecision,
      String mergedIntoId,
      long version,
      Instant createdAt,
      Instant updatedAt) {}

  @Insert(
      """
      INSERT INTO core_achievement
        (id, type, title, title_norm, data_nature, visibility, effective_status,
         publication_year, year_precision, version)
      VALUES
        (#{id}, #{type}, #{title}, #{titleNorm}, #{dataNature}, #{visibility}, #{effectiveStatus},
         #{publicationYear}, #{yearPrecision}, 1)
      """)
  void insert(
      @Param("id") String id,
      @Param("type") String type,
      @Param("title") String title,
      @Param("titleNorm") String titleNorm,
      @Param("dataNature") String dataNature,
      @Param("visibility") String visibility,
      @Param("effectiveStatus") String effectiveStatus,
      @Param("publicationYear") Integer publicationYear,
      @Param("yearPrecision") String yearPrecision);

  @Select(
      """
      SELECT id             AS id,
             type           AS type,
             title          AS title,
             title_norm     AS titleNorm,
             data_nature    AS dataNature,
             visibility     AS visibility,
             effective_status AS effectiveStatus,
             publication_year::int AS publicationYear,
             year_precision AS yearPrecision,
             merged_into_id AS mergedIntoId,
             version        AS version,
             created_at     AS createdAt,
             updated_at     AS updatedAt
      FROM core_achievement
      WHERE id = #{id}
      """)
  Optional<AchievementRow> findById(@Param("id") String id);

  @Select(
      """
      SELECT merged_into_id FROM core_achievement WHERE id = #{id}
      """)
  Optional<String> findMergedIntoId(@Param("id") String id);

  /**
   * 条件更新生效状态与可见性。
   *
   * <p>带 expected_version 条件：受影响行数为 0 说明并发已被改变，调用方必须报冲突而不是覆盖。
   */
  @Update(
      """
      UPDATE core_achievement
      SET effective_status = #{effectiveStatus},
          visibility = #{visibility},
          version = version + 1,
          updated_at = now()
      WHERE id = #{id} AND version = #{expectedVersion}
      """)
  int updatePublicationState(
      @Param("id") String id,
      @Param("effectiveStatus") String effectiveStatus,
      @Param("visibility") String visibility,
      @Param("expectedVersion") long expectedVersion);

  /** 隐私收紧：只降低可见性，不做其他改动。用于来源展示许可撤销等场景。 */
  @Update(
      """
      UPDATE core_achievement
      SET visibility = #{visibility}, version = version + 1, updated_at = now()
      WHERE id = #{id} AND visibility <> #{visibility}
      """)
  int tightenVisibility(@Param("id") String id, @Param("visibility") String visibility);

  // ---------------------------------------------------------------------------
  // 类型扩展
  // ---------------------------------------------------------------------------

  @Insert(
      """
      INSERT INTO core_paper
        (achievement_id, doi, doi_norm, venue, published_date, preprint_status,
         citation_count, citation_source, abstract_text)
      VALUES
        (#{achievementId}, #{doi}, #{doiNorm}, #{venue}, #{publishedDate}, #{preprintStatus},
         #{citationCount}, #{citationSource}, #{abstractText})
      """)
  void insertPaper(
      @Param("achievementId") String achievementId,
      @Param("doi") String doi,
      @Param("doiNorm") String doiNorm,
      @Param("venue") String venue,
      @Param("publishedDate") LocalDate publishedDate,
      @Param("preprintStatus") String preprintStatus,
      @Param("citationCount") Integer citationCount,
      @Param("citationSource") String citationSource,
      @Param("abstractText") String abstractText);

  record PaperRow(
      String achievementId,
      String doi,
      String doiNorm,
      String venue,
      LocalDate publishedDate,
      String preprintStatus,
      Integer citationCount,
      String citationSource,
      String abstractText) {}

  /**
   * 刷新既有论文的单一来源指标。
   *
   * <p>人工修订保护写在 WHERE 里，而不是由调用方先查 provenance 再决定要不要更新：先读后判会在
   * 读与写之间留出一个窗口，人工在这个窗口内提交的修订值会被随后执行的来源更新覆盖——而那正是
   * 「自动更新不得覆盖人工审核值」这条红线要禁止的事。放进同一条 SQL 后，判断与写入同在一个
   * 语句的快照里，不存在窗口。
   *
   * <p>用 COALESCE 而不是直接赋值：来源这次没给引用量，不应把已有的观测值清空。「来源这次没说」
   * 与「来源说没有引用」是两件事，前者保留旧值，后者由值 0 表达（0 能通过非空判断落到 COALESCE
   * 的结果上）。
   *
   * <p>非论文类型不会有 core_paper 行，更新自然不命中；这不是异常，调用方据返回 0 跳过即可。
   */
  @Update(
      """
      UPDATE core_paper p
      SET citation_count  = COALESCE(#{citationCount}, p.citation_count),
          citation_source = COALESCE(#{citationSource}, p.citation_source)
      WHERE p.achievement_id = #{achievementId}
        AND NOT EXISTS (
              SELECT 1
              FROM core_field_provenance fp
              WHERE fp.entity_type = 'ACHIEVEMENT'
                AND fp.entity_id = p.achievement_id
                AND fp.field_name IN ('citationCount', 'citationSource')
                AND fp.value_level IN ('MANUAL', 'REVIEWED')
            )
      """)
  int refreshPaperMetrics(
      @Param("achievementId") String achievementId,
      @Param("citationCount") Integer citationCount,
      @Param("citationSource") String citationSource);

  @Select(
      """
      SELECT achievement_id AS achievementId, doi AS doi, doi_norm AS doiNorm, venue AS venue,
             published_date AS publishedDate, preprint_status AS preprintStatus,
             citation_count AS citationCount, citation_source AS citationSource,
             abstract_text AS abstractText
      FROM core_paper WHERE achievement_id = #{achievementId}
      """)
  Optional<PaperRow> findPaper(@Param("achievementId") String achievementId);

  @Insert(
      """
      INSERT INTO core_patent
        (achievement_id, application_no, application_no_norm, publication_no,
         ipc_codes, applicant_names, filing_date, publication_date)
      VALUES
        (#{achievementId}, #{applicationNo}, #{applicationNoNorm}, #{publicationNo},
         #{ipcCodes,typeHandler=com.cognitanexus.core.platform.persistence.StringArrayTypeHandler},
         #{applicantNames,typeHandler=com.cognitanexus.core.platform.persistence.StringArrayTypeHandler},
         #{filingDate}, #{publicationDate})
      """)
  void insertPatent(
      @Param("achievementId") String achievementId,
      @Param("applicationNo") String applicationNo,
      @Param("applicationNoNorm") String applicationNoNorm,
      @Param("publicationNo") String publicationNo,
      @Param("ipcCodes") String[] ipcCodes,
      @Param("applicantNames") String[] applicantNames,
      @Param("filingDate") LocalDate filingDate,
      @Param("publicationDate") LocalDate publicationDate);

  /**
   * 专利行。
   *
   * <p>数组列一律用 String[]：StringArrayTypeHandler 的形参类型就是 String[]，把行字段写成
   * List 会让 MyBatis 找不到处理器、退回 Object 处理并把 java.sql.Array 直接塞进构造参数。
   * 与 AchievementDetails.Patent 之间的 List/String[] 转换在 {@link #toPatent} 里做。
   */
  record PatentRow(
      String achievementId,
      String applicationNo,
      String applicationNoNorm,
      String publicationNo,
      String[] ipcCodes,
      String[] applicantNames,
      LocalDate filingDate,
      LocalDate publicationDate) {}

  @Select(
      """
      SELECT achievement_id AS achievementId, application_no AS applicationNo,
             application_no_norm AS applicationNoNorm, publication_no AS publicationNo,
             ipc_codes AS ipcCodes, applicant_names AS applicantNames,
             filing_date AS filingDate, publication_date AS publicationDate
      FROM core_patent WHERE achievement_id = #{achievementId}
      """)
  Optional<PatentRow> findPatent(@Param("achievementId") String achievementId);

  @Insert(
      """
      INSERT INTO core_research_project
        (achievement_id, project_no, project_no_norm, leader_name, funding_agency,
         project_category, start_date, end_date)
      VALUES
        (#{achievementId}, #{projectNo}, #{projectNoNorm}, #{leaderName}, #{fundingAgency},
         #{projectCategory}, #{startDate}, #{endDate})
      """)
  void insertProject(
      @Param("achievementId") String achievementId,
      @Param("projectNo") String projectNo,
      @Param("projectNoNorm") String projectNoNorm,
      @Param("leaderName") String leaderName,
      @Param("fundingAgency") String fundingAgency,
      @Param("projectCategory") String projectCategory,
      @Param("startDate") LocalDate startDate,
      @Param("endDate") LocalDate endDate);

  record ProjectRow(
      String achievementId,
      String projectNo,
      String projectNoNorm,
      String leaderName,
      String fundingAgency,
      String projectCategory,
      LocalDate startDate,
      LocalDate endDate) {}

  @Select(
      """
      SELECT achievement_id AS achievementId, project_no AS projectNo,
             project_no_norm AS projectNoNorm, leader_name AS leaderName,
             funding_agency AS fundingAgency, project_category AS projectCategory,
             start_date AS startDate, end_date AS endDate
      FROM core_research_project WHERE achievement_id = #{achievementId}
      """)
  Optional<ProjectRow> findProject(@Param("achievementId") String achievementId);

  @Insert(
      """
      INSERT INTO core_award
        (achievement_id, award_name, award_year, award_level, granting_body)
      VALUES
        (#{achievementId}, #{awardName}, #{awardYear}, #{awardLevel}, #{grantingBody})
      """)
  void insertAward(
      @Param("achievementId") String achievementId,
      @Param("awardName") String awardName,
      @Param("awardYear") Integer awardYear,
      @Param("awardLevel") String awardLevel,
      @Param("grantingBody") String grantingBody);

  record AwardRow(
      String achievementId,
      String awardName,
      Integer awardYear,
      String awardLevel,
      String grantingBody) {}

  @Select(
      """
      SELECT achievement_id AS achievementId, award_name AS awardName,
             award_year::int AS awardYear, award_level AS awardLevel,
             granting_body AS grantingBody
      FROM core_award WHERE achievement_id = #{achievementId}
      """)
  Optional<AwardRow> findAward(@Param("achievementId") String achievementId);

  @Select("SELECT count(*) FROM core_paper WHERE achievement_id = #{achievementId}")
  int countPaper(@Param("achievementId") String achievementId);

  @Select("SELECT count(*) FROM core_patent WHERE achievement_id = #{achievementId}")
  int countPatent(@Param("achievementId") String achievementId);

  @Select("SELECT count(*) FROM core_research_project WHERE achievement_id = #{achievementId}")
  int countProject(@Param("achievementId") String achievementId);

  @Select("SELECT count(*) FROM core_award WHERE achievement_id = #{achievementId}")
  int countAward(@Param("achievementId") String achievementId);

  /**
   * 统计某成果在指定类型下的扩展行数。
   *
   * <p>刻意写成四个独立计数而不是一条 CASE 语句：把类型作为绑定参数放进 CASE 表达式时，
   * PostgreSQL 无法推断该参数类型会直接报错；而用字符串拼接类型名会把 SQL 结构交给调用方。
   */
  default int countExtensions(String achievementId, AchievementType type) {
    return switch (type) {
      case PAPER -> countPaper(achievementId);
      case PATENT -> countPatent(achievementId);
      case PROJECT -> countProject(achievementId);
      case AWARD -> countAward(achievementId);
    };
  }

  // ---------------------------------------------------------------------------
  // 来源关联
  // ---------------------------------------------------------------------------

  @Insert(
      """
      INSERT INTO core_achievement_source
        (id, achievement_id, source_record_id, source_id, source_updated_at, received_at)
      VALUES
        (#{id}, #{achievementId}, #{sourceRecordId}, #{sourceId}, #{sourceUpdatedAt}, #{receivedAt})
      ON CONFLICT (achievement_id, source_record_id) DO NOTHING
      """)
  int linkSource(
      @Param("id") String id,
      @Param("achievementId") String achievementId,
      @Param("sourceRecordId") String sourceRecordId,
      @Param("sourceId") String sourceId,
      @Param("sourceUpdatedAt") Instant sourceUpdatedAt,
      @Param("receivedAt") Instant receivedAt);

  @Select(
      """
      SELECT count(DISTINCT source_id) FROM core_achievement_source WHERE achievement_id = #{achievementId}
      """)
  int countDistinctSources(@Param("achievementId") String achievementId);

  // ---------------------------------------------------------------------------
  // 稳定标识注册
  // ---------------------------------------------------------------------------

  @Select(
      """
      SELECT entity_id FROM core_identifier_registry
      WHERE entity_type = #{entityType} AND identifier_kind = #{kind}
        AND scope = #{scope} AND normalized_value = #{normalizedValue}
      """)
  Optional<String> findRegisteredEntity(
      @Param("entityType") String entityType,
      @Param("kind") String kind,
      @Param("scope") String scope,
      @Param("normalizedValue") String normalizedValue);

  /**
   * 注册稳定标识。
   *
   * <p>冲突时返回 0 行影响而不是抛错：未经证实的冲突标识必须转为治理候选，
   * 不能让事务因唯一约束失败而中止（架构 §5.3）。
   */
  @Insert(
      """
      INSERT INTO core_identifier_registry
        (id, entity_type, entity_id, identifier_kind, scope, normalized_value)
      VALUES
        (#{id}, #{entityType}, #{entityId}, #{kind}, #{scope}, #{normalizedValue})
      ON CONFLICT (identifier_kind, scope, normalized_value) DO NOTHING
      """)
  int registerIdentifier(
      @Param("id") String id,
      @Param("entityType") String entityType,
      @Param("entityId") String entityId,
      @Param("kind") String kind,
      @Param("scope") String scope,
      @Param("normalizedValue") String normalizedValue);

  @Select(
      """
      SELECT entity_id FROM core_identifier_registry
      WHERE identifier_kind = #{kind} AND normalized_value = #{normalizedValue}
      LIMIT 1
      """)
  Optional<String> findAnyEntityByNormalizedValue(
      @Param("kind") String kind, @Param("normalizedValue") String normalizedValue);

  /**
   * 该规范实体是否存在任何已登记的稳定标识。
   *
   * <p>判据取自注册表，而不是回头去扩展表读 DOI / 申请号字段：采集时「来源是否提供了稳定标识」
   * 落成的就是这里的这一行，两处若各写一份判定，早晚会出现「采集说没有、重评估说有」的分歧。
   */
  @Select(
      """
      SELECT EXISTS(
        SELECT 1 FROM core_identifier_registry
        WHERE entity_type = #{entityType} AND entity_id = #{entityId}
      )
      """)
  boolean hasRegisteredIdentifier(
      @Param("entityType") String entityType, @Param("entityId") String entityId);

  // ---------------------------------------------------------------------------
  // 字段溯源
  // ---------------------------------------------------------------------------

  @Insert(
      """
      INSERT INTO core_field_provenance
        (id, entity_type, entity_id, field_name, value_level, value_text, source_record_id, updated_at)
      VALUES
        (#{id}, #{entityType}, #{entityId}, #{fieldName}, 'SOURCE', #{valueText}, #{sourceRecordId}, now())
      ON CONFLICT (entity_type, entity_id, field_name) DO UPDATE
        SET value_text = EXCLUDED.value_text,
            source_record_id = EXCLUDED.source_record_id,
            value_level = 'SOURCE',
            updated_at = now()
        WHERE core_field_provenance.value_level = 'SOURCE'
      """)
  int upsertSourceProvenance(
      @Param("id") String id,
      @Param("entityType") String entityType,
      @Param("entityId") String entityId,
      @Param("fieldName") String fieldName,
      @Param("valueText") String valueText,
      @Param("sourceRecordId") String sourceRecordId);

  record ProvenanceRow(
      String id,
      String entityType,
      String entityId,
      String fieldName,
      String valueLevel,
      String valueText,
      String sourceRecordId,
      Instant updatedAt) {}

  @Select(
      """
      SELECT id AS id, entity_type AS entityType, entity_id AS entityId, field_name AS fieldName,
             value_level AS valueLevel, value_text AS valueText,
             source_record_id AS sourceRecordId, updated_at AS updatedAt
      FROM core_field_provenance
      WHERE entity_type = #{entityType} AND entity_id = #{entityId} AND field_name = #{fieldName}
      """)
  Optional<ProvenanceRow> findProvenance(
      @Param("entityType") String entityType,
      @Param("entityId") String entityId,
      @Param("fieldName") String fieldName);

  @Select(
      """
      SELECT id AS id, entity_type AS entityType, entity_id AS entityId, field_name AS fieldName,
             value_level AS valueLevel, value_text AS valueText,
             source_record_id AS sourceRecordId, updated_at AS updatedAt
      FROM core_field_provenance
      WHERE entity_type = #{entityType} AND entity_id = #{entityId}
      ORDER BY field_name
      """)
  List<ProvenanceRow> listProvenance(
      @Param("entityType") String entityType, @Param("entityId") String entityId);

  /** 人工/已审核值写回：只允许从 SOURCE 升级，不允许来源自动改写它。 */
  @Insert(
      """
      INSERT INTO core_field_provenance
        (id, entity_type, entity_id, field_name, value_level, value_text, updated_at)
      VALUES
        (#{id}, #{entityType}, #{entityId}, #{fieldName}, #{valueLevel}, #{valueText}, now())
      ON CONFLICT (entity_type, entity_id, field_name) DO UPDATE
        SET value_level = EXCLUDED.value_level,
            value_text = EXCLUDED.value_text,
            source_record_id = NULL,
            updated_at = now()
      """)
  int upsertReviewedProvenance(
      @Param("id") String id,
      @Param("entityType") String entityType,
      @Param("entityId") String entityId,
      @Param("fieldName") String fieldName,
      @Param("valueLevel") String valueLevel,
      @Param("valueText") String valueText);

  // ---------------------------------------------------------------------------
  // 候选检索
  // ---------------------------------------------------------------------------

  @Select(
      """
      SELECT id             AS id,
             type           AS type,
             title          AS title,
             title_norm     AS titleNorm,
             data_nature    AS dataNature,
             visibility     AS visibility,
             effective_status AS effectiveStatus,
             publication_year::int AS publicationYear,
             year_precision AS yearPrecision,
             merged_into_id AS mergedIntoId,
             version        AS version,
             created_at     AS createdAt,
             updated_at     AS updatedAt
      FROM core_achievement
      WHERE title_norm = #{titleNorm} AND merged_into_id IS NULL
      ORDER BY id
      LIMIT #{limit}
      """)
  List<AchievementRow> findByExactTitle(
      @Param("titleNorm") String titleNorm, @Param("limit") int limit);

  @Select(
      """
      SELECT id             AS id,
             type           AS type,
             title          AS title,
             title_norm     AS titleNorm,
             data_nature    AS dataNature,
             visibility     AS visibility,
             effective_status AS effectiveStatus,
             publication_year::int AS publicationYear,
             year_precision AS yearPrecision,
             merged_into_id AS mergedIntoId,
             version        AS version,
             created_at     AS createdAt,
             updated_at     AS updatedAt
      FROM core_achievement
      WHERE effective_status = #{effectiveStatus} AND merged_into_id IS NULL
      ORDER BY created_at, id
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<AchievementRow> listByStatus(
      @Param("effectiveStatus") String effectiveStatus,
      @Param("limit") int limit,
      @Param("offset") int offset);

  @Select(
      "SELECT count(*) FROM core_achievement WHERE effective_status = #{effectiveStatus} AND merged_into_id IS NULL")
  long countByStatus(@Param("effectiveStatus") String effectiveStatus);

  @Select(
      """
      SELECT id             AS id,
             type           AS type,
             title          AS title,
             title_norm     AS titleNorm,
             data_nature    AS dataNature,
             visibility     AS visibility,
             effective_status AS effectiveStatus,
             publication_year::int AS publicationYear,
             year_precision AS yearPrecision,
             merged_into_id AS mergedIntoId,
             version        AS version,
             created_at     AS createdAt,
             updated_at     AS updatedAt
      FROM core_achievement
      WHERE merged_into_id IS NULL AND visibility = 'PUBLIC' AND effective_status = 'EFFECTIVE'
      ORDER BY id
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<AchievementRow> listPublicForExport(@Param("limit") int limit, @Param("offset") int offset);

  @Select(
      """
      SELECT count(*) FROM core_achievement
      WHERE merged_into_id IS NULL AND visibility = 'PUBLIC' AND effective_status = 'EFFECTIVE'
      """)
  long countPublicForExport();

  /**
   * 三元组相似度候选。
   *
   * <p>结果只作为治理候选，绝不据此自动合并；判定还须比对年份、参与者与类型（AC-IMPL-FR09-02）。
   *
   * <p>必须把 {@code similarity} 一并查出来：它是治理人员判断「这到底是不是同一篇」的首要依据。
   * 早先这条查询只在 WHERE 里用阈值过滤、SELECT 却没带出相似度，调用方只能把阈值本身写进候选
   * 证据，于是几千条候选的证据里写着同一个数字——高置信重复与勉强过线的候选无法区分，待办列表
   * 也就无法按可信度排序处理。
   */
  /**
   * 相似标题查询的行：成果字段 + 这一次比较算出的相似度。
   *
   * <p>单独一个行类型，而不是给 {@link AchievementRow} 加一列（见那里的说明）。相似度是本次
   * 查询附带的计算值、不是成果自身的属性，用包装类型而非 double，让「没算过」与「算出来是 0」
   * 在类型上就分得开。
   */
  record SimilarTitleRow(
      String id,
      String type,
      String title,
      String titleNorm,
      String dataNature,
      String visibility,
      String effectiveStatus,
      Integer publicationYear,
      String yearPrecision,
      String mergedIntoId,
      long version,
      Instant createdAt,
      Instant updatedAt,
      Double similarity) {}

  default Achievement toDomain(SimilarTitleRow row) {
    return toDomain(
        new AchievementRow(
            row.id(),
            row.type(),
            row.title(),
            row.titleNorm(),
            row.dataNature(),
            row.visibility(),
            row.effectiveStatus(),
            row.publicationYear(),
            row.yearPrecision(),
            row.mergedIntoId(),
            row.version(),
            row.createdAt(),
            row.updatedAt()));
  }

  @Select(
      """
      SELECT id             AS id,
             type           AS type,
             title          AS title,
             title_norm     AS titleNorm,
             data_nature    AS dataNature,
             visibility     AS visibility,
             effective_status AS effectiveStatus,
             publication_year::int AS publicationYear,
             year_precision AS yearPrecision,
             merged_into_id AS mergedIntoId,
             version        AS version,
             created_at     AS createdAt,
             updated_at     AS updatedAt,
             similarity(title_norm, #{titleNorm}) AS similarity
      FROM core_achievement
      WHERE merged_into_id IS NULL
        AND title_norm % #{titleNorm}
        AND similarity(title_norm, #{titleNorm}) >= #{threshold}
        AND title_norm <> #{titleNorm}
      ORDER BY similarity(title_norm, #{titleNorm}) DESC, id
      LIMIT #{limit}
      """)
  List<SimilarTitleRow> findSimilarByTitle(
      @Param("titleNorm") String titleNorm,
      @Param("threshold") double threshold,
      @Param("limit") int limit);

  @Select(
      """
      SELECT count(*) FROM core_achievement
      WHERE data_nature = #{dataNature} AND effective_status = #{effectiveStatus}
        AND merged_into_id IS NULL
      """)
  long countByNatureAndStatus(
      @Param("dataNature") String dataNature, @Param("effectiveStatus") String effectiveStatus);

  @Select(
      """
      SELECT count(*) FROM core_achievement WHERE merged_into_id IS NULL
      """)
  long countAll();

  /**
   * 找出「唯一来源是该来源」且当前公开的成果。
   *
   * <p>用于撤销来源展示许可：只有完全依赖该来源的成果才必须随之收紧可见性；
   * 同时有其它来源证据的成果不应因为一个来源被停用而整体下线。
   */
  @Select(
      """
      SELECT a.id
      FROM core_achievement a
      JOIN core_achievement_source s ON s.achievement_id = a.id
      WHERE s.source_id = #{sourceId}
        AND a.visibility = 'PUBLIC'
        AND a.merged_into_id IS NULL
        AND NOT EXISTS (
          SELECT 1 FROM core_achievement_source s2
          WHERE s2.achievement_id = a.id AND s2.source_id <> #{sourceId}
        )
      ORDER BY a.id
      LIMIT #{limit}
      """)
  List<String> findExclusivelyFromSource(
      @Param("sourceId") String sourceId, @Param("limit") int limit);

  default Achievement toDomain(AchievementRow row) {
    return new Achievement(
        row.id(),
        AchievementType.valueOf(row.type()),
        row.title(),
        row.titleNorm(),
        DataNature.valueOf(row.dataNature()),
        Visibility.valueOf(row.visibility()),
        EffectiveStatus.of(row.effectiveStatus()),
        new PublicationYear(row.publicationYear(), YearPrecision.of(row.yearPrecision())),
        row.mergedIntoId(),
        row.version(),
        row.createdAt(),
        row.updatedAt());
  }

  default AchievementDetails.Paper toPaper(PaperRow row) {
    return new AchievementDetails.Paper(
        row.achievementId(),
        row.doi(),
        row.doiNorm(),
        row.venue(),
        row.publishedDate(),
        row.preprintStatus(),
        row.citationCount(),
        row.citationSource(),
        row.abstractText());
  }

  default AchievementDetails.Patent toPatent(PatentRow row) {
    return new AchievementDetails.Patent(
        row.achievementId(),
        row.applicationNo(),
        row.applicationNoNorm(),
        row.publicationNo(),
        row.ipcCodes() == null ? List.of() : List.of(row.ipcCodes()),
        row.applicantNames() == null ? List.of() : List.of(row.applicantNames()),
        row.filingDate(),
        row.publicationDate());
  }

  default AchievementDetails.ResearchProject toProject(ProjectRow row) {
    return new AchievementDetails.ResearchProject(
        row.achievementId(),
        row.projectNo(),
        row.projectNoNorm(),
        row.leaderName(),
        row.fundingAgency(),
        row.projectCategory(),
        row.startDate(),
        row.endDate());
  }

  default AchievementDetails.Award toAward(AwardRow row) {
    return new AchievementDetails.Award(
        row.achievementId(),
        row.awardName(),
        row.awardYear(),
        row.awardLevel(),
        row.grantingBody());
  }
}
