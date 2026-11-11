package com.cognitanexus.core.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.DataNature;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 引用文本渲染的行为约束。
 *
 * <p>依据：FR02、编码规范 §4.5。这里逐条钉住的是「文本长什么样」，不是「文本里包含什么关键词」：
 * 作者之间的分隔符该用 {@code and} 还是逗号、国标的年份占位怎么写，写错了都会让粘贴进论文的那段
 * 引用格式不合规范，而 contains 断言一个也拦不住。
 *
 * <p>另一条主线是「不编造」：年份未知不填当年、署名顺序未知不按姓名重排、姓名不拆。这三件事都
 * 不会让文本看起来「坏掉」，正因如此才必须用测试钉住。
 */
class CitationFormatterTest {

  private static final String ACHIEVEMENT_ID = "ach_0a1b2c3d4e5f";

  private static final String TITLE = "深度学习在医学影像中的应用";

  @Test
  @DisplayName("国标期刊论文：作者. 题名[J]. 刊名, 年. DOI.")
  void gbT7714JournalArticle() {
    String text =
        CitationFormatter.format(
            paper(2023, "中国科学: 信息科学", "10.1360/SSI-2023-0001", TWO_AUTHORS),
            CitationFormat.GB_T_7714);
    assertThat(text)
        .isEqualTo("张三, 李四. 深度学习在医学影像中的应用[J]. 中国科学: 信息科学, 2023. " + "DOI: 10.1360/SSI-2023-0001.");
  }

  @Test
  @DisplayName("国标：作者超过 3 位只列前 3 位并加「等」")
  void gbT7714TruncatesAuthors() {
    CitationSubject subject =
        paper(
            2023,
            "某刊",
            null,
            List.of(author("张三", 1), author("李四", 2), author("王五", 3), author("赵六", 4)));
    assertThat(CitationFormatter.format(subject, CitationFormat.GB_T_7714))
        .startsWith("张三, 李四, 王五, 等. ");
  }

  @Test
  @DisplayName("国标：年份未知写「出版年不详」，不填当年")
  void gbT7714UnknownYear() {
    String text =
        CitationFormatter.format(paper(null, null, null, TWO_AUTHORS), CitationFormat.GB_T_7714);
    assertThat(text).isEqualTo("张三, 李四. 深度学习在医学影像中的应用[J]. [刊名不详], [出版年不详].");
  }

  @Test
  @DisplayName("国标：专利写 [P] 与公开号，项目写 [R] 与项目号，获奖写 [Z] 与级别")
  void gbT7714OtherTypes() {
    assertThat(
            CitationFormatter.format(
                other(AchievementType.PATENT, 2022, "CN114000000A", null, null, TWO_AUTHORS),
                CitationFormat.GB_T_7714))
        .isEqualTo("张三, 李四. 深度学习在医学影像中的应用[P]. 公开号: CN114000000A, 2022.");

    assertThat(
            CitationFormatter.format(
                other(AchievementType.PROJECT, 2021, "62100001", "国家自然科学基金委员会", null, TWO_AUTHORS),
                CitationFormat.GB_T_7714))
        .isEqualTo("张三, 李四. 深度学习在医学影像中的应用[R]. 项目编号: 62100001, " + "国家自然科学基金委员会, 2021.");

    assertThat(
            CitationFormatter.format(
                other(AchievementType.AWARD, 2020, null, "教育部", "一等奖", TWO_AUTHORS),
                CitationFormat.GB_T_7714))
        .isEqualTo("张三, 李四. 深度学习在医学影像中的应用[Z]. 教育部, 一等奖, 2020.");
  }

  @Test
  @DisplayName("撤稿成果在国标文本末尾追加说明，而不是给出与正常成果无异的引用")
  void gbT7714Retracted() {
    CitationSubject subject =
        withIntegrity(paper(2023, "某刊", "10.1/x", TWO_AUTHORS), "RETRACTION", "已撤稿");
    assertThat(CitationFormatter.format(subject, CitationFormat.GB_T_7714))
        .endsWith("注：平台登记有已撤稿，引用前请核对原文.");
  }

  @Test
  @DisplayName("BibTeX：@article 字段齐全，作者以 and 连接")
  void bibtexArticle() {
    String text =
        CitationFormatter.format(
            paper(2023, "中国科学: 信息科学", "10.1360/SSI-2023-0001", TWO_AUTHORS), CitationFormat.BIBTEX);
    assertThat(text)
        .isEqualTo(
            "@article{anon2023item_3d4e5f,\n"
                + "  author = {张三 and 李四},\n"
                + "  title = {深度学习在医学影像中的应用},\n"
                + "  journal = {中国科学: 信息科学},\n"
                + "  year = {2023},\n"
                + "  doi = {10.1360/SSI-2023-0001}\n"
                + "}\n");
  }

