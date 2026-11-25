package com.cognitanexus.query.graph.projection.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.config.Neo4jGraphProperties;
import com.cognitanexus.query.graph.infrastructure.Neo4jSessionRunner;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv.FakeStore;
import com.cognitanexus.query.graph.projection.application.GraphBackfillService;
import com.cognitanexus.query.graph.projection.application.GraphEventProjector;
import com.cognitanexus.query.graph.projection.application.GraphProjectionCheckpointStore;
import com.cognitanexus.query.graph.projection.application.GraphProjectionInspector;
import com.cognitanexus.query.graph.projection.application.GraphProjectionReconciler;
import com.cognitanexus.query.graph.projection.application.GraphProjectionSynchronizer;
import com.cognitanexus.query.graph.projection.infrastructure.GraphProjectionCheckpointMapper;
import com.cognitanexus.query.graph.projection.infrastructure.GraphProjectionSourceMapper;
import com.cognitanexus.query.graph.projection.worker.GraphProjectionWorker;
import com.cognitanexus.query.platform.persistence.JsonCodec;
import com.cognitanexus.query.projection.application.ProjectionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 图投影的条件装配。
 *
 * <p>锁住的行为是「默认关闭 = 什么都不装」：{@code cn.query.graph.projection.*} 全部默认关闭时，
 * 多出来的 Mapper、调度器与后台任务都可能改变现有部署行为，而「只是加了个 Bean」正是这类事故的
 * 起点（AGENTS.md §1「先复用，再演进」）。同时锁住反方向：两层开关都打开且
 * {@code worker-enabled=true} 时，Worker 必须真的在容器里，否则「调度开着」只是一句配置。
 */
class GraphProjectionConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(GraphProjectionConfiguration.class)
          .withBean(
              GraphProjectionSourceMapper.class, () -> mock(GraphProjectionSourceMapper.class))
          .withBean(
              GraphProjectionCheckpointMapper.class,
              () -> mock(GraphProjectionCheckpointMapper.class))
          .withBean(Neo4jSessionRunner.class, () -> mock(Neo4jSessionRunner.class))
          .withBean(JsonCodec.class, () -> new JsonCodec(new ObjectMapper()))
          .withBean(
              ProjectionProperties.class, GraphProjectionConfigurationTest::projectionProperties)
          .withBean(Neo4jGraphProperties.class, GraphProjectionConfigurationTest::graphProperties)
          .withBean(GraphProjectionStore.class, () -> new FakeStore("query-public", 0L, "gen0001"));

  private static ProjectionProperties projectionProperties() {
    return new ProjectionProperties(
        "query", 500, 5, 200, false, 2_000L, 20_000, 100_000, 1_000L, 10_000, 20, 100);
  }

  private static Neo4jGraphProperties graphProperties() {
    return new Neo4jGraphProperties(
        true,
        "bolt://127.0.0.1:7687",
        "neo4j",
        "secret",
        "neo4j",
        Duration.ofSeconds(3),
        Duration.ofSeconds(10),
        20,
        50,
        20_000,
        100_000,
        1_000L,
        "query-public",
        false);
  }

  @Test
  @DisplayName("默认配置：一个图投影 Bean 都不装（含调度器），不连图库也不跑后台任务")
  void nothingIsWiredByDefault() {
    runner.run(
        context -> {
          assertThat(context).doesNotHaveBean(GraphProjectionWorker.class);
          assertThat(context).doesNotHaveBean(GraphEventProjector.class);
          assertThat(context).doesNotHaveBean(GraphBackfillService.class);
          assertThat(context).doesNotHaveBean(GraphProjectionReconciler.class);
          assertThat(context).doesNotHaveBean(GraphProjectionCheckpointStore.class);
        });
  }

  @Test
  @DisplayName("只打开图库但没打开投影同步：读侧装配在，写图/调度仍不装")
  void graphWithoutProjectionDoesNotWireProjectionServices() {
    runner
        .withPropertyValues("cn.query.graph.enabled=true")
        .run(
            context -> {
              assertThat(context).hasBean("graphProjectionSource");
              assertThat(context).hasBean("graphProjectionCheckpointStore");
              assertThat(context).hasSingleBean(GraphProjectionInspector.class);
              assertThat(context).doesNotHaveBean(GraphEventProjector.class);
              assertThat(context).doesNotHaveBean(GraphProjectionWorker.class);
            });
  }

  @Test
  @DisplayName("打开投影但 worker-enabled=false：回填/对账可装配，调度器不装")
  void projectionWithoutWorkerEnabledDoesNotSchedule() {
    runner
        .withPropertyValues("cn.query.graph.enabled=true", "cn.query.graph.projection.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(GraphEventProjector.class);
              assertThat(context).hasSingleBean(GraphProjectionSynchronizer.class);
              assertThat(context).hasSingleBean(GraphProjectionReconciler.class);
              assertThat(context)
                  .as("后台增量追赶必须显式开启，不能因为打开投影就自动跑起来")
                  .doesNotHaveBean(GraphProjectionWorker.class);
            });
  }

  @Test
  @DisplayName("全部打开：调度器装配到容器里（与其他投影 Bean 同一次装配）")
  void workerIsWiredWhenAllSwitchesOn() {
    runner
        .withPropertyValues(
            "cn.query.graph.enabled=true",
            "cn.query.graph.projection.enabled=true",
            "cn.query.graph.projection.worker-enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(GraphProjectionWorker.class);
              GraphProjectionWorker worker = context.getBean(GraphProjectionWorker.class);
              // 未写图时水位为 0：装配成功且没有触发任何写入。
              assertThat(worker.projectedVersion()).isZero();
            });
  }
}
