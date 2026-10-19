package com.cognitanexus.core.catalog.infrastructure;

import com.cognitanexus.core.catalog.application.CatalogRepositories.TopicRepository.Topic;
import com.cognitanexus.core.catalog.application.CatalogRepositories.TopicRepository.TopicAssignment;
import com.cognitanexus.core.catalog.domain.Contribution;
import com.cognitanexus.sharedkernel.RelationState;
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
 * 关系域持久化：参与关系、发生时机归属、成果分类、领域规范词、机构。
 *
 * <p>依据：架构 V0.2 §5.1、§5.3。一个学者对同一成果只有一条参与关系，唯一约束
 * <code>uk_core_contribution_scholar_achievement</code> 保证；这里用 ON CONFLICT DO NOTHING
 * 让重复关联表现为 0 行影响，避免事务中止。
 */
@Mapper
public interface RelationshipMapper {

  record ContributionRow(
      String id,
      String scholarId,
      String achievementId,
      String role,
      Integer authorOrder,
      String relationState,
      String sourceRecordId,
      long version,
      Instant createdAt,
      Instant updatedAt) {}

  @Insert(
      """
      INSERT INTO core_contribution
        (id, scholar_id, achievement_id, role, author_order, relation_state, source_record_id, version)
      VALUES
        (#{id}, #{scholarId}, #{achievementId}, #{role}, #{authorOrder}, #{relationState},
         #{sourceRecordId}, 1)
      ON CONFLICT (scholar_id, achievement_id) DO NOTHING
      """)
  int insertContributionIfAbsent(
      @Param("id") String id,
      @Param("scholarId") String scholarId,
      @Param("achievementId") String achievementId,
      @Param("role") String role,
      @Param("authorOrder") Integer authorOrder,
      @Param("relationState") String relationState,
      @Param("sourceRecordId") String sourceRecordId);

  @Select(
      """
      SELECT id AS id, scholar_id AS scholarId, achievement_id AS achievementId, role AS role,
             author_order AS authorOrder, relation_state AS relationState,
             source_record_id AS sourceRecordId, version AS version,
             created_at AS createdAt, updated_at AS updatedAt
      FROM core_contribution
      WHERE achievement_id = #{achievementId}
      ORDER BY author_order NULLS LAST, id
      """)
  List<ContributionRow> listByAchievement(@Param("achievementId") String achievementId);

  @Select(
      """
      SELECT id AS id, scholar_id AS scholarId, achievement_id AS achievementId, role AS role,
             author_order AS authorOrder, relation_state AS relationState,
             source_record_id AS sourceRecordId, version AS version,
             created_at AS createdAt, updated_at AS updatedAt
      FROM core_contribution
      WHERE scholar_id = #{scholarId}
      ORDER BY created_at DESC, id
      """)
  List<ContributionRow> listByScholar(@Param("scholarId") String scholarId);

  /**
   * 学者的年份分布（学者档案页的指标条）。
   *
   * <p>三条口径都写进 SQL 而不是事后过滤，因为它们决定这个数字能不能对外说：
   *
   * <ul>
   *   <li>参与关系必须是 {@code AUTO} 或 {@code SELF_CONFIRMED}——待核验与争议关系不得计入，
   *       否则“同名不同人”的成果会被算到这位学者头上，正是业务红线禁止的那种混淆；
   *   <li>成果必须对公众可见、且未被合并——下架与被并走的成果不该继续出现在个人指标里；
   *   <li>年份缺失的记录不在本查询里出现，另由 {@link #countUndatedWorksOf} 单独计数，
   *       界面上单列而不是折算到某一年。
   * </ul>
   */
  @Select(
      """
      SELECT a.publication_year AS year, count(*) AS amount
      FROM core_contribution c
      JOIN core_achievement a ON a.id = c.achievement_id
      WHERE c.scholar_id = #{scholarId}
        AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
        AND a.visibility = 'PUBLIC'
        AND a.effective_status = 'EFFECTIVE'
        AND a.merged_into_id IS NULL
        AND a.publication_year IS NOT NULL
      GROUP BY a.publication_year
      ORDER BY a.publication_year
      """)
  List<YearAmountRow> yearDistributionOf(@Param("scholarId") String scholarId);

