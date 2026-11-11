package com.cognitanexus.core.taxonomy.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 学术领域受控词表。
 *
 * <p>依据：SRS 5.3（按学术领域分类管理；无法可靠映射时标记待分类，不强行归类）、
 * 任务书「学术成果按照学术领域分类管理，提供分类过滤和检索功能」。
 *
 * <p>为什么是「受控词表 + 规则」而不是「调用模型分类」：领域分类会直接进入检索分面与领域热点
 * 排名，必须满足三个条件——可复现（同一版本、同一输入得同一结果）、可解释（每条归属都能说出
 * 命中了什么）、可回滚（换版本可整批重算）。规则匹配天然满足这三点；模型调用则需要额外引入
 * 外部依赖且输出不稳定。词表以资源文件承载，改词表是一次可评审的数据变更，而不是代码变更。
 *
 * <p>词表本身带版本号，推断结果落库时记录该版本，形成「这批分类是用哪一版权表算出来的」这一
 * 可追溯链。
 */
public record AcademicTaxonomy(
    String version,
    String matchMode,
    double minScore,
    double secondaryRatio,
    double declaredWeight,
    double abstractWeight,
    List<Field> fields) {

  /** 唯一支持的匹配模式，见 {@link MatchText}。写死在词表里是为了让「怎么算的」不藏在代码里。 */
  public static final String MATCH_MODE_TOKEN_PREFIX = "token-prefix";

  /** 一个学科领域及其判别词。 */
  public record Field(String canonicalName, List<Term> terms) {}

  /**
   * 判别词。
   *
   * @param text 词表中书写的形式，用于证据展示（可能带连字符，便于人读）
   * @param needle 预计算好的查找串，见 {@link MatchText#needle}
   * @param weight 权重，5 表示领域专属术语，1 表示弱提示
   */
  public record Term(String text, String needle, double weight) {}

  /**
   * 从 JSON 解析。
   *
   * <p>解析即校验，且校验失败直接抛出：一份写坏的词表如果被容忍，表现是「全部成果都判不出领域」
   * 或「都判成同一个领域」——两种都很难在数据量大的时候被肉眼发现。宁可启动就失败。
   */
  public static AcademicTaxonomy fromJson(JsonNode root) {
    String version = text(root, "version");
    if (version == null) {
      throw new IllegalStateException("学术领域词表缺少 version");
    }
    String matchMode = text(root, "matchMode");
    if (!MATCH_MODE_TOKEN_PREFIX.equals(matchMode)) {
      throw new IllegalStateException(
          "学术领域词表的 matchMode 必须是 " + MATCH_MODE_TOKEN_PREFIX + "，实际为 " + matchMode);
    }
    double minScore = positive(root, "minScore");
    double secondaryRatio = ratio(root, "secondaryRatio");
    double declaredWeight = ratio(root, "titleWeight");
    double abstractWeight = ratio(root, "abstractWeight");
    JsonNode fieldNodes = root.path("fields");
    if (!fieldNodes.isArray() || fieldNodes.isEmpty()) {
      throw new IllegalStateException("学术领域词表没有定义任何领域");
    }

    List<Field> fields = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (JsonNode fieldNode : fieldNodes) {
      String name = text(fieldNode, "canonicalName");
      if (name == null) {
        throw new IllegalStateException("学术领域词表存在缺少 canonicalName 的领域");
      }
      if (!names.add(name)) {
        throw new IllegalStateException("学术领域词表存在重名领域：" + name);
      }
      JsonNode termNodes = fieldNode.path("terms");
      if (!termNodes.isArray() || termNodes.isEmpty()) {
        throw new IllegalStateException("领域「" + name + "」没有定义任何判别词");
      }
      List<Term> terms = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      for (JsonNode termNode : termNodes) {
        String termText = text(termNode, "term");
        if (termText == null) {
          throw new IllegalStateException("领域「" + name + "」存在空的判别词");
        }
        double weight = positive(termNode, "weight");
        // 规范化与查找串都在加载期算一次：匹配阶段对每条记录要跑数百次 indexOf，
        // 把不随记录变化的计算挪出循环是这一层存在的全部理由。
        String normalized = MatchText.normalize(termText).trim();
        if (normalized.isEmpty()) {
          throw new IllegalStateException("领域「" + name + "」的判别词规范化后为空：" + termText);
        }
        if (!seen.add(normalized)) {
          throw new IllegalStateException("领域「" + name + "」的判别词重复：" + termText);
        }
        terms.add(new Term(termText, MatchText.needle(normalized), weight));
      }
      fields.add(new Field(name, List.copyOf(terms)));
    }
    return new AcademicTaxonomy(
        version,
        matchMode,
        minScore,
        secondaryRatio,
        declaredWeight,
        abstractWeight,
        List.copyOf(fields));
  }

  public static AcademicTaxonomy load(InputStream in, ObjectMapper mapper) {
    try (InputStream stream = in) {
      return fromJson(mapper.readTree(stream));
    } catch (IOException ex) {
      throw new IllegalStateException("读取学术领域词表失败", ex);
    }
  }

  /** 按领域名取判别词，供测试与排查使用。 */
  public List<Term> termsOf(String canonicalName) {
    for (Field field : fields) {
      if (field.canonicalName().equals(canonicalName)) {
        return field.terms();
      }
    }
    return List.of();
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isTextual()) {
      return null;
    }
    String text = value.asText().trim();
    return text.isEmpty() ? null : text;
  }

  private static double positive(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isNumber() || value.asDouble() <= 0) {
      throw new IllegalStateException("学术领域词表的 " + field + " 必须是正数");
    }
    return value.asDouble();
  }

  private static double ratio(JsonNode node, String field) {
    double value = positive(node, field);
    if (value > 1) {
      throw new IllegalStateException("学术领域词表的 " + field + " 必须落在 (0,1] 区间");
    }
    return value;
  }

  /** 词表版本 + 匹配模式的组合标识，落库到 core_achievement_topic.rule_version。 */
  public String ruleVersion() {
    return version + "/" + matchMode;
  }

  /** 便于日志与提示展示的短描述。 */
  public String describe() {
    return version
        + "（"
        + fields.size()
        + " 个领域，"
        + fields.stream().mapToInt(f -> f.terms().size()).sum()
        + " 个判别词，阈值 "
        + minScore
        + "）";
  }
}
