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

    Optional<DedupCandidate> findById(String id);

    long countOpenCandidates();

    long countOpenFor(String achievementId);

    boolean resolve(String id, String status, String resolvedBy, String reason);
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

    /** 迁移结果统计。 */
    record ContributionMove(int inserted, int removed) {}
  }
}
