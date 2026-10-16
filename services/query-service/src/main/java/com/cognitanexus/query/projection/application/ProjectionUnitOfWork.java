package com.cognitanexus.query.projection.application;

import com.cognitanexus.query.projection.application.ProjectionRepositories.InboxStore;
import com.cognitanexus.query.projection.domain.ConsumerProgress;
import com.cognitanexus.query.projection.domain.InboxEventRecord;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单条事件的投影应用事务。
 *
 * <p>依据：架构 V0.2 §6.1「查询发布：受影响投影/统计/边证据、处理进度、发布版本」同一事务，
 * §7.1「最后原子推进 publishedRevision」。
 *
 * <p>关键设计：
 *
 * <ol>
 *   <li>先对 {@code qry_consumer_state} 行加锁。这一行同时被「切换 generation」写，因此投影应用与
 *       重建切换天然串行化。不加锁的话，一次并发重建切换可以让应用事务继续往已被切换掉的旧代
 *       写入，而那批写入永远不会被任何查询读到——数据看起来存在，功能却缺失，极难定位。
 *   <li>只应用 {@code revision == publishedRevision + 1} 的事件。这不是优化，而是「不跳过毒事件
 *       伪造新水位」的实现：前一条失败或隔离时，后面的修订必须等它，而不是踩着它前进。
 *   <li>应用与水位推进在同一事务内。若分开提交，崩溃后会出现「投影已改但水位未动」或反之，
 *       前者导致重复应用，后者导致内容缺失却宣称已发布。
 * </ol>
 */
@Component
class ProjectionUnitOfWork {

  private final InboxStore inbox;
  private final ProjectionApplier applier;

  ProjectionUnitOfWork(InboxStore inbox, ProjectionApplier applier) {
    this.inbox = inbox;
    this.applier = applier;
  }

  /** 单条事件的应用结果。 */
  record ApplyResult(Status status, String note, long publishedRevision) {

    enum Status {
      APPLIED,
      ISOLATED,
      /** 前序修订尚未完成，本条不得越过它。 */
      DEFERRED
    }

    boolean applied() {
      return status == Status.APPLIED;
    }

    boolean deferred() {
      return status == Status.DEFERRED;
    }
  }

  @Transactional
  public ApplyResult applyOne(
      String consumerId, InboxEventRecord event, Optional<Map<String, Object>> survivorProjection) {
    ConsumerProgress progress = inbox.lockProgress(consumerId);
    long expected = progress.publishedRevision() + 1;
    if (event.catalogRevision() != expected) {
      return new ApplyResult(
          ApplyResult.Status.DEFERRED,
          "等待修订 " + expected + "（当前事件修订 " + event.catalogRevision() + "）",
          progress.publishedRevision());
    }

    ProjectionApplier.Outcome outcome =
        applier.apply(progress.activeGeneration(), event, survivorProjection);

    if (outcome.shouldIsolate()) {
      // 隔离而不推进水位：水位必须停在这一条上，让「视图落后」这件事在一致性接口上可见，
      // 而不是跳过它继续前进、然后对外宣称已追平。
      inbox.markIsolated(event.id(), outcome.note());
      return new ApplyResult(
          ApplyResult.Status.ISOLATED, outcome.note(), progress.publishedRevision());
    }

    inbox.markApplied(event.id());
    inbox.advancePublishedRevision(consumerId, event.catalogRevision());
    return new ApplyResult(ApplyResult.Status.APPLIED, outcome.note(), event.catalogRevision());
  }
}
