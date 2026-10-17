package com.cognitanexus.core.taxonomy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 学术领域受控词表。
 *
 * <p>两部分：一份是"随包发布的词表本身必须可用"——它直接决定全部领域分类的质量，写坏了的
 * 表现是"什么都判不出"或"都判成同一个领域"，都不容易被肉眼发现；另一部分是解析期的拒绝规则。
 */
class AcademicTaxonomyTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static AcademicTaxonomy bundled() throws IOException {
    try (InputStream in =
        new ClassPathResource("taxonomy/academic-fields.json").getInputStream()) {
      return AcademicTaxonomy.load(in, MAPPER);
    }
  }

  private static AcademicTaxonomy fromJson(String json) throws IOException {
    return AcademicTaxonomy.fromJson(MAPPER.readTree(json));
  }

  private static String withFields(String fieldsJson) {
    return """
        {"version":"t","matchMode":"token-prefix","minScore":4,"secondaryRatio":0.5,
         "titleWeight":1,"abstractWeight":0.5,"fields":[%s]}
        """
        .formatted(fieldsJson);
  }

  @Nested
  @DisplayName("随包词表")
  class Bundled {

    @Test
    void loadsAndCoversEnoughDisciplines() throws IOException {
      AcademicTaxonomy taxonomy = bundled();
      assertThat(taxonomy.version()).isNotBlank();
      // 少于这个数量说明词表被误删过；学科门类本来就该有十几个
      assertThat(taxonomy.fields()).hasSizeGreaterThanOrEqualTo(15);
      assertThat(taxonomy.minScore()).isPositive();
      assertThat(taxonomy.describe()).contains(taxonomy.version());
    }

    @Test
    void everyTermHasUsableNeedleAndWeight() throws IOException {
      AcademicTaxonomy taxonomy = bundled();
      for (AcademicTaxonomy.Field field : taxonomy.fields()) {
        assertThat(field.canonicalName()).isNotBlank();
        assertThat(field.terms()).isNotEmpty();
        for (AcademicTaxonomy.Term term : field.terms()) {
          assertThat(term.text()).isNotBlank();
          assertThat(term.needle()).isNotBlank();
          assertThat(term.weight()).isPositive();
          if (MatchText.isAscii(term.needle())) {
            // ASCII 判别词的查找串必须前置空格，否则退化为子串匹配
            assertThat(term.needle()).startsWith(" ");
          }
        }
      }
    }

    @Test
    void ruleVersionCarriesMatchModeSoRecomputeTracksBoth() throws IOException {
      // 词表版本进 core_achievement_topic.rule_version，换版本即可整批重算。
      // 匹配模式一并进版本号：换匹配算法同样会让全部结论变化，只锁词表版本会漏掉这一种。
      assertThat(bundled().ruleVersion()).isEqualTo(bundled().version() + "/token-prefix");
    }
  }

  @Nested
  @DisplayName("解析期拒绝")
  class Rejects {

    @Test
    void missingVersion() {
      assertThatThrownBy(
              () ->
                  fromJson(
                      """
                      {"matchMode":"token-prefix","minScore":4,"secondaryRatio":0.5,
                       "titleWeight":1,"abstractWeight":0.5,
                       "fields":[{"canonicalName":"甲","terms":[{"term":"a","weight":1}]}]}
                      """))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("version");
    }

    @Test
    void unknownMatchModeIsRefusedRatherThanSilentlyUsed() {
      assertThatThrownBy(
              () ->
                  fromJson(
                      """
                      {"version":"t","matchMode":"substring","minScore":4,"secondaryRatio":0.5,
                       "titleWeight":1,"abstractWeight":0.5,
                       "fields":[{"canonicalName":"甲","terms":[{"term":"a","weight":1}]}]}
                      """))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("matchMode");
    }

    @Test
    void emptyFieldList() {
      assertThatThrownBy(() -> fromJson(withFields("")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("没有定义任何领域");
    }

    @Test
    void duplicateFieldName() throws IOException {
      String field = "{\"canonicalName\":\"甲\",\"terms\":[{\"term\":\"a\",\"weight\":1}]}";
      assertThatThrownBy(() -> fromJson(withFields(field + "," + field)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("重名领域");
    }

    @Test
    void duplicateTermWithinFieldIsCaughtEvenAfterNormalization() throws IOException {
      // 两条只在大小写/连字符上不同的判别词会重复计分，等于悄悄放大该领域的权重
      String field =
          "{\"canonicalName\":\"甲\",\"terms\":[{\"term\":\"Alpha\",\"weight\":1},"
              + "{\"term\":\"alpha\",\"weight\":1}]}";
      assertThatThrownBy(() -> fromJson(withFields(field)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("重复");
    }

    @Test
    void fieldWithoutTerms() {
      assertThatThrownBy(
              () -> fromJson(withFields("{\"canonicalName\":\"甲\",\"terms\":[]}")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("没有定义任何判别词");
    }

    @Test
    void nonPositiveWeight() {
      assertThatThrownBy(
              () ->
                  fromJson(
                      withFields(
                          "{\"canonicalName\":\"甲\",\"terms\":[{\"term\":\"a\",\"weight\":0}]}")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("weight");
    }

    @Test
    void ratioAboveOne() throws IOException {
      JsonNode root =
          MAPPER.readTree(
              """
              {"version":"t","matchMode":"token-prefix","minScore":4,"secondaryRatio":1.5,
               "titleWeight":1,"abstractWeight":0.5,
               "fields":[{"canonicalName":"甲","terms":[{"term":"a","weight":1}]}]}
              """);
      assertThatThrownBy(() -> AcademicTaxonomy.fromJson(root))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("secondaryRatio");
    }

    @Test
    void termThatNormalizesToNothing() {
      // 例如只有标点：规范化后为空串，会成为一条永远命中不了的判别词
      assertThatThrownBy(
              () ->
                  fromJson(
                      withFields(
                          "{\"canonicalName\":\"甲\",\"terms\":[{\"term\":\"---\",\"weight\":1}]}")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("规范化后为空");
    }
  }

  @Nested
  @DisplayName("查询")
  class Lookup {

    @Test
    void termsOfReturnsFieldTermsAndEmptyForUnknown() throws IOException {
      AcademicTaxonomy taxonomy = bundled();
      String first = taxonomy.fields().get(0).canonicalName();
      assertThat(taxonomy.termsOf(first)).isNotEmpty();
      assertThat(taxonomy.termsOf("不存在的领域")).isEmpty();
    }
  }
}
