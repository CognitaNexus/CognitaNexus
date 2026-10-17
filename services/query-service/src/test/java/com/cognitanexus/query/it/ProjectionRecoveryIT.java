package com.cognitanexus.query.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.query.projection.api.InboxRequests.BatchReceipt;
import com.cognitanexus.query.projection.api.InboxRequests.EventRequest;
import com.cognitanexus.query.projection.application.InboxReceiver;
import com.cognitanexus.query.projection.application.ProjectionRepositories.InboxStore;
import com.cognitanexus.query.projection.domain.ConsumerProgress;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 投影链路在重复、乱序、毒事件与恢复下的真实数据库行为（T32）。
 *
 * <p>为什么必须是真实数据库：结论全部落在「水位行推进到几」「事件行是 APPLIED 还是 ISOLATED」
 * 「同一 eventId 第二次投递有没有产生第二行」上，而唯一键、行锁、连续门控三者共同决定结果。
 * 用 Mock 仓储写「insertIfAbsent 返回 0」只能证明实现里有个 if，证明不了唯一键真的在拦。
 *
 * <p>断言使用<b>接收回执</b>而不是事后直接调发布器：回执是 core 侧唯一能看到的东西，
 * 「received 表示已接收、publishedRevision 之前才对外可查」这条语义本身就是要验证的对象。
 *
 * <p>本类不调用 core：非合并事件在准备阶段不访问外部服务，因此这里验证的是本服务自己的
 * 接收与发布语义，不涉及跨服务契约。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"cn.projection.worker-enabled=false"})
class ProjectionRecoveryIT {

  private static final String CONSUMER = "query";

