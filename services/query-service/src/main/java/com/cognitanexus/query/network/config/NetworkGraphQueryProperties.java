package com.cognitanexus.query.network.config;

import com.cognitanexus.query.network.domain.GraphQueryMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 合作网络图查询的路由配置。
 *
 * <p>依据：ADR-005 §6.1、§7、§8.1。默认值必须让「什么都不配」与今天的行为完全一致：
 * {@link GraphQueryMode#POSTGRES_ONLY}，即公开网络端点 100% 走原 PostgreSQL 实现。
 *
 * <p>与 {@code cn.query.graph.enabled} 的关系：{@code enabled} 控制图库客户端是否装配（Driver、
 * 连接池、schema 初始化），本配置控制<b>查询路由</b>。两者都打开才会真正读图；只打开 {@code enabled}
 * 而模式仍是默认值时，图只用于回填与健康检查，公开查询仍走 PG——这是避免「接上图库就顺手切了流量」
 * 的显式分步。
 *
 * @param mode 路由模式；取值见 {@link GraphQueryMode}，非法值在启动时失败而不是静默回落默认值
 * @param shadowCompareAsync 对照模式是否异步执行图查询。默认 true：对照不能把用户延迟翻倍；
 *     置 false 便于本地核对与测试同步观察结果
 * @param shadowCompareQueueCapacity 对照任务队列容量。队列满时丢弃并计数（{@code parityDroppedCount}），
 *     绝不无限堆积：图库变慢时用无界队列会把内存和线程一起拖垮
 */
@ConfigurationProperties(prefix = "cn.query.graph.query")
public record NetworkGraphQueryProperties(
    String mode, Boolean shadowCompareAsync, Integer shadowCompareQueueCapacity) {

  /** 默认对照队列容量。 */
  public static final int DEFAULT_SHADOW_QUEUE_CAPACITY = 64;

  public NetworkGraphQueryProperties {
    mode = mode == null || mode.isBlank() ? GraphQueryMode.POSTGRES_ONLY.name() : mode.trim();
    // 立刻解析：配置写错时在创建 Bean 阶段失败，而不是等到第一次公开请求才发现「一直在走 PG」。
    GraphQueryMode.parse(mode);
    shadowCompareAsync = shadowCompareAsync == null || shadowCompareAsync;
    shadowCompareQueueCapacity =
        shadowCompareQueueCapacity == null || shadowCompareQueueCapacity <= 0
            ? DEFAULT_SHADOW_QUEUE_CAPACITY
            : shadowCompareQueueCapacity;
  }

  /** 是否异步执行新旧对照。 */
  public boolean shadowCompareAsyncEnabled() {
    return Boolean.TRUE.equals(shadowCompareAsync);
  }

  /** 解析后的路由模式。 */
  public GraphQueryMode queryMode() {
    return GraphQueryMode.parse(mode);
  }

  /** 供启动日志展示；不含地址与凭据。 */
  public String describe() {
    return String.format(
        java.util.Locale.ROOT,
        "graphQuery[mode=%s, shadowCompareAsync=%s, shadowCompareQueueCapacity=%d]",
        queryMode(),
        shadowCompareAsyncEnabled(),
        shadowCompareQueueCapacity);
  }
}
