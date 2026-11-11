package com.cognitanexus.core.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.EffectiveStatus;
import com.cognitanexus.core.catalog.domain.PublicationYear;
import com.cognitanexus.core.catalog.domain.YearPrecision;
import com.cognitanexus.core.catalog.infrastructure.RelatedMapper;
import com.cognitanexus.core.catalog.infrastructure.RelatedMapper.CandidateRow;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.Visibility;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 相关成果推荐的三条口径。
 *
 * <p>这三条都是「不说清楚就会被误读」的地方：低于门槛的信号不展示、理由必须逐条给出、
 * 结果为空时必须说明「未采集不等于不相关」。
 */
@ExtendWith(MockitoExtension.class)
class RelatedAchievementServiceTest {

  private static final String BASE_ID = "ach_base";

  @Mock private RelatedMapper mapper;
  @Mock private PublicCatalogQueryService publicCatalog;

  private RelatedAchievementService service;

  @BeforeEach
  void setUp() {
    service = new RelatedAchievementService(mapper, publicCatalog);
  }

  private static Achievement achievement(String id) {
    return new Achievement(
        id,
        AchievementType.PAPER,
        "Cancer immunotherapy",
        "cancer immunotherapy",
        DataNature.REAL,
        Visibility.PUBLIC,
        EffectiveStatus.EFFECTIVE,
        new PublicationYear(2020, YearPrecision.YEAR),
        null,
        1L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static CandidateRow candidate(String id, long score) {
    return new CandidateRow(id, "标题 " + id, 2021, "Nature", "10.1000/x", 120, 2, 1, 3, 4, score);
  }

  private void publicTarget(String id, String redirectedFrom) {
    when(publicCatalog.requirePublicTarget(anyString()))
        .thenReturn(new PublicCatalogQueryService.PublicTarget(achievement(id), redirectedFrom));
  }

  @Test
  void filtersCandidatesBelowMinimumScore() {
    publicTarget(BASE_ID, null);
    // 只有单个共同主题时分数是 3，低于门槛 6：这类候选点开是浪费读者时间。
    when(mapper.findRelated(BASE_ID, 24))
        .thenReturn(List.of(candidate("ach_low", 3), candidate("ach_ok", 46)));

    Map<String, Object> view = service.related(BASE_ID, 0);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) view.get("items");
    assertThat(items).hasSize(1);
    assertThat(items.get(0)).containsEntry("achievementId", "ach_ok");
    assertThat((Integer) view.get("count")).isEqualTo(1);
  }

  @Test
  void defaultLimitIsEightAndCappedAtTwenty() {
    publicTarget(BASE_ID, null);
    when(mapper.findRelated(BASE_ID, 24)).thenReturn(List.of());
    service.related(BASE_ID, 0);
    // 多取三倍再按门槛过滤：门槛只在 Java 侧判断，避免同一条件写在两处。
    verify(mapper).findRelated(BASE_ID, 24);

    publicTarget(BASE_ID, null);
    when(mapper.findRelated(BASE_ID, 60)).thenReturn(List.of());
    service.related(BASE_ID, 999);
    verify(mapper).findRelated(BASE_ID, 60);
  }

  @Test
  void reasonsAreOrderedBySignalStrength() {
    publicTarget(BASE_ID, null);
    when(mapper.findRelated(BASE_ID, 24)).thenReturn(List.of(candidate("ach_1", 78)));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items =
        (List<Map<String, Object>>) service.related(BASE_ID, 0).get("items");

    @SuppressWarnings("unchecked")
    List<String> reasons = (List<String>) items.get(0).get("reasons");
    // 同作者最可信，读者先看到的就是它；顺序与权重一致，不是按数据库返回顺序。
    assertThat(reasons)
        .containsExactly("共有 1 位相同作者", "有 4 篇文献同时引用了两者", "与本文共享 3 篇参考文献", "同属 2 个主题");

    @SuppressWarnings("unchecked")
    Map<String, Object> evidence = (Map<String, Object>) items.get(0).get("evidence");
    assertThat(evidence)
        .containsEntry("sharedAuthors", 1L)
        .containsEntry("coCitedBy", 4L)
        .containsEntry("sharedReferences", 3L)
        .containsEntry("sharedTopics", 2L);
  }

  @Test
  void emptyResultExplainsMissingCoverageInsteadOfClaimingIrrelevance() {
    publicTarget(BASE_ID, null);
    when(mapper.findRelated(BASE_ID, 24)).thenReturn(List.of());

    Map<String, Object> view = service.related(BASE_ID, 0);

    assertThat((Integer) view.get("count")).isZero();
    // 「没有被推荐」不等于「不相关」：引用采集是抽样的，这句话必须出现在结果里。
    assertThat(view).containsKey("emptyReason");
    assertThat((String) view.get("note")).contains("抽样");
  }

  @Test
  void mergedSourceReportsRedirect() {
    publicTarget("ach_new", BASE_ID);
    when(mapper.findRelated("ach_new", 24)).thenReturn(List.of());

    Map<String, Object> view = service.related(BASE_ID, 0);

    // 合并重定向：查询用旧 ID，结果指向合并后的实体，并明确告知调用方。
    assertThat(view)
        .containsEntry("achievementId", "ach_new")
        .containsEntry("redirectedFrom", BASE_ID);
  }

  @Test
  void nonPublicTargetFailsBeforeQuerying() {
    when(publicCatalog.requirePublicTarget(BASE_ID))
        .thenThrow(
            new com.cognitanexus.sharedkernel.BusinessException(
                com.cognitanexus.sharedkernel.ErrorCode.NOT_FOUND, "成果不存在或未公开"));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.related(BASE_ID, 0))
        .isInstanceOf(com.cognitanexus.sharedkernel.BusinessException.class);
    // 受限成果不能因为「被推荐」而开出一条旁路入口。
    verify(mapper, never()).findRelated(anyString(), anyInt());
  }
}
