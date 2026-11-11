package com.cognitanexus.ingestion.collect.application;

import com.cognitanexus.ingestion.collect.adapter.CrossrefCitationFetcher;
import com.cognitanexus.ingestion.collect.adapter.OpenAlexCitationFetcher;
import com.cognitanexus.ingestion.collect.adapter.SourceAdapterRegistry.AdapterProperties;
import com.cognitanexus.ingestion.platform.client.CoreInternalClient;
import com.cognitanexus.ingestion.platform.client.CoreInternalClient.CitationReceipt;
import com.cognitanexus.ingestion.platform.client.CoreInternalClient.PendingCitation;
import com.cognitanexus.ingestion.platform.client.CoreInternalClient.PendingCitations;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * 参考文献补水：把「还没采过参考文献」的成果逐批补上。
 *
 * <p>依据：SRS FR06。功能位置与 {@code CollectExecutionService} 平行而不是嵌进去，因为两者要解决的
 * 问题不同：常规采集是「按查询条件发现新成果」，补水是「已知成果、回头取它引用了谁」。混进采集任务
 * 会让一个任务的产物有两种含义（新成果 / 引用观测），失败重试时也无法判断该重做哪一半。
 *
 * <p>两个来源、三条运行约束：
 *
 * <ol>
 *   <li><b>先 OpenAlex 后 Crossref</b>。OpenAlex 一次请求能取 25 篇（按请求计费），但它有全站共享的
 *       每日额度，用尽后返回 429 并要求等十几小时；Crossref 免费但只能一篇一请求。因此自动模式先试
 *       OpenAlex，一旦判定额度不可用就整轮改用 Crossref，而不是把「没额度」记成一串抓取失败。
 *   <li><b>只补可见论文</b>。待补清单来自 core，采集侧不自己挑：可见性、合并重定向与「采过没有」
 *       的权威都在 core。
 *   <li><b>取不到就记取不到</b>。来源按 DOI 查不到这一篇时投递一条空观测，core 记为 NOT_FOUND，
 *       补采清单因此不会反复命中同一批永远取不到的标识，也就不会每天白烧配额。
 * </ol>
 */
@Service
public class CitationHydrationService {

  private static final Logger log = LoggerFactory.getLogger(CitationHydrationService.class);

  private final CoreInternalClient core;
  private final OpenAlexCitationFetcher openAlexFetcher;
  private final CrossrefCitationFetcher crossrefFetcher;
  private final HydrationProperties properties;

  public CitationHydrationService(
      CoreInternalClient core,
      AdapterProperties adapterProperties,
      HydrationProperties properties,
      ObjectMapper objectMapper) {
    this.core = core;
    this.properties = properties;
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    this.openAlexFetcher =
        new OpenAlexCitationFetcher(
            objectMapper,
            httpClient,
            adapterProperties.userAgent(),
            adapterProperties.maxResponseBytes(),
            adapterProperties.apiKey());
    this.crossrefFetcher =
        new CrossrefCitationFetcher(
            objectMapper,
            httpClient,
            adapterProperties.userAgent(),
            adapterProperties.maxResponseBytes());
  }

  /** 补水来源。 */
  public enum Provider {
    /** 先试 OpenAlex，额度不可用时整轮改用 Crossref。 */
    AUTO,
    OPENALEX,
    CROSSREF
  }

  public HydrationReport hydrate(int limit, String search, String topicId) {
    return hydrate(limit, search, topicId, Provider.AUTO);
  }

