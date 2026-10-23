package com.cognitanexus.ingestion.collect.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.ingestion.collect.adapter.OpenAlexAbstractFetcher;
import com.cognitanexus.ingestion.platform.client.CoreInternalClient.PendingAbstract;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 投递条目的拼装规则。
 *
 * <p>守两件事：
 *
 * <ol>
 *   <li><b>来源没返回的 DOI 也要出现在条目里</b>（文本为 null）。漏掉它，core 就不知道这一篇试过，
 *       下一轮会把同一批永远补不上的 DOI 再问一遍——待补清单从此不再收敛，而日志上看不出异常。
 *   <li><b>还原出空串的按「没有摘要」处理</b>。空串写进库里会在详情页显出一段空白，读者无法区分
 *       「来源没给」与「摘要丢了」。
 * </ol>
 */
class AbstractHydrationEntriesTest {

  private static final List<PendingAbstract> BATCH =
      List.of(
          new PendingAbstract("ach_1", "10.1/a", "第一篇"),
          new PendingAbstract("ach_2", "10.1/b", "第二篇"),
          new PendingAbstract("ach_3", "10.1/c", "第三篇"));

  @Test
  @DisplayName("取到的写入文本，没返回的与空串的都按「来源没有」回报，但一条都不能少")
  void everyPendingItemGetsAnEntry() {
    Map<String, OpenAlexAbstractFetcher.WorkAbstract> found =
        Map.of(
            "10.1/a", new OpenAlexAbstractFetcher.WorkAbstract("10.1/a", "W1", "这是摘要"),
            "10.1/b", new OpenAlexAbstractFetcher.WorkAbstract("10.1/b", "W2", "   "));

    AbstractHydrationService.Batch batch = AbstractHydrationService.entriesOf(BATCH, found);

    assertThat(batch.entries()).hasSize(3);
    assertThat(batch.withAbstract()).isEqualTo(1);
    assertThat(batch.withoutAbstract()).isEqualTo(2);

    assertThat(batch.entries().get(0))
        .containsEntry("achievementId", "ach_1")
        .containsEntry("abstractText", "这是摘要");
    // 还原出空串：必须是 null，不能是 ""
    assertThat(batch.entries().get(1))
        .containsEntry("achievementId", "ach_2")
        .containsEntry("abstractText", null);
    // 来源根本没返回这一篇：同样要有一条，文本为 null
    assertThat(batch.entries().get(2))
        .containsEntry("achievementId", "ach_3")
        .containsEntry("abstractText", null);
  }

  @Test
  @DisplayName("来源一个都没返回时，仍然逐条回报「没有」")
  void reportsAllMissingWhenSourceReturnsNothing() {
    AbstractHydrationService.Batch batch = AbstractHydrationService.entriesOf(BATCH, Map.of());

    assertThat(batch.entries()).hasSize(3);
    assertThat(batch.withAbstract()).isZero();
    assertThat(batch.withoutAbstract()).isEqualTo(3);
    assertThat(batch.entries())
        .allSatisfy(entry -> assertThat(entry).containsEntry("abstractText", null));
  }
}
