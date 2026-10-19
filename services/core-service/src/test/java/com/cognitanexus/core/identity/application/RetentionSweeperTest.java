package com.cognitanexus.core.identity.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 保留期清理的两条不变量。
 *
 * <p>1. 保留天数真的传到了清理语句——写死默认值而不读配置，等于部署时改不了。
 * <p>2. 清理失败不能把定时任务打死，否则撤销表从此不再清理且没人发现。
 */
@ExtendWith(MockitoExtension.class)
class RetentionSweeperTest {

  @Mock private AuthService auth;

  @Test
  void sweepPassesConfiguredRetention() {
    new RetentionSweeper(auth, 45).sweep();

    verify(auth).purgeRevocations(45);
  }

  @Test
  void retentionIsClampedToAtLeastOneDay() {
    // 配成 0 或负数会删掉「刚撤销的会话」，策略失效；下限按 1 天兜住。
    new RetentionSweeper(auth, 0).sweep();

    verify(auth).purgeRevocations(1);
  }

  @Test
  void sweepSwallowsFailure() {
    when(auth.purgeRevocations(anyInt())).thenThrow(new IllegalStateException("数据库暂不可用"));

    assertThatCode(() -> new RetentionSweeper(auth, 30).sweep()).doesNotThrowAnyException();
  }
}
