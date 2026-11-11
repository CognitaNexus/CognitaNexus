package com.cognitanexus.ingestion.collect.application;

import com.cognitanexus.ingestion.collect.adapter.OpenAlexAbstractFetcher;
import com.cognitanexus.ingestion.collect.adapter.SourceAdapterRegistry.AdapterProperties;
import com.cognitanexus.ingestion.platform.client.CoreInternalClient;
import com.cognitanexus.ingestion.platform.client.CoreInternalClient.AbstractReceipt;
import com.cognitanexus.ingestion.platform.client.CoreInternalClient.PendingAbstract;
import com.cognitanexus.ingestion.platform.client.CoreInternalClient.PendingAbstracts;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
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
 * 摘要补水：把缺摘要的可见论文逐批补上。
 *
 * <p>依据：FR02／FR16。与 {@link CitationHydrationService} 平行而不是合并：两者都是「core 给
 * 清单、采集侧按 DOI 去外网取」，但取的字段不同、可接受的上游批量也不同。合并成一个服务会让
 * 「参考文献取失败」与「摘要取失败」共用一条重试口径，而前者值得重试、后者常常是来源本来就没有。
 *
 * <p><b>取不到也必须回报</b>。这是本类最关键的一条：{@code abstract_inverted_index} 缺失表示
 * 来源确实没有这一篇的摘要（实测 Elsevier 系普遍如此），不是请求失败。如果把它当成失败跳过，
 * core 就不会记下尝试时刻，下一轮会把同一批再问一遍——那 55% 永远补不上的 DOI 会永久占据
 * 待补清单的前排，把真正能补的挤到后面，且日志上看起来一切正常。
 *
 * <p>反过来，真正的请求失败（配额、超时、响应过大）必须抛出而不是记成「没有摘要」：否则一次
 * 上游抖动就会把成百上千篇论文永久标记为「来源无摘要」，再也补不回来。
 */
@Service
public class AbstractHydrationService {

  private static final Logger log = LoggerFactory.getLogger(AbstractHydrationService.class);

  private final CoreInternalClient core;
  private final OpenAlexAbstractFetcher fetcher;
  private final AbstractHydrationProperties properties;

  public AbstractHydrationService(
      CoreInternalClient core,
      AdapterProperties adapterProperties,
      AbstractHydrationProperties properties,
      ObjectMapper objectMapper) {
    this.core = core;
    this.properties = properties;
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    this.fetcher =
        new OpenAlexAbstractFetcher(
            objectMapper,
            httpClient,
            adapterProperties.userAgent(),
            adapterProperties.maxResponseBytes(),
            adapterProperties.apiKey());
  }

  /**
   * 补水到指定篇数或没有待补为止。
   *
   * @param limit 本次最多处理多少篇（按成果计，不是按请求计）
   */
  public HydrationReport hydrate(int limit) {
    int target = clamp(limit, 1, properties.maxWorksPerRun());
    Counters counters = new Counters();
    Set<String> attempted = new LinkedHashSet<>();
    Integer creditsRemaining = null;
    int rounds = 0;
    String stoppedReason = "";
    long remaining = -1;

    while (counters.processed < target && rounds < properties.maxRounds()) {
      rounds++;
      int want = Math.min(properties.batchSize(), target - counters.processed);
      // 多要一点：有的条目没有 DOI（理论上 core 已过滤，这里仍要挡住），多要才能把这一轮凑满
      PendingAbstracts pending = core.pendingAbstracts(want * 2);
      remaining = pending.remaining();
      List<PendingAbstract> batch = new ArrayList<>();
      for (PendingAbstract item : pending.items()) {
        if (item.doi() == null || item.doi().isBlank() || attempted.contains(item.doi())) {
          continue;
        }
        batch.add(item);
        if (batch.size() >= want) {
          break;
        }
      }
      if (batch.isEmpty()) {
        stoppedReason = "没有更多待补成果（剩余 " + pending.remaining() + " 篇）";
        break;
      }
      if (creditsRemaining != null && creditsRemaining < properties.minRemainingCredits()) {
        stoppedReason = "OpenAlex 剩余额度 " + creditsRemaining + " 美分低于阈值，本轮先停";
        break;
      }

      List<String> dois = new ArrayList<>(batch.size());
      for (PendingAbstract item : batch) {
        dois.add(item.doi());
        attempted.add(item.doi());
      }

      Map<String, OpenAlexAbstractFetcher.WorkAbstract> found = new LinkedHashMap<>();
      try {
        OpenAlexAbstractFetcher.FetchResult result = fetcher.fetch(dois);
        creditsRemaining =
            result.remainingCredits() == null ? creditsRemaining : result.remainingCredits();
        for (OpenAlexAbstractFetcher.WorkAbstract work : result.works()) {
          found.put(work.doi(), work);
        }
      } catch (BusinessException ex) {
        // 真正取不到（配额／限流／响应过大／上游不可用）：整轮停手，且**不**回报任何「没有摘要」。
        // 把这些记成「来源没给」会把一次抖动固化成一整批永久缺失。
        if (ex.errorCode() == ErrorCode.RATE_LIMITED
            || ex.errorCode() == ErrorCode.PAYLOAD_TOO_LARGE
            || ex.errorCode() == ErrorCode.DEPENDENCY_UNAVAILABLE) {
          stoppedReason = "上游不可用（" + ex.errorCode() + "）：" + ex.getMessage();
          log.warn("摘要补水本轮中止 {}", stoppedReason);
          break;
        }
        throw ex;
      }

      Batch built = entriesOf(batch, found);
      counters.withAbstract += built.withAbstract();
      counters.withoutAbstract += built.withoutAbstract();
      AbstractReceipt receipt = core.postAbstracts(built.entries());
      counters.processed += batch.size();
      counters.filled += receipt.filled();
      counters.notFound += receipt.notFound();
      counters.skipped += receipt.skipped();
      counters.tooLong += receipt.tooLong();
    }

    if (counters.processed >= target) {
      stoppedReason = "已处理到本次目标篇数 " + target;
    } else if (rounds >= properties.maxRounds() && stoppedReason.isEmpty()) {
      stoppedReason = "达到本次最大轮次 " + properties.maxRounds() + "，剩余待补请再触发一次";
    }

    log.info(
        "摘要补水完成 目标={} 处理={} 取到摘要={} 来源没有={} 写入={} 跳过={} 过长={} 剩余待补={} 轮次={} 剩余额度={}",
        target,
        counters.processed,
        counters.withAbstract,
        counters.withoutAbstract,
        counters.filled,
        counters.skipped,
        counters.tooLong,
        remaining,
        rounds,
        creditsRemaining);

    return new HydrationReport(
        target,
        counters.processed,
        counters.withAbstract,
        counters.withoutAbstract,
        counters.filled,
        counters.notFound,
        counters.skipped,
        counters.tooLong,
        remaining,
        rounds,
        creditsRemaining,
        stoppedReason,
        "只补公开生效且有 DOI 的论文；来源没有摘要的只记尝试时刻、不写空文本；" + "已有摘要、人工修订与已合并成果一律不覆盖");
  }

