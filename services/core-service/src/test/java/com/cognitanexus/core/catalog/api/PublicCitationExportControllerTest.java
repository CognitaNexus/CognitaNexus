package com.cognitanexus.core.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.application.CatalogCitationService;
import com.cognitanexus.core.catalog.application.CitationBatch;
import com.cognitanexus.core.catalog.domain.CitationFormat;
import com.cognitanexus.core.catalog.domain.CitationWarning;
import com.cognitanexus.core.platform.web.ApiResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 批量导出接口的响应契约。
 *
 * <p>三件必须钉住的事：预览报文里能看清「少给了哪条、为什么」；下载地址逐条编码而不是拼逗号串；
 * 下载入口手工写的响应头与正文逐字一致——预览看到的与他下载到的必须是同一份内容，否则「先看再下」
 * 这个流程本身就是误导。
 */
@ExtendWith(MockitoExtension.class)
class PublicCitationExportControllerTest {

  @Mock CatalogCitationService citations;

  private PublicCitationExportController controller() {
    return new PublicCitationExportController(citations);
  }

  @Test
  @DisplayName("预览：条目、跳过的条目、文件级告警与下载地址齐备")
  void batchPreview() {
    when(citations.exportBatch(List.of("ach_1", "ach_gone"), "bibtex")).thenReturn(batch());

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller().batch(List.of("ach_1", "ach_gone"), "bibtex");
    Map<String, Object> data = response.getBody().data();

    assertThat(data)
        .containsEntry("format", "bibtex")
        .containsEntry("mediaType", "application/x-bibtex")
        .containsEntry("machineReadable", true)
        .containsEntry("requested", 2)
        .containsEntry("exported", 1)
        .containsEntry("skippedCount", 1)
        .containsEntry("fileName", "citations-bibtex-2026-10-18.bib");
    assertThat(data.get("text")).isEqualTo("@article{k1,\n}\n");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries = (List<Map<String, Object>>) data.get("entries");
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0))
        .containsEntry("achievementId", "ach_1")
        .containsEntry("citeKey", "k1");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> skipped = (List<Map<String, Object>>) data.get("skipped");
    assertThat(skipped).hasSize(1);
    assertThat(skipped.get(0))
        .containsEntry("achievementId", "ach_gone")
        .containsEntry("reason", CitationBatch.REASON_NOT_AVAILABLE);

    assertThat((List<?>) data.get("warnings")).hasSize(1);
    // 逐条编码：id 里出现逗号时逗号串会被拆成两个不存在的 id，而 id 来自客户端。
    assertThat(data.get("downloadUrl"))
        .isEqualTo("/api/v1/public/citations/batch/download?format=bibtex&ids=ach_1&ids=ach_gone");
  }

  @Test
  @DisplayName("预览禁止缓存：否则读者会对着上一次的文本点下载")
  void previewIsNotCacheable() {
    when(citations.exportBatch(List.of("ach_1"), "bibtex")).thenReturn(batch());

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller().batch(List.of("ach_1"), "bibtex");

    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
  }

  @Test
  @DisplayName("下载：附件头、字符集与正文取自同一份批量结果")
  void download() throws Exception {
    when(citations.exportBatch(List.of("ach_1"), "bibtex")).thenReturn(batch());

    MockHttpServletResponse response = new MockHttpServletResponse();
    controller().download(List.of("ach_1"), "bibtex", response);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo("application/x-bibtex;charset=UTF-8");
    assertThat(response.getHeader("Content-Disposition"))
        .isEqualTo("attachment; filename=\"citations-bibtex-2026-10-18.bib\"");
    assertThat(response.getContentAsString()).isEqualTo("@article{k1,\n}\n");
  }

  @Test
  @DisplayName("下载：国标按 text/plain 与 .txt 交付")
  void downloadPlainText() throws Exception {
    when(citations.exportBatch(List.of("ach_1"), "gb_t_7714")).thenReturn(plainBatch());

    MockHttpServletResponse response = new MockHttpServletResponse();
    controller().download(List.of("ach_1"), "gb_t_7714", response);

    assertThat(response.getContentType()).isEqualTo("text/plain;charset=UTF-8");
    assertThat(response.getHeader("Content-Disposition"))
        .isEqualTo("attachment; filename=\"citations-gb-t-7714-2026-10-18.txt\"");
    assertThat(response.getContentAsString()).isEqualTo("[1] 张三. 题名[J]. 某刊, 2023.");
  }

  private static CitationBatch batch() {
    return new CitationBatch(
        CitationFormat.BIBTEX,
        "citations-bibtex-2026-10-18.bib",
        "application/x-bibtex",
        true,
        2,
        List.of(
            new CitationBatch.Entry(
                "ach_1",
                "k1",
                "@article{k1,\n}\n",
                List.of(CitationWarning.of(CitationWarning.YEAR_UNKNOWN, "来源未提供年份")))),
        List.of(
            new CitationBatch.Skipped(
                "ach_gone", CitationBatch.REASON_NOT_AVAILABLE, "该成果当前不可公开导出，已跳过")),
        List.of(CitationWarning.of(CitationWarning.CITE_KEY_DISAMBIGUATED, "有 1 条引用键重复，已加后缀")),
        "@article{k1,\n}\n",
        "CognitaNexus 学术成果分享平台",
        "2026-10-18");
  }

  private static CitationBatch plainBatch() {
    return new CitationBatch(
        CitationFormat.GB_T_7714,
        "citations-gb-t-7714-2026-10-18.txt",
        "text/plain",
        false,
        1,
        List.of(new CitationBatch.Entry("ach_1", "k1", "张三. 题名[J]. 某刊, 2023.", List.of())),
        List.of(),
        List.of(),
        "[1] 张三. 题名[J]. 某刊, 2023.",
        "CognitaNexus 学术成果分享平台",
        "2026-10-18");
  }
}
