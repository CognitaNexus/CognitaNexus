package com.cognitanexus.core.taxonomy.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.TopicRepository;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.Capabilities;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.taxonomy.domain.TopicClassification;
import com.cognitanexus.core.taxonomy.domain.TopicClassifier;
import com.cognitanexus.core.taxonomy.domain.TopicClassifier.Candidate;
import com.cognitanexus.sharedkernel.BusinessException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 领域推断。
 *
 * <p>依据：SRS 5.3（按学术领域分类管理；无法可靠映射时标记待分类，不强行归类）、任务书
 * 「学术成果按照学术领域分类管理，提供分类过滤和检索功能」。
 *
 * <p>三条不可让步的规则：
 *
 * <ol>
 *   <li><b>只处理没有来源分类的成果。</b>来源是权威，推断是来源缺席时的替代品。已有来源分类的
 *       成果不进候选集，因此不存在「推断覆盖来源」这条代码路径。
 *   <li><b>每条推断归属都必须带证据与规则版本。</b>没有证据的推断日后无法复查，也无法解释给
 *       读者听；库层用约束强制这一点（见 V10 迁移）。
 *   <li><b>判不出就如实记为待分类。</b>台账里 outcome=UNRESOLVED 的行既是 SRS 5.3 要求的「标记
 *       待分类」，也让批量推断能够增量推进——否则判不出的成果会永远霸占队列头部。
 * </ol>
 *
 * <p>本服务不写入 outbox 事件：领域归属属于投影载荷的一部分，成果本身没有变化，公开可见性也未
 * 改变。重算产生的新归属通过重建投影生效。
 */
@Service
public class TopicInferenceService {

  /** 单批上限。整批在一个事务里完成，批太大既撑大事务也拉长锁窗口。 */
  private static final int MAX_BATCH = 500;

  private static final int DEFAULT_BATCH = 200;

  /** 响应里带回的样例条数：够看清判成了什么，不至于把响应撑成一份全量数据。 */
  private static final int SAMPLE_SIZE = 5;

  private final TopicInferenceRepository inferences;
  private final TopicRepository topics;
  private final TopicClassifier classifier;
  private final AuditRecorder audit;

  public TopicInferenceService(
      TopicInferenceRepository inferences,
      TopicRepository topics,
      TopicClassifier classifier,
      AuditRecorder audit) {
    this.inferences = inferences;
    this.topics = topics;
    this.classifier = classifier;
    this.audit = audit;
  }

  /** 一条推断出来的归属。 */
  public record Assignment(
      String topicId, String fieldName, double score, double confidence, String evidence) {}

  /** 单条成果的处理结果。unresolvedReason 仅在判不出时非空。 */
  public record Item(
      String achievementId, String title, List<Assignment> assignments, String unresolvedReason) {}

  /**
   * 一批推断的结果。
   *
   * @param idempotentRows 因归属已存在而未新建的行数。它非零说明这一批里有成果被重复处理过，
   *     是判断「是否在做无用功」的直接依据；正常推进时应为 0。
   * @param supersededRows 因规则版本变化而被清掉的旧版本归属行数。
   */
  public record BatchReport(
      String ruleVersion,
      String taxonomy,
      boolean dryRun,
      int examined,
      int assigned,
      int unresolved,
      int createdRows,
      int idempotentRows,
      int supersededRows,
      long pendingRemaining,
      long inferredRows,
      long achievementsWithoutTopic,
      List<Item> samples) {}

