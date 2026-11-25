package com.cognitanexus.query.network.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.network.application.NetworkQueryRouter;
import com.cognitanexus.query.platform.web.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 公开网络端点的协议层行为。
 *
 * <p>切换查询实现时，最容易被顺手改坏的是这些「看起来只是参数处理」的细节：{@code scholarId}
 * 要 trim、可选筛选空串要归一为 null、{@code maxNodes} 缺省要变成 0、响应要包在统一的
 * {@link ApiResponse} 里。它们一旦变化，前端拿到的就不是同一个契约。
 */
class PublicNetworkControllerTest {

  private final NetworkQueryRouter router = mock(NetworkQueryRouter.class);

  private final PublicNetworkController controller = new PublicNetworkController(router);

  @Test
  @DisplayName("/graph：参数按契约归一后交给路由（trim、空串转 null、maxNodes 缺省 0）")
  void graphNormalizesParameters() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("seedInScope", true);
    when(router.neighborhood(any(), anyInt(), anyInt(), any(), any(), any())).thenReturn(data);

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller.graph("  scholar-1  ", 2, null, "REAL", "  ", "  org-1  ");

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("OK");
    assertThat(response.getBody().data()).isEqualTo(data);
    verify(router).neighborhood(eq("scholar-1"), eq(2), eq(0), eq("REAL"), isNull(), eq("org-1"));
  }

  @Test
  @DisplayName("/graph：显式 maxNodes 原样传递，服务端上限由应用层强制")
  void graphForwardsExplicitMaxNodes() {
    when(router.neighborhood(any(), anyInt(), anyInt(), any(), any(), any())).thenReturn(Map.of());

    controller.graph("scholar-1", 3, 500, "REAL", "topic-1", null);

    verify(router)
        .neighborhood(eq("scholar-1"), eq(3), eq(500), eq("REAL"), eq("topic-1"), isNull());
  }

  @Test
  @DisplayName("/path：起点终点 trim 后交给路由，空筛选归一为 null")
  void pathNormalizesParameters() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("reachable", true);
    when(router.shortestPath(any(), any(), any(), any(), any())).thenReturn(data);

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller.path(" from-1 ", "to-1", "ALL", "", " org-2 ");

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isEqualTo(data);
    verify(router).shortestPath("from-1", "to-1", "ALL", null, "org-2");
  }
}
