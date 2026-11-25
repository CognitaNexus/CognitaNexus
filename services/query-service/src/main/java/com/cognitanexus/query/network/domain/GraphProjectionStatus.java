package com.cognitanexus.query.network.domain;

import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import java.util.Locale;

/**
 * 一次「是否可以由图库回答公开查询」的判定结论。
 *
 * <p>依据：ADR-005 §6.1、§7。判定只有两个真问题，缺一不可：
 *
 * <ol>
 *   <li><b>schema 是否就绪</b>：缺唯一约束的图在并发写入下会悄悄产生重复节点，查询因此必须停止；
 *   <li><b>投影水位是否覆盖已发布修订</b>：{@code projected_version < published_revision} 表示
 *       「图里还没有当前已发布的那批成果」。此时图仍然连通、看起来完整，但少了最新的合作边——
 *       一个「看起来完整的旧答案」比明确降级危险得多。
 * </ol>
 *
 * <p>另外检查 generation：PG 全量重建会换代，图仍停留在旧代时即使版本号看起来不落后也不可服务
 * （ADR-005 §5.3）。判断「是否可用」共有四条，缺一不可：schema 就绪、水位记录存在、<b>基线初始化
 * 已完成</b>（版本 &gt; 0、generation 双方可判定且严格相等、回填状态为 COMPLETED 且无失败成果）、
 * 水位未落后。{@code dataComplete} 把这些数据条件合成一个可断言的布尔：数据覆盖是否完整。
 * 它<b>不</b>包含 schema 就绪——那是「能不能安全地读」，不是「数据全不全」。
 *
 * @param schemaReady 图库 schema（唯一约束与索引）是否已确认就绪
 * @param projectionPresent 图库中是否已存在本作用域的水位记录
 * @param projectionCoversPublishedRevision 水位版本是否 ≥ PG 已发布修订（且未被 generation 检查否决）
 * @param projectedVersion 图库记录的已投影版本；无记录时为 0
 * @param authoritativeRevision PG 当前已发布修订（权威水位）
 * @param projectedGeneration 图库水位记录的 generation；无记录时为 null
 * @param authoritativeGeneration PG 当前活动 generation
 * @param reasonCode 判定原因码，用于降级计数与日志（不含地址、凭据等内部信息）
 * @param reason 供日志/错误信息使用的可公开说明
 */
