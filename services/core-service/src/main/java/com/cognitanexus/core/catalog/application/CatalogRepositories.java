package com.cognitanexus.core.catalog.application;

import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.AchievementDetails;
import com.cognitanexus.core.catalog.domain.Contribution;
import com.cognitanexus.core.catalog.domain.FieldProvenance;
import com.cognitanexus.core.catalog.domain.IdentifierKind;
import com.cognitanexus.core.catalog.domain.Scholar;
import com.cognitanexus.core.catalog.domain.SourceRecord;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.RelationState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 目录模块的持久化出站端口。
 *
 * <p>依据：编码规范 §2.2。端口在应用层定义、由基础设施实现；应用层不感知 MyBatis。
 */
public interface CatalogRepositories {

  /** 来源配置。来源许可与允许展示规则以 core 为权威（架构 §4.2）。 */
  interface SourceConfigRepository {

    /**
     * 来源配置。
     *
     * <p>{@code enabled} 是<b>采集开关</b>，{@code displayLicenseStatus} 是<b>展示许可</b>，
     * 两者互不影响（ADR-001 §3 方案 A）：暂停采集不动许可，撤销许可也不停止采集——
     * 后者仍可为已授权范围更新元数据。
     */
    record SourceConfig(
        String id,
        String sourceCode,
        String displayName,
        boolean enabled,
        String licenseScope,
        String baseUrl,
        int configVersion,
        List<String> allowedFields,
        String displayLicenseStatus,
        java.time.Instant displayLicenseChangedAt,
        String displayLicenseNote) {

      public boolean displayLicenseGranted() {
        return "GRANTED".equals(displayLicenseStatus);
      }
    }

    Optional<SourceConfig> findByCode(String sourceCode);

    Optional<SourceConfig> findById(String id);

    List<SourceConfig> listAll();

    /**
     * 登记一个来源配置。
     *
     * <p>同 source_code 已存在时返回 false 且不做任何修改：来源的许可范围与字段白名单属于
     * 需要评审的事实，不能被一次重复登记悄悄重置。
     */
    boolean register(
        String id,
        String sourceCode,
        String displayName,
        boolean enabled,
        String licenseScope,
        String baseUrl,
        List<String> allowedFields);

    /** 更新启用状态；来源停用后不得再以旧副本启动新任务。 */
    int setEnabled(String sourceId, boolean enabled);

    /** 更新字段白名单并提升配置版本；白名单变化后适配器必须按新版本执行。 */
    int updateAllowedFields(String sourceId, List<String> allowedFields);

    /**
     * 设置展示许可状态（{@code GRANTED} / {@code REVOKED}）；状态未变时返回 0。
     *
     * <p>它只改许可位。撤权带来的成果收紧是另一件事，由撤权作业逐批完成——两者分开，
     * 才能让「阻断」即时发生、而「落地」可以慢慢来（ADR-001 §4）。
     */
    int setDisplayLicense(String sourceId, String status, String note);
  }

  /** 原始来源记录。原值只追加；幂等重复不改原值。 */
  interface SourceRecordRepository {

    /** 首次接收返回 true；相同交接键已存在返回 false，此时不修改任何原值。 */
    boolean insertIfAbsent(SourceRecord record);

    Optional<SourceRecord> findByHandoffKey(
        String sourceId, String sourceRecordId, String contentFingerprint);

    /** 已有接收结果：用交接键反查当时落到的成果，使重复交接能返回原接收结果。 */
    Optional<String> findLinkedAchievementId(String sourceId, String sourceRecordId);

    long countAll();
  }

  /** 成果仓储。 */
  interface AchievementRepository {

    void insert(Achievement achievement);

    Optional<Achievement> findById(String id);

    /** 解析合并重定向：旧标识必须跳到幸存实体，不能让旧链接指向错误对象。 */
    Optional<String> resolveSurvivorId(String achievementId);

    void insertPaper(AchievementDetails.Paper paper);

    void insertPatent(AchievementDetails.Patent patent);

