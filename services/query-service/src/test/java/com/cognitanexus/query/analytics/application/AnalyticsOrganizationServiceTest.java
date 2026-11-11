package com.cognitanexus.query.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.analytics.infrastructure.AnalyticsMapper;
import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 机构维度的行为约束（FR11 机构统计的可浏览版本）。
 *
 * <p>这一组只盯三件容易出错、且错了之后看不出来的事：
 *
 * <ol>
 *   <li>机构名必须<b>逐字</b>传给查询层。若在这里做 trim、大小写归一或模糊匹配，点进 A 机构会
 *       看到 B 机构的成果，而页面本身没有任何异常迹象；
 *   <li>分页参数必须夹取。{@code size=100000} 会让一次点击拉全表，在统计接口上是可被滥用的入口；
 *   <li>空机构名必须拒绝，而不是回落到「全部机构」——那会把一次误调用变成一次全量导出。
 * </ol>
 */
class AnalyticsOrganizationServiceTest {

  private static final String GENERATION = "gen-7";
  private static final long PUBLISHED_REVISION = 12695L;

  private AnalyticsMapper mapper;
  private PublicBarrierGuard guard;
  private AnalyticsService service;

  @BeforeEach
  void setUp() {
    mapper = mock(AnalyticsMapper.class);
    guard = mock(PublicBarrierGuard.class);
    service = new AnalyticsService(mapper, guard);
  }

  private void givenServing() {
    when(guard.assertPubliclyServed())
        .thenReturn(
            new PublicBarrierGuard.BarrierSnapshot(
                PUBLISHED_REVISION, 12700L, 12700L, PUBLISHED_REVISION, GENERATION));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> section(Map<String, Object> data, String key) {
    Object value = data.get(key);
    assertThat(value).as("响应中必须存在 %s", key).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> rows(Map<String, Object> data, String key) {
    Object value = data.get(key);
    assertThat(value).as("响应中必须存在 %s", key).isInstanceOf(List.class);
    return (List<Map<String, Object>>) value;
  }

  @Test
  @DisplayName("机构清单：缺省口径为 REAL，计数与学者数原样透出，口径说明随数据返回")
  void organizationsDefaultScope() {
    givenServing();
    when(mapper.pageOrganizations(eq(GENERATION), eq("REAL"), isNull(), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                new AnalyticsMapper.OrganizationRow("Tsinghua University", 159L, 398L),
                new AnalyticsMapper.OrganizationRow("Peking University", 120L, 300L)));
    when(mapper.countOrganizations(GENERATION, "REAL", null)).thenReturn(2L);

    Map<String, Object> data = service.organizations(null, null, 1, 20);

    assertThat(data.get("total")).isEqualTo(2L);
    assertThat(data.get("page")).isEqualTo(1);
    assertThat(data.get("size")).isEqualTo(20);
    assertThat(data.get("publishedRevision")).isEqualTo(PUBLISHED_REVISION);
    List<Map<String, Object>> items = rows(data, "items");
    assertThat(items).hasSize(2);
    assertThat(items.get(0).get("organization")).isEqualTo("Tsinghua University");
    assertThat(items.get(0).get("achievementCount")).isEqualTo(159L);
    assertThat(items.get(0).get("scholarCount")).isEqualTo(398L);
    // 口径说明不能缺：缺了读者会把它当成「该机构全部成果」而不是「当前发布版本下的公开合格成果」
    assertThat(section(data, "scope")).containsKey("note");
    assertThat(section(data, "countingRules")).containsKey("grouping");
  }

  @Test
  @DisplayName("机构清单：空白搜索词按不限处理，不当成匹配空串")
  void organizationsBlankQueryBecomesNull() {
    givenServing();
    when(mapper.pageOrganizations(anyString(), anyString(), isNull(), anyInt(), anyInt()))
        .thenReturn(List.of());
    when(mapper.countOrganizations(anyString(), anyString(), isNull())).thenReturn(0L);

    Map<String, Object> data = service.organizations("REAL", "   ", 1, 20);

    verify(mapper).pageOrganizations(GENERATION, "REAL", null, 20, 0);
    assertThat(data.get("query")).isNull();
  }

  @Test
  @DisplayName("机构清单：页码与页大小都被夹取，size 不得突破上限")
  void organizationsClampsPaging() {
    givenServing();
    when(mapper.pageOrganizations(anyString(), anyString(), isNull(), anyInt(), anyInt()))
        .thenReturn(List.of());
    when(mapper.countOrganizations(anyString(), anyString(), isNull())).thenReturn(0L);

    Map<String, Object> data = service.organizations("REAL", null, -5, 100000);

    assertThat(data.get("page")).isEqualTo(1);
    assertThat(data.get("size")).isEqualTo(100);
    verify(mapper).pageOrganizations(GENERATION, "REAL", null, 100, 0);
  }

  @Test
  @DisplayName("机构清单：搜索词按原文传给查询层，不做大小写或空白归一")
  void organizationsPassQueryVerbatim() {
    givenServing();
    when(mapper.pageOrganizations(anyString(), anyString(), anyString(), anyInt(), anyInt()))
        .thenReturn(List.of());
    when(mapper.countOrganizations(anyString(), anyString(), anyString())).thenReturn(0L);

    service.organizations("REAL", "  Tsinghua ", 1, 20);

    verify(mapper).pageOrganizations(GENERATION, "REAL", "Tsinghua", 20, 0);
  }

  @Test
  @DisplayName("机构详情：机构名逐字传给查询层，不得归一化")
  void organizationDetailPassesNameVerbatim() {
    givenServing();
    String name = "Taylor's University,School of Computer Science,Subang Jaya,Malaysia";
    when(mapper.pageOrganizationAchievements(
            anyString(), anyString(), anyString(), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                new AnalyticsMapper.OrganizationAchievementRow(
                    "ach_1", "A paper", "PAPER", 2024, "Venue", "10.1/x", 12L)));
    when(mapper.countOrganizationAchievements(GENERATION, "REAL", name)).thenReturn(1L);

    Map<String, Object> data = service.organization(name, null, 1, 20);

    verify(mapper).pageOrganizationAchievements(GENERATION, "REAL", name, 20, 0);
    assertThat(data.get("organization")).isEqualTo(name);
    assertThat(data.get("total")).isEqualTo(1L);
    List<Map<String, Object>> items = rows(data, "items");
    assertThat(items).hasSize(1);
    assertThat(items.get(0).get("achievementId")).isEqualTo("ach_1");
    assertThat(items.get(0).get("publicationYear")).isEqualTo(2024);
  }

  @Test
  @DisplayName("机构详情：空机构名直接拒绝，不回落到全部机构")
  void organizationDetailRejectsBlankName() {
    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> service.organization("   ", "REAL", 1, 20))
        .satisfies(ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
    // 连屏障都不该被读：参数不合法时不应打开任何数据通路
    verify(guard, never()).assertPubliclyServed();
    verify(mapper, never())
        .pageOrganizationAchievements(anyString(), anyString(), anyString(), anyInt(), anyInt());
  }

  @Test
  @DisplayName("机构详情：公开水位不达标时向上抛出，不返回半旧数据")
  void organizationDetailPropagatesBarrier() {
    when(guard.assertPubliclyServed())
        .thenThrow(new BusinessException(ErrorCode.VIEW_UPDATING, "视图正在更新"));

    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> service.organization("Tsinghua University", "REAL", 1, 20))
        .satisfies(ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VIEW_UPDATING));
  }
}
