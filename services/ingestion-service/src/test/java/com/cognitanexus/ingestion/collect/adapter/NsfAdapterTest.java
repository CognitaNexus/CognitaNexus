package com.cognitanexus.ingestion.collect.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectedRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * NSF 项目记录映射。
 *
 * <p>依据：AC-IMPL-FR16-01（四类成果各须有可公开样例）、AC-IMPL-FR16-02（规模按去重实体计）。
 *
 * <p>这一层盯两件事：项目专属字段（项目号、负责人、资助机构、起止日期）必须落到 core 认识的
 * 键上，否则插入时会「成功但字段为空」；以及<strong>不采集的个人信息不进入载荷</strong>——
 * NSF 原始记录里有 PI 邮箱、电话、承担单位地址，漏一个字段就会让公开接口把它们带出去。
 */
class NsfAdapterTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  @DisplayName("空游标从 offset=1 开始，非法游标报错")
  void offsetParsing() {
    assertThat(NsfAdapter.offsetOf(null)).isEqualTo(1);
    assertThat(NsfAdapter.offsetOf("  ")).isEqualTo(1);
    assertThat(NsfAdapter.offsetOf("26")).isEqualTo(26);
    assertThatThrownBy(() -> NsfAdapter.offsetOf("abc"))
        .isInstanceOf(com.cognitanexus.sharedkernel.BusinessException.class);
    assertThatThrownBy(() -> NsfAdapter.offsetOf("0"))
        .isInstanceOf(com.cognitanexus.sharedkernel.BusinessException.class);
  }

  @Test
  @DisplayName("URL 带关键词、页大小与 offset，且只请求必要字段")
  void urlCarriesQueryAndFieldWhitelist() {
    String url =
        NsfAdapter.buildUrl(
            "https://api.nsf.gov/services/v1/awards.json", "robotics", 25, 51, "CognitaNexus/0.2");

    assertThat(url)
        .contains("keyword=robotics")
        .contains("rpp=25")
        .contains("offset=51")
        .contains("printFields=")
        .doesNotContain("piEmail")
        .doesNotContain("awardeePhone");
  }

  @Test
  @DisplayName("项目字段映射到 core 认识的键，年份同时写入 publicationYear")
  void mapsProjectFields() throws Exception {
    JsonNode item =
        JSON.readTree(
            """
            {
              "id":"2602818",
              "title":"Collaborative Research: Group Structure and Sensing",
              "startDate":"01/15/2027",
              "expDate":"12/31/2029",
              "awardeeName":"Old Dominion University Research Foundation",
              "pdPIName":"Ian K Bartol",
              "agency":"NSF",
              "program":["Artificial Intelligence (AI)","Biotechnology"],
              "abstractText":"This study focuses on hydrodynamic benefits.",
              "piEmail":"ibartol@odu.edu",
              "awardeePhone":"7576834292"
            }
            """);

    CollectedRecord record =
        NsfAdapter.toRecord(item, Instant.parse("2026-10-17T00:00:00Z"), Set.of());

    assertThat(record.sourceRecordId()).isEqualTo("2602818");
    assertThat(record.payload())
        .containsEntry("kind", "PROJECT")
        .containsEntry("projectNo", "2602818")
        .containsEntry("leaderName", "Ian K Bartol")
        .containsEntry("fundingAgency", "NSF")
        .containsEntry("projectCategory", "Artificial Intelligence (AI)")
        .containsEntry("projectStartDate", "2027-01-15")
        .containsEntry("projectEndDate", "2029-12-31")
        .containsEntry("publicationYear", 2027);
    // 个人信息不得进入载荷
    assertThat(record.payload().values().toString())
        .doesNotContain("ibartol@odu.edu")
        .doesNotContain("7576834292");
  }

  @Test
  @DisplayName("负责人缺失时退回 piFirstName + piLastName")
  void fallsBackToPiNameParts() throws Exception {
    JsonNode item =
        JSON.readTree(
            """
            {"id":"1","title":"T","piFirstName":"Ada","piLastName":"Lovelace","awardeeName":"X"}
            """);

    CollectedRecord record = NsfAdapter.toRecord(item, Instant.now(), Set.of());

    assertThat(record.payload()).containsEntry("leaderName", "Ada Lovelace");
  }

  @Test
  @DisplayName("缺 id 或标题的记录被跳过，不产生半条成果")
  void skipsIncompleteRecords() throws Exception {
    assertThat(
            NsfAdapter.toRecord(
                JSON.readTree(
                    """
        {"title":"T"}
        """),
                Instant.now(),
                Set.of()))
        .isNull();
    assertThat(
            NsfAdapter.toRecord(
                JSON.readTree(
                    """
        {"id":"1"}
        """),
                Instant.now(),
                Set.of()))
        .isNull();
  }

  @Test
  @DisplayName("字段白名单生效：不在白名单的字段不写入")
  void respectsAllowedFields() throws Exception {
    JsonNode item =
        JSON.readTree(
            """
            {"id":"1","title":"T","pdPIName":"A","agency":"NSF"}
            """);

    CollectedRecord record =
        NsfAdapter.toRecord(item, Instant.now(), Set.of("kind", "title", "projectNo"));

    assertThat(record.payload()).containsOnlyKeys("kind", "title", "projectNo");
  }
}
