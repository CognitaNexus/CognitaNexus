package com.cognitanexus.query.projection.application;

import com.cognitanexus.query.platform.persistence.JsonCodec;
import com.cognitanexus.query.projection.application.ProjectionRepositories.ReadModelStore;
import com.cognitanexus.query.projection.domain.InboxEventRecord;
import com.cognitanexus.query.projection.domain.ProjectedAchievement;
import com.cognitanexus.query.projection.domain.ProjectedContribution;
import com.cognitanexus.query.projection.domain.ProjectedTopic;
import com.cognitanexus.query.projection.domain.ScholarPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 事件到读投影的应用逻辑。
 *
 * <p>依据：架构 V0.2 §7.1（发布协议）、§7.2（隐私收紧）、§9.3（合作边）。
 *
 * <p>本类只做「给定事件与目标 generation，写哪些读模型」的判断，不控制事务、不推进水位：
 * 事务边界在 {@code ProjectionUnitOfWork}，水位推进也在那里。把两件事分开的原因是，
 * 重建回放会用同一套应用逻辑写到一个非活动代上，此时绝不能推进发布水位。
 *
 * <p>三条不可让步的规则：
 *
 * <ol>
 *   <li>不认识的类型或更高的 schemaVersion 一律<b>隔离</b>，不猜字段含义继续投影；
 *   <li>隐私收紧事件必须同时移除合作边，不能只改可见性；
 *   <li>单篇成果的作者数超过建边上限时<b>隔离并记录</b>，绝不静默只取前 N 位作者。
 * </ol>
 */
@Component
class ProjectionApplier {

  private static final Logger log = LoggerFactory.getLogger(ProjectionApplier.class);

  private final ReadModelStore readModel;
  private final EventPayloadReader reader;
  private final ProjectionProperties properties;

  ProjectionApplier(ReadModelStore readModel, JsonCodec json, ProjectionProperties properties) {
    this.readModel = readModel;
    this.reader = new EventPayloadReader(json);
    this.properties = properties;
  }

  /** 一次应用的结论。 */
  record Outcome(boolean isolated, String note) {

    static Outcome applied(String note) {
      return new Outcome(false, note);
    }

    static Outcome isolate(String reason) {
      return new Outcome(true, reason);
    }

    boolean shouldIsolate() {
      return isolated;
    }
  }

  /**
   * 应用一条事件。
   *
   * @param survivorProjection 合并类事件所需的幸存实体当前投影，已在事务外取得；非合并事件为
   *     empty。之所以在事务外取，是因为架构 §6.1 明确禁止事务内访问外部 HTTP。
   */
  Outcome apply(
      String generation, InboxEventRecord event, Optional<Map<String, Object>> survivorProjection) {
    if (event.schemaVersion() > QueryEventTypes.SUPPORTED_SCHEMA_VERSION) {
      return Outcome.isolate("未知事件 schemaVersion=" + event.schemaVersion() + "，转隔离待处理而不是按当前版本猜测字段");
    }
    return switch (event.eventType()) {
      case QueryEventTypes.CATALOG_CHANGE_COMMITTED -> applyCatalogChange(generation, event);
      case QueryEventTypes.PUBLIC_VISIBILITY_TIGHTENED ->
          applyVisibilityTightened(generation, event);
      case QueryEventTypes.PUBLIC_VISIBILITY_RESTORED -> applyVisibilityRestored(generation, event);
      case QueryEventTypes.ACHIEVEMENT_MERGED ->
          applyAchievementMerged(generation, event, survivorProjection);
      case QueryEventTypes.SCHOLAR_MERGED -> applyScholarMerged(generation, event);
      case QueryEventTypes.PORTAL_AUTHORITY_GRANTED,
          QueryEventTypes.PORTAL_AUTHORITY_REVOKED,
          QueryEventTypes.CLAIM_DECIDED ->
          Outcome.applied("门户授权与认领结论不影响公开投影，仅记录已应用");
      default -> Outcome.isolate("未知事件类型 " + event.eventType() + "，转隔离待处理而不是丢弃后宣称已追平水位");
    };
  }

  // ---------------------------------------------------------------------------
  // 成果变更
  // ---------------------------------------------------------------------------

