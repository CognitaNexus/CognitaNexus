package com.cognitanexus.query.projection.application;

import com.cognitanexus.query.platform.persistence.JsonCodec;
import com.cognitanexus.query.platform.util.TextNormalizer;
import com.cognitanexus.query.projection.domain.InboxEventRecord;
import com.cognitanexus.query.projection.domain.ProjectedAchievement;
import com.cognitanexus.query.projection.domain.ProjectedContribution;
import com.cognitanexus.query.projection.domain.ProjectedTopic;
import com.cognitanexus.query.projection.domain.ScholarPair;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 事件载荷到投影记录的翻译。
 *
 * <p>依据：架构 V0.2 §6.3、§5.4、§9.3。所有字段读取都是「显式存在才使用」：缺失字段不猜默认值，
 * 需要必填字段而它absent 时交由调用方隔离该事件。这样做的原因是，投影一旦写入了一个编造的值
 * （例如给未知年份补一个合理年份），错误会沿着检索与统计一路传下去，而源头无法反查。
 */
final class EventPayloadReader {

  private final JsonCodec json;

  EventPayloadReader(JsonCodec json) {
    this.json = json;
  }

  /**
   * 把事件载荷翻译成成果投影。
   *
   * <p>可见性与生效状态缺失时按受限期值兜底（RESTRICTED / PENDING_GOVERNANCE），这是**有意的失败
   * 关闭**：事件来自服务之外，可能版本不符或字段被裁剪，此时宁可让这条不展示，也不能凭空宣称它
   * 已公开。注意这条兜底只适用于事件路径；快照导出路径的同类缺失直接判定为错误并中止重建，理由见
   * {@link #achievementFromExport}。
   *
   * @param event 事件本身，用于取 achievementId 与 revision 作为兜底
   * @return 无法构成可用投影时返回 empty（调用方据此隔离事件）
   */
  java.util.Optional<ProjectedAchievement> achievement(InboxEventRecord event) {
    Map<String, Object> payload = event.payload();
    String achievementId =
        firstNonBlank(asString(payload.get("achievementId")), event.aggregateId());
    String title = asString(payload.get("title"));
    String type = asString(payload.get("type"));
    String dataNature = asString(payload.get("dataNature"));
    if (achievementId == null || title == null || type == null || dataNature == null) {
      return java.util.Optional.empty();
    }
    String titleNorm = asString(payload.get("titleNorm"));
    if (titleNorm == null || titleNorm.isBlank()) {
      // 载荷缺 titleNorm 时按同一归一化规则现算。这里不能放弃该条：titleNorm 是检索键，
      // 缺少它会让成果在关键词检索中永久不可见，而成果本身是有效数据。
      titleNorm = TextNormalizer.normalize(title);
    }
    return java.util.Optional.of(
        new ProjectedAchievement(
            achievementId,
            type,
            title,
            titleNorm,
            dataNature,
            firstNonBlank(asString(payload.get("visibility")), "RESTRICTED"),
            firstNonBlank(asString(payload.get("effectiveStatus")), "PENDING_GOVERNANCE"),
            asInteger(payload.get("publicationYear")),
            asString(payload.get("doi")),
            asString(payload.get("venue")),
            asLong(payload.get("citationCount")),
            asString(payload.get("citationSource")),
            asLocalDate(payload.get("publishedDate")),
            asString(payload.get("abstractText")),
            event.aggregateVersion(),
            event.catalogRevision()));
  }

  /**
   * 与上同源：从 core 的单个成果导出视图翻译，字段名保持一致。
   *
   * <p><b>与事件路径的关键差别：可见性缺失在这里是错误，不是默认值。</b>事件路径对缺字段按受限兜底
   * （见 {@link #achievement}），因为那里面对的是外部流入、可能版本不符的事件，宁可不展示也不能误
   * 展示；而快照导出是**我们自己产出的**，缺字段只可能是导出侧写漏了。此时若同样按受限兜底，结果
   * 是每一条都变成非公开行，重建报告全绿而公开视图被清空。所以这里返回 empty，由调用方中止重建
   * ——中止的代价只是活动代不变，兜底的代价是整个平台对外没有内容。
   */
  java.util.Optional<ProjectedAchievement> achievementFromExport(
      Map<String, Object> view, long revision) {
    String achievementId =
        firstNonBlank(asString(view.get("id")), asString(view.get("achievementId")));
    String title = asString(view.get("title"));
    String type = asString(view.get("type"));
    String dataNature = asString(view.get("dataNature"));
    String visibility = asString(view.get("visibility"));
    String effectiveStatus = asString(view.get("effectiveStatus"));
    if (achievementId == null
        || title == null
        || type == null
        || dataNature == null
        || visibility == null
        || effectiveStatus == null) {
      return java.util.Optional.empty();
    }
    String titleNorm = asString(view.get("titleNorm"));
    if (titleNorm == null || titleNorm.isBlank()) {
      titleNorm = TextNormalizer.normalize(title);
    }
    return java.util.Optional.of(
        new ProjectedAchievement(
            achievementId,
            type,
            title,
            titleNorm,
            dataNature,
            visibility,
            effectiveStatus,
            asInteger(view.get("publicationYear")),
            asString(view.get("doi")),
            asString(view.get("venue")),
            asLong(view.get("citationCount")),
            asString(view.get("citationSource")),
            asLocalDate(view.get("publishedDate")),
            asString(view.get("abstractText")),
            asLong(view.get("version")) == null ? 1L : asLong(view.get("version")),
            revision));
  }