    void insertProject(AchievementDetails.ResearchProject project);

    void insertAward(AchievementDetails.Award award);

    Optional<AchievementDetails.Paper> findPaper(String achievementId);

    /**
     * 刷新既有成果的单一来源指标（引用量与来源），返回受影响行数。
     *
     * <p>为什么需要单独一条通路：来源记录会以新版本再次到达同一个稳定标识（DOI）上，此时实体本身
     * 已经存在，接收流程走的是「追加来源归属」分支，不会重建扩展表——引用量因此永远停在首次接入
     * 那一刻，公开统计里也就永远没有数据。
     *
     * <p>SQL 内自带人工修订保护：只要该字段在 core_field_provenance 中已是 MANUAL 或 REVIEWED，
     * 本次更新不命中任何行。把保护写在 SQL 里而不是先读后判，是为了让「读」与「写」之间不留
     * 可被并发人工提交插队的窗口。
     *
     * @return 1 表示已刷新；0 表示成果不存在、非论文类型，或指标已被人工修订保护
     */
    int refreshPaperMetrics(String achievementId, Integer citationCount, String citationSource);

    Optional<AchievementDetails.Patent> findPatent(String achievementId);

    Optional<AchievementDetails.ResearchProject> findProject(String achievementId);

    Optional<AchievementDetails.Award> findAward(String achievementId);

    /** 条件更新生效状态与可见性；受影响行数必须为 1，否则说明版本或状态已被他人改变。 */
    int updatePublicationState(
        String achievementId, String effectiveStatus, String visibility, long expectedVersion);

    Optional<String> findAchievementIdByDoiNorm(String doiNorm);

    Optional<String> findAchievementIdByApplicationNoNorm(String applicationNoNorm);

    Optional<String> findAchievementIdByProjectNoNorm(String projectNoNorm);

    void linkSource(
        String id,
        String achievementId,
        String sourceRecordId,
        String sourceId,
        Instant sourceUpdatedAt,
        Instant receivedAt);

    /**
     * 标题相似候选检索，走三元组索引；仅用于生成候选，不用于自动合并。
     *
     * <p>返回相似度而不是只返回实体：相似度是治理人员排序待办的唯一依据。把它丢掉之后，
     * 下游只能拿阈值当相似度写进证据，几千条候选的证据里就会是同一个数字。
     */
    List<SimilarTitle> findSimilarByTitle(String titleNorm, double threshold, int limit);

    /** 相似候选：实体与它的真实相似度必须成对传递。 */
    record SimilarTitle(Achievement achievement, double similarity) {}

    /** 精确同标题匹配（归一后完全一致）。 */
    List<Achievement> findByExactTitle(String titleNorm, int limit);

    long countByNatureAndStatus(String dataNature, String effectiveStatus);

    List<Achievement> listByStatus(String effectiveStatus, int limit, int offset);

    long countByStatus(String effectiveStatus);

    List<Achievement> listPublicForExport(int limit, int offset);

    long countPublicForExport();

    /**
     * 除该来源外已无有效展示依据、且当前公开的成果标识；用于撤销展示许可时收紧可见性。
     *
     * <p>「有效」指那个来源的展示许可仍是 {@code GRANTED}——只暂停采集的来源仍构成有效依据。
     */
    List<String> findExclusivelyFromSource(String sourceId, int limit);

    /** 撤权受理时的目标条数。 */
    long countExclusivelyFromSource(String sourceId);

    /** 完成判据：仍公开、且已无任何有效展示依据的成果数（撤权作业收尾时必须为 0）。 */
    long countPublicWithoutValidLicense(String sourceId);

    /** 收紧一批并把收紧原因记为该来源；返回实际改动的成果标识。 */
    List<String> tightenBatchForSource(String sourceId, int limit);

    /**
     * 一条成果的全部来源引用，按接收时间排序。
     *
     * <p>详情页据此展示「这条元数据从哪几处来」：来源编码、来源记录号、原站外链、许可范围、
     * 数据性质与来源侧更新时间。不含原始载荷——对外要的是可核对，不是把采集到的内容全量倒出。
     */
    List<SourceRef> sourcesOf(String achievementId);

