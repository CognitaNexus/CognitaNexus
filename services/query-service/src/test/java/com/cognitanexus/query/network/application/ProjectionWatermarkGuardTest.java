package com.cognitanexus.query.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import com.cognitanexus.query.graph.schema.Neo4jSchemaInitializer;
import com.cognitanexus.query.network.domain.GraphProjectionStatus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 投影水位落后保护（Stale Projection Guard）。
 *
 * <p>这些用例锁住 ADR-005 §6.1/§7 的承诺：**图落后于已发布修订时必须被判定为不可用**，
 * 而不是返回一张「看起来完整」的旧图。逐个原因码覆盖，是因为它们对应完全不同的运维处置：
 * 回填没跟上（PROJECTION_STALE）、作用域配错（META_MISSING）、PG 换代（GENERATION_MISMATCH）、
 * 图库连不上（META_UNREADABLE）、schema 没建好（SCHEMA_NOT_READY）。
 */
class ProjectionWatermarkGuardTest {

  private GraphProjectionStore store;
  private Neo4jSchemaInitializer initializer;
  private ProjectionWatermarkGuard guard;

  @BeforeEach
  void setUp() {
    store = mock(GraphProjectionStore.class);
    initializer = mock(Neo4jSchemaInitializer.class);
    when(initializer.isReady()).thenReturn(true);
    guard = new ProjectionWatermarkGuard(store, initializer, NetworkTestFixtures.graphProperties());
  }

  private static GraphProjectionMeta meta(long projectedVersion, String generation) {
    return new GraphProjectionMeta(
        "query-public", projectedVersion, projectedVersion, generation, Instant.now());
  }

  @Test
  @DisplayName("水位相等：可用，且数据完整")
  void equalWatermarkIsUsable() {
    when(store.readMeta("query-public")).thenReturn(Optional.of(meta(120L, "42")));

    GraphProjectionStatus status = guard.evaluate(120L, "42");

    assertThat(status.graphUsable()).isTrue();
    assertThat(status.dataComplete()).isTrue();
    assertThat(status.reasonCode()).isEqualTo(GraphProjectionStatus.READY);
    assertThat(status.projectedVersion()).isEqualTo(120L);
    assertThat(status.authoritativeRevision()).isEqualTo(120L);
    assertThat(status.describe()).contains("reason=READY").contains("projectedVersion=120");
  }

  @Test
  @DisplayName("水位领先（例如已回填到更新修订）：仍可用，不因「不等」误报落后")
  void aheadWatermarkIsUsable() {
    when(store.readMeta("query-public")).thenReturn(Optional.of(meta(121L, "42")));

    assertThat(guard.evaluate(120L, "42").graphUsable()).isTrue();
  }

  @Test
  @DisplayName("水位落后：不可用，原因是 PROJECTION_STALE，且 dataComplete=false")
  void staleWatermarkIsRejected() {
    when(store.readMeta("query-public")).thenReturn(Optional.of(meta(119L, "42")));

    GraphProjectionStatus status = guard.evaluate(120L, "42");

    assertThat(status.graphUsable()).isFalse();
    assertThat(status.dataComplete()).isFalse();
    assertThat(status.reasonCode()).isEqualTo(GraphProjectionStatus.PROJECTION_STALE);
    assertThat(status.reason()).contains("projectedVersion=119").contains("publishedRevision=120");
    assertThat(status.projectedVersion()).isEqualTo(119L);
  }

  @Test
  @DisplayName("图库没有水位记录：按「尚未回填」拒绝，而不是把 0 当成一个落后版本")
  void missingMetaIsRejected() {
    when(store.readMeta("query-public")).thenReturn(Optional.empty());

    GraphProjectionStatus status = guard.evaluate(120L, "42");

    assertThat(status.graphUsable()).isFalse();
    assertThat(status.dataComplete()).isFalse();
    assertThat(status.projectionPresent()).isFalse();
    assertThat(status.reasonCode()).isEqualTo(GraphProjectionStatus.PROJECTION_META_MISSING);
  }