  private Outcome applyCatalogChange(String generation, InboxEventRecord event) {
    String changeKind = event.stringOf("changeKind");
    if (changeKind == null) {
      return Outcome.isolate("CatalogChangeCommitted 缺少 changeKind，无法判断应写入还是移除");
    }
    if (!QueryEventTypes.CHANGE_UPSERT.equals(changeKind)) {
      return Outcome.isolate("未知 changeKind=" + changeKind);
    }
    Optional<ProjectedAchievement> achievement = reader.achievement(event);
    if (achievement.isEmpty()) {
      return Outcome.isolate("成果事件缺少 achievementId/title/type/dataNature，无法构成可用投影");
    }
    ProjectedAchievement projected = achievement.get();
    List<ProjectedContribution> contributions =
        reader.contributions(event.payload().get("contributions"), projected.achievementId());
    if (contributions.size() > properties.maxAuthorsPerAchievementForEdges()) {
      // 组合边的增长是作者数的平方级。这里明确失败并记录，而不是只取前 N 位作者：
      // 截断会静默改变科研语义（少建边），而结果看起来完全正常。
      return Outcome.isolate(
          "参与关系数量 "
              + contributions.size()
              + " 超过建边上限 "
              + properties.maxAuthorsPerAchievementForEdges()
              + "，需异步构建或缩小范围，本轮不做静默截断");
    }
    writeAchievement(
        generation,
        projected,
        contributions,
        reader.topics(event.payload().get("topicViews"), event.payload().get("topics")));
    return Outcome.applied("已写入成果投影与派生集合");
  }

  // ---------------------------------------------------------------------------
  // 隐私收紧
  // ---------------------------------------------------------------------------

  private Outcome applyVisibilityTightened(String generation, InboxEventRecord event) {
    List<String> targets =
        stringList(
            event.payload().get("tightenedAchievementIds"), event.payload().get("tightened"));
    String single = event.stringOf("achievementId");
    if (single != null) {
      targets.add(single);
    }
    if (targets.isEmpty()) {
      // 来源级受理事件（撤权受理时逐批收紧还没跑，事件里只有 sourceId 与目标数，没有成果列表）。
      // 受理的即时性由公开读屏障保证：公开请求会实时向 core 核实权威水位与许可状态；
      // 投影里逐条撤下由后续的批次收紧事件（带成果列表）完成。把它隔离会让水位永久停住，
      // 反而让真正的恢复事件永远无法应用。
      if (event.stringOf("sourceId") != null) {
        return Outcome.applied("来源级受理事件（无成果列表），逐批收紧事件随后到达");
      }
      return Outcome.isolate("隐私收紧事件未指明受影响成果，无法确定应撤下哪些内容");
    }
    int tightened = 0;
    for (String achievementId : targets) {
      tightened +=
          readModel.restrictAchievement(generation, achievementId, event.catalogRevision());
    }
    // 未命中也要算已应用：来源停用时列出的成果可能本就不在投影里（未生效或已撤下），
    // 这不是错误，把它转成隔离会让水位无故停住。
    log.info(
        "隐私收紧已应用 revision={} 目标数={} 实际收紧={}", event.catalogRevision(), targets.size(), tightened);
    return Outcome.applied("已撤下 " + tightened + " 项并移除其合作边（目标 " + targets.size() + " 项）");
  }

  // ---------------------------------------------------------------------------
  // 隐私恢复
  // ---------------------------------------------------------------------------

  /**
   * 应用许可恢复事件。
   *
   * <p>core 侧恢复许可时逐条重新判定公开资格（ADR-001 §3），事件里列出的成果都已经重新满足
   * 公开资格，因此投影端直接把可见性翻回 PUBLIC 即可，不需要重新推断资格。
   *
   * <p>被收紧时合作边被删除而参与关系保留（restrictAchievement 只动这两处），恢复时从投影自身
   * 的参与关系重建该成果的合作边：关系数据没有离开过投影，向 core 重拉一遍既慢又引入第二个
   * 一致性窗口。若某成果在投影中不存在（未生效、或收紧后又被合并删除），跳过即可——与收紧
   * 事件同样的「未命中也算已应用」语义。
   */
  private Outcome applyVisibilityRestored(String generation, InboxEventRecord event) {
    List<String> targets =
        stringList(event.payload().get("restoredAchievementIds"), event.payload().get("restored"));
    String single = event.stringOf("achievementId");
    if (single != null) {
      targets.add(single);
    }
    if (targets.isEmpty()) {
      if (event.stringOf("sourceId") != null) {
        return Outcome.applied("来源级受理事件（无成果列表），逐批恢复事件随后到达");
      }
      return Outcome.isolate("恢复事件未指明受影响成果，无法确定应恢复哪些内容");
    }
    int restored = 0;
    for (String achievementId : targets) {
      int changed =
          readModel.restoreAchievement(generation, achievementId, event.catalogRevision());
      if (changed > 0) {
        // 边只对仍存在的投影成果重建；listContributions 读的是收紧时保留下来的参与关系。
        readModel.replaceEdges(generation, achievementId, pairsOf(generation, achievementId));
      }
      restored += changed;
    }
    log.info(
        "隐私恢复已应用 revision={} 目标数={} 实际恢复={}", event.catalogRevision(), targets.size(), restored);
    return Outcome.applied("已恢复 " + restored + " 项并重建其合作边（目标 " + targets.size() + " 项）");
  }

