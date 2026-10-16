package com.cognitanexus.sharedkernel;

import java.util.Set;

/**
 * 数据性质。真实 / 模拟 / 混合三值互斥。
 *
 * <p>依据：BR-IMP-07、架构 V0.2 §5.4。默认现实统计与网络只接受真实且公开、生效、合格的数据；
 * 演示模式必须明确标识。禁止任何路径以 REAL 作为缺省值。
 */
public enum DataNature {
  REAL,
  SIMULATED,
  MIXED;

  /** 是否允许进入默认现实统计、排名与合作网络。 */
  public boolean eligibleForRealityView() {
    return this == REAL;
  }

  public static Set<DataNature> realityOnly() {
    return Set.of(REAL);
  }
}
