package com.cognitanexus.core.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.application.AdminCatalogService;
import com.cognitanexus.core.catalog.application.ContributionRelationService;
import com.cognitanexus.core.catalog.application.SourceAdminService;
import com.cognitanexus.core.platform.audit.AuditQueryService;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.portal.application.PortalService;
import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/**
 * 统一待办入口（FR14）。
 *
 * <p>这一层不做业务判断，但它决定「今天该处理什么」这个问题的答案是否正确：
 *
 * <ul>
 *   <li>口径必须窄——只统计「需要人做决定」的事项。把采集失败、投递积压一并加进来，待办数会
 *       长期不等于零，运维人员第一周就会学会忽略它，这一屏随即失效。
 *   <li>没有能力时必须给 {@code null} 而不是 0。0 的意思是「今天没事」，null 的意思是
 *       「这件事我看不到」，两者在运维决策上是相反的结论。
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AdminCatalogTodosTest {

  @Mock AdminCatalogService adminCatalog;
  @Mock SourceAdminService sourceAdmin;
  @Mock AuditQueryService auditQuery;
  @Mock ContributionRelationService relations;
  @Mock PortalService portal;

  private AdminCatalogController controller;

  @BeforeEach
  void setUp() {
    controller =
        new AdminCatalogController(adminCatalog, sourceAdmin, auditQuery, relations, portal);
  }

  @AfterEach
  void tearDown() {
    ActorContextHolder.clear();
  }

  private static ActorContext actor(String id, String... roles) {
    return new ActorContext(new AccountId(id), id, id, Set.of(roles), 1L);
  }

  private static Map<String, Object> overviewWith(
      long pending, long candidates, long anomalies, long deliveryPending) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("achievementsPending", pending);
    view.put("dedupCandidatesOpen", candidates);
    view.put("anomaliesOpen", anomalies);
    Map<String, Object> delivery = new LinkedHashMap<>();
    delivery.put("pending", deliveryPending);
    delivery.put("failed", 0L);
    view.put("delivery", delivery);
    return view;
  }

  @SuppressWarnings("unchecked")
  private static <T> T dataOf(ResponseEntity<ApiResponse<T>> response) {
    assertThat(response.getBody()).isNotNull();
    return response.getBody().data();
  }

  @Test
  @DisplayName("待办总数只统计需要人决定的事项，投递积压不计入")
  void todoTotalCountsOnlyHumanDecisions() {
    ActorContextHolder.set(actor("acc_admin", RoleNames.PLATFORM_ADMIN));
    // 投递积压给一个很大的值：它属于运行状态，若被计入总数，这条断言就会失败。
    when(adminCatalog.overview(any())).thenReturn(overviewWith(11, 22, 33, 999));
    when(portal.countOpenClaims()).thenReturn(4L);
    when(portal.countPendingChanges()).thenReturn(5L);

    Map<String, Object> view = dataOf(controller.todos());

    assertThat(view).containsEntry("achievementsPending", 11L);
    assertThat(view).containsEntry("claimsPending", 4L);
    assertThat(view).containsEntry("portalChangesPending", 5L);
    assertThat(view.get("todoTotal")).isEqualTo(11L + 22 + 33 + 4 + 5);
    // overview 的其他字段必须原样带出：待办页同时展示运行状态链接所依赖的计数。
    assertThat(view).containsKey("delivery");
  }

  @Test
  @DisplayName("待办说明必须写明口径与 null 的含义，否则界面只能靠猜")
  void todoNoteExplainsScope() {
    ActorContextHolder.set(actor("acc_reviewer", RoleNames.REVIEWER));
    when(adminCatalog.overview(any())).thenReturn(overviewWith(1, 2, 3, 0));
    when(portal.countOpenClaims()).thenReturn(0L);
    when(portal.countPendingChanges()).thenReturn(0L);

    Map<String, Object> view = dataOf(controller.todos());

    String note = String.valueOf(view.get("todoNote"));
    assertThat(note).contains("需要人做决定");
    assertThat(note).contains("采集失败");
    assertThat(note).contains("null");
  }

  @Test
  @DisplayName("未登录时在取任何计数之前就被拒绝，不会先算一遍再返回 401")
  void todosRequiresAuthentication() {
    // 真实守卫在 AdminCatalogService.overview 里的 Capabilities.require(actor, governance:manage)：
    // 匿名操作者拿到 AUTH_REQUIRED。这里复制它的失败方式，验证控制器不会在聚合之前先去取
    // 认领与门户变更计数——那等于对匿名请求做了一次未授权查询。
    when(adminCatalog.overview(isNull()))
        .thenThrow(new BusinessException(ErrorCode.AUTH_REQUIRED, "请先登录"));

    assertThatThrownBy(() -> controller.todos())
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.AUTH_REQUIRED);

    verifyNoInteractions(portal);
  }

  @Test
  @DisplayName("普通账号没有治理能力，待办入口拒绝访问")
  void todosRejectsPlainUser() {
    ActorContextHolder.set(actor("acc_user", RoleNames.USER));
    when(adminCatalog.overview(any())).thenThrow(BusinessException.accessDenied("当前账号不具备该操作的能力"));

    assertThatThrownBy(() -> controller.todos())
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.ACCESS_DENIED);

    // 拒绝之后不得再去取认领与变更计数。
    verifyNoInteractions(portal);
  }
}