  @Test
  @DisplayName("BibTeX：十个语法字符逐个转义，反斜杠不被二次转义，花括号保持平衡")
  void bibtexEscapesSpecialCharacters() {
    CitationSubject subject =
        copyOf(
            paper(2023, null, null, TWO_AUTHORS),
            ACHIEVEMENT_ID,
            "A & B: 50% of $5 #tag_1 {x} ~ ^ \\ end",
            TWO_AUTHORS);
    String text = CitationFormatter.format(subject, CitationFormat.BIBTEX);
    assertThat(text)
        .contains(
            "title = {A \\& B: 50\\% of \\$5 \\#tag\\_1 \\{x\\} \\textasciitilde{} "
                + "\\textasciicircum{} \\textbackslash{} end}");
    assertThat(countChar(text, '{')).isEqualTo(countChar(text, '}'));
  }

  @Test
  @DisplayName("BibTeX：年份未知省略 year 字段，不写 n.d. 或 0")
  void bibtexOmitsUnknownYear() {
    String text =
        CitationFormatter.format(paper(null, "某刊", null, TWO_AUTHORS), CitationFormat.BIBTEX);
    assertThat(text).doesNotContain("year =");
  }

  @Test
  @DisplayName("BibTeX：专利与项目用 @misc，不用纯 BibTeX 不认的 @patent")
  void bibtexNonPaperUsesMisc() {
    String patent =
        CitationFormatter.format(
            other(AchievementType.PATENT, 2022, "CN114000000A", "某公司", null, TWO_AUTHORS),
            CitationFormat.BIBTEX);
    assertThat(patent).startsWith("@misc{");
    assertThat(patent).contains("number = {CN114000000A}");
    assertThat(patent).contains("assignee = {某公司}");
    assertThat(patent).contains("note = {专利}");
    assertThat(
            CitationFormatter.format(paper(2023, "某刊", null, TWO_AUTHORS), CitationFormat.BIBTEX))
        .startsWith("@article{");
  }

  @Test
  @DisplayName("RIS：逐行标签正确，以 ER 结尾，值里的换行被压成空格")
  void risStructure() {
    CitationSubject subject =
        copyOf(paper(2023, "某刊", "10.1/x", TWO_AUTHORS), ACHIEVEMENT_ID, "第一行\n第二行", TWO_AUTHORS);
    assertThat(CitationFormatter.format(subject, CitationFormat.RIS))
        .isEqualTo(
            "TY  - JOUR\n"
                + "AU  - 张三\n"
                + "AU  - 李四\n"
                + "TI  - 第一行 第二行\n"
                + "PY  - 2023\n"
                + "JO  - 某刊\n"
                + "DO  - 10.1/x\n"
                + "ER  - \n");
  }

  @Test
  @DisplayName("RIS：撤稿与缺年份都写进 N1，且不给出假的 PY")
  void risNotes() {
    CitationSubject subject =
        withIntegrity(paper(null, "某刊", null, TWO_AUTHORS), "RETRACTION", "已撤稿");
    String text = CitationFormatter.format(subject, CitationFormat.RIS);
    assertThat(text).contains("N1  - 来源未提供年份; 平台登记有已撤稿，引用前请核对原文\n");
    assertThat(text).doesNotContain("PY  - ");
  }

  @Test
  @DisplayName("APA：双作者用 , & 连接，年份未知写 n.d.")
  void apaTwoAuthors() {
    assertThat(
            CitationFormatter.format(paper(2023, "某刊", "10.1/x", TWO_AUTHORS), CitationFormat.APA))
        .isEqualTo("张三, & 李四. (2023). 深度学习在医学影像中的应用. 某刊. https://doi.org/10.1/x");

    assertThat(CitationFormatter.format(paper(null, null, null, TWO_AUTHORS), CitationFormat.APA))
        .isEqualTo("张三, & 李四. (n.d.). 深度学习在医学影像中的应用.");
  }

  @Test
  @DisplayName("APA：DOI 已有 https://doi.org 前缀时不重复拼接")
  void apaDoesNotDoubleDoiPrefix() {
    String text =
        CitationFormatter.format(
            paper(2023, null, "https://doi.org/10.1360/ABC", TWO_AUTHORS), CitationFormat.APA);
    assertThat(text).endsWith("https://doi.org/10.1360/ABC");
  }

  @Test
  @DisplayName("APA 的姓名拆分简化只作为该格式的告警，不污染其它格式")
  void apaWarnsAboutNameSplit() {
    CitationSubject subject = paper(2023, "某刊", null, TWO_AUTHORS);
    assertThat(CitationFormatter.formatWarnings(subject, CitationFormat.APA))
        .extracting(CitationWarning::code)
        .containsExactly(CitationWarning.NAME_SPLIT_NOT_APPLIED);
    assertThat(CitationFormatter.formatWarnings(subject, CitationFormat.GB_T_7714)).isEmpty();
    assertThat(CitationFormatter.formatWarnings(subject, CitationFormat.BIBTEX)).isEmpty();
  }

