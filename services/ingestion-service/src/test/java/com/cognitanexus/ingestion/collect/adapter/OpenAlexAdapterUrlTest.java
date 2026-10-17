package com.cognitanexus.ingestion.collect.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OpenAlex 查询 URL 的组装。
 *
 * <p>依据：AC-IMPL-FR08-02（重试与续采必须能定位断点、不重复抓取）。
 *
 * <p>这里守一个曾真实发生的失效：OpenAlex 的 {@code cursor} 翻页已要求 API key，无密钥时第二页
 * 返回 401；而 {@code cursor} 为空时它又把 next_cursor 置空，看起来就像「只有一页」。两者叠加
 * 的结果是任务状态 SUCCEEDED、实际只采到第一页。改用 {@code page} 偏移分页后，无密钥也能翻页，
 * 代价是数据插入时可能漏行——这一点写在适配器注释里，不假装没有。
 */
class OpenAlexAdapterUrlTest {

  private static final String BASE = "https://api.openalex.org/works";
  private static final String UA = "CognitaNexus/0.2 (mailto:cn-admin@example.edu.cn)";

  @Test
  @DisplayName("第一页用 page=1，且不再发送 cursor 参数")
  void firstPageUsesPageOne() {
    String url = OpenAlexAdapter.buildUrl(BASE, "cancer", 50, 1, UA);

    assertThat(url).contains("page=1").doesNotContain("cursor=");
  }

  @Test
  @DisplayName("后续页按页码推进")
  void subsequentPageUsesPageNumber() {
    assertThat(OpenAlexAdapter.buildUrl(BASE, "cancer", 50, 7, UA)).contains("page=7");
  }

  @Test
  @DisplayName("查询式与页大小进入 URL，中文查询按 UTF-8 编码")
  void queryAndPageSizeAreEncoded() {
    String url = OpenAlexAdapter.buildUrl(BASE, "癌症 免疫", 25, 1, UA);

    assertThat(url)
        .contains("per-page=25")
        .contains("search=%E7%99%8C%E7%97%87+%E5%85%8D%E7%96%AB");
  }

  @Test
  @DisplayName("基地址已带查询串时用 & 续接，不产生两个问号")
  void appendsAmpersandWhenBaseHasQuery() {
    String url = OpenAlexAdapter.buildUrl(BASE + "?filter=type:article", "cancer", 50, 1, UA);

    assertThat(url)
        .contains("?filter=type:article&search=")
        .doesNotContain("?filter=type:article?");
  }

  @Test
  @DisplayName("只取需要的字段，避免整包返回把响应撑到上限")
  void selectsNarrowFieldSet() {
    String url = OpenAlexAdapter.buildUrl(BASE, "x", 10, 1, UA);

    assertThat(url).contains("select=").contains("topics").contains("primary_topic");
  }

  @Test
  @DisplayName("UA 里的联系邮箱作为 mailto 参数传给来源，未提供时不加")
  void mailtoComesFromUserAgent() {
    assertThat(OpenAlexAdapter.buildUrl(BASE, "x", 10, 1, UA))
        .contains("mailto=cn-admin%40example.edu.cn");
    assertThat(OpenAlexAdapter.buildUrl(BASE, "x", 10, 1, "CognitaNexus/0.2"))
        .doesNotContain("mailto=");
  }

  @Test
  @DisplayName("空游标视为第一页")
  void emptyCursorMeansFirstPage() {
    assertThat(OpenAlexAdapter.pageNumber(null)).isEqualTo(1);
    assertThat(OpenAlexAdapter.pageNumber("   ")).isEqualTo(1);
  }

  @Test
  @DisplayName("非数字游标必须报错，不能悄悄回落到第一页重采")
  void invalidCursorFailsLoudly() {
    // 回落到第 1 页会让任务一直「有进展」却永远推进不到新数据
    assertThatThrownBy(() -> OpenAlexAdapter.pageNumber("IlsxNzkuNDE5NDY"))
        .isInstanceOf(com.cognitanexus.sharedkernel.BusinessException.class)
        .hasMessageContaining("不是合法页码");
    assertThatThrownBy(() -> OpenAlexAdapter.pageNumber("0"))
        .isInstanceOf(com.cognitanexus.sharedkernel.BusinessException.class);
  }
}
