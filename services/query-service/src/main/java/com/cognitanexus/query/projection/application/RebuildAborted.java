package com.cognitanexus.query.projection.application;

/**
 * 重建中止信号。
 *
 * <p>依据：架构 V0.2 §7.3「校验四类计数、抽样内容、边证据和连续水位后切换」——任一项不满足都必须
 * 放弃本次重建，而不是把一个不完整的视图换上去。用异常而不是返回值表达，是为了同时获得两件事：
 * 携带可记录的原因，以及让所在事务自动回滚（返回值无法回滚已写入的半批数据）。
 */
class RebuildAborted extends RuntimeException {

  private static final long serialVersionUID = 1L;

  RebuildAborted(String message) {
    super(message);
  }
}