  @Test
  @DisplayName("引用键稳定、只含安全字符，且同作者同年同名但不同成果时仍不同")
  void citeKeyIsStableAndCollisionFree() {
    CitationSubject first = paper(2023, "某刊", null, TWO_AUTHORS);
    assertThat(CitationFormatter.citeKey(first)).isEqualTo("anon2023item_3d4e5f");
    assertThat(CitationFormatter.citeKey(first)).isEqualTo(CitationFormatter.citeKey(first));

    CitationSubject other = copyOf(first, "ach_ffeeddccbbaa", TITLE, TWO_AUTHORS);
    assertThat(CitationFormatter.citeKey(other)).matches("[a-z0-9_]+");
    assertThat(CitationFormatter.citeKey(other)).isNotEqualTo(CitationFormatter.citeKey(first));
  }

  @Test
  @DisplayName("英文作者名的引用键取 ASCII 折叠后的前缀")
  void citeKeyForLatinName() {
    CitationSubject subject =
        new CitationSubject(
            ACHIEVEMENT_ID,
            AchievementType.PAPER,
            "Deep Learning for Medical Imaging",
            2023,
            "Nature",
            null,
            null,
            null,
            null,
            List.of(author("Zhang San", 1)),
            "NONE",
            "无诚信声明",
            DataNature.REAL);
    assertThat(CitationFormatter.citeKey(subject)).isEqualTo("zhangsan2023deeplearni_3d4e5f");
  }

  @Test
  @DisplayName("格式解析只容忍大小写与连字符，不接受缩写")
  void formatParsing() {
    assertThat(CitationFormat.of("bibtex")).isEqualTo(CitationFormat.BIBTEX);
    assertThat(CitationFormat.of("BIBTEX")).isEqualTo(CitationFormat.BIBTEX);
    assertThat(CitationFormat.of("gbt7714")).isEqualTo(CitationFormat.GB_T_7714);
    assertThat(CitationFormat.of("gb-t-7714")).isEqualTo(CitationFormat.GB_T_7714);
    assertThat(CitationFormat.of("apa")).isEqualTo(CitationFormat.APA);
    assertThatThrownBy(() -> CitationFormat.of("bib")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CitationFormat.of("  ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CitationFormat.of(null)).isInstanceOf(IllegalArgumentException.class);
    assertThat(CitationFormat.codes()).containsExactly("bibtex", "ris", "gb_t_7714", "apa");
  }

  // ------------------------------------------------------------------ 夹具

  private static final List<CitationAuthor> TWO_AUTHORS = List.of(author("张三", 1), author("李四", 2));

  private static CitationSubject paper(
      Integer year, String venue, String doi, List<CitationAuthor> authors) {
    return new CitationSubject(
        ACHIEVEMENT_ID,
        AchievementType.PAPER,
        TITLE,
        year,
        venue,
        doi,
        null,
        null,
        null,
        authors,
        "NONE",
        "无诚信声明",
        DataNature.REAL);
  }

  private static CitationSubject other(
      AchievementType type,
      Integer year,
      String number,
      String issuingBody,
      String awardLevel,
      List<CitationAuthor> authors) {
    return new CitationSubject(
        ACHIEVEMENT_ID,
        type,
        TITLE,
        year,
        null,
        null,
        number,
        issuingBody,
        awardLevel,
        authors,
        "NONE",
        "无诚信声明",
        DataNature.REAL);
  }

  private static CitationSubject withIntegrity(
      CitationSubject subject, String status, String label) {
    return new CitationSubject(
        subject.achievementId(),
        subject.type(),
        subject.title(),
        subject.year(),
        subject.venue(),
        subject.doi(),
        subject.number(),
        subject.issuingBody(),
        subject.awardLevel(),
        subject.authors(),
        status,
        label,
        subject.dataNature());
  }

  private static CitationSubject copyOf(
      CitationSubject subject, String id, String title, List<CitationAuthor> authors) {
    return new CitationSubject(
        id,
        subject.type(),
        title,
        subject.year(),
        subject.venue(),
        subject.doi(),
        subject.number(),
        subject.issuingBody(),
        subject.awardLevel(),
        authors,
        subject.integrityStatus(),
        subject.integrityLabel(),
        subject.dataNature());
  }

  private static CitationAuthor author(String name, Integer order) {
    return new CitationAuthor(name, order, "AUTO");
  }

  private static int countChar(String text, char target) {
    int count = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == target) {
        count++;
      }
    }
    return count;
  }
}
