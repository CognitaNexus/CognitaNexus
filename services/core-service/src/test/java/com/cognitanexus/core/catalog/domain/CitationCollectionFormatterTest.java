package com.cognitanexus.core.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.DataNature;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 文献集合文件的两条行为约束：文件内引用键唯一、四个格式的成篇方式。
 *
 * <p>为什么这两件事必须单独钉：它们都不产生「看起来坏掉」的结果。BibTeX 撞键是静默覆盖——文件能编译、
 * 参考文献表少一条；国标不编号或 APA 加了编号，也都能读，只是不能直接交上去。靠肉眼看不出来的东西
 * 只能靠断言。
 */
class CitationCollectionFormatterTest {

  private static final String TITLE = "深度学习在医学影像中的应用";

  @Test
  @DisplayName("不同成果的键本来就不同时，全部保持原样")
  void keepsDistinctKeys() {
    List<CitationSubject> subjects = List.of(paper("id-aaaaaa111111"), paper("id-bbbbbb222222"));
    List<String> keys = CitationCollectionFormatter.uniqueCiteKeys(subjects);

    assertThat(keys).hasSize(2).doesNotHaveDuplicates();
    assertThat(keys.get(0)).isEqualTo(CitationFormatter.citeKey(subjects.get(0)));
    assertThat(keys.get(1)).isEqualTo(CitationFormatter.citeKey(subjects.get(1)));
  }

  @Test
  @DisplayName("同一成果重复出现：第一个保留原键，第二个追加 a")
  void disambiguatesDuplicateKeys() {
    CitationSubject subject = paper("id-aaaaaa111111");
    List<String> keys = CitationCollectionFormatter.uniqueCiteKeys(List.of(subject, subject));

    String base = CitationFormatter.citeKey(subject);
    assertThat(keys).containsExactly(base, base + "a");
  }

