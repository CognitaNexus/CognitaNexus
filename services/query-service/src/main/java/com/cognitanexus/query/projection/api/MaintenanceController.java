package com.cognitanexus.query.projection.api;

import com.cognitanexus.query.platform.web.ApiResponse;
import com.cognitanexus.query.platform.web.TraceIdHolder;
import com.cognitanexus.query.projection.application.RebuildService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投影维护入口。
 *
 * <p>依据：架构 V0.2 §7.3。放在 {@code /internal/v1} 下并因此受服务令牌保护：本服务没有管理员
 * 会话体系（对外只有匿名可读端点），若把重建暴露在公开路径上，任何匿名调用者都能用它消耗核心服务
 * 与数据库资源。运维通过携带服务令牌的调用触发，操作者身份由调用方（core 侧审计）留痕。
 */
@RestController
@RequestMapping("/internal/v1/maintenance")
public class MaintenanceController {

  private final RebuildService rebuildService;

  public MaintenanceController(RebuildService rebuildService) {
    this.rebuildService = rebuildService;
  }

  @PostMapping("/rebuild")
  public ResponseEntity<ApiResponse<Map<String, Object>>> rebuild() {
    return ResponseEntity.ok(ApiResponse.ok(rebuildService.rebuild(), TraceIdHolder.current()));
  }

  @GetMapping("/generations")
  public ResponseEntity<ApiResponse<Map<String, Object>>> generations() {
    return ResponseEntity.ok(ApiResponse.ok(rebuildService.purgeable(), TraceIdHolder.current()));
  }

  @PostMapping("/purge")
  public ResponseEntity<ApiResponse<Map<String, Object>>> purge() {
    return ResponseEntity.ok(ApiResponse.ok(rebuildService.purge(), TraceIdHolder.current()));
  }
}
