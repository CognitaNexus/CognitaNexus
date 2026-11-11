package com.cognitanexus.core.governance.api;

import com.cognitanexus.core.governance.application.PublicCorrectionService;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公众纠错入口。
 *
 * <p>依据：FR02／FR16。无需登录——发现错误的往往是站外读者，要求先注册才能说「这条写错了」，
 * 等于把反馈渠道关掉。
 *
 * <p>「无需登录」不等于「无需把关」：内容边界、按来源滑动窗口限流、重复报告合并成一条待办，
 * 三层都在应用层完成（见 {@link PublicCorrectionService}）。接口本身不改任何数据，只登记待办。
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicCorrectionController {

  private final PublicCorrectionService corrections;

  public PublicCorrectionController(PublicCorrectionService corrections) {
    this.corrections = corrections;
  }

  /**
   * 可纠错字段清单。
   *
   * <p>由服务端给出而不是前端硬编码：字段标签只在
   * {@link PublicCorrectionService.Field} 定义一处，两处各写一份早晚会漂移成「界面写作者、待办写参与人」。
   */
  @GetMapping("/correction-fields")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> correctionFields() {
    return ResponseEntity.ok(ApiResponse.ok(corrections.fields(), TraceIdHolder.current()));
  }

  /**
   * 提交一条纠错报告。
   *
   * <p>响应不返回任何治理内部信息（待办总数、其它报告原文），只回执「受理了、这是第几次报告」。
   * 匿名调用方不该从回执里读出平台积压了多少异常。
   */
  @PostMapping("/achievements/{achievementId}/correction-reports")
  public ResponseEntity<ApiResponse<Map<String, Object>>> report(
      @PathVariable String achievementId,
      @RequestBody(required = false) CorrectionReportRequest body,
      HttpServletRequest request) {
    ActorContext actor = ActorContextHolder.currentOrNull();
    PublicCorrectionService.Outcome outcome =
        corrections.submit(
            new PublicCorrectionService.ReportCommand(
                achievementId,
                body == null ? null : body.field(),
                body == null ? null : body.message(),
                body == null ? null : body.contact()),
            clientKeyOf(request),
            actor == null ? null : actor.accountIdValue());

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("accepted", true);
    view.put("reportId", outcome.reportId());
    view.put("reportCount", outcome.reportCount());
    view.put("field", outcome.field());
    view.put("fieldLabel", outcome.fieldLabel());
    view.put("merged", outcome.merged());
    view.put(
        "note",
        outcome.merged()
            ? "同一问题已有未处理的报告，本次已并入（累计 " + outcome.reportCount() + " 次）"
            : "报告已登记为治理待办，核实结果不会自动修改这条记录");
    return ResponseEntity.ok(ApiResponse.ok(view, TraceIdHolder.current()));
  }

  /**
   * 限流用的来源标识。
   *
   * <p>只取 TCP 对端地址，<b>不信任客户端自带的 X-Forwarded-For</b>：那个头任何人都能写，
   * 拿它当限流键等于把限流关掉。
   *
   * <p>部署到反向代理之后必须让容器或网关把对端地址还原（Spring 的
   * {@code server.forward-headers-strategy}），否则所有访客都会看到同一个对端地址、共用一份配额，
   * 于是第五个人提交后整站被锁十分钟。这是上线前必须确认的一项。
   */
  private static String clientKeyOf(HttpServletRequest request) {
    return request == null ? null : request.getRemoteAddr();
  }

  /** 纠错报告请求体。 */
  public record CorrectionReportRequest(String field, String message, String contact) {}
}
