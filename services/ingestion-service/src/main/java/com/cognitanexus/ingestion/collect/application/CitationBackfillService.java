package com.cognitanexus.ingestion.collect.application;

import com.cognitanexus.ingestion.collect.adapter.OpenAlexAdapter;
import com.cognitanexus.ingestion.collect.adapter.SourceAdapter;
import com.cognitanexus.ingestion.collect.adapter.SourceAdapterRegistry;
import com.cognitanexus.ingestion.collect.domain.AdapterKind;
import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectedRecord;
import com.cognitanexus.ingestion.collect.domain.CollectModels.SourceRunConfig;
import com.cognitanexus.ingestion.platform.client.CoreInternalClient;
import com.cognitanexus.ingestion.platform.util.Fingerprints;
import com.cognitanexus.sharedkernel.BusinessException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 引文反向回填：把「站内论文引了很多次、但本站还没收录」的外部文献收进来。
 *
 * <p>为什么需要它。正向补参考文献已经把 93% 的站内论文的引用列表采回来了，可是 80 万条边里只有
 * 3.4% 的另一端在库内——缺口不在「没采到引用」，在「被引的那一端根本不存在」。一篇被引 227 次的
 * 论文收进来，一次点亮 227 条边；再补 1000 篇摘要做不到这件事。
 *
 * <p>为什么不走采集任务状态机。回填不是「按查询串翻页」式的来源采集，它的输入是 core 给出的一份
 * 有限标识清单，一轮就能跑完。硬套任务模型就得为它编一个假查询串，还会让「任务成功」与「补了多少
 * 节点」两件事混在同一个进度条里。这里直接复用交接通道（{@code /internal/v1/source-records}），
 * 入库、去重、治理、发布资格判定全部走同一套核心逻辑，与采集任务没有区别。
 *
 * <p>选样口径由 core 决定（见 {@code CitationNetworkService#backfillCandidates}）：只有 core 同时
 * 知道「哪些标识还没收录」与「每个标识被引几次」。采集侧自己挑会重复抓已收录的作品。
 */
@Component
public class CitationBackfillService {

  private static final Logger log = LoggerFactory.getLogger(CitationBackfillService.class);

  /** 单次向 core 交接的记录数。与导入一致：一批太大时失败要整批重来，太小则交接往返过多。 */
  private static final int HANDOFF_CHUNK = 200;

  /**
   * 单次向 OpenAlex 请求的标识数。
   *
   * <p>50 是 OpenAlex 的 OR 过滤上限，超出的标识被静默丢弃（请求照常 200）。必须与适配器里的
   * 同名上限一致，否则「请求了 80 个、只判到 50 个」会被记成「另外 30 个来源查不到」。
   */
  private static final int IDENTIFIERS_PER_REQUEST = 50;

  /**
   * 回填后触发重新解析时扫描的标识键上限。
   *
   * <p>取得比回填量大：一轮回填只收 50 个新节点，但站里可能积着更多「节点早就进来了、边却还标
   * EXTERNAL」的键（历史批次、人工导入、别的来源都可能是这个状态），顺手一起解决。
   */
  private static final int RESOLVE_LIMIT = 2000;

  private final CoreInternalClient coreClient;
  private final SourceRunConfigService sources;
  private final SourceAdapterRegistry adapters;

  public CitationBackfillService(
      CoreInternalClient coreClient,
      SourceRunConfigService sources,
      SourceAdapterRegistry adapters) {
    this.coreClient = coreClient;
    this.sources = sources;
    this.adapters = adapters;
  }

  /**
   * 回填结果。
   *
   * @param requested 向 core 要到的候选标识数
   * @param fetched 来源实际返回的作品数；与 requested 的差额是「来源查不到」，不是失败
   * @param missing 来源查不到的标识数（作品已删除、转为私有或标识失效）
   * @param remaining 同一标识种类下还剩多少个候选，用来判断还要跑几轮
   * @param resolvedRows 本轮随之点亮的引用边数（真正让引用网络变密的那个数字）
   * @param unresolvedRemaining 还剩多少个「节点已进站、边仍是 EXTERNAL」的标识键；为 0 表示可以停了
   */
  public record BackfillOutcome(
      String sourceCode,
      String kind,
      int requested,
      int fetched,
      int missing,
      int accepted,
      int attached,
      int duplicate,
      int rejected,
      int remaining,
      int resolvedRows,
      int unresolvedRemaining,
      List<String> failures) {}

  /**
   * 跑一轮回填。
   *
   * @param sourceCode 用于承接这批记录的执行配置编码（决定字段白名单与许可状态）
   * @param kind 标识种类，{@code DOI} 或 {@code OPENALEX_ID}
   * @param limit 本轮最多处理多少个候选
   */
  public BackfillOutcome run(String sourceCode, String kind, int limit) {
    SourceRunConfig config =
        sources
            .findByCode(sourceCode)
            .orElseThrow(() -> BusinessException.invalidArgument("来源未登记执行配置：" + sourceCode));
    // 回填是「收新成果」，不是「补已有成果的字段」，所以许可与开关必须按采集的门槛判，
    // 不能因为「只是补节点」就绕过许可审核。
    if (!config.licenseApproved()) {
      throw BusinessException.businessRule("来源许可未获批准，不能用于引文反向回填：" + sourceCode);
    }
    if (!config.enabled()) {
      throw BusinessException.businessRule("来源已停用，不能用于引文反向回填：" + sourceCode);
    }
    if (config.adapterKind() != AdapterKind.OPENALEX) {
      throw BusinessException.businessRule(
          "引文反向回填需要按标识精确取元数据，目前只有 OpenAlex 支持，当前来源种类为 " + config.adapterKind());
    }
    SourceAdapter adapter = adapters.require(AdapterKind.OPENALEX);
    if (!(adapter instanceof OpenAlexAdapter openAlex)) {
      throw BusinessException.businessRule("OpenAlex 适配器不可用，无法回填");
    }

    // 上限 20 是实测出来的，不是拍的：每收一条新成果，core 都要与全库比对标题来登记去重候选，
    // 单条约 0.5～1.2 秒。50 条要 25 秒以上，超过调用方约 20 秒的读超时，整批以
    // DEPENDENCY_UNAVAILABLE 报错回滚——看起来像依赖挂了，实际只是批量开得太大。
    // 20 条约 6～12 秒，留了余量。要补得多就多调几轮，不要调大单批。
    int cap = Math.min(Math.max(limit, 1), 20);
    CoreInternalClient.BackfillCandidates candidates = coreClient.backfillCandidates(kind, cap);
    List<CoreInternalClient.BackfillCandidate> items = candidates.items();
    if (items.isEmpty()) {
      // 没有候选不等于「没事可做」：站里可能还积着「节点已进站、边仍是 EXTERNAL」的键，
      // 所以这里照样触发一次重新解析，而不是直接返回。
      int resolvedRows = 0;
      int unresolvedRemaining = 0;
      try {
        CoreInternalClient.ResolveReceipt resolve = coreClient.resolveCitations(RESOLVE_LIMIT);
        resolvedRows = resolve.rowsResolved();
        unresolvedRemaining = resolve.keysRemaining();
      } catch (RuntimeException ex) {
        log.warn("无候选时重新解析引用失败: {}", ex.toString());
      }
      return new BackfillOutcome(
          config.sourceCode(),
          candidates.kind(),
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          candidates.remaining(),
          resolvedRows,
          unresolvedRemaining,
          List.of());
    }

    List<String> values = new ArrayList<>(items.size());
    for (CoreInternalClient.BackfillCandidate item : items) {
      if (item.value() != null && !item.value().isBlank()) {
        values.add(item.value().trim());
      }
    }

    LinkedHashSet<String> allowed = new LinkedHashSet<>(config.allowedFields());
    List<CollectedRecord> records = new ArrayList<>();
    List<String> failures = new ArrayList<>();
    for (int offset = 0; offset < values.size(); offset += IDENTIFIERS_PER_REQUEST) {
      List<String> chunk =
          values.subList(offset, Math.min(offset + IDENTIFIERS_PER_REQUEST, values.size()));
      try {
        records.addAll(openAlex.fetchByIdentifiers(candidates.kind(), chunk, allowed));
      } catch (RuntimeException ex) {
        // 一批失败不能让整轮静默变成「0 条」：那会被读成「这些标识来源都没有」。
        log.warn("回填批次取数失败 kind={} size={}: {}", candidates.kind(), chunk.size(), ex.toString());
        failures.add("取数失败：" + chunk.size() + " 个标识（" + ex.getMessage() + "）");
      }
    }

    int accepted = 0;
    int attached = 0;
    int duplicate = 0;
    int rejected = 0;
    for (int offset = 0; offset < records.size(); offset += HANDOFF_CHUNK) {
      List<CollectedRecord> chunk =
          records.subList(offset, Math.min(offset + HANDOFF_CHUNK, records.size()));
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("sourceCode", config.sourceCode());
      body.put("configVersion", config.configVersion());
      // 批次键带上本批标识的指纹：同一批标识重跑会得到同一个键，core 按幂等键识别为同一批，
      // 不会因为「又跑了一次回填」而重复建实体。
      body.put(
          "batchId",
          "backfill-" + Fingerprints.of(candidates.kind(), fingerprintOf(chunk)).substring(0, 24));
      body.put("records", chunk.stream().map(CitationBackfillService::toHandoffItem).toList());
      CoreInternalClient.HandoffResult result = coreClient.handoff(body);
      accepted += result.accepted();
      attached += result.attached();
      duplicate += result.duplicate();
      rejected += result.rejected();
    }

    // 最后一公里：刚收进来的成果只是「节点」，引用边还停在 EXTERNAL。不触发重新解析的话，
    // 回执上 accepted 是个正数、引用网络一条新边都没有——从外面看就是「跑成功了但什么都没变」。
    // 解析失败不算回填失败（节点已经进库，下次再解析即可），所以只记进 failures 不抛异常。
    int resolvedRows = 0;
    int unresolvedRemaining = 0;
    try {
      CoreInternalClient.ResolveReceipt resolve = coreClient.resolveCitations(RESOLVE_LIMIT);
      resolvedRows = resolve.rowsResolved();
      unresolvedRemaining = resolve.keysRemaining();
    } catch (RuntimeException ex) {
      log.warn("回填后重新解析引用失败，节点已入库，待下次解析: {}", ex.toString());
      failures.add("重新解析引用失败：" + ex.getMessage());
    }

    return new BackfillOutcome(
        config.sourceCode(),
        candidates.kind(),
        values.size(),
        records.size(),
        Math.max(values.size() - records.size(), 0),
        accepted,
        attached,
        duplicate,
        rejected,
        candidates.remaining(),
        resolvedRows,
        unresolvedRemaining,
        List.copyOf(failures));
  }

  private static Map<String, Object> toHandoffItem(CollectedRecord record) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("sourceRecordId", record.sourceRecordId());
    item.put("contentFingerprint", contentFingerprint(record.payload()));
    item.put("payload", record.payload());
    item.put("dataNature", record.dataNature().name());
    item.put("sourceUpdatedAt", null);
    item.put(
        "sourcePublishedAt",
        record.sourcePublishedAt() == null ? null : record.sourcePublishedAt().toString());
    item.put(
        "fetchedAt",
        record.fetchedAt() == null ? Instant.now().toString() : record.fetchedAt().toString());
    return item;
  }

  /**
   * 内容指纹：只用业务字段，绝不含抓取时刻。
   *
   * <p>与导入侧同一口径。含易变值会让「同一篇作品的同一版本」在每轮回填里都被当成新内容，去重靠
   * 指纹的那一层就失效了。
   */
  private static String contentFingerprint(Map<String, Object> payload) {
    return Fingerprints.of(
        String.valueOf(payload.get("kind")),
        String.valueOf(payload.get("title")),
        String.valueOf(payload.get("publicationYear")),
        String.valueOf(payload.get("doi")));
  }

  /** 批次指纹：把本批的来源记录标识排在一起算，与顺序无关。 */
  private static String fingerprintOf(List<CollectedRecord> records) {
    List<String> ids = new ArrayList<>(records.size());
    for (CollectedRecord record : records) {
      ids.add(record.sourceRecordId());
    }
    ids.sort(String::compareTo);
    return String.join(",", ids);
  }
}
