package com.cognitanexus.core.integrity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.integrity.application.IntegrityNoticeService;
import com.cognitanexus.core.integrity.domain.IntegrityNotice;
import com.cognitanexus.core.integrity.domain.IntegrityNoticeType;
import com.cognitanexus.core.integrity.domain.IntegritySummary;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/**
 * 管理端诚信声明接口的请求装配。
 *
 * <p>这一层不判定业务，但有三处容易写错、且错了不会报错的地方必须钉住：
 *
 * <ol>
 *   <li>请求体整体缺失时不能抛空指针（治理台表单被清空后误点提交是常见操作）；
 *   <li>日期用字符串接收，解析失败要给出「yyyy-MM-dd」这种可照着改的提示，而不是框架的
 *       Failed to convert；
 *   <li>操作者必须取自认证上下文，不能从请求体读——否则任何人可以冒充审核员登记撤稿。
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AdminIntegrityControllerTest {

  @Mock IntegrityNoticeService notices;

  private final ActorContext reviewer =
      new ActorContext(
          new AccountId("acc_reviewer"), "reviewer", "审核员", Set.of(RoleNames.REVIEWER), 1L);

  @AfterEach
  void clearActor() {
    ActorContextHolder.clear();
  }

  private AdminIntegrityController controller() {
    return new AdminIntegrityController(notices);
  }

  private static IntegrityNotice notice(String status) {
    return new IntegrityNotice(
        "integrity_1",
        "ach_1",
        IntegrityNoticeType.RETRACTION,
        status,
        "示例期刊编辑部",
        "经调查确认图 3 数据存在重复使用，编辑部决定撤稿",
        "https://example.org/retraction/1",
        LocalDate.parse("2026-10-01"),
        "acc_reviewer",
        Instant.parse("2026-10-18T09:00:00Z"),
        null,
        null,
        null);
  }

  @Test
  @DisplayName("登记：命令按请求体装配，日期被解析，操作者取自认证上下文")
  void registersWithParsedDateAndAuthenticatedActor() {
    ActorContextHolder.set(reviewer);
    when(notices.register(any(), anyString(), any())).thenReturn(notice("ACTIVE"));

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller()
            .register(
                "ach_1",
                new AdminIntegrityController.RegisterRequest(
                    "RETRACTION",
                    "示例期刊编辑部",
                    "经调查确认图 3 数据存在重复使用，编辑部决定撤稿",
                    "https://example.org/retraction/1",
                    "2026-10-01"));

    ArgumentCaptor<IntegrityNoticeService.RegisterCommand> captor =
        ArgumentCaptor.forClass(IntegrityNoticeService.RegisterCommand.class);
    verify(notices).register(eq(reviewer), eq("ach_1"), captor.capture());
    assertThat(captor.getValue().announcedOn()).isEqualTo(LocalDate.parse("2026-10-01"));
    assertThat(captor.getValue().noticeType()).isEqualTo("RETRACTION");

    Map<String, Object> body = response.getBody().data();
    assertThat(body).containsEntry("id", "integrity_1");
    // 管理端视图比公开视图多两个字段：挂在哪条成果上、谁登记的。
    assertThat(body).containsEntry("achievementId", "ach_1");
    assertThat(body).containsEntry("recordedBy", "acc_reviewer");
  }

  @Test
  @DisplayName("请求体缺失：不抛空指针，把「字段不能为空」交给服务层回答")
  void toleratesMissingBody() {
    ActorContextHolder.set(reviewer);
    when(notices.register(any(), anyString(), any())).thenReturn(notice("ACTIVE"));

    controller().register("ach_1", null);

    ArgumentCaptor<IntegrityNoticeService.RegisterCommand> captor =
        ArgumentCaptor.forClass(IntegrityNoticeService.RegisterCommand.class);
    verify(notices).register(eq(reviewer), eq("ach_1"), captor.capture());
    assertThat(captor.getValue().noticeType()).isNull();
    assertThat(captor.getValue().reason()).isNull();
    assertThat(captor.getValue().announcedOn()).isNull();
  }

  @Test
  @DisplayName("日期格式错误：报错给出 yyyy-MM-dd 与收到的原值")
  void rejectsBadDateFormat() {
    ActorContextHolder.set(reviewer);
    assertThatThrownBy(
            () ->
                controller()
                    .register(
                        "ach_1",
                        new AdminIntegrityController.RegisterRequest(
                            "RETRACTION", "某期刊", "理由足够长的一段说明文字", null, "2026/10/01")))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            error ->
                assertThat(((BusinessException) error).errorCode())
                    .isEqualTo(ErrorCode.INVALID_ARGUMENT))
        .hasMessageContaining("yyyy-MM-dd");
  }

  @Test
  @DisplayName("撤销：理由原样传给服务层，回执带撤销后的状态")
  void revokes() {
    ActorContextHolder.set(reviewer);
    when(notices.revoke(any(), eq("integrity_1"), anyString())).thenReturn(notice("REVOKED"));

    Map<String, Object> body =
        controller()
            .revoke(
                "integrity_1", new AdminIntegrityController.RevokeRequest("经作者申诉与编辑部复核，原声明依据不成立"))
            .getBody()
            .data();

    verify(notices).revoke(eq(reviewer), eq("integrity_1"), eq("经作者申诉与编辑部复核，原声明依据不成立"));
    assertThat(body).containsEntry("status", "REVOKED");
    assertThat(body).containsEntry("active", false);
  }

  @Test
  @DisplayName("成果维度查询：汇总与明细一起返回，汇总字段非空")
  void listsByAchievement() {
    // 这两个端点现在要求治理能力，操作者必须来自上下文；控制器把同一个 actor 传给服务
    ActorContextHolder.set(reviewer);
    when(notices.noticesOf(reviewer, "ach_1")).thenReturn(List.of(notice("ACTIVE")));
    when(notices.summaryOf(reviewer, "ach_1"))
        .thenReturn(
            new IntegritySummary(
                "RETRACTION", "已撤稿", "该成果已被声明作废，不应作为有效研究引用", List.of("RETRACTION"), 1, 1));

    Map<String, Object> body = controller().ofAchievement("ach_1").getBody().data();

    assertThat(body.get("summary")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> summary = (Map<String, Object>) body.get("summary");
    assertThat(summary).containsEntry("status", "RETRACTION");
    assertThat(summary).containsEntry("label", "已撤稿");
    assertThat(summary).containsEntry("noticed", true);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
    assertThat(items).hasSize(1);
  }

  @Test
  @DisplayName("最近声明：默认只列生效中的，回显查询参数便于调用方确认口径")
  void listsRecent() {
    ActorContextHolder.set(reviewer);
    when(notices.recent(reviewer, 50, true)).thenReturn(List.of(notice("ACTIVE")));

    Map<String, Object> body = controller().recent(50, true).getBody().data();

    assertThat(body).containsEntry("total", 1);
    assertThat(body).containsEntry("activeOnly", true);
    assertThat(body).containsEntry("limit", 50);
  }
}
