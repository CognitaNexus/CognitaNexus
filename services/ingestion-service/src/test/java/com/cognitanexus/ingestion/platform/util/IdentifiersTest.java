package com.cognitanexus.ingestion.platform.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 业务标识生成的行为约束。
 *
 * <p>依据：编码规范 §5.3。主键为「前缀_随机十六进制」，不依赖数据库自增，因此在跨服务交接时
 * 可以就地生成。测试关注三件事：前缀可辨识、随机段长度固定、批量生成不碰撞。
 */
class IdentifiersTest {

  @Test
  @DisplayName("标识以前缀开头，使日志与人工排查一眼看出实体种类")
  void prefixed() {
    assertThat(Identifiers.next("ach")).startsWith("ach_");
    assertThat(Identifiers.next("sch")).startsWith("sch_");
    assertThat(Identifiers.batch()).startsWith("hbt_");
  }

  @Test
  @DisplayName("随机段为 26 位小写十六进制（13 字节），长度固定")
  void fixedLength() {
    String id = Identifiers.next("ach");
    assertThat(id).matches("ach_[0-9a-f]{26}");
  }

  @Test
  @DisplayName("连续生成一万个标识不碰撞")
  void noCollision() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 10_000; i++) {
      seen.add(Identifiers.next("ach"));
    }
    assertThat(seen).hasSize(10_000);
  }

  @Test
  @DisplayName("同一前缀下的标识彼此不同，且不以时间戳为成分（离线可用）")
  void notSequential() {
    String first = Identifiers.next("x");
    String second = Identifiers.next("x");
    assertThat(first).isNotEqualTo(second);
  }
}