  /** 从投影自身的参与关系生成该成果的合格作者对（与写入时同一把尺子）。 */
  private List<ScholarPair> pairsOf(String generation, String achievementId) {
    List<ProjectedContribution> contributions =
        readModel.listContributions(generation, achievementId);
    List<String> scholars = new ArrayList<>();
    for (ProjectedContribution contribution : contributions) {
      if (contribution.qualifiedForGraph() && contribution.scholarId() != null) {
        scholars.add(contribution.scholarId());
      }
    }
    scholars.sort(String::compareTo);
    List<ScholarPair> pairs = new ArrayList<>();
    for (int i = 0; i < scholars.size(); i++) {
      for (int j = i + 1; j < scholars.size(); j++) {
        if (!scholars.get(i).equals(scholars.get(j))) {
          pairs.add(new ScholarPair(scholars.get(i), scholars.get(j)));
        }
      }
    }
    return pairs;
  }

  // ---------------------------------------------------------------------------
  // 合并
  // ---------------------------------------------------------------------------

  private Outcome applyAchievementMerged(
      String generation, InboxEventRecord event, Optional<Map<String, Object>> survivorProjection) {
    String survivorId = event.stringOf("survivorId");
    String mergedId = event.stringOf("mergedId");
    if (survivorId == null || mergedId == null) {
      return Outcome.isolate("成果合并事件缺少 survivorId/mergedId");
    }
    // 被合并成果整体移除：它已不再是公开实体，保留投影会让旧链接指向错误对象（架构 §5.4）
    readModel.deleteAchievement(generation, mergedId);
    readModel.recordRedirect("ACHIEVEMENT", mergedId, survivorId, event.catalogRevision());

    if (survivorProjection.isEmpty()) {
      // core 明确回答幸存者当前不具公开资格：此时必须把它也从投影里移除，
      // 而不是留着一份过期投影继续可检索。
      readModel.deleteAchievement(generation, survivorId);
      log.info(
          "成果合并幸存者当前不公开，已从投影移除 survivorId={} revision={}", survivorId, event.catalogRevision());
      return Outcome.applied("合并完成：被合并项已移除，幸存者本体不公开故同样移除");
    }
    Optional<ProjectedAchievement> survivor =
        reader.achievementFromExport(survivorProjection.get(), event.catalogRevision());
    if (survivor.isEmpty()) {
      return Outcome.isolate("core 返回的幸存成果投影无法解析为可用记录");
    }
    List<ProjectedContribution> contributions =
        reader.contributions(survivorProjection.get().get("contributions"), survivorId);
    writeAchievement(
        generation,
        survivor.get(),
        contributions,
        reader.topics(
            survivorProjection.get().get("topicViews"), survivorProjection.get().get("topics")));
    return Outcome.applied("合并完成：幸存者投影按 core 权威结果重写，旧标识已建立重定向");
  }

  private Outcome applyScholarMerged(String generation, InboxEventRecord event) {
    String survivorId = event.stringOf("survivorId");
    String mergedId = event.stringOf("mergedId");
    String survivorName = event.stringOf("survivorName");
    if (survivorId == null || mergedId == null) {
      return Outcome.isolate("学者合并事件缺少 survivorId/mergedId");
    }
    // 先取受影响成果集合，再改挂：改挂之后被合并学者的关系已不存在，集合会算错。
    List<String> affected =
        new ArrayList<>(readModel.listAchievementIdsOfScholar(generation, mergedId));
    for (String achievementId : readModel.listAchievementIdsOfScholar(generation, survivorId)) {
      if (!affected.contains(achievementId)) {
        affected.add(achievementId);
      }
    }
    int moved =
        readModel.rewriteScholarContributions(generation, mergedId, survivorId, survivorName);
    readModel.recordRedirect("SCHOLAR", mergedId, survivorId, event.catalogRevision());
    // 关系改挂后，这些成果的合格作者集合变了，边必须整体重建——留着旧边会让合作图继续显示
    // 已不存在的合作关系。边数可能在成果数量上放大，因此只对真正受影响的成果重建。
    for (String achievementId : affected) {
      rebuildEdges(generation, achievementId);
    }
    log.info(
        "学者合并已应用 revision={} 迁移关系={} 受影响成果={}", event.catalogRevision(), moved, affected.size());
    return Outcome.applied("学者合并完成：迁移关系 " + moved + " 条，重建边 " + affected.size() + " 篇");
  }

