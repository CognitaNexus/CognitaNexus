package com.cognitanexus.ingestion.platform.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * JSON 编解码。
 *
 * <p>依据：编码规范 §5.5。移植自 core 侧同类组件：本服务不依赖 core 的代码（架构 §4.2
 * 禁止服务间 Java 业务模块依赖），因此这类极小工具在各服务内各自实现，而不是抽成共享库
 * 让三个服务互相耦合。
 *
 * <p>刻意复制入参 ObjectMapper 而不是注入全局实例：全局实例的配置会被 Web 层需求影响，
 * 而入库 JSON 的形态不能随 Web 序列化配置漂移。
 */
@Component
public class JsonCodec {

  private final ObjectMapper mapper;

  public JsonCodec(ObjectMapper source) {
    this.mapper = source.copy();
    this.mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  public String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("无法序列化 JSON", ex);
    }
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> readMap(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return mapper.readValue(json, Map.class);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("无法解析 JSON 对象", ex);
    }
  }

  public List<String> readStringList(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return mapper.readValue(
          json, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("无法解析 JSON 字符串数组", ex);
    }
  }

  public <T> T read(String json, Class<T> type) {
    try {
      return mapper.readValue(json, type);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("无法解析 JSON 为 " + type.getSimpleName(), ex);
    }
  }
}
