package com.cognitanexus.core.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.EffectiveStatus;
import com.cognitanexus.core.catalog.domain.PublicationYear;
import com.cognitanexus.core.governance.application.GovernanceService;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.event.OutboxStore;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.Visibility;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 人工确认发布的状态门禁。
 *
 * <p>锁住一条具体的缺陷：撤销发布（WITHDRAWN）此前没有任何恢复入口——确认发布只接受待治理状态，
 * 于是一次误撤或者「先下架、核实后再上架」的处置会把成果永久钉在不可见状态，界面上既不提示
 * 也不给路径。下架与重新上架是一对对称的人工作业，后者必须存在，并且仍然受「真实数据」
 * 「类型扩展恰好一个」等放行条件约束。
 */
@ExtendWith(MockitoExtension.class)
class AdminCatalogConfirmTest {

  @Mock AchievementRepository achievements;
  @Mock CatalogRepositories.ScholarRepository scholars;
  @Mock CatalogRepositories.ContributionRepository contributions;
  @Mock CatalogRepositories.SourceRecordRepository sourceRecords;
  @Mock CatalogRepositories.SourceConfigRepository sourceConfigs;
  @Mock CatalogRepositories.TopicRepository topics;
  @Mock CatalogRepositories.FieldProvenanceRepository provenance;
  @Mock CatalogRepositories.IdentifierRegistryRepository identifiers;
  @Mock GovernanceService governance;
  @Mock PublicCatalogQueryService publicCatalog;
  @Mock OutboxStore outbox;
  @Mock AuditRecorder audit;
  @Mock PublicProjectionAssembler projections;

  private AdminCatalogService service;
  private ActorContext actor;

  @BeforeEach
  void setUp() {
    service =
        new AdminCatalogService(
            achievements,
            scholars,
            contributions,
            sourceRecords,
            sourceConfigs,
            topics,
            provenance,
            identifiers,
            governance,
            publicCatalog,
            outbox,
            audit,
            projections,
            new CatalogChangePublisher(
                achievements,
                projections,
                outbox,
                id -> com.cognitanexus.core.integrity.domain.IntegritySummary.STATUS_NONE),
            id -> com.cognitanexus.core.integrity.domain.IntegritySummary.STATUS_NONE);
    actor =
        new ActorContext(
            new AccountId("acc_reviewer"), "reviewer", "审核员", Set.of(RoleNames.REVIEWER), 1L);
  }

  private static Achievement paper(String id, EffectiveStatus status) {
    return new Achievement(
        id,
        AchievementType.PAPER,
        "A Title",
        "a title",
        DataNature.REAL,
        status == EffectiveStatus.EFFECTIVE ? Visibility.PUBLIC : Visibility.RESTRICTED,
        status,
        PublicationYear.of(2024),
        null,
        3L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  @DisplayName("已下架的成果可以重新确认发布：下架不再是不可逆的死路")
  void withdrawnCanBeRepublished() {
    // 第一次读用于判定前置状态，第二次读是写成功后重新取回的新实体——服务端返回的视图必须
    // 反映库里的事实，所以这两次不能是同一个快照。
    when(achievements.findById("ach_withdrawn"))
        .thenReturn(
            Optional.of(paper("ach_withdrawn", EffectiveStatus.WITHDRAWN)),
            Optional.of(paper("ach_withdrawn", EffectiveStatus.EFFECTIVE)));
    when(achievements.findPaper("ach_withdrawn"))
        .thenReturn(
            Optional.of(
                new com.cognitanexus.core.catalog.domain.AchievementDetails.Paper(
                    "ach_withdrawn",
                    "10.1000/x",
                    "10.1000/x",
                    "A Journal",
                    null,
                    null,
                    3,
                    "Crossref",
                    null)));
    when(achievements.updatePublicationState(
            eq("ach_withdrawn"), anyString(), anyString(), anyLong()))
        .thenReturn(1);
    when(publicCatalog.publiclyVisible("ach_withdrawn")).thenReturn(false);

    var view = service.confirmPublication(actor, "ach_withdrawn", "核实完毕，恢复上架");

    assertThat(view.get("effectiveStatus")).isEqualTo(EffectiveStatus.EFFECTIVE.name());
    verify(achievements)
        .updatePublicationState(
            "ach_withdrawn", EffectiveStatus.EFFECTIVE.name(), Visibility.PUBLIC.name(), 3L);
  }

  @Test
  @DisplayName("已生效的成果不能被「再次确认」：这不是一个可以重复执行的动作")
  void effectiveCannotBeConfirmedAgain() {
    when(achievements.findById("ach_live"))
        .thenReturn(Optional.of(paper("ach_live", EffectiveStatus.EFFECTIVE)));

    assertThatThrownBy(() -> service.confirmPublication(actor, "ach_live", "重复确认"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("待治理或已下架");

    verify(achievements, never())
        .updatePublicationState(anyString(), anyString(), anyString(), anyLong());
  }

  @Test
  @DisplayName("类型扩展不是一个的成果仍然不能发布：重新上架也不能绕过放行条件")
  void withdrawnStillNeedsExactlyOneExtension() {
    when(achievements.findById("ach_broken"))
        .thenReturn(Optional.of(paper("ach_broken", EffectiveStatus.WITHDRAWN)));
    when(achievements.findPaper("ach_broken")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.confirmPublication(actor, "ach_broken", "恢复上架"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("类型扩展数量");

    verify(achievements, never())
        .updatePublicationState(anyString(), anyString(), anyString(), anyLong());
  }
}
