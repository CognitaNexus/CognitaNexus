package com.cognitanexus.core.catalog.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.EffectiveStatus;
import com.cognitanexus.core.platform.event.EventTypes;
import com.cognitanexus.core.platform.event.OutboxEvent;
import com.cognitanexus.core.platform.event.OutboxStore;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.Visibility;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 目录成果变更事件的外发口径。
 *
 * <p>依据：架构 V0.2 §7.1、§7.3。
 *
 * <p><b>为什么必须只有这一个出口</b>：这条载荷原先在两个服务里各拼一份——来源接入拼了论文指标
 * （doi、venue、引用量、摘要），人工确认发布那份没拼。于是同一条成果「被自动发布」与「被人工
 * 确认发布」发出去的事件字段集不同，查询侧按后者重建时会把论文指标整段写成空。这类偏差只在
 * 特定路径上出现，测试覆盖正常的路径永远发现不了。
 *
 * <p>因此本组件是唯一装配点，字段集只在这里定义一次；新增字段时两个调用方同时生效。
 *
 * <p><b>可见性与生效状态必须取自持久化实体，不得在此推断。</b>这里曾经出过一个真实缺陷：早先
 * 载荷把 visibility/effectiveStatus 写死成 PUBLIC/EFFECTIVE，理由是「本方法只在通过自动发布
 * 判定之后被调用」。但该方法还有第二个调用点——既有身份收到来源新版本时刷新引用量。于是一条
 * 仍处于 RESTRICTED/PENDING_GOVERNANCE 的成果，只要来源刷新了引用量，就会外发一条自称 PUBLIC
 * 的事件，查询侧据此把它放进公开检索。结果是公开详情返回 404、公开检索却能搜到它——权威侧说
 * 不可见，投影侧说可见。
 *
 * <p>教训不是「多加一个判断」，而是：写死一个断言，等于把断言成立的隐含前提变成全局约定，后来
 * 任何新增调用点都会静默继承这个错误前提。因此一律以数据库为唯一事实来源。
 */
@Component
public class CatalogChangePublisher {

  private static final Logger log = LoggerFactory.getLogger(CatalogChangePublisher.class);

  private final AchievementRepository achievements;
  private final PublicProjectionAssembler projections;
  private final OutboxStore outbox;

  public CatalogChangePublisher(
      AchievementRepository achievements,
      PublicProjectionAssembler projections,
      OutboxStore outbox) {
    this.achievements = achievements;
    this.projections = projections;
    this.outbox = outbox;
  }

  /**
   * 按持久化实体现状外发一次目录变更。
   *
   * <p>取不到实体时<b>失败关闭</b>：按受限可见性与待治理状态外发。查不到实体时宣称「已公开」，
   * 是这类错误里后果最重的一种——公开视图会先于权威表把内容放出去，而事后很难判断哪些是误放的。
   *
   * @param type 类型与标题仅用于实体缺失时的兜底；实体存在时一律以库内值为准
   */
  public void publish(
      String achievementId,
      AchievementType type,
      String title,
      DataNature dataNature,
      long aggregateVersion) {
    Achievement current = achievements.findById(achievementId).orElse(null);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("changeKind", EventTypes.CHANGE_UPSERT);
    payload.put("achievementId", achievementId);
    payload.put("type", type.name());
    payload.put("title", title);
    payload.put("dataNature", dataNature.name());
    if (current == null) {
      log.warn(
          "外发目录变更时未能读到实体，按受限可见性外发 achievementId={} aggregateVersion={}",
          achievementId,
          aggregateVersion);
      payload.put("visibility", Visibility.RESTRICTED.name());
      payload.put("effectiveStatus", EffectiveStatus.PENDING_GOVERNANCE.name());
    } else {
      payload.put("visibility", current.visibility().name());
      payload.put("effectiveStatus", current.effectiveStatus().name());
      payload.put("publicationYear", current.publicationYear().year());
      payload.put("titleNorm", current.titleNorm());
    }
    // 类型扩展字段：缺哪个就少哪个，不用空字符串冒充「来源给了空值」。
    achievements
        .findPaper(achievementId)
        .ifPresent(
            p -> {
              payload.put("doi", p.doi());
              payload.put("venue", p.venue());
              payload.put("citationCount", p.citationCount());
              payload.put("citationSource", p.citationSource());
              payload.put(
                  "publishedDate", p.publishedDate() == null ? null : p.publishedDate().toString());
              payload.put("abstractText", p.abstractText());
            });

    payload.put("contributions", projections.contributionsOf(achievementId));
    payload.put("topics", projections.topicIdsOf(achievementId));
    payload.put("topicViews", projections.topicsOf(achievementId));

    outbox.append(
        OutboxEvent.builder(
                EventTypes.CATALOG_CHANGE_COMMITTED, EventTypes.AGG_ACHIEVEMENT, achievementId)
            .aggregateVersion(aggregateVersion)
            .payload(payload));
  }

  /** 便捷入口：调用方手上就有一条刚读出的实体，字段直接取自它。 */
  public void publish(Achievement achievement, long aggregateVersion) {
    publish(
        achievement.id(),
        achievement.type(),
        achievement.title(),
        achievement.dataNature(),
        aggregateVersion);
  }
}
