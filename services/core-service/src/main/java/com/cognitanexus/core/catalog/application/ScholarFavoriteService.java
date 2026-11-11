package com.cognitanexus.core.catalog.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.ScholarRepository;
import com.cognitanexus.core.catalog.domain.Scholar;
import com.cognitanexus.core.catalog.infrastructure.ScholarFavoriteMapper;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 学者收藏（关注）。
 *
 * <p>依据：需求基线扩展项。语义只说一件事：<b>收藏是个人列表，不是订阅</b>。
 *
 * <p>为什么不做「关注后收到新成果通知」：新成果入库是高频事件（一次采集可能上千条），
 * 沿关注关系扩散会产生扇出风暴，而通知一旦发出无法撤回。要做得先有批量与合并，
 * 否则上线第一天就是几百条通知，用户会直接关掉这个功能。
 *
 * <p>为什么公开侧只给数量：收藏行为在学术平台上有一定敏感性（例如跟踪某位学者）。
 * 返回收藏者名单等于把「谁在关注谁」公开，这个决定不该由功能实现顺手做出。
 */
@Service
public class ScholarFavoriteService {

  private static final int MAX_LIST = 100;

  private final ScholarFavoriteMapper favorites;
  private final ScholarRepository scholars;

  public ScholarFavoriteService(ScholarFavoriteMapper favorites, ScholarRepository scholars) {
    this.favorites = favorites;
    this.scholars = scholars;
  }

  /** 收藏结果。favorited 为 false 表示本次调用前就已经收藏过（幂等，不报错）。 */
  public record FavoriteResult(boolean favorited, long favoriteCount, String note) {}

  @Transactional
  public FavoriteResult favorite(ActorContext actor, String scholarId) {
    requireActor(actor);
    Scholar scholar = requireCollectable(scholarId);
    int inserted = favorites.insert(actor.accountIdValue(), scholar.id());
    long total = favorites.countByScholar(scholar.id());
    return new FavoriteResult(
        true,
        total,
        inserted == 0 ? "此前已收藏过这位学者；本次调用没有重复写入。" : "已收藏。收藏只影响你的列表，不会因为这位学者有新成果而给你发通知。");
  }

  @Transactional
  public FavoriteResult unfavorite(ActorContext actor, String scholarId) {
    requireActor(actor);
    // 取消收藏不要求学者当前可见：如果档案后来被限制，用户仍然有权把自己的收藏条目去掉。
    int deleted = favorites.delete(actor.accountIdValue(), scholarId);
    long total = favorites.countByScholar(scholarId);
    return new FavoriteResult(false, total, deleted == 0 ? "此前没有收藏这位学者。" : "已取消收藏。");
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> list(ActorContext actor, int limit) {
    requireActor(actor);
    int capped = Math.min(Math.max(limit, 1), MAX_LIST);
    return favorites.listByAccount(actor.accountIdValue(), capped).stream()
        .map(
            row -> {
              Map<String, Object> view = new LinkedHashMap<>();
              view.put("scholarId", row.scholarId());
              view.put("displayName", row.displayName());
              view.put("organizationName", row.organizationName());
              view.put("favoritedAt", row.favoritedAt());
              return view;
            })
        .toList();
  }

  @Transactional(readOnly = true)
  public Map<String, Object> status(ActorContext actor, String scholarId) {
    long total = favorites.countByScholar(scholarId);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("scholarId", scholarId);
    view.put("favoriteCount", total);
    // 未登录时 favorited 是 null 而不是 false：false 表示「登录了但没收藏」，
    // 这两件事在前端要区分（前者应引导登录，后者显示「收藏」按钮）。
    if (actor == null) {
      view.put("favorited", null);
      view.put("note", "未登录：只显示公开收藏数");
      return view;
    }
    view.put("favorited", favorites.exists(actor.accountIdValue(), scholarId) > 0);
    return view;
  }

  @Transactional(readOnly = true)
  public long publicCount(String scholarId) {
    return favorites.countByScholar(scholarId);
  }

  private Scholar requireCollectable(String scholarId) {
    Scholar scholar =
        scholars.findById(scholarId).orElseThrow(() -> BusinessException.notFound("学者不存在"));
    if (scholar.merged()) {
      throw BusinessException.businessRule("该学者档案已被合并，请对合并后的档案收藏");
    }
    if (scholar.dataNature() != DataNature.REAL) {
      // 模拟数据不该被收藏：收藏数会被当成真实的社会证明展示出去。
      throw BusinessException.businessRule("该学者是模拟数据，不能收藏");
    }
    return scholar;
  }

  private static void requireActor(ActorContext actor) {
    if (actor == null) {
      throw new BusinessException(ErrorCode.AUTH_REQUIRED, "请先登录");
    }
  }
}