  // ---------------------------------------------------------------------------
  // 写入与建边
  // ---------------------------------------------------------------------------

  /**
   * 写入成果及其派生集合。
   *
   * <p>顺序固定为「成果 → 参与关系 → 领域 → 合作边」，且边总是最后整体重建：边集合是成果资格与
   * 关系的函数，先写边再写关系会算出一份基于旧关系的边集。重建统一走
   * {@link #rebuildEdges}，使「什么算一条合格边」这条规则只有一个实现。
   */
  private void writeAchievement(
      String generation,
      ProjectedAchievement projected,
      List<ProjectedContribution> contributions,
      List<ProjectedTopic> topics) {
    readModel.upsertAchievement(generation, projected);
    // 派生集合只为「当前公开可见」的成果保留。撤销发布或收紧为受限之后，若仍按事件里带来的
    // 参与关系与领域照写一遍，投影里就会留下一条已下架成果的作者行与领域行：公开读看不见它
    // （查询都带状态过滤），但统计口径与「重建结果 vs 增量结果」会从此不相等——全量重建只导入
    // 公开快照，会把这些行清掉，而这种差异只在切换代的那一刻才暴露。
    boolean visible = projected.publiclyVisible();
    readModel.replaceContributions(
        generation, projected.achievementId(), visible ? contributions : List.of());
    readModel.replaceTopics(generation, projected.achievementId(), visible ? topics : List.of());
    rebuildEdges(generation, projected.achievementId());
  }

  /** 按统一规则重建某成果的合作边：仅真实、公开、生效、关系合格的共同论文建边（架构 §9.3）。 */
  void rebuildEdges(String generation, String achievementId) {
    Optional<ProjectedAchievement> achievement =
        readModel.findAchievement(generation, achievementId);
    if (achievement.isEmpty() || !achievement.get().eligibleForRealityView()) {
      readModel.deleteEdges(generation, achievementId);
      return;
    }
    List<ProjectedContribution> contributions =
        readModel.listContributions(generation, achievementId);
    readModel.replaceEdges(generation, achievementId, reader.pairs(contributions));
  }

  /**
   * 把 core 导出的公开投影写入指定 generation（重建用）。
   *
   * <p>与事件路径共用同一套翻译与写入逻辑，只是数据来源不同：导出项与事件载荷的字段名被刻意
   * 设计成一致（§7.3 要求导出与事件回放产出同一份投影），因此这里能直接复用。若两条路径各自
   * 实现一遍，重建后与增量更新的投影必然出现字段级差异，而这类差异只在切换 generation 时才暴露。
   *
   * <p>导出项不可用时**中止重建**而不是跳过或兜底。跳过会少数据，兜底会改数据，只有中止是安全的：
   * 中止时活动代保持为原代，对外内容一字不变，失败代的残留行由清理入口回收。
   *
   * @return 写入成功返回 null；否则返回不可导入的具体原因，由调用方据此放弃本次重建
   */
  String importProjection(String generation, Map<String, Object> exportItem, long revision) {
    Optional<ProjectedAchievement> achievement = reader.achievementFromExport(exportItem, revision);
    if (achievement.isEmpty()) {
      return "必填字段不全（id / type / title / dataNature / visibility / effectiveStatus 均须存在）";
    }
    ProjectedAchievement projected = achievement.get();
    List<ProjectedContribution> contributions =
        reader.contributions(exportItem.get("contributions"), projected.achievementId());
    writeAchievement(
        generation,
        projected,
        contributions,
        reader.topics(exportItem.get("topicViews"), exportItem.get("topics")));
    return null;
  }

  private static List<String> stringList(Object first, Object second) {
    List<String> result = new ArrayList<>();
    collect(first, result);
    collect(second, result);
    return result;
  }

  private static void collect(Object value, List<String> target) {
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof String text && !text.isBlank()) {
          target.add(text);
        }
      }
    }
  }
}
