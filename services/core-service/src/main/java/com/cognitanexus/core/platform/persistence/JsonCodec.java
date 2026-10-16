package com.cognitanexus.core.platform.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * jsonb 列的序列化辅助。
 *
 * <p>依据：编码规范 §12。写入 jsonb 时统一使用 JDBC 参数加显式 <code>::jsonb</code> 转换，
 * 不做字符串拼接，避免注入与转义错误；读取时 PG 驱动直接返回 JSON 文本。
 */
@Component
public class JsonCodec {

  private final ObjectMapper mapper;

  public JsonCodec(ObjectMapper mapper) {
    this.mapper = mapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  /** 序列化为 JSON 文本；null 映射为 JSON null 而不是 SQL NULL，保持「显式空值」语义。 */
  public String write(Map<String, Object> value) {
    if (value == null) {
      return null;
    }
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("JSON 序列化失败", ex);
    }
  }

  public String write(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("JSON 序列化失败", ex);
    }
  }

  /** 读取字符串数组。jsonb 中若混入非字符串元素，直接失败而不是静默丢弃。 */
  public List<String> readStringList(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      JavaType type = mapper.getTypeFactory().constructCollectionType(List.class, String.class);
      return mapper.readValue(json, type);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("JSON 字符串数组反序列化失败", ex);
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
      throw new IllegalStateException("JSON 反序列化失败", ex);
    }
  }

  public <T> T read(String json, Class<T> type) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return mapper.readValue(json, type);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("JSON 反序列化失败", ex);
    }
  }
}
