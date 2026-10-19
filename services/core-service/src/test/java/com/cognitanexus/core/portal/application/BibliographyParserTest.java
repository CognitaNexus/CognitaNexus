package com.cognitanexus.core.portal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.core.portal.application.BibliographyParser.Entry;
import com.cognitanexus.core.portal.application.BibliographyParser.Format;
import com.cognitanexus.core.portal.application.BibliographyParser.ParseResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BibTeX / RIS 解析器的单元测试。
 *
 * <p>测试数据取自真实的导出格式，不是编出来的玩具样例：嵌套花括号、LaTeX 重音、缺 ER 的 RIS、
 * 引号包裹的 value 都是实际文件里会出现的写法。解析器最危险的失败不是抛异常，而是「静默截断
 * 标题」——那种错误在页面上看起来像一条正常但内容残缺的成果。
 */
class BibliographyParserTest {

  @Test
  @DisplayName("典型 BibTeX：嵌套花括号、DOI 前缀、年份都正确归一")
  void parsesTypicalArticle() {
    String bib =
        """
        @article{smith2020,
          title = {Deep Learning for {Protein} Structure Prediction},
          author = {Smith, John and Doe, Jane},
          journal = {Nature},
          year = {2020},
          doi = {https://doi.org/10.1038/s41586-020-1234-5}
        }
        """;
    ParseResult result = BibliographyParser.parse(bib, Format.BIBTEX);
    assertThat(result.entries()).hasSize(1);
    Entry entry = result.entries().get(0);
    assertThat(entry.title()).isEqualTo("Deep Learning for Protein Structure Prediction");
    assertThat(entry.authorsRaw()).isEqualTo("Smith, John and Doe, Jane");
    assertThat(entry.venue()).isEqualTo("Nature");
    assertThat(entry.year()).isEqualTo(2020);
    assertThat(entry.doi()).isEqualTo("10.1038/s41586-020-1234-5");
    assertThat(entry.supported()).isTrue();
    assertThat(entry.citationKey()).isEqualTo("smith2020");
  }

  @Test
  @DisplayName("标题里有逗号与嵌套花括号时不被截断")
  void keepsCommaInsideBraces() {
    String bib =
        """
        @inproceedings{key1,
          title = {A Study of {Alpha, Beta}, and {Gamma}},
          booktitle = {Proceedings of the {XYZ} Conference},
          year = 2019
        }
        """;
    Entry entry = BibliographyParser.parse(bib, Format.BIBTEX).entries().get(0);
    assertThat(entry.title()).isEqualTo("A Study of Alpha, Beta, and Gamma");
    assertThat(entry.venue()).isEqualTo("Proceedings of the XYZ Conference");
    assertThat(entry.year()).isEqualTo(2019);
  }

  @Test
  @DisplayName("引号包裹的 value 与花括号两种写法都支持")
  void parsesQuotedValues() {
    String bib =
        """
        @article{k2,
          title = "Quoted Title With, Comma",
          journal = "Journal of Tests",
          year = "2021"
        }
        """;
    Entry entry = BibliographyParser.parse(bib, Format.BIBTEX).entries().get(0);
    assertThat(entry.title()).isEqualTo("Quoted Title With, Comma");
    assertThat(entry.venue()).isEqualTo("Journal of Tests");
    assertThat(entry.year()).isEqualTo(2021);
  }

  @Test
  @DisplayName("@comment 与 @string 不当作文献条目，但要给出去向说明")
  void skipsNonEntryBlocks() {
    String bib =
        """
        @comment{this is a comment with @article{nested, title={x}}}
        @string{nature = "Nature"}
        @article{real1, title = {Real Entry}, year = 2022}
        """;
    ParseResult result = BibliographyParser.parse(bib, Format.BIBTEX);
    assertThat(result.entries()).hasSize(1);
    assertThat(result.entries().get(0).title()).isEqualTo("Real Entry");
    assertThat(result.skipped()).anyMatch(s -> s.contains("@string"));
  }

