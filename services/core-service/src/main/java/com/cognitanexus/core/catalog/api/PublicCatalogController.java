package com.cognitanexus.core.catalog.api;

import com.cognitanexus.core.catalog.application.PublicCatalogQueryService;
import com.cognitanexus.core.catalog.application.ScholarProfileService;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
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
  private final ScholarProfileService profiles;

  public PublicCatalogController(
      PublicCatalogQueryService publicCatalog, ScholarProfileService profiles) {
    this.publicCatalog = publicCatalog;
    this.profiles = profiles;
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

  /**
   * 学者头像。
   *
   * <p>这是本服务里唯一一个**匿名可读的文件接口**，因此三条边界要写清楚：
   *
   * <ul>
   *   <li>只返回图片类型，类型由存储层按文件头判定，不信任任何客户端输入；
   *   <li>没有头像时返回 404，而不是一张默认占位图——占位图由前端用姓名首字绘制，
   *       服务端发图会让「这位学者有没有维护头像」这件事无从判断；
   *   <li>缓存 5 分钟并带 {@code ETag}：头像是列表页高频资源，每次都回源会把档案页拖慢，
   *       但换头像后要能被看到，所以不做长缓存。URL 里的 {@code v} 参数由详情接口给出。
   * </ul>
   */
  @GetMapping("/scholars/{scholarId}/avatar")
  public ResponseEntity<byte[]> avatar(@PathVariable String scholarId) {
    ScholarProfileService.AvatarResource avatar = profiles.avatar(scholarId);
    MediaType mediaType = MediaType.parseMediaType(avatar.mediaType());
    return ResponseEntity.ok()
        .contentType(mediaType)
        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
        .header("X-Content-Type-Options", "nosniff")
        .body(avatar.content());
  }
}
