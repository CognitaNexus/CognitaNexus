package com.cognitanexus.core.governance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.governance.application.GovernanceRepositories.AnomalyRepository;
import com.cognitanexus.core.governance.application.GovernanceRepositories.AnomalyRepository.OpenAnomalyKey;
import com.cognitanexus.core.governance.application.GovernanceRepositories.DataQualityRepository;
import com.cognitanexus.core.governance.domain.GovernanceAnomaly;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.sharedkernel.AccountId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 数据质量巡检。
 *
 * <p>重点不在「能扫出多少条」，而在三件容易写错的事：①同一问题重复巡检不得重复登记；
 * ②问题已经消失的待办必须被关闭，否则列表会长期停留在历史状态；③截断时不得把「没扫到」当成
 * 「不存在」（那会把上限变成一个静默的错误结论）。
 */
class DataQualityScanServiceTest {

  /** 巡检是治理动作：读取业务数据并登记待办，因此与其它治理入口一样需要治理能力。 */
  private static final ActorContext REVIEWER =
      new ActorContext(
          new AccountId("acc_reviewer"), "reviewer", "审核员", Set.of(RoleNames.REVIEWER), 1L);

  private DataQualityRepository data;
  private AnomalyRepository anomalies;
  private AuditRecorder audit;
  private DataQualityScanService service;

  @BeforeEach
  void setUp() {
    data = mock(DataQualityRepository.class);
    anomalies = mock(AnomalyRepository.class);
    audit = mock(AuditRecorder.class);
    service = new DataQualityScanService(data, anomalies, audit);
    // 默认：所有探测项都为空（干净库）。各用例只覆盖自己关心的那一项。
    lenient().when(data.yearOutOfRangeIds(anyInt())).thenReturn(List.of());
    lenient().when(data.yearOutOfRangeSamples(anyInt())).thenReturn(List.of());
    lenient().when(data.topicKindConflictIds(anyInt())).thenReturn(List.of());
    lenient().when(data.topicKindConflictSamples(anyInt())).thenReturn(List.of());
    lenient().when(data.effectiveWithoutTopicByType()).thenReturn(List.of());
    lenient().when(data.effectiveWithoutTopicSamples(anyInt())).thenReturn(List.of());
    lenient().when(data.effectiveWithoutContributorByType()).thenReturn(List.of());
    lenient().when(data.effectiveWithoutContributorSamples(anyInt())).thenReturn(List.of());
    lenient().when(data.papersWithoutIdentifierByType()).thenReturn(List.of());
    lenient().when(data.papersWithoutIdentifierSamples(anyInt())).thenReturn(List.of());
    lenient().when(data.duplicateScholarNames(anyInt(), anyInt())).thenReturn(List.of());
    lenient().when(data.ambiguousScholars(anyInt(), anyInt())).thenReturn(List.of());
    lenient().when(data.countAmbiguousScholars(anyInt())).thenReturn(0L);
    lenient().when(data.countPendingRelationsOfAmbiguous(anyInt())).thenReturn(0L);
    lenient()
        .when(anomalies.findOpenIdByDedupeKey(anyString(), anyString()))
        .thenReturn(Optional.empty());
    lenient().when(anomalies.listOpenOfKind(anyString(), anyInt())).thenReturn(List.of());
    lenient().when(anomalies.refreshDetail(anyString(), any())).thenReturn(true);
    lenient().when(anomalies.resolve(anyString())).thenReturn(true);
    lenient().when(anomalies.countOpenAnomalies()).thenReturn(1L);
  }

  @Test
  @DisplayName("首次巡检查到年份越界：逐条登记，detail 带判定口径与建议动作")
  void recordsYearOutOfRange() {
    when(data.yearOutOfRangeIds(anyInt())).thenReturn(List.of("ach_1"));
    when(data.yearOutOfRangeSamples(anyInt()))
        .thenReturn(List.of(new DataQualityRepository.Sample("ach_1", "某成果", "PAPER", 20245)));

    DataQualityScanService.Report report = service.scan(REVIEWER);

    ArgumentCaptor<GovernanceAnomaly> captor = ArgumentCaptor.forClass(GovernanceAnomaly.class);
    verify(anomalies).record(captor.capture());
    GovernanceAnomaly recorded = captor.getValue();
    assertThat(recorded.kind()).isEqualTo(GovernanceAnomaly.KIND_YEAR_OUT_OF_RANGE);
    assertThat(recorded.entityId()).isEqualTo("ach_1");
    assertThat(recorded.detail().get("dedupeKey")).isEqualTo("ach_1");
    assertThat(recorded.detail().get("summary")).asString().contains("20245");
    assertThat(recorded.detail().get("criteria")).asString().contains("publication_year");
    assertThat(recorded.detail().get("suggestedAction")).asString().contains("不要按相邻数字猜测");
    assertThat(report.created()).isEqualTo(1);
    assertThat(report.findings())
        .filteredOn(f -> f.kind().equals(GovernanceAnomaly.KIND_YEAR_OUT_OF_RANGE))
        .singleElement()
        .satisfies(f -> assertThat(f.detected()).isEqualTo(1));
  }