  /**
   * 补水到指定篇数或配额为止。
   *
   * @param limit 本次最多补多少篇（按成果计）
   * @param search 题名关键字，用于集中补一个子领域（引用边两端都在库内才有边）
   * @param topicId 领域过滤，同上
   */
  public HydrationReport hydrate(int limit, String search, String topicId, Provider provider) {
    int target = clamp(limit, 1, properties.maxWorksPerRun());
    Set<String> attempted = new LinkedHashSet<>();
    Counters counters = new Counters();
    Integer creditsRemaining = null;
    String providerUsed =
        provider == Provider.CROSSREF
            ? "CROSSREF"
            : provider == Provider.OPENALEX ? "OPENALEX" : "OPENALEX";
    boolean openAlexUsable = provider != Provider.CROSSREF;
    int rounds = 0;
    String stoppedReason = "";

    while (counters.fetched < target && rounds < properties.maxRounds()) {
      rounds++;
      int want = Math.min(properties.batchSize(), target - counters.fetched);
      // 多要一点：来源查不到的 DOI 不产生观测，多要才能把这一轮的量补足
      PendingCitations pending = core.pendingCitations(want * 2, search, topicId);
      List<PendingCitation> candidates = new ArrayList<>();
      for (PendingCitation item : pending.items()) {
        if (item.doi() == null || item.doi().isBlank() || attempted.contains(item.doi())) {
          continue;
        }
        candidates.add(item);
        if (candidates.size() >= want) {
          break;
        }
      }
      if (candidates.isEmpty()) {
        stoppedReason = "没有更多待补成果（剩余 " + pending.remaining() + " 篇，均已尝试过）";
        break;
      }
      if (openAlexUsable
          && creditsRemaining != null
          && creditsRemaining < properties.minRemainingCredits()) {
        log.info("OpenAlex 剩余配额 {} 低于阈值，本轮改用 Crossref", creditsRemaining);
        openAlexUsable = false;
        providerUsed = "CROSSREF";
      }

      List<Map<String, Object>> observations = new ArrayList<>();
      if (openAlexUsable) {
        List<String> dois = new ArrayList<>();
        for (PendingCitation item : candidates) {
          dois.add(item.doi());
        }
        try {
          OpenAlexCitationFetcher.FetchResult result = openAlexFetcher.fetch(dois);
          creditsRemaining =
              result.remainingCredits() == null ? creditsRemaining : result.remainingCredits();
          Map<String, OpenAlexCitationFetcher.WorkReferences> found = new LinkedHashMap<>();
          for (OpenAlexCitationFetcher.WorkReferences work : result.works()) {
            found.put(work.doi(), work);
          }
          for (String doi : dois) {
            attempted.add(doi);
            OpenAlexCitationFetcher.WorkReferences work = found.get(doi);
            if (work == null) {
              observations.add(emptyObservation(doi, "OPENALEX"));
              counters.notFound++;
              continue;
            }
            observations.add(
                observation(
                    work.doi(),
                    "OPENALEX",
                    work.workId(),
                    work.referencesTotal(),
                    work.references(),
                    work.references() == null ? 0 : work.references().size(),
                    work.fetchedAt()));
            counters.fetched++;
          }
          providerUsed = "OPENALEX";
        } catch (BusinessException ex) {
          // 额度/限流/响应过大：不把这一批记成失败，改用 Crossref 重取同一批
          if (ex.errorCode() == ErrorCode.RATE_LIMITED
              || ex.errorCode() == ErrorCode.PAYLOAD_TOO_LARGE
              || ex.errorCode() == ErrorCode.DEPENDENCY_UNAVAILABLE) {
            log.warn("OpenAlex 取参考文献不可用（{}），本轮改用 Crossref：{}", ex.errorCode(), ex.getMessage());
            openAlexUsable = false;
            providerUsed = "CROSSREF";
          } else {
            throw ex;
          }
        }
      }

      if (!openAlexUsable) {
        for (PendingCitation item : candidates) {
          if (counters.fetched >= target) {
            break;
          }
          attempted.add(item.doi());
          CrossrefCitationFetcher.Result result = fetchOneFromCrossref(item.doi(), counters);
          if (result == null) {
            continue;
          }
          observations.add(
              observation(
                  result.doi(),
                  "CROSSREF",
                  null,
                  result.referencesTotal(),
                  result.references(),
                  result.entriesReceived(),
                  result.fetchedAt()));
          if (!result.notFound()) {
            counters.fetched++;
          }
        }
      }

      if (!observations.isEmpty()) {
        CitationReceipt receipt = core.postCitationObservations(observations);
        counters.matched += receipt.matched();
        counters.referencesWritten += receipt.referencesWritten();
        counters.resolved += receipt.resolved();
        counters.external += receipt.external();
      }
    }

    if (counters.fetched >= target) {
      stoppedReason = "已补到本次目标篇数 " + target;
    } else if (rounds >= properties.maxRounds()) {
      stoppedReason = "达到本次最大轮次 " + properties.maxRounds() + "，剩余待补请再触发一次";
    }

    log.info(
        "引用补水完成 来源={} 目标={} 取到={} 命中={} 条目={} 站内={} 未收录={} 轮次={} 剩余配额={}",
        providerUsed,
        target,
        counters.fetched,
        counters.matched,
        counters.referencesWritten,
        counters.resolved,
        counters.notFound,
        rounds,
        creditsRemaining);

    return new HydrationReport(
        target,
        counters.fetched,
        counters.matched,
        counters.referencesWritten,
        counters.resolved,
        counters.external,
        counters.notFound,
        rounds,
        providerUsed,
        creditsRemaining,
        stoppedReason,
        "引用补水只处理可见论文；来源查不到的 DOI 已记为 NOT_FOUND，不会再进入待补清单；"
            + "OpenAlex 有每日共享额度，额度用尽时自动改用 Crossref（免费但需逐篇请求）");
  }