  @Test
  @DisplayName("键的后缀只由出现顺序决定：同一输入两次得到同一份键")
  void keyAssignmentIsDeterministic() {
    List<CitationSubject> subjects = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      subjects.add(paper("id-aaaaaa111111"));
    }
    assertThat(CitationCollectionFormatter.uniqueCiteKeys(subjects))
        .isEqualTo(CitationCollectionFormatter.uniqueCiteKeys(subjects));
  }

  @Test
  @DisplayName("超过 26 个重复键时后缀走 aa、ab，不会撞回单字母")
  void suffixBeyondAlphabet() {
    List<CitationSubject> subjects = new ArrayList<>();
    for (int i = 0; i < 28; i++) {
      subjects.add(paper("id-aaaaaa111111"));
    }
    List<String> keys = CitationCollectionFormatter.uniqueCiteKeys(subjects);
    String base = CitationFormatter.citeKey(subjects.get(0));

    assertThat(keys).hasSize(28).doesNotHaveDuplicates();
    assertThat(keys.get(0)).isEqualTo(base);
    assertThat(keys.get(1)).isEqualTo(base + "a");
    assertThat(keys.get(26)).isEqualTo(base + "z");
    assertThat(keys.get(27)).isEqualTo(base + "aa");
  }

  @Test
  @DisplayName("BibTeX 成篇：条目之间空一行，且每条与单条导出逐字一致")
  void bibtexDocument() {
    List<CitationSubject> subjects = List.of(paper("id-aaaaaa111111"), paper("id-bbbbbb222222"));
    List<String> keys = CitationCollectionFormatter.uniqueCiteKeys(subjects);
    List<String> texts = new ArrayList<>();
    for (int i = 0; i < subjects.size(); i++) {
      texts.add(CitationFormatter.format(subjects.get(i), CitationFormat.BIBTEX, keys.get(i)));
    }

    String document = CitationCollectionFormatter.document(texts, CitationFormat.BIBTEX);

    // 空行是 BibTeX 条目之间唯一安全的间隔：没有空行时部分工具会把下一条当成上一条的续行。
    assertThat(document).contains("}\n\n@");
    assertThat(document).startsWith("@article{" + keys.get(0));
    assertThat(document).endsWith("}\n");
    assertThat(document).contains(texts.get(0));
    assertThat(document).contains(texts.get(1));
  }

  @Test
  @DisplayName("RIS 成篇：每条以 ER 结束，条目之间空一行")
  void risDocument() {
    CitationSubject subject = paper("id-aaaaaa111111");
    String text = CitationFormatter.format(subject, CitationFormat.RIS);

    String document = CitationCollectionFormatter.document(List.of(text, text), CitationFormat.RIS);

    assertThat(document).isEqualTo(text + "\n" + text);
    assertThat(document).contains("ER  - \n\nTY  - JOUR");
  }

  @Test
  @DisplayName("国标成篇：逐条编号 [1] [2]，编号不属于条目本身")
  void gbDocumentIsNumbered() {
    List<String> texts =
        List.of(
            CitationFormatter.format(paper("id-aaaaaa111111"), CitationFormat.GB_T_7714),
            CitationFormatter.format(paper("id-bbbbbb222222"), CitationFormat.GB_T_7714));

    String document = CitationCollectionFormatter.document(texts, CitationFormat.GB_T_7714);

    assertThat(document).startsWith("[1] " + texts.get(0));
    assertThat(document).contains("\n[2] " + texts.get(1));
    assertThat(countOccurrences(document, "[1]")).isEqualTo(1);
  }

  @Test
  @DisplayName("APA 成篇：段落之间空行，不编号")
  void apaDocumentIsNotNumbered() {
    String text = CitationFormatter.format(paper("id-aaaaaa111111"), CitationFormat.APA);

    String document = CitationCollectionFormatter.document(List.of(text, text), CitationFormat.APA);

    assertThat(document).isEqualTo(text + "\n\n" + text);
    assertThat(document).doesNotContain("[1]");
  }

  @Test
  @DisplayName("空集合得到空文本，不产生只有编号的空壳")
  void emptyDocument() {
    assertThat(CitationCollectionFormatter.document(List.of(), CitationFormat.BIBTEX)).isEmpty();
    assertThat(CitationCollectionFormatter.document(List.of("  ", ""), CitationFormat.GB_T_7714))
        .isEmpty();
    assertThat(CitationCollectionFormatter.uniqueCiteKeys(List.of())).isEmpty();
  }

  @Test
  @DisplayName("指定键只影响 BibTeX：其余格式不使用引用键")
  void borrowedKeyOnlyAffectsBibtex() {
    CitationSubject subject = paper("id-aaaaaa111111");

    assertThat(CitationFormatter.format(subject, CitationFormat.BIBTEX, "picked_key_2023"))
        .startsWith("@article{picked_key_2023,");
    assertThat(CitationFormatter.format(subject, CitationFormat.GB_T_7714, "picked_key_2023"))
        .isEqualTo(CitationFormatter.format(subject, CitationFormat.GB_T_7714));
    // 传空键时回落到按成果元数据计算的键，不会写出 {@code @article{,}
    assertThat(CitationFormatter.format(subject, CitationFormat.BIBTEX, " "))
        .startsWith("@article{" + CitationFormatter.citeKey(subject) + ",");
  }

  // ------------------------------------------------------------------ 夹具

  private static CitationSubject paper(String achievementId) {
    return new CitationSubject(
        achievementId,
        AchievementType.PAPER,
        TITLE,
        2023,
        "中国科学: 信息科学",
        "10.1360/SSI-2023-0001",
        null,
        null,
        null,
        List.of(new CitationAuthor("张三", 1, "AUTO")),
        "NONE",
        "无诚信声明",
        DataNature.REAL);
  }

  private static int countOccurrences(String text, String token) {
    int count = 0;
    int from = 0;
    while (true) {
      int index = text.indexOf(token, from);
      if (index < 0) {
        return count;
      }
      count++;
      from = index + token.length();
    }
  }
}
