package com.cognitanexus.core.catalog.api;

import com.cognitanexus.core.catalog.application.CatalogCitationService;
import com.cognitanexus.core.catalog.application.CitationBundle;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开引用导出。
 *
 * <p>依据：FR02。两个入口：一次拿全四种格式（前端引用面板用），以及只拿一种（下载 .bib/.ris、
 * 命令行拼接引用用）。
 *
 * <p>下载入口<b>手工写响应体</b>，不返回 {@code ResponseEntity<String>}。理由是踩过的坑：一旦让
 * Spring 按 {@code Accept} 做内容协商，请求方声明 {@code Accept: text/plain} 而业务拒绝时，
 * 错误处理器想写 JSON 就会命中「无可用转换器」，容器再派发一次 ERROR，认证过滤器不处理 ERROR 派发，
 * 客户端最终看到的是一个与本意无关的状态码。错误报文不该取决于请求方声明接受什么。
 */
@RestController
@RequestMapping("/api/v1/public/achievements/{achievementId}")
public class PublicCitationController {

  private final CatalogCitationService citations;

  public PublicCitationController(CatalogCitationService citations) {
    this.citations = citations;
  }

  /**
   * 全部引用格式。
   *
   * <p>响应里带 {@code warnings}：元数据不全或成果已被撤稿时，文本本身可以照常给出，但必须同时说明
   * 它在哪里不完整。让前端自己判断「这段引用看起来对不对」是不可能做对的事。
   */
  @GetMapping("/citations")
  public ResponseEntity<ApiResponse<Map<String, Object>>> citations(
      @PathVariable String achievementId) {
    CitationBundle bundle = citations.citations(achievementId);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("achievementId", bundle.achievementId());
    view.put("citeKey", bundle.citeKey());
    view.put("defaultFormat", CitationViews.formatCode(bundle.defaultFormat()));
    view.put("provenance", bundle.provenance());
    view.put("exportedOn", bundle.exportedOn());
    view.put("warnings", CitationViews.warningsView(bundle.warnings()));
    List<Map<String, Object>> formats = new ArrayList<>();
    for (CitationBundle.FormatEntry entry : bundle.formats()) {
      Map<String, Object> item = new LinkedHashMap<>();
      String code = CitationViews.formatCode(entry.format());
      item.put("code", code);
      item.put("label", entry.format().label());
      item.put("text", entry.text());
      item.put("fileName", entry.fileName());
      item.put("mediaType", entry.mediaType());
      item.put("machineReadable", entry.machineReadable());
      item.put(
          "downloadUrl",
          "/api/v1/public/achievements/" + bundle.achievementId() + "/citation?format=" + code);
      item.put("warnings", CitationViews.warningsView(entry.warnings()));
      formats.add(item);
    }
    view.put("formats", formats);
    view.put(
        "note",
        "引用文本由平台按已登记元数据生成，未做任何人工润色；标注的缺失项需按原文补全后再使用。" + "文本里没有卷、期、页码，是因为平台不采集这些字段，而不是它们不存在");
    return ResponseEntity.ok(ApiResponse.ok(view, TraceIdHolder.current()));
  }

  /**
   * 单一格式，作为附件返回。
   *
   * <p>文件名只用 ASCII 的引用键：<b>不把标题拼进文件名</b>。中文标题放进 {@code Content-Disposition}
   * 需要 {@code filename*} 加百分号编码，而不同浏览器对这个字段的处理并不一致，结果是一部分用户
   * 下载到一个乱码文件名——引用键本身已经唯一，够用了。
   */
  @GetMapping("/citation")
  public void citation(
      @PathVariable String achievementId,
      @RequestParam(name = "format", required = false) String format,
      HttpServletResponse response)
      throws IOException {
    CatalogCitationService.CitationText text = citations.citation(achievementId, format);
    String fileName = text.citeKey() + "." + text.format().fileExtension();
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(text.format().mediaType() + ";charset=UTF-8");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
    // 引用文本随成果元数据变化，但变化不频繁；短缓存足够，同时避免撤稿后长期给出旧标记。
    response.setHeader("Cache-Control", "public, max-age=300");
    response.getWriter().write(text.text());
  }
}
