package com.cognitanexus.ingestion.collect.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wikidata 专利 SPARQL 的组装。
 *
 * <p>这里守一个曾真实发生的失效：{@code ORDER BY} 被写在内层 WHERE 闭合之后的位置，拼出来的查询
 * 是 {@code { SELECT ... WHERE { A . B } } ORDER BY ?patent LIMIT 100 OFFSET 0 }} ——修饰符落到了
 * 外层 WHERE 组里，末尾还有一个没有开括号的 {@code }}。WDQS 对这种查询只回 400，并把查询原样回显，
 * 于是日志里全是「返回 400」而没有一句「哪里不对」。当时用 curl 复现时手写的查询是正确版本，全部
 * 200，两边对不上号，多花了一轮排查。
 *
 * <p>所以测试守的不是「字符串长什么样」，而是「花括号必须配对、解算修饰符必须落在子查询里」。
 */
class WikidataPatentAdapterTest {

  @Test
  @DisplayName("全量查询的花括号必须配对")
  void queryBracesAreBalanced() {
    String query = WikidataPatentAdapter.buildQuery("", 100, 0);

    assertThat(count(query, '{')).isEqualTo(count(query, '}'));
  }

  @Test
  @DisplayName("ORDER BY 落在子查询里：在条目的 WHERE 闭合之后、子查询闭合之前")
  void orderBySitsInsideSubSelect() {
    String query = WikidataPatentAdapter.buildQuery("", 100, 0);

    int innerWhereCloses = query.indexOf("?patent wdt:P1246 ?n . }");
    int orderBy = query.indexOf("ORDER BY ?patent");
    int subSelectCloses = query.indexOf("} ?patent wdt:P1246 ?number");

    assertThat(innerWhereCloses).isGreaterThan(-1);
    assertThat(orderBy).isGreaterThan(innerWhereCloses);
    assertThat(subSelectCloses).isGreaterThan(orderBy);
  }

  @Test
  @DisplayName("关键词过滤写在子查询的 WHERE 里，不能出现在 ORDER BY 之后")
  void keywordFilterPrecedesSolutionModifiers() {
    String query = WikidataPatentAdapter.buildQuery("Laser", 50, 0);

    int filter = query.indexOf("FILTER(CONTAINS(LCASE(?kwLabel)");
    int orderBy = query.indexOf("ORDER BY ?patent");

    assertThat(filter).isGreaterThan(-1);
    assertThat(filter).isLessThan(orderBy);
    // 大小写不敏感是查询自己保证的，所以关键词在这里就先折成小写
    assertThat(query).contains("\"laser\"").doesNotContain("\"Laser\"");
  }

  @Test
  @DisplayName("关键词里的引号与反斜杠要转义，否则把查询截断成语法错误")
  void keywordIsEscapedForSparqlLiteral() {
    String query = WikidataPatentAdapter.buildQuery("a\"b\\c", 10, 0);

    assertThat(query).contains("\"a\\\"b\\\\c\"");
  }

  @Test
  @DisplayName("空白关键词不产生 FILTER，全量扫描不带多余模式")
  void blankKeywordOmitsFilter() {
    assertThat(WikidataPatentAdapter.buildQuery("   ", 10, 0)).doesNotContain("kwLabel");
    assertThat(WikidataPatentAdapter.buildQuery(null, 10, 0)).doesNotContain("kwLabel");
  }

  @Test
  @DisplayName("页大小与偏移都是正数下限：0 会被 SPARQL 当成「不返回」或负偏移")
  void pageSizeAndOffsetAreClamped() {
    String query = WikidataPatentAdapter.buildQuery("", 0, -5);

    assertThat(query).contains("LIMIT 1").contains("OFFSET 0");
  }

  @Test
  @DisplayName("空游标是第一页")
  void emptyCursorIsFirstPage() {
    assertThat(WikidataPatentAdapter.offsetOf(null)).isZero();
    assertThat(WikidataPatentAdapter.offsetOf("  ")).isZero();
  }

  @Test
  @DisplayName("游标是偏移量本身")
  void cursorIsOffset() {
    assertThat(WikidataPatentAdapter.offsetOf("100")).isEqualTo(100);
    assertThat(WikidataPatentAdapter.offsetOf(" 200 ")).isEqualTo(200);
  }

  @Test
  @DisplayName("非法游标必须报错，不能回落到 0 从头重采")
  void invalidCursorFailsLoudly() {
    // 回落到 0 会让任务一直「有产出」却永远推不到新数据，日志上还看不出异常
    assertThatThrownBy(() -> WikidataPatentAdapter.offsetOf("abc"))
        .isInstanceOf(com.cognitanexus.sharedkernel.BusinessException.class)
        .hasMessageContaining("不是合法偏移量");
    assertThatThrownBy(() -> WikidataPatentAdapter.offsetOf("-1"))
        .isInstanceOf(com.cognitanexus.sharedkernel.BusinessException.class);
  }

  private static int count(String text, char target) {
    int total = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == target) {
        total++;
      }
    }
    return total;
  }
}
