package com.cognitanexus.core.platform.util;

import com.cognitanexus.sharedkernel.BusinessException;

/**
 * 分页参数。
 *
 * <p>依据：架构 V0.2 §9.1。每页默认 20、最大 100，交互偏移上限 10,000；超过上限明确报错，
 * 不伪装成「无结果」。深分页超出上限时提示收窄条件，而不是静默截断。
 */
public record PageRequest(int page, int size) {

  public static final int DEFAULT_SIZE = 20;
  public static final int MAX_SIZE = 100;
  public static final int MAX_OFFSET = 10_000;

  public PageRequest {
    if (page < 1) {
      throw BusinessException.invalidArgument("page 从 1 开始");
    }
    if (size < 1 || size > MAX_SIZE) {
      throw BusinessException.invalidArgument("size 须在 1 到 " + MAX_SIZE + " 之间");
    }
    if ((long) (page - 1) * size > MAX_OFFSET) {
      throw BusinessException.businessRule("结果集过深，请收窄筛选条件后重试（交互偏移上限 " + MAX_OFFSET + " 条）");
    }
  }

  public static PageRequest of(Integer page, Integer size) {
    return new PageRequest(page == null ? 1 : page, size == null ? DEFAULT_SIZE : size);
  }

  public int offset() {
    return (page - 1) * size;
  }
}
