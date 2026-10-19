package com.cognitanexus.core.catalog.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.CitationRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.CitationRepository.CitedKey;
import com.cognitanexus.core.catalog.application.CatalogRepositories.CitationRepository.CitedWork;
import com.cognitanexus.core.catalog.application.CatalogRepositories.CitationRepository.Observation;
import com.cognitanexus.core.catalog.application.CatalogRepositories.CitationRepository.PendingWork;
import com.cognitanexus.core.catalog.application.CatalogRepositories.CitationRepository.Reference;
import com.cognitanexus.core.catalog.application.CatalogRepositories.CitationRepository.ResolutionCounts;
import com.cognitanexus.core.catalog.application.CatalogRepositories.IdentifierRegistryRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.IdentifierKind;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.core.platform.util.TextNormalizer;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 引用网络：参考文献观测入库、引用边解析、以及「本篇引用了谁 / 谁引用了本篇」的读取。
 *
 * <p>依据：架构 V0.2 §5.1、SRS FR06、BR-IMP-04。待办清单第 1 条「没有引用网络，只有合作网络」
 * 指的就是这里：在此之前平台只有「学者—学者」的合作边，没有「成果—成果」的引用边。
 *
 * <p>设计要点与它们各自防的事故：
 *
 * <ol>
 *   <li><b>观测与边分开存</b>。「这篇的参考文献采过没有」与「这篇引用了哪些文献」是两件事。
 *       只存边的话，「没有引用」与「没采过」都表现为零条边，页面只能二选一猜一种说法。
 *   <li><b>边只有两种状态</b>：RESOLVED（指向平台内一条成果）与 EXTERNAL（指向平台外文献，
 *       只计数）。不做「疑似匹配」这种中间态——引用是硬事实，模糊匹配出来的边比缺边更糟。
 *   <li><b>重采整体替换</b>。引用列表是快照，重采时先删本篇旧条目再插新条目，避免「上一次采到
 *       的、这一次来源已不再列出的引用」永远留在库里。
 *   <li><b>缺失不是 0</b>。没采过就没有 coverage（而不是 coverage 里写 0 篇引用）；来源没声明
 *       总数时 {@code referencesTotal} 为 null 而不是等于实际列表长度。
 *   <li><b>站内一律按公开资格过滤</b>，且计数与明细用同一条件，限制级成果既不出现在列表里，
 *       也不会让「共 N 篇」比列表多出几条。
 * </ol>
 */
@Service
public class CitationNetworkService {

  private static final Logger log = LoggerFactory.getLogger(CitationNetworkService.class);

  /** 单篇参考文献条数上限：来源偶发返回上万条（超长综述），不设上限会让单次入库变成事故。 */
  private static final int MAX_REFERENCES_PER_WORK = 5000;

  /** 单次观测投递条数上限：一次请求塞太多会让事务过长，也让失败重试代价过高。 */
  private static final int MAX_OBSERVATIONS_PER_CALL = 200;

  /** 单次重新解析的标识键上限。 */
  private static final int MAX_RESOLVE_KEYS = 5000;

  private static final String TARGET_ACHIEVEMENT = "ACHIEVEMENT";

  private final CitationRepository citations;
  private final IdentifierRegistryRepository identifiers;
  private final PublicCatalogQueryService publicCatalog;
  private final AuditRecorder audit;

  public CitationNetworkService(
      CitationRepository citations,
      IdentifierRegistryRepository identifiers,
      PublicCatalogQueryService publicCatalog,
      AuditRecorder audit) {
    this.citations = citations;
    this.identifiers = identifiers;
    this.publicCatalog = publicCatalog;
    this.audit = audit;
  }

  // ---------------------------------------------------------------------------
  // 写入：观测入库
  // ---------------------------------------------------------------------------

  /**
   * 记录一批参考文献观测。
   *
   * <p>整批同一事务：任一条写入失败则整批回滚，采集侧按同一批 DOI 重投是幂等的（每篇先删旧条目
   * 再插新条目），因此「回滚后重投」不会留下半篇引用。DOI 在库里查不到不是失败——来源收录了本
   * 平台没有的工作是常态，这类条目不写入、只在回执里点名。
   *
   * @return 回执：命中/未命中/写入条数、解析结果，以及未被采纳的 DOI 样本
   */
  @Transactional
  public ObservationReceipt recordObservations(
      List<CitationObservationInput> inputs, ActorContext actor) {
    if (inputs == null || inputs.isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "观测列表为空");
    }
    if (inputs.size() > MAX_OBSERVATIONS_PER_CALL) {
      throw new BusinessException(
          ErrorCode.INVALID_ARGUMENT, "单次观测条数上限 " + MAX_OBSERVATIONS_PER_CALL);
    }

