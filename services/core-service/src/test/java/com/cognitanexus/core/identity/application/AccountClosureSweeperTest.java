package com.cognitanexus.core.identity.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 调度器只做委派，但有两件事必须守住：用统一批量上限、遇错不终止后续调度。 */
@ExtendWith(MockitoExtension.class)
class AccountClosureSweeperTest {

  @Mock private AccountClosureService closures;

  @Test
  void sweepUsesSharedBatchLimit() {
    new AccountClosureSweeper(closures).sweep();

    verify(closures).executeDue(AccountClosureService.MAX_AUTO_BATCH);
  }

  @Test
  void sweepSwallowsFailure() {
    when(closures.executeDue(anyInt())).thenThrow(new IllegalStateException("数据库暂不可用"));

    assertThatCode(() -> new AccountClosureSweeper(closures).sweep()).doesNotThrowAnyException();
  }
}
