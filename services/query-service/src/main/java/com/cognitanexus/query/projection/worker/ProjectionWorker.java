package com.cognitanexus.query.projection.worker;

import com.cognitanexus.query.projection.application.ProjectionProperties;
import com.cognitanexus.query.projection.application.ProjectionPublisher;
import com.cognitanexus.query.projection.application.ProjectionRepositories.InboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 投影追赶调度。
 *
 * <p>依据：架构 V0.2 §6.4「查询计算中崩溃：已收事件保留；未发布批次回滚/重算，不推进发布版本」。
 *
 * <p>存在的必要性：投递回调里已经尝试过一次发布，但两类情况必须在回调之外重试——
 * (1) 回调期间 core 不可达，合并类事件无法取得权威投影；(2) 进程在接收与发布之间崩溃，事件已落库
 * 而投影未应用。没有这个周期，这两种情况都会永久停留在「已接收未发布」。
 *
 * <p>异常就地吞掉：一次失败不能停掉调度线程，否则下一次连「把失败记下来」都做不到。
 */
@Component
public class ProjectionWorker {

  private static final Logger log = LoggerFactory.getLogger(ProjectionWorker.class);

  private final InboxStore inbox;
  private final ProjectionPublisher publisher;
  private final ProjectionProperties properties;

  public ProjectionWorker(
      InboxStore inbox, ProjectionPublisher publisher, ProjectionProperties properties) {
    this.inbox = inbox;
    this.publisher = publisher;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${cn.projection.worker-interval-ms:2000}",
      initialDelayString = "2000")
  public void catchUp() {
    if (!properties.workerEnabled()) {
      return;
    }
    String consumerId = properties.consumerId();
    try {
      // 先把已超退避窗口的失败行重新入队，再尝试发布
      int rearmed = inbox.rearmFailed(consumerId, REARM_ATTEMPT_LIMIT);
      if (rearmed > 0) {
        log.warn("重新入队失败事件 consumer={} count={}", consumerId, rearmed);
      }
      ProjectionPublisher.PublishOutcome outcome =
          publisher.publishPending(consumerId, properties.publishBatchPerBatch());
      if (outcome.applied() > 0 || outcome.isolated() > 0) {
        log.info(
            "投影追赶 consumer={} applied={} isolated={} publishedRevision={}",
            consumerId,
            outcome.applied(),
            outcome.isolated(),
            outcome.publishedRevision());
      }
    } catch (RuntimeException ex) {
      log.warn("投影追赶周期异常 consumer={} message={}", consumerId, ex.toString());
    }
  }

  /** 重新入队上限，与 InboxStoreAdapter 中的应用失败上限保持一致。 */
  private static final int REARM_ATTEMPT_LIMIT = 6;
}
