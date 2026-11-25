package com.cognitanexus.query.graph.schema;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * 加载并切分 Cypher schema 定义文件（{@code classpath:neo4j/schema.cypher}）。
 *
 * <p>为什么把 schema 放在资源文件而不是 Java 字符串：约束与索引是运维也要读的东西，
 * 放在 {@code .cypher} 里可以直接交给 {@code cypher-shell} 核对，且审阅 DDL 的人不需要读 Java。
 *
 * <p>解析规则刻意保持简单：整行 {@code //} 注释与空行剔除，按分号切分。不做通用 Cypher 解析——
 * 支持一半语法比不支持更危险，本文件只允许一条语句一行的 CREATE 形式（由测试锁定）。
 */
@Component
public class Neo4jSchemaStatementSource {

  /** schema 定义文件位置。运维手工核对时使用同一路径。 */
  public static final String RESOURCE_PATH = "neo4j/schema.cypher";

  private final Resource resource;

  public Neo4jSchemaStatementSource() {
    this(new ClassPathResource(RESOURCE_PATH));
  }

  public Neo4jSchemaStatementSource(Resource resource) {
    this.resource = resource;
  }

  /** 读取并切分全部 schema 语句；文件缺失或没有任何语句视为配置错误，明确失败。 */
  public List<String> load() {
    String raw;
    try (InputStream in = resource.getInputStream()) {
      raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("无法读取图库 schema 定义：" + RESOURCE_PATH, e);
    }
    List<String> statements = parse(raw);
    if (statements.isEmpty()) {
      throw new IllegalStateException("图库 schema 定义为空：" + RESOURCE_PATH);
    }
    return statements;
  }

  /** 纯函数版本，便于单元测试与离线校验。 */
  public static List<String> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    StringBuilder withoutComments = new StringBuilder(raw.length());
    for (String line : raw.split("\n", -1)) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("//")) {
        continue;
      }
      withoutComments.append(trimmed).append('\n');
    }
    List<String> statements = new ArrayList<>();
    for (String piece : withoutComments.toString().split(";")) {
      String statement = piece.trim();
      if (!statement.isEmpty()) {
        statements.add(statement);
      }
    }
    return List.copyOf(statements);
  }
}