  /**
   * 把「待补清单」与「来源返回的摘要」拼成投递条目。
   *
   * <p>抽成静态方法是为了让最关键的一条规矩可以被单独断言：<b>来源没返回的 DOI 也必须出现在
   * 条目里</b>，{@code abstractText} 为 null。漏掉它，core 就不会记下尝试时刻，那 55% 永远补不
   * 上的 DOI 会一直排在待补清单前排，而日志上一切正常。
   *
   * <p>还原出空串的按「来源没有」处理并计入 withoutAbstract：空串写进库里会在页面上显示一段空白
   * 摘要，读者无法区分「没有摘要」与「摘要丢了」。
   */
  static Batch entriesOf(
      List<PendingAbstract> batch, Map<String, OpenAlexAbstractFetcher.WorkAbstract> found) {
    List<Map<String, Object>> entries = new ArrayList<>(batch.size());
    int withAbstract = 0;
    int withoutAbstract = 0;
    for (PendingAbstract item : batch) {
      OpenAlexAbstractFetcher.WorkAbstract work = found.get(item.doi());
      String text = work == null ? null : work.abstractText();
      if (text != null && text.isBlank()) {
        text = null;
      }
      if (text == null) {
        withoutAbstract++;
      } else {
        withAbstract++;
      }
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("achievementId", item.achievementId());
      entry.put("abstractText", text);
      entries.add(entry);
    }
    return new Batch(entries, withAbstract, withoutAbstract);
  }

  /** 一批投递条目与「取到／没取到」的分项计数。 */
  record Batch(List<Map<String, Object>> entries, int withAbstract, int withoutAbstract) {}

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(value, max));
  }

  private static final class Counters {
    private int processed;
    private int withAbstract;
    private int withoutAbstract;
    private int filled;
    private int notFound;
    private int skipped;
    private int tooLong;
  }

  /** 补水运行参数。 */
  @ConfigurationProperties(prefix = "cn.collect.abstract")
  public record AbstractHydrationProperties(
      Integer batchSize, Integer minRemainingCredits, Integer maxWorksPerRun, Integer maxRounds) {

    public AbstractHydrationProperties {
      // 20 篇一次：摘要比参考文献小得多，但单次响应里全是要还原的倒排索引，批次不宜过大
      batchSize = batchSize == null || batchSize <= 0 ? 20 : Math.min(batchSize, 50);
      // 与引用补水同一口径：美分。低于该值就不再打 OpenAlex，留给常规采集
      minRemainingCredits =
          minRemainingCredits == null || minRemainingCredits < 0 ? 5 : minRemainingCredits;
      maxWorksPerRun = maxWorksPerRun == null || maxWorksPerRun <= 0 ? 400 : maxWorksPerRun;
      maxRounds = maxRounds == null || maxRounds <= 0 ? 200 : maxRounds;
    }
  }

  /**
   * 补水回执。
   *
   * <p>{@code notFound} 与 {@code worksWithoutAbstract} 应当相等：前者是 core 实际把「来源没有」
   * 落成尝试时刻的条数，后者是我们自己判定的条数。两个数不等就只有两种原因——批次里有 core 查不到
   * 的标识，或者 core 的那道写入门禁挡下了本该写入的条目。把它留在回执里，是为了让这种不一致
   * 有地方暴露，而不是只能从「怎么总数对不上」里猜。
   */
  public record HydrationReport(
      int target,
      int worksProcessed,
      int worksWithAbstract,
      int worksWithoutAbstract,
      int filled,
      int notFound,
      int skipped,
      int tooLong,
      long remaining,
      int rounds,
      Integer creditsRemaining,
      String stoppedReason,
      String note) {}
}