  @Test
  @DisplayName("generation 不一致（PG 已重建换代）：不可用，即使版本号不落后")
  void generationMismatchIsRejected() {
    when(store.readMeta("query-public")).thenReturn(Optional.of(meta(120L, "41")));

    GraphProjectionStatus status = guard.evaluate(120L, "42");

    assertThat(status.graphUsable()).isFalse();
    assertThat(status.reasonCode()).isEqualTo(GraphProjectionStatus.GENERATION_MISMATCH);
    assertThat(status.projectedGeneration()).isEqualTo("41");
    assertThat(status.authoritativeGeneration()).isEqualTo("42");
    assertThat(status.projectionCoversPublishedRevision()).isFalse();
  }

  @Test
  @DisplayName("权威侧 generation 只是空白时不做换代判断（老部署可能没有该值）")
  void blankAuthoritativeGenerationDoesNotBlock() {
    when(store.readMeta("query-public")).thenReturn(Optional.of(meta(120L, "41")));

    assertThat(guard.evaluate(120L, "   ").graphUsable()).isTrue();
  }

  @Test
  @DisplayName("权威侧没给出 generation 时不做换代判断")
  void missingAuthoritativeGenerationSkipsGenerationCheck() {
    when(store.readMeta("query-public")).thenReturn(Optional.of(meta(120L, "41")));

    assertThat(guard.evaluate(120L, null).graphUsable()).isTrue();
  }

  @Test
  @DisplayName("schema 未就绪：先拒服务，且不读水位（缺约束的图不能读）")
  void schemaNotReadyIsRejectedWithoutReadingMeta() {
    when(initializer.isReady()).thenReturn(false);

    GraphProjectionStatus status = guard.evaluate(120L, "42");

    assertThat(status.graphUsable()).isFalse();
    assertThat(status.schemaReady()).isFalse();
    assertThat(status.reasonCode()).isEqualTo(GraphProjectionStatus.SCHEMA_NOT_READY);
    verify(store, never()).readMeta(anyString());
  }

  @Test
  @DisplayName("读水位失败（图库不可达/认证失败）：判定为不可读，不向上抛")
  void unreadableMetaIsReportedNotThrown() {
    when(store.readMeta("query-public"))
        .thenThrow(
            new com.cognitanexus.query.graph.infrastructure.GraphStoreUnavailableException("boom"));

    GraphProjectionStatus status = guard.evaluate(120L, "42");

    assertThat(status.graphUsable()).isFalse();
    assertThat(status.reasonCode()).isEqualTo(GraphProjectionStatus.PROJECTION_META_UNREADABLE);
    // 原因里只放异常类型，不放主机名/凭据。
    assertThat(status.reason())
        .contains("GraphStoreUnavailableException")
        .doesNotContain("bolt://");
  }

  @Test
  @DisplayName("负的权威修订按 0 处理：不会因为调用方传错而误判「水位领先」")
  void negativeAuthoritativeRevisionIsClamped() {
    when(store.readMeta("query-public")).thenReturn(Optional.of(meta(0L, "42")));

    GraphProjectionStatus status = guard.evaluate(-5L, "42");

    assertThat(status.authoritativeRevision()).isZero();
    assertThat(status.graphUsable()).isTrue();
  }

  @Test
  @DisplayName("判定记录的输入归一：负版本按 0、空原因码按 READY、空说明按空串")
  void statusNormalizesInputs() {
    GraphProjectionStatus status =
        new GraphProjectionStatus(true, true, true, -1L, -2L, null, null, "  ", null);

    assertThat(status.projectedVersion()).isZero();
    assertThat(status.authoritativeRevision()).isZero();
    assertThat(status.reasonCode()).isEqualTo(GraphProjectionStatus.READY);
    assertThat(status.reason()).isEmpty();
    assertThat(status.graphUsable()).isTrue();
  }
}
