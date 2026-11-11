package com.cognitanexus.core.identity.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 保留期清理：会话撤销表。
 *
 * <p>这张表在每次鉴权时被查询（判断当前会话是否已被撤销），而每次改口令、重置口令、封禁
 * 都会写入一行。不清理就会永久增长，最终拖慢所有请求。{@link AuthService#purgeRevocations}
 * 早就写好了，但此前没有任何调用点——属于「写了清理函数却没人调用」的典型死代码。
 *
 * <p>保留窗口必须长于会话最大寿命，否则「刚被撤销的会话」记录先被删掉，旧会话会重新变得可用。
 * 默认 30 天，远大于本项目配置的 2 小时会话超时（{@code spring.session.timeout}）；如有外部
 * 反向代理延长了会话，需同步调大这个保留窗口。
 */
@Component
public class RetentionSweeper {

  private static final Logger log = LoggerFactory.getLogger(RetentionSweeper.class);

  private final AuthService auth;
  private final int revocationRetentionDays;

  public RetentionSweeper(
      AuthService auth,
      @Value("${cn.identity.revocation-retention-days:30}") int revocationRetentionDays) {
    this.auth = auth;
    this.revocationRetentionDays = Math.max(revocationRetentionDays, 1);
  }

  @Scheduled(
      fixedDelayString = "${cn.identity.retention-interval-ms:86400000}",
      initialDelayString = "${cn.identity.retention-initial-delay-ms:300000}")
  public void sweep() {
    try {
      int removed = auth.purgeRevocations(revocationRetentionDays);
      if (removed > 0) {
        log.info("保留期清理：删除超期会话撤销记录 {} 行（保留 {} 天）", removed, revocationRetentionDays);
      }
    } catch (RuntimeException e) {
      log.error("保留期清理失败，下一轮重试（保留 {} 天）", revocationRetentionDays, e);
    }
  }
}
