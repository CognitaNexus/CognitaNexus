package com.cognitanexus.core.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cognitanexus.core.catalog.domain.AchievementDetails.Award;
import com.cognitanexus.core.catalog.domain.AchievementDetails.Paper;
import com.cognitanexus.core.catalog.domain.AchievementDetails.Patent;
import com.cognitanexus.core.catalog.domain.AchievementDetails.ResearchProject;
import com.cognitanexus.core.catalog.domain.Contribution.Role;
import com.cognitanexus.core.catalog.domain.FieldProvenance.ValueLevel;
import com.cognitanexus.core.catalog.domain.Scholar.ClaimedStatus;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.RelationState;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 目录域值对象的语义约束。
 *
 * <p>依据：架构 V0.2 §5.3/§5.4、BR-IMP-04、BR-IMP-08、AC-IMPL-FR10-01。
 *
 * <p>这一组用例覆盖的是「看起来只是枚举、其实承载业务红线」的几处取值。它们此前没有任何用例：
 * 类型安全的枚举很容易被当成「编译器会拦住的东西」，但这些枚举上的<b>判断方法</b>（谁能公开、
 * 什么值可以被覆盖、哪种关系可以建边）恰恰是红线落地的地方，写反了不会有编译错误，只会静默
 * 放宽约束。
 */
class CatalogDomainSemanticsTest {

  private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");

  @Nested
  @DisplayName("生效状态：公开资格只有 EFFECTIVE 一种")
  class EffectiveStatusSemantics {

    @Test
    @DisplayName("待治理与已撤回都不得进入公开视图")
    void onlyEffectiveIsPubliclyEligible() {
      assertThat(EffectiveStatus.of("EFFECTIVE").publiclyEligible()).isTrue();
      assertThat(EffectiveStatus.PENDING_GOVERNANCE.publiclyEligible()).isFalse();
      // 撤回后保留历史与来源归属，但不得继续对外出现。
      assertThat(EffectiveStatus.WITHDRAWN.publiclyEligible()).isFalse();
    }

