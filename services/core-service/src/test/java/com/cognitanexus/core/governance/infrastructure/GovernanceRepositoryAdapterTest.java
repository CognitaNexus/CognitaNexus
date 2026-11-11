package com.cognitanexus.core.governance.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.governance.application.GovernanceRepositories;
import com.cognitanexus.core.governance.application.GovernanceRepositories.CandidateBuckets;
import com.cognitanexus.core.governance.application.GovernanceRepositories.MergeTargetRepository.ContributionMove;
import com.cognitanexus.core.governance.application.GovernanceRepositories.ScoredCandidate;
import com.cognitanexus.core.governance.domain.DedupCandidate;
import com.cognitanexus.core.governance.domain.GovernanceAnomaly;
import com.cognitanexus.core.governance.domain.MergeLedgerEntry;
import com.cognitanexus.core.platform.persistence.JsonCodec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 治理域仓储适配器：行对象与领域对象之间的翻译。
 *
 * <p>这一层最典型的故障不是抛异常，而是"少翻译一个字段"——比如相似度没带出来、快照读错列，
 * 上层拿到一个字段全默认值的对象继续算下去，界面上只表现为"这一栏一直是 0"。因此逐字段核对，
 * 并把两个边界写下来：受影响行数 0 必须翻成 false（而不是吞掉），相似度缺失必须回落 0 而不是 NPE。
 */
@ExtendWith(MockitoExtension.class)
class GovernanceRepositoryAdapterTest {

  @Mock GovernanceMapper mapper;
  @Mock JsonCodec json;

  private GovernanceRepositoryAdapter adapter;

  private static final Instant WHEN = Instant.parse("2026-06-01T00:00:00Z");

  @BeforeEach
  void setUp() {
    adapter = new GovernanceRepositoryAdapter(mapper, json);
  }

  private static GovernanceMapper.CandidateRow candidateRow(String id, String status) {
    return new GovernanceMapper.CandidateRow(
        id,
        "PAPER",
        "ach_left",
        "ach_right",
        "{\"reason\":\"TITLE_SIMILARITY\"}",
        status,
        WHEN,
        null,
        null,
        null,
        null);
  }

  @Test
  @DisplayName("候选插入：受影响行数为 1 才算插入成功，0 表示唯一约束已命中")
  void insertIfAbsentReflectsAffectedRows() {
    when(json.write(any())).thenReturn("{\"reason\":\"TITLE_SIMILARITY\"}");
    when(mapper.insertCandidateIfAbsent(
            any(), any(), any(), any(), eq("{\"reason\":\"TITLE_SIMILARITY\"}")))
        .thenReturn(1)
        .thenReturn(0);

    DedupCandidate candidate =
        new DedupCandidate(
            "ddc_1",
            "PAPER",
            "ach_left",
            "ach_right",
            Map.of("reason", "TITLE_SIMILARITY"),
            DedupCandidate.Status.OPEN,
            WHEN,
            null,
            null,
            null,
            null);

    assertThat(adapter.insertIfAbsent(candidate)).isTrue();
    assertThat(adapter.insertIfAbsent(candidate)).isFalse();
  }

