package com.cognitanexus.sharedkernel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CSV 转义规则。
 *
 * <p>这些断言的价值不在「工具类能用」，而在钉住一条纪律：两个导出出口（公开检索、我的成果）
 * 共用同一套转义。任何一处日后想「顺手改一下分隔符」，这里会先红。
 */
class CsvTest {

  @Test
  @DisplayName("普通字段原样输出，行尾是 CRLF")
  void plainRowEndsWithCrlf() {
    StringBuilder csv = Csv.begin();
    Csv.appendRow(csv, "a", "b", "c");
    assertThat(csv.toString()).isEqualTo(Csv.BOM + "a,b,c\r\n");
  }

  @Test
  @DisplayName("含逗号、双引号、换行的字段被引号包裹，内部引号翻倍")
  void escapesSpecialCharacters() {
    StringBuilder csv = new StringBuilder();
    Csv.appendRow(csv, "Graph, \"Network\"", "line1\nline2", "带\r回车");
    assertThat(csv.toString())
        .isEqualTo("\"Graph, \"\"Network\"\"\",\"line1\nline2\",\"带\r回车\"\r\n");
  }

  @Test
  @DisplayName("null 与空串都输出空字段，而不是字面量 null")
  void nullBecomesEmptyField() {
    StringBuilder csv = new StringBuilder();
    Csv.appendRow(csv, null, "", "x");
    assertThat(csv.toString()).isEqualTo(",,x\r\n");
  }

  @Test
  @DisplayName("每行以 BOM 开头，Excel 才不会按 GBK 解读中文")
  void beginWritesBom() {
    StringBuilder csv = Csv.begin();
    Csv.appendRow(csv, "题名", "作者");
    byte[] bytes = Csv.toBytes(csv);
    assertThat(bytes[0] & 0xFF).isEqualTo(0xEF);
    assertThat(bytes[1] & 0xFF).isEqualTo(0xBB);
    assertThat(bytes[2] & 0xFF).isEqualTo(0xBF);
    assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("题名,作者");
  }

  @Test
  @DisplayName("空结果仍产出表头（只有 BOM 与表头一行）")
  void headerOnlyWhenNoRows() {
    StringBuilder csv = Csv.begin();
    Csv.appendRow(csv, "id", "title");
    String text = csv.toString();
    assertThat(text.startsWith(Csv.BOM + "id,title\r\n")).isTrue();
    assertThat(text.split("\r\n", -1).length - 1).isEqualTo(1);
  }
}
