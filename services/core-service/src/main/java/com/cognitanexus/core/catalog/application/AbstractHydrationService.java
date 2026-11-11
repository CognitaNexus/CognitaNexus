package com.cognitanexus.core.catalog.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository.PendingAbstract;
import com.cognitanexus.core.catalog.application.CatalogRepositories.FieldProvenanceRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.platform.util.TextNormalizer;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 摘要补水：把缺摘要的可见论文补上摘要。
 *
 * <p>依据：FR02／FR16（元数据来源可追溯）。功能位置与 {@code CitationNetworkService} 平行，
 * 都是「core 给清单、采集侧去外网取、取回来投给 core 落库」的形态，但两者解决的不是同一件事：
 * 引用补水取的是「这一篇引用了谁」，摘要补水取的是「这一篇自己讲了什么」。混在一条通道里，
 * 失败重试时无法判断该重做哪一半。
 *
 * <p><b>为什么这事只能由外部来源做，不能让用户或管理员填</b>：摘要是来源给出的元数据事实。
 * 让人补录会把「来源说了什么」与「我们认为它说了什么」混成同一个字段，而这两种内容在页面上
 * 长得一模一样，事后无法区分。人工介入只能是 MANUAL 级修订，且一旦标记，自动补采即停手。
 *
 * <p><b>三道闸门</b>（见 {@link AchievementRepository#fillPaperAbstract}）：已有摘要不覆盖、
 * 人工与已审核值不覆盖、已合并成果不写。这里不做预检，预检只是为了少一次无意义的 UPDATE，
 * 不作为保护依据——保护必须在与写入同一条语句里完成，否则读与写之间就有一个可被并发人工提交
 * 插队的窗口。
 */
@Service
public class AbstractHydrationService {

  private static final Logger log = LoggerFactory.getLogger(AbstractHydrationService.class);

  /**
   * 单篇摘要长度上限。
   *
   * <p>超过就整条拒收而不是截断：截断会把来源值改成我们编的东西，而且截断后的文本在检索里
   * 仍然「能命中」，读者无从知道它被改过。宁可留着空缺，也不留一段伪造成原文的半截话。
   */
  private static final int MAX_ABSTRACT_CHARS = 40_000;

  /** 单次投递上限。与采集侧批量大小同量级，防止一次请求把事务拉得过长。 */
  private static final int MAX_ENTRIES_PER_BATCH = 200;

  private final AchievementRepository achievements;
  private final FieldProvenanceRepository provenance;
  private final CatalogChangePublisher catalogChanges;

  public AbstractHydrationService(
      AchievementRepository achievements,
      FieldProvenanceRepository provenance,
      CatalogChangePublisher catalogChanges) {
    this.achievements = achievements;
    this.provenance = provenance;
    this.catalogChanges = catalogChanges;
  }

  /**
   * 待补摘要清单。
   *
   * @param limit 本批最多多少篇
   * @param search 题名关键字，用于集中补一个子领域
   */
  @Transactional(readOnly = true)
  public PendingPage pendingAbstracts(int limit, String search) {
    int cap = Math.min(Math.max(limit, 1), MAX_ENTRIES_PER_BATCH);
    String normalized = blankToNull(TextNormalizer.normalize(search));
    List<PendingAbstract> rows = achievements.listPendingAbstracts(cap, normalized);
    List<Map<String, Object>> items = new ArrayList<>(rows.size());
    for (PendingAbstract row : rows) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("achievementId", row.achievementId());
      item.put("doi", row.doi());
      item.put("title", row.title());
      items.add(item);
    }
    return new PendingPage(items, achievements.countPendingAbstracts(normalized));
  }

  /**
   * 接收一批取回的摘要。
   *
   * <p>{@code abstractText} 为空表示「来源没有这一篇的摘要」。这一条必须回报而不是不回：不回的话
   * core 无从知道已经试过，下一轮会把它重新放进待补清单，永远重复同一个请求。
   *
   * @return 各计数的回执；{@code skipped} 覆盖「已有人工修订」「已合并」「已有摘要」三种正常跳过
   */
  @Transactional
  public FillReceipt recordAbstracts(List<Entry> entries, String provider) {
    if (entries == null || entries.isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "摘要批次为空");
    }
    if (entries.size() > MAX_ENTRIES_PER_BATCH) {
      throw new BusinessException(
          ErrorCode.INVALID_ARGUMENT, "单批摘要不得超过 " + MAX_ENTRIES_PER_BATCH + " 条");
    }
    int filled = 0;
    int notFound = 0;
    int skipped = 0;
    int tooLong = 0;
    for (Entry entry : entries) {
      if (entry == null || entry.achievementId() == null || entry.achievementId().isBlank()) {
        skipped++;
        continue;
      }
      String text = entry.abstractText() == null ? null : entry.abstractText().strip();
      if (text != null && text.length() > MAX_ABSTRACT_CHARS) {
        // 不截断、也不标记已尝试：这是异常长的响应，下一轮重试仍可能拿到正常长度的一条
        log.warn(
            "摘要过长已拒收 achievementId={} 长度={} 上限={}",
            entry.achievementId(),
            text.length(),
            MAX_ABSTRACT_CHARS);
        tooLong++;
        continue;
      }
      if (text == null || text.isEmpty()) {
        // 来源没有摘要：只记时刻，不写空文本（空串会让页面显示一段空白摘要）
        if (achievements.markAbstractChecked(entry.achievementId()) == 1) {
          notFound++;
        } else {
          skipped++;
        }
        continue;
      }
      int updated = achievements.fillPaperAbstract(entry.achievementId(), text);
      if (updated != 1) {
        // 0 有四种可能：已有摘要、受人工修订保护、成果已被合并、或已经尝试过。
        // 都属于正常跳过，但仍要把「试过了」落下去，否则人工保护的那批会每轮被重新取出。
        achievements.markAbstractChecked(entry.achievementId());
        skipped++;
        continue;
      }
      provenance.upsertSource("ACHIEVEMENT", entry.achievementId(), "abstractText", text, null);
      publish(entry.achievementId());
      filled++;
    }
    log.info(
        "摘要回执 来源={} 收到={} 写入={} 来源没有={} 跳过={} 过长={}",
        provider,
        entries.size(),
        filled,
        notFound,
        skipped,
        tooLong);
    return new FillReceipt(
        entries.size(), filled, notFound, skipped, tooLong, Instant.now(), note());
  }

  /**
   * 把已有摘要的成果重新外发一次。
   *
   * <p>为什么需要它：补采之前写入的摘要早已进了投影，但「先补摘要、后建索引」这类顺序问题会让
   * 投影与权威表短暂不一致。给出一个可重放的入口，比让运维去猜「要不要重建整代投影」安全得多。
   * 只外发、不改数据，因此可以反复调用。
   */
  @Transactional
  public Map<String, Object> republish(List<String> achievementIds) {
    if (achievementIds == null || achievementIds.isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "成果标识列表为空");
    }
    if (achievementIds.size() > MAX_ENTRIES_PER_BATCH) {
      throw new BusinessException(
          ErrorCode.INVALID_ARGUMENT, "单次重发不得超过 " + MAX_ENTRIES_PER_BATCH + " 条");
    }
    int published = 0;
    int missing = 0;
    for (String id : achievementIds) {
      if (publish(id)) {
        published++;
      } else {
        missing++;
      }
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("requested", achievementIds.size());
    view.put("published", published);
    view.put("missing", missing);
    view.put("note", "只重发目录变更事件，不修改任何字段；missing 表示成果不存在（含已被合并删除的旧标识）");
    return view;
  }

  /** 按持久化实体现状外发一次目录变更。实体缺失时返回 false，不猜它是什么状态。 */
  private boolean publish(String achievementId) {
    Achievement current = achievements.findById(achievementId).orElse(null);
    if (current == null) {
      // 极端情况：刚写完摘要，成果在另一个事务里被合并删除。事件不发，摘要留在空处不影响正确性。
      log.warn("摘要已写入但读不到实体，跳过事件 achievementId={}", achievementId);
      return false;
    }
    catalogChanges.publish(
        achievementId, current.type(), current.title(), current.dataNature(), current.version());
    return true;
  }

  private static String note() {
    return "来源没有摘要的成果只记尝试时刻，不写空文本；已有摘要、人工修订与已合并成果一律不覆盖；" + "写入后经目录变更事件同步到公开检索";
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /** 待补页。remaining 是同一条件下的总数，用来判断还要不要继续补。 */
  public record PendingPage(List<Map<String, Object>> items, long remaining) {}

  /** 一条取回的摘要。abstractText 为 null／空表示来源没有这一篇的摘要。 */
  public record Entry(String achievementId, String abstractText) {}

  /** 投递请求体。provider 只用于日志与排障，不参与落库判定。 */
  public record AbstractBatch(String provider, List<Entry> entries) {}

  /** 重发请求体。 */
  public record RepublishBatch(List<String> achievementIds) {}

  /** 回执。 */
  public record FillReceipt(
      int received,
      int filled,
      int notFound,
      int skipped,
      int tooLong,
      Instant appliedAt,
      String note) {}
}