  /** 关系合格但年份缺失的成果数。它与上一条查询的口径完全相同，只是年份为 NULL。 */
  @Select(
      """
      SELECT count(*)
      FROM core_contribution c
      JOIN core_achievement a ON a.id = c.achievement_id
      WHERE c.scholar_id = #{scholarId}
        AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
        AND a.visibility = 'PUBLIC'
        AND a.effective_status = 'EFFECTIVE'
        AND a.merged_into_id IS NULL
        AND a.publication_year IS NULL
      """)
  long countUndatedWorksOf(@Param("scholarId") String scholarId);

  /**
   * 该学者名下已被声明撤稿的成果数。
   *
   * <p><b>只单独计数，不从 {@code worksTotal} 里扣除。</b>撤稿成果仍然是这位学者的产出记录，
   * 把它悄悄减掉会让「成果数」在撤稿那一刻凭空变小，读者无从知道发生了什么；单列一个数字，读者
   * 才能看到「其中 N 条已撤稿」这一事实。口径与年份分布完全一致（关系合格、公开、生效、未合并）。
   */
  @Select(
      """
      SELECT count(*)
      FROM core_contribution c
      JOIN core_achievement a ON a.id = c.achievement_id
      WHERE c.scholar_id = #{scholarId}
        AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
        AND a.visibility = 'PUBLIC'
        AND a.effective_status = 'EFFECTIVE'
        AND a.merged_into_id IS NULL
        AND EXISTS (
          SELECT 1 FROM core_achievement_integrity_notice n
          WHERE n.achievement_id = a.id
            AND n.notice_type = 'RETRACTION'
            AND n.status = 'ACTIVE'
        )
      """)
  long countRetractedWorksOf(@Param("scholarId") String scholarId);

  /**
   * 主要合作者：与该学者共同署名过合格成果的人，按共同成果数降序。
   *
   * <p>两端的参与关系都必须合格：只要有一端是待核验或争议，这次“合作”就没有被确认，
   * 把它算进合作者排行等于用一条排行榜替来源背书。
   *
   * <p>已合并的学者不出现（{@code merged_into_id IS NULL}）：否则同一个人的两个历史标识会各自
   * 占一行，把一个人的合作次数拆成两半。
   */
  @Select(
      """
      SELECT s.id AS scholarId, s.display_name AS displayName,
             s.organization_name AS organizationName, count(*) AS shared
      FROM core_contribution me
      JOIN core_achievement a ON a.id = me.achievement_id
      JOIN core_contribution other
        ON other.achievement_id = me.achievement_id AND other.scholar_id <> me.scholar_id
      JOIN core_scholar s ON s.id = other.scholar_id
      WHERE me.scholar_id = #{scholarId}
        AND me.relation_state IN ('AUTO', 'SELF_CONFIRMED')
        AND other.relation_state IN ('AUTO', 'SELF_CONFIRMED')
        AND s.merged_into_id IS NULL
        AND a.visibility = 'PUBLIC'
        AND a.effective_status = 'EFFECTIVE'
        AND a.merged_into_id IS NULL
      GROUP BY s.id, s.display_name, s.organization_name
      ORDER BY shared DESC, s.id
      LIMIT #{limit}
      """)
  List<CoauthorRow> topCoauthorsOf(@Param("scholarId") String scholarId, @Param("limit") int limit);

  record YearAmountRow(Integer year, long amount) {}

  record CoauthorRow(String scholarId, String displayName, String organizationName, long shared) {}