  @Test
  @DisplayName("没有 title 的条目跳过并说明原因，不影响同批其他条目")
  void skipsEntryWithoutTitle() {
    String bib =
        """
        @article{broken, author = {Nobody}, year = 2020}
        @article{good, title = {Good One}, year = 2021}
        """;
    ParseResult result = BibliographyParser.parse(bib, Format.BIBTEX);
    assertThat(result.entries()).hasSize(1);
    assertThat(result.entries().get(0).title()).isEqualTo("Good One");
    assertThat(result.skipped()).anyMatch(s -> s.contains("broken") && s.contains("title"));
  }

  @Test
  @DisplayName("LaTeX 重音与转义被还原成可读文本")
  void cleansLatexAccents() {
    String bib =
        """
        @article{k3,
          title = {Caf{\\'e} na{\\"i}ve and A \\& B},
          journal = {Journal {\\"O}sterreich},
          year = 2018
        }
        """;
    Entry entry = BibliographyParser.parse(bib, Format.BIBTEX).entries().get(0);
    assertThat(entry.title()).isEqualTo("Café naïve and A & B");
    assertThat(entry.venue()).isEqualTo("Journal Österreich");
  }

  @Test
  @DisplayName("专利类型标记为不支持，但仍保留在结果里让用户看到为什么")
  void marksPatentUnsupported() {
    String bib =
        """
        @patent{pat1,
          title = {A Method of Doing Things},
          year = 2021
        }
        """;
    Entry entry = BibliographyParser.parse(bib, Format.BIBTEX).entries().get(0);
    assertThat(entry.supported()).isFalse();
    assertThat(entry.warnings()).anyMatch(w -> w.contains("专利"));
  }

  @Test
  @DisplayName("未识别类型按论文处理并给出警告")
  void unknownTypeDefaultsToPaperWithWarning() {
    String bib = "@dataset{ds1, title = {Some Dataset}, year = 2023}";
    Entry entry = BibliographyParser.parse(bib, Format.BIBTEX).entries().get(0);
    assertThat(entry.supported()).isTrue();
    assertThat(entry.warnings()).anyMatch(w -> w.contains("未识别"));
  }

  @Test
  @DisplayName("典型 RIS：多个 AU、PY、JO、DO 正确归并")
  void parsesRisRecord() {
    String ris =
        """
        TY  - JOUR
        TI  - Attention Is All You Need
        AU  - Vaswani, Ashish
        AU  - Shazeer, Noam
        PY  - 2017
        JO  - Advances in Neural Information Processing Systems
        DO  - 10.5555/3295222.3295349
        ER  -
        """;
    ParseResult result = BibliographyParser.parse(ris, Format.RIS);
    assertThat(result.format()).isEqualTo(Format.RIS);
    assertThat(result.entries()).hasSize(1);
    Entry entry = result.entries().get(0);
    assertThat(entry.title()).isEqualTo("Attention Is All You Need");
    assertThat(entry.authorsRaw()).isEqualTo("Vaswani, Ashish; Shazeer, Noam");
    assertThat(entry.year()).isEqualTo(2017);
    assertThat(entry.doi()).isEqualTo("10.5555/3295222.3295349");
  }

  @Test
  @DisplayName("RIS 的缩进续行拼回同一个字段，不丢摘要后半段")
  void joinsRisContinuationLines() {
    String ris =
        """
        TY  - JOUR
        TI  - Title Here
        AB  - First part of the abstract
          and the second line continues here.
        ER  -
        """;
    Entry entry = BibliographyParser.parse(ris, Format.RIS).entries().get(0);
    assertThat(entry.abstractText())
        .isEqualTo("First part of the abstract and the second line continues here.");
  }

  @Test
  @DisplayName("缺 ER 的 RIS 也要收下最后一条")
  void collectsTrailingRecordWithoutEr() {
    String ris =
        """
        TY  - JOUR
        TI  - No Terminator
        PY  - 2020
        """;
    ParseResult result = BibliographyParser.parse(ris, Format.RIS);
    assertThat(result.entries()).hasSize(1);
    assertThat(result.entries().get(0).title()).isEqualTo("No Terminator");
  }

