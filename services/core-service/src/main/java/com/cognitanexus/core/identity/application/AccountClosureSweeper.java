package com.cognitanexus.core.identity.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 注销冷静期到期扫描。
 *
 * <p>只做调度，不含业务判断：执行逻辑在 {@link AccountClosureService#executeDue(int)}，
 * 与管理员手工裁决共用同一条「停用账号」路径。放在这里是因为定时器不该成为第二套业务实现。
 *
 * <p>异常必须在这里吞掉：Spring 的定时任务一旦抛出未捕获异常，后续调度在某些调度器实现下
 * 会停止，结果是「注销永远不再自动执行」而日志里只有一条错误。宁可记错误日志、下一轮重试。
 */
@Component
public class AccountClosureSweeper {

  private static final Logger log = LoggerFactory.getLogger(AccountClosureSweeper.class);

  private final AccountClosureService closures;

  /**
   * 首次执行时打一行日志。
   *
   * <p>没有这行日志，「定时器到底有没有被调度」在运行时是看不出来的：扫描 0 条时按设计不打印，
   * 于是只能靠等一小时再去数据库里找线索。这行同时也是部署后的自检点——重启后应在一分钟内
   * 出现在日志里，没出现就说明调度没注册。
   */
  private final java.util.concurrent.atomic.AtomicBoolean announced =
      new java.util.concurrent.atomic.AtomicBoolean(false);

  public AccountClosureSweeper(AccountClosureService closures) {
    this.closures = closures;
  }

  @Scheduled(
      fixedDelayString = "${cn.closure.sweep-interval-ms:3600000}",
      initialDelayString = "${cn.closure.sweep-initial-delay-ms:60000}")
  public void sweep() {
    if (announced.compareAndSet(false, true)) {
      log.info("注销冷静期扫描已启动：每轮最多执行 {} 条，之后每轮只在实际执行时打日志", AccountClosureService.MAX_AUTO_BATCH);
    }
    try {
      int executed = closures.executeDue(AccountClosureService.MAX_AUTO_BATCH);
      if (executed > 0) {
        log.info("注销冷静期扫描：本轮执行 {} 条", executed);
      }
    } catch (RuntimeException e) {
      log.error("注销冷静期扫描失败，下一轮重试", e);
    }
  }
}
