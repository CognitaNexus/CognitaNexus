package com.cognitanexus.query.projection.application;

import com.cognitanexus.query.projection.application.ProjectionRepositories.InboxStore;
import com.cognitanexus.query.projection.domain.InboxEventRecord;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inbox 接收的本地事务边界。
 *
 * <p>依据：架构 V0.2 §6.1「Inbox 接收：原始事件、重复键与接收状态」同一事务，§6.3「接收端只在
 * Inbox 已提交后 ACK」。
 *
 * <p>独立成 Bean 而非把它放在 {@code InboxReceiver} 里：同类内部调用不会经过 Spring 代理，
 * {@code @Transactional} 会被静默忽略，事务不开而代码照常运行，直到并发下出现「收到事件却查不到」
 * 才暴露。本仓库已经因为这个问题返工过数次，因此凡是事务方法一律单独成 Bean。
 *
 * <p>本事务只写事件与水位，<b>不</b>做投影计算：投影计算可能触发对 core 的读取，而架构 §6.1
 * 禁止事务内访问外部 HTTP；把两次操作塞进一个事务还会让一次 core 慢响应长时间持有数据库连接。
 */
@Component
class InboxUnitOfWork {

  private final InboxStore inbox;

  InboxUnitOfWork(InboxStore inbox) {
    this.inbox = inbox;
  }

  /**
   * 幂等写入事件并推进接收水位。
   *
   * @return 真正新增的事件行数；重复投递返回 0
   */
  @Transactional
  public int persist(String consumerId, List<InboxEventRecord> events) {
    int inserted = inbox.insertIfAbsent(events);
    long highest = 0;
    for (InboxEventRecord event : events) {
      highest = Math.max(highest, event.catalogRevision());
    }
    if (highest > 0) {
      inbox.observeHighestSeen(consumerId, highest);
    }
    inbox.advanceReceivedRevision(consumerId);
    return inserted;
  }
}
