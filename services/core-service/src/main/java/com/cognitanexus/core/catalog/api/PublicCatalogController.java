package com.cognitanexus.core.catalog.api;

import com.cognitanexus.core.catalog.application.PublicCatalogQueryService;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开成果与学者详情。
 *
 * <p>依据：架构 V0.2 §10.1。无需登录，但服务端强制公开资格：不存在与不可见对外表现一致，
 * 避免用状态码探测受限对象的存在性。
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicCatalogController {

  private final PublicCatalogQueryService publicCatalog;

  public PublicCatalogController(PublicCatalogQueryService publicCatalog) {
    this.publicCatalog = publicCatalog;
  }

  @GetMapping("/achievements/{achievementId}")
  public ResponseEntity<ApiResponse<Map<String, Object>>> achievement(
      @PathVariable String achievementId) {
    return ResponseEntity.ok(
        ApiResponse.ok(publicCatalog.achievementDetail(achievementId), TraceIdHolder.current()));
  }

  @GetMapping("/scholars/{scholarId}")
  public ResponseEntity<ApiResponse<Map<String, Object>>> scholar(@PathVariable String scholarId) {
    return ResponseEntity.ok(
        ApiResponse.ok(publicCatalog.scholarDetail(scholarId), TraceIdHolder.current()));
  }
}
