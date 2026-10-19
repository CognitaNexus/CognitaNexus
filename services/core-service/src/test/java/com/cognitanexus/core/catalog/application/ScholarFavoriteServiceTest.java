package com.cognitanexus.core.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.domain.Scholar;
import com.cognitanexus.core.catalog.infrastructure.ScholarFavoriteMapper;
import com.cognitanexus.core.catalog.infrastructure.ScholarFavoriteMapper.FavoriteRow;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 收藏的四条规则。
 *
 * <p>收藏看起来只是一个开关，但有三件事容易做错：把模拟数据收进来（收藏数会被当成真实社会证明）、
 * 对已合并档案收藏（收藏指向一个不再存在的实体）、未登录时把「不知道」当成「没有收藏」。
 */
@ExtendWith(MockitoExtension.class)
class ScholarFavoriteServiceTest {

  private static final String ACCOUNT_ID = "acc_1";

  @Mock private ScholarFavoriteMapper favorites;
  @Mock private CatalogRepositories.ScholarRepository scholars;

  private ScholarFavoriteService service;
  private ActorContext actor;

  @BeforeEach
  void setUp() {
    service = new ScholarFavoriteService(favorites, scholars);
    actor = new ActorContext(new AccountId(ACCOUNT_ID), "u1", "用户一", Set.of(RoleNames.USER), 1L);
  }

  private static Scholar scholar(String id, DataNature nature, String mergedIntoId) {
    return new Scholar(
        id,
        "张伟",
        "张伟",
        nature,
        Scholar.ClaimedStatus.UNCLAIMED,
        "北京航空航天大学",
        null,
        mergedIntoId,
        1L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  void favoriteWritesAndReturnsPublicCount() {
    when(scholars.findById("sch_1"))
        .thenReturn(Optional.of(scholar("sch_1", DataNature.REAL, null)));
    when(favorites.insert(ACCOUNT_ID, "sch_1")).thenReturn(1);
    when(favorites.countByScholar("sch_1")).thenReturn(3L);

    var result = service.favorite(actor, "sch_1");

    assertThat(result.favorited()).isTrue();
    assertThat(result.favoriteCount()).isEqualTo(3L);
    assertThat(result.note()).doesNotContain("此前已收藏");
  }

  @Test
  void favoriteIsIdempotentAndSaysSo() {
    when(scholars.findById("sch_1"))
        .thenReturn(Optional.of(scholar("sch_1", DataNature.REAL, null)));
    // 主键冲突被 ON CONFLICT DO NOTHING 吸收，返回 0 行：这是幂等，不是失败。
    when(favorites.insert(ACCOUNT_ID, "sch_1")).thenReturn(0);
    when(favorites.countByScholar("sch_1")).thenReturn(1L);

    var result = service.favorite(actor, "sch_1");

    assertThat(result.favorited()).isTrue();
    assertThat(result.note()).contains("此前已收藏");
  }

  @Test
  void favoriteRejectsMergedScholar() {
    when(scholars.findById("sch_old"))
        .thenReturn(Optional.of(scholar("sch_old", DataNature.REAL, "sch_new")));

    assertThatThrownBy(() -> service.favorite(actor, "sch_old"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("合并");
    verify(favorites, never()).insert(anyString(), anyString());
  }

  @Test
  void favoriteRejectsSimulatedScholar() {
    when(scholars.findById("sch_sim"))
        .thenReturn(Optional.of(scholar("sch_sim", DataNature.SIMULATED, null)));

    // 模拟数据被收藏后，收藏数会被当成真实的社会证明展示出去。
    assertThatThrownBy(() -> service.favorite(actor, "sch_sim"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("模拟");
    verify(favorites, never()).insert(anyString(), anyString());
  }

  @Test
  void favoriteRequiresLogin() {
    assertThatThrownBy(() -> service.favorite(null, "sch_1")).isInstanceOf(BusinessException.class);
    verifyNoInteractions(favorites, scholars);
  }

  @Test
  void unfavoriteDoesNotRequireScholarToBeCollectable() {
    when(favorites.delete(ACCOUNT_ID, "sch_old")).thenReturn(1);
    when(favorites.countByScholar("sch_old")).thenReturn(0L);

    var result = service.unfavorite(actor, "sch_old");

    assertThat(result.favorited()).isFalse();
    // 档案后来被限制或合并，用户仍有权把自己的收藏条目去掉，因此不校验学者可收藏性。
    verify(scholars, never()).findById(anyString());
  }

  @Test
  void statusWithoutLoginReturnsNullRatherThanFalse() {
    when(favorites.countByScholar("sch_1")).thenReturn(5L);

    var view = service.status(null, "sch_1");

    assertThat(view).containsEntry("favoriteCount", 5L);
    // null 表示「不知道」，false 表示「登录了但没收藏」——前端要能区分这两种情况。
    assertThat(view.get("favorited")).isNull();
    verify(favorites, never()).exists(anyString(), anyString());
  }

  @Test
  void statusWithLoginReportsExistence() {
    when(favorites.countByScholar("sch_1")).thenReturn(1L);
    when(favorites.exists(ACCOUNT_ID, "sch_1")).thenReturn(1L);

    assertThat(service.status(actor, "sch_1")).containsEntry("favorited", true);
  }

  @Test
  void listCapsLimitAndMapsRows() {
    when(favorites.listByAccount(ACCOUNT_ID, 100))
        .thenReturn(
            List.of(
                new FavoriteRow("sch_1", "张伟", "北京航空航天大学", Instant.parse("2026-10-01T00:00:00Z"))));

    var rows = service.list(actor, 9999);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("scholarId", "sch_1").containsEntry("displayName", "张伟");
    verify(favorites).listByAccount(ACCOUNT_ID, 100);
    verify(favorites, never()).countByAccount(anyString());
  }

  @Test
  void publicCountOnlyReadsAggregate() {
    when(favorites.countByScholar("sch_1")).thenReturn(7L);

    assertThat(service.publicCount("sch_1")).isEqualTo(7L);
    // 公开侧只给数量，不给收藏者名单：谁在关注谁是敏感信息，不该由功能顺手公开。
    verify(favorites, never()).listByAccount(anyString(), anyInt());
  }
}
