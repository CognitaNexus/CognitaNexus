package com.cognitanexus.core.governance.application;

import com.cognitanexus.core.governance.domain.DedupCandidate;
import com.cognitanexus.core.governance.domain.GovernanceAnomaly;
import com.cognitanexus.core.governance.domain.MergeLedgerEntry;
import java.util.List;
import java.util.Optional;

/**
 * 治理模块的持久化出站端口。
 *
 * <p>依据：编码规范 §2.2、架构 V0.2 §4.3（governance 处理去重、消歧并经 catalog 发布）。
 *
 * <p>合并机制（重定向、来源归属迁移、关系迁移）放在治理域而不是目录域，因为「合并」是治理裁决的
 * 结果，需要与台账在同一次写入中成立；把机制拆到目录域会让台账与实际变更可能不一致。
 */
public interface GovernanceRepositories {

  /** 去重候选仓储。候选状态可变，但历史裁决结论不覆盖：重复裁决返回 0 行影响。 */
  interface DedupCandidateRepository {

    boolean insertIfAbsent(DedupCandidate candidate);

    List<DedupCandidate> listOpenCandidates(int limit, int offset);

    /**
     * 工作台列表：带相似度，可按相似度下限筛选、按白名单排序。
     *
     * <p>与 {@link #listOpenCandidates} 并存而不是替换：那个方法按创建时间倒序给出「最新登记」，
     * 适合观察采集节奏；本方法按可信度组织待办，适合处理积压。两者服务的问题不同。
     *
     * @param minSimilarity 相似度下限；null 表示不限
     * @param sortBy 排序键白名单取值；未知取值由实现回落到相似度降序
     */
    List<ScoredCandidate> listOpenCandidatesScored(
        Double minSimilarity, String sortBy, int limit, int offset);

    long countOpenCandidatesByFilter(Double minSimilarity);

    /** 按相似度分档计数，用于让「先处理哪一批」成为一个可判断的问题。 */
    CandidateBuckets countOpenCandidatesByBucket(double highLow, double midLow);

    Optional<DedupCandidate> findById(String id);

    /**
     * 两条实体之间的候选（不分左右顺序、不限状态）。
     *
     * <p>不限状态是刻意的：改判时要先看「最终实体这一对是不是已经被问过」。若已经问过且已裁决，
     * 再建一条同样的问题只会把一条已经答过的问题重新排进待办。
     */
    Optional<DedupCandidate> findBetween(String leftId, String rightId);

    long countOpenCandidates();

    long countOpenFor(String achievementId);

    /** 两条实体之间的 OPEN 候选数（不分左右顺序）。 */
    long countOpenBetween(String leftId, String rightId);

    boolean resolve(String id, String status, String resolvedBy, String reason);

    /**
     * 裁决候选并把它的问题转交给另一条候选（status = REBASED）。
     *
     * <p>条件 status='OPEN' 与 {@link #resolve} 一致：已经有人下过结论的候选不被覆盖。承接指针
     * 与状态在同一条语句里写入，避免出现「已标记改判但不知道改判到哪」的中间态。
     */
    boolean resolveRebased(String id, String resolvedBy, String reason, String rebasedToId);

    /**
     * 随合并一并裁决两条实体之间的 OPEN 候选，返回受影响条数。
     *
     * <p>合并已经回答了候选提出的问题，留下 OPEN 会让它变成一条处理不掉的死条目。
     */
    int resolveOpenBetween(
        String leftId, String rightId, String status, String resolvedBy, String reason);
  }

  /** 候选及其真实相似度。 */
  record ScoredCandidate(DedupCandidate candidate, double similarity) {}

  /** 相似度分档计数。 */
  record CandidateBuckets(long high, long mid, long low, long total) {}

  /**
   * 候选两端的成果状态，<b>实时读取</b>。
   *
   * <p>为什么不能沿用候选自带的 {@code evidence}：那是一份登记候选那一刻的快照。用它渲染工作台
   * 会把「当时的结论」当成「现在的事实」——候选登记之后可能已经有别的治理动作把某一端合并走了，
   * 此时它显示的仍是「EFFECTIVE / PUBLIC」，而按它去合并会被服务端拒绝。治理人员照着过期状态
   * 做判断，得到的是一次失败和一个看不懂的错误码，而不是「这一端已经并走了」。
   *
   * <p>只按一批标识取一次，不做逐条查询：待办列表一页最多几十条，逐条查会把一次翻页变成几十次
   * 往返。
   */
  interface AchievementStateRepository {