    /** 来源引用（对外可披露字段）。 */
    record SourceRef(
        String sourceId,
        String sourceCode,
        String displayName,
        String sourceRecordId,
        String externalUrl,
        String licenseScope,
        String dataNature,
        Instant sourceUpdatedAt,
        Instant receivedAt,
        String displayLicenseStatus) {}

    /** 恢复作业的目标条数：因该来源撤权被收紧、当前仍受限的成果数。 */
    long countRestrictedBySource(String sourceId);

    /** 因该来源撤权被收紧的成果的参与学者（去重），供撤权通知定位接收者（ADR-003 §3）。 */
    java.util.List<String> listScholarIdsByRestrictionSource(String sourceId);

    /** 恢复许可时的一批回填：只放开「因该来源撤权被收紧、且现在又有有效依据」的成果。 */
    List<String> restoreBatchForSource(String sourceId, int limit);

    /** 收紧可见性；只降不升，且仅在当前可见性不同时产生一次版本变更。 */
    int tightenVisibility(String achievementId, String visibility);

    /**
     * 单条成果与给定归一题名的相似度；成果不存在时返回 {@code null}（与「相似度为 0」区分）。
     *
     * <p>与 {@link #findSimilarByTitle} 用同一个 PostgreSQL {@code similarity()}，
     * 保证「够得上候选」与「算不算明显矛盾」是同一种尺子量出来的。
     */
    Double titleSimilarity(String achievementId, String titleNorm);

    /**
     * 对稳定标识加事务级互斥，串行化同一标识的首次入库判定。
     *
     * <p>见 {@code AchievementMapper.lockStableIdentifier} 的说明：没有这把锁时，并发的
     * check-then-insert 会让后到的一方撞唯一约束、整批回滚。
     */
    void lockStableIdentifier(String kind, String normalizedValue);
  }

  /** 学者仓储。同名不直接合并：这里只提供精确匹配候选，置信判断在治理层。 */
  interface ScholarRepository {

    void insert(Scholar scholar);

    Optional<Scholar> findById(String id);

    List<Scholar> findByExactNameNorm(String nameNorm, int limit);

    Optional<String> findScholarIdByOrcid(String orcidNorm);

    Optional<String> findScholarIdByOpenAlexId(String openAlexIdNorm);

    void insertNameVariant(
        String id, String scholarId, String variant, String variantNorm, String evidence);

    void upsertOrganizationHint(String scholarId, String organizationName);

    Optional<String> resolveSurvivorId(String scholarId);

    int updateClaimedStatus(String scholarId, String claimedStatus, long expectedVersion);

    /**
     * 认领状态流转：只有指定前置状态才可迁移。
     *
     * <p>用前置条件而不是无条件赋值，是为了让并发场景下的「先到者生效、后到者失败」
     * 表现为可解释的 0 行影响，而不是后写覆盖先写。
     */
    int transitionClaimedStatus(String scholarId, String fromStatus, String toStatus);

    /** 写入经审核的研究方向简介。 */
    int updateResearchSummary(String scholarId, String researchSummary);

    /** 写入经审核的机构归属。 */
    int updateOrganizationName(String scholarId, String organizationName);

    // -------------------------------------------------------------------------
    // 门户自助维护（自述字段与代表作置顶）
    // -------------------------------------------------------------------------

    /** 读取自述字段；学者不存在时返回 empty（与 findById 区分开，避免把「不存在」说成「没填」）。 */
    Optional<com.cognitanexus.core.catalog.domain.ScholarProfile> findProfile(String scholarId);

    /** 写入主页地址，null 表示清空。 */
    int updateHomepageUrl(String scholarId, String homepageUrl);

    /** 写入头像引用与媒体类型；两者必须同时给（只有引用没有类型，响应头就无从填写）。 */
    int updateAvatar(String scholarId, String avatarRef, String avatarMediaType);