  @Autowired private InboxReceiver receiver;
  @Autowired private InboxStore inbox;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  @DynamicPropertySource
  static void pointToIsolatedDatabase(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> PostgresTestDatabase.DEFAULT_URL);
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres_it");
    registry.add("spring.flyway.url", () -> PostgresTestDatabase.DEFAULT_URL);
    registry.add("spring.flyway.user", () -> "postgres");
    registry.add("spring.flyway.password", () -> "postgres_it");
  }

  @BeforeEach
  void resetProjection() throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
      assertThat(conn.getMetaData().getURL())
          .as("集成测试必须连 55432 的隔离实例")
          .contains(":55432/")
          .contains("query_it");
    }
    jdbc.execute(
        """
        TRUNCATE TABLE qry_inbox_event, qry_achievement, qry_contribution,
                       qry_contribution_org, qry_achievement_topic, qry_coauthor_edge CASCADE
        """);
    jdbc.update(
        "UPDATE qry_consumer_state SET received_revision = 0, highest_seen_revision = 0,"
            + " published_revision = 0 WHERE consumer_id = ?",
        CONSUMER);
    jdbc.update("DELETE FROM qry_id_redirect");
  }

  // ===========================================================================
  // 重复投递
  // ===========================================================================

  @Test
  @DisplayName("T32 重复投递：同批两次、跨批一次，都只产生一行、只投影一次")
  void duplicateDeliveryHasSingleBusinessEffect() {
    EventRequest event = upsert(1, "ach-dup", "重复投递的成果", "PAPER");

    BatchReceipt first = receiver.receive(CONSUMER, List.of(event, event));
    assertThat(first.received()).as("同批重复必须就地折叠成一条").isEqualTo(1);
    assertThat(first.duplicated()).isEqualTo(0);
    assertThat(first.applied()).isEqualTo(1);

    BatchReceipt second = receiver.receive(CONSUMER, List.of(event));
    assertThat(second.received()).as("整批重复时一行都没写入，received 必须是 0").isEqualTo(0);
    assertThat(second.duplicated()).as("跨批重投必须被判为重复而不是新事件").isEqualTo(1);
    assertThat(second.applied()).as("重复投递不得再投影一次").isEqualTo(0);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM qry_inbox_event", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM qry_achievement", Integer.class))
        .isEqualTo(1);
    ConsumerProgress progress = inbox.readProgress(CONSUMER);
    assertThat(progress.receivedRevision()).as("重复投递不得把接收水位推过实际收到的修订").isEqualTo(1);
    assertThat(progress.publishedRevision()).isEqualTo(1);
  }

  // ===========================================================================
  // 乱序抵达
  // ===========================================================================

  @Test
  @DisplayName("T32 乱序抵达：先到 3 后到 1、2，水位停在缺口前；补齐后一次追到 3，缺口本身可被指出")
  void outOfOrderRevisionStallsUntilGapFilled() {
    BatchReceipt afterThree =
        receiver.receive(CONSUMER, List.of(upsert(3, "ach-3", "第三条", "PAPER")));
    assertThat(afterThree.received()).as("事件本身要落库，不能因为缺前序就拒收").isEqualTo(1);
    assertThat(afterThree.applied()).as("缺 1、2 时不得越过它们去投影 3").isEqualTo(0);
    assertThat(afterThree.pendingRemaining()).as("库里躺着未投影的修订 3，回执不得回答「没有积压」").isTrue();
    assertThat(afterThree.receivedRevision()).isEqualTo(0);
    assertThat(afterThree.publishedRevision()).isEqualTo(0);

    ConsumerProgress progress = inbox.readProgress(CONSUMER);
    assertThat(progress.receivedRevision()).as("缺 1、2 时接收水位必须停在 0，否则等于宣称已收到从未到达的修订").isEqualTo(0);
    assertThat(progress.highestSeenRevision()).as("见过的最大修订仍要记下来，用于观察积压").isEqualTo(3);
    assertThat(progress.receiveLag()).as("接收侧积压要能从进度行直接读出来：见过的 3 减去水位 0").isEqualTo(3);
    assertThat(progress.publishLag()).as("事件一条都没通过门控，发布侧积压为 0——积压不在这一段").isEqualTo(0);

    // 缺口必须能被读出来：已发布水位之后最早收到的事件是 3，而期望的是 1。
    assertThat(inbox.firstReceivedRevisionAfter(CONSUMER, 0)).hasValue(3);

    BatchReceipt fillOne = receiver.receive(CONSUMER, List.of(upsert(1, "ach-1", "第一条", "PAPER")));
    assertThat(fillOne.applied()).isEqualTo(1);
    assertThat(fillOne.publishedRevision()).isEqualTo(1);

    BatchReceipt fillTwo = receiver.receive(CONSUMER, List.of(upsert(2, "ach-2", "第二条", "PAPER")));
    assertThat(fillTwo.applied()).as("补齐后同一批里连带把已到达的 3 一起投影").isEqualTo(2);
    assertThat(fillTwo.publishedRevision()).isEqualTo(3);
    assertThat(inbox.readProgress(CONSUMER).receivedRevision()).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM qry_achievement", Integer.class))
        .isEqualTo(3);
  }

  // ===========================================================================
  // 毒事件
  // ===========================================================================

  @Test
  @DisplayName("T32 毒事件：未知 schemaVersion 转隔离，水位停在它之前，积压与最早隔离修订都可见")
  void poisonEventIsIsolatedAndBlocksWatermark() {
    receiver.receive(CONSUMER, List.of(upsert(1, "ach-ok", "正常事件", "PAPER")));

    BatchReceipt poisoned =
        receiver.receive(CONSUMER, List.of(poison(2, 99, "CatalogChangeCommitted")));
    assertThat(poisoned.isolated()).as("未知 schemaVersion 必须被隔离而不是猜字段").isEqualTo(1);
    assertThat(poisoned.publishedRevision()).as("发布水位不得越过被隔离的修订").isEqualTo(1);

    BatchReceipt later =
        receiver.receive(CONSUMER, List.of(upsert(3, "ach-after", "毒事件之后的事件", "PAPER")));
    assertThat(later.applied()).as("被隔离的修订挡住后面的一切").isEqualTo(0);
    assertThat(later.publishedRevision()).isEqualTo(1);

    assertThat(inbox.oldestIsolatedRevision(CONSUMER)).as("被隔离的修订必须可被指出，否则「水位停住」无从排查").isEqualTo(2);
    Map<String, Long> byState = inbox.countByState(CONSUMER);
    assertThat(byState.get("ISOLATED")).as("积压要按状态可见：%s", byState).isEqualTo(1L);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM qry_achievement", Integer.class))
        .as("毒事件之后的事件不得被提前投影")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("T32 恢复：退避重投只重新入队 FAILED，不把 ISOLATED 悄悄放回队列")
  void rearmDoesNotResurrectIsolatedEvents() {
    receiver.receive(CONSUMER, List.of(poison(1, 1, "未定义事件类型")));
    assertThat(inbox.countByState(CONSUMER).get("ISOLATED")).isEqualTo(1L);

    int rearmed = inbox.rearmFailed(CONSUMER, 5);
    assertThat(rearmed).as("没有 FAILED 行时不应重新入队任何东西").isEqualTo(0);
    assertThat(inbox.countByState(CONSUMER).get("ISOLATED"))
        .as("隔离是人的决定，不能被自动重试悄悄改回 RECEIVED")
        .isEqualTo(1L);
    assertThat(inbox.readProgress(CONSUMER).publishedRevision()).isEqualTo(0);
  }

  // ===========================================================================
  // 夹具
  // ===========================================================================

  private static EventRequest upsert(
      long revision, String achievementId, String title, String type) {
    return new EventRequest(
        "evt-" + achievementId,
        "CatalogChangeCommitted",
        1,
        "ACHIEVEMENT",
        achievementId,
        1L,
        revision,
        null,
        null,
        Map.of(
            "changeKind",
            "UPSERT",
            "achievementId",
            achievementId,
            "title",
            title,
            "type",
            type,
            "dataNature",
            "SIMULATED",
            "visibility",
            "PUBLIC",
            "effectiveStatus",
            "EFFECTIVE",
            "publicationYear",
            2024,
            "contributions",
            List.of(),
            "topics",
            List.of()));
  }

  private static EventRequest poison(long revision, int schemaVersion, String eventType) {
    return new EventRequest(
        "evt-poison-" + revision,
        eventType,
        schemaVersion,
        "ACHIEVEMENT",
        "ach-poison-" + revision,
        1L,
        revision,
        null,
        null,
        Map.of("changeKind", "UPSERT", "achievementId", "ach-poison-" + revision));
  }
}
