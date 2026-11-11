package com.cognitanexus.core.catalog.application;

import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.infrastructure.RelatedMapper;
import com.cognitanexus.core.catalog.infrastructure.RelatedMapper.CandidateRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 相关成果推荐。
 *
 * <p>依据：需求基线「相关成果」扩展项。四条信号（同作者、共被引、共享参考文献、同主题）由
 * {@link RelatedMapper} 一条 SQL 算出，本类只做三件事：校验公开资格、生成人话理由、给出覆盖说明。
 *
 * <p>为什么理由必须逐条回给前端，而不是只给一个分数：
 * 读者看到一个「相关成果」列表，只有分数没有理由时，无法判断它是「同一批作者」还是「同一个
 * 大主题下的随机一篇」。前者值得点开，后者多半是噪音。把证据摊开，读者能自己决定信不信。
 *
 * <p>为什么必须带覆盖说明：
 * 推荐依赖已采集的引用列表，而引用采集是抽样的（平台只对部分成果拉过参考文献）。不说明这一点，
 * 「没有相关成果」会被读成「这篇论文没有相关工作」——这是对学术内容的事实性误述，比不推荐更糟。
 */
@Service
public class RelatedAchievementService {

  private static final int DEFAULT_LIMIT = 8;
  private static final int MAX_LIMIT = 20;

  /**
   * 最低分门槛。
   *
   * <p>只有「同主题」一条信号时，分数是 3×主题数（上限 18）。门槛设在 6：至少共享 2 个主题
   * 才值得出现在「相关成果」里。单个共同主题在大类下等于没有约束力，那种候选点开是浪费读者时间。
   */
  private static final long MIN_SCORE = 6;

  private final RelatedMapper mapper;
  private final PublicCatalogQueryService publicCatalog;

  public RelatedAchievementService(RelatedMapper mapper, PublicCatalogQueryService publicCatalog) {
    this.mapper = mapper;
    this.publicCatalog = publicCatalog;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> related(String achievementId, int limit) {
    // 与详情、引用网络共用同一套公开资格判定：合并重定向跳到终点、资格作用在终点实体。
    // 推荐列表里出现一条受限成果，等于给受限内容开了个旁路入口。
    PublicCatalogQueryService.PublicTarget target =
        publicCatalog.requirePublicTarget(achievementId);
    Achievement achievement = target.achievement();

    int capped = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    // 多取一些再按门槛过滤：门槛在 Java 侧，SQL 里加同样的条件会让「为什么这条没进来」
    // 变成两处判断，改一处忘一处。
    List<CandidateRow> rows = mapper.findRelated(achievement.id(), capped * 3);

    List<Map<String, Object>> items = new ArrayList<>();
    for (CandidateRow row : rows) {
      if (row.score() < MIN_SCORE) {
        continue;
      }
      if (items.size() >= capped) {
        break;
      }
      items.add(itemView(row));
    }

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("achievementId", achievement.id());
    if (target.redirectedFrom() != null) {
      view.put("redirectedFrom", target.redirectedFrom());
      view.put("redirectNote", "该成果已被合并，本响应指向合并后的实体");
    }
    view.put("items", items);
    view.put("count", items.size());
    view.put(
        "method",
        "同作者权重最高（每个共同作者 40 分，上限 40），其次共被引（每次 8 分，上限 32），"
            + "再次共享参考文献（每篇 3 分，上限 27），同主题最低（每个 3 分，上限 18）；"
            + "低于 6 分不展示。只统计两端都在平台内的关系。");
    view.put(
        "note",
        "推荐只基于平台已收录的关系：同作者来自参与关系（不含待核验），共被引与共享参考文献来自"
            + "已采集的引用列表。引用采集是抽样进行的，未采集到的成果不会出现在这里，"
            + "因此「没有被推荐」不等于「不相关」。");
    if (items.isEmpty()) {
      view.put("emptyReason", "这篇成果目前没有可用的相关信号：可能尚未采集参考文献，也没有共同作者或共同主题。");
    }
    return view;
  }

  private static Map<String, Object> itemView(CandidateRow row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("achievementId", row.achievementId());
    item.put("title", row.title());
    item.put("publicationYear", row.publicationYear());
    item.put("venue", row.venue());
    item.put("doi", row.doi());
    item.put("citationCount", row.citationCount());
    item.put("score", row.score());

    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("sharedAuthors", row.sharedAuthors());
    evidence.put("coCitedBy", row.coCitedBy());
    evidence.put("sharedReferences", row.sharedReferences());
    evidence.put("sharedTopics", row.sharedTopics());
    item.put("evidence", evidence);
    item.put("reasons", reasons(row));
    return item;
  }

  /** 理由按可信度排序，与权重顺序一致：读者先看到最强的那条依据。 */
  private static List<String> reasons(CandidateRow row) {
    List<String> reasons = new ArrayList<>(4);
    if (row.sharedAuthors() > 0) {
      reasons.add("共有 " + row.sharedAuthors() + " 位相同作者");
    }
    if (row.coCitedBy() > 0) {
      reasons.add("有 " + row.coCitedBy() + " 篇文献同时引用了两者");
    }
    if (row.sharedReferences() > 0) {
      reasons.add("与本文共享 " + row.sharedReferences() + " 篇参考文献");
    }
    if (row.sharedTopics() > 0) {
      reasons.add("同属 " + row.sharedTopics() + " 个主题");
    }
    return reasons;
  }
}