  @Test
  @DisplayName("同一问题再次巡检：刷新既有异常，不新增待办")
  void rescansWithoutDuplicating() {
    when(data.yearOutOfRangeIds(anyInt())).thenReturn(List.of("ach_1"));
    when(anomalies.findOpenIdByDedupeKey(GovernanceAnomaly.KIND_YEAR_OUT_OF_RANGE, "ach_1"))
        .thenReturn(Optional.of("anm_existing"));

    DataQualityScanService.Report report = service.scan(REVIEWER);

    verify(anomalies, never()).record(any());
    verify(anomalies).refreshDetail(eq("anm_existing"), any());
    assertThat(report.created()).isZero();
    assertThat(report.refreshed()).isEqualTo(1);
  }

  @Test
  @DisplayName("问题已经消失：既有待办被自动关闭，并留下审计")
  void closesAnomalyThatNoLongerHolds() {
    when(anomalies.listOpenOfKind(GovernanceAnomaly.KIND_YEAR_OUT_OF_RANGE, 500))
        .thenReturn(List.of(new OpenAnomalyKey("anm_stale", "ach_old")));

    DataQualityScanService.Report report = service.scan(REVIEWER);

    verify(anomalies).resolve("anm_stale");
    // 审计里必须能读回「是谁在什么时候把这条待办关掉的」，且动作名与人工处置区分开。
    ArgumentCaptor<AuditRecorder.AuditEntry> audited =
        ArgumentCaptor.forClass(AuditRecorder.AuditEntry.class);
    verify(audit, org.mockito.Mockito.atLeastOnce()).record(audited.capture());
    assertThat(audited.getAllValues())
        .anyMatch(
            entry ->
                "GOVERNANCE_ANOMALY_AUTO_CLOSED".equals(entry.action())
                    && "anm_stale".equals(entry.targetId()));
    assertThat(report.autoClosed()).isEqualTo(1);
    assertThat(report.findings())
        .filteredOn(f -> f.kind().equals(GovernanceAnomaly.KIND_YEAR_OUT_OF_RANGE))
        .singleElement()
        .satisfies(f -> assertThat(f.autoClosed()).isEqualTo(1));
  }