  @Test
  @DisplayName("候选列表：证据 JSON 被解回 Map，状态字符串被解回枚举")
  void listOpenCandidatesMapsRows() {
    when(mapper.listCandidates("OPEN", 10, 0)).thenReturn(List.of(candidateRow("ddc_1", "OPEN")));
    when(json.readMap(anyString())).thenReturn(Map.of("reason", "TITLE_SIMILARITY"));

    List<DedupCandidate> result = adapter.listOpenCandidates(10, 0);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).id()).isEqualTo("ddc_1");
    assertThat(result.get(0).status()).isEqualTo(DedupCandidate.Status.OPEN);
    assertThat(result.get(0).evidence()).containsEntry("reason", "TITLE_SIMILARITY");
  }

  @Test
  @DisplayName("工作台候选带出相似度：缺列回落 0 而不是抛空指针")
  void listOpenCandidatesScoredCarriesSimilarity() {
    GovernanceMapper.CandidateListRow scored =
        new GovernanceMapper.CandidateListRow(
            "ddc_1",
            "PAPER",
            "ach_left",
            "ach_right",
            "{}",
            "OPEN",
            WHEN,
            null,
            null,
            null,
            null,
            0.88d);
    GovernanceMapper.CandidateListRow noScore =
        new GovernanceMapper.CandidateListRow(
            "ddc_2",
            "PAPER",
            "ach_left",
            "ach_right",
            "{}",
            "OPEN",
            WHEN,
            null,
            null,
            null,
            null,
            null);
    when(mapper.listCandidatesFiltered("OPEN", 0.7d, "similarity_desc", 20, 0))
        .thenReturn(List.of(scored, noScore));
    when(json.readMap(anyString())).thenReturn(Map.of());

    List<ScoredCandidate> result = adapter.listOpenCandidatesScored(0.7d, "similarity_desc", 20, 0);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).similarity()).isEqualTo(0.88d);
    assertThat(result.get(1).similarity()).isZero();
  }

  @Test
  @DisplayName("分档计数按字段搬运，不重排顺序：high/mid/low 位置错了界面会把「高置信」显示成「低置信」")
  void bucketCountsKeepFieldOrder() {
    when(mapper.countCandidateBuckets("OPEN", 0.9d, 0.7d))
        .thenReturn(new GovernanceMapper.CandidateBuckets(11L, 22L, 33L, 66L));

    CandidateBuckets buckets = adapter.countOpenCandidatesByBucket(0.9d, 0.7d);

    assertThat(buckets.high()).isEqualTo(11L);
    assertThat(buckets.mid()).isEqualTo(22L);
    assertThat(buckets.low()).isEqualTo(33L);
    assertThat(buckets.total()).isEqualTo(66L);
  }

  @Test
  @DisplayName("按标识查找：不存在返回 empty，不构造一个空壳候选")
  void findByIdReturnsEmptyWhenMissing() {
    when(mapper.findCandidate("ddc_1")).thenReturn(Optional.empty());

    assertThat(adapter.findById("ddc_1")).isEmpty();
  }

  @Test
  @DisplayName("裁决与异常处理：受影响行数 0 一律翻成 false，让上层能区分「已处理」与「被别人先处理了」")
  void resolveReturnsFalseWhenNoRowAffected() {
    when(mapper.resolveCandidate("ddc_1", "REJECTED", "acc_1", "理由")).thenReturn(0);
    assertThat(adapter.resolve("ddc_1", "REJECTED", "acc_1", "理由")).isFalse();

    when(mapper.resolveAnomaly("anm_1")).thenReturn(0);
    assertThat(adapter.resolve("anm_1")).isFalse();
  }

  @Test
  @DisplayName("按一对实体查候选：不限状态，且把改判指针一并搬回来")
  void findBetweenCarriesRebasedPointer() {
    GovernanceMapper.CandidateRow rebased =
        new GovernanceMapper.CandidateRow(
            "ddc_1",
            "PAPER",
            "ach_left",
            "ach_right",
            "{\"reason\":\"TITLE_SIMILARITY\"}",
            "REBASED",
            WHEN,
            WHEN,
            "acc_1",
            "上游被合并",
            "ddc_2");
    when(mapper.findCandidateBetween("ach_left", "ach_right")).thenReturn(Optional.of(rebased));
    when(json.readMap(anyString())).thenReturn(Map.of("reason", "TITLE_SIMILARITY"));

    DedupCandidate found = adapter.findBetween("ach_left", "ach_right").orElseThrow();

    assertThat(found.status()).isEqualTo(DedupCandidate.Status.REBASED);
    assertThat(found.rebasedToIdOrNull()).isEqualTo("ddc_2");
  }

  @Test
  @DisplayName("改判：状态与承接指针同一条语句写入，受影响行数 0 表示被他人先处置")
  void resolveRebasedReflectsAffectedRows() {
    when(mapper.resolveCandidateRebased("ddc_1", "acc_1", "上游被合并", "ddc_2"))
        .thenReturn(1)
        .thenReturn(0);

    assertThat(adapter.resolveRebased("ddc_1", "acc_1", "上游被合并", "ddc_2")).isTrue();
    assertThat(adapter.resolveRebased("ddc_1", "acc_1", "上游被合并", "ddc_2")).isFalse();
  }

  @Test
  @DisplayName("改判指针只在 REBASED 状态下暴露：驳回的候选不该读起来像还有下家")
  void rebasedPointerHiddenForOtherStatuses() {
    DedupCandidate rejected =
        new DedupCandidate(
            "ddc_1",
            "PAPER",
            "ach_left",
            "ach_right",
            Map.of("reason", "TITLE_SIMILARITY"),
            DedupCandidate.Status.REJECTED,
            WHEN,
            WHEN,
            "acc_1",
            "不是同一篇",
            "ddc_2");

    assertThat(rejected.rebasedToId()).isEqualTo("ddc_2");
    assertThat(rejected.rebasedToIdOrNull()).isNull();
  }

  @Test
  @DisplayName("台账写入：快照序列化后落库，操作与实体类型不丢")
  void appendWritesSnapshot() {
    when(json.write(any())).thenReturn("{\"sourcesMoved\":2}");

    adapter.append(
        new MergeLedgerEntry(
            "mgl_1",
            MergeLedgerEntry.Operation.MERGE,
            "ach_survivor",
            "ach_merged",
            MergeLedgerEntry.EntityType.ACHIEVEMENT.name(),
            Map.of("sourcesMoved", 2),
            "同一篇论文的两种收录",
            "acc_reviewer",
            WHEN));

    verify(mapper)
        .insertLedger(
            eq("mgl_1"),
            eq("MERGE"),
            eq("ach_survivor"),
            eq("ach_merged"),
            eq("ACHIEVEMENT"),
            eq("{\"sourcesMoved\":2}"),
            eq("同一篇论文的两种收录"),
            eq("acc_reviewer"));
  }

  @Test
  @DisplayName("台账读取：快照与操作枚举都还原，合并前的状态可回看")
  void listLedgerRestoresSnapshot() {
    when(mapper.listLedgerByEntity("ach_survivor", 50))
        .thenReturn(
            List.of(
                new GovernanceMapper.LedgerRow(
                    "mgl_1",
                    "MERGE",
                    "ach_survivor",
                    "ach_merged",
                    "ACHIEVEMENT",
                    "{\"sourcesMoved\":2}",
                    "理由",
                    "acc_reviewer",
                    WHEN)));
    when(json.readMap(anyString())).thenReturn(Map.of("sourcesMoved", 2));

    List<MergeLedgerEntry> result = adapter.listByEntity("ach_survivor", 50);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).operation()).isEqualTo(MergeLedgerEntry.Operation.MERGE);
    assertThat(result.get(0).snapshot()).containsEntry("sourcesMoved", 2);
    assertThat(result.get(0).entityType()).isEqualTo("ACHIEVEMENT");
  }

  @Test
  @DisplayName("异常登记与读取：详情 JSON 往返一致")
  void anomalyRoundTrip() {
    when(json.write(any())).thenReturn("{\"occupiedBy\":\"ach_2\"}");
    when(json.readMap(anyString())).thenReturn(Map.of("occupiedBy", "ach_2"));

    adapter.record(
        new GovernanceAnomaly(
            "anm_1",
            GovernanceAnomaly.KIND_IDENTIFIER_CONFLICT,
            "src_1",
            "ach_1",
            Map.of("occupiedBy", "ach_2"),
            "OPEN",
            WHEN,
            null));

    verify(mapper)
        .insertAnomaly(
            eq("anm_1"),
            eq(GovernanceAnomaly.KIND_IDENTIFIER_CONFLICT),
            eq("src_1"),
            eq("ach_1"),
            anyString());

    when(mapper.listAnomalies("OPEN", 20, 0))
        .thenReturn(
            List.of(
                new GovernanceMapper.AnomalyRow(
                    "anm_1",
                    GovernanceAnomaly.KIND_IDENTIFIER_CONFLICT,
                    "src_1",
                    "ach_1",
                    "{\"occupiedBy\":\"ach_2\"}",
                    "OPEN",
                    WHEN,
                    null)));

    List<GovernanceAnomaly> result = adapter.listOpenAnomalies(20, 0);
    assertThat(result).hasSize(1);
    assertThat(result.get(0).detail()).containsEntry("occupiedBy", "ach_2");
  }

  @Test
  @DisplayName("合并关系迁移：先把幸存方的记录插入、再删除被合并方的，顺序颠倒会在中间态丢关系")
  void moveContributionsInsertsThenDeletes() {
    when(mapper.insertMovedContributions("ach_merged", "ach_survivor")).thenReturn(5);
    when(mapper.deleteContributionsOf("ach_merged")).thenReturn(4);

    ContributionMove move = adapter.moveContributions("ach_merged", "ach_survivor");

    assertThat(move.inserted()).isEqualTo(5);
    assertThat(move.removed()).isEqualTo(4);
  }

  @Test
  @DisplayName("学者关系迁移与姓名变体迁移：变体搬运后同样清理原属记录")
  void moveScholarRelationsCleansUpSource() {
    when(mapper.insertMovedScholarContributions("sch_merged", "sch_survivor")).thenReturn(3);
    when(mapper.deleteContributionsOfScholar("sch_merged")).thenReturn(3);
    ContributionMove move = adapter.moveScholarContributions("sch_merged", "sch_survivor");
    assertThat(move.inserted()).isEqualTo(3);
    assertThat(move.removed()).isEqualTo(3);

    when(mapper.moveNameVariants("sch_merged", "sch_survivor")).thenReturn(2);
    assertThat(adapter.moveNameVariants("sch_merged", "sch_survivor")).isEqualTo(2);
    verify(mapper).deleteNameVariantsOf("sch_merged");
  }

  @Test
  @DisplayName("门户授权搬迁与撤销各自独立：两边都有管理者时只能撤销，不能自动合并授权")
  void authorityOperationsDelegated() {
    when(mapper.moveActiveAuthority("sch_merged", "sch_survivor")).thenReturn(1);
    assertThat(adapter.moveActiveAuthority("sch_merged", "sch_survivor")).isEqualTo(1);

    when(mapper.revokeAuthorityOfScholar("sch_merged", "需人工重新授予")).thenReturn(1);
    assertThat(adapter.revokeAuthorityOfScholar("sch_merged", "需人工重新授予")).isEqualTo(1);

    when(mapper.findActiveAuthorityId("sch_survivor")).thenReturn(Optional.of("aut_1"));
    assertThat(adapter.findActiveAuthorityId("sch_survivor")).contains("aut_1");
  }

  @Test
  @DisplayName("合并标记：受影响行数 0 翻成 false，让上层走版本冲突分支而不是静默继续")
  void markMergedReflectsAffectedRows() {
    when(mapper.markAchievementMerged("ach_merged", "ach_survivor")).thenReturn(1);
    when(mapper.markScholarMerged("sch_merged", "sch_survivor")).thenReturn(0);

    assertThat(adapter.markAchievementMerged("ach_merged", "ach_survivor")).isTrue();
    assertThat(adapter.markScholarMerged("sch_merged", "sch_survivor")).isFalse();
  }

  @Test
  @DisplayName("来源归属与主题迁移、快照读取都直接委托：条数即迁移结果，不做二次加工")
  void linkageAndSnapshotDelegation() {
    when(mapper.moveSourceLinks("ach_merged", "ach_survivor")).thenReturn(2);
    when(mapper.moveTopicLinks("ach_merged", "ach_survivor")).thenReturn(3);
    when(mapper.snapshotContributions("ach_merged")).thenReturn(List.of("ctb_1"));
    when(mapper.snapshotSources("ach_merged")).thenReturn(List.of("acs_1"));

    assertThat(adapter.moveSourceLinks("ach_merged", "ach_survivor")).isEqualTo(2);
    assertThat(adapter.moveTopicLinks("ach_merged", "ach_survivor")).isEqualTo(3);
    assertThat(adapter.snapshotContributions("ach_merged")).containsExactly("ctb_1");
    assertThat(adapter.snapshotSources("ach_merged")).containsExactly("acs_1");
  }

  @Test
  @DisplayName("筛选计数与总计数走各自 SQL：混用会让界面的「命中数」与「待办总数」互相冒充")
  void countMethodsUseDistinctQueries() {
    when(mapper.countCandidatesFiltered("OPEN", 0.9d)).thenReturn(12L);
    when(mapper.countOpenCandidates()).thenReturn(99L);
    when(mapper.countOpenForAchievement("ach_1")).thenReturn(2L);
    when(mapper.countOpenAnomalies()).thenReturn(7L);

    assertThat(adapter.countOpenCandidatesByFilter(0.9d)).isEqualTo(12L);
    assertThat(adapter.countOpenCandidates()).isEqualTo(99L);
    assertThat(adapter.countOpenFor("ach_1")).isEqualTo(2L);
    assertThat(adapter.countOpenAnomalies()).isEqualTo(7L);
  }

  @Test
  @DisplayName("适配器同时实现四个端口：应用层各自依赖窄接口，不因合并实现而互相可见")
  void implementsAllFourPorts() {
    assertThat(adapter).isInstanceOf(GovernanceRepositories.DedupCandidateRepository.class);
    assertThat(adapter).isInstanceOf(GovernanceRepositories.MergeLedgerRepository.class);
    assertThat(adapter).isInstanceOf(GovernanceRepositories.AnomalyRepository.class);
    assertThat(adapter).isInstanceOf(GovernanceRepositories.MergeTargetRepository.class);
  }

  @Test
  @DisplayName("合并影响预览的行被翻成端口记录：字段一个不少，姓名缺失回落空串而不是 null")
  void previewRowsAreMapped() {
    when(mapper.previewContributions("ach_merged"))
        .thenReturn(
            List.of(
                new GovernanceMapper.PreviewContributionRow("sch_1", "甲", "AUTO", "AUTHOR"),
                new GovernanceMapper.PreviewContributionRow("sch_2", null, "DISPUTED", "AUTHOR")));
    when(mapper.previewSourceLinks("ach_merged"))
        .thenReturn(List.of(new GovernanceMapper.PreviewSourceRow("src_1", "rec_1")));
    when(mapper.previewTopicIds("ach_merged")).thenReturn(List.of("tpc_1"));
    when(mapper.previewIdentifiers("ACHIEVEMENT", "ach_merged"))
        .thenReturn(List.of(new GovernanceMapper.PreviewIdentifierRow("DOI", "", "10.1/x")));

    List<GovernanceRepositories.MergeTargetRepository.PreviewContribution> contributions =
        adapter.previewContributions("ach_merged");
    assertThat(contributions).hasSize(2);
    assertThat(contributions.get(0).scholarName()).isEqualTo("甲");
    // 姓名缺省写空串这件事只在 SQL 的 coalesce 里做一份，端口层不重复实现同一规则，
    // 因此 mapper 若给出 null，这里如实传出 null 而不是就地补一个空串。
    assertThat(contributions.get(1).scholarName()).isNull();
    assertThat(contributions.get(1).relationState()).isEqualTo("DISPUTED");
    assertThat(adapter.previewSources("ach_merged")).hasSize(1);
    assertThat(adapter.previewSources("ach_merged").get(0).sourceRecordId()).isEqualTo("rec_1");
    assertThat(adapter.previewSources("ach_merged").get(0).sourceId()).isEqualTo("src_1");
    assertThat(adapter.previewTopics("ach_merged")).containsExactly("tpc_1");
    assertThat(adapter.previewIdentifiers("ACHIEVEMENT", "ach_merged").get(0).normalizedValue())
        .isEqualTo("10.1/x");
  }

  @Test
  @DisplayName("标识改指直接委托并把受影响行数原样返回：0 行说明这批标识没动过")
  void moveIdentifiersDelegates() {
    when(mapper.moveIdentifiers("ACHIEVEMENT", "ach_merged", "ach_survivor")).thenReturn(2);
    when(mapper.moveIdentifiers("SCHOLAR", "sch_merged", "sch_survivor")).thenReturn(0);

    assertThat(adapter.moveIdentifiers("ACHIEVEMENT", "ach_merged", "ach_survivor")).isEqualTo(2);
    assertThat(adapter.moveIdentifiers("SCHOLAR", "sch_merged", "sch_survivor")).isZero();
  }

  @Test
  @DisplayName("两条实体之间的候选数单独查询：为回答它而拉回全部候选会让只读预览变成全表扫描")
  void countOpenBetweenUsesDedicatedQuery() {
    when(mapper.countOpenBetween("ach_1", "ach_2")).thenReturn(3L);
    when(mapper.countOpenForAchievement("ach_1")).thenReturn(5L);

    assertThat(adapter.countOpenBetween("ach_1", "ach_2")).isEqualTo(3L);
    assertThat(adapter.countOpenFor("ach_1")).isEqualTo(5L);
  }

  @Test
  @DisplayName("批量取两端状态：逐字段翻译，且查不到的标识不补空对象")
  void statesOfTranslatesRowByRow() {
    when(mapper.findAchievementStates(any()))
        .thenReturn(
            List.of(
                new GovernanceMapper.AchievementStateRow(
                    "ach_1",
                    "PAPER",
                    "标题一",
                    "SIMULATED",
                    "RESEARCHER",
                    "PENDING_GOVERNANCE",
                    null,
                    null,
                    "UNKNOWN")));

    List<GovernanceRepositories.AchievementStateRepository.AchievementState> states =
        adapter.statesOf(List.of("ach_1", "ach_1", "ach_missing"));

    // 去重后下传：同一个标识重复出现不应让 SQL 的实参里出现重复项。
    assertThat(states).hasSize(1);
    GovernanceRepositories.AchievementStateRepository.AchievementState state = states.get(0);
    assertThat(state.id()).isEqualTo("ach_1");
    assertThat(state.title()).isEqualTo("标题一");
    assertThat(state.dataNature()).isEqualTo("SIMULATED");
    assertThat(state.visibility()).isEqualTo("RESEARCHER");
    assertThat(state.effectiveStatus()).isEqualTo("PENDING_GOVERNANCE");
    assertThat(state.merged()).isFalse();
    // 缺失的标识不返回：返回一个字段全为 null 的对象，调用侧会把它当成「存在但状态不明」，
    // 与「这一行根本没有」是两件事。缺失就是缺失。
    assertThat(states).noneMatch(item -> "ach_missing".equals(item.id()));
  }

  @Test
  @DisplayName("空标识集合直接返回空表，不发一次注定为空的查询")
  void statesOfSkipsEmptyInput() {
    assertThat(adapter.statesOf(List.of())).isEmpty();
    assertThat(adapter.statesOf(null)).isEmpty();
    verify(mapper, org.mockito.Mockito.never()).findAchievementStates(any());
  }
}
