package com.cognitanexus.query.consistency;

import com.cognitanexus.query.corelink.CoreInternalClient;
import com.cognitanexus.query.platform.web.ApiResponse;
import com.cognitanexus.query.platform.web.TraceIdHolder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开一致性观测端点。
 *
 * <p>依据：架构 V0.2 §7.4「同时记录入队、接收、开始处理、发布时刻，区分网络延迟与计算积压」、
 * §12.4（必备指标）。
 *
 * <p>这个端点本身就是一项交付物：它让「审核生效后 60 秒内可见」这句目标可以被逐次核对，而不是
 * 只能看到最终的对与错。它不做拒绝判断，也不改变任何状态，因此可以匿名访问；里面的数字是服务
 * 自身的进度，不含任何未公开内容。
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicConsistencyController {

  private final PublicBarrierGuard guard;
  private final CoreInternalClient core;

  public PublicConsistencyController(PublicBarrierGuard guard, CoreInternalClient core) {
    this.guard = guard;
    this.core = core;
  }

  @GetMapping("/consistency")
  public ResponseEntity<ApiResponse<Map<String, Object>>> consistency() {
    Map<String, Object> data = guard.describe();
    Map<String, Object> coreTarget = core.describeTarget();
    data.put("coreTarget", coreTarget);
    Map<String, Object> note = new LinkedHashMap<>();
    note.put(
        "revisionSemantics",
        "receivedRevision 是已持久接收的连续修订；publishedRevision 是投影已发布的连续修订；"
            + "requiredPublicRevision 是隐私收紧后必须达到的权威水位。");
    note.put(
        "servingRule",
        "publishedRevision < requiredPublicRevision 时全部公开查询返回 503 VIEW_UPDATING；"
            + "core 不可达时同样拒绝，不假定已核实。");
    note.put("measuredSeparately", "本端点只呈现时刻与水位，不宣称已满足 60 秒传播目标；该目标的验证需要逐次记录提交时刻与首次可见时刻，属性能验证环节。");
    data.put("note", note);
    return ResponseEntity.ok(ApiResponse.ok(data, TraceIdHolder.current()));
  }
}
