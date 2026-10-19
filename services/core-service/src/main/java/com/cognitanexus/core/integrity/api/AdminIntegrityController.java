package com.cognitanexus.core.integrity.api;

import com.cognitanexus.core.integrity.application.IntegrityNoticeService;
import com.cognitanexus.core.integrity.application.IntegrityNoticeViews;
import com.cognitanexus.core.integrity.domain.IntegrityNotice;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学术诚信声明的管理端接口。
 *
 * <p>依据：架构 V0.2 §10.1。能力校验在应用层完成，URL 前缀不构成授权依据——把「只有审核员能用」
 * 写在路径上，等于让任何人换一个路径就能用。
 *
 * <p>这里没有 DELETE。撤销走 {@code POST .../revoke} 并强制填理由：撤稿与「撤销撤稿」都是学术记录
 * 的一部分，删除操作会让这段历史凭空消失，而这类消失事后无法从数据里看出来。
 */
@RestController
@RequestMapping("/api/v1/admin/core")
public class AdminIntegrityController {

  private final IntegrityNoticeService notices;

  public AdminIntegrityController(IntegrityNoticeService notices) {
    this.notices = notices;
  }

  /** 最近声明。默认只返回生效中的，{@code activeOnly=false} 可看到撤销历史。 */
  @GetMapping("/integrity-notices")
  public ResponseEntity<ApiResponse<Map<String, Object>>> recent(
      @RequestParam(name = "limit", defaultValue = "50") int limit,
      @RequestParam(name = "activeOnly", defaultValue = "true") boolean activeOnly) {
    List<Map<String, Object>> items =
        notices.recent(ActorContextHolder.currentOrNull(), limit, activeOnly).stream()
            .map(AdminIntegrityController::viewOf)
            .toList();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("total", items.size());
    data.put("limit", limit);
    data.put("activeOnly", activeOnly);
    return ResponseEntity.ok(ApiResponse.ok(data, TraceIdHolder.current()));
  }

  /** 某条成果的全部声明（含撤销历史），供治理台在成果卡片上展开。 */
  @GetMapping("/achievements/{achievementId}/integrity-notices")
  public ResponseEntity<ApiResponse<Map<String, Object>>> ofAchievement(
      @PathVariable String achievementId) {
    var actor = ActorContextHolder.currentOrNull();
    List<IntegrityNotice> found = notices.noticesOf(actor, achievementId);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("summary", IntegrityNoticeViews.of(notices.summaryOf(actor, achievementId)));
    data.put("items", found.stream().map(AdminIntegrityController::viewOf).toList());
    return ResponseEntity.ok(ApiResponse.ok(data, TraceIdHolder.current()));
  }

  /**
   * 登记一条声明。
   *
   * <p>请求体用字符串接收类型与日期：让 {@code noticeType} 由领域枚举严格解析、日期由服务解析，
   * 可以在报错时说清「允许哪些值」，而不是抛一个 Spring 的绑定异常给前端看。
   */
  @PostMapping("/achievements/{achievementId}/integrity-notices")
  public ResponseEntity<ApiResponse<Map<String, Object>>> register(
      @PathVariable String achievementId, @RequestBody(required = false) RegisterRequest body) {
    // 请求体可以完全缺失：此时命令里全是 null，由服务层逐项给出「哪个字段不能为空」，
    // 而不是让控制器抛一个空指针或 500。
    IntegrityNoticeService.RegisterCommand command =
        body == null
            ? new IntegrityNoticeService.RegisterCommand(null, null, null, null, null)
            : new IntegrityNoticeService.RegisterCommand(
                body.noticeType(),
                body.declaredBy(),
                body.reason(),
                body.sourceUrl(),
                parseDate(body.announcedOn()));
    IntegrityNotice notice =
        notices.register(ActorContextHolder.currentOrNull(), achievementId, command);
    return ResponseEntity.ok(ApiResponse.ok(viewOf(notice), TraceIdHolder.current()));
  }

  /** 撤销一条声明。理由必填且会写进审计，撤销后汇总状态按剩余生效声明重算。 */
  @PostMapping("/integrity-notices/{noticeId}/revoke")
  public ResponseEntity<ApiResponse<Map<String, Object>>> revoke(
      @PathVariable String noticeId, @RequestBody(required = false) RevokeRequest body) {
    String reason = body == null ? null : body.reason();
    IntegrityNotice revoked = notices.revoke(ActorContextHolder.currentOrNull(), noticeId, reason);
    return ResponseEntity.ok(ApiResponse.ok(viewOf(revoked), TraceIdHolder.current()));
  }

  private static Map<String, Object> viewOf(IntegrityNotice notice) {
    Map<String, Object> view = new LinkedHashMap<>(IntegrityNoticeViews.of(notice));
    // 治理台需要知道这条声明挂在哪条成果上；公开视图里不需要（它就在成果详情里面）。
    view.put("achievementId", notice.achievementId());
    view.put("recordedBy", notice.recordedBy()); // 仅管理端可见：对外视图刻意不含登记账号（见 IntegrityNoticeViews）
    return view;
  }

  /**
   * 解析声明日期。
   *
   * <p>用字符串而不是 {@code LocalDate} 直接绑定：绑定失败时 Spring 抛的是类型转换异常，报文里
   * 只有「Failed to convert」，读者无法得知期望格式。这里显式解析并给出 ISO 日期示例。
   */
  private static LocalDate parseDate(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(raw.trim());
    } catch (RuntimeException e) {
      throw com.cognitanexus.sharedkernel.BusinessException.invalidArgument(
          "声明日期格式应为 yyyy-MM-dd，收到：" + raw);
    }
  }

  public record RegisterRequest(
      String noticeType, String declaredBy, String reason, String sourceUrl, String announcedOn) {}

  public record RevokeRequest(String reason) {}
}
