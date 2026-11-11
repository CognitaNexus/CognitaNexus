package com.cognitanexus.core.catalog.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ContributionRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.FieldProvenanceRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ScholarRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.AchievementDetails;
import com.cognitanexus.core.catalog.domain.Contribution;
import com.cognitanexus.core.catalog.domain.Scholar;
import com.cognitanexus.core.catalog.domain.ScholarProfile;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.Capabilities;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.portal.application.PortalService;
import com.cognitanexus.sharedkernel.BusinessException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 学者门户的自助维护：简介、主页链接、头像、代表作置顶。
 *
 * <p>依据：SRS 5.4、AC-IMPL-FR06-01、待办「学者主页不能自己维护」。
 *
 * <p><b>为什么这几项可以直接生效，而显示名与机构必须审批：</b>前者是「本人对自己工作的自述」，
 * 平台没有能力也没有必要替学者背书；后者是身份与归属事实，改错会把他人的成果挂到别人名下，
 * 因此仍走 {@code portal-changes} 的待审通道（待审版本绝不覆盖已公开版本）。
 *
 * <p>所有写入都要求对象级授权（{@link PortalService#requirePortalAuthority}），
 * 平台管理员身份本身不构成改写任意学者档案的理由。
 */
@Service
public class ScholarProfileService {

  private static final Logger log = LoggerFactory.getLogger(ScholarProfileService.class);

  public static final int MAX_FEATURED = 3;
  private static final int MAX_SUMMARY_LENGTH = 2000;
  private static final int MAX_HOMEPAGE_LENGTH = 500;

  private final ScholarRepository scholars;
  private final AchievementRepository achievements;
  private final ContributionRepository contributions;
  private final FieldProvenanceRepository provenance;
  private final ScholarAvatarStorage avatarStorage;
  private final PortalService portalService;
  private final PublicCatalogQueryService catalog;
  private final AuditRecorder audit;

  public ScholarProfileService(
      ScholarRepository scholars,
      AchievementRepository achievements,
      ContributionRepository contributions,
      FieldProvenanceRepository provenance,
      ScholarAvatarStorage avatarStorage,
      PortalService portalService,
      PublicCatalogQueryService catalog,
      AuditRecorder audit) {
    this.scholars = scholars;
    this.achievements = achievements;
    this.contributions = contributions;
    this.provenance = provenance;
    this.avatarStorage = avatarStorage;
    this.portalService = portalService;
    this.catalog = catalog;
    this.audit = audit;
  }

  // ===========================================================================
  // 读取
  // ===========================================================================

  /** 门户编辑页要的当前值。含置顶清单与上限，前端不必硬编码 3。 */
  @Transactional(readOnly = true)
  public Map<String, Object> editableProfile(ActorContext actor, String scholarId) {
    requireMaintainer(actor, scholarId);
    Scholar scholar = requireScholar(scholarId);
    ScholarProfile profile =
        scholars.findProfile(scholarId).orElse(ScholarProfile.empty(scholarId));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("scholarId", scholar.id());
    data.put("displayName", scholar.displayName());
    data.put("organizationName", scholar.organizationName());
    data.put("researchSummary", scholar.researchSummary());
    data.put("homepageUrl", profile.homepageUrl());
    data.put("avatarUrl", profile.avatarUrl());
    data.put("profileUpdatedAt", iso(profile.profileUpdatedAt()));
    List<Map<String, Object>> featured = featuredViews(scholarId);
    data.put("featured", featured);
    data.put("maxFeatured", MAX_FEATURED);
    data.put(
        "note",
        "简介、主页与头像由本人填写，即时生效并标注为本人自述；显示名与机构属于身份与归属事实，" + "仍需提交变更并经审核。置顶只能选本人已有合格参与关系、且当前公开可读的成果。");
    return data;
  }

  /** 公开头像：只给字节与媒体类型，缓存与状态码由控制器决定。 */
  @Transactional(readOnly = true)
  public AvatarResource avatar(String scholarId) {
    Scholar scholar =
        scholars.findById(scholarId).orElseThrow(() -> BusinessException.notFound("学者不存在"));
    String targetId =
        scholar.merged() ? scholars.resolveSurvivorId(scholarId).orElse(scholarId) : scholarId;
    ScholarProfile profile =
        scholars.findProfile(targetId).orElseThrow(() -> BusinessException.notFound("该学者未上传头像"));
    if (!profile.hasAvatar()) {
      throw BusinessException.notFound("该学者未上传头像");
    }
    return new AvatarResource(avatarStorage.read(profile.avatarRef()), profile.avatarMediaType());
  }

  /** 头像响应体。 */
  public record AvatarResource(byte[] content, String mediaType) {}

  // ===========================================================================
  // 写入
  // ===========================================================================

  /**
   * 修改自述字段。
   *
   * <p>参数语义刻意区分「不动」与「清空」：{@code null} 表示这一项不提交（保留原值），
   * 空字符串表示清空。把两者合并成一种，前端就无法只改简介而不碰主页。
   */
  @Transactional
  public Map<String, Object> updateProfile(
      ActorContext actor, String scholarId, String researchSummary, String homepageUrl) {
    requireMaintainer(actor, scholarId);
    Scholar scholar = requireScholar(scholarId);
    ScholarProfile before = scholars.findProfile(scholarId).orElse(ScholarProfile.empty(scholarId));

    String summary = researchSummary == null ? null : researchSummary.strip();
    if (summary != null && summary.length() > MAX_SUMMARY_LENGTH) {
      throw BusinessException.invalidArgument("研究简介不得超过 " + MAX_SUMMARY_LENGTH + " 字");
    }
    if (summary != null && "".equals(summary)) {
      // 空串 = 清空。落库用 null，公开页据此退回「尚未填写」，而不是渲染一段空白段落。
      summary = null;
    }
    String homepage = homepageUrl == null ? null : homepageUrl.strip();
    if (homepage != null && "".equals(homepage)) {
      homepage = null;
    }
    if (homepage != null) {
      homepage = normalizeHomepage(homepage);
    }

    Map<String, Object> beforeView = new LinkedHashMap<>();
    beforeView.put("researchSummary", scholar.researchSummary());
    beforeView.put("homepageUrl", before.homepageUrl());
    Map<String, Object> afterView = new LinkedHashMap<>();

    if (summary != null || researchSummary != null) {
      scholars.updateResearchSummary(scholarId, summary);
      // 写 MANUAL 级来源：来源侧自动更新不得覆盖本人填写的内容（BR-IMP-02）。
      provenance.upsertReviewed("SCHOLAR", scholarId, "researchSummary", "MANUAL", summary);
      afterView.put("researchSummary", summary);
    }
    if (homepage != null || homepageUrl != null) {
      scholars.updateHomepageUrl(scholarId, homepage);
      afterView.put("homepageUrl", homepage);
    }
    if (afterView.isEmpty()) {
      throw BusinessException.invalidArgument("没有需要修改的字段");
    }

    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "SCHOLAR_PROFILE_UPDATED",
            "SCHOLAR",
            scholarId,
            beforeView,
            afterView,
            "本人自助维护门户资料（简介与主页链接）"));
    return editableProfile(actor, scholarId);
  }

  /** 替换代表作置顶。传空列表表示清空。 */
  @Transactional
  public Map<String, Object> updateFeatured(
      ActorContext actor, String scholarId, List<String> achievementIds) {
    requireMaintainer(actor, scholarId);
    requireScholar(scholarId);

    List<String> ids =
        new ArrayList<>(new LinkedHashSet<>(achievementIds == null ? List.of() : achievementIds));
    ids.removeIf(id -> id == null || id.isBlank());
    if (ids.size() > MAX_FEATURED) {
      throw BusinessException.invalidArgument("代表作最多置顶 " + MAX_FEATURED + " 条");
    }
    List<String> before = scholars.listFeatured(scholarId);
    // 先逐条校验再落库：任何一条不合格都整体拒绝，不做「部分成功」。
    // 部分成功会让学者以为自己选的三条都在，实际少了一条。
    for (String id : ids) {
      requireFeatureable(scholarId, id);
    }
    scholars.replaceFeatured(scholarId, ids);

    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "SCHOLAR_FEATURED_UPDATED",
            "SCHOLAR",
            scholarId,
            Map.of("featured", before),
            Map.of("featured", ids),
            "本人调整代表作置顶顺序"));
    return editableProfile(actor, scholarId);
  }

  @Transactional
  public Map<String, Object> uploadAvatar(
      ActorContext actor, String scholarId, String fileName, String contentType, byte[] content) {
    requireMaintainer(actor, scholarId);
    ScholarProfile before = scholars.findProfile(scholarId).orElse(ScholarProfile.empty(scholarId));
    ScholarAvatarStorage.StoredAvatar stored = avatarStorage.store(scholarId, contentType, content);
    scholars.updateAvatar(scholarId, stored.ref(), stored.mediaType());
    // 落库成功后再删旧文件：先删后写一旦写库失败，头像就凭空消失。
    avatarStorage.deleteQuietly(before.avatarRef());

    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "SCHOLAR_AVATAR_UPDATED",
            "SCHOLAR",
            scholarId,
            Map.of("avatarMediaType", String.valueOf(before.avatarMediaType())),
            Map.of(
                "avatarMediaType",
                stored.mediaType(),
                "size",
                stored.size(),
                "sha256",
                stored.sha256()),
            "本人上传门户头像"
                + (fileName == null || fileName.isBlank() ? "" : "（原文件名 " + fileName + "）")));
    log.info(
        "头像已更新 scholarId={} mediaType={} size={}", scholarId, stored.mediaType(), stored.size());
    return editableProfile(actor, scholarId);
  }

  @Transactional
  public Map<String, Object> removeAvatar(ActorContext actor, String scholarId) {
    requireMaintainer(actor, scholarId);
    ScholarProfile before = scholars.findProfile(scholarId).orElse(ScholarProfile.empty(scholarId));
    if (!before.hasAvatar()) {
      throw BusinessException.notFound("该学者没有头像可删除");
    }
    scholars.clearAvatar(scholarId);
    avatarStorage.deleteQuietly(before.avatarRef());
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "SCHOLAR_AVATAR_REMOVED",
            "SCHOLAR",
            scholarId,
            Map.of("avatarMediaType", before.avatarMediaType()),
            Map.of("avatarMediaType", "none"),
            "本人移除门户头像"));
    return editableProfile(actor, scholarId);
  }

  // ===========================================================================
  // 公开视图（供 PublicCatalogQueryService 复用）
  // ===========================================================================

  /**
   * 公开页要的自述区块。
   *
   * <p>置顶里已不可公开的条目会被跳过并计数：静默丢掉会让「我明明置顶了三条，页面上只剩两条」
   * 变成一个无人能解释的现象。
   */
  @Transactional(readOnly = true)
  public Map<String, Object> publicProfileOf(String scholarId) {
    ScholarProfile profile =
        scholars.findProfile(scholarId).orElse(ScholarProfile.empty(scholarId));
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("homepageUrl", profile.homepageUrl());
    view.put("avatarUrl", profile.avatarUrl());
    view.put("profileUpdatedAt", iso(profile.profileUpdatedAt()));
    view.put("note", "简介、主页与头像由本人填写（平台不代学者背书）；显示名与机构经审核后变更");
    return view;
  }

  /** 公开页的置顶清单。 */
  @Transactional(readOnly = true)
  public Map<String, Object> publicFeaturedOf(String scholarId) {
    List<Map<String, Object>> items = new ArrayList<>();
    int unavailable = 0;
    int missing = 0;
    for (String achievementId : scholars.listFeatured(scholarId)) {
      try {
        PublicCatalogQueryService.PublicTarget target = catalog.requirePublicTarget(achievementId);
        items.add(featuredView(target.achievement()));
      } catch (BusinessException ex) {
        // 不存在与不可公开在这里同样处理：不披露原因，只累计数量。
        if (ex.errorCode() == com.cognitanexus.sharedkernel.ErrorCode.NOT_FOUND) {
          missing++;
        } else {
          unavailable++;
        }
      }
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("items", items);
    view.put("unavailableCount", unavailable + missing);
    view.put("note", "本人置顶的代表作；已不可公开或已被合并的条目自动隐藏并计入 unavailableCount");
    return view;
  }

  // ===========================================================================
  // 内部
  // ===========================================================================

  private List<Map<String, Object>> featuredViews(String scholarId) {
    List<Map<String, Object>> items = new ArrayList<>();
    for (String achievementId : scholars.listFeatured(scholarId)) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("achievementId", achievementId);
      Optional<Achievement> achievement = achievements.findById(achievementId);
      item.put("title", achievement.map(Achievement::title).orElse(null));
      item.put("type", achievement.map(a -> a.type().name()).orElse(null));
      item.put("typeLabel", achievement.map(a -> a.type().label()).orElse(null));
      item.put(
          "publicationYear",
          achievement.flatMap(a -> Optional.ofNullable(a.publicationYear().year())).orElse(null));
      // 编辑页要能看出「这条已经不可公开了」，否则学者永远不知道自己置顶了一条被撤下的成果。
      boolean readable;
      try {
        catalog.requirePublicTarget(achievementId);
        readable = true;
      } catch (BusinessException ex) {
        readable = false;
      }
      item.put("publiclyReadable", readable);
      items.add(item);
    }
    return items;
  }

  private Map<String, Object> featuredView(Achievement achievement) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", achievement.id());
    view.put("type", achievement.type().name());
    view.put("typeLabel", achievement.type().label());
    view.put("title", achievement.title());
    view.put("publicationYear", achievement.publicationYear().year());
    achievements
        .findPaper(achievement.id())
        .ifPresent(
            (AchievementDetails.Paper paper) -> {
              view.put("venue", paper.venue());
              view.put("citationCount", paper.citationCount());
              view.put("citationSource", paper.citationSource());
            });
    return view;
  }

  /** 置顶的准入条件：属于该学者的合格关系 + 当前公开可读。 */
  private void requireFeatureable(String scholarId, String achievementId) {
    Achievement achievement =
        achievements.findById(achievementId).orElseThrow(() -> BusinessException.notFound("成果不存在"));
    String targetId = achievement.merged() ? achievement.mergedIntoId() : achievement.id();
    boolean mine =
        contributions.listByAchievement(targetId).stream()
            .anyMatch(
                (Contribution contribution) ->
                    scholarId.equals(contribution.scholarId()) && contribution.qualifiedForGraph());
    if (!mine) {
      throw BusinessException.invalidArgument("只能置顶本人已有合格参与关系的成果：" + achievementId);
    }
    try {
      catalog.requirePublicTarget(achievementId);
    } catch (BusinessException ex) {
      throw BusinessException.invalidArgument("该成果当前不可公开，不能置顶：" + achievementId);
    }
  }

  private String normalizeHomepage(String raw) {
    if (raw.length() > MAX_HOMEPAGE_LENGTH) {
      throw BusinessException.invalidArgument("主页地址不得超过 " + MAX_HOMEPAGE_LENGTH + " 字符");
    }
    URI uri;
    try {
      uri = new URI(raw);
    } catch (URISyntaxException ex) {
      throw BusinessException.invalidArgument("主页地址不是合法的 URL");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
    if (!scheme.equals("http") && !scheme.equals("https")) {
      // 只允许 http(s)：javascript: 与 data: 会被原样渲染成链接，等于给档案页开一个脚本入口。
      throw BusinessException.invalidArgument("主页地址必须以 http:// 或 https:// 开头");
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw BusinessException.invalidArgument("主页地址缺少主机名");
    }
    return raw;
  }

  private Scholar requireScholar(String scholarId) {
    Scholar scholar =
        scholars.findById(scholarId).orElseThrow(() -> BusinessException.notFound("学者不存在"));
    if (scholar.merged()) {
      throw BusinessException.invalidArgument("该档案已被合并，请在合并后的档案上维护资料");
    }
    return scholar;
  }

  private void requireMaintainer(ActorContext actor, String scholarId) {
    Capabilities.require(actor, RoleNames.CAP_PORTAL_MAINTAIN);
    portalService.requirePortalAuthority(actor, scholarId);
  }

  private static String iso(Instant instant) {
    return instant == null ? null : instant.toString();
  }
}
