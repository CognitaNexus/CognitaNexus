package com.cognitanexus.ingestion.collect.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * {@link SourceAdapterRegistry.AdapterProperties} 必须能被 Spring 绑定。
 *
 * <p>这个测试守的是一次真实上线的启动失败：给 record 加了一个「少参数」的便利构造器之后，
 * 构造器绑定选不出该用哪个，回落到 JavaBean 绑定去找无参构造，而 record 没有无参构造，
 * 于是启动即抛 {@code NoSuchMethodException: AdapterProperties.<init>()}，服务进崩溃循环。
 *
 * <p>为什么原有的单测抓不到：所有单测都直接 {@code new} 对象，不加载 Spring 上下文，
 * 而这个问题只出现在容器启动那一瞬间。{@link ApplicationContextRunner} 用一个最小上下文
 * 就能复现绑定路径，不需要数据库、不需要起整个应用。
 */
class AdapterPropertiesBindingTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  @DisplayName("配置项能绑定到 record，包括新增的 api-key")
  void bindsAllComponents() {
    runner
        .withPropertyValues(
            "cn.collect.adapter.user-agent=Probe/1.0 (mailto:a@b.c)",
            "cn.collect.adapter.request-timeout=15s",
            "cn.collect.adapter.max-response-bytes=1048576",
            "cn.collect.adapter.api-key=k-123")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              SourceAdapterRegistry.AdapterProperties bound =
                  context.getBean(SourceAdapterRegistry.AdapterProperties.class);
              assertThat(bound.apiKey()).isEqualTo("k-123");
              assertThat(bound.maxResponseBytes()).isEqualTo(1048576);
            });
  }

  @Test
  @DisplayName("api-key 缺省或空白都归一成 null，不发出空的 api_key 参数")
  void blankApiKeyBecomesNull() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(SourceAdapterRegistry.AdapterProperties.class).apiKey())
              .isNull();
        });
    runner
        .withPropertyValues("cn.collect.adapter.api-key=   ")
        .run(
            context ->
                assertThat(context.getBean(SourceAdapterRegistry.AdapterProperties.class).apiKey())
                    .isNull());
  }

  @Configuration
  @EnableConfigurationProperties(SourceAdapterRegistry.AdapterProperties.class)
  static class Config {}
}
