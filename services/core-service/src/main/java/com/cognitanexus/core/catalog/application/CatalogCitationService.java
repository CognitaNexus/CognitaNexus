package com.cognitanexus.core.catalog.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ContributionRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ScholarRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.AchievementDetails;
import com.cognitanexus.core.catalog.domain.CitationAuthor;
import com.cognitanexus.core.catalog.domain.CitationCollectionFormatter;
import com.cognitanexus.core.catalog.domain.CitationFormat;
import com.cognitanexus.core.catalog.domain.CitationFormatter;
import com.cognitanexus.core.catalog.domain.CitationSubject;
import com.cognitanexus.core.catalog.domain.CitationWarning;
import com.cognitanexus.core.catalog.domain.Contribution;
import com.cognitanexus.core.integrity.application.IntegrityNoticeRepository;
import com.cognitanexus.core.integrity.domain.IntegritySummary;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.RelationState;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成果引用导出（FR02）。
 *
 * <p>为什么这件事值得单独做而不是让读者自己拼：学术成果被引用的方式决定了它能不能被找回去。
 * 平台页面上的一条标题，贴进论文时缺作者、缺年份、缺 DOI 都会让人找不到原文；而「引用格式」
 * 是有公开规范的东西，平台既然握着元数据，就应当按规范渲染，而不是把这件事留给每位读者自己判断。
 *
 * <p>三条边界，都是刻意为之：
 *
 * <ol>
 *   <li><b>只读</b>。本服务不改任何成果字段，也没有任何写通路。
 *   <li><b>沿用公开读的资格判定</b>。可见性与生效状态、合并重定向全部走
 *       {@link PublicCatalogQueryService#requirePublicTarget}，不另写一份——另写一份就是给受限内容
 *       留第二条读出口。
 *   <li><b>撤稿不下架这条规则在这里同样成立</b>：撤稿的成果仍然给出引用文本（它确实发表过，引用它
 *       的人需要能查到它），但文本与告警都必须带上撤稿事实。
 * </ol>
 */
@Service
public class CatalogCitationService {

  /** 前端默认选中的格式。中文语境下国标最常用。 */
  private static final CitationFormat DEFAULT_FORMAT = CitationFormat.GB_T_7714;

  /** 出处说明。写在响应字段里，不写进引用正文（理由见 {@link CitationBundle}）。 */
  private static final String PROVENANCE = "CognitaNexus 学术成果分享平台";

  /**
   * 单次批量导出的上限。
   *
   * <p>100 条不是性能数字，而是「一份参考文献库文件」的合理规模：超过它更可能是想把整个检索结果
   * 倒成一份文件，而那件事应当走 CSV 导出 + 自行清洗，而不是让平台替人拼一份几百条的 .bib。
   * 上限写死并显式拒绝，而不是截断——截断会让人以为导出成功。
   */
  public static final int MAX_BATCH_ITEMS = 100;

  private final PublicCatalogQueryService catalog;
  private final AchievementRepository achievements;
  private final ScholarRepository scholars;
  private final ContributionRepository contributions;
  private final IntegrityNoticeRepository integrityNotices;

  public CatalogCitationService(
      PublicCatalogQueryService catalog,
      AchievementRepository achievements,
      ScholarRepository scholars,
      ContributionRepository contributions,
      IntegrityNoticeRepository integrityNotices) {
    this.catalog = catalog;
    this.achievements = achievements;
    this.scholars = scholars;
    this.contributions = contributions;
    this.integrityNotices = integrityNotices;
  }

  /** 四种格式一次给全。 */
  @Transactional(readOnly = true)
  public CitationBundle citations(String achievementId) {
    List<CitationWarning> warnings = new ArrayList<>();
    CitationSubject subject = subjectOf(achievementId, warnings);
    String citeKey = CitationFormatter.citeKey(subject);

    List<CitationBundle.FormatEntry> formats = new ArrayList<>();
    for (CitationFormat format : CitationFormat.values()) {
      formats.add(
          new CitationBundle.FormatEntry(
              format,
              CitationFormatter.format(subject, format),
              citeKey + "." + format.fileExtension(),
              format.mediaType(),
              format.machineReadable(),
              CitationFormatter.formatWarnings(subject, format)));
    }
    return new CitationBundle(
        subject.achievementId(),
        citeKey,
        DEFAULT_FORMAT,
        PROVENANCE,
        LocalDate.now().toString(),
        warnings,
        formats);
  }

  /**
   * 单一格式，供下载与命令行使用。
   *
   * @throws BusinessException 400（{@code INVALID_ARGUMENT}）当格式无法识别；404 当成果不可公开
   */
  @Transactional(readOnly = true)
  public CitationText citation(String achievementId, String rawFormat) {
    CitationFormat format = parseFormat(rawFormat);
    List<CitationWarning> warnings = new ArrayList<>();
    CitationSubject subject = subjectOf(achievementId, warnings);
    return new CitationText(
        format,
        CitationFormatter.citeKey(subject),
        CitationFormatter.format(subject, format),
        List.copyOf(warnings));
  }

  /**
   * 批量导出：把一组成果的引用装配成一份文件。
   *
   * <p>依据：FR02、ADR-004 §5 第 4 条（原先登记为未做）。四条边界：
   *
   * <ol>
   *   <li><b>逐条沿用单条的资格判定</b>。每一条都走 {@link #subjectOf}，也就是都过
   *       {@code requirePublicTarget}，因此批量入口不会成为绕过可见性/生效状态/合并重定向的第二条
   *       读出口。
   *   <li><b>单条不可见不拖垮整批</b>。选中列表来自检索结果，从看到结果到点导出之间，其中一条可能
   *       刚好被下架或并入别处。这时整批 500 会让读者丢掉其余 19 条；悄悄少给一条又等于平台在
   *       无声地改动读者的选择。所以跳过并报数，且<b>原因只写一个码</b>——区分「不存在」与「不可公开」
   *       就是一条探测受限成果的通道。
   *   <li><b>同一文件内引用键唯一</b>，撞键由 {@link CitationCollectionFormatter#uniqueCiteKeys}
   *       加后缀并回告警。BibTeX 撞键是静默覆盖，不处理就等于交出一个少条目的文件。
   *   <li><b>一条都导不出来时明确失败</b>：给一份空文件比给一句「都不可导出」更容易被误当成成功。
   * </ol>
   *
   * @throws BusinessException 400 格式非法或未选任何成果；422 超过单次上限；404 全部不可导出
   */
  @Transactional(readOnly = true)
  public CitationBatch exportBatch(List<String> rawIds, String rawFormat) {
    CitationFormat format = parseFormat(rawFormat);
    List<String> ids = normalizeIds(rawIds);
    if (ids.isEmpty()) {
      throw BusinessException.invalidArgument("请至少选择一条成果再导出引用");
    }
    if (ids.size() > MAX_BATCH_ITEMS) {
      throw BusinessException.businessRule(
          "一次最多导出 "
              + MAX_BATCH_ITEMS
              + " 条成果的引用，本次选中 "
              + ids.size()
              + " 条。请减少选中项后分批导出（文献管理软件可以逐个导入多个文件）");
    }

    // 先全部解析，再按「解析后的实体」分组。合并过的成果会被重定向到终点实体，读者若同时勾了原记录
    // 与幸存记录，两条指向的是同一篇文献：不去重就会写出两份一样的引用；只按出现顺序去重又会把幸存
    // 记录记成「重复项」，读起来像「那条活着的被跳过了」。因此分组后优先取未经重定向的 id 作为条目，
    // 被合并的那条记为 DUPLICATE_TARGET。
    Map<String, List<Resolved>> byEntity = new LinkedHashMap<>();
    List<CitationBatch.Skipped> skipped = new ArrayList<>();
    for (String id : ids) {
      List<CitationWarning> warnings = new ArrayList<>();
      try {
        PublicCatalogQueryService.PublicTarget target = catalog.requirePublicTarget(id);
        CitationSubject subject = subjectOf(target.achievement(), warnings);
        byEntity
            .computeIfAbsent(subject.achievementId(), key -> new ArrayList<>())
            .add(new Resolved(id, subject, List.copyOf(warnings), target.redirectedFrom() != null));
      } catch (BusinessException ex) {
        // 单条不可见不该让整批失败：选中列表常常来自检索结果，而检索结果与点击之间可能刚好有
        // 一条被下架。但原因统一写 NOT_AVAILABLE，且不区分「不存在」与「不可公开」。
        skipped.add(
            new CitationBatch.Skipped(
                id, CitationBatch.REASON_NOT_AVAILABLE, "该成果当前不可公开导出（已下架、未生效、被合并到别处，或不存在），已跳过"));
      }
    }

    List<CitationSubject> subjects = new ArrayList<>();
    List<List<CitationWarning>> subjectWarnings = new ArrayList<>();
    for (List<Resolved> group : byEntity.values()) {
      Resolved chosen = group.get(0);
      for (Resolved candidate : group) {
        if (!candidate.redirected()) {
          chosen = candidate;
          break;
        }
      }
      subjects.add(chosen.subject());
      subjectWarnings.add(chosen.warnings());
      for (Resolved candidate : group) {
        if (candidate == chosen) {
          continue;
        }
        skipped.add(
            new CitationBatch.Skipped(
                candidate.requestedId(),
                CitationBatch.REASON_DUPLICATE_TARGET,
                "该成果与本次已选中的另一条指向同一条公开记录（合并重定向），未重复写入"));
      }
    }
    if (subjects.isEmpty()) {
      throw BusinessException.notFound("选中的 " + ids.size() + " 条成果都不可公开导出，没有可生成的内容");
    }

    List<String> keys = CitationCollectionFormatter.uniqueCiteKeys(subjects);
    List<CitationBatch.Entry> entries = new ArrayList<>();
    List<String> texts = new ArrayList<>();
    int renamedKeys = 0;
    for (int i = 0; i < subjects.size(); i++) {
      CitationSubject subject = subjects.get(i);
      String key = keys.get(i);
      if (!key.equals(CitationFormatter.citeKey(subject))) {
        renamedKeys++;
      }
      List<CitationWarning> entryWarnings = new ArrayList<>(subjectWarnings.get(i));
      // 格式自身的局限（目前只有 APA 的姓名不拆分）在这一条上成立，随条目一起给出；
      // 元数据局限也在这里一并交付，前端按 code 合并即可，不需要再猜哪些属于整份文件。
      entryWarnings.addAll(CitationFormatter.formatWarnings(subject, format));
      String entryText = CitationFormatter.format(subject, format, key);
      entries.add(new CitationBatch.Entry(subject.achievementId(), key, entryText, entryWarnings));
      texts.add(entryText);
    }

    List<CitationWarning> collectionWarnings = new ArrayList<>();
    if (renamedKeys > 0) {
      collectionWarnings.add(
          CitationWarning.of(
              CitationWarning.CITE_KEY_DISAMBIGUATED,
              "有 "
                  + renamedKeys
                  + " 条引用键与文件中已有条目重复，已追加字母后缀以保证同一文件内唯一；"
                  + "若你此前已在自己的文献库里用过这些键，需要按本文件更新"));
    }

    String exportedOn = LocalDate.now().toString();
    String fileName =
        "citations-"
            + format.name().toLowerCase(Locale.ROOT).replace('_', '-')
            + "-"
            + exportedOn
            + "."
            + format.fileExtension();
    return new CitationBatch(
        format,
        fileName,
        format.mediaType(),
        format.machineReadable(),
        ids.size(),
        entries,
        skipped,
        collectionWarnings,
        CitationCollectionFormatter.document(texts, format),
        PROVENANCE,
        exportedOn);
  }

  /** 单格式解析：把非法的用户输入转成 400，而不是 500。 */
  private static CitationFormat parseFormat(String rawFormat) {
    try {
      return CitationFormat.of(rawFormat);
    } catch (IllegalArgumentException ex) {
      throw BusinessException.invalidArgument(ex.getMessage());
    }
  }

  /**
   * 规整 id 列表：去空白、去空项、按首次出现去重。
   *
   * <p>去重保留首次出现的位置而不是重排，是因为读者选中的顺序就是他想在文件里看到的顺序（比如按
   * 检索结果的年份排下来）。重复选中同一成果只写一条——同一文件里两条一模一样的引用除了制造重复
   * 条目没有任何用处。
   */
  private static List<String> normalizeIds(List<String> rawIds) {
    if (rawIds == null || rawIds.isEmpty()) {
      return List.of();
    }
    Set<String> unique = new LinkedHashSet<>();
    for (String raw : rawIds) {
      if (raw == null) {
        continue;
      }
      String id = raw.trim();
      if (!id.isEmpty()) {
        unique.add(id);
      }
    }
    return List.copyOf(unique);
  }

  /** 单格式渲染结果。 */
  public record CitationText(
      CitationFormat format, String citeKey, String text, List<CitationWarning> warnings) {}

  /**
   * 一条 id 的解析结果。
   *
   * @param requestedId 读者勾的那个 id（可能与解析后的实体不同：合并过的成果会被重定向）
   * @param redirected 这条 id 是否经过合并重定向。分组去重时优先取「没经过重定向」的那条作为条目，
   *     这样被记成重复项的永远是那条已被合并掉的旧记录
   */
  private record Resolved(
      String requestedId,
      CitationSubject subject,
      List<CitationWarning> warnings,
      boolean redirected) {}

  /**
   * 装配被引用对象。
   *
   * <p>同时把「元数据层面」的局限收集进 {@code warnings}：判断与装配在同一处完成，避免出现
   * 「格式化器发现年份缺失但没人负责报告」这种沉默。
   */
  private CitationSubject subjectOf(String achievementId, List<CitationWarning> warnings) {
    return subjectOf(catalog.requirePublicTarget(achievementId).achievement(), warnings);
  }

  /**
   * 装配被引用对象（已解析出的终点实体）。
   *
   * <p>批量导出需要自己拿到 {@code PublicTarget}（要用它判断这条是否经过合并重定向），因此把装配与
   * 资格判定拆成两层：判定仍只有 {@code requirePublicTarget} 一处，装配可以被两个入口复用。
   */
  private CitationSubject subjectOf(Achievement achievement, List<CitationWarning> warnings) {
    String id = achievement.id();

    Optional<AchievementDetails.Paper> paper = achievements.findPaper(id);
    Optional<AchievementDetails.Patent> patent = achievements.findPatent(id);
    Optional<AchievementDetails.ResearchProject> project = achievements.findProject(id);
    Optional<AchievementDetails.Award> award = achievements.findAward(id);

    List<Contribution> relations = contributions.listByAchievement(id);
    List<CitationAuthor> authors = new ArrayList<>();
    int excluded = 0;
    int unresolved = 0;
    int pending = 0;
    for (Contribution relation : orderedByAuthorOrder(relations)) {
      if (relation.relationState() == RelationState.EXCLUDED) {
        // 已排除的参与关系不得出现在署名里：它表达的是「平台判定这条归属不成立」，
        // 把它写进引用等于把一个已被推翻的判断输出成事实。
        excluded++;
        continue;
      }
      Optional<String> displayName =
          scholars.findById(relation.scholarId()).map(scholar -> scholar.displayName());
      if (displayName.isEmpty() || displayName.get().isBlank()) {
        unresolved++;
        continue;
      }
      if (relation.relationState() == RelationState.PENDING_VERIFY
          || relation.relationState() == RelationState.DISPUTED) {
        pending++;
      }
      authors.add(
          new CitationAuthor(
              displayName.get(), relation.authorOrder(), relation.relationState().name()));
    }

    IntegritySummary integrity = IntegritySummary.of(integrityNotices.findByAchievement(id));

    Integer year = achievement.publicationYear().year();
    CitationSubject subject =
        new CitationSubject(
            id,
            achievement.type(),
            achievement.title(),
            year,
            paper.map(AchievementDetails.Paper::venue).orElse(null),
            paper.map(AchievementDetails.Paper::doi).orElse(null),
            numberField(patent, project),
            issuingBodyField(patent, project, award),
            award.map(AchievementDetails.Award::awardLevel).orElse(null),
            authors,
            integrity.status(),
            integrity.label(),
            achievement.dataNature());

    collectMetadataWarnings(subject, warnings, excluded, unresolved, pending);
    return subject;
  }

  /**
   * 署名序排序：已知序按 1、2、3 升序，未知序的排在后面并保持仓储返回顺序。
   *
   * <p>把未知序的按姓名或标识重排会更「整齐」，但那是编造顺序：读者看到的第一位会被当成第一作者。
   * 保持仓储顺序 + 明确告警，是唯一不制造虚假事实的做法。
   */
  private static List<Contribution> orderedByAuthorOrder(List<Contribution> relations) {
    List<Contribution> ordered = new ArrayList<>();
    for (Contribution relation : relations) {
      if (relation.authorOrder() != null) {
        ordered.add(relation);
      }
    }
    ordered.sort(Comparator.comparing(Contribution::authorOrder));
    for (Contribution relation : relations) {
      if (relation.authorOrder() == null) {
        ordered.add(relation);
      }
    }
    return ordered;
  }

  private static String numberField(
      Optional<AchievementDetails.Patent> patent,
      Optional<AchievementDetails.ResearchProject> project) {
    // 专利优先用公开号：申请号只说明「提出过」，公开号才是可以被引用的那一个；
    // 只有申请号时退化为申请号，并由文本里的「公开号:」前缀之外的措辞承担不了的部分交给告警。
    return patent
        .map(p -> firstNonBlank(p.publicationNo(), p.applicationNo()))
        .orElseGet(() -> project.map(AchievementDetails.ResearchProject::projectNo).orElse(null));
  }

  private static String issuingBodyField(
      Optional<AchievementDetails.Patent> patent,
      Optional<AchievementDetails.ResearchProject> project,
      Optional<AchievementDetails.Award> award) {
    String patentApplicants =
        patent
            .map(p -> p.applicantNames() == null ? null : String.join("、", p.applicantNames()))
            .orElse(null);
    return firstNonBlank(
        patentApplicants,
        project.map(AchievementDetails.ResearchProject::fundingAgency).orElse(null),
        award.map(AchievementDetails.Award::grantingBody).orElse(null));
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  /** 元数据层面的局限。顺序固定，便于前端稳定展示与脚本按序断言。 */
  private static void collectMetadataWarnings(
      CitationSubject subject,
      List<CitationWarning> warnings,
      int excluded,
      int unresolved,
      int pending) {
    if (!subject.realData()) {
      warnings.add(
          CitationWarning.of(
              CitationWarning.DATA_NATURE_NOT_REAL,
              "本条数据性质为 " + subject.dataNature().name() + "，不是真实来源数据，不得作为正式引用依据"));
    }
    switch (subject.integrityStatus() == null
        ? IntegritySummary.STATUS_NONE
        : subject.integrityStatus()) {
      case "RETRACTION" ->
          warnings.add(
              CitationWarning.of(CitationWarning.RETRACTED, "该成果已被撤稿（平台登记有撤稿声明），引用前必须核对原文"));
      case "CORRECTION" ->
          warnings.add(CitationWarning.of(CitationWarning.CORRECTED, "该成果登记有更正声明，引用时应核对更正后的版本"));
      case "CONCERN" ->
          warnings.add(CitationWarning.of(CitationWarning.CONCERNED, "该成果登记有关注声明，结论尚未定性，引用时应说明"));
      default -> {
        // 无生效声明，不需要告警。
      }
    }
    if (!subject.yearKnown()) {
      warnings.add(
          CitationWarning.of(CitationWarning.YEAR_UNKNOWN, "来源未提供年份，文本中以占位说明代替年份，正式引用前请按原文补全"));
    }
    if (subject.authors().isEmpty()) {
      warnings.add(CitationWarning.of(CitationWarning.NO_AUTHOR, "未登记署名者，引用文本缺少作者项，请按原文补全"));
    }
    int orderUnknown = 0;
    for (CitationAuthor author : subject.authors()) {
      if (!author.orderUsable()) {
        orderUnknown++;
      }
    }
    if (orderUnknown > 0) {
      warnings.add(
          CitationWarning.of(
              CitationWarning.AUTHOR_ORDER_UNKNOWN,
              "有 " + orderUnknown + " 位署名者的署名顺序未登记，文本中的先后顺序不代表真实署名顺序"));
    }
    if (pending > 0) {
      warnings.add(
          CitationWarning.of(
              CitationWarning.AUTHOR_PENDING_VERIFY,
              "有 " + pending + " 位署名者的归属尚待核验，姓名可能对应错误的学者档案"));
    }
    if (excluded > 0) {
      warnings.add(
          CitationWarning.of(
              CitationWarning.EXCLUDED_DROPPED, "有 " + excluded + " 条参与关系已被治理判为不成立，未计入署名"));
    }
    if (unresolved > 0) {
      warnings.add(
          CitationWarning.of(
              CitationWarning.AUTHOR_NAME_UNRESOLVED, "有 " + unresolved + " 位署名者取不到显示名，未写入引用文本"));
    }
    if (subject.type() == AchievementType.PAPER
        && (subject.venue() == null || subject.venue().isBlank())) {
      warnings.add(CitationWarning.of(CitationWarning.VENUE_MISSING, "未登记刊名或会议名，引用文本缺少发表载体"));
    }
  }
}