    /** 清空头像，公开页退回姓名首字占位。 */
    int clearAvatar(String scholarId);

    /** 置顶成果的标识，按名次升序。 */
    List<String> listFeatured(String scholarId);

    /**
     * 整体替换置顶顺序。
     *
     * <p>用「先删后插」而不是逐条 upsert：置顶是一份有序清单，逐条更新在名次互换时会撞上
     * 唯一索引，只能靠临时负数占位绕过，反而更脆。
     */
    void replaceFeatured(String scholarId, List<String> achievementIds);

    long countAll();
  }

  /** 参与关系仓储。一个学者对同一成果最多一条关系。 */
  interface ContributionRepository {

    /** 首次建立返回 true；已存在返回 false（不新增第二条关系，也不重复计数）。 */
    boolean insertIfAbsent(Contribution contribution);

    List<Contribution> listByAchievement(String achievementId);

    /** 定位某学者在某成果上的关系标识；未建立关系时返回 empty。 */
    Optional<String> findContributionId(String scholarId, String achievementId);

    Optional<Contribution> findContributionById(String contributionId);

    List<Contribution> listByScholar(String scholarId);

    /**
     * 按前置状态迁移关系状态，返回受影响行数。
     *
     * <p>用前置条件而不是无条件赋值：并发下「先到者生效、后到者失败」必须表现为可解释的 0 行，
     * 否则本人的异议会被一个稍后到达的确认动作静默覆盖。
     */
    int transitionRelationState(String contributionId, RelationState from, RelationState to);

    /** 指定关系状态的参与关系，用于治理端列出待裁决的争议。 */
    List<Contribution> listByRelationState(RelationState state, int limit);

    void linkOrganization(String contributionId, String organizationId);

    /**
     * 该参与关系在成果发生时的机构规范名，可零个或多个。
     *
     * <p>供公开投影载荷使用：查询侧按「成果发生时机构」统计，缺失即未知、单列不计入已知排名
     * （架构 §9.2）。不能用学者当前任职机构代替发生时机构。
     */
    List<String> listOrganizationNames(String contributionId);

    long countContributions();

    /**
     * 学者年份分布：只统计关系合格（AUTO / SELF_CONFIRMED）、成果对公众可见且未被合并的记录。
     *
     * <p>这三个条件缺一不可，它们决定这个数字能不能对外说：待核验关系多半是「同名但未必同人」，
     * 算进来就是把别人的成果记在这位学者名下；下架与被并走的成果算进来，则会让个人指标与公开
     * 检索页对不上。
     */
    List<YearAmount> yearDistributionOf(String scholarId);

    /** 关系合格但年份缺失的成果数：单列披露，不折算到任何一年。 */
    long countUndatedWorksOf(String scholarId);

    /** 该学者名下已被声明撤稿的公开成果数；只单独计数，不从总数里扣除。 */
    long countRetractedWorksOf(String scholarId);

    /**
     * 主要合作者，按共同合格成果数降序，最多 {@code limit} 位。
     *
     * <p>两端的参与关系都必须合格。只要求本人这一端合格是不够的：另一端若是待核验关系，
     * 这次「合作」本身就未被确认，把它排进合作者榜首等于用排行替来源背书。
     */
    List<CoauthorCount> topCoauthorsOf(String scholarId, int limit);

    /**
     * 引用量汇总（口径同上：关系合格、公开、生效、未合并）。
     *
     * <p>把「有多少篇有引用量记录」与「总和」放在同一条查询里返回，是因为这两个数字只有
     * 一起出现才有意义：单独的总和会被读成「全部成果的引用量」，而分母其实是其中一部分。
     * 缺失的引用量不补 0，单独计数。
     */
    CitationSummary citationSummaryOf(String scholarId);

    /** 按发表年份分组的引用量；{@code works} 与年份分布同源，便于两图对齐。 */
    List<CitationYear> citationByYearOf(String scholarId);

    /** 引用量的来源分布：跨来源求和不可直接比较，页面上必须把分布说出来。 */
    List<CitationSourceCount> citationSourcesOf(String scholarId);

