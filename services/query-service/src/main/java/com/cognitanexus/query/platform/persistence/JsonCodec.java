package com.cognitanexus.query.platform.persistence;

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
 * <p>依据：编码规范 §5.5。移植自另外两个服务的同类组件：本服务不依赖 core 的代码（架构 §4.2
 * 禁止服务间 Java 业务模块依赖），因此这类极小工具在各服务内各自实现，而不是抽成共享库让三个
 * 服务互相耦合。
 *
 * <p>刻意复制入参 ObjectMapper 而不是注入全局实例：全局实例的配置会被 Web 层需求影响，
 * 而入库 JSON 的形态不能随 Web 序列化配置漂移。事件载荷原样落库，解析时按需取字段，
 * 不做「反序列化成强类型 DTO」——未知事件类型与提升后的 schemaVersion 都必须能原样保留，
 * 否则隔离待处理就变成了丢弃。
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

  public List<String> readStringList(Object value) {
    if (value == null) {
      return List.of();
    }
    if (value instanceof List<?> list) {
      return list.stream().filter(item -> item instanceof String).map(String.class::cast).toList();
    }
    return List.of();
  }

  public Map<String, Object> readMap(Object value) {
    if (value instanceof Map<?, ?> map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> typed = (Map<String, Object>) map;
      return typed;
    }
    return Map.of();
  }

  /**
   * 读取对象数组。
   *
   * <p>只保留元素本身是对象的条目：事件载荷中形如 contributions / topicViews 的字段是对象数组，
   * 若其中混入非对象元素（来源侧数据不规范），跳过该元素比让整条事件隔离更合适——被跳过的元素
   * 只影响该条成果的辅助信息，而整条事件隔离会让成果本身从检索中消失。
   */
  public List<Map<String, Object>> readMapList(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<Map<String, Object>> result = new java.util.ArrayList<>(list.size());
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) map;
        result.add(typed);
      }
    }
    return result;
  }
}
