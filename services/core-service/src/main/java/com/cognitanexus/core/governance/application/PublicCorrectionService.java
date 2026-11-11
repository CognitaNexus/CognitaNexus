package com.cognitanexus.core.governance.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.governance.domain.GovernanceAnomaly;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.core.platform.util.TextNormalizer;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公众纠错受理。
 *
 * <p>依据：FR02／FR16「成果详情须给出可追溯的来源」的现实反面——外部读者比治理人员更早发现
 * 「这条记录写错了」，而在此之前平台没有任何入口接收这类反馈。专家、被收录学者本人、同领域读者
 * 都只能通过平台之外的渠道喊话。
 *
 * <p><b>纠错报告不修改任何数据。</b>它只登记一条待办（{@link GovernanceAnomaly#KIND_PUBLIC_CORRECTION_REPORT}），
 * 由治理人员核实后走既有的修订与裁决路径。原因不是保守，而是本平台的红线：来源原值不可被人工修订
 * 覆盖、自动更新不得覆盖人工审核值。若让一条匿名请求直接改写字段，就等于开了一条绕过全部溯源与
 * 审核的写入通道，而这条通道无法审计到人。
 *
 * <p><b>防灌设计（三层，各管一件事）：</b>
 *
 * <ol>
 *   <li>过程内滑动窗口限流：同一来源 10 分钟内最多 {@value #SUBMITS_PER_WINDOW} 次；单条成果
 *       24 小时内最多 {@value #SUBMITS_PER_ACHIEVEMENT} 条报告（不分来源，封「慢速滴灌」）。它挡的是
 *       脚本刷接口，代价是单实例——多实例部署时每个实例各有一份窗口。
 *   <li>待办合并：同一条成果、同一个字段、同一段说明（归一后）在未处理期间只占一条待办，重复提交
 *       只累加 {@code reportCount}。它保证「刷 100 次」不会变成 100 条待办，把治理队列淹掉。
 *   <li>内容边界：说明 10–1000 字、联系方式 120 字以内、字段必须是枚举值。太短的说明无法核实，
 *       无上限的说明会把待办变成一篇作文。
 * </ol>
 *
 * <p><b>限流是有意「先于存在性判断」的。</b>若先查成果是否存在，未登录访客就能用 404 与其它状态
 * 码的差别探测某条受限成果的标识是否有效。限流对所有成果一视同仁，不随成果是否存在而变，因此
 * 不构成探测通道。
 *
 * <p><b>限流是单实例的、尽力而为的。</b>多实例部署时每个实例各有一份窗口，实际阈值会成倍放宽；
 * 真正的边界应放在网关（按 IP 与路径限速）。本地单实例运行下这层足够，且不引入 Redis 依赖
 * （架构决策明确不引入 Redis 作为业务依赖）。
 */
@Service
public class PublicCorrectionService {

  private static final Logger log = LoggerFactory.getLogger(PublicCorrectionService.class);

  /** 说明长度的下限：短于它无法定位到具体错在哪。 */
  public static final int MESSAGE_MIN = 10;

  /** 说明长度的上限：一条待办要能被一口气读完。 */
  public static final int MESSAGE_MAX = 1000;

  /** 联系方式上限。长度限制本身就是一种隐私保护：不鼓励在纠错里粘贴大段个人信息。 */
  public static final int CONTACT_MAX = 120;

  private static final int SUBMITS_PER_WINDOW = 5;

  private static final Duration WINDOW = Duration.ofMinutes(10);

  /**
   * 单条成果 24 小时内可累积的报告条数（**全站口径，不分来源**）。
   *
   * <p>为什么不分来源：来源级窗口（5 次 / 10 分钟）已经挡住了「一个人一口气刷」，挡不住的
   * 是「慢速滴灌」——一分钟一条、每条措辞都不同，一天下来一条成果上能堆一百多条待办。按成果
   * 全局计数是唯一能封住这条路径的规则；把它拆成「每来源」就等于没写，因为单个来源永远先撞上
   * 10 分钟窗口（这一点是实测出来的：初版把来源拼进了这个键，测试断言 11 次被拒时根本没触发）。
   *
   * <p>代价要写明：这是**可以被消耗的**配额——有人可以恶意刷满，让当天的真实报告被拒。取值因此
   * 定得比正常使用高一个量级（一条成果一天被报 20 处不同问题已经不是正常使用），并且配额耗尽时
   * 治理人手里已经有 20 条待办可看，噪音被限制住了，真相不会因此丢失。
   */
  private static final int SUBMITS_PER_ACHIEVEMENT = 20;

  private static final Duration ACHIEVEMENT_WINDOW = Duration.ofHours(24);

  /** 同一来源在 1 分钟内重复提交相同内容视为一次误触或重试，不累加计数。 */
  private static final Duration DUPLICATE_SUPPRESS = Duration.ofMinutes(1);

  /** 窗口表的清理阈值：超过它就顺手清一遍过期键，避免长期运行后无界增长。 */
  private static final int WINDOW_CLEANUP_THRESHOLD = 5000;

  /** 摘要里引用的原文长度。待办列表只显示一行，全文在 detail.report 里。 */
  private static final int SUMMARY_EXCERPT = 60;

  private final AchievementRepository achievements;
  private final GovernanceRepositories.AnomalyRepository anomalies;
  private final Clock clock;
  private final Map<String, Deque<Instant>> windows = new ConcurrentHashMap<>();

  @Autowired
  public PublicCorrectionService(
      AchievementRepository achievements, GovernanceRepositories.AnomalyRepository anomalies) {
    this(achievements, anomalies, Clock.systemUTC());
  }

  /** 测试入口：注入可控时钟，使限流与「重复提交抑制」可以被断言而不是靠 sleep。 */
  public PublicCorrectionService(
      AchievementRepository achievements,
      GovernanceRepositories.AnomalyRepository anomalies,
      Clock clock) {
    this.achievements = achievements;
    this.anomalies = anomalies;
    this.clock = clock;
  }

  /**
   * 可纠错的字段。
   *
   * <p>用枚举而不是自由文本：录入方给的字段名一旦自由，同一处错误会被登记成「标题」「论文标题」
   * 「题名」三条待办，而处置人看到的只是三个说法。
   */
  public enum Field {
    TITLE("标题"),
    YEAR("发表年份"),
    AUTHOR("作者与参与人"),
    VENUE("期刊或会议"),
    IDENTIFIER("DOI、专利号等标识"),
    TYPE("成果类型"),
    TOPIC("研究领域"),
    SOURCE("来源或链接"),
    OTHER("其它");

    private final String label;

    Field(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 外部输入解析。未知值一律拒绝，不做「猜一个最像的」——猜错会把报告归到错误的处置队列。 */
    public static Optional<Field> of(String code) {
      if (code == null || code.isBlank()) {
        return Optional.empty();
      }
      String normalized = code.trim().toUpperCase(java.util.Locale.ROOT);
      for (Field field : values()) {
        if (field.name().equals(normalized)) {
          return Optional.of(field);
        }
      }
      return Optional.empty();
    }
  }

  /** 提交命令。 */
  public record ReportCommand(String achievementId, String field, String message, String contact) {}

  /**
   * 受理结果。
   *
   * @param reportId 待办标识（既有待办被合并时就是那条既有待办的标识）
   * @param merged 是否并入了既有待办
   * @param reportCount 该问题当前的累计报告数；它是对外可披露的「有多少人也认为这里错了」
   */
  public record Outcome(
      String reportId, boolean merged, int reportCount, String field, String fieldLabel) {}

  /**
   * 受理一条公众纠错报告。
   *
   * @param submitterKey 来源标识（当前为 TCP 对端地址）。只用于限流与溯源指纹，不落库原文。
   * @param accountId 若请求来自已登录账号则为该账号标识；匿名时传 {@code null}
   */
  @Transactional
  public Outcome submit(ReportCommand command, String submitterKey, String accountId) {
    if (command == null) {
      throw BusinessException.invalidArgument("请求体不能为空");
    }
    String message = requireMessage(command.message());
    String contact = optionalContact(command.contact());
    Field field =
        Field.of(command.field())
            .orElseThrow(
                () ->
                    BusinessException.invalidArgument(
                        "请选择要纠正的字段，取值见 /api/v1/public/correction-fields"));

    String key = submitterKey == null || submitterKey.isBlank() ? "unknown" : submitterKey.trim();
    // 限流先于存在性判断：见类注释。两条规则各自记账，任一越界即拒绝。
    enforceRate(key, SUBMITS_PER_WINDOW, WINDOW, "提交过于频繁，请稍后再试");
    // 这条不拼来源：见 SUBMITS_PER_ACHIEVEMENT 的注释（拼来源会让它永远打不到上限）。
    String perAchievementKey = "ach|" + nullToEmpty(command.achievementId());
    enforceRate(
        perAchievementKey,
        SUBMITS_PER_ACHIEVEMENT,
        ACHIEVEMENT_WINDOW,
        "同一成果的纠错提交次数已达上限，请等待治理人员核实");

    Achievement achievement = resolvePublicAchievement(command.achievementId());
    String dedupeKey = dedupeKeyOf(achievement.id(), field, message);
    String fingerprint = fingerprintOf(key);
    Instant now = clock.instant();

    Optional<GovernanceAnomaly> existing =
        anomalies.findOpenByDedupeKey(GovernanceAnomaly.KIND_PUBLIC_CORRECTION_REPORT, dedupeKey);
    if (existing.isPresent()) {
      GovernanceAnomaly open = existing.get();
      Map<String, Object> detail = new LinkedHashMap<>(open.detail());
      int count = intOf(detail.get("reportCount"), 1);
      boolean suppressed = isSuppressedRepeat(detail, fingerprint, now);
      if (!suppressed) {
        count++;
      }
      detail.put("reportCount", count);
      detail.put("lastReportedAt", now.toString());
      detail.put("lastReporterFingerprint", fingerprint);
      // 联系方式随最近一次提交更新：同一个人第二次补了联系方式，第一次的「无」不该继续盖住它。
      if (contact != null) {
        detail.put("contact", contact);
      }
      anomalies.refreshDetail(open.id(), detail);
      log.info(
          "公众纠错并入既有待办 achievement={} field={} count={} suppressed={}",
          achievement.id(),
          field.name(),
          count,
          suppressed);
      return new Outcome(open.id(), true, count, field.name(), field.label());
    }

    String anomalyId = Identifiers.next("anm");
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("dedupeKey", dedupeKey);
    detail.put("summary", "公众纠错：" + field.label() + "——" + excerpt(message));
    detail.put("field", field.name());
    detail.put("fieldLabel", field.label());
    detail.put("report", message);
    detail.put("reportCount", 1);
    detail.put("firstReportedAt", now.toString());
    detail.put("lastReportedAt", now.toString());
    detail.put("lastReporterFingerprint", fingerprint);
    detail.put("reporterKind", accountId == null || accountId.isBlank() ? "ANONYMOUS" : "ACCOUNT");
    if (accountId != null && !accountId.isBlank()) {
      detail.put("reporterAccountId", accountId);
    }
    detail.put("submitterFingerprint", fingerprint);
    if (contact != null) {
      detail.put("contact", contact);
    }
    detail.put("handlingNote", "报告只登记为待办，不修改任何字段；核实后请走修订或裁决路径，并在处置结论里写明依据");
    anomalies.record(
        new GovernanceAnomaly(
            anomalyId,
            GovernanceAnomaly.KIND_PUBLIC_CORRECTION_REPORT,
            null,
            achievement.id(),
            detail,
            "OPEN",
            now,
            null));
    log.info("公众纠错登记 achievement={} field={}", achievement.id(), field.name());
    return new Outcome(anomalyId, false, 1, field.name(), field.label());
  }

  /** 对外披露的字段清单：前端下拉直接用它，标签只在服务端定义一处。 */
  public List<Map<String, Object>> fields() {
    return java.util.Arrays.stream(Field.values())
        .map(
            field -> {
              Map<String, Object> view = new LinkedHashMap<String, Object>();
              view.put("code", field.name());
              view.put("label", field.label());
              return view;
            })
        .toList();
  }

  /** 未处理的公众纠错条数。 */
  @Transactional(readOnly = true)
  public long countOpen() {
    return anomalies.countOpenOfKind(GovernanceAnomaly.KIND_PUBLIC_CORRECTION_REPORT);
  }

  // ---------------------------------------------------------------------------
  // 内部
  // ---------------------------------------------------------------------------

  /**
   * 解析被纠错的成果，并确认它当前确实对公众可见。
   *
   * <p>「不存在」与「不可见」返回同一个 404：否则未登录访客可以用状态码差异探测受限成果。
   * 已被合并的标识会被重定向到幸存实体——报告写在旧链接上，不该因此丢掉。
   */
  private Achievement resolvePublicAchievement(String achievementId) {
    if (achievementId == null || achievementId.isBlank()) {
      throw BusinessException.invalidArgument("缺少成果标识");
    }
    Achievement achievement =
        achievements
            .findById(achievementId.trim())
            .orElseThrow(() -> BusinessException.notFound("成果不存在或尚不可见"));
    if (achievement.merged()) {
      String survivor =
          achievements.resolveSurvivorId(achievement.id()).orElse(achievement.mergedIntoId());
      achievement =
          achievements
              .findById(survivor)
              .orElseThrow(() -> BusinessException.notFound("成果不存在或尚不可见"));
    }
    if (!achievement.visibility().publiclyVisible()
        || !achievement.effectiveStatus().publiclyEligible()) {
      throw BusinessException.notFound("成果不存在或尚不可见");
    }
    return achievement;
  }

  private String requireMessage(String raw) {
    String value = raw == null ? "" : raw.trim();
    if (value.length() < MESSAGE_MIN) {
      throw BusinessException.invalidArgument("请把问题说清楚，至少 " + MESSAGE_MIN + " 个字");
    }
    if (value.length() > MESSAGE_MAX) {
      throw BusinessException.invalidArgument("说明请不要超过 " + MESSAGE_MAX + " 个字");
    }
    return value;
  }

  private String optionalContact(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.trim();
    if (value.length() > CONTACT_MAX) {
      throw BusinessException.invalidArgument("联系方式请不要超过 " + CONTACT_MAX + " 个字");
    }
    return value;
  }

  /**
   * 滑动窗口计数。
   *
   * <p>窗口用「先清理过期时间点、再判断是否越界」的写法，未越界才记账——否则被拒的那次也会占一个
   * 名额，用户越重试越出不来。
   */
  private void enforceRate(String windowKey, int limit, Duration window, String message) {
    Instant now = clock.instant();
    Instant floor = now.minus(window);
    cleanupIfLarge(now);
    Deque<Instant> hits = windows.computeIfAbsent(windowKey, k -> new ArrayDeque<>());
    synchronized (hits) {
      while (!hits.isEmpty() && hits.peekFirst().isBefore(floor)) {
        hits.pollFirst();
      }
      if (hits.size() >= limit) {
        throw new BusinessException(ErrorCode.RATE_LIMITED, message);
      }
      hits.addLast(now);
    }
  }

  private void cleanupIfLarge(Instant now) {
    if (windows.size() <= WINDOW_CLEANUP_THRESHOLD) {
      return;
    }
    Instant oldest = now.minus(ACHIEVEMENT_WINDOW);
    windows
        .entrySet()
        .removeIf(
            entry -> {
              Deque<Instant> hits = entry.getValue();
              synchronized (hits) {
                return hits.isEmpty() || hits.peekLast().isBefore(oldest);
              }
            });
  }

  /**
   * 同一去重键的重复提交是否应视为「同一次提交」。
   *
   * <p>浏览器在超时后重试、用户连点两下都会产生完全相同的请求；把它们各自计数会让
   * {@code reportCount} 变成一个虚高的数字，而那个数字是要拿去判断「有多少人认为这里错了」的。
   */
  private boolean isSuppressedRepeat(Map<String, Object> detail, String fingerprint, Instant now) {
    if (!fingerprint.equals(detail.get("lastReporterFingerprint"))) {
      return false;
    }
    Object last = detail.get("lastReportedAt");
    if (!(last instanceof String text) || text.isBlank()) {
      return false;
    }
    try {
      return Instant.parse(text).isAfter(now.minus(DUPLICATE_SUPPRESS));
    } catch (RuntimeException e) {
      // 时间戳损坏时不抑制：宁可多算一次，也不要静默吞掉一条真实报告。
      return false;
    }
  }

  private static String dedupeKeyOf(String achievementId, Field field, String message) {
    String normalized = TextNormalizer.normalize(message);
    return achievementId
        + "|"
        + field.name()
        + "|"
        + shortHash(normalized == null ? "" : normalized);
  }

  /**
   * 来源指纹。
   *
   * <p>只保留地址的散列前 8 位，不落原文：它的用途只有两个——「这两条报告是不是同一个人提的」
   * 与「被刷时能不能归并到同一来源」。存原始 IP 会把一个公开纠错入口变成一张访问日志。
   * 这里的散列不带密钥，因此不是匿名化承诺，只是不作日常检索用途。
   */
  private static String fingerprintOf(String key) {
    return "fp_" + shortHash(key);
  }

  private static String shortHash(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash, 0, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  private static String excerpt(String message) {
    String flat = message.replaceAll("\\s+", " ").trim();
    return flat.length() <= SUMMARY_EXCERPT ? flat : flat.substring(0, SUMMARY_EXCERPT) + "…";
  }

  private static int intOf(Object value, int fallback) {
    return value instanceof Number number ? number.intValue() : fallback;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
