package com.cognitanexus.ingestion.collect.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cognitanexus.sharedkernel.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 文件导入的解析与校验。
 *
 * <p>依据：AC-IMPL-FR08（文件形态的来源输入）、AC-IMPL-FR16-01（四类成果均可入库）。
 *
 * <p>这一层守三件事：①坏行必须指出行号与原因，不能只说「有 N 条没进去」；②类型必填字段缺失时
 * 拒绝入库，而不是写一条「有标题但什么都不是」的成果；③没有稳定标识时不伪造一个——用内容指纹
 * 保证重复导入幂等，但不同文件里的相似记录不会被自动合并。
 */
class ImportServiceTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static ImportService service() {
    // 解析与校验不依赖来源配置和 core 客户端，构造空实现即可
    return new ImportService(null, null, JSON);
  }

  @Test
  @DisplayName("CSV 支持双引号包裹、引号内逗号与换行")
  void splitsQuotedCsv() {
    String text =
        "kind,title,authors\n" + "PAPER,\"A, B and C\",\"张三|清华大学;李四\"\n" + "PAPER,\"多行\n标题\",王五\n";

    List<List<String>> table = ImportService.splitCsv(text);

    assertThat(table).hasSize(3);
    assertThat(table.get(1)).containsExactly("PAPER", "A, B and C", "张三|清华大学;李四");
    assertThat(table.get(2).get(1)).isEqualTo("多行\n标题");
  }

  @Test
  @DisplayName("引号未闭合时报错，不猜测行列边界")
  void rejectsUnclosedQuote() {
    assertThatThrownBy(() -> ImportService.splitCsv("kind,title\nPAPER,\"未闭合\n"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("未闭合");
  }

  @Test
  @DisplayName("解析 CSV 时去掉 BOM 并跳过全空行")
  void parsesCsvWithBomAndBlankLines() {
    byte[] content =
        ("\uFEFFkind,title,authors\n" + "PAPER,第一篇,张三\n" + ",,\n").getBytes(StandardCharsets.UTF_8);

    List<Map<String, Object>> rows = service().parse("patents.csv", content);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("kind", "PAPER").containsEntry("title", "第一篇");
  }

  @Test
  @DisplayName("解析 JSON 数组")
  void parsesJsonArray() {
    byte[] content =
        """
        [{"kind":"AWARD","title":"某奖","awardYear":"2024"}]
        """
            .getBytes(StandardCharsets.UTF_8);

    List<Map<String, Object>> rows = service().parse("awards.json", content);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("kind", "AWARD");
  }

  @Test
  @DisplayName("专利必须有申请号或公开号")
  void patentRequiresNumber() {
    Map<String, Object> row = Map.of("kind", "PATENT", "title", "一种装置");

    assertThatThrownBy(() -> service().toPayload(row, Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("applicationNo");
  }

  @Test
  @DisplayName("科研项目必须有项目号")
  void projectRequiresProjectNo() {
    Map<String, Object> row = Map.of("kind", "PROJECT", "title", "某项目");

    assertThatThrownBy(() -> service().toPayload(row, Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("projectNo");
  }

  @Test
  @DisplayName("获奖必须有获奖年份，缺奖项名时用标题兜底")
  void awardRequiresYear() {
    assertThatThrownBy(() -> service().toPayload(Map.of("kind", "AWARD", "title", "某奖"), Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("awardYear");

    Map<String, Object> payload =
        service().toPayload(Map.of("kind", "AWARD", "title", "某奖", "awardyear", "2024"), Set.of());
    assertThat(payload)
        .containsEntry("kind", "AWARD")
        .containsEntry("awardName", "某奖")
        .containsEntry("awardYear", 2024);
  }

  @Test
  @DisplayName("类型取值非法时报错")
  void rejectsUnknownKind() {
    assertThatThrownBy(() -> service().toPayload(Map.of("kind", "BOOK", "title", "x"), Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("kind 取值非法");
  }

  @Test
  @DisplayName("作者列按 姓名|机构 解析，顺序保留")
  void parsesAuthors() {
    List<Map<String, Object>> authors = ImportService.authors("张三|清华大学;李四;王五|中科院");

    assertThat(authors).hasSize(3);
    assertThat(authors.get(0)).containsEntry("name", "张三").containsEntry("order", 1);
    assertThat(authors.get(0).get("organizations")).isEqualTo(List.of("清华大学"));
    assertThat(authors.get(1)).doesNotContainKey("organizations");
  }

  @Test
  @DisplayName("稳定标识优先作为来源记录标识，没有时用内容指纹")
  void sourceRecordIdPrefersStableKeys() {
    assertThat(
            ImportService.sourceRecordId(
                Map.of("kind", "PAPER", "title", "t", "doi", "https://doi.org/10.1/ABC")))
        .isEqualTo("10.1/abc");
    assertThat(
            ImportService.sourceRecordId(
                Map.of("kind", "PATENT", "title", "t", "applicationNo", "CN202310001234.5")))
        .isEqualTo("cn202310001234.5");

    String first =
        ImportService.sourceRecordId(Map.of("kind", "AWARD", "title", "物理奖", "awardYear", 2024));
    String again =
        ImportService.sourceRecordId(Map.of("kind", "AWARD", "title", "物理奖", "awardYear", 2024));
    assertThat(first).startsWith("import-").isEqualTo(again);
  }

  @Test
  @DisplayName("字段白名单生效，但 kind 与 title 始终保留")
  void respectsAllowedFields() {
    Map<String, Object> row =
        Map.of(
            "kind", "PAPER",
            "title", "标题",
            "doi", "10.1/x",
            "publicationyear", "2024");

    Map<String, Object> payload = service().toPayload(row, Set.of("kind", "title", "doi"));

    assertThat(payload).containsOnlyKeys("kind", "title", "doi");
  }

  @Test
  @DisplayName("年份超出合理范围时报错，不写入一条公元 20245 年的成果")
  void rejectsAbsurdYear() {
    assertThatThrownBy(
            () ->
                service()
                    .toPayload(
                        Map.of("kind", "PAPER", "title", "x", "publicationyear", "20245"),
                        Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超出合理范围");
  }
}
