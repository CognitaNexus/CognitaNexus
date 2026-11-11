package com.cognitanexus.core.catalog.api;

import com.cognitanexus.core.catalog.application.CatalogCitationService;
import com.cognitanexus.core.catalog.application.CitationBatch;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批量引用导出：把读者在检索结果里勾中的几条成果，装配成一份可以直接导入的文献库文件。
 *
 * <p>依据：FR02、ADR-004。为什么必须有两个入口而不是一个：
 *
 * <ul>
 *   <li>{@code GET /batch} 返回 JSON，含整份正文、逐条文本、被跳过的条目与文件级告警。前端要先给读者
 *       看清楚「将要下载的是什么、少了什么」，再让他下载。
 *   <li>{@code GET /batch/download} 直接给文件。下载必须是可导航的 GET：浏览器发起下载时不带请求体，
 *       让下载走 POST 只能靠 fetch + Blob，那样一来大文件全在内存里过一遍，二来右键「链接另存为」
 *       这类常见操作直接失效。
 * </ul>
 *
 * <p><b>两个入口都是 GET。</b>预览是纯读操作，本来就不该发明一个 POST：平台的一次性 CSRF 令牌在
 * 校验通过后立即失效（见前端 `http.ts` 的注释），让一次「看一眼会得到什么」的读操作去消耗写令牌，
 * 会让读者在紧接着点下载时莫名其妙地收到 403。GET 同时让 curl 与脚本可以不带会话直接核对内容。
 *
 * <p>两个入口共用同一个服务方法，因此「预览看到的内容」与「下载到的内容」不可能不一致。
 */
@RestController
@RequestMapping("/api/v1/public/citations")
public class PublicCitationExportController {

  private final CatalogCitationService citations;

  public PublicCitationExportController(CatalogCitationService citations) {
    this.citations = citations;
  }

  /** 批量导出预览。响应里的 {@code text} 与下载得到的内容逐字一致。 */
  @GetMapping("/batch")
  public ResponseEntity<ApiResponse<Map<String, Object>>> batch(
      @RequestParam(name = "ids", required = false) List<String> ids,
      @RequestParam(name = "format", required = false) String format) {
    CitationBatch batch = citations.exportBatch(ids, format);

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("format", CitationViews.formatCode(batch.format()));
    view.put("formatLabel", batch.format().label());
    view.put("mediaType", batch.mediaType());
    view.put("machineReadable", batch.machineReadable());
    view.put("fileName", batch.entryFileName());
    view.put("requested", batch.requested());
    view.put("exported", batch.entries().size());
    view.put("skippedCount", batch.skipped().size());

    List<Map<String, Object>> entries = new ArrayList<>();
    for (CitationBatch.Entry entry : batch.entries()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("achievementId", entry.achievementId());
      item.put("citeKey", entry.citeKey());
      item.put("text", entry.text());
      item.put("warnings", CitationViews.warningsView(entry.warnings()));
      entries.add(item);
    }
    view.put("entries", entries);

    List<Map<String, Object>> skipped = new ArrayList<>();
    for (CitationBatch.Skipped item : batch.skipped()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("achievementId", item.achievementId());
      entry.put("reason", item.reason());
      entry.put("message", item.message());
      skipped.add(entry);
    }
    view.put("skipped", skipped);
    view.put("warnings", CitationViews.warningsView(batch.warnings()));
    view.put("text", batch.text());
    view.put("provenance", batch.provenance());
    view.put("exportedOn", batch.exportedOn());
    view.put("downloadUrl", downloadUrl(batch, ids));
    view.put(
        "note", "文件由平台按已登记元数据生成，未做人工润色。被跳过的成果当前不可公开导出；" + "引用文本里没有卷、期、页码，是因为平台不采集这些字段，而不是它们不存在");
    // 预览随选择与元数据变化，浏览器不得用启发式缓存复用上一次的结果——那会让人对着旧文本点下载。
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(ApiResponse.ok(view, TraceIdHolder.current()));
  }

  /**
   * 批量导出下载。
   *
   * <p>与单条下载一样<b>手工写响应体</b>：返回 {@code ResponseEntity} 会让错误报文走内容协商，
   * 请求方声明 {@code Accept: application/x-bibtex} 时错误处理器写不出 JSON，客户端最终看到的是
   * 一个与本意无关的状态码（这个坑在单条下载入口已经踩过）。
   */
  @GetMapping("/batch/download")
  public void download(
      @RequestParam(name = "ids", required = false) List<String> ids,
      @RequestParam(name = "format", required = false) String format,
      HttpServletResponse response)
      throws IOException {
    CitationBatch batch = citations.exportBatch(ids, format);
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(batch.mediaType() + ";charset=UTF-8");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader(
        "Content-Disposition", "attachment; filename=\"" + batch.entryFileName() + "\"");
    // 与单条下载同一口径：随元数据变化，但变化不频繁；短缓存既减少重复生成，又不会在撤稿后
    // 长期给出旧标记。
    response.setHeader("Cache-Control", "public, max-age=300");
    response.getWriter().write(batch.text());
  }

  /**
   * 下载地址。
   *
   * <p>逐条放进 {@code ids} 参数而不是拼成逗号串：Spring 两种写法都收，但逗号串依赖「id 里不会有
   * 逗号」这个约定，而 id 来自客户端，约定并不由我们保证。逐条 + 编码则不依赖任何约定。
   */
  private static String downloadUrl(CitationBatch batch, List<String> ids) {
    StringBuilder url = new StringBuilder("/api/v1/public/citations/batch/download");
    url.append("?format=").append(CitationViews.formatCode(batch.format()));
    for (String id : ids) {
      if (id == null || id.isBlank()) {
        continue;
      }
      url.append("&ids=").append(encode(id.trim()));
    }
    return url.toString();
  }

  private static String encode(String value) {
    // 用 (String, Charset) 重载：它不抛受检异常，省掉一个永远不会发生的 catch。
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