    /** 年份桶。 */
    record YearAmount(int year, long amount) {}

    /** 合作者及其共同成果数。 */
    record CoauthorCount(
        String scholarId, String displayName, String organizationName, long sharedAchievements) {}

    /**
     * 引用量汇总。
     *
     * @param works 口径内成果数（含没有引用量概念的专利/项目/获奖）
     * @param withCitation 确有引用量记录的成果数；0 是观测值，与缺失不同
     * @param nonPaperWorks 类型上没有引用量的成果数
     * @param totalCitations 上述记录的当前累计引用量之和
     * @param maxCitations 单篇最高，用于提示分布偏斜
     */
    record CitationSummary(
        long works,
        long withCitation,
        long nonPaperWorks,
        long totalCitations,
        long maxCitations) {}

    /** 某一年发表成果的引用量：{@code works} 为该年成果数，{@code withCitation} 为其中有引用量记录的数量。 */
    record CitationYear(Integer year, long works, long withCitation, long citations) {}

    /** 某个来源贡献了多少条引用量记录。 */
    record CitationSourceCount(String source, long works) {}
  }

  /** 领域（分类）仓储。无法可靠映射时标记待分类，不强行归类（SRS 5.3）。 */
  interface TopicRepository {

    record Topic(String id, String canonicalName, String nameNorm, String sourceCategory) {}

    /**
     * 一条成果—领域归属，带上「这条归属是怎么来的」。
     *
     * @param assignmentKind SOURCE=来源元数据给出的分类，INFERRED=本系统按受控词表推断
     * @param confidence 推断置信度 0..1；来源分类为 null（来源不提供这个概念）
     */
    record TopicAssignment(
        String topicId, String assignmentKind, java.math.BigDecimal confidence) {}

    /** 按规范名查找或创建；返回 topicId。 */
    String findOrCreate(String canonicalName);

    Optional<Topic> findTopic(String id);

    void link(String achievementId, String topicId, String sourceCategory);

    List<String> listTopicIdsOf(String achievementId);

    /** 带归属来源的领域列表。投影必须走这一条：只带 id 的列表会让查询侧无法区分来源与推断。 */
    List<TopicAssignment> listAssignmentsOf(String achievementId);

    /**
     * 清除某成果由推断产生的全部领域归属与推断台账，返回被清掉的归属行数。
     *
     * <p>来源给出分类时调用。来源是权威，推断只是来源缺席时的替代品；替代品留着会与来源分类
     * 并列显示，让读者以为该成果有两个独立来源的领域归属。台账一并清除，避免留下「推断为 X」
     * 而库里实际挂的是来源分类这种前后不一致的痕迹。
     */
    int clearInferred(String achievementId);

    long countTopics();
  }

  /** 机构仓储。 */
  interface OrganizationRepository {

    /** 按规范名查找或创建；返回 organizationId。缺失标未知，不静默使用当前任职机构。 */
    String findOrCreate(String canonicalName, String countryCode);

    Optional<String> findIdByNameNorm(String nameNorm);
  }

  /** 稳定标识注册。注册键 = 实体类型 + 标识种类 + 作用域 + 规范值（架构 §5.3）。 */
  interface IdentifierRegistryRepository {

    /** 查找已注册的规范实体；未注册返回 empty。 */
    Optional<String> findEntityId(
        String entityType, IdentifierKind kind, String scope, String normalizedValue);

    /** 注册绑定；返回 false 表示该注册键已被其他实体占用（冲突须转为治理候选）。 */
    boolean register(
        String id,
        String entityType,
        String entityId,
        IdentifierKind kind,
        String scope,
        String normalizedValue);

    /** 该实体是否有任何已登记的稳定标识。用于裁决后重评估发布时复核「有无稳定标识」这一条件。 */
    boolean hasRegistration(String entityType, String entityId);

