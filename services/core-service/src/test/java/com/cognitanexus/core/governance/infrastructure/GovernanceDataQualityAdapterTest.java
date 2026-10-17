package com.cognitanexus.core.governance.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.governance.application.GovernanceRepositories;
import com.cognitanexus.core.governance.application.GovernanceRepositories.AnomalyRepository.OpenAnomalyKey;
import com.cognitanexus.core.governance.application.GovernanceRepositories.DataQualityRepository.AmbiguousScholar;
import com.cognitanexus.core.governance.application.GovernanceRepositories.DataQualityRepository.Bucket;
import com.cognitanexus.core.governance.application.GovernanceRepositories.DataQualityRepository.NameGroup;
import com.cognitanexus.core.governance.application.GovernanceRepositories.DataQualityRepository.Sample;
import com.cognitanexus.core.governance.application.GovernanceRepositories.DataQualityRepository.ScholarSample;
import com.cognitanexus.core.platform.persistence.JsonCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 数据质量巡检与异常去重的适配器出口。
 *
 * <p>这一层的故障形态与「翻译错字段」不同：巡检是按问题种类逐条登记的，若两个出口悄悄复用了
 * 同一个 mapper 方法，巡检会把「无主题」的数记到「无贡献者」名下，而两边都能跑通、都有数字。
 * 因此这里逐条钉住「哪个出口走哪个 SQL」，并守住两条边界：上限必须原样下传（巡检跑在业务库上，
 * 写死上限等于让大库全表扫描），聚合计数必须是 long（int 截断会在四位数以后悄悄归零）。
 */
@ExtendWith(MockitoExtension.class)
class GovernanceDataQualityAdapterTest {

  @Mock GovernanceMapper mapper;
  @Mock JsonCodec json;

  private GovernanceRepositoryAdapter adapter;

  @BeforeEach
  void setUp() {
    adapter = new GovernanceRepositoryAdapter(mapper, json);
  }

  private static GovernanceMapper.AnomalySampleRow sampleRow(String id) {
    return new GovernanceMapper.AnomalySampleRow(id, "标题-" + id, "PAPER", 2019);
  }

  @Test
  @DisplayName("年份越界：上限原样下传，样例的标题、类型、年份逐字段搬运且不串位")
  void yearOutOfRangeDelegatesWithLimit() {
    when(mapper.yearOutOfRangeIds(50)).thenReturn(List.of("ach_1", "ach_2"));
    when(mapper.yearOutOfRangeSamples(50))
        .thenReturn(List.of(sampleRow("ach_1"), sampleRow("ach_2")));

    assertThat(adapter.yearOutOfRangeIds(50)).containsExactly("ach_1", "ach_2");
    List<Sample> samples = adapter.yearOutOfRangeSamples(50);

    assertThat(samples).hasSize(2);
    assertThat(samples.get(0).id()).isEqualTo("ach_1");
    assertThat(samples.get(0).title()).isEqualTo("标题-ach_1");
    assertThat(samples.get(0).type()).isEqualTo("PAPER");
    assertThat(samples.get(0).publicationYear()).isEqualTo(2019);
  }

  @Test
  @DisplayName("类型冲突：走自己的 SQL，不复用年份越界那条查询")
  void topicKindConflictUsesItsOwnQueries() {
    when(mapper.topicKindConflictIds(20)).thenReturn(List.of("tpc_1"));
    when(mapper.topicKindConflictSamples(20)).thenReturn(List.of(sampleRow("tpc_1")));

    assertThat(adapter.topicKindConflictIds(20)).containsExactly("tpc_1");
    assertThat(adapter.topicKindConflictSamples(20))
        .extracting(Sample::id)
        .containsExactly("tpc_1");

    verify(mapper, org.mockito.Mockito.never()).yearOutOfRangeIds(20);
    verify(mapper, org.mockito.Mockito.never()).yearOutOfRangeSamples(20);
  }

