package com.cognitanexus.ingestion.collect.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OpenAlex 学科分层到平台领域的映射。
 *
 * <p>依据：核心 SPEC 领域归属条款、AC-IMPL-FR13-01（领域口径必须来自可复核的登记分类）。
 *
 * <p>这里守的是一条红线：<b>来源分类只能是平台领域体系里的名字</b>。一旦把 OpenAlex 的英文主题
 * 名原样放进去，领域表会同时存在「物理学」和「Physics and Astronomy」两套说法，分面、统计与
 * 领域洞察页各算各的，看起来有分类，实际无法互相核对。
 *
 * <p>同时守第二条：<b>映射不上就承认映射不上</b>。把 Engineering 硬归到机械工程，会让一批土木、
 * 电气成果顶着错误领域出现在领域榜上——错归类比不归类更难发现。
 */
class OpenAlexFieldMapperTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static OpenAlexFieldMapper mapper;

  @BeforeAll
  static void load() {
    mapper = OpenAlexFieldMapper.loadFromClasspath(JSON);
  }

  @Test
  @DisplayName("打包资源存在且带版本号")
  void resourceIsVersioned() {
    assertThat(mapper.version()).isEqualTo("openalex-map-2026-v1");
  }

  @Test
  @DisplayName("一级学科按表映射")
  void mapsField() {
    assertThat(mapper.map("Physics and Astronomy", null)).contains("物理学");
    assertThat(mapper.map("Mathematics", null)).contains("数学");
  }

  @Test
  @DisplayName("二级学科优先于一级学科")
  void subfieldWinsOverField() {
    assertThat(mapper.map("Computer Science", "Artificial Intelligence")).contains("人工智能");
    assertThat(mapper.map("Computer Science", "Software")).contains("计算机科学与技术");
  }

  @Test
  @DisplayName("过粗的一级学科不映射，宁可留空")
  void coarseFieldIsNotGuessed() {
    // Engineering 之下可能是机械、土木、电气，归到任何一个都是编造
    assertThat(mapper.map("Engineering", null)).isEmpty();
    assertThat(mapper.map("Engineering", "Some Unlisted Subfield")).isEmpty();
    assertThat(mapper.map("Chemical Engineering", null)).isEmpty();
  }

  @Test
  @DisplayName("未知分层与空值返回空，不抛异常")
  void unknownReturnsEmpty() {
    assertThat(mapper.map(null, null)).isEmpty();
    assertThat(mapper.map("", "  ")).isEmpty();
    assertThat(mapper.map(" nonexistent field ", "nonexistent subfield")).isEmpty();
  }

  @Test
  @DisplayName("大小写与首尾空白不影响匹配")
  void matchingIsLenient() {
    assertThat(mapper.map("  physics and astronomy  ", null)).contains("物理学");
    assertThat(mapper.map("computer science", "artificial intelligence")).contains("人工智能");
  }

  @Test
  @DisplayName("topics 只保留能映射的领域，并按出现顺序去重")
  void mapsTopicsInOrder() throws Exception {
    JsonNode topics =
        JSON.readTree(
            """
            [
              {"display_name":"Magnetic confinement fusion research",
               "field":{"display_name":"Physics and Astronomy"},
               "subfield":{"display_name":"Condensed Matter Physics"}},
              {"display_name":"Fusion materials",
               "field":{"display_name":"Materials Science"},
               "subfield":{"display_name":"Materials Chemistry"}},
              {"display_name":"Another AI topic",
               "field":{"display_name":"Computer Science"},
               "subfield":{"display_name":"Artificial Intelligence"}},
              {"display_name":"Yet another AI topic",
               "field":{"display_name":"Computer Science"},
               "subfield":{"display_name":"Computer Vision and Pattern Recognition"}}
            ]
            """);

    List<String> mapped = OpenAlexAdapter.mappedTopics(topics, mapper);

    // 前两个经 field 映射，后两个都归人工智能，去重后只留一次
    assertThat(mapped).containsExactly("物理学", "材料科学与工程", "人工智能");
  }

  @Test
  @DisplayName("最多保留三个领域，避免单篇成果被领域榜重复放大")
  void capsAtThreeTopics() throws Exception {
    JsonNode topics =
        JSON.readTree(
            """
            [
              {"field":{"display_name":"Physics and Astronomy"}},
              {"field":{"display_name":"Chemistry"}},
              {"field":{"display_name":"Mathematics"}},
              {"field":{"display_name":"Energy"}}
            ]
            """);

    assertThat(OpenAlexAdapter.mappedTopics(topics, mapper)).hasSize(3);
  }

  @Test
  @DisplayName("全部映射不上时返回空列表，交给规则推断")
  void unmappableTopicsAreDropped() throws Exception {
    JsonNode topics =
        JSON.readTree(
            """
            [{"display_name":"Something","field":{"display_name":"Engineering"}}]
            """);

    assertThat(OpenAlexAdapter.mappedTopics(topics, mapper)).isEmpty();
  }

  @Test
  @DisplayName("来源领域取主学科；主学科不可映射时退到 topics")
  void sourceCategoryPrefersPrimaryTopic() throws Exception {
    JsonNode primary =
        JSON.readTree(
            """
            {"display_name":"Fusion research",
             "field":{"display_name":"Physics and Astronomy"},
             "subfield":{"display_name":"Nuclear and High Energy Physics"}}
            """);
    JsonNode topics =
        JSON.readTree(
            """
        [{"field":{"display_name":"Materials Science"}}]
        """);

    assertThat(OpenAlexAdapter.sourceCategory(primary, topics, mapper)).isEqualTo("物理学");
  }

  @Test
  @DisplayName("主学科不可映射且 topics 也不可映射时，来源领域为空")
  void sourceCategoryIsNullWhenNothingMaps() throws Exception {
    JsonNode primary =
        JSON.readTree(
            """
            {"field":{"display_name":"Engineering"}}
            """);
    JsonNode topics =
        JSON.readTree(
            """
        [{"field":{"display_name":"Engineering"}}]
        """);

    assertThat(OpenAlexAdapter.sourceCategory(primary, topics, mapper)).isNull();
  }
}
