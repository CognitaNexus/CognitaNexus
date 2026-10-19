package com.cognitanexus.core.catalog.api;

import com.cognitanexus.core.catalog.application.CitationNetworkService;
import com.cognitanexus.core.catalog.application.CitationNetworkService.ObservationReceipt;
import com.cognitanexus.core.catalog.application.CitationNetworkService.ResolveResult;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.security.Capabilities;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 引用网络的管理端：看覆盖率、看待补规模、手动触发重新解析。
 *
 * <p>依据：架构 V0.2 §10.1。能力校验用 {@code SOURCE_MANAGE}：补参考文献是采集动作，
 * 会消耗来源配额，与「管理来源」是同一类权限；审核类角色没有这个能力。
 */
@RestController
@RequestMapping("/api/v1/admin/core/citations")
public class AdminCitationController {

  private final CitationNetworkService citationNetwork;

  public AdminCitationController(CitationNetworkService citationNetwork) {
    this.citationNetwork = citationNetwork;
  }

  /**
   * 引用网络覆盖率与待补规模。
   *
   * <p>三个数字必须一起看：已采集篇数决定「边」的上限，可见论文总数决定覆盖率，
   * 待补篇数决定还要跑多久。只给一个百分比看不出「是数据少还是没去采」。
   */
  @GetMapping("/stats")
  public ResponseEntity<ApiResponse<Map<String, Object>>> stats(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String topicId) {
    Capabilities.require(ActorContextHolder.currentOrNull(), RoleNames.CAP_SOURCE_MANAGE);
    return ResponseEntity.ok(
        ApiResponse.ok(citationNetwork.stats(search, topicId), TraceIdHolder.current()));
  }

  /**
   * 重新解析外部引用。
   *
   * <p>新进的成果登记了作品 ID 之后，原先「解析不到」的引用可以升级为站内边。这一步只读注册表，
   * 不访问外网、不消耗来源配额，因此随时可以跑。
   */
  @PostMapping("/resolve")
  public ResponseEntity<ApiResponse<ResolveResult>> resolve(
      @RequestParam(defaultValue = "5000") int limit) {
    var actor = ActorContextHolder.currentOrNull();
    Capabilities.require(actor, RoleNames.CAP_SOURCE_MANAGE);
    return ResponseEntity.ok(
        ApiResponse.ok(citationNetwork.resolvePending(limit, actor), TraceIdHolder.current()));
  }

  /**
   * 待补参考文献的成果清单。
   *
   * <p>采集侧按这份清单去来源批量取引用列表。管理端也用它可以回答「还有哪些没补」。
   */
  @GetMapping("/pending")
  public ResponseEntity<ApiResponse<CitationNetworkService.PendingPage>> pending(
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String topicId) {
    Capabilities.require(ActorContextHolder.currentOrNull(), RoleNames.CAP_SOURCE_MANAGE);
    return ResponseEntity.ok(
        ApiResponse.ok(
            citationNetwork.pendingWorks(limit, search, topicId), TraceIdHolder.current()));
  }

  /**
   * 内部投递口的管理端镜像。
   *
   * <p>正常路径是采集服务用服务令牌调 {@code /internal/v1/citation-observations}；这里的镜像只给
   * 运维排障用（例如手工补一条观测验证解析链路），同样要求 SOURCE_MANAGE，避免它成为绕过
   * 服务令牌往引用网络里写内容的入口。
   */
  @PostMapping("/observations")
  public ResponseEntity<ApiResponse<ObservationReceipt>> observations(
      @RequestBody CitationNetworkService.ObservationBatch batch) {
    var actor = ActorContextHolder.currentOrNull();
    Capabilities.require(actor, RoleNames.CAP_SOURCE_MANAGE);
    return ResponseEntity.ok(
        ApiResponse.ok(
            citationNetwork.recordObservations(batch.observations(), actor),
            TraceIdHolder.current()));
  }
}
