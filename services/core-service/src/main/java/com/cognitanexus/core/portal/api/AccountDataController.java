package com.cognitanexus.core.portal.api;

import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.portal.application.AccountDataExportService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号级数据导出。
 *
 * <p>返回文件本体而不是 JSON 信封：这是一次下载，把文件包进信封等于让每个客户端各写一遍解码。
 * 文件名用 URLEncoder 处理——文件名里只有 ASCII，但先按规范编码，避免将来有中文用户名时出现
 * 响应头乱码。
 */
@RestController
@RequestMapping("/api/v1/account")
public class AccountDataController {

  private final AccountDataExportService exports;

  public AccountDataController(AccountDataExportService exports) {
    this.exports = exports;
  }

  @GetMapping("/export")
  public ResponseEntity<byte[]> export() {
    AccountDataExportService.ExportFile file = exports.export(ActorContextHolder.currentOrNull());
    String encoded = URLEncoder.encode(file.filename(), StandardCharsets.UTF_8).replace("+", "%20");
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
        .contentType(MediaType.APPLICATION_JSON)
        .body(file.content());
  }
}