  @Test
  @DisplayName("专利 RIS 记录标记为不支持")
  void marksRisPatentUnsupported() {
    String ris =
        """
        TY  - PAT
        TI  - A Patent
        ER  -
        """;
    Entry entry = BibliographyParser.parse(ris, Format.RIS).entries().get(0);
    assertThat(entry.supported()).isFalse();
  }

  @Test
  @DisplayName("按内容自动识别格式")
  void detectsFormat() {
    assertThat(BibliographyParser.detect("@article{k, title={T}}")).isEqualTo(Format.BIBTEX);
    assertThat(BibliographyParser.detect("TY  - JOUR\nTI  - T\nER  -")).isEqualTo(Format.RIS);
  }

  @Test
  @DisplayName("摘要超过上限的条目跳过而不是静默截断")
  void skipsOversizedAbstract() {
    String longAbstract = "x".repeat(5001);
    String bib =
        "@article{big, title = {Big Abstract}, year = 2020, abstract = {" + longAbstract + "}}";
    ParseResult result = BibliographyParser.parse(bib, Format.BIBTEX);
    assertThat(result.entries()).isEmpty();
    assertThat(result.skipped()).anyMatch(s -> s.contains("摘要"));
  }

  @Test
  @DisplayName("条目数超过单次上限时截断并说明")
  void capsEntryCount() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < BibliographyParser.MAX_ENTRIES + 5; i++) {
      sb.append("@article{k")
          .append(i)
          .append(", title = {Title ")
          .append(i)
          .append("}, year = 2020}\n");
    }
    ParseResult result = BibliographyParser.parse(sb.toString(), Format.BIBTEX);
    assertThat(result.entries()).hasSize(BibliographyParser.MAX_ENTRIES);
    assertThat(result.skipped()).anyMatch(s -> s.contains("上限"));
  }

  @Test
  @DisplayName("空内容返回零条而不是抛异常")
  void emptyContentYieldsNothing() {
    ParseResult result = BibliographyParser.parse("", Format.BIBTEX);
    assertThat(result.entries()).isEmpty();
    assertThat(result.skipped()).isEmpty();
  }

  @Test
  @DisplayName("括号不闭合时记录说明并停止，不产生半条数据")
  void unbalancedBracesStopsCleanly() {
    String bib = "@article{bad, title = {Never Closed";
    ParseResult result = BibliographyParser.parse(bib, Format.BIBTEX);
    assertThat(result.entries()).isEmpty();
    assertThat(result.skipped()).anyMatch(s -> s.contains("闭合"));
  }

  @Test
  @DisplayName("同一字段重复出现时取第一次，不拼接")
  void keepsFirstOccurrenceOfDuplicatedField() {
    String bib = "@article{dup, title = {First}, title = {Second}, year = 2020}";
    Entry entry = BibliographyParser.parse(bib, Format.BIBTEX).entries().get(0);
    assertThat(entry.title()).isEqualTo("First");
  }

  @Test
  @DisplayName("年份缺失时留空，不编造")
  void missingYearStaysNull() {
    String bib = "@article{noyear, title = {No Year}, year = {in press}}";
    Entry entry = BibliographyParser.parse(bib, Format.BIBTEX).entries().get(0);
    assertThat(entry.year()).isNull();
    assertThat(entry.warnings()).anyMatch(w -> w.contains("年份"));
  }

  @Test
  @DisplayName("作者字段超长时截断并标注，不静默丢弃整条")
  void truncatesOversizedAuthors() {
    String manyAuthors = String.join(" and ", List.of("A".repeat(600), "B".repeat(600)));
    String bib = "@article{many, title = {Many Authors}, author = {" + manyAuthors + "}}";
    Entry entry = BibliographyParser.parse(bib, Format.BIBTEX).entries().get(0);
    assertThat(entry.authorsRaw().length()).isEqualTo(1000);
    assertThat(entry.warnings()).anyMatch(w -> w.contains("截断"));
  }
}
