package com.cognitanexus.ingestion.collect.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectedRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Nobel 获奖记录映射。
 *
 * <p>依据：AC-IMPL-FR16-01（四类成果各须有可公开样例）。
 *
 * <p>关键判据是「一条记录 = 一个人一次获奖」：一位获奖者可能拿过两次奖，同一年一个奖项也可能
 * 有三位获奖者。若按「一个奖项一条」聚合，后一位获奖者会覆盖前一位，学者门户里就会出现「他获得
 * 了别人的奖」。
 */
class NobelAdapterTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String LAUREATE =
      """
      {
        "id":"745",
        "knownName":{"en":"A. Michael Spence","se":"A. Michael Spence"},
        "gender":"male",
        "birth":{"date":"1943-11-06"},
        "nobelPrizes":[
          {
            "awardYear":"2001",
            "category":{"en":"Economic Sciences","se":"Ekonomi"},
            "categoryFullName":{"en":"The Sveriges Riksbank Prize in Economic Sciences in Memory of Alfred Nobel"},
            "motivation":{"en":"for their analyses of markets with asymmetric information"},
            "affiliations":[{"name":{"en":"Stanford University"}}],
            "links":[{"rel":"nobelPrize","href":"https://api.nobelprize.org/2/nobelPrize/eco/2001"}]
          }
        ]
      }
      """;

  @Test
  @DisplayName("offset 解析：空为 0，非法或负值报错")
  void offsetParsing() {
    assertThat(NobelAdapter.offsetOf(null)).isZero();
    assertThat(NobelAdapter.offsetOf("50")).isEqualTo(50);
    assertThatThrownBy(() -> NobelAdapter.offsetOf("-1"))
        .isInstanceOf(com.cognitanexus.sharedkernel.BusinessException.class);
    assertThatThrownBy(() -> NobelAdapter.offsetOf("page-two"))
        .isInstanceOf(com.cognitanexus.sharedkernel.BusinessException.class);
  }

  @Test
  @DisplayName("URL 带 limit 与 offset")
  void urlCarriesPaging() {
    assertThat(NobelAdapter.buildUrl("https://api.nobelprize.org/2.1/laureates", 100, 200))
        .contains("limit=100")
        .contains("offset=200");
  }

  @Test
  @DisplayName("一次获奖产出一条 AWARD 记录，字段完整且不含个人隐私")
  void mapsOnePrizePerRecord() throws Exception {
    List<CollectedRecord> records = new ArrayList<>();
    NobelAdapter.collectPrizes(
        JSON.readTree(LAUREATE), Instant.parse("2026-10-17T00:00:00Z"), Set.of(), records);

    assertThat(records).hasSize(1);
    CollectedRecord record = records.get(0);
    assertThat(record.sourceRecordId()).isEqualTo("nobel-745-2001-eco");
    assertThat(record.payload())
        .containsEntry("kind", "AWARD")
        .containsEntry("title", "Nobel Prize in Economic Sciences 2001")
        .containsEntry("awardYear", 2001)
        .containsEntry("awardLevel", "INTERNATIONAL")
        .containsEntry("publicationYear", 2001)
        .containsEntry(
            "awardName",
            "The Sveriges Riksbank Prize in Economic Sciences in Memory of Alfred Nobel")
        .containsEntry("grantingBody", "Nobel Foundation");
    assertThat(record.payload().values().toString()).doesNotContain("1943-11-06");
  }

  @Test
  @DisplayName("同一人两次获奖产出两条记录，不互相覆盖")
  void twoPrizesProduceTwoRecords() throws Exception {
    JsonNode laureate =
        JSON.readTree(
            """
            {
              "id":"999",
              "knownName":{"en":"Two Time"},
              "nobelPrizes":[
                {"awardYear":"1954","category":{"en":"Chemistry"},
                 "links":[{"href":"https://api.nobelprize.org/2/nobelPrize/che/1954"}]},
                {"awardYear":"1962","category":{"en":"Peace"},
                 "links":[{"href":"https://api.nobelprize.org/2/nobelPrize/pea/1962"}]}
              ]
            }
            """);

    List<CollectedRecord> records = new ArrayList<>();
    NobelAdapter.collectPrizes(laureate, Instant.now(), Set.of(), records);

    assertThat(records).hasSize(2);
    assertThat(records.stream().map(CollectedRecord::sourceRecordId))
        .containsExactly("nobel-999-1954-che", "nobel-999-1962-pea");
  }

  @Test
  @DisplayName("缺获奖人姓名时跳过，不产出无名记录")
  void skipsLaureateWithoutName() throws Exception {
    JsonNode laureate =
        JSON.readTree(
            """
            {"id":"1","nobelPrizes":[{"awardYear":"2000","category":{"en":"Physics"}}]}
            """);

    List<CollectedRecord> records = new ArrayList<>();
    NobelAdapter.collectPrizes(laureate, Instant.now(), Set.of(), records);

    assertThat(records).isEmpty();
  }
}
