package com.cognitanexus.query.projection.application;

import com.cognitanexus.query.projection.application.ProjectionRepositories.InboxStore;
import com.cognitanexus.query.projection.application.ProjectionRepositories.MaintenanceStore;
import com.cognitanexus.query.projection.domain.ConsumerProgress;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 重建期间写入非活动代的事务边界。
 *
 * <p>依据：架构 V0.2 §7.3。与增量发布的关键区别是：这里<b>不推进发布水位</b>。重建期间活动代仍
 * 是旧代，公开查询读的也是旧代；若在这里顺手推进水位，就会出现「水位说已发布到修订 N，而查询
 * 读到的旧代内容只到修订 M」的错配，屏障校验将据此放行尚未核实的公开内容。
 *
 * <p>切换是单独的事务，并且同样对进度行加锁：锁与增量应用的锁是同一把，因此切换点一定落在两条
 * 事件应用之间，不会与正在进行的应用交错。
 */
@Component
class RebuildUnitOfWork {

  private final InboxStore inbox;
  private final MaintenanceStore maintenance;
  private final ProjectionApplier applier;

  RebuildUnitOfWork(InboxStore inbox, MaintenanceStore maintenance, ProjectionApplier applier) {
    this.inbox = inbox;
    this.maintenance = maintenance;
    this.applier = applier;
  }

  record BatchResult(int applied, String note) {}

  /**
   * 把一批准备就绪的事件应用到指定 generation。
   *
   * <p>遇到需要隔离的事件时抛 {@link RebuildAborted}：异常会让本批事务回滚，新代不会留下
   * 「回放到一半」的痕迹。若改为返回一个失败标记，半批写入就会提交，之后重建又是在这个基础上
   * 继续，最终「已回放到修订 X」这句话不再成立。
   */
  @Transactional
  public BatchResult applyBatch(
      String generation, List<ProjectionPreparer.Prepared> preparedEvents) {
    int applied = 0;
    for (ProjectionPreparer.Prepared prepared : preparedEvents) {
      ProjectionApplier.Outcome outcome =
          applier.apply(generation, prepared.event(), prepared.survivorProjection());
      if (outcome.shouldIsolate()) {
        throw new RebuildAborted("重建回放被隔离：" + outcome.note());
      }
      applied++;
    }
    return new BatchResult(applied, "本批已应用到 generation " + generation);
  }

  /**
   * 切换活动代并发布水位。
   *
   * <p>三件事在同一事务内：标记新代 READY、把活动代与发布水位一起写、把旧代退役。分三次提交的话，
   * 中间任何一次崩溃都会留下「活动代指向一个还没 READY 的代」或「水位与内容不匹配」的状态，
   * 而这两种状态都无法从外部看出来。
   */
  @Transactional
  public ConsumerProgress switchTo(String consumerId, String generation, long publishedRevision) {
    ConsumerProgress locked = inbox.lockProgress(consumerId);
    maintenance.markGenerationReady(generation);
    maintenance.switchGeneration(consumerId, generation, publishedRevision);
    if (!locked.activeGeneration().equals(generation)) {
      maintenance.retireGeneration(locked.activeGeneration());
    }
    return inbox.readProgress(consumerId);
  }
}