  List<ProjectedContribution> contributions(Object raw, String achievementId) {
    List<ProjectedContribution> result = new ArrayList<>();
    for (Map<String, Object> view : json.readMapList(raw)) {
      String scholarId = asString(view.get("scholarId"));
      if (scholarId == null) {
        continue;
      }
      result.add(
          new ProjectedContribution(
              // 载荷中的贡献视图不带成果标识（它就在父对象上），因此由调用方把父标识传下来。
              // 若改用视图里的字段，绝大多数情况下会得到 null，参与关系就无法按成果归属。
              achievementId,
              scholarId,
              asString(view.get("scholarName")),
              firstNonBlank(asString(view.get("role")), "AUTHOR"),
              asInteger(view.get("authorOrder")),
              firstNonBlank(asString(view.get("relationState")), "PENDING_VERIFY"),
              json.readStringList(view.get("organizations"))));
    }
    return result;
  }

  /**
   * 领域列表。
   *
   * <p>优先读带规范名的 topicViews；载荷只给了 id 列表时退回读 topicIds，此时名称为 null。
   * 两条路径都接受，是因为「名称暂时缺失」不应该让整条成果事件被隔离——领域名称只影响展示，
   * 不影响检索与统计口径。
   *
   * <p>退回路径的归属来源一律记为 SOURCE。这不是猜：引入规则推断之前，平台上的领域归属全部
   * 来自来源元数据，因此老载荷里的领域按定义都是来源给出的。把它标成「未知」反而会制造一个
   * 需要解释的第三态。
   */
  List<ProjectedTopic> topics(Object topicViews, Object topicIds) {
    List<ProjectedTopic> result = new ArrayList<>();
    List<Map<String, Object>> views = json.readMapList(topicViews);
    if (!views.isEmpty()) {
      for (Map<String, Object> view : views) {
        String topicId = asString(view.get("id"));
        if (topicId != null) {
          result.add(
              new ProjectedTopic(
                  topicId,
                  asString(view.get("canonicalName")),
                  firstNonBlank(asString(view.get("assignmentKind")), "SOURCE"),
                  asDouble(view.get("confidence"))));
        }
      }
      return result;
    }
    for (String topicId : json.readStringList(topicIds)) {
      result.add(new ProjectedTopic(topicId, null, "SOURCE", null));
    }
    return result;
  }

  /**
   * 由合格参与关系生成无序学者对。
   *
   * <p>顺序无关：{@link ScholarPair} 在构造时按规范 ID 字典序排列，因此同一对作者无论遍历顺序
   * 如何都得到同一个规范形式，边不会重复计数。
   */
  List<ScholarPair> pairs(List<ProjectedContribution> contributions) {
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

  static String asString(Object value) {
    if (value instanceof String text) {
      return text.isBlank() ? null : text;
    }
    return null;
  }

  static Integer asInteger(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return Integer.valueOf(text.trim());
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return null;
  }

  static Long asLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return Long.valueOf(text.trim());
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return null;
  }

  static Double asDouble(Object value) {
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return Double.valueOf(text.trim());
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return null;
  }

  /**
   * 日期解析。
   *
   * <p>只接受 ISO 日期。解析失败返回 null 而不是退化为「今年」或「本月」：架构 §5.4 要求日期未知
   * 不得补 1 月 1 日或当前时间，一个错误的日期比一个缺失的日期更难被发现。
   */
  static LocalDate asLocalDate(Object value) {
    String text = asString(value);
    if (text == null) {
      return null;
    }
    try {
      return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
    } catch (RuntimeException ex) {
      return null;
    }
  }

  static String firstNonBlank(String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate;
      }
    }
    return null;
  }
}
