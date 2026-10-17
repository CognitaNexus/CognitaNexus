package com.cognitanexus.core.platform.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cognitanexus.sharedkernel.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 分页参数的行为约束。
 *
 * <p>依据：架构 V0.2 §9.1。深分页超出交互上限时<b>明确报错</b>，不伪装成「无结果」——
 * 后者会让调用方以为「确实没有更多数据」，而真相是「服务端拒绝执行这个查询」。
 */
class PageRequestTest {

  @Test
  @DisplayName("缺省为第 1 页、每页 20 条")
  void defaults() {
    PageRequest request = PageRequest.of(null, null);
    assertThat(request.page()).isEqualTo(1);
    assertThat(request.size()).isEqualTo(PageRequest.DEFAULT_SIZE);
    assertThat(request.offset()).isZero();
  }

  @Test
  @DisplayName("偏移量由页码算出")
  void offsetComputed() {
    assertThat(new PageRequest(1, 20).offset()).isZero();
    assertThat(new PageRequest(2, 20).offset()).isEqualTo(20);
    assertThat(new PageRequest(5, 50).offset()).isEqualTo(200);
  }

  @Test
  @DisplayName("页码从 1 开始，0 与负数被拒绝")
  void pageMustStartAtOne() {
    assertThatThrownBy(() -> new PageRequest(0, 20))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("page 从 1 开始");
    assertThatThrownBy(() -> new PageRequest(-3, 20))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("page 从 1 开始");
  }

  @Test
  @DisplayName("每页条数须在 1 到 100 之间，边界值可接受")
  void sizeBounds() {
    assertThat(new PageRequest(1, 1).size()).isEqualTo(1);
    assertThat(new PageRequest(1, PageRequest.MAX_SIZE).size()).isEqualTo(PageRequest.MAX_SIZE);
    assertThatThrownBy(() -> new PageRequest(1, 0)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> new PageRequest(1, 101)).isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("偏移恰好等于上限时允许，超过上限时报错并提示收窄条件")
  void offsetLimit() {
    // 501 页 × 20 条 = 10000，恰好等于上限
    assertThat(new PageRequest(501, 20).offset()).isEqualTo(PageRequest.MAX_OFFSET);
    assertThatThrownBy(() -> new PageRequest(502, 20))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("结果集过深");
  }

  @Test
  @DisplayName("深分页报的是业务规则错误，不是「没有数据」")
  void deepPagingIsExplicitError() {
    assertThatThrownBy(() -> new PageRequest(1_000, 100))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("收窄筛选条件");
  }
}