public record GraphProjectionStatus(
    boolean schemaReady,
    boolean projectionPresent,
    boolean projectionCoversPublishedRevision,
    long projectedVersion,
    long authoritativeRevision,
    String projectedGeneration,
    String authoritativeGeneration,
    String reasonCode,
    String reason) {

  /** schema 已就绪、水位未落后、generation 一致：图结果可以返回。 */
  public static final String READY = "READY";

  /** schema 未就绪（启动初始化失败、仅应用了部分 DDL，或从未执行）。 */
  public static final String SCHEMA_NOT_READY = "SCHEMA_NOT_READY";

  /** 图库中没有本作用域的水位记录：尚未回填，或作用域配错。 */
  public static final String PROJECTION_META_MISSING = "PROJECTION_META_MISSING";

  /** 水位版本落后于 PG 已发布修订。 */
  public static final String PROJECTION_STALE = "PROJECTION_STALE";

  /** 图停留在旧的 generation（PG 已重建换代）。 */
  public static final String GENERATION_MISMATCH = "GENERATION_MISMATCH";

  /**
   * 图投影尚未完成基线初始化：版本仍为 0、generation 是占位/不可判定，或回填未完成、有失败成果。
   *
   * <p>与 {@link #PROJECTION_STALE} 分开的原因是<b>处置不同</b>：落后意味着「回填在跑、等它跟上」，
   * 而未初始化意味着「回填没有成功完成过」，必须重新执行或修复回填，等多久都不会自愈。
   */
  public static final String PROJECTION_NOT_INITIALIZED = "PROJECTION_NOT_INITIALIZED";

  /** 水位读取失败（图库不可达、认证失败、驱动异常）。 */
  public static final String PROJECTION_META_UNREADABLE = "PROJECTION_META_UNREADABLE";

  public GraphProjectionStatus {
    projectedVersion = Math.max(projectedVersion, 0L);
    authoritativeRevision = Math.max(authoritativeRevision, 0L);
    reasonCode = reasonCode == null || reasonCode.isBlank() ? READY : reasonCode.trim();
    reason = reason == null ? "" : reason;
  }

  /** 数据是否覆盖当前已发布修订（不看 schema 就绪）。 */
  public boolean dataComplete() {
    return projectionPresent && projectionCoversPublishedRevision;
  }

  /** 是否可以把本次公开查询交给图库。 */
  public boolean graphUsable() {
    return READY.equals(reasonCode);
  }

  /** 供日志一行输出；不包含地址、凭据或请求内容。 */
  public String describe() {
    return String.format(
        Locale.ROOT,
        "graphProjection[reason=%s, schemaReady=%s, projectedVersion=%d, authoritativeRevision=%d,"
            + " projectedGeneration=%s, authoritativeGeneration=%s]",
        reasonCode,
        schemaReady,
        projectedVersion,
        authoritativeRevision,
        projectedGeneration,
        authoritativeGeneration);
  }

  public static GraphProjectionStatus ready(
      long projectedVersion, long authoritativeRevision, String generation) {
    return new GraphProjectionStatus(
        true,
        true,
        true,
        projectedVersion,
        authoritativeRevision,
        generation,
        generation,
        READY,
        "图投影水位与已发布修订一致");
  }

  public static GraphProjectionStatus schemaNotReady(
      long authoritativeRevision, String authoritativeGeneration) {
    return new GraphProjectionStatus(
        false,
        false,
        false,
        0L,
        authoritativeRevision,
        null,
        authoritativeGeneration,
        SCHEMA_NOT_READY,
        "图库 schema 未就绪（唯一约束缺失或初始化失败），不允许在缺约束的图上服务查询");
  }

  public static GraphProjectionStatus metaMissing(
      long authoritativeRevision, String authoritativeGeneration) {
    return new GraphProjectionStatus(
        true,
        false,
        false,
        0L,
        authoritativeRevision,
        null,
        authoritativeGeneration,
        PROJECTION_META_MISSING,
        "图库中不存在本作用域的投影水位记录：尚未回填或作用域配置不一致");
  }

  public static GraphProjectionStatus stale(GraphProjectionMeta meta, long authoritativeRevision) {
    return stale(meta, authoritativeRevision, null);
  }

  public static GraphProjectionStatus stale(
      GraphProjectionMeta meta, long authoritativeRevision, String authoritativeGeneration) {
    return new GraphProjectionStatus(
        true,
        true,
        false,
        meta.projectedVersion(),
        authoritativeRevision,
        meta.generation(),
        authoritativeGeneration,
        PROJECTION_STALE,
        "图投影落后于已发布修订：projectedVersion="
            + meta.projectedVersion()
            + " < publishedRevision="
            + authoritativeRevision);
  }

  public static GraphProjectionStatus generationMismatch(
      GraphProjectionMeta meta, long authoritativeRevision, String authoritativeGeneration) {
    return new GraphProjectionStatus(
        true,
        true,
        false,
        meta.projectedVersion(),
        authoritativeRevision,
        meta.generation(),
        authoritativeGeneration,
        GENERATION_MISMATCH,
        "图投影仍停留在旧 generation：" + meta.generation() + "，而当前活动代是 " + authoritativeGeneration);
  }

  public static GraphProjectionStatus metaUnreadable(
      long authoritativeRevision, String authoritativeGeneration, String errorKind) {
    return new GraphProjectionStatus(
        true,
        false,
        false,
        0L,
        authoritativeRevision,
        null,
        authoritativeGeneration,
        PROJECTION_META_UNREADABLE,
        "读取图投影水位失败（" + errorKind + "），图库当前不可用");
  }

  /**
   * 图投影尚未完成基线初始化。
   *
   * <p>典型场景：空库上 {@code projectedVersion == publishedRevision == 0}（旧实现会把
   * {@code 0 >= 0} 读成「不落后」而放行）、回填中断后水位停在 0、generation 还是占位值
   * {@code UNINITIALIZED}、或回填状态不是 COMPLETED/存在失败成果。
   *
   * <p>{@code projectionPresent=true}：水位记录可能存在，只是它证明不了图是完整的。
   */
  public static GraphProjectionStatus notInitialized(
      GraphProjectionMeta meta,
      long authoritativeRevision,
      String authoritativeGeneration,
      String detail) {
    return new GraphProjectionStatus(
        true,
        true,
        false,
        meta.projectedVersion(),
        authoritativeRevision,
        meta.generation(),
        authoritativeGeneration,
        PROJECTION_NOT_INITIALIZED,
        "图投影尚未完成基线初始化，不能作为公开查询来源：" + detail);
  }
}
