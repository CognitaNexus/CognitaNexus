package com.cognitanexus.core.taxonomy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.core.taxonomy.domain.TopicClassifier.Candidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 领域规则分类器。
 *
 * <p>用一份刻意做小的词表（三个领域、几个判别词）来测，而不是拿随包词表测：随包词表改一个权重
 * 就会让断言全红，那种测试只会训练人忽略红灯。这里要固定的是<b>规则本身</b>——阈值、次位领域的
 * 取舍、摘要折减、并列时的确定性——这些才是改代码时真正可能被改坏的东西。
 */
class TopicClassifierTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String FIELD_甲 =
      """
      {"canonicalName":"甲","terms":[{"term":"alpha","weight":5},{"term":"beta","weight":2},
       {"term":"zeta","weight":5},{"term":"alpha beta","weight":5},{"term":"omega","weight":5}]}
      """;
  private static final String FIELD_乙 =
      """
      {"canonicalName":"乙","terms":[{"term":"gamma","weight":4},{"term":"omega","weight":5}]}
      """;
  private static final String FIELD_丙 =
      """
      {"canonicalName":"丙","terms":[{"term":"epsilon","weight":5}]}
      """;

  private TopicClassifier classifier;

  @BeforeEach
  void setUp() throws IOException {
    classifier = new TopicClassifier(taxonomy(FIELD_甲 + "," + FIELD_乙 + "," + FIELD_丙));
  }

  private static AcademicTaxonomy taxonomy(String fieldsJson) throws IOException {
    return AcademicTaxonomy.fromJson(
        MAPPER.readTree(
            """
            {"version":"t","matchMode":"token-prefix","minScore":4,"secondaryRatio":0.5,
             "titleWeight":1,"abstractWeight":0.5,"fields":[%s]}
            """
                .formatted(fieldsJson)));
  }

  private static List<String> names(List<TopicClassification> got) {
    return got.stream().map(TopicClassification::fieldName).toList();
  }

  @Nested
  @DisplayName("阈值")
  class Threshold {

    @Test
    void singleStrongTermInTitleIsEnough() {
      List<TopicClassification> got = classifier.classify("Alpha Method for Imaging", null, null);
      assertThat(names(got)).containsExactly("甲");
      assertThat(got.get(0).score()).isEqualTo(5.0);
      assertThat(got.get(0).confidence()).isBetween(0.01, 0.99);
    }

    @Test
    void weakEvidenceYieldsNothingRatherThanAGuess() {
      // beta 权重 2，低于阈值 4。SRS 5.3：不强行归类，返回空就是如实回答"不知道"。
      assertThat(classifier.classify("Beta Study", null, null)).isEmpty();
    }

    @Test
    void rankStillExposesBelowThresholdCandidatesForExplainingWhy() {
      // 待分类清单要能回答"为什么判不出"，所以打分排序结果必须可见
      List<Candidate> ranked = classifier.rank("Beta Study", null, null);
      assertThat(ranked).hasSize(1);
      assertThat(ranked.get(0).fieldName()).isEqualTo("甲");
      assertThat(ranked.get(0).score()).isEqualTo(2.0);
    }

    @Test
    void noTextAtAll() {
      assertThat(classifier.classify(null, null, null)).isEmpty();
      assertThat(classifier.classify("   ", " ", "  ")).isEmpty();
      assertThat(classifier.rank(null, null, null)).isEmpty();
    }

    @Test
    void noTermHitAtAll() {
      assertThat(classifier.classify("Unrelated Title Words", null, null)).isEmpty();
      assertThat(classifier.rank("Unrelated Title Words", null, null)).isEmpty();
    }
  }

  @Nested
  @DisplayName("题名、期刊与摘要的权重")
  class Weights {

    @Test
    void venueCountsAsMuchAsTitle() {
      List<TopicClassification> got = classifier.classify(null, "Alpha Journal", null);
      assertThat(names(got)).containsExactly("甲");
      assertThat(got.get(0).score()).isEqualTo(5.0);
    }

    @Test
    void evidenceDistinguishesTitleFromVenue() {
      // 实测中出现过证据写着 title 命中、而那个词其实来自期刊名的情况。
      // 证据是复核分类的依据，写错位置等于毁掉追溯链。
      assertThat(classifier.classify("Alpha", null, null).get(0).evidence())
          .contains("title:alpha=5.0");
      assertThat(classifier.classify(null, "Alpha Journal", null).get(0).evidence())
          .contains("venue:alpha=5.0");
    }

    @Test
    void venueIsNotConsultedWhenTitleAlreadyMatched() {
      // 同一条判别词只贡献一次得分，多处出现不叠加
      List<TopicClassification> got = classifier.classify("Alpha", "Alpha Journal", null);
      assertThat(got.get(0).score()).isEqualTo(5.0);
      assertThat(got.get(0).evidence()).contains("命中 1 项");
    }

    @Test
    void abstractOnlyHitIsHalved() {
      assertThat(classifier.rank(null, null, "alpha"))
          .singleElement()
          .satisfies(candidate -> assertThat(candidate.score()).isEqualTo(2.5));
      // 折半后低于阈值：只出现在摘要里的证据不足以支撑一条归属
      assertThat(classifier.classify(null, null, "alpha")).isEmpty();
    }

    @Test
    void hittingBothDeclaredAndAbstractCountsOnce() {
      // 同一条判别词在题名与摘要都出现时只贡献一次，而且是按题名的全量权重计
      List<TopicClassification> got = classifier.classify("Alpha", null, "alpha alpha");
      assertThat(got.get(0).score()).isEqualTo(5.0);
    }
  }

  @Nested
  @DisplayName("次位领域的取舍")
  class SecondaryField {

    @Test
    void closeSecondFieldIsKept() {
      List<TopicClassification> got = classifier.classify("alpha gamma", null, null);
      // 甲 5 分，乙 4 分；下限 = max(阈值 4, 5*0.5) = 4，恰好够
      assertThat(names(got)).containsExactly("甲", "乙");
      assertThat(got.get(0).confidence()).isGreaterThan(got.get(1).confidence());
    }

    @Test
    void distantSecondFieldIsDropped() {
      List<TopicClassification> got = classifier.classify("alpha zeta gamma", null, null);
      // 甲 10 分，乙 4 分；下限 = max(4, 5) = 5，4 分不够
      assertThat(names(got)).containsExactly("甲");
    }

    @Test
    void atMostTwoFields() {
      List<TopicClassification> got = classifier.classify("alpha gamma epsilon", null, null);
      assertThat(got).hasSizeLessThanOrEqualTo(2);
    }
  }

  @Nested
  @DisplayName("确定性")
  class Determinism {

    @Test
    void tieIsBrokenByFieldNameNotByVocabularyOrder() throws IOException {
      String tieFieldA = "{\"canonicalName\":\"甲\",\"terms\":[{\"term\":\"omega\",\"weight\":5}]}";
      String tieFieldB = "{\"canonicalName\":\"乙\",\"terms\":[{\"term\":\"omega\",\"weight\":5}]}";
      TopicClassifier forward = new TopicClassifier(taxonomy(tieFieldA + "," + tieFieldB));
      TopicClassifier reversed = new TopicClassifier(taxonomy(tieFieldB + "," + tieFieldA));
      assertThat(names(forward.classify("omega", null, null)))
          .isEqualTo(names(reversed.classify("omega", null, null)));
    }

    @Test
    void repeatedRunsAgree() {
      List<TopicClassification> first = classifier.classify("alpha gamma", "beta", "zeta");
      List<TopicClassification> second = classifier.classify("alpha gamma", "beta", "zeta");
      assertThat(first).isEqualTo(second);
    }
  }

  @Nested
  @DisplayName("匹配细节")
  class Matching {

    @Test
    void hyphenatedTitleMatchesMultiWordTerm() {
      assertThat(names(classifier.classify("Alpha-Beta Study", null, null))).containsExactly("甲");
    }

    @Test
    void multiWordTermToleratesInflectionOnLastWord() {
      // "Alpha Betas" 的末词是 betas，前缀匹配应当命中 alpha beta
      assertThat(classifier.rank("Alpha Betas", null, null))
          .anySatisfy(candidate -> assertThat(candidate.fieldName()).isEqualTo("甲"));
    }

    @Test
    void shortTermDoesNotMatchInsideUnrelatedWord() throws IOException {
      // 实测过的失效形态：rna 在 internal 里命中
      TopicClassifier shortClassifier =
          new TopicClassifier(
              taxonomy("{\"canonicalName\":\"甲\",\"terms\":[{\"term\":\"rna\",\"weight\":5}]}"));
      assertThat(shortClassifier.classify("Internal Revenue Report", null, null)).isEmpty();
      assertThat(names(shortClassifier.classify("RNA Sequencing", null, null)))
          .containsExactly("甲");
    }

    @Test
    void evidenceNamesWhereAndWhat() {
      List<TopicClassification> got = classifier.classify("alpha method", null, null);
      assertThat(got.get(0).evidence()).contains("命中 1 项").contains("title:alpha=5.0");
    }

    @Test
    void evidenceIsCappedSoItStaysReadable() throws IOException {
      // 一份把命中项全部回填的证据串会长到无法阅读，也会白白撑大数据库
      StringBuilder terms = new StringBuilder();
      StringBuilder abstractText = new StringBuilder();
      for (int i = 1; i <= 12; i++) {
        if (i > 1) {
          terms.append(',');
        }
        String term = "term%02d".formatted(i);
        terms.append("{\"term\":\"").append(term).append("\",\"weight\":5}");
        abstractText.append(term).append(' ');
      }
      TopicClassifier wide =
          new TopicClassifier(taxonomy("{\"canonicalName\":\"甲\",\"terms\":[" + terms + "]}"));
      List<Candidate> ranked = wide.rank(null, null, abstractText.toString());
      assertThat(ranked.get(0).evidence()).contains("命中 12 项").contains("另有 4 项");
    }
  }
}
