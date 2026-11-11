package com.cognitanexus.core.identity.application;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 登录短窗限流。
 *
 * <p>依据：架构 V0.2 §8.1。密钥放在 redis-session，与可淘汰缓存分实例：限流计数不得随内存压力
 * 被淘汰，否则爆破防护会在压力下自动失效。
 *
 * <p>本组件只提供短窗防护，不是权威依据；账号的失败次数与锁定时刻保存在核心库。Redis 不可用时
 * 这里的检查选择「放行但记录」，由数据库侧的锁定兜底——因为把 Redis 故障解释成拒绝登录会让
 * 全体用户无法登录，而把 Redis 故障解释成停止计数只是在更短窗口内少了一层保护。
 */
@Component
public class LoginThrottle {

  private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

  private static final String KEY_PREFIX = "cn:local:core:login:";
  private static final int MAX_ATTEMPTS_PER_WINDOW = 12;
  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final StringRedisTemplate redis;

  public LoginThrottle(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /** 当前窗口内该登录名是否已超限。 */
  public boolean exceeded(String subjectHash) {
    try {
      String value = redis.opsForValue().get(KEY_PREFIX + subjectHash);
      return value != null && Long.parseLong(value) >= MAX_ATTEMPTS_PER_WINDOW;
    } catch (RuntimeException ex) {
      log.warn("登录限流读取失败，降级为依赖数据库锁定 message={}", ex.toString());
      return false;
    }
  }

  public void recordAttempt(String subjectHash) {
    try {
      String key = KEY_PREFIX + subjectHash;
      Long count = redis.opsForValue().increment(key);
      if (count != null && count == 1L) {
        redis.expire(key, WINDOW);
      }
    } catch (RuntimeException ex) {
      log.warn("登录限流写入失败 message={}", ex.toString());
    }
  }

  public void clear(String subjectHash) {
    try {
      redis.delete(KEY_PREFIX + subjectHash);
    } catch (RuntimeException ex) {
      log.warn("登录限流清理失败 message={}", ex.toString());
    }
  }
}
