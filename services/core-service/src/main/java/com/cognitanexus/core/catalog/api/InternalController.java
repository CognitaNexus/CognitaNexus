package com.cognitanexus.core.catalog.api;

import com.cognitanexus.core.catalog.application.AdminCatalogService;
import com.cognitanexus.core.catalog.application.SourceIntakeCommands.BatchReceipt;
import com.cognitanexus.core.catalog.application.SourceIntakeCommands.SourceBatch;
import com.cognitanexus.core.catalog.application.SourceIntakeService;
import com.cognitanexus.core.platform.event.OutboxStore;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.security.Capabilities;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部服务接口。
 *
 * <p>依据：架构 V0.2 §10.1、§10.2。这些端点禁止外网入口，由 {@code ServiceTokenFilter} 以服务
 * 身份与最小权限把关；不能凭路径前缀自动信任。服务令牌只能访问内部端点，不能冒充人类管理员，
 * 因此这里把调用者构造为 SYSTEM 主体而不是伪造一个管理员账号。
 */
@RestController
@RequestMapping("/internal/v1")
public class InternalController {

  private final SourceIntakeService sourceIntake;
  private final AdminCatalogService adminCatalog;
  private final OutboxStore outbox;

  public InternalController(
      SourceIntakeService sourceIntake, AdminCatalogService adminCatalog, OutboxStore outbox) {
    this.sourceIntake = sourceIntake;
    this.adminCatalog = adminCatalog;
    this.outbox = outbox;
  }

  /**
   * 接收来源批次。
   *
   * <p>返回 202 表示「已持久接收」，<b>不</b>表示治理通过；待核验与异常数量在响应的计数中单列。
   */
  @PostMapping("/source-records")
  public ResponseEntity<ApiResponse<BatchReceipt>> receiveSourceBatch(
      @RequestBody SourceBatch batch) {
    BatchReceipt receipt = sourceIntake.accept(batch, systemActor());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(ApiResponse.ok(receipt, TraceIdHolder.current()));
  }

