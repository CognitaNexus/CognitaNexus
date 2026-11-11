package com.cognitanexus.ingestion.collect.application;

import com.cognitanexus.ingestion.collect.domain.CollectModels.SourceRunConfig;
import com.cognitanexus.ingestion.platform.client.CoreInternalClient;
import com.cognitanexus.ingestion.platform.util.Fingerprints;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 文件导入：以文件形态接入来源记录。
 *
 * <p>依据：AC-IMPL-FR08（一期验证两种独立且获准来源输入，API/文件任选）、AC-IMPL-FR16-01
 * （四类成果均须可公开检索）。论文、项目、获奖已有可免密钥访问的公开 API，专利没有：公开专利
 * 检索服务普遍要求注册密钥，而没有凭据就不该绕过服务方去抓页面。因此专利类走文件导入——由
 * 使用者从合法渠道导出后导入，系统负责校验、去重、治理与展示，不负责替使用者取得授权。
 *
 * <p>数据性质必须由调用方显式给出，默认 {@link DataNature#SIMULATED}：导入的文件可能来自公开
 * 数据集，也可能来自课程组的演示样例，系统无从判断。缺省成 REAL 会让模拟数据混进「真实」统计，
 * 那是本平台最不能犯的错；缺省成 SIMULATED 最多让真实数据暂时显得保守，可以改。
 *
 * <p>逐行校验、逐行报错：坏行不会中断整批，也不会被静默丢弃——导入结果里必须能按行号指出问题，
 * 否则用户只知道「有 12 条没进去」，无法定位。
 */
@Service
public class ImportService {

  /** 单次导入上限。超过上限要求拆分文件，而不是让一次请求占满内存与事务。 */
  static final int MAX_ROWS = 5000;

  /** 单次交给 core 的条数上限，与采集交接的批大小保持一致。 */
  private static final int HANDOFF_CHUNK = 200;

  private static final Set<String> KINDS = Set.of("PAPER", "PATENT", "PROJECT", "AWARD");

  /** CSV 列名 → core 规范化记录的键。表头用业务可读的名字，映射集中在这里。 */
  private static final Map<String, String> COLUMNS = new LinkedHashMap<>();

  static {
    COLUMNS.put("kind", "kind");
    COLUMNS.put("类型", "kind");
    COLUMNS.put("title", "title");
    COLUMNS.put("标题", "title");
    COLUMNS.put("doi", "doi");
    COLUMNS.put("doi号", "doi");
    COLUMNS.put("venue", "venue");
    COLUMNS.put("发表载体", "venue");
    COLUMNS.put("publicationyear", "publicationYear");
    COLUMNS.put("年份", "publicationYear");
    COLUMNS.put("publisheddate", "publishedDate");
    COLUMNS.put("abstracttext", "abstractText");
    COLUMNS.put("摘要", "abstractText");
    COLUMNS.put("authors", "authors");
    COLUMNS.put("作者", "authors");
    COLUMNS.put("topics", "topics");
    COLUMNS.put("领域", "topics");
    COLUMNS.put("sourcecategory", "sourceCategory");
    COLUMNS.put("sourceurl", "sourceUrl");
    COLUMNS.put("citationcount", "citationCount");
    COLUMNS.put("citationsource", "citationSource");
    COLUMNS.put("applicationno", "applicationNo");
    COLUMNS.put("申请号", "applicationNo");
    COLUMNS.put("publicationno", "publicationNo");
    COLUMNS.put("公开号", "publicationNo");
    COLUMNS.put("ipccodes", "ipcCodes");
    COLUMNS.put("ipc分类", "ipcCodes");
    COLUMNS.put("applicantnames", "applicantNames");
    COLUMNS.put("申请人", "applicantNames");
    COLUMNS.put("filingdate", "filingDate");
    COLUMNS.put("申请日", "filingDate");
    COLUMNS.put("patentpublicationdate", "patentPublicationDate");
    COLUMNS.put("公开日", "patentPublicationDate");
    COLUMNS.put("projectno", "projectNo");
    COLUMNS.put("项目号", "projectNo");
    COLUMNS.put("leadername", "leaderName");
    COLUMNS.put("负责人", "leaderName");
    COLUMNS.put("fundingagency", "fundingAgency");
    COLUMNS.put("资助机构", "fundingAgency");
    COLUMNS.put("projectcategory", "projectCategory");
    COLUMNS.put("项目类别", "projectCategory");
    COLUMNS.put("projectstartdate", "projectStartDate");
    COLUMNS.put("项目开始", "projectStartDate");
    COLUMNS.put("projectenddate", "projectEndDate");
    COLUMNS.put("项目结束", "projectEndDate");
    COLUMNS.put("awardname", "awardName");
    COLUMNS.put("奖项", "awardName");
    COLUMNS.put("awardyear", "awardYear");
    COLUMNS.put("获奖年份", "awardYear");
    COLUMNS.put("awardlevel", "awardLevel");
    COLUMNS.put("获奖级别", "awardLevel");
    COLUMNS.put("grantingbody", "grantingBody");
    COLUMNS.put("颁发机构", "grantingBody");
  }

  private final SourceRunConfigService sources;
  private final CoreInternalClient coreClient;
  private final ObjectMapper objectMapper;

  public ImportService(
      SourceRunConfigService sources, CoreInternalClient coreClient, ObjectMapper objectMapper) {
    this.sources = sources;
    this.coreClient = coreClient;
    this.objectMapper = objectMapper;
  }

  /** 导入结果。错误按「文件行号 + 原因」返回，行号从 1 开始且与用户在文件里看到的一致。 */
  public record ImportOutcome(
      int received,
      int accepted,
      int attached,
      int duplicate,
      int rejected,
      int invalid,
      List<Map<String, Object>> errors) {}

  public ImportOutcome importFile(
      String sourceCode, String dataNature, String filename, byte[] content) {
    if (content == null || content.length == 0) {
      throw BusinessException.invalidArgument("导入文件为空");
    }
    DataNature nature = parseNature(dataNature);
    SourceRunConfig config =
        sources
            .findByCode(sourceCode)
            .orElseThrow(() -> BusinessException.invalidArgument("来源未登记执行配置：" + sourceCode));
    if (!config.licenseApproved()) {
      throw BusinessException.businessRule("来源许可未获批准，不能导入：" + sourceCode);
    }

    List<Map<String, Object>> rows = parse(filename, content);
    if (rows.size() > MAX_ROWS) {
      throw BusinessException.invalidArgument(
          "单次导入最多 " + MAX_ROWS + " 行，实际 " + rows.size() + " 行；请拆分文件");
    }

    List<Map<String, Object>> errors = new ArrayList<>();
    List<Map<String, Object>> payloads = new ArrayList<>();
    Set<String> allowed = new LinkedHashSet<>(config.allowedFields());
    for (int index = 0; index < rows.size(); index++) {
      int line = index + 1;
      Map<String, Object> row = rows.get(index);
      try {
        Map<String, Object> payload = toPayload(row, allowed);
        String sourceRecordId = sourceRecordId(payload);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sourceRecordId", sourceRecordId);
        item.put("contentFingerprint", contentFingerprint(payload));
        item.put("payload", payload);
        item.put("dataNature", nature.name());
        item.put("sourceUpdatedAt", null);
        item.put("sourcePublishedAt", null);
        item.put("fetchedAt", Instant.now().toString());
        payloads.add(item);
      } catch (IllegalArgumentException ex) {
        errors.add(error(line, ex.getMessage()));
      }
    }

    int accepted = 0;
    int attached = 0;
    int duplicate = 0;
    int rejected = 0;
    for (int offset = 0; offset < payloads.size(); offset += HANDOFF_CHUNK) {
      List<Map<String, Object>> chunk =
          payloads.subList(offset, Math.min(offset + HANDOFF_CHUNK, payloads.size()));
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("sourceCode", config.sourceCode());
      body.put("configVersion", config.configVersion());
      // 批次键带内容指纹：同一文件重复导入会算出同一个键，core 按幂等键识别为同一批，
      // 不会因为「又导入了一次」而重复建实体。
      body.put("batchId", "import-" + Fingerprints.of(filename, String.valueOf(offset)));
      body.put("records", chunk);
      CoreInternalClient.HandoffResult result = coreClient.handoff(body);
      accepted += result.accepted();
      attached += result.attached();
      duplicate += result.duplicate();
      rejected += result.rejected();
    }

    return new ImportOutcome(
        rows.size(), accepted, attached, duplicate, rejected, errors.size(), errors);
  }

  private static Map<String, Object> error(int line, String message) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("line", line);
    item.put("message", message);
    return item;
  }

  private static DataNature parseNature(String raw) {
    if (raw == null || raw.isBlank()) {
      return DataNature.SIMULATED;
    }
    try {
      return DataNature.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw BusinessException.invalidArgument("数据性质取值必须是 REAL / SIMULATED / MIXED 之一，实际为 " + raw);
    }
  }

  /** 依据扩展名选择解析方式：.json 走 JSON 数组，其余按 CSV 处理。 */
  List<Map<String, Object>> parse(String filename, byte[] content) {
    String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
    String text = new String(content, StandardCharsets.UTF_8);
    if (text.startsWith("\uFEFF")) {
      // Excel 导出的 UTF-8 CSV 常带 BOM；不去掉会让第一列列名多一个不可见字符，
      // 表现为「表头看着对、kind 全是空」。
      text = text.substring(1);
    }
    if (name.endsWith(".json") || text.stripLeading().startsWith("[")) {
      return parseJson(text);
    }
    return parseCsv(text);
  }

  private List<Map<String, Object>> parseJson(String text) {
    try {
      JsonNode root = objectMapper.readTree(text);
      if (!root.isArray()) {
        throw BusinessException.invalidArgument("JSON 导入文件的顶层必须是数组");
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      for (JsonNode node : root) {
        if (!node.isObject()) {
          throw BusinessException.invalidArgument("JSON 数组的每一项必须是对象");
        }
        rows.add(objectMapper.convertValue(node, Map.class));
      }
      return rows;
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw BusinessException.invalidArgument("JSON 解析失败：" + ex.getMessage());
    }
  }

  private List<Map<String, Object>> parseCsv(String text) {
    List<List<String>> table = splitCsv(text);
    if (table.isEmpty()) {
      throw BusinessException.invalidArgument("CSV 文件没有内容");
    }
    List<String> header = table.get(0);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int r = 1; r < table.size(); r++) {
      List<String> cells = table.get(r);
      // 全空行跳过：Excel 另存常留下尾随空行，把它当成一条错误记录会给出无意义的行号
      boolean blank = true;
      for (String cell : cells) {
        if (cell != null && !cell.isBlank()) {
          blank = false;
          break;
        }
      }
      if (blank) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      for (int c = 0; c < header.size(); c++) {
        String key = header.get(c) == null ? "" : header.get(c).trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty() || c >= cells.size()) {
          continue;
        }
        String value = cells.get(c);
        if (value != null && !value.isBlank()) {
          row.put(key, value.trim());
        }
      }
      rows.add(row);
    }
    return rows;
  }

  /**
   * CSV 拆分。
   *
   * <p>手工实现而不引第三方库：导入只需要「逗号分隔 + 双引号包裹 + 引号内换行」这三条，
   * 引一个解析库反而要额外审一遍它的容错行为。这里对未闭合引号直接报错，不猜用户的意图。
   */
  static List<List<String>> splitCsv(String text) {
    List<List<String>> table = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    int index = 0;
    while (index < text.length()) {
      char ch = text.charAt(index);
      if (quoted) {
        if (ch == '"') {
          if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
            cell.append('"');
            index += 2;
            continue;
          }
          quoted = false;
          index++;
          continue;
        }
        cell.append(ch);
        index++;
        continue;
      }
      switch (ch) {
        case '"' -> {
          quoted = true;
          index++;
        }
        case ',' -> {
          row.add(cell.toString());
          cell.setLength(0);
          index++;
        }
        case '\r' -> index++;
        case '\n' -> {
          row.add(cell.toString());
          cell.setLength(0);
          table.add(row);
          row = new ArrayList<>();
          index++;
        }
        default -> {
          cell.append(ch);
          index++;
        }
      }
    }
    if (quoted) {
      throw BusinessException.invalidArgument("CSV 存在未闭合的双引号，无法确定行列边界");
    }
    if (cell.length() > 0 || !row.isEmpty()) {
      row.add(cell.toString());
      table.add(row);
    }
    return table;
  }

  /** 行 → 规范化记录。所有必填校验都在这里，错误信息直接指出缺哪个字段。 */
  Map<String, Object> toPayload(Map<String, Object> row, Set<String> allowed) {
    Map<String, Object> mapped = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : row.entrySet()) {
      String key = COLUMNS.get(entry.getKey().trim().toLowerCase(Locale.ROOT));
      if (key != null) {
        mapped.put(key, entry.getValue());
      }
    }

    String kind = normalizeKind(text(mapped.get("kind")));
    if (kind == null) {
      throw new IllegalArgumentException("缺少 kind（PAPER / PATENT / PROJECT / AWARD）");
    }
    if (!KINDS.contains(kind)) {
      throw new IllegalArgumentException("kind 取值非法：" + kind);
    }
    String title = text(mapped.get("title"));
    if (title == null) {
      throw new IllegalArgumentException("缺少 title");
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("kind", kind);
    payload.put("title", title);
    copyText(payload, mapped, "doi");
    copyText(payload, mapped, "venue");
    copyText(payload, mapped, "publishedDate");
    copyText(payload, mapped, "abstractText");
    copyText(payload, mapped, "sourceUrl");
    copyText(payload, mapped, "citationSource");
    copyInteger(payload, mapped, "citationCount");
    copyInteger(payload, mapped, "publicationYear");
    payload.put("authors", authors(text(mapped.get("authors"))));
    payload.put("topics", splitList(text(mapped.get("topics"))));
    String category = text(mapped.get("sourceCategory"));
    if (category != null) {
      payload.put("sourceCategory", category);
    }

    switch (kind) {
      case "PATENT" -> {
        String applicationNo = text(mapped.get("applicationNo"));
        String publicationNo = text(mapped.get("publicationNo"));
        if (applicationNo == null && publicationNo == null) {
          throw new IllegalArgumentException("专利缺少 applicationNo 或 publicationNo");
        }
        copyText(payload, mapped, "applicationNo");
        copyText(payload, mapped, "publicationNo");
        copyText(payload, mapped, "filingDate");
        copyText(payload, mapped, "patentPublicationDate");
        payload.put("ipcCodes", splitList(text(mapped.get("ipcCodes"))));
        payload.put("applicantNames", splitList(text(mapped.get("applicantNames"))));
      }
      case "PROJECT" -> {
        String projectNo = text(mapped.get("projectNo"));
        if (projectNo == null) {
          throw new IllegalArgumentException("科研项目缺少 projectNo");
        }
        copyText(payload, mapped, "projectNo");
        copyText(payload, mapped, "leaderName");
        copyText(payload, mapped, "fundingAgency");
        copyText(payload, mapped, "projectCategory");
        copyText(payload, mapped, "projectStartDate");
        copyText(payload, mapped, "projectEndDate");
      }
      case "AWARD" -> {
        Integer awardYear = integer(text(mapped.get("awardYear")));
        Integer publicationYear = integer(text(mapped.get("publicationYear")));
        if (awardYear == null && publicationYear == null) {
          throw new IllegalArgumentException("获奖缺少 awardYear（或 publicationYear）");
        }
        if (awardYear != null) {
          payload.put("awardYear", awardYear);
        }
        copyText(payload, mapped, "awardName");
        copyText(payload, mapped, "awardLevel");
        copyText(payload, mapped, "grantingBody");
        if (!payload.containsKey("awardName")) {
          payload.put("awardName", title);
        }
      }
      default -> {
        // PAPER 无专属必填字段：标题与年份已足够形成一条可展示的论文元数据
      }
    }

    if (allowed != null && !allowed.isEmpty()) {
      payload.keySet().retainAll(allowed);
      // kind / title 是 core 的定位字段，白名单里缺它们时仍必须保留，否则整条记录无法识别
      payload.put("kind", kind);
      payload.put("title", title);
    }
    return payload;
  }

  /**
   * 来源记录标识。
   *
   * <p>优先使用类型内的稳定标识（DOI / 申请号 / 项目号），这样同一成果重复导入会被 core 识别为
   * 同一条并按「追加来源」处理；没有稳定标识时退化为内容指纹，保证同一份文件重复导入幂等，
   * 但不同文件里的同一获奖不会被自动合并——这正是「没有稳定证据就不合并」的应有行为。
   */
  static String sourceRecordId(Map<String, Object> payload) {
    String kind = String.valueOf(payload.get("kind"));
    String stable =
        switch (kind) {
          case "PAPER" -> text(payload.get("doi"));
          case "PATENT" -> text(payload.get("applicationNo"));
          case "PROJECT" -> text(payload.get("projectNo"));
          default -> null;
        };
    if (stable != null) {
      return stable.toLowerCase(Locale.ROOT).replace("https://doi.org/", "");
    }
    Object authors = payload.get("authors");
    String firstAuthor = "";
    if (authors instanceof List<?> list
        && !list.isEmpty()
        && list.get(0) instanceof Map<?, ?> map) {
      Object name = map.get("name");
      firstAuthor = name == null ? "" : String.valueOf(name);
    }
    return "import-"
        + Fingerprints.of(
                kind,
                text(payload.get("title")),
                String.valueOf(payload.get("publicationYear")),
                String.valueOf(payload.get("awardYear")),
                firstAuthor)
            .substring(0, 16);
  }

  private static String contentFingerprint(Map<String, Object> payload) {
    return Fingerprints.of(
        String.valueOf(payload.get("kind")),
        String.valueOf(payload.get("title")),
        String.valueOf(payload.get("publicationYear")),
        String.valueOf(payload.get("doi")),
        String.valueOf(payload.get("applicationNo")),
        String.valueOf(payload.get("projectNo")));
  }

  /**
   * 作者列。
   *
   * <p>格式：{@code 姓名|机构;姓名|机构}。机构可省略。用竖线与分号而不是逗号，是因为 CSV 本身
   * 以逗号分隔，作者列里再出现逗号会让一列被拆成多列，而错位的列不会报错，只会静默写错字段。
   */
  static List<Map<String, Object>> authors(String raw) {
    List<Map<String, Object>> authors = new ArrayList<>();
    if (raw == null || raw.isBlank()) {
      return authors;
    }
    int order = 1;
    for (String part : raw.split(";")) {
      String entry = part.trim();
      if (entry.isEmpty()) {
        continue;
      }
      String name = entry;
      String organization = null;
      int bar = entry.indexOf('|');
      if (bar >= 0) {
        name = entry.substring(0, bar).trim();
        organization = entry.substring(bar + 1).trim();
      }
      if (name.isEmpty()) {
        continue;
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("name", name);
      view.put("order", order++);
      if (organization != null && !organization.isEmpty()) {
        view.put("organizations", List.of(organization));
      }
      authors.add(view);
    }
    return authors;
  }

  private static List<String> splitList(String raw) {
    List<String> values = new ArrayList<>();
    if (raw == null || raw.isBlank()) {
      return values;
    }
    for (String part : raw.split("[;；]")) {
      String value = part.trim();
      if (!value.isEmpty()) {
        values.add(value);
      }
    }
    return values;
  }

  private static void copyText(
      Map<String, Object> payload, Map<String, Object> mapped, String key) {
    String value = text(mapped.get(key));
    if (value != null) {
      payload.put(key, value);
    }
  }

  private static void copyInteger(
      Map<String, Object> payload, Map<String, Object> mapped, String key) {
    Integer value = integer(text(mapped.get(key)));
    if (value != null) {
      payload.put(key, value);
    }
  }

  private static Integer integer(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      int value = Integer.parseInt(raw.trim());
      if (value < 0 || value > 3000) {
        throw new IllegalArgumentException("年份或计数超出合理范围：" + raw);
      }
      return value;
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("数值字段无法解析：" + raw);
    }
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  /**
   * 类型归一。
   *
   * <p>接受中文类型名：导出文件由人工准备时，「专利 / 项目 / 获奖」比 PATENT 更不容易写错。
   * 其余取值一律按大写处理，非法值仍由后续校验拒绝——这里只做翻译，不做猜测。
   */
  static String normalizeKind(String raw) {
    if (raw == null) {
      return null;
    }
    String value = raw.trim();
    return switch (value) {
      case "论文", "期刊论文", "会议论文" -> "PAPER";
      case "专利" -> "PATENT";
      case "项目", "科研项目" -> "PROJECT";
      case "获奖", "奖项" -> "AWARD";
      default -> value.toUpperCase(Locale.ROOT);
    };
  }
}
