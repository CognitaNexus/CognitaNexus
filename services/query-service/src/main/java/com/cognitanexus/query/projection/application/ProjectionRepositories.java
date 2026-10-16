package com.cognitanexus.query.projection.application;

import com.cognitanexus.query.projection.domain.ConsumerProgress;
import com.cognitanexus.query.projection.domain.InboxEventRecord;
import com.cognitanexus.query.projection.domain.ProjectedAchievement;
import com.cognitanexus.query.projection.domain.ProjectedContribution;
import com.cognitanexus.query.projection.domain.ProjectedTopic;
import com.cognitanexus.query.projection.domain.ScholarPair;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 投影模块的持久化出站端口。
 *
 * <p>依据：编码规范 §2.2。端口在应用层定义、由基础设施实现；应用层不感知 MyBatis。
 *
 * <p>分成三个接口而不是一个：Inbox 写入（接收侧）、读模型写入（投影侧）、维护操作（重建与
 * generation 切换）三者的调用方与事务边界完全不同。合成一个接口会让「谁有权切换 generation」
 * 这个问题在类型系统里消失。
 */
public interface ProjectionRepositories {

  /** Inbox 与水位。 */
  interface InboxStore {

    /** 幂等写入；返回真正新增的行数。重复投递不报错、不覆盖已发生过的处理状态。 */
    int insertIfAbsent(List<InboxEventRecord> events);

    /** 待处理事件，按 catalogRevision 升序；只返回已到重试时刻的 RECEIVED 行。 */
    List<InboxEventRecord> listPending(String consumerId, int limit);

    /**
     * 读取进度并在当前事务内加行锁。
     *
     * <p>投影应用与 generation 切换都通过这一行串行化：若应用事务不加锁，一次并发重建切换可能
     * 让应用事务继续往已被切换掉的旧代写入，而那批写入永远不会被任何查询看到。
     */
    ConsumerProgress lockProgress(String consumerId);

    ConsumerProgress readProgress(String consumerId);

    /** 记录见过的最大修订，用于观察积压与缺口宽度；不参与发布判断。 */
    int observeHighestSeen(String consumerId, long revision);

    /** 按连续规则推进 receivedRevision；返回推进后的值。 */
    long advanceReceivedRevision(String consumerId);

    /** 推进 publishedRevision；只允许前进。 */
    int advancePublishedRevision(String consumerId, long revision);

    int markApplied(String id);

    int markFailed(String id, String error, int backoffSeconds);

    int markIsolated(String id, String reason);

    /** 把超过退避窗口的 FAILED 行重新置为 RECEIVED；返回重新入队行数。 */
    int rearmFailed(String consumerId, int maxAttempts);

    Map<String, Long> countByState(String consumerId);

    /** 某修订之后已持久接收到的事件，按修订升序；用于重建时在快照之后回放。 */
    List<InboxEventRecord> listAfterRevision(String consumerId, long revision, int limit);

    /** 最早被隔离的修订；无隔离返回 0。用于让「水位停住」这件事可被看见。 */
    long oldestIsolatedRevision(String consumerId);

    /** 该修订之后最早的隔离/失败事件修订号；无阻塞返回 0。 */
    long firstBlockedRevisionAfter(String consumerId, long revision);

    /**
     * 该修订之后最早一条已持久接收事件所占的修订值；无此类事件时返回 empty。
     *
     * <p>用途是识别<b>修订空洞</b>。连续门控要求事件一条不落地按序到达；若权威侧某条事件被永久
     * 判死（投递重试耗尽）而消费方在此之后的事件照常收到，本地就会出现「已发布水位 N，却直接收到
     * N+k」的形状。这种情况不会自愈，也不会抛错——门控只是不停地把新到事件判为「还没轮到」，
     * 水位永远停在 N。把这段空洞显式读出来，才能把它从「系统看起来在忙」变成「系统明确坏了」。
     */
    OptionalLong firstReceivedRevisionAfter(String consumerId, long revision);

    Optional<InboxEventRecord> findById(String id);
  }

  /** 读模型写入。所有写入都必须显式给出 generation，禁止默认写到「当前代」。 */
  interface ReadModelStore {

    void upsertAchievement(String generation, ProjectedAchievement achievement);

    /** 删除成果及其全部派生数据（参与关系、机构、领域、合作边）。 */
    void deleteAchievement(String generation, String achievementId);

    /** 收紧可见性为 RESTRICTED，并移除该成果的合作边；返回受影响行数。 */
    int restrictAchievement(String generation, String achievementId, long revision);

    void replaceContributions(
        String generation, String achievementId, List<ProjectedContribution> contributions);

    void replaceTopics(String generation, String achievementId, List<ProjectedTopic> topics);

    /** 先删该成果全部边再插入新边，不靠累计加一（架构 §9.3）。 */
    void replaceEdges(String generation, String achievementId, List<ScholarPair> pairs);

    void deleteEdges(String generation, String achievementId);

    /**
     * 把被合并学者的参与关系与发生时机构整体改挂到幸存学者。
     *
     * <p>幸存者名称一并传入：合并事件带上了幸存者姓名，投影若继续沿用旧名，检索结果里的作者名
     * 会与核心的门户显示长期不一致，而查询侧没有任何自愈途径。
     */
    int rewriteScholarContributions(
        String generation, String mergedId, String survivorId, String survivorName);

    int updateScholarName(String generation, String scholarId, String scholarName);

    /** 列出该学者当前参与的全部成果标识。 */
    List<String> listAchievementIdsOfScholar(String generation, String scholarId);

    Optional<ProjectedAchievement> findAchievement(String generation, String achievementId);

    List<ProjectedContribution> listContributions(String generation, String achievementId);

    void recordRedirect(String entityType, String oldId, String newId, long revision);
  }

  /** generation 与重建运行记录。 */
  interface MaintenanceStore {

    String activeGeneration(String consumerId);

    void createGeneration(String generation, String note);

    void markGenerationReady(String generation);

    void retireGeneration(String generation);

    void markGenerationFailed(String generation, String note);

    /** 原子切换活动代；调用方必须已持有进度行锁。 */
    int switchGeneration(String consumerId, String generation, long publishedRevision);

    String startRebuild(String id, String generation, Long baseRevision);

    void finishRebuild(
        String id, String status, Long replayedRevision, long importedRows, String note);

    List<GenerationView> listGenerations();

    List<ProjectionRepositories.RebuildRun> listRebuildRuns(int limit);

    /** 上一代按保留窗口保留，这里只做计数，供运维决定何时清理。 */
    long countRetiredGenerations(String protectGeneration);

    /** 清理已经退役的代；返回清理的代数。调用方负责确认在途请求已排空。 */
    int purgeRetiredGenerations(String protectGeneration);
  }

  /** 重建运行记录视图。 */
  record RebuildRun(
      String id,
      String generation,
      String status,
      java.time.Instant startedAt,
      java.time.Instant finishedAt,
      Long baseRevision,
      Long replayedRevision,
      long importedRows,
      String note) {}

  /** generation 视图。 */
  record GenerationView(
      String generation,
      String status,
      java.time.Instant createdAt,
      java.time.Instant readyAt,
      String note) {}
}
