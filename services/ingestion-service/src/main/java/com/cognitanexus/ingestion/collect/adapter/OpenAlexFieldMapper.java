package com.cognitanexus.ingestion.collect.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * OpenAlex 学科分层到平台学术领域的映射。
 *
 * <p>依据：架构 V0.2 §6.2、核心 SPEC 关于领域归属的口径。OpenAlex 返回的 field/subfield 是英文
 * 学科名（如 "Physics and Astronomy"），而平台领域体系固定为 18 个中文领域。若把英文名直接当作
 * 领域名写入，领域表会被几千个英文概念撑开，与已治理的领域体系并存成两套互不相通的分面——这比
 * 没有分类更糟，因为它看起来有分类。
 *
 * <p>因此这里只做一件事：把来源给出的学科名翻成平台领域名。翻不出来就返回空，交给 core 的规则
 * 推断在成果正文上判定。宁可标「待分类」，不强行归类。
 *
 * <p>映射表是数据资产（{@code resources/taxonomy/openalex-field-map.json}），改词表不需要改代码；
 * 但版本号必须同时提升，否则同一份数据在不同版本下会得到不同领域而无从区分。
 */
public final class OpenAlexFieldMapper {

  private static final String RESOURCE = "/taxonomy/openalex-field-map.json";

  /** subfield 比 field 细，命中率优先：先查 subfield，再回落到 field。 */
  private final Map<String, String> subfields;

  private final Map<String, String> fields;

  private final String version;

  OpenAlexFieldMapper(Map<String, String> fields, Map<String, String> subfields, String version) {
    this.fields = fields;
    this.subfields = subfields;
    this.version = version;
  }

  /**
   * 从 classpath 加载映射表。
   *
   * <p>缺失或损坏时直接抛异常让服务起不来：映射表是打包进 jar 的静态资产，它不在场只可能是构建
   * 出错。若静默降级为「全部不映射」，采集会看起来正常、领域归属却整批丢失。
   */
  public static OpenAlexFieldMapper loadFromClasspath(ObjectMapper objectMapper) {
    try (InputStream stream = OpenAlexFieldMapper.class.getResourceAsStream(RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("缺少领域映射资源：" + RESOURCE);
      }
      JsonNode root = objectMapper.readTree(stream);
      return new OpenAlexFieldMapper(
          readMap(root.path("fields")), readMap(root.path("subfields")), text(root, "version"));
    } catch (IOException ex) {
      throw new IllegalStateException("读取领域映射资源失败：" + RESOURCE, ex);
    }
  }

  private static Map<String, String> readMap(JsonNode node) {
    Map<String, String> map = new LinkedHashMap<>();
    if (node != null && node.isObject()) {
      node.fields()
          .forEachRemaining(
              entry -> {
                String key = normalizeKey(entry.getKey());
                String value = entry.getValue().asText("");
                if (key != null && !value.isBlank()) {
                  map.put(key, value.trim());
                }
              });
    }
    return map;
  }

  /**
   * 映射一个 OpenAlex 分层到平台领域。
   *
   * @param fieldName OpenAlex field，例如 "Engineering"
   * @param subfieldName OpenAlex subfield，例如 "Mechanical Engineering"，可为 null
   * @return 平台领域规范名；映射不上时返回空
   */
  public Optional<String> map(String fieldName, String subfieldName) {
    String subfield = normalizeKey(subfieldName);
    if (subfield != null) {
      String hit = subfields.get(subfield);
      if (hit != null) {
        return Optional.of(hit);
      }
    }
    String field = normalizeKey(fieldName);
    if (field != null) {
      String hit = fields.get(field);
      if (hit != null) {
        return Optional.of(hit);
      }
    }
    return Optional.empty();
  }

  public String version() {
    return version;
  }

  /** 大小写与首尾空白不影响匹配；空串视为缺失而不是一个键。 */
  private static String normalizeKey(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    return trimmed.isEmpty() ? null : trimmed.toLowerCase(java.util.Locale.ROOT);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.path(field);
    return value == null || value.isMissingNode() || value.isNull() ? null : value.asText();
  }
}