  /**
   * 引用量汇总。四个数字必须一起给：只有总和没有「多少篇有数据」，读者会把它当成全量合计。
   *
   * @param works 口径内成果总数（含非论文）
   * @param withCitation 确有引用量记录的成果数（引用量可为 0，0 是观测值不是缺失）
   * @param nonPaperWorks 类型上就没有「引用量」这一概念的成果（专利/项目/获奖）
   * @param totalCitations 这些记录的当前累计引用量之和
   * @param maxCitations 单篇最高，用来提示分布是否高度偏斜
   */
  record CitationSummaryRow(
      long works, long withCitation, long nonPaperWorks, long totalCitations, long maxCitations) {}

  record CitationYearRow(Integer year, long works, long withCitation, long citations) {}

  record CitationSourceRow(String source, long works) {}

  /**
   * 该学者口径内成果的引用量汇总。
   *
   * <p><b>为什么必须 LEFT JOIN 并单独数「有多少篇没数据」。</b>引用量存在 {@code core_paper}
   * 里，只有论文才有这一行；直接 JOIN 会把专利、项目、获奖整条吞掉，读者看到的「篇均引用」
   * 分母就悄悄变成了「有论文记录的成果数」。而把缺失值当 0 参与平均，等于把「来源没说」算成
   * 「没人引用」——这是本平台明令禁止的那种把推断当事实的做法。
   *
   * <p>口径与年份分布保持一致：关系合格、公开、生效、未合并。
   */
  @Select(
      """
      SELECT count(*) AS works,
             count(p.citation_count) AS withCitation,
             count(*) FILTER (WHERE a.type <> 'PAPER') AS nonPaperWorks,
             coalesce(sum(p.citation_count), 0) AS totalCitations,
             coalesce(max(p.citation_count), 0) AS maxCitations
      FROM core_contribution c
      JOIN core_achievement a ON a.id = c.achievement_id
      LEFT JOIN core_paper p ON p.achievement_id = a.id
      WHERE c.scholar_id = #{scholarId}
        AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
        AND a.visibility = 'PUBLIC'
        AND a.effective_status = 'EFFECTIVE'
        AND a.merged_into_id IS NULL
      """)
  CitationSummaryRow citationSummaryOf(@Param("scholarId") String scholarId);

  /**
   * 按发表年份分组的引用量。
   *
   * <p>年份缺失的成果不在这里出现（与年份分布同理，单列披露）。{@code works} 与该年
   * 年份分布柱的数值一致，便于界面把两个图对齐着看。
   */
  @Select(
      """
      SELECT a.publication_year AS year,
             count(*) AS works,
             count(p.citation_count) AS withCitation,
             coalesce(sum(p.citation_count), 0) AS citations
      FROM core_contribution c
      JOIN core_achievement a ON a.id = c.achievement_id
      LEFT JOIN core_paper p ON p.achievement_id = a.id
      WHERE c.scholar_id = #{scholarId}
        AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
        AND a.visibility = 'PUBLIC'
        AND a.effective_status = 'EFFECTIVE'
        AND a.merged_into_id IS NULL
        AND a.publication_year IS NOT NULL
      GROUP BY a.publication_year
      ORDER BY a.publication_year
      """)
  List<CitationYearRow> citationByYearOf(@Param("scholarId") String scholarId);

  /**
   * 引用量的来源分布。
   *
   * <p>必须披露：Crossref 与 OpenAlex 的计数口径不同，同一篇论文在两个来源下可以是两个数字。
   * 平台每篇只取一个来源的值，于是「把它们的和当成一个总数」在严格意义上并不成立——
   * 不说明这一点，页面上那个六位数会被读成「这位学者被引用多少次」。
   */
  @Select(
      """
      SELECT coalesce(p.citation_source, '来源未注明') AS source, count(*) AS works
      FROM core_contribution c
      JOIN core_achievement a ON a.id = c.achievement_id
      JOIN core_paper p ON p.achievement_id = a.id
      WHERE c.scholar_id = #{scholarId}
        AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
        AND a.visibility = 'PUBLIC'
        AND a.effective_status = 'EFFECTIVE'
        AND a.merged_into_id IS NULL
        AND p.citation_count IS NOT NULL
      GROUP BY 1
      ORDER BY works DESC, source
      """)
  List<CitationSourceRow> citationSourcesOf(@Param("scholarId") String scholarId);