  /**
   * 执行一批推断。
   *
   * @param dryRun 只算不写，用于先看清词表在当前数据上的效果
   * @param reason 非 dryRun 时必填：这批派生数据是在什么情况下产出的，属于审计要求
   */
  @Transactional
  public BatchReport infer(ActorContext actor, Integer limit, boolean dryRun, String reason) {
    Capabilities.require(actor, RoleNames.CAP_GOVERNANCE_MANAGE);
    if (!dryRun && (reason == null || reason.isBlank())) {
      throw BusinessException.invalidArgument("必须说明本次领域推断的理由");
    }
    String ruleVersion = classifier.taxonomy().ruleVersion();
    long inferredRowsBefore = inferences.countInferredRows(ruleVersion);
    long pendingBefore = inferences.countPending(ruleVersion);

    List<TopicInferenceRepository.PendingAchievement> pending =
        inferences.listPending(resolveBatchSize(limit), ruleVersion);

    int assigned = 0;
    int unresolved = 0;
    int createdRows = 0;
    int idempotentRows = 0;
    int supersededRows = 0;
    List<Item> samples = new ArrayList<>();

    for (TopicInferenceRepository.PendingAchievement item : pending) {
      List<TopicClassification> got =
          classifier.classify(item.title(), item.venue(), item.abstractText());
      if (got.isEmpty()) {
        unresolved++;
        String why = unresolvedReason(item);
        if (!dryRun) {
          inferences.upsertInference(item.achievementId(), ruleVersion, "UNRESOLVED", 0, null, why);
        }
        addSample(samples, new Item(item.achievementId(), item.title(), List.of(), why));
        continue;
      }

      assigned++;
      List<Assignment> assignments = new ArrayList<>();
      if (!dryRun) {
        // 换词表版本重算时先清掉旧版本的归属：同一成果上挂着两版推断，读起来像是它有两个来源，
        // 而实际上只是一次重算没收干净。
        supersededRows +=
            inferences.deleteInferredOfOtherVersions(item.achievementId(), ruleVersion);
        for (TopicClassification classification : got) {
          String topicId = topics.findOrCreate(classification.fieldName());
          int affected =
              inferences.linkInferred(
                  item.achievementId(),
                  topicId,
                  ruleVersion,
                  BigDecimal.valueOf(classification.confidence()),
                  classification.evidence());
          if (affected == 1) {
            createdRows++;
          } else {
            idempotentRows++;
          }
          assignments.add(
              new Assignment(
                  topicId,
                  classification.fieldName(),
                  classification.score(),
                  classification.confidence(),
                  classification.evidence()));
        }
        inferences.upsertInference(
            item.achievementId(),
            ruleVersion,
            "ASSIGNED",
            got.size(),
            BigDecimal.valueOf(got.get(0).confidence()),
            got.get(0).evidence());
      } else {
        for (TopicClassification classification : got) {
          assignments.add(
              new Assignment(
                  null,
                  classification.fieldName(),
                  classification.score(),
                  classification.confidence(),
                  classification.evidence()));
        }
      }
      addSample(samples, new Item(item.achievementId(), item.title(), assignments, null));
    }

    long inferredRowsAfter = inferences.countInferredRows(ruleVersion);
    if (!dryRun) {
      audit.record(
          AuditRecorder.AuditEntry.of(
              actor,
              "TOPIC_INFERENCE_APPLIED",
              "TAXONOMY",
              ruleVersion,
              Map.of("inferredRows", inferredRowsBefore, "pending", pendingBefore),
              afterSnapshot(assigned, unresolved, createdRows, supersededRows, inferredRowsAfter),
              reason));
    }
    return new BatchReport(
        ruleVersion,
        classifier.taxonomy().describe(),
        dryRun,
        pending.size(),
        assigned,
        unresolved,
        createdRows,
        idempotentRows,
        supersededRows,
        inferences.countPending(ruleVersion),
        inferredRowsAfter,
        inferences.countAchievementsWithoutTopic(),
        samples);
  }

