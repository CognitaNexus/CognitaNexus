package com.cognitanexus.ingestion.collect.worker;

import com.cognitanexus.ingestion.collect.application.CitationHydrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 引用网络后台补水。
 *
 * <p>为什么必须自动跑：待补候选只挑「从未取过引用」的公开论文，而采集每天新增约 2700 条公开
 * 论文。手动补水跑完一次之后如果不做这件事，覆盖率只会一路往下掉——补水本身很便宜（300 篇约
 * 12 次上游请求、0.0012 美元），漏跑一天的代价却是覆盖率掉四个百分点。这类「指标慢慢腐坏」
 * 的退化在人工巡检下几乎发现不了，只能靠调度。
 *
 * <p>固定使用 OPENALEX 而不走 AUTO：AUTO 在额度低于阈值时会整轮改用 Crossref，而 Crossref
 * 逐篇请求、同一批量要慢几十倍。定时任务宁可这一轮少补，也不要悄悄退化成慢路径——慢路径不会
 * 报错，只会让人以为补水还在跑。
 *
 * <p>异常一律就地吞掉：一次上游超时不能让调度线程停掉，否则补水会长期静默停摆，而停摆期间
 * 库里的数字看上去是正常的。这一条与 {@code CollectWorker} 的处理方式一致。
 *
 * <p>调度并发：本服务的调度线程池已显式配到 4（见 application.yml）。默认只有 1 个线程，而
 * {@code CollectWorker} 是 2 秒一次的固定延迟，会把唯一线程长期占住，本任务会被无限期推迟且
 * 不报任何错——这类故障在日志上完全看不见。
 */
@Component
public class CitationHydrationWorker {

  private static final Logger log = LoggerFactory.getLogger(CitationHydrationWorker.class);

  private final CitationHydrationService hydration;
  private final WorkerProperties properties;

  public CitationHydrationWorker(CitationHydrationService hydration, WorkerProperties properties) {
    this.hydration = hydration;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${cn.collect.citation.worker.interval-ms:1800000}",
      initialDelayString = "${cn.collect.citation.worker.initial-delay-ms:180000}")
  public void tick() {
    if (!properties.enabled()) {
      return;
    }
    try {
      CitationHydrationService.HydrationReport report =
          hydration.hydrate(
              properties.worksPerTick(), null, null, CitationHydrationService.Provider.OPENALEX);
      if (report.worksFetched() > 0) {
        log.info(
            "引用补水 取到={} 命中={} 引用={} 解析边={} 未找到={} 剩余额度={} 说明={}",
            report.worksFetched(),
            report.worksMatched(),
            report.referencesWritten(),
            report.resolvedEdges(),
            report.notFoundWorks(),
            report.creditsRemaining(),
            report.stoppedReason());
      } else {
        // 「本轮没东西可补」也要留一行：这类任务最典型的失效方式是静默停摆，
        // 而停摆期间库里的覆盖率数字看上去完全正常。一行 info 是唯一能证明它还在跑的痕迹。
        // 一天 48 行，不会淹掉日志。
        log.info("引用补水 本轮无新增 说明={}", report.stoppedReason());
      }
    } catch (RuntimeException ex) {
      log.error("引用补水失败，下一周期自动重试", ex);
    }
  }

  /**
   * 补水定时任务参数。
   *
   * <p>这里不能再加便利构造器：record 一旦有多个构造器，Spring Boot 的构造器绑定就选不出来，
   * 回落到 JavaBean 绑定去找无参构造，启动时报
   * {@code NoSuchMethodException: ...<init>()}。这个坑在 {@code AdapterProperties} 上真踩过一次。
   */
  @ConfigurationProperties(prefix = "cn.collect.citation.worker")
  public record WorkerProperties(Boolean enabled, Integer worksPerTick) {

    public WorkerProperties {
      enabled = enabled == null ? Boolean.TRUE : enabled;
      // 300 篇约 12 次批量请求。每天新增约 2700 条公开论文，一天跑 48 轮、每轮 300 篇，
      // 供给远大于需求，因此单个周期失败不会造成积压。
      worksPerTick = worksPerTick == null || worksPerTick <= 0 ? 300 : worksPerTick;
    }
  }
}
