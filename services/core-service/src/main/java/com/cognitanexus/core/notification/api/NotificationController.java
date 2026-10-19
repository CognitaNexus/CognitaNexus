package com.cognitanexus.core.notification.api;

import com.cognitanexus.core.notification.application.NotificationService;
import com.cognitanexus.core.notification.domain.Notification;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内通知（ADR-003）。所有端点都以当前会话账号为准，不接受 accountId 参数。
 *
 * <p>V20 之后多了三件事：按类型筛选、全部已读、从本人列表移除。移除是归档不是删除——
 * 数据还在库里，只是不再出现在这个账号的列表与未读数里。接口措辞统一用「移除」，
 * 不用「删除」，避免调用方以为数据被清掉了。
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

  private final NotificationService notifications;

  public NotificationController(NotificationService notifications) {
    this.notifications = notifications;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<Map<String, Object>>> list(
      @RequestParam(defaultValue = "false") boolean unreadOnly,
      @RequestParam(required = false) String kind,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    ActorContext actor = ActorContextHolder.currentOrNull();
    List<Notification> items = notifications.list(actor, unreadOnly, kind, limit, offset);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("items", items.stream().map(this::view).toList());
    view.put("unreadOnly", unreadOnly);
    view.put("kind", kind == null ? "" : kind);
    view.put("limit", limit);
    view.put("offset", offset);
    return ResponseEntity.ok(ApiResponse.ok(view, TraceIdHolder.current()));
  }

  @GetMapping("/unread-count")
  public ResponseEntity<ApiResponse<Map<String, Object>>> unreadCount() {
    ActorContext actor = ActorContextHolder.currentOrNull();
    return ResponseEntity.ok(
        ApiResponse.ok(
            Map.of("unreadCount", notifications.unreadCount(actor)), TraceIdHolder.current()));
  }

  /** 本人列表中实际出现过的类型。前端据此渲染筛选下拉，不用硬编码一份可能与后端脱节的枚举。 */
  @GetMapping("/kinds")
  public ResponseEntity<ApiResponse<Map<String, Object>>> kinds() {
    ActorContext actor = ActorContextHolder.currentOrNull();
    return ResponseEntity.ok(
        ApiResponse.ok(Map.of("kinds", notifications.kinds(actor)), TraceIdHolder.current()));
  }

  @PostMapping("/read")
  public ResponseEntity<ApiResponse<Map<String, Object>>> markRead(
      @RequestBody MarkReadRequest body) {
    ActorContext actor = ActorContextHolder.currentOrNull();
    int affected = notifications.markRead(actor, body.ids());
    return ResponseEntity.ok(
        ApiResponse.ok(Map.of("markedRead", affected), TraceIdHolder.current()));
  }

  /** 全部已读。只影响本人的未读行，返回受影响条数。 */
  @PostMapping("/read-all")
  public ResponseEntity<ApiResponse<Map<String, Object>>> markAllRead() {
    ActorContext actor = ActorContextHolder.currentOrNull();
    int affected = notifications.markAllRead(actor);
    return ResponseEntity.ok(
        ApiResponse.ok(Map.of("markedRead", affected), TraceIdHolder.current()));
  }

  /**
   * 从本人列表移除（归档）。
   *
   * <p>允许移除未读通知：用户点了移除就是明确处置，替他把未读消息扣住反而是越界。
   * 前端在未读行上会先提示一次。
   */
  @PostMapping("/archive")
  public ResponseEntity<ApiResponse<Map<String, Object>>> archive(
      @RequestBody MarkReadRequest body) {
    ActorContext actor = ActorContextHolder.currentOrNull();
    int affected = notifications.archive(actor, body.ids());
    return ResponseEntity.ok(
        ApiResponse.ok(
            Map.of("archived", affected, "note", "只是从你的列表移除，数据仍保留供审计，无法恢复显示"),
            TraceIdHolder.current()));
  }

  /** 清空已读：只移除自己已读的通知，未读一条都不动。 */
  @PostMapping("/archive-read")
  public ResponseEntity<ApiResponse<Map<String, Object>>> archiveAllRead() {
    ActorContext actor = ActorContextHolder.currentOrNull();
    int affected = notifications.archiveAllRead(actor);
    return ResponseEntity.ok(
        ApiResponse.ok(
            Map.of("archived", affected, "note", "只移除已读通知；未读的仍留在列表里"), TraceIdHolder.current()));
  }

  // 通知内容是服务端写死的措辞，不含用户提交的自由文本渲染面（标题正文均纯文本展示）。
  private Map<String, Object> view(Notification notification) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", notification.id());
    view.put("kind", notification.kind());
    view.put("title", notification.title());
    view.put("body", notification.body());
    // refType / refId 允许为 null（撤权类通知没有单一跳转目标）；non_null 序列化会丢掉这两个字段，
    // 前端按可选处理（与成果年份 yearKnown 的处理同理）。
    view.put("refType", notification.refType());
    view.put("refId", notification.refId());
    view.put("createdAt", notification.createdAt());
    view.put("read", notification.readAt() != null);
    return view;
  }

  record MarkReadRequest(List<String> ids) {}
}
