package com.cognitanexus.core.platform.event;

import com.cognitanexus.core.platform.security.InternalServiceTokenProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Outbox 投递器。
 *
 * <p>依据：架构 V0.2 §6.3。语义为至少一次，不宣称恰好一次：
 *
 * <ol>
 *   <li>在短事务内领取一批待投递事件并延长租约，事务结束；
 *   <li>在事务外按修订顺序 HTTP 发送一个批次；
 *   <li>收到 ACK 才把该接收者标记为已投递；ACK 丢失会重投，由接收端 Inbox 唯一键消除重复业务效果。
 * </ol>
 *
 * <p>网络投递成功不等于查询视图完成更新，因此这里只记录「已接收」；查询侧的 receivedRevision 与
 * publishedRevision 是另一组独立水位，由接收端维护。
 */
@Component
public class OutboxDispatcher {

  private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

  private final OutboxStore outboxStore;
  private final OutboxProperties properties;
  private final InternalServiceTokenProperties tokenProperties;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  public OutboxDispatcher(
      OutboxStore outboxStore,
      OutboxProperties properties,
      InternalServiceTokenProperties tokenProperties,
      ObjectMapper objectMapper) {
    this.outboxStore = outboxStore;
    this.properties = properties;
    this.tokenProperties = tokenProperties;
    this.objectMapper = objectMapper;
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.getConnectTimeout())
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(properties.getRequestTimeout());
    this.restClient = RestClient.builder().requestFactory(factory).build();
  }

  @Scheduled(fixedDelayString = "${cn.outbox.dispatch-interval-ms:800}")
  public void dispatch() {
    if (!properties.isDispatcherEnabled()) {
      return;
    }
    for (Map.Entry<String, String> entry : properties.getConsumerEndpoints().entrySet()) {
      try {
        dispatchTo(entry.getKey(), entry.getValue());
      } catch (RuntimeException ex) {
        log.warn("投递周期异常 consumer={} message={}", entry.getKey(), ex.toString());
      }
    }
  }

  private void dispatchTo(String consumerId, String endpoint) {
    List<OutboxStore.PendingDelivery> batch;
    try {
      batch = outboxStore.claimPending(consumerId, properties.getBatchSize(), 1);
    } catch (RuntimeException ex) {
      log.warn("领取待投递事件失败 consumer={} message={}", consumerId, ex.toString());
      return;
    }
    if (batch.isEmpty()) {
      return;
    }
    String error = send(consumerId, endpoint, batch);
    if (error == null) {
      batch.forEach(delivery -> outboxStore.markDelivered(delivery.deliveryId()));
      log.debug("事件批次投递完成 consumer={} count={}", consumerId, batch.size());
      return;
    }
    for (OutboxStore.PendingDelivery delivery : batch) {
      int backoff = backoffSeconds(delivery.attempts());
      outboxStore.markFailed(delivery.deliveryId(), error, backoff, properties.getMaxAttempts());
      if (delivery.attempts() >= properties.getMaxAttempts()) {
        log.error(
            "事件投递超过重试上限，转 FAILED 等待人工重放 consumer={} eventId={} revision={} error={}",
            consumerId,
            delivery.eventId(),
            delivery.catalogRevision(),
            error);
      }
    }
  }

  /** 返回 null 表示接收端已 ACK。 */
  private String send(String consumerId, String endpoint, List<OutboxStore.PendingDelivery> batch) {
    try {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("consumerId", consumerId);
      List<Map<String, Object>> events = new ArrayList<>(batch.size());
      for (OutboxStore.PendingDelivery delivery : batch) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", delivery.eventId());
        event.put("eventType", delivery.eventType());
        event.put("schemaVersion", delivery.schemaVersion());
        event.put("aggregateType", delivery.aggregateType());
        event.put("aggregateId", delivery.aggregateId());
        event.put("aggregateVersion", delivery.aggregateVersion());
        event.put("catalogRevision", delivery.catalogRevision());
        event.put(
            "occurredAt", delivery.occurredAt() == null ? null : delivery.occurredAt().toString());
        event.put("traceId", delivery.traceId());
        event.put("payload", objectMapper.readValue(delivery.payloadJson(), Map.class));
        events.add(event);
      }
      body.put("events", events);

      restClient
          .post()
          .uri(endpoint)
          .contentType(MediaType.APPLICATION_JSON)
          .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
          .header("X-Service-Name", "core")
          .header("X-Service-Token", tokenProperties.serviceTokens().getOrDefault(consumerId, ""))
          .body(objectMapper.writeValueAsString(body))
          .retrieve()
          .toBodilessEntity();
      return null;
    } catch (Exception ex) {
      return describe(ex);
    }
  }

  private String describe(Throwable ex) {
    Throwable root = ex;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String message = root.getMessage();
    String text = root.getClass().getSimpleName() + ": " + (message == null ? "" : message);
    return text.length() > 400 ? text.substring(0, 400) : text;
  }

  private int backoffSeconds(int attempt) {
    long base = 1L << Math.min(Math.max(attempt - 1, 0), 6);
    long capped = Math.min(base, Math.max(1, properties.getMaxBackoff().toSeconds()));
    int jitter =
        ThreadLocalRandom.current().nextInt(0, Math.max(1, properties.getMaxJitterSeconds()) + 1);
    return (int) Math.min(capped + jitter, 3600);
  }
}
