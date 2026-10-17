package com.cognitanexus.ingestion.collect.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 采集 SQL 的两条硬性约束。
 *
 * <p>这两条约束的性质决定了它们只能在这一层验证：单元测试 mock 掉仓储之后，「查询里有没有这个
 * 条件」再也测不到；而它们的失效方式都是<b>静默</b>的——
 *
 * <ul>
 *   <li>可执行任务的查询少了退避条件：抓取失败的退避整套逻辑都还在跑、失败计数也在涨，
 *       但调度每 2 秒照样把任务取出来重试，等于退避没实现；
 *   <li>记录抓取失败的两条 UPDATE 拆成两次写：出现「失败次数已加、退避时刻没设」的中间状态，
 *       取任务的查询在那一瞬间会把任务判为可立即重试。
 * </ul>
 *
 * <p>因此这里直接对注解里的 SQL 文本断言，并在失败信息里说明为什么。这是白盒断言，代价是改列名
 * 时要同步改这里；换来的是「退避到底有没有生效」这个事实不被误判为已验证。
 */
class CollectMapperSqlTest {

  private static String selectSqlOf(String method, Class<?>... parameterTypes) throws Exception {
    Method target = CollectMapper.class.getMethod(method, parameterTypes);
    Select annotation = target.getAnnotation(Select.class);
    assertThat(annotation).as("%s 上应有 @Select", method).isNotNull();
    return String.join(" ", annotation.value());
  }

  private static String updateSqlOf(String method, Class<?>... parameterTypes) throws Exception {
    Method target = CollectMapper.class.getMethod(method, parameterTypes);
    Update annotation = target.getAnnotation(Update.class);
    assertThat(annotation).as("%s 上应有 @Update", method).isNotNull();
    return String.join(" ", annotation.value());
  }

  @Test
  @DisplayName("可执行任务查询带退避过滤：少了它，退避逻辑全在跑但调度照样每 2 秒重试")
  void runnableQueryHonoursBackoffWindow() throws Exception {
    String sql = selectSqlOf("listRunnableJobIds", int.class);

    assertThat(sql).contains("next_attempt_at IS NULL OR next_attempt_at <= now()");
    assertThat(sql).contains("status IN ('PENDING', 'RUNNING')");
  }

  @Test
  @DisplayName("退避的写入是一次 UPDATE：失败次数与下一次尝试时刻不能分开写")
  void fetchFailureWritesCountAndWindowAtomically() throws Exception {
    String sql = updateSqlOf("recordFetchFailure", String.class, String.class, int.class);

    assertThat(sql).contains("consecutive_failures = consecutive_failures + 1");
    assertThat(sql).contains("make_interval(secs => #{backoffSeconds})");
    assertThat(sql).contains("last_error = #{lastError}");
  }

  @Test
  @DisplayName("复位只改计数与退避窗口，并在已经归零时不产生写入")
  void clearFailuresTouchesNothingElse() throws Exception {
    String sql = updateSqlOf("clearFetchFailures", String.class);

    assertThat(sql).contains("consecutive_failures = 0");
    assertThat(sql).contains("next_attempt_at = NULL");
    // 带上条件才写：任务列表里的绝大多数任务都是健康的，每次抓取成功都 UPDATE 一行是白开销。
    assertThat(sql).contains("consecutive_failures <> 0 OR next_attempt_at IS NOT NULL");
  }
}
