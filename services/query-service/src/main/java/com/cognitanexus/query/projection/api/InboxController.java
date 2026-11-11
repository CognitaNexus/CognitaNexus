package com.cognitanexus.query.projection.api;

import com.cognitanexus.query.platform.web.ApiResponse;
import com.cognitanexus.query.platform.web.TraceIdHolder;
import com.cognitanexus.query.projection.api.InboxRequests.BatchReceipt;
import com.cognitanexus.query.projection.api.InboxRequests.BatchRequest;
import com.cognitanexus.query.projection.application.InboxReceiver;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件接收端点。
 *
 * <p>依据：架构 V0.2 §6.3、§10.1（{@code /internal/v1/*} 归对应服务所有，禁止外网入口）。
 *
 * <p>由 {@code InternalServiceTokenFilter} 以服务令牌把关：本端点会写投影与发布水位，任何能访问
 * 该端口并成功投递伪造事件的进程都能往公开检索里塞入成果，或声明「已发布到修订 N」而实际没有
 * 投影。它是本服务权限最高的写入口。
 *
 * <p>返回 200 才代表已持久接收（core 收到 2xx 即 ACK）。投影尚未追平时仍返回 200：事件已经落库，
 * 剩下的计算由本服务的调度周期继续完成，而返回失败会让 core 重投同一批事件（幂等，不会重复执行）。
 */
@RestController
@RequestMapping("/internal/v1/inbox")
public class InboxController {

  private static final Logger log = LoggerFactory.getLogger(InboxController.class);

  private final InboxReceiver receiver;

  public InboxController(InboxReceiver receiver) {
    this.receiver = receiver;
  }

  @PostMapping("/batches")
  public ResponseEntity<ApiResponse<BatchReceipt>> receive(
      @Valid @RequestBody BatchRequest request) {
    BatchReceipt receipt = receiver.receive(request.consumerId(), request.events());
    log.debug(
        "事件批次已接收 consumer={} received={} duplicated={} applied={}",
        request.consumerId(),
        receipt.received(),
        receipt.duplicated(),
        receipt.applied());
    return ResponseEntity.ok(ApiResponse.ok(receipt, TraceIdHolder.current()));
  }
}
