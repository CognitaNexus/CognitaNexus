package com.cognitanexus.query.discovery.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 检索条件与查询版本的行为约束。
 *
 * <p>依据：FR11（公开检索）、架构 V0.2 §9.1「排序字段走白名单，末位唯一稳定ID」「首请求返回
 * queryVersion，后续分页必须携带」。
 *
 * <p>queryVersion 的意义在于：它同时绑定条件与数据快照。条件变了却沿用旧版本翻页，会把两套
 * 条件的结果混进同一个列表；数据变了却沿用旧版本，会跨过两个快照。因此摘要必须<b>只依赖字段
 * 取值本身</b>，不依赖拼装顺序、不依赖对象身份。
 */
class SearchQueryTest {

  private static SearchQuery query(Integer size, String sort, String nature) {
    return new SearchQuery(
        "graph",
        "graph",
        null,
        null,
        null,
        null,
        null,
        nature,
        sort,
        1,
        size == null ? 20 : size,
        null);
  }

  @Test
  @DisplayName("允许的排序键是固定白名单，且包含全部五个取值")
  void allowedSorts() {
    assertThat(SearchQuery.allowedSorts())
        .containsExactly("relevance", "year_desc", "year_asc", "title_asc", "citations_desc");
    assertThat(SearchQuery.allowedSorts()).isUnmodifiable();
  }

  @Test
  @DisplayName("允许的数据性质取值包含 REAL / SIMULATED / MIXED / ALL")
  void allowedNatures() {
    assertThat(SearchQuery.allowedNatures()).containsExactly("REAL", "SIMULATED", "MIXED", "ALL");
  }

  @Test
  @DisplayName("相同条件产生相同摘要：字段顺序固定，不受构造顺序影响")
  void digestIsStable() {
    SearchQuery a = query(20, "relevance", "REAL");
    SearchQuery b = query(20, "relevance", "REAL");
    assertThat(a.digest()).isEqualTo(b.digest());
    assertThat(a.digest()).hasSize(16);
  }

  @Test
  @DisplayName("任一条件变化都改变摘要：否则前端改用条件后仍能沿用旧版本翻页")
  void digestChangesWithAnyField() {
    String base = query(20, "relevance", "REAL").digest();
    assertThat(query(10, "relevance", "REAL").digest()).isNotEqualTo(base);
    assertThat(query(20, "year_desc", "REAL").digest()).isNotEqualTo(base);
    assertThat(query(20, "relevance", "ALL").digest()).isNotEqualTo(base);
  }

  @Test
  @DisplayName("摘要不依赖页码：翻页时条件未变，版本必须保持一致")
  void digestIgnoresPage() {
    SearchQuery first =
        new SearchQuery(
            "graph", "graph", null, null, null, null, null, "REAL", "relevance", 1, 20, null);
    SearchQuery second =
        new SearchQuery(
            "graph", "graph", null, null, null, null, null, "REAL", "relevance", 3, 20, null);
    assertThat(first.digest()).isEqualTo(second.digest());
    // 但版本号里带发布修订，数据变化时版本必须改变。
    assertThat(first.versionFor(100L)).isNotEqualTo(first.versionFor(101L));
  }

  @Test
  @DisplayName("queryVersion 形如 q<摘要>-r<发布修订>，两段都可被调用方读出")
  void versionFormat() {
    SearchQuery q = query(20, "relevance", "REAL");
    String version = q.versionFor(12695L);
    assertThat(version).isEqualTo("q" + q.digest() + "-r12695");
  }

  @Test
  @DisplayName("偏移量由页码与每页条数算出，第 1 页偏移为 0")
  void offset() {
    assertThat(
            new SearchQuery(
                    null, null, null, null, null, null, null, "REAL", "relevance", 1, 20, null)
                .offset())
        .isZero();
    assertThat(
            new SearchQuery(
                    null, null, null, null, null, null, null, "REAL", "relevance", 4, 20, null)
                .offset())
        .isEqualTo(60);
  }

  @Test
  @DisplayName("关键词归一为空时返回 null，使「未填关键词」与「关键词为空串」在 SQL 层走同一条路径")
  void normalizeKeywordBlankToNull() {
    assertThat(SearchQuery.normalizeKeyword("  ")).isNull();
    assertThat(SearchQuery.normalizeKeyword(null)).isNull();
    assertThat(SearchQuery.normalizeKeyword("\u200b")).isNull();
  }

  @Test
  @DisplayName("关键词归一必须与投影侧 title_norm 同规则，否则会静默漏结果")
  void normalizeKeywordMatchesProjection() {
    assertThat(SearchQuery.normalizeKeyword("  Graph   Neural ")).isEqualTo("graph neural");
  }

  @Test
  @DisplayName("排序键缺省时由服务层补默认值，但白名单校验只认显式取值")
  void nullableSortAllowed() {
    assertThat(query(20, null, "REAL").sort()).isNull();
    assertThat(SearchQuery.allowedSorts()).doesNotContainNull();
  }

  @Test
  @DisplayName("列表型常量不可被调用方改写")
  void constantsImmutable() {
    List<String> sorts = SearchQuery.allowedSorts();
    assertThat(sorts).isSameAs(SearchQuery.allowedSorts());
  }

  @Test
  @DisplayName("LIKE 模式转义：用户输入的 % 与 _ 按字面处理，不会变成通配符")
  void likePatternEscapesWildcards() {
    // 不转义时 "100%" 会拼成 LIKE '%100%%'，等价于「以 100 开头的任意内容」；
    // 搜一个单独的 "%" 则直接命中全库。这类错误不报错、页面也正常，只是答案错了。
    assertThat(SearchQuery.likePatternOf("100%")).isEqualTo("100\\%");
    assertThat(SearchQuery.likePatternOf("a_b")).isEqualTo("a\\_b");
    assertThat(SearchQuery.likePatternOf("%")).isEqualTo("\\%");
    assertThat(SearchQuery.likePatternOf("_")).isEqualTo("\\_");
  }

  @Test
  @DisplayName("LIKE 模式转义：反斜杠本身要转义，普通关键词保持原样")
  void likePatternEscapesBackslashAndKeepsPlainText() {
    assertThat(SearchQuery.likePatternOf("a\\b")).isEqualTo("a\\\\b");
    assertThat(SearchQuery.likePatternOf("graph neural")).isEqualTo("graph neural");
    assertThat(SearchQuery.likePatternOf(null)).isNull();
  }

  @Test
  @DisplayName("归一化与 LIKE 转义是两件事：normalizeKeyword 保留字面，转义只发生在拼 SQL 之前")
  void normalizeAndEscapeAreSeparateSteps() {
    // 相似度计算用的是归一化值，它必须保留 % 本身；只有拼进 LIKE 模式时才转义。
    assertThat(SearchQuery.normalizeKeyword(" 100% ")).isEqualTo("100%");
    assertThat(SearchQuery.likePatternOf(SearchQuery.normalizeKeyword(" 100% ")))
        .isEqualTo("100\\%");
  }
}