    int matched = 0;
    int referencesWritten = 0;
    int resolved = 0;
    int external = 0;
    int selfCitations = 0;
    int notFoundWorks = 0;
    List<String> unmatchedDois = new ArrayList<>();
    List<String> conflicts = new ArrayList<>();

    for (CitationObservationInput input : inputs) {
      String doiNorm = normalizeDoi(input.doi());
      if (doiNorm == null) {
        unmatchedDois.add(String.valueOf(input.doi()));
        continue;
      }
      Optional<String> achievementId =
          identifiers.findEntityId(TARGET_ACHIEVEMENT, IdentifierKind.DOI, "", doiNorm);
      if (achievementId.isEmpty()) {
        // 来源收录了本平台没有的工作：不是错误，也不能凭标题猜一条成果出来
        if (unmatchedDois.size() < 10) {
          unmatchedDois.add(input.doi());
        }
        continue;
      }
      String citingId = achievementId.get();
      matched++;

      String workIdNorm = normalizeOpenAlexId(input.providerWorkId());
      if (workIdNorm != null) {
        String conflict = registerWorkId(citingId, workIdNorm);
        if (conflict != null && conflicts.size() < 10) {
          conflicts.add(conflict);
        }
      }

      Instant fetchedAt = parseInstant(input.fetchedAt());
      // 按「归一后的标识」去重而不是按原文：同一条文献常以 W 开头和带 URL 的两种写法同时出现，
      // 按原文去重会统计出两倍的引用数（数据库唯一索引会挡住第二条，但计数已经错了）。
      List<ReferenceKey> referenceIds = normalizeReferences(input.references());
      // 来源按这个 DOI 什么都查不到：记 NOT_FOUND 而不是「采到 0 篇」。两者对读者的含义完全不同，
      // 而且 NOT_FOUND 会让补采清单不再反复命中同一批永远取不到的标识。
      boolean notFound =
          workIdNorm == null
              && referenceIds.isEmpty()
              && input.referencesTotal() == null
              && (input.references() == null || input.references().isEmpty());
      String providerName =
          input.provider() == null || input.provider().isBlank()
              ? "UNKNOWN"
              : input.provider().trim();
      citations.deleteReferencesOf(citingId);
      // 上报方若给了「来源实际返回了几条」，就用它；否则按带标识的条数计。
      // 两者不同是常态（Crossref 的部分条目只有作者与题名，没有 DOI），差额必须能被看见。
      int listedCount =
          input.referencesListed() == null ? referenceIds.size() : input.referencesListed();
      citations.saveObservation(
          new Observation(
              citingId,
              providerName,
              workIdNorm,
              input.referencesTotal(),
              listedCount,
              notFound ? "NOT_FOUND" : "OBSERVED",
              fetchedAt));
      if (notFound) {
        notFoundWorks++;
        continue;
      }

      for (ReferenceKey reference : referenceIds) {
        String kind = reference.kind();
        String valueNorm = reference.value();
        Optional<String> target =
            identifiers.findEntityId(
                TARGET_ACHIEVEMENT,
                "DOI".equals(kind) ? IdentifierKind.DOI : IdentifierKind.OPENALEX_ID,
                "",
                valueNorm);
        String citedId = target.orElse(null);
        String resolution = citedId == null ? "EXTERNAL" : "RESOLVED";
        citations.insertReference(
            new Reference(
                Identifiers.next("cre"),
                citingId,
                kind,
                valueNorm,
                citedId,
                resolution,
                fetchedAt));
        referencesWritten++;
        if ("RESOLVED".equals(resolution)) {
          if (citingId.equals(citedId)) {
            selfCitations++;
          } else {
            resolved++;
          }
        } else {
          external++;
        }
      }
    }

