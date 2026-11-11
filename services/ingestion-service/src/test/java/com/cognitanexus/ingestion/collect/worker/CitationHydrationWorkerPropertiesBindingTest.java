package com.cognitanexus.ingestion.collect.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * {@link CitationHydrationWorker.WorkerProperties} 必须能被 Spring 绑定。
 *
 * <p>守的是同一类事故：record 一旦有多个构造器，构造器绑定就选不出来，回落到 JavaBean 绑定
 * 去找无参构造，启动即抛 {@code NoSuchMethodException}，服务进崩溃循环。这个类上的
 * {@code ApplicationContextRunner} 用最小上下文走一遍真实绑定路径，不需要数据库。
 */
class CitationHydrationWorkerPropertiesBindingTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  @DisplayName("三项配置都能绑定，包括 yml 里的连字符写法")
  void bindsAllComponents() {
    runner
        .withPropertyValues(
            "cn.collect.citation.worker.enabled=false",
            "cn.collect.citation.worker.works-per-tick=42",
            "cn.collect.citation.worker.interval-ms=600000")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              CitationHydrationWorker.WorkerProperties bound =
                  context.getBean(CitationHydrationWorker.WorkerProperties.class);
              assertThat(bound.enabled()).isFalse();
              assertThat(bound.worksPerTick()).isEqualTo(42);
            });
  }

  @Test
  @DisplayName("什么都不配时也要能起：默认开启、300 篇")
  void bindsWithNoProperties() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          CitationHydrationWorker.WorkerProperties bound =
              context.getBean(CitationHydrationWorker.WorkerProperties.class);
          assertThat(bound.enabled()).isTrue();
          assertThat(bound.worksPerTick()).isEqualTo(300);
        });
  }

  @Configuration
  @EnableConfigurationProperties(CitationHydrationWorker.WorkerProperties.class)
  static class Config {}
}
