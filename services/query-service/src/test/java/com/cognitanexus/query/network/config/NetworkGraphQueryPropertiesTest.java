package com.cognitanexus.query.network.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cognitanexus.query.network.domain.GraphQueryMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 路由配置的默认值与失败语义。
 *
 * <p>默认值必须是「不改变现有行为」的：任何一项缺失都不能让公开网络查询悄悄改走图库。
 * 同时，配置写错必须在启动时失败——一个拼错的模式名如果回落默认值，运维会以为已经切换，
 * 而实际上一直走 PG（ADR-005 §7「降级必须可见」的反面）。
 */
class NetworkGraphQueryPropertiesTest {

  @Test
  @DisplayName("全部缺省：POSTGRES_ONLY + 异步对照 + 默认队列容量")
  void defaultsAreSafe() {
    NetworkGraphQueryProperties properties = new NetworkGraphQueryProperties(null, null, null);

    assertThat(properties.queryMode()).isEqualTo(GraphQueryMode.POSTGRES_ONLY);
    assertThat(properties.shadowCompareAsyncEnabled()).isTrue();
    assertThat(properties.shadowCompareQueueCapacity())
        .isEqualTo(NetworkGraphQueryProperties.DEFAULT_SHADOW_QUEUE_CAPACITY);
  }

  @Test
  @DisplayName("模式名按宽松规则解析（连字符/大小写），并归一化空白")
  void modeIsParsedLeniently() {
    assertThat(new NetworkGraphQueryProperties(" neo4j-with-pg-fallback ", null, null).queryMode())
        .isEqualTo(GraphQueryMode.NEO4J_WITH_PG_FALLBACK);
    assertThat(new NetworkGraphQueryProperties("shadow_compare", false, 8).describe())
        .contains("mode=SHADOW_COMPARE")
        .contains("shadowCompareAsync=false")
        .contains("shadowCompareQueueCapacity=8");
  }

  @Test
  @DisplayName("非法模式名在构造（启动绑定）阶段失败，不静默回落")
  void invalidModeFailsFast() {
    assertThatThrownBy(() -> new NetworkGraphQueryProperties("graph", null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cn.query.graph.query.mode");
  }

  @Test
  @DisplayName("队列容量非正数按默认值处理：0 容量会让所有对照任务被丢弃")
  void nonPositiveQueueCapacityFallsBackToDefault() {
    assertThat(
            new NetworkGraphQueryProperties("SHADOW_COMPARE", true, 0).shadowCompareQueueCapacity())
        .isEqualTo(NetworkGraphQueryProperties.DEFAULT_SHADOW_QUEUE_CAPACITY);
    assertThat(
            new NetworkGraphQueryProperties("SHADOW_COMPARE", true, -5)
                .shadowCompareQueueCapacity())
        .isEqualTo(NetworkGraphQueryProperties.DEFAULT_SHADOW_QUEUE_CAPACITY);
  }
}