    Map<String, Object> after = new LinkedHashMap<>();
    after.put("received", inputs.size());
    after.put("matched", matched);
    after.put("referencesWritten", referencesWritten);
    after.put("resolved", resolved);
    after.put("external", external);
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "CITATION_OBSERVATIONS_RECORDED",
            "SOURCE",
            "citation-network",
            null,
            after,
            "参考文献观测入库"));

    log.info(
        "引用观测入库 收到={} 命中={} 条目={} 站内={} 站外={}",
        inputs.size(),
        matched,
        referencesWritten,
        resolved,
        external);
    return new ObservationReceipt(
        inputs.size(),
        matched,
        referencesWritten,
        resolved,
        external,
        selfCitations,
        notFoundWorks,
        List.copyOf(unmatchedDois),
        List.copyOf(conflicts));
  }

  /**
   * 登记「这条成果在来源里的作品 ID」。
   *
   * <p>它是把参考文献列表（只给作品 ID）连到本平台成果的唯一桥：没有它，所有引用都只能是
   * EXTERNAL。注册失败（同一作品 ID 已被别的成果占用）时返回一句可读说明，不抛异常——
   * 标识冲突属于治理问题，不该让整批观测失败。
   */
  private String registerWorkId(String achievementId, String workIdNorm) {
    Optional<String> existing =
        identifiers.findEntityId(TARGET_ACHIEVEMENT, IdentifierKind.OPENALEX_ID, "", workIdNorm);
    if (existing.isPresent()) {
      return existing.get().equals(achievementId)
          ? null
          : "作品 ID " + workIdNorm + " 已登记在另一条成果上，本条未采纳";
    }
    boolean registered =
        identifiers.register(
            Identifiers.next("idr"),
            TARGET_ACHIEVEMENT,
            achievementId,
            IdentifierKind.OPENALEX_ID,
            "",
            workIdNorm);
    return registered ? null : "作品 ID " + workIdNorm + " 登记冲突，本条未采纳";
  }

  // ---------------------------------------------------------------------------
  // 写入：重新解析外部引用
  // ---------------------------------------------------------------------------

  /**
   * 把「当时解析不到」的外部引用重新按标识查一遍。
   *
   * <p>为什么需要单独一步：引用与被引两端的采集是先后发生的。今天采到 A 的参考文献时 B 还没进库，
   * 那条引用只能记为 EXTERNAL；明天 B 进库并登记了作品 ID，这条边就该变成 RESOLVED。重采 A
   * 需要外网配额，而重新解析只需要一张注册表。
   *
   * <p>只动 EXTERNAL 行，已解析的行不受影响——否则一次误跑会把已经确认的边改坏。
   */
  @Transactional
  public ResolveResult resolvePending(int limit, ActorContext actor) {
    int cap = Math.min(Math.max(limit, 1), MAX_RESOLVE_KEYS);
    List<CitedKey> keys = citations.listUnresolvedKeys(cap);
    int upgradedKeys = 0;
    int upgradedRows = 0;
    for (CitedKey key : keys) {
      IdentifierKind kind;
      try {
        kind = IdentifierKind.valueOf(key.kind());
      } catch (IllegalArgumentException ex) {
        continue;
      }
      Optional<String> target =
          identifiers.findEntityId(TARGET_ACHIEVEMENT, kind, "", key.valueNorm());
      if (target.isEmpty()) {
        continue;
      }
      int rows = citations.resolveByKey(key.kind(), key.valueNorm(), target.get(), Instant.now());
      if (rows > 0) {
        upgradedKeys++;
        upgradedRows += rows;
      }
    }
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("keysScanned", keys.size());
    after.put("keysResolved", upgradedKeys);
    after.put("rowsResolved", upgradedRows);
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "CITATION_REFERENCES_RESOLVED",
            "SOURCE",
            "citation-network",
            null,
            after,
            "重新解析外部引用"));
    log.info("引用重新解析 扫描键={} 升级键={} 升级条目={}", keys.size(), upgradedKeys, upgradedRows);
    return new ResolveResult(keys.size(), upgradedKeys, upgradedRows);
  }

  // ---------------------------------------------------------------------------
  // 读取
  // ---------------------------------------------------------------------------

  /**
   * 公开读取某条成果的引用网络。
   *
   * <p>调用方给的可能是被合并的旧标识，公开资格必须作用在合并后的终点实体上——与详情、引用导出
   * 走同一个判定（{@code requirePublicTarget}），否则这里会成为一条读取不可见成果的关系旁路。
   */
  @Transactional(readOnly = true)
  public Map<String, Object> publicNetwork(String achievementId) {
    PublicCatalogQueryService.PublicTarget target =
        publicCatalog.requirePublicTarget(achievementId);
    Achievement achievement = target.achievement();
    Optional<Observation> stored = citations.findObservation(achievement.id());
    // NOT_FOUND 也算「有观测行」，但它不是「采到了」：引用条目一条都没有，页面上必须说缺口
    boolean notFound = stored.map(row -> "NOT_FOUND".equals(row.outcome())).orElse(false);
    Optional<Observation> observation = notFound ? Optional.empty() : stored;
    ResolutionCounts counts =
        observation.isPresent()
            ? citations.countReferencesOf(achievement.id())
            : new ResolutionCounts(0, 0, 0);
    List<CitedWork> references =
        observation.isPresent() ? citations.listResolvedReferences(achievement.id()) : List.of();
    List<CitedWork> citedBy = citations.listCitedBy(achievement.id());

    int observedWorks = citations.countObservedWorks();
    int notFoundWorks = citations.countNotFoundWorks();
    int publicPapers = citations.countPublicPapers();

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("achievementId", achievement.id());
    if (target.redirectedFrom() != null) {
      view.put("redirectedFrom", target.redirectedFrom());
      view.put("redirectNote", "该成果已被合并，本响应指向合并后的实体");
    }

    Map<String, Object> coverage = new LinkedHashMap<>();
    coverage.put("observed", observation.isPresent());
    coverage.put("outcome", notFound ? "NOT_FOUND" : observation.isPresent() ? "OBSERVED" : "NONE");
    if (notFound) {
      coverage.put("provider", stored.map(Observation::provider).orElse(null));
      coverage.put("fetchedAt", stored.map(Observation::fetchedAt).orElse(null));
      coverage.put("listingNote", "来源按该成果的标识查不到这一篇，因此无法取得参考文献；这是采集缺口，不是「没有引用」");
    }
    if (observation.isPresent()) {
      Observation row = observation.get();
      coverage.put("provider", row.provider());
      coverage.put("providerWorkId", row.providerWorkId());
      coverage.put("fetchedAt", row.fetchedAt());
      coverage.put("referencesTotal", row.referencesTotal());
      coverage.put("referencesListed", row.referencesListed());
      coverage.put("resolvedInPlatform", counts.resolved());
      coverage.put("external", counts.external());
      coverage.put("selfCitations", counts.selfReferences());
      // 拿到的条目里有一部分没有任何可识别标识（例如只有作者与题名的参考文献），无法建边。
      // 单列成 unidentified，读者才能对上「拿到 185 条、边 170 条」这件事，而不是以为少了 15 条。
      int identified = counts.resolved() + counts.external() + counts.selfReferences();
      coverage.put("unidentified", Math.max(row.referencesListed() - identified, 0));
      // 来源声明的总数与实际拿到的条数不一致时如实披露差额，不假装列表是完整的
      Integer gap =
          row.referencesTotal() == null
              ? null
              : Math.max(row.referencesTotal() - row.referencesListed(), 0);
      coverage.put("listingGap", gap);
      coverage.put(
          "listingNote",
          gap == null
              ? "来源未声明参考文献总数，只按实际拿到的条目统计"
              : gap == 0
                  ? "来源声明的总数与实际拿到的条目一致"
                  : "来源声明共 "
                      + row.referencesTotal()
                      + " 篇，实际拿到 "
                      + row.referencesListed()
                      + " 篇，差额 "
                      + gap
                      + " 篇未纳入统计");
    }

    Map<String, Object> referencesBlock = new LinkedHashMap<>();
    referencesBlock.put("count", references.size());
    referencesBlock.put(
        "items", references.stream().map(CitationNetworkService::citedWorkView).toList());
    referencesBlock.put(
        "note",
        observation.isPresent()
            ? "本篇引用的文献中，有 "
                + counts.resolved()
                + " 篇收录于本平台；其余 "
                + counts.external()
                + " 篇为平台外文献，只计数量、不显示为关系"
            : notFound
                ? "来源未收录该成果，取不到它的参考文献列表；此处为空表示取不到，不表示本篇没有引用任何文献"
                : "尚未采集本篇的参考文献列表：此处为空表示未采集，不表示本篇没有引用任何文献");

    Map<String, Object> citedByBlock = new LinkedHashMap<>();
    citedByBlock.put("count", citedBy.size());
    citedByBlock.put("items", citedBy.stream().map(CitationNetworkService::citedWorkView).toList());
    citedByBlock.put(
        "note",
        "被引数只统计本平台已采集参考文献的成果（当前 "
            + observedWorks
            + " 篇）。来源报告的累计引用量"
            + "包含全世界所有引用者，与本处的站内被引不是同一口径，两者不可相加、也不可互相校验");

    Map<String, Object> platform = new LinkedHashMap<>();
    platform.put("observedWorks", observedWorks);
    platform.put("notFoundWorks", notFoundWorks);
    platform.put("publicPapers", publicPapers);
    platform.put(
        "coveragePercent",
        publicPapers == 0
            ? null
            : BigDecimal.valueOf(observedWorks * 100L)
                .divide(BigDecimal.valueOf(publicPapers), 2, RoundingMode.HALF_UP)
                .doubleValue());
    platform.put(
        "note",
        "引用网络只覆盖已采集参考文献的成果。未采集的成果既不显示它引用了谁，也不会出现在别人的被引列表里" + "——缺边是采集覆盖率的问题，不是「这些成果之间没有引用」");

    view.put("coverage", coverage);
    view.put("references", referencesBlock);
    view.put("citedBy", citedByBlock);
    view.put("platform", platform);
    view.put("reportedCitationCountNote", "来源报告的累计引用量见成果详情的引用量字段；它是数字事实，不是本页的边数");
    return view;
  }

  /** 管理端/内部用：当前覆盖率与待补规模。 */
  @Transactional(readOnly = true)
  public Map<String, Object> stats(String search, String topicId) {
    int observed = citations.countObservedWorks();
    int notFound = citations.countNotFoundWorks();
    int publicPapers = citations.countPublicPapers();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("observedWorks", observed);
    view.put("publicPapers", publicPapers);
    view.put(
        "coveragePercent",
        publicPapers == 0
            ? null
            : BigDecimal.valueOf(observed * 100L)
                .divide(BigDecimal.valueOf(publicPapers), 2, RoundingMode.HALF_UP)
                .doubleValue());
    view.put("notFoundWorks", notFound);
    view.put("pendingWorks", citations.countPendingWorks(search, topicId));
    view.put("note", "每篇成果的参考文献需要向外网来源单独取一次；采集端按来源配额分批补，未补到的成果不计入引用网络");
    return view;
  }

  /**
   * 待补参考文献的成果清单（供采集侧按 DOI 批量取）。
   *
   * <p>只给可见论文、且带 DOI 的：没有 DOI 的成果无法向来源精确取引用列表，凭题名搜出来的
   * 「可能是这篇」不能用来建边。
   */
  @Transactional(readOnly = true)
  public PendingPage pendingWorks(int limit, String search, String topicId) {
    int cap = Math.min(Math.max(limit, 1), 200);
    List<PendingWork> rows =
        citations.listPendingWorks(cap, blankToNull(search), blankToNull(topicId));
    List<Map<String, Object>> items = new ArrayList<>(rows.size());
    for (PendingWork row : rows) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("achievementId", row.achievementId());
      item.put("doi", row.doi());
      item.put("citationCount", row.citationCount());
      items.add(item);
    }
    return new PendingPage(
        items, citations.countPendingWorks(blankToNull(search), blankToNull(topicId)));
  }

  private static Map<String, Object> citedWorkView(CitedWork work) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", work.achievementId());
    item.put("title", work.title());
    item.put("type", work.type() == null ? null : work.type().name());
    item.put("typeLabel", work.type() == null ? "类型未知" : work.type().label());
    item.put("publicationYear", work.publicationYear());
    item.put("yearKnown", work.yearKnown());
    item.put("venue", work.venue());
    item.put("doi", work.doi());
    return item;
  }

  // ---------------------------------------------------------------------------
  // 归一与校验
  // ---------------------------------------------------------------------------

  /**
   * DOI 归一，必须与导入侧一致。
   *
   * <p>导入侧用的是 {@code TextNormalizer.normalizeStableIdentifier} 再去前缀；这里直接复用
   * 同一个函数，不另写一套。两处不一时，同一篇论文的两种写法会各自成为一条引用，边数凭空翻倍。
   */
  static String normalizeDoi(String raw) {
    return TextNormalizer.normalizeStableIdentifier(raw);
  }

  /**
   * 判断一个被引标识是作品 ID 还是 DOI。
   *
   * <p>不做「包含 10. 就是 DOI」这类猜测：OpenAlex 的作品 ID 形如 {@code W2741809807}，
   * DOI 形如 {@code 10.1000/xyz}，两者形状互不重叠。形状不清的一律按 DOI 处理——DOI 查不到
   * 只会让这一条落成 EXTERNAL（少一条边），而把 DOI 误当作品 ID 会去注册表里查一个不存在的键，
   * 结果一样但更难排查。
   */
  static String classifyReference(String raw) {
    String value = TextNormalizer.normalizeStableIdentifier(raw);
    if (value == null) {
      return "DOI";
    }
    int slash = value.lastIndexOf('/');
    String tail = slash >= 0 ? value.substring(slash + 1) : value;
    boolean workId =
        tail.length() > 1
            && tail.charAt(0) == 'w'
            && tail.substring(1).chars().allMatch(Character::isDigit);
    return workId ? "OPENALEX_ID" : "DOI";
  }

  /**
   * OpenAlex 作品 ID 归一：去 URL 前缀，保留小写形态。
   *
   * <p>小写化来自 {@code normalizeStableIdentifier}；注册与解析都走这里，因此库里存的是
   * {@code w2886498337} 这种形态。改这条规则必须同时改已登记值，否则历史边全部失效。
   */
  static String normalizeOpenAlexId(String raw) {
    String value = TextNormalizer.normalizeStableIdentifier(raw);
    if (value == null) {
      return null;
    }
    for (String prefix :
        List.of(
            "https://openalex.org/",
            "http://openalex.org/",
            "https://api.openalex.org/works/",
            "https://api.openalex.org/authors/",
            "openalex.org/")) {
      if (value.startsWith(prefix)) {
        String rest = value.substring(prefix.length());
        return rest.isEmpty() ? null : rest;
      }
    }
    return value;
  }

  /**
   * 把来源给的原始引用标识归一成 (种类, 规范值) 并去重。
   *
   * <p>去重键是「种类 + 规范值」而不是原文：同一篇文献可能同时以 {@code W123} 与
   * {@code https://openalex.org/W123} 出现，按原文去重会让引用数翻倍。数据库唯一索引拦得住重复行，
   * 但拦不住一个已经算错的数字。
   */
  static List<ReferenceKey> normalizeReferences(List<String> values) {
    java.util.Map<String, ReferenceKey> unique = new LinkedHashMap<>();
    if (values == null) {
      return List.of();
    }
    for (String raw : values) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      String kind = classifyReference(raw);
      String value = "DOI".equals(kind) ? normalizeDoi(raw) : normalizeOpenAlexId(raw);
      if (value == null) {
        continue;
      }
      unique.putIfAbsent(kind + "|" + value, new ReferenceKey(kind, value));
      if (unique.size() >= MAX_REFERENCES_PER_WORK) {
        log.warn("参考文献条数超过上限 {}，其余未纳入", MAX_REFERENCES_PER_WORK);
        break;
      }
    }
    return new ArrayList<>(unique.values());
  }

  /** 一条被引标识：种类 + 规范值。 */
  record ReferenceKey(String kind, String value) {}

  private static Instant parseInstant(String raw) {
    if (raw == null || raw.isBlank()) {
      return Instant.now();
    }
    try {
      return Instant.parse(raw.trim());
    } catch (DateTimeParseException ex) {
      // 时刻不合法时用当前时刻：观测确实是刚发生的，这比丢弃整条观测更接近事实
      return Instant.now();
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  // ---------------------------------------------------------------------------
  // 输入输出
  // ---------------------------------------------------------------------------

  /** 采集侧提交的一条观测：某篇成果的参考文献列表。 */
  public record CitationObservationInput(
      String doi,
      String provider,
      String providerWorkId,
      Integer referencesTotal,
      List<String> references,
      Integer referencesListed,
      String fetchedAt) {}

  /** 一批观测。两个入口（内部服务令牌、管理端镜像）共用同一个请求体形状。 */
  public record ObservationBatch(List<CitationObservationInput> observations) {}

  /** 观测入库回执。 */
  public record ObservationReceipt(
      int received,
      int matched,
      int referencesWritten,
      int resolved,
      int external,
      int selfCitations,
      int notFoundWorks,
      List<String> unmatchedDois,
      List<String> identifierConflicts) {}

  /** 重新解析结果。 */
  public record ResolveResult(int keysScanned, int keysResolved, int rowsResolved) {}

  /** 待补清单。 */
  public record PendingPage(List<Map<String, Object>> items, int remaining) {}
}
