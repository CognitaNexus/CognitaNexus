package com.cognitanexus.core.platform.security;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * 会话序列化配置。
 *
 * <p>依据：架构 V0.2 §8.2。会话内容使用 JSON 并带类型白名单，禁止使用会信任任意 Java 类型的
 * 默认序列化：Redis 中的会话数据若可被替换为攻击者构造的载荷，JDK 反序列化会成为代码执行入口。
 *
 * <p>白名单覆盖四类：
 *
 * <ul>
 *   <li>本工程的类（com.cognitanexus.）——保存的登录态快照；
 *   <li>Spring Session 自身类型——会话容器与属性映射；
 *   <li>Spring Security 自身类型——框架会把请求缓存、CSRF 令牌等写进会话；
 *   <li>java.lang / java.util / java.time——字符串、集合、时间等基础载体。
 * </ul>
 *
 * <p>为什么必须放行 Spring Security 包：框架默认的 RequestCache 会在未认证请求命中受保护
 * 资源时，把 org.springframework.security.web.savedrequest.DefaultSavedRequest 写入会话。
 * 白名单若不含该前缀，读取会话时即刻抛序列化异常。该异常发生在过滤器阶段，Spring MVC 的
 * 异常处理拿不到它，最终会退化成容器默认的错误页——表现为普通的一次未登录访问返回 500，
 * 而不是预期的 401。此处的取舍是：放行该前缀，同时保留其余限制。
 *
 * <p>残留风险与配套措施：放行整个前缀意味着白名单的粒度不足以区分「框架会话类」与「其他
 * Spring Security 类」。真正的防线是 Redis 实例自身的认证与网络隔离（架构 §8.2 双实例分离，
 * 会话实例 noeviction + AOF 且仅本地/内网可达）。攻击者若能任意改写 Redis 中的会话键，
 * 已可直接伪造登录态，序列化白名单在此不构成额外保护。故本配置定位为纵深防御的一层，
 * 而非唯一防线。
 *
 * <p>白名单之外的子类型在序列化或反序列化时直接失败，而不是降级为宽松模式。
 */
@Configuration(proxyBeanMethods = false)
public class SessionSerializerConfig {

  @Bean
  public RedisSerializer<Object> springSessionDefaultRedisSerializer(ObjectMapper objectMapper) {
    PolymorphicTypeValidator validator =
        BasicPolymorphicTypeValidator.builder()
            .allowIfSubType("com.cognitanexus.")
            .allowIfSubType("org.springframework.session.")
            .allowIfSubType("org.springframework.security.")
            .allowIfSubType("java.util.")
            .allowIfSubType("java.lang.")
            .allowIfSubType("java.time.")
            .build();

    ObjectMapper sessionMapper = objectMapper.copy();
    sessionMapper.activateDefaultTyping(
        validator, ObjectMapper.DefaultTyping.EVERYTHING, JsonTypeInfo.As.PROPERTY);
    return new GenericJackson2JsonRedisSerializer(sessionMapper);
  }
}
