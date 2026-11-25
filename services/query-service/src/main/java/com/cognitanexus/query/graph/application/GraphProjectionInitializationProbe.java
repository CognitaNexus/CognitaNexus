package com.cognitanexus.query.graph.application;

import java.util.Optional;

/**
 * 图投影「基线初始化（Backfill）」就绪探针。
 *
 * <p>依据：ADR-005 §5.2、§6.1、§7。
 *
 * <p>为什么水位还不够：{@code GraphProjectionMeta.projected_version} 只回答「图里写到了哪个版本」，
 * 它无法区分下面三种完全不同的状态：
 *
 * <ol>
 *   <li>回填<b>完整跑完</b>，图与 PG 同版本；
 *   <li>回填<b>正在跑</b>，水位已经推进到某个中间版本（甚至因为批次顺序刚好等于已发布修订）；
 *   <li>回填<b>中断且留下失败成果</b>，水位停住，但图上少了一批成果的合作边。
 * </ol>
 *
 * <p>这三种状态下「{@code projectedVersion >= publishedRevision}」可能同时成立，而只有第一种可以
 * 服务公开查询。因此回填在开始/结束时写一份初始化状态（状态、失败成果数、所对应的 generation），
 * 读路径用它回答「基线是否真的完成」。判定口径是<b>严格</b>的：
 *
 * <ul>
 *   <li>{@link InitializationState#COMPLETED}；</li>
 *   <li>{@code failedWorks == 0}（有任何一条成果回填失败，图上就少一批合作边，而缺边会被读成
 *       「这两位不合作」）；</li>
 *   <li>状态里的 {@code generation} 必须等于 PG 当前活动代（否则这份「完成」属于旧代）。</li>
 * </ul>
 *
 * <p>返回值语义：{@link Optional#empty()} 表示<b>本部署没有初始化状态记录</b>（例如尚未接入回填
 * 标记的旧实例）。此时水位保护退回到「元数据完整性」判定（版本 &gt; 0、generation 双方可判定且
 * 严格相等），不会因为缺少探针就放行空图。
 *
 * <p>实现必须是<b>只读</b>的：探针失败（图库不可达/认证失败）由调用方按「水位不可读」处理，
 * 不能伪装成「未初始化」——两者的运维处置不同。
 */
public interface GraphProjectionInitializationProbe {

  /**
   * 读取指定作用域的初始化状态。
   *
   * @param scope 投影作用域
   * @return 初始化状态；该作用域没有状态记录时返回 {@link Optional#empty()}
   */
  Optional<InitializationState> inspect(String scope);

  /** 回填在开始灌数据前写入状态，结束时更新为 {@link #COMPLETED} 或失败态。 */
  record InitializationState(String scope, String status, long failedWorks, String generation) {

    /** 回填完整跑完（唯一可服务的状态）。 */
    public static final String COMPLETED = "COMPLETED";

    public InitializationState {
      scope = scope == null ? "" : scope.trim();
      status = status == null || status.isBlank() ? "UNKNOWN" : status.trim();
      failedWorks = Math.max(failedWorks, 0L);
      generation = generation == null || generation.isBlank() ? null : generation.trim();
    }

    public boolean completed() {
      return COMPLETED.equalsIgnoreCase(status);
    }

    /** 无失败成果；有失败成果说明图上缺边，不能当作「完整初始化」。 */
    public boolean withoutFailedWorks() {
      return failedWorks == 0L;
    }

    /**
     * 这份「完成」是否属于给定活动代。
     *
     * <p>状态里没有 generation 视为不可判定（{@code false}）：既然无法证明它属于当前代，就不能
     * 用它给「图可用」背书。
     */
    public boolean belongsTo(String activeGeneration) {
      return generation != null
          && activeGeneration != null
          && !activeGeneration.isBlank()
          && generation.equals(activeGeneration.trim());
    }

    /** 供日志一行输出；不含地址与凭据。 */
    public String describe() {
      return String.format(
          java.util.Locale.ROOT,
          "graphInitialization[scope=%s, status=%s, failedWorks=%d, generation=%s]",
          scope,
          status,
          failedWorks,
          generation);
    }
  }
}
