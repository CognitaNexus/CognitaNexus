package com.cognitanexus.core.governance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.governance.application.PublicCorrectionService;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.sharedkernel.AccountId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/**
 * 公众纠错接口的响应结构。
 *
 * <p>这一层不判定业务，但它决定「回执说了什么」。回执是匿名提交方唯一能看到的东西，因此有两条
 * 契约必须钉住：
 *
 * <ol>
 *   <li><b>不泄露治理积压</b>：响应里不能出现异常总数、其它报告原文这类治理内部信息；
 *   <li><b>不能说成「已修复」</b>：{@code note} 必须写明「已登记，核实后由治理人员处理」，
 *       因为这条接口一个字段都没改。
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class PublicCorrectionControllerTest {

  @Mock PublicCorrectionService corrections;
  @Mock HttpServletRequest request;

  private PublicCorrectionController controller() {
    return new PublicCorrectionController(corrections);
  }

  @Test
  @DisplayName("首次提交的回执：accepted、次数、字段标签，且 note 明说不改数据")
  void firstReportReceipt() {
    when(request.getRemoteAddr()).thenReturn("10.0.0.7");
    when(corrections.submit(any(), anyString(), isNull()))
        .thenReturn(new PublicCorrectionService.Outcome("anm_1", false, 1, "TITLE", "标题"));

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller()
            .report(
                "ach_1",
                new PublicCorrectionController.CorrectionReportRequest(
                    "TITLE", "这篇论文的标题少了一个副标题", null),
                request);

    Map<String, Object> body = response.getBody().data();
    assertThat(body).containsEntry("accepted", true);
    assertThat(body).containsEntry("reportId", "anm_1");
    assertThat(body).containsEntry("reportCount", 1);
    assertThat(body).containsEntry("fieldLabel", "标题");
    assertThat(body).containsEntry("merged", false);
    assertThat(String.valueOf(body.get("note"))).contains("已登记", "不会自动修改");
  }

  @Test
  @DisplayName("并入既有待办的回执：merged=true 且 note 说明累计次数")
  void mergedReportReceipt() {
    when(request.getRemoteAddr()).thenReturn("10.0.0.7");
    when(corrections.submit(any(), anyString(), isNull()))
        .thenReturn(new PublicCorrectionService.Outcome("anm_1", true, 3, "TITLE", "标题"));

    Map<String, Object> body =
        controller()
            .report(
                "ach_1",
                new PublicCorrectionController.CorrectionReportRequest(
                    "TITLE", "这篇论文的标题少了一个副标题", null),
                request)
            .getBody()
            .data();

    assertThat(body).containsEntry("merged", true);
    assertThat(String.valueOf(body.get("note"))).contains("并入", "3");
    // 回执不得夹带治理内部信息
    assertThat(body).doesNotContainKeys("anomaliesOpen", "total", "openAnomalies");
  }

  @Test
  @DisplayName("已登录提交时把账号标识一并传出，匿名时传 null")
  void passesAccountIdWhenLoggedIn() throws Exception {
    when(request.getRemoteAddr()).thenReturn("10.0.0.7");
    when(corrections.submit(any(), anyString(), eq("acc_1")))
        .thenReturn(new PublicCorrectionService.Outcome("anm_1", false, 1, "YEAR", "发表年份"));

    try (var ignored = withActor("acc_1")) {
      controller()
          .report(
              "ach_1",
              new PublicCorrectionController.CorrectionReportRequest(
                  "YEAR", "发表年份应为 2023 而不是 2024", "me@example.org"),
              request);
    }
    verify(corrections).submit(any(), eq("10.0.0.7"), eq("acc_1"));
  }

  @Test
  @DisplayName("请求体缺失时不抛 NPE，交给服务层给出可解释的参数错误")
  void toleratesMissingBody() {
    when(request.getRemoteAddr()).thenReturn("10.0.0.7");
    when(corrections.submit(any(), anyString(), isNull()))
        .thenThrow(com.cognitanexus.sharedkernel.BusinessException.invalidArgument("请求体不能为空"));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> controller().report("ach_1", null, request))
        .isInstanceOf(com.cognitanexus.sharedkernel.BusinessException.class);
  }

  @Test
  @DisplayName("字段清单直接下发给前端，标签只在服务端定义一处")
  void exposesFieldCatalogue() {
    when(corrections.fields()).thenReturn(List.of(Map.of("code", "TITLE", "label", "标题")));

    List<Map<String, Object>> fields = controller().correctionFields().getBody().data();
    assertThat(fields).hasSize(1);
    assertThat(fields.get(0)).containsEntry("code", "TITLE").containsEntry("label", "标题");
  }

  /** 临时把操作者放进 ThreadLocal：控制器从 ActorContextHolder 取身份，而不是从请求体读。 */
  private static AutoCloseable withActor(String accountId) {
    ActorContext actor =
        new ActorContext(new AccountId(accountId), "u", "用户", Set.of(RoleNames.REVIEWER), 1L);
    com.cognitanexus.core.platform.security.ActorContextHolder.set(actor);
    return () -> com.cognitanexus.core.platform.security.ActorContextHolder.clear();
  }
}