    /**
     * 该实体在某类标识下已登记的全部规范值。
     *
     * <p>判「同名但标识不同」需要它：既有学者已持有另一个 ORCID，说明来的是另一个同名者；
     * 既有学者一个标识都没有，则可能只是这一次才带上标识。两种处置不同，不能都按「同名就复用」。
     */
    List<String> registeredValues(String entityType, String entityId, IdentifierKind kind);
  }

  /** 字段级溯源。自动更新只写 SOURCE 级，不得覆盖人工与已审核值。 */
  interface FieldProvenanceRepository {

    void upsertSource(
        String entityType,
        String entityId,
        String fieldName,
        String valueText,
        String sourceRecordId);

    Optional<FieldProvenance> find(String entityType, String entityId, String fieldName);

    List<FieldProvenance> listByEntity(String entityType, String entityId);

    /**
     * 写入人工或已审核值。
     *
     * <p>一旦升级为 MANUAL/REVIEWED，后续来源自动更新不得再覆盖该字段
     * （BR-IMP-04、AC-IMPL-FR10-01）。
     */
    void upsertReviewed(
        String entityType, String entityId, String fieldName, String valueLevel, String valueText);
  }

  /** 类型一致性校验：成果类型的扩展是否存在，用于不变量自检。 */
  int countExtensions(String achievementId, AchievementType type);

  /**
   * 引用网络：参考文献观测与引用条目。
   *
   * <p>依据：架构 V0.2 §5.1、SRS FR06。与「引用量」（{@code core_paper.citation_count}）是两件
   * 不同的事实：引用量是来源给的一个数字，引用网络是「谁引用了谁」的边。前者可以由一个来源
   * 单独提供，后者必须有实际的参考文献列表；两者都缺时都不能补 0。
   */
  interface CitationRepository {

    /** 一篇成果的参考文献采集证据。{@code referencesTotal} 为来源声明的总数，缺失即 null。 */
    record Observation(
        String achievementId,
        String provider,
        String providerWorkId,
        Integer referencesTotal,
        int referencesListed,
        String outcome,
        java.time.Instant fetchedAt) {}

    /** 一条参考文献条目。{@code citedAchievementId} 非空即为可显示的边。 */
    record Reference(
        String id,
        String citingAchievementId,
        String citedKind,
        String citedValueNorm,
        String citedAchievementId,
        String resolution,
        java.time.Instant observedAt) {}

    /** 引用/被引列表里的一篇成果。 */
    record CitedWork(
        String achievementId,
        String title,
        AchievementType type,
        Integer publicationYear,
        boolean yearKnown,
        String venue,
        String doi) {}

    /** 本篇引用条目的解析结果计数：站内、站外、自引三者互不重叠。 */
    record ResolutionCounts(int resolved, int external, int selfReferences) {}

    /** 外部引用的标识键（重新解析时的输入）。 */
    record CitedKey(String kind, String valueNorm) {}

    /** 还没有参考文献观测的可见论文。 */
    record PendingWork(String achievementId, String doi, Integer citationCount) {}

    java.util.Optional<Observation> findObservation(String achievementId);

    void saveObservation(Observation observation);

    /** 重采时整体替换本篇的引用条目；返回删除行数。 */
    int deleteReferencesOf(String citingAchievementId);

    void insertReference(Reference reference);

    java.util.List<CitedWork> listResolvedReferences(String citingAchievementId);

    java.util.List<CitedWork> listCitedBy(String citedAchievementId);

    ResolutionCounts countReferencesOf(String citingAchievementId);

    java.util.List<CitedKey> listUnresolvedKeys(int limit);

    /**
     * 把一个标识键下的所有外部引用升级为已解析。
     *
     * <p>按标识批量更新而不是逐行：同一篇被引文献通常被多篇引用，逐个更新会把同一件事做很多遍。
     */
    int resolveByKey(
        String kind, String valueNorm, String citedAchievementId, java.time.Instant at);

    int countObservedWorks();

    int countNotFoundWorks();

    int countPublicPapers();

    java.util.List<PendingWork> listPendingWorks(int limit, String search, String topicId);

    int countPendingWorks(String search, String topicId);
  }
}
