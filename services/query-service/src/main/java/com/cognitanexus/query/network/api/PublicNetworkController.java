package com.cognitanexus.query.network.api;

import com.cognitanexus.query.network.application.NetworkService;
import com.cognitanexus.query.platform.web.ApiResponse;
import com.cognitanexus.query.platform.web.TraceIdHolder;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开合作网络端点。
 *
 * <p>依据：架构 V0.2 §10.1，{@code /api/v1/public/network/*} 归 query 所有，无需登录。
 *
 * <p>路径与子图范围上限由服务端强制，不接受客户端放宽：子图规模直接决定内存与计算量，
 * 让调用方指定上限等于把服务做成可被单次请求拖垮的形态。
 */
@RestController
@RequestMapping("/api/v1/public/network")
public class PublicNetworkController {

  private final NetworkService networkService;

  public PublicNetworkController(NetworkService networkService) {
    this.networkService = networkService;
  }

  @GetMapping("/graph")
  public ResponseEntity<ApiResponse<Map<String, Object>>> graph(
      @RequestParam String scholarId,
      @RequestParam(defaultValue = "1") int depth,
      @RequestParam(required = false) Integer maxNodes,
      @RequestParam(required = false, defaultValue = "REAL") String dataNature,
      @RequestParam(required = false) String topic,
      @RequestParam(required = false) String organization) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            networkService.neighborhood(
                scholarId.trim(),
                depth,
                maxNodes == null ? 0 : maxNodes,
                dataNature,
                blankToNull(topic),
                blankToNull(organization)),
            TraceIdHolder.current()));
  }

  @GetMapping("/path")
  public ResponseEntity<ApiResponse<Map<String, Object>>> path(
      @RequestParam String from,
      @RequestParam String to,
      @RequestParam(required = false, defaultValue = "REAL") String dataNature,
      @RequestParam(required = false) String topic,
      @RequestParam(required = false) String organization) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            networkService.shortestPath(
                from.trim(), to.trim(), dataNature, blankToNull(topic), blankToNull(organization)),
            TraceIdHolder.current()));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