  /**
   * 权威公开水位。
   *
   * <p>query 每次公开请求都会读取该值：本地已发布水位低于 requiredPublicRevision 时必须返回
   * 503 VIEW_UPDATING，不得继续返回旧缓存、旧计数或旧路径证据（架构 §7.2）。
   */
  @GetMapping("/public-barrier")
  public ResponseEntity<ApiResponse<Map<String, Object>>> publicBarrier() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("catalogRevision", outbox.currentRevision());
    data.put("requiredPublicRevision", outbox.requiredPublicRevision());
    data.put("note", "requiredPublicRevision 由隐私收紧类变更在同事务推进；落后即代表公开资格尚未核实");
    return ResponseEntity.ok(ApiResponse.ok(data, TraceIdHolder.current()));
  }

  /**
   * 投递健康度。
   *
   * <p>三个数字必须分开看，合并成一个「积压」会掩盖完全不同的处置方式：PENDING 会自己好，
   * FAILED 不会自己好，DELIVERED 只是历史。
   */
  @GetMapping("/outbox/deliveries")
  public ResponseEntity<ApiResponse<Map<String, Object>>> outboxDeliveries() {
    List<Map<String, Object>> consumers = new ArrayList<>();
    for (String consumerId : outbox.consumers()) {
      OutboxStore.FailedRange failed = outbox.failedRange(consumerId);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("consumerId", consumerId);
      view.put("pendingCount", outbox.pendingCount(consumerId));
      view.put("oldestPendingRevision", outbox.oldestPendingRevision(consumerId));
      view.put("deadLetterCount", failed.count());
      view.put("deadLetterOldestRevision", failed.oldestRevision());
      view.put("deadLetterNewestRevision", failed.newestRevision());
      consumers.add(view);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("catalogRevision", outbox.currentRevision());
    data.put("consumers", consumers);
    data.put(
        "note", "deadLetter 不为 0 表示该接收者在这段修订上永久缺失变更；投影侧会停滞而不会报错，" + "需调用 requeue-failed 或重建投影。");
    return ResponseEntity.ok(ApiResponse.ok(data, TraceIdHolder.current()));
  }

  /** 死信明细，最多 limit 条。 */
  @GetMapping("/outbox/dead-letters")
  public ResponseEntity<ApiResponse<Map<String, Object>>> deadLetters(
      @RequestParam String consumerId, @RequestParam(defaultValue = "50") int limit) {
    List<OutboxStore.FailedDelivery> rows = outbox.failedDeliveries(consumerId, limit);
    List<Map<String, Object>> items = new ArrayList<>(rows.size());
    for (OutboxStore.FailedDelivery row : rows) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("deliveryId", row.deliveryId());
      item.put("catalogRevision", row.catalogRevision());
      item.put("eventId", row.eventId());
      item.put("eventType", row.eventType());
      item.put("attempts", row.attempts());
      item.put("lastError", row.lastError());
      item.put("lastAttemptAt", row.lastAttemptAt());
      items.add(item);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("consumerId", consumerId);
    data.put("items", items);
    data.put("note", "lastError 通常就是「消费者当时不可达」；确认消费者已恢复后再重投。");
    return ResponseEntity.ok(ApiResponse.ok(data, TraceIdHolder.current()));
  }

  /**
   * 死信重投。
   *
   * <p>把该接收者的死信重新排回投递队列。只动投递状态行，事件正文不动——Outbox 只追加、无删除，
   * 所以重投不会产生任何内容层面的副作用，消费方按 (consumerId, eventId) 去重。
   *
   * <p>不设「自动定时重投」：死信意味着消费者在一段可观测的时间内持续不可达，自动重投会把
   * 「消费者已经坏了」这件事悄悄变成「投递器一直在忙」，故障从告警变成噪音。重投是运维判断，
   * 不是自愈。
   */
  @PostMapping("/outbox/requeue-failed")
  public ResponseEntity<ApiResponse<Map<String, Object>>> requeueFailed(
      @RequestParam String consumerId, @RequestParam(required = false) Long upToRevision) {
    long ceiling = upToRevision != null ? upToRevision : outbox.currentRevision();
    OutboxStore.FailedRange before = outbox.failedRange(consumerId);
    int requeued = outbox.requeueFailed(consumerId, ceiling);
    OutboxStore.FailedRange after = outbox.failedRange(consumerId);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("consumerId", consumerId);
    data.put("upToRevision", ceiling);
    data.put("requeued", requeued);
    data.put("deadLetterBefore", before.count());
    data.put("deadLetterAfter", after.count());
    data.put(
        "note",
        "重投只复位投递状态、不改事件正文；消费方按 (consumerId, eventId) 去重，重复投递安全。" + "重投后仍需确认消费者已可达，否则下一轮会再次判死。");
    return ResponseEntity.ok(ApiResponse.ok(data, TraceIdHolder.current()));
  }

  /**
   * 会话鉴权（供其他服务管理端点转发用户请求时使用）。
   *
   * <p>依据：架构 V0.2 §10.2「ingestion 的管理请求携带用户 Cookie 由 core 鉴权接口验证并校验
   * CSRF 和目标能力」。本服务是身份与权限的唯一事实源，其他服务不自行解析会话、也不缓存判定
   * 结果（架构明确禁止跨请求正向缓存鉴权结论）。
   *
   * <p>CSRF 采用双提交校验：比较 XSRF-TOKEN Cookie 与 X-XSRF-TOKEN 请求头是否一致，且两者
   * 都非空。<b>本端点必须由服务令牌保护</b>（见 ServiceTokenFilter），否则它就成了一个
   * 「把任意 Cookie 拿去换权限结论」的公开预言机。
   *
   * <p>返回体刻意只包含判定结论与实现判定所需的最小身份信息，不含口令哈希、失败计数等
   * 认证内部状态。
   */
  @PostMapping("/session-context")
  public ResponseEntity<ApiResponse<Map<String, Object>>> sessionContext(
      HttpServletRequest request) {
    ActorContext actor = ActorContextHolder.currentOrNull();
    boolean csrfOk = doubleSubmitCsrfMatches(request);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("authenticated", actor != null);
    if (actor == null) {
      data.put("csrfAccepted", false);
      data.put("reason", "会话缺失、已撤销或账号状态已变更");
      // 未认证默认按 401 表达，调用方据此直接回绝，不必再自行推断
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(ApiResponse.failure(ErrorCode.AUTH_REQUIRED, "会话无效或已失效", TraceIdHolder.current()));
    }
    data.put("accountId", actor.accountIdValue());
    data.put("username", actor.username());
    data.put("displayName", actor.displayName());
    data.put("roles", actor.roles().stream().sorted().toList());
    data.put("capabilities", Capabilities.of(actor).stream().sorted().toList());
    data.put("csrfAccepted", csrfOk);
    return ResponseEntity.ok(ApiResponse.ok(data, TraceIdHolder.current()));
  }

  /**
   * 来源运行配置（供 ingestion 同步执行副本并在每批开始前重查启用状态）。
   *
   * <p>依据：架构 V0.2 §4.2、§6.2。core 是来源许可与白名单的权威，ingestion 只持有执行副本；
   * 停用后必须以本端点的当前值为准，不能继续用本地旧副本启动新任务。
   */
  @GetMapping("/source-configs/{sourceCode}")
  public ResponseEntity<ApiResponse<Map<String, Object>>> sourceConfig(
      @PathVariable String sourceCode) {
    Map<String, Object> data = adminCatalog.exportSourceConfig(sourceCode);
    return ResponseEntity.ok(ApiResponse.ok(data, TraceIdHolder.current()));
  }

  /** 公开投影导出，供 query 在新 generation 上重建（架构 §7.3）。 */
  @GetMapping("/exports/public-projection")
  public ResponseEntity<ApiResponse<Map<String, Object>>> exportPublicProjection(
      @RequestParam(defaultValue = "200") int limit, @RequestParam(defaultValue = "0") int offset) {
    int safeLimit = Math.min(Math.max(limit, 1), 1000);
    Map<String, Object> data = adminCatalog.exportPublic(safeLimit, Math.max(offset, 0));
    return ResponseEntity.ok(ApiResponse.ok(data, TraceIdHolder.current()));
  }

  /**
   * 单个成果的公开投影。
   *
   * <p>供 query 在收到合并类事件后重新取回幸存实体的当前投影（架构 §5.4：合并必须留下重定向与
   * 关系前后快照）。成果已不具备公开资格时返回 404 {@code NOT_FOUND}，而不是返回一份过期投影：
   * 让调用方明确知道「现在不该存在这条记录」，从而把它从投影中移除。
   */
  @GetMapping("/exports/public-achievement/{achievementId}")
  public ResponseEntity<ApiResponse<Map<String, Object>>> exportPublicAchievement(
      @PathVariable String achievementId) {
    return adminCatalog
        .exportPublicOne(achievementId)
        .map(view -> ResponseEntity.ok(ApiResponse.ok(view, TraceIdHolder.current())))
        .orElseGet(
            () ->
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(
                        ApiResponse.failure(
                            ErrorCode.NOT_FOUND, "该成果当前不具备公开资格", TraceIdHolder.current())));
  }

  /** 内部调用的系统主体：审计中呈现为 SYSTEM，不代表任何人类管理员。 */
  private ActorContext systemActor() {
    return new ActorContext(
        new AccountId("system"), RoleNames.SYSTEM, RoleNames.SYSTEM, Set.of(RoleNames.SYSTEM), 0L);
  }

  /**
   * 双提交 CSRF 校验。
   *
   * <p>与 {@code CookieCsrfTokenRepository} 同一思路：令牌同时存在于 Cookie 与请求头，两者一致
   * 才认为请求来自同源页面。这里手写比较而不复用框架校验器，是因为本端点不在浏览器会话中，
   * 没有框架的令牌上下文，需要的只是一次等值且非空的比较。
   */
  private static boolean doubleSubmitCsrfMatches(HttpServletRequest request) {
    String headerToken = request.getHeader("X-XSRF-TOKEN");
    if (headerToken == null || headerToken.isBlank()) {
      return false;
    }
    String cookieToken = null;
    jakarta.servlet.http.Cookie[] cookies = request.getCookies();
    if (cookies != null) {
      for (jakarta.servlet.http.Cookie cookie : cookies) {
        if ("XSRF-TOKEN".equals(cookie.getName())) {
          cookieToken = cookie.getValue();
          break;
        }
      }
    }
    return cookieToken != null && !cookieToken.isBlank() && cookieToken.equals(headerToken);
  }
}
