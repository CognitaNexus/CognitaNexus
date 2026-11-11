package com.cognitanexus.core.notification.api;

import com.cognitanexus.core.notification.application.AnnouncementService;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统公告（管理员）。
 *
 * <p>路径放在 /api/v1/admin/core 下：发布公告是对全体账号的写操作，必须走管理员鉴权与审计，
 * 不能挂在用户自己的 /notifications 路径上。
 */
@RestController
@RequestMapping("/api/v1/admin/core/announcements")
public class AdminAnnouncementController {

  private final AnnouncementService announcements;

  public AdminAnnouncementController(AnnouncementService announcements) {
    this.announcements = announcements;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<Map<String, Object>>> publish(
      @RequestBody PublishRequest body) {
    AnnouncementService.PublishResult result =
        announcements.publish(
            ActorContextHolder.currentOrNull(), body.title(), body.body(), body.level());
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", result.announcementId());
    view.put("recipients", result.recipients());
    view.put("note", result.note());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(view, TraceIdHolder.current()));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<Map<String, Object>>> list(
      @RequestParam(defaultValue = "20") int limit) {
    List<Map<String, Object>> items = announcements.list(ActorContextHolder.currentOrNull(), limit);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("items", items);
    payload.put("note", "recipients 是发布时刻的启用账号数，不是已读人数；公告正文同时已复制进各账号的通知，" + "因此本表只用于追溯");
    return ResponseEntity.ok(ApiResponse.ok(payload, TraceIdHolder.current()));
  }

  /** 发布请求体。level 取 INFO（默认）或 WARNING。 */
  public record PublishRequest(String title, String body, String level) {}
}
