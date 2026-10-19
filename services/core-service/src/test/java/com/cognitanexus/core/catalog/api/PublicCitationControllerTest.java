package com.cognitanexus.core.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.application.CatalogCitationService;
import com.cognitanexus.core.catalog.application.CitationBundle;
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
 * 引用导出接口的响应契约。
 *
 * <p>这里钉两件事：报文里每个格式都带齐下载所需信息（前端不该自己拼 URL 与文件名），以及下载入口
 * <b>手工写的响应头</b>确实生效——{@code Content-Disposition} 的引号、{@code charset} 的位置都是
 * 靠着才成立的，改错一个字符浏览器就会把 .bib 当成网页打开。
 */
@ExtendWith(MockitoExtension.class)
class PublicCitationControllerTest {

  @Mock CatalogCitationService citations;

  private PublicCitationController controller() {
    return new PublicCitationController(citations);
  }

  @Test
  @DisplayName("全部格式：四个条目、每个带下载地址与文件名，元数据告警在顶层")
  void allFormats() {
    when(citations.citations("ach_1")).thenReturn(bundle());

    ResponseEntity<ApiResponse<Map<String, Object>>> response = controller().citations("ach_1");

    Map<String, Object> data = response.getBody().data();
    assertThat(data).containsEntry("achievementId", "ach_1");
    assertThat(data).containsEntry("citeKey", "anon2023item_3d4e5f");
    assertThat(data).containsEntry("defaultFormat", "gb_t_7714");
    assertThat((List<?>) data.get("warnings")).hasSize(1);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> formats = (List<Map<String, Object>>) data.get("formats");
    assertThat(formats).hasSize(2);
    assertThat(formats.get(0))
        .containsEntry("code", "bibtex")
        .containsEntry("fileName", "anon2023item_3d4e5f.bib")
        .containsEntry("downloadUrl", "/api/v1/public/achievements/ach_1/citation?format=bibtex")
        .containsEntry("machineReadable", true);
    assertThat(formats.get(1)).containsEntry("code", "gb_t_7714");
  }

  @Test
  @DisplayName("单格式下载：附件头、字符集与正文都写在响应里")
  void downloadHeaders() throws Exception {
    when(citations.citation("ach_1", "bibtex"))
        .thenReturn(
            new CatalogCitationService.CitationText(
                CitationFormat.BIBTEX, "anon2023item_3d4e5f", "@article{k,\n}\n", List.of()));

    MockHttpServletResponse response = new MockHttpServletResponse();
    controller().citation("ach_1", "bibtex", response);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo("application/x-bibtex;charset=UTF-8");
    assertThat(response.getHeader("Content-Disposition"))
        .isEqualTo("attachment; filename=\"anon2023item_3d4e5f.bib\"");
    assertThat(response.getContentAsString()).isEqualTo("@article{k,\n}\n");
  }

  @Test
  @DisplayName("国标文本按 text/plain 交付，文件名用 .txt")
  void plainTextDownload() throws Exception {
    when(citations.citation("ach_1", "gb_t_7714"))
        .thenReturn(
            new CatalogCitationService.CitationText(
                CitationFormat.GB_T_7714,
                "anon2023item_3d4e5f",
                "张三. 题名[J]. 某刊, 2023.",
                List.of()));

    MockHttpServletResponse response = new MockHttpServletResponse();
    controller().citation("ach_1", "gb_t_7714", response);

    assertThat(response.getContentType()).isEqualTo("text/plain;charset=UTF-8");
    assertThat(response.getHeader("Content-Disposition"))
        .isEqualTo("attachment; filename=\"anon2023item_3d4e5f.txt\"");
    assertThat(response.getContentAsString()).isEqualTo("张三. 题名[J]. 某刊, 2023.");
  }

  private static CitationBundle bundle() {
    return new CitationBundle(
        "ach_1",
        "anon2023item_3d4e5f",
        CitationFormat.GB_T_7714,
        "CognitaNexus 学术成果分享平台",
        "2026-10-18",
        List.of(CitationWarning.of(CitationWarning.YEAR_UNKNOWN, "来源未提供年份")),
        List.of(
            new CitationBundle.FormatEntry(
                CitationFormat.BIBTEX,
                "@article{k,\n}\n",
                "anon2023item_3d4e5f.bib",
                "application/x-bibtex",
                true,
                List.of()),
            new CitationBundle.FormatEntry(
                CitationFormat.GB_T_7714,
                "张三. 题名[J]. 某刊, 2023.",
                "anon2023item_3d4e5f.txt",
                "text/plain",
                false,
                List.of())));
  }
}