  /** 覆盖率与台账概况。管理端用它回答「现在分类到什么程度了」。 */
  @Transactional(readOnly = true)
  public Map<String, Object> summary(ActorContext actor) {
    Capabilities.require(actor, RoleNames.CAP_GOVERNANCE_MANAGE);
    String ruleVersion = classifier.taxonomy().ruleVersion();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("ruleVersion", ruleVersion);
    view.put("taxonomy", classifier.taxonomy().describe());
    view.put("minScore", classifier.taxonomy().minScore());
    view.put("secondaryRatio", classifier.taxonomy().secondaryRatio());
    view.put("abstractWeight", classifier.taxonomy().abstractWeight());
    view.put("pending", inferences.countPending(ruleVersion));
    view.put("inferredRows", inferences.countInferredRows(ruleVersion));
    view.put("achievementsWithoutTopic", inferences.countAchievementsWithoutTopic());

    List<Map<String, Object>> byKind = new ArrayList<>();
    for (TopicInferenceRepository.KindCount count : inferences.countByKind()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("assignmentKind", count.kind());
      row.put("rows", count.rowCount());
      row.put("achievements", count.achievementCount());
      byKind.add(row);
    }
    view.put("byAssignmentKind", byKind);

    List<Map<String, Object>> byOutcome = new ArrayList<>();
    for (TopicInferenceRepository.OutcomeCount count : inferences.countByOutcome(ruleVersion)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("outcome", count.outcome());
      row.put("achievements", count.achievements());
      byOutcome.add(row);
    }
    view.put("byOutcome", byOutcome);
    return view;
  }

  /** 待分类清单：词表判不出领域的成果。这就是 SRS 5.3 所说的「标记待分类」。 */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> unresolved(ActorContext actor, Integer limit) {
    Capabilities.require(actor, RoleNames.CAP_GOVERNANCE_MANAGE);
    String ruleVersion = classifier.taxonomy().ruleVersion();
    int size = limit == null ? DEFAULT_BATCH : Math.min(Math.max(limit, 1), MAX_BATCH);
    List<Map<String, Object>> items = new ArrayList<>();
    for (TopicInferenceRepository.InferenceRecord record :
        inferences.listUnresolved(ruleVersion, size)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("achievementId", record.achievementId());
      row.put("outcome", record.outcome());
      row.put("reason", record.evidence());
      row.put("inferredAt", record.inferredAt() == null ? null : record.inferredAt().toString());
      items.add(row);
    }
    return items;
  }

  private static int resolveBatchSize(Integer limit) {
    if (limit == null) {
      return DEFAULT_BATCH;
    }
    if (limit < 1) {
      throw BusinessException.invalidArgument("批大小必须为正数");
    }
    return Math.min(limit, MAX_BATCH);
  }

  private Map<String, Object> afterSnapshot(
      int assigned, int unresolved, int createdRows, int supersededRows, long inferredRowsAfter) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("assigned", assigned);
    after.put("unresolved", unresolved);
    after.put("createdRows", createdRows);
    after.put("supersededRows", supersededRows);
    after.put("inferredRows", inferredRowsAfter);
    return after;
  }

  private static void addSample(List<Item> samples, Item item) {
    if (samples.size() < SAMPLE_SIZE) {
      samples.add(item);
    }
  }

  /**
   * 说明某条成果为什么判不出。
   *
   * <p>待分类清单的价值全在这句话上：只说「未分类」，治理人员无从判断是文本不足、阈值过高还是
   * 词表缺词。因此这里把最高得分、阈值与命中的判别词一并写出来。
   */
  private String unresolvedReason(TopicInferenceRepository.PendingAchievement item) {
    boolean noText = blank(item.title()) && blank(item.venue()) && blank(item.abstractText());
    if (noText) {
      return "题名、期刊与摘要均为空，没有可用于判断的文本";
    }
    List<Candidate> ranked = classifier.rank(item.title(), item.venue(), item.abstractText());
    if (ranked.isEmpty()) {
      return "题名、期刊与摘要中没有任何词表判别词命中，缺可依据的线索";
    }
    Candidate top = ranked.get(0);
    return "最高得分 "
        + top.score()
        + " 低于阈值 "
        + classifier.taxonomy().minScore()
        + "（首位候选："
        + top.fieldName()
        + "）；"
        + top.evidence();
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
