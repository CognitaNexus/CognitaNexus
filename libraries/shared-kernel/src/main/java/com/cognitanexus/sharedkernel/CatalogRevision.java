package com.cognitanexus.sharedkernel;

/**
 * 核心公开目录的单调提交次序。
 *
 * <p>依据：架构 V0.2 §6.3。凡影响公开检索、统计或图的核心事务，在提交末段推进该计数器并写入
 * 变更集事件；行锁持续至提交，使修订顺序与提交顺序一致。禁止用普通序列的最大值推断
 * 「此前事务均已提交」。
 *
 * @param value 单调递增的修订号，从 1 开始
 */
public record CatalogRevision(long value) {

  public CatalogRevision {
    if (value < 0) {
      throw new IllegalArgumentException("catalogRevision 不能为负");
    }
  }

  public static CatalogRevision initial() {
    return new CatalogRevision(0L);
  }

  public boolean isAfter(CatalogRevision other) {
    return this.value > other.value;
  }

  public boolean isContiguousAfter(CatalogRevision other) {
    return this.value == other.value + 1;
  }

  @Override
  public String toString() {
    return Long.toString(value);
  }
}