  @Select(
      """
      SELECT id FROM core_contribution
      WHERE scholar_id = #{scholarId} AND achievement_id = #{achievementId}
      """)
  Optional<String> findContributionId(
      @Param("scholarId") String scholarId, @Param("achievementId") String achievementId);

  @Select(
      """
      SELECT id AS id, scholar_id AS scholarId, achievement_id AS achievementId, role AS role,
             author_order AS authorOrder, relation_state AS relationState,
             source_record_id AS sourceRecordId, version AS version,
             created_at AS createdAt, updated_at AS updatedAt
      FROM core_contribution WHERE id = #{contributionId}
      """)
  Optional<ContributionRow> findContributionById(@Param("contributionId") String contributionId);

  /**
   * 处于指定关系状态的参与关系，按更新时间倒序。
   *
   * <p>治理排序要用「最近变化」：争议越新越可能还热着，压在列表末尾只会让争议在公开视图里
   * 长期保持沉默。
   */
  @Select(
      """
      SELECT id AS id, scholar_id AS scholarId, achievement_id AS achievementId, role AS role,
             author_order AS authorOrder, relation_state AS relationState,
             source_record_id AS sourceRecordId, version AS version,
             created_at AS createdAt, updated_at AS updatedAt
      FROM core_contribution
      WHERE relation_state = #{relationState}
      ORDER BY updated_at DESC, id
      LIMIT #{limit}
      """)
  List<ContributionRow> listByRelationState(
      @Param("relationState") String relationState, @Param("limit") int limit);

  @Select("SELECT count(*) FROM core_contribution")
  long countContributions();

  /**
   * 状态流转：解除本人关系时改为 EXCLUDED 而不是删除行。
   *
   * <p>删除参与关系会让「曾经存在过、后被审核排除」这段历史消失；共享成果与其他参与者的
   * 关系也不得因某人解除而受影响（AC-IMPL-FR07-01）。
   */
  @Update(
      """
      UPDATE core_contribution
      SET relation_state = #{toState}, version = version + 1, updated_at = now()
      WHERE id = #{contributionId} AND relation_state = #{fromState}
      """)
  int transitionRelationState(
      @Param("contributionId") String contributionId,
      @Param("fromState") String fromState,
      @Param("toState") String toState);

  @Select("SELECT relation_state FROM core_contribution WHERE id = #{contributionId}")
  Optional<String> findRelationState(@Param("contributionId") String contributionId);

  // ---------------------------------------------------------------------------
  // 发生时机归属
  // ---------------------------------------------------------------------------

  @Insert(
      """
      INSERT INTO core_contribution_org (contribution_id, organization_id)
      VALUES (#{contributionId}, #{organizationId})
      ON CONFLICT (contribution_id, organization_id) DO NOTHING
      """)
  int linkOrganization(
      @Param("contributionId") String contributionId,
      @Param("organizationId") String organizationId);

  @Select(
      """
      SELECT count(*) FROM core_contribution_org WHERE contribution_id = #{contributionId}
      """)
  int countOrganizationsOf(@Param("contributionId") String contributionId);

  /**
   * 参与关系在成果发生时的机构规范名。
   *
   * <p>按机构 id 排序返回固定顺序：投影载荷要可比对，不能让行序随机漂移导致同一份数据每次
   * 计算出不同的摘要。缺失机构归属时返回空列表（未知即未知），不退化为当前任职机构。
   */
  @Select(
      """
      SELECT o.canonical_name
      FROM core_contribution_org co
      JOIN ref_organization o ON o.id = co.organization_id
      WHERE co.contribution_id = #{contributionId}
      ORDER BY o.id
      """)
  List<String> listOrganizationNames(@Param("contributionId") String contributionId);

