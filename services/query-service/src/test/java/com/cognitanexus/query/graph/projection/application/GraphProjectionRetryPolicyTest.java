package com.cognitanexus.query.graph.projection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cognitanexus.query.graph.infrastructure.GraphStoreUnavailableException;
import com.cognitanexus.query.graph.projection.application.GraphProjectionErrorClassifier.Kind;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 重试策略与错误分类的边界（有限、有退避、有分类）。 */
class GraphProjectionRetryPolicyTest {

  @Test
  @DisplayName("默认策略：3 次尝试、200ms 基础退避、线性增长并封顶 5 秒")
  void defaults() {
    GraphProjectionRetryPolicy policy = GraphProjectionRetryPolicy.defaults();

    assertThat(policy.maxAttempts()).isEqualTo(3);
    assertThat(policy.backoffMillis()).isEqualTo(200L);
    assertThat(policy.backoffFor(1)).isEqualTo(Duration.ofMillis(200));
    assertThat(policy.backoffFor(10)).isEqualTo(Duration.ofMillis(2_000));
    assertThat(new GraphProjectionRetryPolicy(3, 10_000L).backoffFor(1))
        .isEqualTo(Duration.ofMillis(5_000));
  }

  @Test
  @DisplayName("非法配置收敛到安全值：0 次尝试不能变成无限重试")
  void invalidValuesAreClamped() {
    GraphProjectionRetryPolicy policy = new GraphProjectionRetryPolicy(0, -5L);

    assertThat(policy.maxAttempts()).isEqualTo(1);
    assertThat(policy.backoffMillis()).isZero();
    assertThat(GraphProjectionRetryPolicy.noRetry().maxAttempts()).isEqualTo(1);
  }

  @Test
  @DisplayName("错误分类：域/数据错误不可重试，基础设施异常可重试")
  void classify() {
    assertThat(GraphProjectionErrorClassifier.classify(new IllegalArgumentException("坏数据")))
        .isEqualTo(Kind.PERMANENT);
    assertThat(GraphProjectionErrorClassifier.classify(new IllegalStateException("状态非法")))
        .isEqualTo(Kind.PERMANENT);
    assertThat(GraphProjectionErrorClassifier.classify(new GraphStoreUnavailableException("连不上图库")))
        .isEqualTo(Kind.RETRYABLE);
    // 未知运行时异常默认有限重试，而不是静默跳过。
    assertThat(GraphProjectionErrorClassifier.classify(new RuntimeException("未知")))
        .isEqualTo(Kind.RETRYABLE);
  }

  @Test
  @DisplayName("配置默认关闭、批次钳制到上限（写错的配置不能把整代成果读进内存）")
  void propertiesDefaults() {
    GraphProjectionProperties defaults =
        new GraphProjectionProperties(false, 0, 0, -1L, -1L, false, 0L, 0, 0, 0, true);

    assertThat(defaults.enabled()).isFalse();
    assertThat(defaults.batchSize()).isEqualTo(500);
    assertThat(defaults.maxAttempts()).isEqualTo(3);
    assertThat(defaults.maxFailedWorks()).isEqualTo(100L);
    assertThat(defaults.eventBatchSize()).isEqualTo(200);

    GraphProjectionProperties huge =
        new GraphProjectionProperties(
            true, 999_999, 99, 999_999L, 10L, true, 1L, 999_999, 999_999, 9_999, false);

    assertThat(huge.batchSize()).isEqualTo(GraphProjectionProperties.MAX_BATCH_SIZE);
    assertThat(huge.maxAttempts()).isEqualTo(10);
    assertThat(huge.retryBackoffMillis()).isEqualTo(60_000L);
    assertThat(huge.reconcileScanLimit()).isEqualTo(200_000);
    assertThat(huge.reconcileSampleSize()).isEqualTo(1_000);
    assertThat(huge.describe()).contains("enabled=true").doesNotContain("password");
  }

  @Test
  @DisplayName("对账口径参数：非法数据性质直接拒绝，ASS 不得被当成「全部」")
  void reconcileOptionsValidation() {
    assertThat(GraphProjectionReconciler.ReconcileOptions.normalizeOrigin("all")).isNull();
    assertThat(GraphProjectionReconciler.ReconcileOptions.normalizeOrigin(" real "))
        .isEqualTo("REAL");
    assertThatThrownBy(() -> GraphProjectionReconciler.ReconcileOptions.normalizeOrigin("MIX"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("REAL");
  }
}
