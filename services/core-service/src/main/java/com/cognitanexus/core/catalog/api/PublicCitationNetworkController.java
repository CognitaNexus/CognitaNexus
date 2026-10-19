package com.cognitanexus.core.catalog.api;

import com.cognitanexus.core.catalog.application.CitationNetworkService;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 引用网络的公开读取。
 *
 * <p>依据：SRS FR06（引用关系查询）。单独开一个端点而不是塞进成果详情，理由是体量与口径：
 * 详情页的首屏不该为了一张图多背几十条引用条目；而引用网络的每个数字都带一句口径说明，
 * 混进详情视图会让「哪些字段是事实、哪些是覆盖率提示」变得分不清。
 *
 * <p>公开资格判定不在本类里做，而由 {@link CitationNetworkService#publicNetwork} 复用详情与引用
 * 导出的同一套判定（合并重定向跳到终点、资格作用在终点实体）。三处各写一份就一定会有一处漏掉。
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicCitationNetworkController {

  private final CitationNetworkService citationNetwork;

  public PublicCitationNetworkController(CitationNetworkService citationNetwork) {
    this.citationNetwork = citationNetwork;
  }

  @GetMapping("/achievements/{achievementId}/citation-network")
  public ResponseEntity<ApiResponse<Map<String, Object>>> network(
      @PathVariable String achievementId) {
    return ResponseEntity.ok(
        ApiResponse.ok(citationNetwork.publicNetwork(achievementId), TraceIdHolder.current()));
  }
}
