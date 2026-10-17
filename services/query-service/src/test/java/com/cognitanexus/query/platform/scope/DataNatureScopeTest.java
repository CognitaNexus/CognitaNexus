package com.cognitanexus.query.platform.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cognitanexus.sharedkernel.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 数据性质作用域归一的行为约束。
 *
 * <p>依据：BR-IMP-10（模拟数据不得冒充真实）、FR11（公开检索）、FR13（统计分析）、架构 V0.2 §9.2。
 *
 * <p>这是全系统最容易被写反、且写反了也测不出来的一条规则：在只有真实数据的库上，
 * 「缺省＝不限制」与「缺省＝REAL」表现完全一致。因此测试把判据钉死为
 * <b>只有调用方显式要求，范围才扩大</b>。
 */
class DataNatureScopeTest {

  @Test
  @DisplayName("缺省（null）归一为 REAL，而不是「不限制」——这是防模拟数据混入真实口径的第一道闸")
  void nullDefaultsToReal() {
    assertThat(DataNatureScope.normalize(null)).isEqualTo("REAL");
  }

  @Test
  @DisplayName("空串与纯空白同样缺省为 REAL，不接受「看起来像没传」的写法绕过闸门")
  void blankDefaultsToReal() {
    assertThat(DataNatureScope.normalize("")).isEqualTo("REAL");
    assertThat(DataNatureScope.normalize("   ")).isEqualTo("REAL");
  }

  @Test
  @DisplayName("只有显式 ALL 才放宽为不限制（返回 null 表示 SQL 层不拼条件）")
  void explicitAllWidensScope() {
    assertThat(DataNatureScope.normalize("ALL")).isNull();
    assertThat(DataNatureScope.normalize("all")).isNull();
    assertThat(DataNatureScope.normalize(" all ")).isNull();
  }

  @Test
  @DisplayName("具体性质名大小写归一后原样返回")
  void concreteNaturesNormalized() {
    assertThat(DataNatureScope.normalize("real")).isEqualTo("REAL");
    assertThat(DataNatureScope.normalize("Simulated")).isEqualTo("SIMULATED");
    assertThat(DataNatureScope.normalize("MIXED")).isEqualTo("MIXED");
  }

  @Test
  @DisplayName("未知取值必须报错而不是回落到默认值：静默回落会让用户以为自己查的是真实口径")
  void unknownValueRejected() {
    assertThatThrownBy(() -> DataNatureScope.normalize("TEST"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("数据性质取值必须是");
  }

  @Test
  @DisplayName("label 把 null 回显为 ALL：作用域字段必须总是出现，缺失会被读成「是真实的」")
  void labelAlwaysPresent() {
    assertThat(DataNatureScope.label(null)).isEqualTo("ALL");
    assertThat(DataNatureScope.label("REAL")).isEqualTo("REAL");
    assertThat(DataNatureScope.label("SIMULATED")).isEqualTo("SIMULATED");
  }

  @Test
  @DisplayName("REAL 口径的说明明确声称自己是默认现实视图口径")
  void realNote() {
    assertThat(DataNatureScope.note("REAL")).contains("仅含真实数据");
  }

  @Test
  @DisplayName("ALL 口径的说明必须点明含模拟数据且仅适用于演示，不得作为真实统计引用")
  void allNoteWarns() {
    String note = DataNatureScope.note(null);
    assertThat(note).contains("含模拟数据").contains("演示");
    assertThat(note).contains("不代表真实收录口径");
  }

  @Test
  @DisplayName("SIMULATED 口径的说明点明属演示口径")
  void simulatedNoteWarns() {
    String note = DataNatureScope.note("SIMULATED");
    assertThat(note).contains("SIMULATED").contains("演示口径");
    assertThat(note).contains("不得作为平台真实统计对外引用");
  }

  @Test
  @DisplayName("三处读入口共用同一份实现：常量与允许集合不因调用方不同而不同")
  void constantsAreShared() {
    assertThat(DataNatureScope.REAL).isEqualTo("REAL");
    assertThat(DataNatureScope.ALL).isEqualTo("ALL");
    // MIXED 是合法具体性质，但不能通过缺省进入
    assertThat(DataNatureScope.normalize("MIXED")).isEqualTo("MIXED");
  }
}
