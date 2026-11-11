package com.cognitanexus.ingestion.collect.worker;

import com.cognitanexus.ingestion.collect.application.AbstractHydrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 摘要回填后台任务。
 *
 * <p>依据：FR02／FR16。为什么必须自动跑：待补清单是「当前没有摘要的公开论文」，而采集每天还在
 * 新增论文，新进来的一批同样缺摘要。手动补一次之后不调度，覆盖率只会一路往下掉，而库里已有的
 * 数字看上去完全正常——这是那种只能靠调度兜住的缓慢腐坏。
 *
 * <p>为什么和 {@code CitationHydrationWorker} 分开两条调度：两者的失败含义不同。引用补水取不到
 * 大多是来源没有这一篇，摘要补水取不到大多也是来源没给，但两者的批次耗时差一个数量级（参考文献
 * 单篇可达数百条）。合在一条调度里，快的一方会被慢的一方拖住，而调度延迟不会报错。
 *
 * <p>异常一律就地吞掉：一次上游超时不能停掉调度线程，否则补水会长期静默停摆。这一条与
 * {@code CollectWorker}、{@code CitationHydrationWorker} 一致。
 */
@Component
public class AbstractHydrationWorker {

  private static final Logger log = LoggerFactory.getLogger(AbstractHydrationWorker.class);

  private final AbstractHydrationService hydration;
  private final WorkerProperties properties;

  public AbstractHydrationWorker(AbstractHydrationService hydration, WorkerProperties properties) {
    this.hydration = hydration;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${cn.collect.abstract.worker.interval-ms:1800000}",
      initialDelayString = "${cn.collect.abstract.worker.initial-delay-ms:600000}")
  public void tick() {
    if (!properties.enabled()) {
      return;
    }
    try {
      AbstractHydrationService.HydrationReport report =
          hydration.hydrate(properties.worksPerTick());
      if (report.worksProcessed() > 0) {
        log.info(
            "摘要回填 处理={} 取到={} 来源没有={} 写入={} 已标记={} 跳过={} 过长={} 剩余待补={} 说明={}",
            report.worksProcessed(),
            report.worksWithAbstract(),
            report.worksWithoutAbstract(),
            report.filled(),
            report.notFound(),
            report.skipped(),
            report.tooLong(),
            report.remaining(),
            report.stoppedReason());
      } else {
        // 「本轮没东西可补」也要留一行：静默停摆时库里的覆盖率数字看起来是正常的，
        // 这一行是唯一能证明它还在跑的痕迹。
        log.info("摘要回填 本轮无处理 说明={}", report.stoppedReason());
      }
    } catch (RuntimeException ex) {
      log.error("摘要回填失败，下一周期自动重试", ex);
    }
  }

  /**
   * 回填任务参数。
   *
   * <p>不能再加便利构造器：record 一旦有多个构造器，Spring Boot 的构造器绑定就选不出来，回落到
   * JavaBean 绑定去找无参构造，启动时报 {@code NoSuchMethodException}。这个坑在
   * {@code AdapterProperties} 上真踩过一次。
   */
  @ConfigurationProperties(prefix = "cn.collect.abstract.worker")
  public record WorkerProperties(Boolean enabled, Integer worksPerTick) {

    public WorkerProperties {
      enabled = enabled == null ? Boolean.TRUE : enabled;
      // 每 30 分钟一轮、每轮 400 篇：待补约 7251 篇，一天 48 轮可处理 19200 篇，一天内补完；
      // 每轮 20 篇一次上游请求，400 篇约 20 次请求，对配额压力很小。
      worksPerTick = worksPerTick == null || worksPerTick <= 0 ? 400 : worksPerTick;
    }
  }
}
