package com.cognitanexus.core.taxonomy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.taxonomy.application.TopicInferenceRepository.InferenceRecord;
import com.cognitanexus.core.taxonomy.application.TopicInferenceRepository.KindCount;
import com.cognitanexus.core.taxonomy.application.TopicInferenceRepository.OutcomeCount;
import com.cognitanexus.core.taxonomy.application.TopicInferenceRepository.PendingAchievement;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 领域推断仓储适配器。
 *
 * <p>这一层是纯粹的转调，但它最典型的故障恰恰是"转错了"：参数顺序颠倒、置信度与条数互换、
 * UNRESOLVED 也带上了置信度。这类错误不会抛异常，只会让台账里的数字静默地换位。因此逐项核对
 * 调用参数，而不是只断言"方法被调用过"。
 */
@ExtendWith(MockitoExtension.class)
class TaxonomyRepositoryAdapterTest {

  private static final String RULE_VERSION = "af-2026-v2/token-prefix";

  @Mock TaxonomyMapper mapper;

  private TaxonomyRepositoryAdapter adapter() {
    return new TaxonomyRepositoryAdapter(mapper);
  }

  @Test
  @DisplayName("推断归属一律以 INFERRED 落库，并带上版本、置信度与证据")
  void linkInferredCarriesProvenance() {
    when(mapper.linkInferred(anyString(), anyString(), anyString(), any(), anyString()))
        .thenReturn(1);

    int affected =
        adapter()
            .linkInferred(
                "ach_1",
                "top_ai",
                RULE_VERSION,
                BigDecimal.valueOf(0.769),
                "命中 2 项：title:graph neural=5.0");

    assertThat(affected).isEqualTo(1);
    verify(mapper)
        .linkInferred(
            eq("ach_1"),
            eq("top_ai"),
            eq(RULE_VERSION),
            eq(BigDecimal.valueOf(0.769)),
            eq("命中 2 项：title:graph neural=5.0"));
  }

  @Test
  @DisplayName("台账写入把结果、条数、置信度按位传递，UNRESOLVED 不带置信度")
  void upsertInferencePassesFieldsInOrder() {
    adapter().upsertInference("ach_2", RULE_VERSION, "UNRESOLVED", 0, null, "最高得分 2.0 低于阈值 4.0");

    verify(mapper)
        .upsertInference(
            eq("ach_2"),
            eq(RULE_VERSION),
            eq("UNRESOLVED"),
            eq(0),
            isNull(),
            eq("最高得分 2.0 低于阈值 4.0"));

    adapter()
        .upsertInference("ach_3", RULE_VERSION, "ASSIGNED", 2, BigDecimal.valueOf(0.486), "命中 2 项");

    verify(mapper)
        .upsertInference(
            eq("ach_3"),
            eq(RULE_VERSION),
            eq("ASSIGNED"),
            eq(2),
            eq(BigDecimal.valueOf(0.486)),
            eq("命中 2 项"));
  }

  @Test
  @DisplayName("待推断查询按批大小与规则版本传递，计数查询走同一版本")
  void pendingQueriesDelegate() {
    when(mapper.listPending(anyInt(), anyString()))
        .thenReturn(List.of(new PendingAchievement("ach_4", "t", "v", null)));
    when(mapper.countPending(anyString())).thenReturn(7L);

    assertThat(adapter().listPending(50, RULE_VERSION))
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.achievementId()).isEqualTo("ach_4");
              assertThat(item.title()).isEqualTo("t");
              assertThat(item.venue()).isEqualTo("v");
              assertThat(item.abstractText()).isNull();
            });
    assertThat(adapter().countPending(RULE_VERSION)).isEqualTo(7L);
    verify(mapper).listPending(50, RULE_VERSION);
    verify(mapper).countPending(RULE_VERSION);
  }

  @Test
  @DisplayName("盘点查询按来源与结果两类分别转调")
  void countQueriesDelegate() {
    when(mapper.countByKind()).thenReturn(List.of(new KindCount("INFERRED", 100L, 90L)));
    when(mapper.countByOutcome(anyString())).thenReturn(List.of(new OutcomeCount("ASSIGNED", 88L)));
    when(mapper.countInferredRows(anyString())).thenReturn(100L);
    when(mapper.countAchievementsWithoutTopic()).thenReturn(3L);

    assertThat(adapter().countByKind())
        .singleElement()
        .satisfies(
            count -> {
              assertThat(count.kind()).isEqualTo("INFERRED");
              assertThat(count.rowCount()).isEqualTo(100L);
              assertThat(count.achievementCount()).isEqualTo(90L);
            });
    assertThat(adapter().countByOutcome(RULE_VERSION))
        .singleElement()
        .satisfies(count -> assertThat(count.outcome()).isEqualTo("ASSIGNED"));
    assertThat(adapter().countInferredRows(RULE_VERSION)).isEqualTo(100L);
    assertThat(adapter().countAchievementsWithoutTopic()).isEqualTo(3L);
  }

  @Test
  @DisplayName("待分类清单原样带出原因与时间")
  void unresolvedListDelegates() {
    Instant when = Instant.parse("2026-07-01T00:00:00Z");
    when(mapper.listUnresolved(anyString(), anyInt()))
        .thenReturn(List.of(new InferenceRecord("ach_5", "UNRESOLVED", 0, null, "无命中", when)));

    assertThat(adapter().listUnresolved(RULE_VERSION, 20))
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record.outcome()).isEqualTo("UNRESOLVED");
              assertThat(record.assignedCount()).isZero();
              assertThat(record.bestConfidence()).isNull();
              assertThat(record.evidence()).isEqualTo("无命中");
              assertThat(record.inferredAt()).isEqualTo(when);
            });
    verify(mapper).listUnresolved(RULE_VERSION, 20);
  }

  @Test
  @DisplayName("换版本重算只删其它版本，版本号原样下传")
  void deleteOtherVersionsDelegates() {
    when(mapper.deleteInferredOtherVersions(anyString(), anyString())).thenReturn(2);
    assertThat(adapter().deleteInferredOfOtherVersions("ach_6", RULE_VERSION)).isEqualTo(2);
    verify(mapper).deleteInferredOtherVersions("ach_6", RULE_VERSION);
  }
}