    @Test
    @DisplayName("未知状态取值直接抛错，不静默落回某个默认状态")
    void unknownStatusRejected() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> EffectiveStatus.of("DRAFT"))
          .withMessageContaining("未知生效状态");
      assertThatIllegalArgumentException().isThrownBy(() -> EffectiveStatus.of(null));
    }
  }

  @Nested
  @DisplayName("年份精度：允许「年份与精度同时为空」，不允许单边有值")
  class YearPrecisionSemantics {

    @Test
    @DisplayName("null 是合法输入，表示来源未给出年份")
    void nullRawMeansNoPrecision() {
      assertThat(YearPrecision.of((String) null)).isNull();
    }

    @Test
    @DisplayName("YEAR 与 UNKNOWN 可由字符串还原，未知取值抛错")
    void knownValuesRoundTrip() {
      assertThat(YearPrecision.of("YEAR")).isEqualTo(YearPrecision.YEAR);
      assertThat(YearPrecision.of("UNKNOWN")).isEqualTo(YearPrecision.UNKNOWN);
      assertThatIllegalArgumentException()
          .isThrownBy(() -> YearPrecision.of("MONTH"))
          .withMessageContaining("未知年份精度");
    }
  }

  @Nested
  @DisplayName("标识种类：归一列名提示必须与实际扩展列一一对应且不重名")
  class IdentifierKindSemantics {

    @Test
    @DisplayName("五种种类的列名提示固定，重名会让排查标识与扩展列同步时指错地方")
    void columnNameHints() {
      assertThat(
              Arrays.stream(IdentifierKind.values()).map(IdentifierKind::columnNameHint).toList())
          .containsExactly("doi", "patent_application_no", "project_no", "orcid", "openalex_id")
          .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("未知种类抛错，避免把无法归一的标识写进注册表")
    void unknownKindRejected() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> IdentifierKind.of("ISBN"))
          .withMessageContaining("未知标识种类");
    }

    @Test
    @DisplayName("已知种类可由字符串还原，含学者类标识（ORCID 与 OpenAlex）")
    void knownKindsRoundTrip() {
      assertThat(IdentifierKind.of("DOI")).isEqualTo(IdentifierKind.DOI);
      assertThat(IdentifierKind.of("PATENT_APPLICATION_NO"))
          .isEqualTo(IdentifierKind.PATENT_APPLICATION_NO);
      assertThat(IdentifierKind.of("PROJECT_NO")).isEqualTo(IdentifierKind.PROJECT_NO);
      assertThat(IdentifierKind.of("ORCID")).isEqualTo(IdentifierKind.ORCID);
      assertThat(IdentifierKind.of("OPENALEX_ID")).isEqualTo(IdentifierKind.OPENALEX_ID);
    }
  }

  @Nested
  @DisplayName("参与关系：只有 AUTO 与 SELF_CONFIRMED 可参与确定展示与建边")
  class ContributionSemantics {

    private Contribution contribution(RelationState state, Integer authorOrder) {
      return new Contribution(
          "ctr_1", "sch_1", "ach_1", "AUTHOR", authorOrder, state, null, 1L, NOW, NOW);
    }

    @Test
    @DisplayName("待核验、争议、已排除一律不构成确定关系（BR-IMP-08）")
    void onlyQualifiedStatesBuildGraph() {
      assertThat(contribution(RelationState.AUTO, 1).qualifiedForGraph()).isTrue();
      assertThat(contribution(RelationState.SELF_CONFIRMED, 2).qualifiedForGraph()).isTrue();
      assertThat(contribution(RelationState.PENDING_VERIFY, null).qualifiedForGraph()).isFalse();
      assertThat(contribution(RelationState.DISPUTED, null).qualifiedForGraph()).isFalse();
      assertThat(contribution(RelationState.EXCLUDED, null).qualifiedForGraph()).isFalse();
    }

    @Test
    @DisplayName("作者序缺失保持 null，不用 0 冒充「第一作者」")
    void missingAuthorOrderStaysNull() {
      assertThat(contribution(RelationState.AUTO, null).authorOrder()).isNull();
      assertThat(contribution(RelationState.AUTO, 1).authorOrder()).isEqualTo(1);
    }

    @Test
    @DisplayName("五种参与角色可由字符串还原，未知角色抛错")
    void rolesRoundTrip() {
      assertThat(Role.of("AUTHOR")).isEqualTo(Role.AUTHOR);
      assertThat(Role.of("INVENTOR")).isEqualTo(Role.INVENTOR);
      assertThat(Role.of("LEADER")).isEqualTo(Role.LEADER);
      assertThat(Role.of("PARTICIPANT")).isEqualTo(Role.PARTICIPANT);
      assertThat(Role.of("AWARDEE")).isEqualTo(Role.AWARDEE);
      assertThatIllegalArgumentException()
          .isThrownBy(() -> Role.of("REVIEWER"))
          .withMessageContaining("未知参与角色");
    }
  }

  @Nested
  @DisplayName("字段溯源：只有 SOURCE 级可被来源覆盖，人工值与已审核值一律保留")
  class FieldProvenanceSemantics {

    private FieldProvenance provenance(ValueLevel level) {
      return new FieldProvenance("prv_1", "ACHIEVEMENT", "ach_1", "title", level, "某标题", "srec_1");
    }

    @Test
    @DisplayName("人工值与已审核值不允许被自动更新覆盖（BR-IMP-04）")
    void onlySourceLevelAllowsOverwrite() {
      assertThat(provenance(ValueLevel.SOURCE).allowsSourceOverwrite()).isTrue();
      assertThat(provenance(ValueLevel.MANUAL).allowsSourceOverwrite()).isFalse();
      assertThat(provenance(ValueLevel.REVIEWED).allowsSourceOverwrite()).isFalse();
    }

    @Test
    @DisplayName("值级别可由字符串还原，未知级别抛错")
    void levelsRoundTrip() {
      assertThat(ValueLevel.of("SOURCE")).isEqualTo(ValueLevel.SOURCE);
      assertThat(ValueLevel.of("MANUAL")).isEqualTo(ValueLevel.MANUAL);
      assertThat(ValueLevel.of("REVIEWED")).isEqualTo(ValueLevel.REVIEWED);
      assertThatIllegalArgumentException()
          .isThrownBy(() -> ValueLevel.of("DERIVED"))
          .withMessageContaining("未知值级别");
    }
  }

  @Nested
  @DisplayName("学者：重定向判定与认领状态")
  class ScholarSemantics {

    private Scholar scholar(String mergedIntoId) {
      return new Scholar(
          "sch_1",
          "张三",
          "张三",
          DataNature.REAL,
          ClaimedStatus.UNCLAIMED,
          "北京航空航天大学",
          null,
          mergedIntoId,
          3L,
          NOW,
          NOW);
    }

    @Test
    @DisplayName("存在重定向即为已被合并，旧链接必须跳到新实体")
    void mergedFlagFollowsRedirect() {
      assertThat(scholar(null).merged()).isFalse();
      assertThat(scholar("sch_9").merged()).isTrue();
    }

    @Test
    @DisplayName("改认领状态返回新实例：其余字段保持，原实例不被改写")
    void withClaimedStatusIsCopyOnWrite() {
      Scholar original = scholar(null);
      Scholar claimed = original.withClaimedStatus(ClaimedStatus.CLAIMED);

      assertThat(claimed).isNotSameAs(original);
      assertThat(original.claimedStatus()).isEqualTo(ClaimedStatus.UNCLAIMED);
      assertThat(claimed.claimedStatus()).isEqualTo(ClaimedStatus.CLAIMED);
      assertThat(claimed.id()).isEqualTo(original.id());
      assertThat(claimed.displayName()).isEqualTo(original.displayName());
      assertThat(claimed.dataNature()).isEqualTo(original.dataNature());
      assertThat(claimed.organizationName()).isEqualTo(original.organizationName());
      assertThat(claimed.version()).isEqualTo(original.version());
      assertThat(claimed.mergedIntoId()).isNull();
    }

    @Test
    @DisplayName("四种认领状态可还原，未知状态抛错")
    void claimedStatusesRoundTrip() {
      assertThat(ClaimedStatus.of("UNCLAIMED")).isEqualTo(ClaimedStatus.UNCLAIMED);
      assertThat(ClaimedStatus.of("CLAIM_PENDING")).isEqualTo(ClaimedStatus.CLAIM_PENDING);
      assertThat(ClaimedStatus.of("CLAIMED")).isEqualTo(ClaimedStatus.CLAIMED);
      assertThat(ClaimedStatus.of("CLAIM_REJECTED")).isEqualTo(ClaimedStatus.CLAIM_REJECTED);
      assertThatIllegalArgumentException()
          .isThrownBy(() -> ClaimedStatus.of("VERIFIED"))
          .withMessageContaining("未知认领状态");
    }
  }

  @Nested
  @DisplayName("原始来源版本：交接键稳定，抓取时刻不参与指纹")
  class SourceRecordSemantics {

    private SourceRecord record(Instant fetchedAt) {
      return new SourceRecord(
          "srec_1",
          "crossref",
          "10.1000/xyz",
          "fp_abc",
          "{}",
          DataNature.REAL,
          NOW,
          LocalDate.of(2024, 5, 3),
          fetchedAt,
          NOW,
          12695L);
    }

    @Test
    @DisplayName("交接键为 来源|来源记录|内容指纹，三段都参与")
    void handoffKeyComposition() {
      assertThat(record(NOW).handoffKey()).isEqualTo("crossref|10.1000/xyz|fp_abc");
    }

    @Test
    @DisplayName("仅抓取时刻不同时交接键相同：否则每次重试都会被当成新事实")
    void fetchedAtIsNotPartOfIdentity() {
      SourceRecord later = record(NOW.plusSeconds(3600));
      assertThat(later.handoffKey()).isEqualTo(record(NOW).handoffKey());
      assertThat(later).isNotEqualTo(record(NOW));
    }
  }

  @Nested
  @DisplayName("成果类型扩展：四类并列定义在一个容器里，互斥且各自独立")
  class AchievementDetailsSemantics {

    @Test
    @DisplayName("容器类不可实例化：它只是四类扩展的并列位置，没有实例语义")
    void containerIsNotInstantiable() {
      assertThat(AchievementDetails.class.getDeclaredConstructors()).hasSize(1);
      assertThat(
              Modifier.isPrivate(
                  AchievementDetails.class.getDeclaredConstructors()[0].getModifiers()))
          .isTrue();
    }

    @Test
    @DisplayName("四类扩展各自可构造，同一成果不会在两种类型下相等")
    void fourExtensionTypesAreDistinct() {
      Paper paper =
          new Paper(
              "ach_1",
              "10.1/x",
              "10.1/x",
              "某期刊",
              LocalDate.of(2024, 3, 1),
              "NONE",
              12,
              "CROSSREF",
              null);
      Patent patent =
          new Patent(
              "ach_1", "CN2024..", "cn2024", "CN1", List.of("G06F"), List.of("某公司"), null, null);
      ResearchProject project =
          new ResearchProject("ach_1", "NSFC-1", "nsfc-1", "张三", "国家自然科学基金", "面上", null, null);
      Award award = new Award("ach_1", "某奖", 2024, "国家级", "某机构");

      assertThat(List.of(paper, patent, project, award)).doesNotHaveDuplicates();
      assertThat(paper).isNotEqualTo(patent);
      assertThat(paper.achievementId()).isEqualTo("ach_1");
      assertThat(patent.ipcCodes()).containsExactly("G06F");
      assertThat(project.fundingAgency()).isEqualTo("国家自然科学基金");
      assertThat(award.awardYear()).isEqualTo(2024);
    }
  }
}
