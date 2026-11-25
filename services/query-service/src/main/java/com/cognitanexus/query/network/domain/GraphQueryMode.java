package com.cognitanexus.query.network.domain;

import java.util.Locale;

/**
 * 合作网络查询的路由模式。
 *
 * <p>依据：ADR-005 §6.1、§7、§8.1。图库是<b>派生读投影</b>，切换必须可配置、可一键回退，
 * 并且任何「图结果可能不是当前已发布版本」的情形都不能静默返回。四种模式各自回答一个不同问题：
 *
 * <ul>
 *   <li>{@link #POSTGRES_ONLY}：默认与回退模式。100% 走原 PostgreSQL 路径，图库连与不连都不影响
 *       结果（{@code cn.query.graph.enabled=false} 时等价于此模式）；
 *   <li>{@link #SHADOW_COMPARE}：灰度前的对照模式。PG 结果是唯一权威返回值，同时用同一屏障快照、
 *       同一筛选条件、同一版本执行图查询并逐字段比较，只记录差异，不改变用户看到的内容；
 *   <li>{@link #NEO4J_WITH_PG_FALLBACK}：目标生产模式。图结果只有在「schema 就绪 + 投影水位不落后
 *       + generation 一致」时才返回；否则显式记录原因并回退到 PG；
 *   <li>{@link #NEO4J_STRICT}：验收/测试模式。强制走图库，任何降级条件都抛错，禁止把「图不可用」
 *       伪装成「PG 结果」——否则验收会在一条根本没走图的路径上通过。
 * </ul>
 *
 * <p>为什么不提供一个「按百分比灰度」的模式：灰度比例是运维参数，不是业务语义；把比例写进枚举
 * 会让「同一请求返回哪种实现」变成不可解释的随机事件。灰度按实例/环境切换模式更可核对。
 */
public enum GraphQueryMode {

  /** 只走 PostgreSQL（默认、一键回退）。 */
  POSTGRES_ONLY,

  /** 新旧查询对照：PG 权威返回 + 图查询对比记录。 */
  SHADOW_COMPARE,

  /** 优先图库，水位落后/不可用时回退 PostgreSQL。 */
  NEO4J_WITH_PG_FALLBACK,

  /** 强制图库，任何降级条件显式失败。 */
  NEO4J_STRICT;

  /**
   * 解析配置值。
   *
   * <p>接受大小写、前后空白与 {@code -} / 空格分隔（{@code neo4j-with-pg-fallback}），因为配置项
   * 来自环境变量与 YAML 两种来源，写错一个连字符就让服务拒绝启动是没有必要的严格。无法识别的值
   * 一律抛异常而不是回落默认值：静默回落会让「配置成图模式但一直走 PG」看起来像正常。
   */
  public static GraphQueryMode parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return POSTGRES_ONLY;
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    for (GraphQueryMode mode : values()) {
      if (mode.name().equals(normalized)) {
        return mode;
      }
    }
    throw new IllegalArgumentException(
        "cn.query.graph.query.mode 取值必须是 "
            + java.util.Arrays.toString(values())
            + " 之一，实际为 "
            + raw);
  }

  /** 该模式是否由图库提供返回结果（对照模式下 PG 才是权威返回）。 */
  public boolean servesFromGraph() {
    return this == NEO4J_WITH_PG_FALLBACK || this == NEO4J_STRICT;
  }

  /** 该模式是否必须连接图库（缺图库 Bean 时不能静默降级）。 */
  public boolean requiresGraph() {
    return this == NEO4J_STRICT;
  }

  /** 该模式是否执行新旧查询对照。 */
  public boolean shadows() {
    return this == SHADOW_COMPARE;
  }
}