  // ---------------------------------------------------------------------------
  // 领域与分类
  // ---------------------------------------------------------------------------

  @Select("SELECT id FROM ref_topic WHERE name_norm = #{nameNorm}")
  Optional<String> findTopicIdByNameNorm(@Param("nameNorm") String nameNorm);

  @Insert(
      """
      INSERT INTO ref_topic (id, canonical_name, name_norm, source_category)
      VALUES (#{id}, #{canonicalName}, #{nameNorm}, #{sourceCategory})
      ON CONFLICT (name_norm) DO NOTHING
      """)
  int insertTopicIfAbsent(
      @Param("id") String id,
      @Param("canonicalName") String canonicalName,
      @Param("nameNorm") String nameNorm,
      @Param("sourceCategory") String sourceCategory);

  @Select(
      """
      SELECT id AS id, canonical_name AS canonicalName, name_norm AS nameNorm,
             source_category AS sourceCategory
      FROM ref_topic WHERE id = #{id}
      """)
  Optional<Topic> findTopicById(@Param("id") String id);

  @Insert(
      """
      INSERT INTO core_achievement_topic (achievement_id, topic_id, source_category)
      VALUES (#{achievementId}, #{topicId}, #{sourceCategory})
      ON CONFLICT (achievement_id, topic_id) DO NOTHING
      """)
  int linkTopic(
      @Param("achievementId") String achievementId,
      @Param("topicId") String topicId,
      @Param("sourceCategory") String sourceCategory);

  @Select(
      """
      SELECT topic_id FROM core_achievement_topic
      WHERE achievement_id = #{achievementId} ORDER BY topic_id
      """)
  List<String> listTopicIdsOf(@Param("achievementId") String achievementId);

  /**
   * 带归属来源的领域列表。
   *
   * <p>来源分类排在推断之前：来源是权威，展示顺序应当反映这一点，而不是按 kind 的字典序
   * （那是 INFERRED 在前）。
   */
  @Select(
      """
      SELECT topic_id AS topicId, assignment_kind AS assignmentKind, confidence AS confidence
      FROM core_achievement_topic
      WHERE achievement_id = #{achievementId}
      ORDER BY (assignment_kind = 'SOURCE') DESC, topic_id
      """)
  List<TopicAssignment> listAssignmentsOf(@Param("achievementId") String achievementId);

  @Delete(
      """
      DELETE FROM core_achievement_topic
      WHERE achievement_id = #{achievementId} AND assignment_kind = 'INFERRED'
      """)
  int deleteInferredTopicsOfAchievement(@Param("achievementId") String achievementId);

  @Delete("DELETE FROM core_topic_inference WHERE achievement_id = #{achievementId}")
  int deleteInferenceOfAchievement(@Param("achievementId") String achievementId);

  @Select("SELECT count(*) FROM ref_topic")
  long countTopics();

  // ---------------------------------------------------------------------------
  // 机构
  // ---------------------------------------------------------------------------

  @Select("SELECT id FROM ref_organization WHERE name_norm = #{nameNorm}")
  Optional<String> findOrganizationIdByNameNorm(@Param("nameNorm") String nameNorm);

  @Insert(
      """
      INSERT INTO ref_organization (id, canonical_name, name_norm, country_code)
      VALUES (#{id}, #{canonicalName}, #{nameNorm}, #{countryCode})
      ON CONFLICT (name_norm) DO NOTHING
      """)
  int insertOrganizationIfAbsent(
      @Param("id") String id,
      @Param("canonicalName") String canonicalName,
      @Param("nameNorm") String nameNorm,
      @Param("countryCode") String countryCode);

  default Contribution toDomain(ContributionRow row) {
    return new Contribution(
        row.id(),
        row.scholarId(),
        row.achievementId(),
        row.role(),
        row.authorOrder(),
        RelationState.valueOf(row.relationState()),
        row.sourceRecordId(),
        row.version(),
        row.createdAt(),
        row.updatedAt());
  }
}