  /** 指定 DOI 的定向补水（排障与验证用，不影响待补清单的推进顺序）。 */
  public HydrationReport hydrateDois(List<String> dois, Provider provider) {
    if (dois == null || dois.isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "DOI 列表为空");
    }
    if (dois.size() > properties.batchSize()) {
      throw new BusinessException(
          ErrorCode.INVALID_ARGUMENT, "单次定向补水上限 " + properties.batchSize() + " 个 DOI");
    }
    Counters counters = new Counters();
    String providerUsed = provider == Provider.OPENALEX ? "OPENALEX" : "CROSSREF";
    List<Map<String, Object>> observations = new ArrayList<>();
    boolean openAlexUsable = provider != Provider.CROSSREF;
    Integer creditsRemaining = null;
    if (openAlexUsable) {
      try {
        OpenAlexCitationFetcher.FetchResult result = openAlexFetcher.fetch(dois);
        creditsRemaining = result.remainingCredits();
        Map<String, OpenAlexCitationFetcher.WorkReferences> found = new LinkedHashMap<>();
        for (OpenAlexCitationFetcher.WorkReferences work : result.works()) {
          found.put(work.doi(), work);
        }
        for (String rawDoi : dois) {
          String doi = OpenAlexCitationFetcher.normalizeDoi(rawDoi);
          OpenAlexCitationFetcher.WorkReferences work = found.get(doi);
          if (work == null) {
            observations.add(emptyObservation(doi, "OPENALEX"));
            counters.notFound++;
          } else {
            observations.add(
                observation(
                    work.doi(),
                    "OPENALEX",
                    work.workId(),
                    work.referencesTotal(),
                    work.references(),
                    work.references() == null ? 0 : work.references().size(),
                    work.fetchedAt()));
            counters.fetched++;
          }
        }
        providerUsed = "OPENALEX";
      } catch (BusinessException ex) {
        if (ex.errorCode() != ErrorCode.RATE_LIMITED
            && ex.errorCode() != ErrorCode.DEPENDENCY_UNAVAILABLE) {
          throw ex;
        }
        observations.clear();
        openAlexUsable = false;
      }
    }
    if (!openAlexUsable) {
      providerUsed = "CROSSREF";
      for (String rawDoi : dois) {
        CrossrefCitationFetcher.Result result =
            fetchOneFromCrossref(OpenAlexCitationFetcher.normalizeDoi(rawDoi), counters);
        if (result == null) {
          continue;
        }
        observations.add(
            observation(
                result.doi(),
                "CROSSREF",
                null,
                result.referencesTotal(),
                result.references(),
                result.entriesReceived(),
                result.fetchedAt()));
        if (!result.notFound()) {
          counters.fetched++;
        }
      }
    }
    CitationReceipt receipt = core.postCitationObservations(observations);
    return new HydrationReport(
        dois.size(),
        counters.fetched,
        receipt.matched(),
        receipt.referencesWritten(),
        receipt.resolved(),
        receipt.external(),
        counters.notFound,
        1,
        providerUsed,
        creditsRemaining,
        "定向补水完成",
        "定向补水不改变待补清单的推进顺序，只用于排障与验证");
  }

  private CrossrefCitationFetcher.Result fetchOneFromCrossref(String doi, Counters counters) {
    if (doi == null) {
      counters.notFound++;
      return null;
    }
    sleepBetweenRequests();
    CrossrefCitationFetcher.Result result;
    try {
      result = crossrefFetcher.fetch(doi);
    } catch (BusinessException ex) {
      // 单篇失败不中断整轮：记下来继续，下一轮会重新命中它（失败不写库，不留假状态）
      log.warn("Crossref 取参考文献失败 doi={} 原因={}", doi, ex.getMessage());
      return null;
    }
    if (result.notFound()) {
      counters.notFound++;
    }
    return result;
  }

  private void sleepBetweenRequests() {
    try {
      Thread.sleep(properties.requestIntervalMillis());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  private static Map<String, Object> observation(
      String doi,
      String provider,
      String workId,
      Integer referencesTotal,
      List<String> references,
      int entriesReceived,
      Instant fetchedAt) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("doi", doi);
    payload.put("provider", provider);
    payload.put("providerWorkId", workId);
    payload.put("referencesTotal", referencesTotal);
    payload.put("references", references == null ? List.of() : references);
    // 来源给出的条目数与其中带标识的条数不是一回事：没有标识的条目无法建边，但也不能假装它不存在
    payload.put("referencesListed", entriesReceived);
    payload.put("fetchedAt", fetchedAt.toString());
    return payload;
  }

  private static Map<String, Object> emptyObservation(String doi, String provider) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("doi", doi);
    payload.put("provider", provider);
    payload.put("providerWorkId", null);
    payload.put("referencesTotal", null);
    payload.put("references", List.of());
    payload.put("referencesListed", 0);
    payload.put("fetchedAt", Instant.now().toString());
    return payload;
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(value, max));
  }

  /** 累计计数。 */
  private static final class Counters {
    private int fetched;
    private int matched;
    private int referencesWritten;
    private int resolved;
    private int external;
    private int notFound;
  }

  /** 补水运行参数。 */
  @ConfigurationProperties(prefix = "cn.collect.citation")
  public record HydrationProperties(
      Integer batchSize,
      Integer minRemainingCredits,
      Integer maxWorksPerRun,
      Integer maxRounds,
      Integer requestIntervalMillis) {

    public HydrationProperties {
      // 25 篇一批：单次响应约 1～2 MB（每篇最多数百条参考文献），既能摊薄配额又不会撞上响应上限
      batchSize = batchSize == null || batchSize <= 0 ? 25 : Math.min(batchSize, 50);
      // 单位是「美分」，不是请求次数：OpenAlex 2026 起按美元计费，剩余额度头是小数，
      // 取回时已折算成美分（见 OpenAlexCitationFetcher#remainingCredits）。
      // 默认 3 美分：匿名预算只有 10 美分且与采集共用，留 3 美分给采集侧，低于此值改走 Crossref。
      minRemainingCredits =
          minRemainingCredits == null || minRemainingCredits < 0 ? 3 : minRemainingCredits;
      maxWorksPerRun = maxWorksPerRun == null || maxWorksPerRun <= 0 ? 1000 : maxWorksPerRun;
      maxRounds = maxRounds == null || maxRounds <= 0 ? 200 : maxRounds;
      // Crossref 逐篇请求：留出间隔，避免在免费池里被限流
      requestIntervalMillis =
          requestIntervalMillis == null || requestIntervalMillis < 0 ? 120 : requestIntervalMillis;
    }
  }

  /** 补水回执。 */
  public record HydrationReport(
      int target,
      int worksFetched,
      int worksMatched,
      int referencesWritten,
      int resolvedEdges,
      int externalReferences,
      int notFoundWorks,
      int rounds,
      String provider,
      Integer creditsRemaining,
      String stoppedReason,
      String note) {}
}