  @Test
  @DisplayName("扫描结果被上限截断时不做「问题已消失」的复核：没扫到不等于不存在")
  void doesNotReconcileWhenTruncated() {
    List<String> many = new java.util.ArrayList<>();
    for (int i = 0; i <= DataQualityScanService.MAX_RECORD_FINDINGS; i++) {
      many.add("ach_" + i);
    }
    when(data.yearOutOfRangeIds(anyInt())).thenReturn(many);

    DataQualityScanService.Report report = service.scan(REVIEWER);

    verify(anomalies, never())
        .listOpenOfKind(eq(GovernanceAnomaly.KIND_YEAR_OUT_OF_RANGE), anyInt());
    assertThat(report.findings())
        .filteredOn(f -> f.kind().equals(GovernanceAnomaly.KIND_YEAR_OUT_OF_RANGE))
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.truncated()).isTrue();
              assertThat(f.detected()).isEqualTo(DataQualityScanService.MAX_RECORD_FINDINGS);
            });
  }

  @Test
  @DisplayName("批量缺陷按类型聚合成一条：计数与类型分布来自同一次探测")
  void aggregatesMissingTopicAsOneAnomaly() {
    when(data.effectiveWithoutTopicByType())
        .thenReturn(
            List.of(
                new DataQualityRepository.Bucket("PAPER", 600),
                new DataQualityRepository.Bucket("AWARD", 94)));

    DataQualityScanService.Report report = service.scan(REVIEWER);

    ArgumentCaptor<GovernanceAnomaly> captor = ArgumentCaptor.forClass(GovernanceAnomaly.class);
    verify(anomalies).record(captor.capture());
    GovernanceAnomaly recorded = captor.getValue();
    assertThat(recorded.kind()).isEqualTo(GovernanceAnomaly.KIND_MISSING_TOPIC);
    assertThat(recorded.entityId()).isNull();
    assertThat(recorded.detail().get("dedupeKey")).isEqualTo("ALL");
    assertThat(recorded.detail().get("count")).isEqualTo(694L);
    assertThat(recorded.detail().get("byType"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("PAPER", 600L)
        .containsEntry("AWARD", 94L);
    assertThat(report.created()).isEqualTo(1);
  }

  @Test
  @DisplayName("批量缺陷计数归零：既有待办被关闭，不再保留一条读起来像仍然存在的问题")
  void closesAggregateWhenCountDropsToZero() {
    when(anomalies.findOpenIdByDedupeKey(GovernanceAnomaly.KIND_MISSING_TOPIC, "ALL"))
        .thenReturn(Optional.of("anm_topic"));

    DataQualityScanService.Report report = service.scan(REVIEWER);

    verify(anomalies).resolve("anm_topic");
    assertThat(report.autoClosed()).isEqualTo(1);
    assertThat(report.created()).isZero();
  }

  @Test
  @DisplayName("同名聚集带上「同名不是同人证据」的口径，并给出可核对的样例")
  void duplicateNamesCarryNonIdentityCaveat() {
    when(data.duplicateScholarNames(anyInt(), anyInt()))
        .thenReturn(List.of(new DataQualityRepository.NameGroup("zhang wei", 4)));
    when(data.scholarsByNameNorm(eq("zhang wei"), anyInt()))
        .thenReturn(
            List.of(
                new DataQualityRepository.ScholarSample("sch_1", "Zhang Wei", "清华大学", "UNCLAIMED"),
                new DataQualityRepository.ScholarSample(
                    "sch_2", "Zhang Wei", "北京大学", "UNCLAIMED")));

    service.scan(REVIEWER);

    ArgumentCaptor<GovernanceAnomaly> captor = ArgumentCaptor.forClass(GovernanceAnomaly.class);
    verify(anomalies).record(captor.capture());
    GovernanceAnomaly recorded = captor.getValue();
    assertThat(recorded.kind()).isEqualTo(GovernanceAnomaly.KIND_DUPLICATE_SCHOLAR_NAME);
    assertThat(recorded.detail().get("criteria")).asString().contains("不作为同人证据");
    assertThat(recorded.detail().get("suggestedAction")).asString().contains("不得仅凭姓名相同合并");
    assertThat(recorded.detail().get("groupCount")).isEqualTo(1);
    assertThat(recorded.detail().get("scholarCount")).isEqualTo(4L);
  }

  @Test
  @DisplayName("同名待核验聚集：计数用待核验关系条数，学者数另给，并声明它不进共著边")
  void ambiguousNamesCountPendingRelations() {
    when(data.countAmbiguousScholars(anyInt())).thenReturn(898L);
    when(data.countPendingRelationsOfAmbiguous(anyInt())).thenReturn(7204L);
    when(data.ambiguousScholars(anyInt(), anyInt()))
        .thenReturn(List.of(new DataQualityRepository.AmbiguousScholar("sch_1", "Li Wei", 42)));

    DataQualityScanService.Report report = service.scan(REVIEWER);

    ArgumentCaptor<GovernanceAnomaly> captor = ArgumentCaptor.forClass(GovernanceAnomaly.class);
    verify(anomalies).record(captor.capture());
    GovernanceAnomaly recorded = captor.getValue();
    assertThat(recorded.kind()).isEqualTo(GovernanceAnomaly.KIND_AMBIGUOUS_SCHOLAR_NAME);
    assertThat(recorded.detail().get("count")).isEqualTo(7204L);
    assertThat(recorded.detail().get("scholarCount")).isEqualTo(898L);
    assertThat(recorded.detail().get("criteria")).asString().contains("待核验关系");
    assertThat(recorded.detail().get("suggestedAction")).asString().contains("不得凭姓名相同批量确认");
    assertThat(report.findings())
        .filteredOn(f -> f.kind().equals(GovernanceAnomaly.KIND_AMBIGUOUS_SCHOLAR_NAME))
        .singleElement()
        .satisfies(f -> assertThat(f.detected()).isEqualTo(7204));
  }

  @Test
  @DisplayName("巡检只登记异常，不写任何业务数据：探测端口只有读方法")
  void scanOnlyWritesAnomalies() {
    when(data.yearOutOfRangeIds(anyInt())).thenReturn(List.of("ach_1"));

    service.scan(REVIEWER);

    // 探测端口 DataQualityRepository 的全部方法名都不含写动词，这里显式断言一次：
    // 一旦有人往这个端口上加「自动修正」的方法，这条断言会立刻提醒他红线在哪。
    assertThat(DataQualityRepository.class.getDeclaredMethods())
        .allSatisfy(
            method ->
                assertThat(method.getName())
                    .as("巡检探测端口不得引入写入方法：%s", method.getName())
                    .doesNotStartWith("update")
                    .doesNotStartWith("delete")
                    .doesNotStartWith("insert")
                    .doesNotStartWith("mark"));
  }

  @Test
  @DisplayName("每条异常的 detail 都带去重键：否则重复巡检会无限追加待办")
  void everyAnomalyCarriesDedupeKey() {
    when(data.yearOutOfRangeIds(anyInt())).thenReturn(List.of("ach_1", "ach_2"));
    when(data.effectiveWithoutTopicByType())
        .thenReturn(List.of(new DataQualityRepository.Bucket("PAPER", 3)));

    service.scan(REVIEWER);

    ArgumentCaptor<GovernanceAnomaly> captor = ArgumentCaptor.forClass(GovernanceAnomaly.class);
    verify(anomalies, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
    assertThat(captor.getAllValues()).isNotEmpty();
    assertThat(captor.getAllValues())
        .allSatisfy(a -> assertThat(a.detail().get("dedupeKey")).asString().isNotBlank());
  }

  @Test
  @DisplayName("巡检报告自带口径：规则版本、时区、年份上下界")
  void reportCarriesCriteria() {
    DataQualityScanService.Report report = service.scan(REVIEWER);

    assertThat(report.ruleVersion()).isEqualTo(DataQualityScanService.RULE_VERSION);
    assertThat(report.timeZone()).isNotBlank();
    assertThat(report.yearBounds()).containsKeys("minYear", "maxYear");
    assertThat(report.findings()).hasSize(7);
    assertThat(report.findings().stream().map(DataQualityScanService.Finding::kind).toList())
        .contains(
            GovernanceAnomaly.KIND_YEAR_OUT_OF_RANGE,
            GovernanceAnomaly.KIND_TOPIC_KIND_CONFLICT,
            GovernanceAnomaly.KIND_MISSING_TOPIC,
            GovernanceAnomaly.KIND_NO_CONTRIBUTOR,
            GovernanceAnomaly.KIND_PAPER_WITHOUT_IDENTIFIER,
            GovernanceAnomaly.KIND_DUPLICATE_SCHOLAR_NAME,
            GovernanceAnomaly.KIND_AMBIGUOUS_SCHOLAR_NAME);
  }

  @Test
  @DisplayName("同类异常的中文标签覆盖全部已定义种类")
  void labelsCoverAllKinds() {
    List<String> kinds =
        List.of(
            GovernanceAnomaly.KIND_STRUCTURE_INVALID,
            GovernanceAnomaly.KIND_IDENTIFIER_CONFLICT,
            GovernanceAnomaly.KIND_MISSING_REQUIRED_FIELD,
            GovernanceAnomaly.KIND_SOURCE_DISABLED,
            GovernanceAnomaly.KIND_UNMAPPED_CATEGORY,
            GovernanceAnomaly.KIND_YEAR_OUT_OF_RANGE,
            GovernanceAnomaly.KIND_TOPIC_KIND_CONFLICT,
            GovernanceAnomaly.KIND_MISSING_TOPIC,
            GovernanceAnomaly.KIND_NO_CONTRIBUTOR,
            GovernanceAnomaly.KIND_PAPER_WITHOUT_IDENTIFIER,
            GovernanceAnomaly.KIND_DUPLICATE_SCHOLAR_NAME,
            GovernanceAnomaly.KIND_AMBIGUOUS_SCHOLAR_NAME);
    assertThat(kinds).allSatisfy(k -> assertThat(GovernanceAnomaly.label(k)).isNotEqualTo(k));
  }

  @Test
  @DisplayName("未知种类原样返回代码，不折成「其它」")
  void unknownKindFallsBackToCode() {
    assertThat(GovernanceAnomaly.label("SOMETHING_NEW")).isEqualTo("SOMETHING_NEW");
    assertThat(GovernanceAnomaly.label(null)).isEmpty();
  }
}