    /** 按标识批量取状态；查不到的标识不返回（不补空对象，缺失就是缺失）。 */
    List<AchievementState> statesOf(java.util.Collection<String> ids);

    /** 成果的实时状态。<b>不</b>包含标题以外的任何来源事实，避免把这层用成第二个目录查询入口。 */
    record AchievementState(
        String id,
        String achievementType,
        String title,
        String dataNature,
        String visibility,
        String effectiveStatus,
        String mergedIntoId,
        Integer publicationYear,
        String yearPrecision) {

      /** 是否已被合并走（重定向到别的实体）。 */
      public boolean merged() {
        return mergedIntoId != null && !mergedIntoId.isBlank();
      }
    }
  }

  /** 合并/拆分台账仓储。只追加。 */
  interface MergeLedgerRepository {

    void append(MergeLedgerEntry entry);

    List<MergeLedgerEntry> listByEntity(String entityId, int limit);
  }

  /** 异常仓储。 */
  interface AnomalyRepository {

    void record(GovernanceAnomaly anomaly);

    List<GovernanceAnomaly> listOpenAnomalies(int limit, int offset);

    long countOpenAnomalies();

    boolean resolve(String id);
  }

  /** 合并所需的实体操作。 */
  interface MergeTargetRepository {

    /** 标记被合并成果并建立重定向；已存在重定向时返回 false，不覆盖首次结论。 */
    boolean markAchievementMerged(String mergedId, String survivorId);

    boolean markScholarMerged(String mergedId, String survivorId);

    int moveSourceLinks(String mergedId, String survivorId);

    int moveTopicLinks(String mergedId, String survivorId);

    /** 迁移参与关系：先补插去重再删除旧行，避免同一学者被重复计数。 */
    ContributionMove moveContributions(String mergedId, String survivorId);

    ContributionMove moveScholarContributions(String mergedScholarId, String survivorScholarId);

    /** 迁移姓名别名；重复别名留在原处，随后被清理，不产生两条同义别名。 */
    int moveNameVariants(String mergedScholarId, String survivorScholarId);

    int deleteNameVariantsOf(String mergedScholarId);

    /**
     * 把被合并门户的生效授权转到幸存门户。
     *
     * <p>仅在幸存门户当前没有生效授权时转移；两边都有管理者时不自动转移，由人工裁决。
     */
    int moveActiveAuthority(String mergedScholarId, String survivorScholarId);

    int revokeAuthorityOfScholar(String mergedScholarId, String reason);

    java.util.Optional<String> findActiveAuthorityId(String scholarId);

    List<String> snapshotContributions(String achievementId);

    List<String> snapshotScholarContributions(String scholarId);

    List<String> snapshotSources(String achievementId);

    // -------------------------------------------------------------------------
    // 合并影响预览（只读）
    // -------------------------------------------------------------------------
    //
    // 预览与执行必须共用同一批查询语义，否则「预览说会迁移 3 条、执行时迁了 5 条」这种不一致
    // 会让预览失去意义。因此这些方法只做读取，具体判断放在应用层，只有一份。

    List<PreviewContribution> previewContributions(String achievementId);

    List<PreviewSource> previewSources(String achievementId);

    List<String> previewTopics(String achievementId);

    List<RegisteredIdentifier> previewIdentifiers(String entityType, String entityId);

    /** 把被合并实体的稳定标识改指幸存实体，返回改指条数。 */
    int moveIdentifiers(String entityType, String mergedId, String survivorId);

    /** 参与关系预览：学者 + 关系状态 + 角色。 */
    record PreviewContribution(
        String scholarId, String scholarName, String relationState, String role) {}

    /** 来源归属预览。 */
    record PreviewSource(String sourceId, String sourceRecordId) {}

    /** 已登记稳定标识预览。 */
    record RegisteredIdentifier(String identifierKind, String scope, String normalizedValue) {}

    /** 迁移结果统计。 */
    record ContributionMove(int inserted, int removed) {}
  }
}
