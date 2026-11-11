package com.cognitanexus.core.identity.api;

import com.cognitanexus.core.identity.application.AccountClosureService;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 身份域的内部维护端点。
 *
 * <p>为什么需要「手工触发一次」：冷静期扫描默认每小时一轮，运维在改了配置或处理完积压后
 * 要确认「现在到底有没有该执行的」，等一小时不是办法。这里与定时任务调用同一个
 * {@link AccountClosureService#executeDue(int)}，不存在「手工路径」与「自动路径」两套逻辑，
 * 也不允许通过这个端点跳过冷静期——它只能执行已经到期的申请，提前执行仍然由 SQL 拒绝。
 *
 * <p>安全：挂在 {@code /internal/v1/**} 下，由服务令牌保护，外网不可达；不受理用户身份。
 */
@RestController
@RequestMapping("/internal/v1/maintenance")
public class InternalMaintenanceController {

  private final AccountClosureService closures;

  public InternalMaintenanceController(AccountClosureService closures) {
    this.closures = closures;
  }

  @PostMapping("/closure-sweep")
  public ResponseEntity<ApiResponse<Map<String, Object>>> closureSweep(
      @RequestParam(defaultValue = "50") int limit) {
    int executed = closures.executeDue(limit);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("executed", executed);
    data.put("limit", limit);
    data.put("note", "只执行冷静期已届满的申请；未到期的申请不受影响，提前执行仍由 SQL 条件拒绝。" + "定时任务每小时跑同一段逻辑，本端点用于运维手工确认。");
    return ResponseEntity.ok(ApiResponse.ok(data, TraceIdHolder.current()));
  }
}
