package com.cognitanexus.query.discovery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.query.discovery.infrastructure.SearchMapper;
import com.cognitanexus.query.projection.application.ProjectionProperties;
import com.cognitanexus.sharedkernel.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 检索导出的三条边界（2026-10-18 导出功能补）：
 *
 * <ol>
 *   <li>CSV 按 RFC 4180 转义：逗号、引号、换行不能让一列变成两列、一行变成两行；</li>
 *   <li>命中超过上限时拒绝并说明原因，不静默截断——截断的 CSV 看起来像完整结果；</li>
 *   <li>空结果仍要有表头：消费方（脚本、表格软件）按固定列名解析，没有表头才会真的出错。</li>
 * </ol>
 */
class SearchServiceExportTest {

  private SearchMapper mapper;
  private PublicBarrierGuard guard;
  private SearchService service;

  @BeforeEach
  void setUp() {
    mapper = mock(SearchMapper.class);
    guard = mock(PublicBarrierGuard.class);
    when(guard.assertPubliclyServed())
        .thenReturn(new PublicBarrierGuard.BarrierSnapshot(100L, 100L, 100L, 100L, "gen0008"));
    ProjectionProperties properties =
        new ProjectionProperties(
            "query", 500, 5, 200, true, 2000L, 20000, 60000, 800L, 10000, 20, 100);
    service = new SearchService(mapper, guard, properties);
  }

  private static SearchQuery query() {
    return new SearchQuery(
        "network", "network", "PAPER", null, null, null, null, "REAL", null, 1, 10, null);
  }

  private static SearchMapper.AchievementHit hit(String id, String title) {
    return new SearchMapper.AchievementHit(
        id,
        "PAPER",
        title,
        "t",
        "REAL",
        "PUBLIC",
        "EFFECTIVE",
        "NONE",
        2024,
        null,
        null,
        null,
        null,
        null,
        1.0);
  }

  @Test
  @DisplayName("CSV：含逗号/引号的标题被引号包裹并翻倍引号，行以 CRLF 结尾，文件带 BOM")
  void csvEscapingFollowsRfc4180() {
    when(mapper.countHits(anyString(), any())).thenReturn(1L);
    when(mapper.listHits(anyString(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(hit("ach_1", "Graph, \"Network\" Analysis\n2024")));
    when(mapper.listAuthorNames(anyString(), anyList())).thenReturn(List.of());
    when(mapper.listTopics(anyString(), anyList())).thenReturn(List.of());

    byte[] csv = service.exportCsv(query());
    String text = new String(csv, StandardCharsets.UTF_8);

    assertThat(text.startsWith("\uFEFF")).isTrue();
    assertThat(text).contains("\"Graph, \"\"Network\"\" Analysis\n2024\"");
    assertThat(text.endsWith("\r\n")).isTrue();
    // 引号内的 LF 是字段内容的一部分，不是行分隔；行数按 CRLF 数。
    assertThat(text.split("\r\n", -1).length - 1).isEqualTo(2);
  }

  @Test
  @DisplayName("命中超过上限时拒绝导出，不查任何列表")
  void overLimitIsRejectedWithoutExport() {
    when(mapper.countHits(anyString(), any()))
        .thenReturn((long) (SearchService.MAX_EXPORT_ROWS + 1));

    assertThatThrownBy(() -> service.exportCsv(query()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("5000")
        .hasMessageContaining("缩小范围");
    verify(mapper, never()).listHits(anyString(), any(), anyInt(), anyInt());
  }

  @Test
  @DisplayName("空结果仍输出表头；作者与领域以分号连接为单列")
  void emptyResultKeepsHeaderAndJoinsListColumns() {
    when(mapper.countHits(anyString(), any())).thenReturn(0L);

    String text = new String(service.exportCsv(query()), StandardCharsets.UTF_8);
    assertThat(text)
        .startsWith(
            "\uFEFFid,type,title,publicationYear,doi,venue,citationCount,dataNature,authors,topics\r\n");
    verify(mapper, never()).listAuthorNames(anyString(), anyList());
  }

  @Test
  @DisplayName("作者与领域各占一列，多人/多领域用分号连接")
  void listColumnsAreJoinedWithSemicolon() {
    when(mapper.countHits(anyString(), any())).thenReturn(1L);
    when(mapper.listHits(anyString(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(hit("ach_1", "Two Authors")));
    when(mapper.listAuthorNames(anyString(), anyList()))
        .thenReturn(
            List.of(
                new SearchMapper.NameRow("ach_1", "Wang Shiping"),
                new SearchMapper.NameRow("ach_1", "Li Na")));
    when(mapper.listTopics(anyString(), anyList()))
        .thenReturn(
            List.of(
                new SearchMapper.TopicRow("ach_1", "top_1", "图学习", "SOURCE", null),
                new SearchMapper.TopicRow("ach_1", "top_2", "数据质量", "INFERRED", null)));

    String text = new String(service.exportCsv(query()), StandardCharsets.UTF_8);
    String dataLine = text.substring(text.indexOf("\r\n") + 2).stripTrailing();
    String[] columns = splitCsvLine(dataLine);
    assertThat(columns[8]).isEqualTo("Wang Shiping; Li Na");
    assertThat(columns[9]).isEqualTo("图学习; 数据质量");
  }

  /** 与服务端转义规则一致的解析器，仅用于断言（RFC 4180 单行简化版）。 */
  private static String[] splitCsvLine(String line) {
    java.util.List<String> columns = new java.util.ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (quoted) {
        if (ch == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            current.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          current.append(ch);
        }
      } else if (ch == '"') {
        quoted = true;
      } else if (ch == ',') {
        columns.add(current.toString());
        current.setLength(0);
      } else {
        current.append(ch);
      }
    }
    columns.add(current.toString());
    return columns.toArray(String[]::new);
  }
}