  @Test
  @DisplayName("三类聚合：分桶标签原样搬（不翻译成枚举），三类各走各自的聚合查询")
  void bucketAggregationsKeepLabelsAndSplitQueries() {
    when(mapper.effectiveWithoutTopicByType())
        .thenReturn(List.of(new GovernanceMapper.AnomalyBucketRow("AWARD", 487L)));
    when(mapper.effectiveWithoutContributorByType())
        .thenReturn(List.of(new GovernanceMapper.AnomalyBucketRow("PAPER", 343L)));
    when(mapper.papersWithoutIdentifierByType())
        .thenReturn(List.of(new GovernanceMapper.AnomalyBucketRow("PAPER", 0L)));

    assertThat(adapter.effectiveWithoutTopicByType())
        .extracting(Bucket::bucket, Bucket::total)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("AWARD", 487L));
    assertThat(adapter.effectiveWithoutContributorByType())
        .extracting(Bucket::bucket, Bucket::total)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("PAPER", 343L));
    // 命中 0 条也要如实返回这一桶（0 是结论，不是「没有数据」）：巡检靠它决定是否关闭待办。
    assertThat(adapter.papersWithoutIdentifierByType())
        .extracting(Bucket::total)
        .containsExactly(0L);
  }

  @Test
  @DisplayName("三类样例出口各走各自的 SQL：复用同一个方法会让两种问题共用一份样例")
  void sampleExitsAreSeparate() {
    when(mapper.effectiveWithoutTopicSamples(10)).thenReturn(List.of(sampleRow("a_1")));
    when(mapper.effectiveWithoutContributorSamples(10)).thenReturn(List.of(sampleRow("b_1")));
    when(mapper.papersWithoutIdentifierSamples(10)).thenReturn(List.of(sampleRow("c_1")));

    assertThat(adapter.effectiveWithoutTopicSamples(10))
        .extracting(Sample::id)
        .containsExactly("a_1");
    assertThat(adapter.effectiveWithoutContributorSamples(10))
        .extracting(Sample::id)
        .containsExactly("b_1");
    assertThat(adapter.papersWithoutIdentifierSamples(10))
        .extracting(Sample::id)
        .containsExactly("c_1");
  }

  @Test
  @DisplayName("同名分组与同名学者：阈值与上限原样下传，计数保持 long 不截断")
  void sameNameQueriesKeepParametersAndLongCounts() {
    when(mapper.duplicateScholarNames(2, 100))
        .thenReturn(List.of(new GovernanceMapper.AnomalyNameGroupRow("张三", 3L)));
    when(mapper.scholarsByNameNorm("张三", 100))
        .thenReturn(
            List.of(
                new GovernanceMapper.AnomalyScholarRow("sch_1", "张三", "北航", "UNCLAIMED"),
                new GovernanceMapper.AnomalyScholarRow("sch_2", "张三", null, "CLAIMED")));

    assertThat(adapter.duplicateScholarNames(2, 100))
        .extracting(NameGroup::nameNorm, NameGroup::total)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("张三", 3L));

    List<ScholarSample> scholars = adapter.scholarsByNameNorm("张三", 100);
    assertThat(scholars).hasSize(2);
    assertThat(scholars.get(0).displayName()).isEqualTo("张三");
    assertThat(scholars.get(0).organizationName()).isEqualTo("北航");
    assertThat(scholars.get(0).claimedStatus()).isEqualTo("UNCLAIMED");
    // 机构缺失如实传 null，不就地补「未知机构」：补了就把「没采到」写成了「查过没有」。
    assertThat(scholars.get(1).organizationName()).isNull();
  }

  @Test
  @DisplayName("歧义学者：学者数与关系数是两个不同的数，各走各的 SQL")
  void ambiguousScholarCountsAreDistinct() {
    when(mapper.ambiguousScholars(3, 20))
        .thenReturn(List.of(new GovernanceMapper.AmbiguousScholarRow("sch_1", "李四", 5L)));
    when(mapper.countAmbiguousScholars(3)).thenReturn(898L);
    when(mapper.countPendingRelationsOfAmbiguous(3)).thenReturn(4519L);

    assertThat(adapter.ambiguousScholars(3, 20))
        .extracting(AmbiguousScholar::scholarId, AmbiguousScholar::pending)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("sch_1", 5L));
    assertThat(adapter.countAmbiguousScholars(3)).isEqualTo(898L);
    assertThat(adapter.countPendingRelationsOfAmbiguous(3)).isEqualTo(4519L);
  }

  @Test
  @DisplayName("按去重键找待办：查不到返回 empty，不编造一个 id 让巡检误以为已登记")
  void findOpenIdByDedupeKeyReturnsEmptyWhenAbsent() {
    when(mapper.findOpenAnomalyId("MISSING_TOPIC", "ALL")).thenReturn(null);
    when(mapper.findOpenAnomalyId("NO_CONTRIBUTOR", "ALL")).thenReturn("anm_9");

    assertThat(adapter.findOpenIdByDedupeKey("MISSING_TOPIC", "ALL")).isEmpty();
    assertThat(adapter.findOpenIdByDedupeKey("NO_CONTRIBUTOR", "ALL")).contains("anm_9");
  }

  @Test
  @DisplayName("刷新待办详情：detail 必须先序列化再落库，0 行说明待办已被他人关闭并返回 false")
  void refreshDetailSerializesAndReflectsAffectedRows() {
    when(json.write(any())).thenReturn("{\"count\":4519}");
    when(mapper.refreshAnomalyDetail("anm_1", "{\"count\":4519}")).thenReturn(1).thenReturn(0);

    assertThat(adapter.refreshDetail("anm_1", Map.of("count", 4519))).isTrue();
    assertThat(adapter.refreshDetail("anm_1", Map.of("count", 4519))).isFalse();
  }

  @Test
  @DisplayName("按种类列待办：行翻成键时 entityId 不丢（复核「问题是否已消失」要用它回查实体）")
  void listOpenOfKindMapsKeys() {
    when(mapper.openAnomaliesOfKind("MISSING_TOPIC", 200))
        .thenReturn(
            List.of(
                new GovernanceMapper.AnomalyKeyRow("anm_1", "ALL"),
                new GovernanceMapper.AnomalyKeyRow("anm_2", "ach_2")));

    List<OpenAnomalyKey> keys = adapter.listOpenOfKind("MISSING_TOPIC", 200);

    assertThat(keys).hasSize(2);
    assertThat(keys.get(0).id()).isEqualTo("anm_1");
    assertThat(keys.get(0).entityId()).isEqualTo("ALL");
    assertThat(keys.get(1).entityId()).isEqualTo("ach_2");
  }

  @Test
  @DisplayName("合并辅助出口：姓名变体删除与学者参与关系快照都直接返回条数，不做二次加工")
  void mergeHelpersDelegate() {
    when(mapper.deleteNameVariantsOf("sch_merged")).thenReturn(2);
    when(mapper.snapshotScholarContributions("sch_merged")).thenReturn(List.of("ctb_1", "ctb_2"));

    assertThat(adapter.deleteNameVariantsOf("sch_merged")).isEqualTo(2);
    assertThat(adapter.snapshotScholarContributions("sch_merged"))
        .containsExactly("ctb_1", "ctb_2");
  }

  @Test
  @DisplayName("适配器同时实现质量探测与异常去重两个端口：应用层按窄接口取用")
  void implementsQualityAndAnomalyPorts() {
    assertThat(adapter).isInstanceOf(GovernanceRepositories.DataQualityRepository.class);
    assertThat(adapter).isInstanceOf(GovernanceRepositories.AnomalyRepository.class);
  }

  @Test
  @DisplayName("待办关闭：受影响 0 行翻成 false，让调用侧区分「我关掉了」与「它已经被关掉了」")
  void resolveAnomalyReflectsAffectedRows() {
    when(mapper.resolveAnomaly("anm_1")).thenReturn(1).thenReturn(0);

    assertThat(adapter.resolve("anm_1")).isTrue();
    assertThat(adapter.resolve("anm_1")).isFalse();
  }

  @Test
  @DisplayName("未知条件的探测方法不吞异常：SQL 失败必须冒出来，不能返回空集合冒充「没有问题」")
  void probeFailureIsNotSwallowed() {
    when(mapper.yearOutOfRangeIds(10)).thenThrow(new IllegalStateException("connection reset"));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.yearOutOfRangeIds(10))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connection reset");
  }
}
