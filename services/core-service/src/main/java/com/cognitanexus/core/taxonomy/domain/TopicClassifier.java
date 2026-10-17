package com.cognitanexus.core.taxonomy.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 领域规则分类器。
 *
 * <p>纯函数：给定文本与词表，输出确定的结果。没有时间、没有随机、没有外部调用，因此同一版本
 * 词表对同一篇成果永远得到同一条结论——这是「可复现」的全部含义，也是这批派生数据能被复查的
 * 前提。
 *
 * <p>三个设计选择及其理由：
 *
 * <ul>
 *   <li><b>题名与期刊同权，摘要折半。</b>题名与期刊名是论文自己声明的主题归属，摘要里出现某个
 *       术语往往只是顺带提及（一篇做图神经网络的论文完全可能在摘要里写到「蛋白质」）。
 *   <li><b>领域专属词权重高、通用词权重低。</b>「metal-organic framework」几乎只出现在材料学
 *       论文里，「algorithm」到处都是。靠权重而不是靠词表长短来区分，才能让多学科论文的排序
 *       符合直觉。
 *   <li><b>达不到阈值就什么都不输出。</b>SRS 5.3 明确「无法可靠映射时标记待分类，不强行归类」。
 *       返回空列表就是如实回答「不知道」；给它随便挑一个分数最高的领域，等于把猜测当结论写进
 *       了检索分面。
 * </ul>
 *
 * <p>打分与取舍刻意分成两步（{@link #rank} 与 {@link #classify}）：只保留过阈值的结论，就无从
 * 回答「这条为什么判不出」——而待分类清单恰恰需要这个回答。因此底层排序结果对外可见，阈值只
 * 决定「采信哪几条」。
 */
public final class TopicClassifier {

  /** 证据串里每个领域最多列出的命中项数。证据是给人看的，不是把整篇文本回填一遍。 */
  private static final int MAX_EVIDENCE_HITS = 8;

  /** 置信度的饱和常数：得分越高越接近 1，但永不等于 1（规则匹配不产生确定性结论）。 */
  private static final double STRENGTH_SATURATION = 3.0;

  private final AcademicTaxonomy taxonomy;

  public TopicClassifier(AcademicTaxonomy taxonomy) {
    this.taxonomy = taxonomy;
  }

  public AcademicTaxonomy taxonomy() {
    return taxonomy;
  }

  /**
   * 打分排序结果，不裁阈值。
   *
   * @param fieldName 领域规范名
   * @param score 加权得分
   * @param evidence 命中明细
   */
  public record Candidate(String fieldName, double score, String evidence) {}

  /**
   * 对全部有命中的领域打分并排序。
   *
   * <p>排序在得分相同时按领域名升序：并列时的顺序必须由数据决定，不能由词表书写顺序决定，
   * 否则往词表中间插一个领域就会悄悄改变既有成果的分类结果。
   *
   * <p>题名与期刊分开规范化后再匹配，而不是拼成一段文本再匹配。差别只在证据上：拼成一段时无法
   * 区分命中来自题名还是期刊，证据只能笼统写「title」——实测中就出现过证据写着题名命中、
   * 而那个词其实来自期刊名的情况。证据是复核分类的依据，写错位置等于毁掉追溯链。
   */
  public List<Candidate> rank(String title, String venue, String abstractText) {
    String titleText = MatchText.normalize(title);
    String venueText = MatchText.normalize(venue);
    String body = MatchText.normalize(abstractText);
    boolean titleEmpty = titleText.isBlank();
    boolean venueEmpty = venueText.isBlank();
    boolean bodyEmpty = body.isBlank();
    if (titleEmpty && venueEmpty && bodyEmpty) {
      return List.of();
    }

    List<Candidate> ranked = new ArrayList<>();
    for (AcademicTaxonomy.Field field : taxonomy.fields()) {
      double score = 0;
      List<Hit> hits = new ArrayList<>();
      for (AcademicTaxonomy.Term term : field.terms()) {
        String needle = term.needle();
        // 题名与期刊同权：两者都是论文自己声明的主题归属。同一条判别词在一次分类里只贡献一次得分，
        // 因此多处的出现不会把得分叠上去。
        boolean inTitle = !titleEmpty && titleText.contains(needle);
        boolean inVenue = !inTitle && !venueEmpty && venueText.contains(needle);
        boolean declared = inTitle || inVenue;
        if (!declared && (bodyEmpty || !body.contains(needle))) {
          continue;
        }
        double effective =
            term.weight() * (declared ? taxonomy.declaredWeight() : taxonomy.abstractWeight());
        score += effective;
        hits.add(new Hit(inTitle ? "title" : inVenue ? "venue" : "abstract", term.text(), effective));
      }
      if (score > 0) {
        ranked.add(new Candidate(field.canonicalName(), round(score), evidence(hits)));
      }
    }
    ranked.sort(
        Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Candidate::fieldName));
    return List.copyOf(ranked);
  }

  /**
   * 推断领域。
   *
   * @param title 成果题名，可为空
   * @param venue 发表期刊或会议，可为空；与题名同权
   * @param abstractText 摘要，可为空；命中按词表的 abstractWeight 折减
   * @return 0 到 2 条归属，按得分降序；无法可靠判定时返回空列表
   */
  public List<TopicClassification> classify(String title, String venue, String abstractText) {
    List<Candidate> ranked = rank(title, venue, abstractText);
    if (ranked.isEmpty()) {
      return List.of();
    }
    Candidate top = ranked.get(0);
    if (top.score() < taxonomy.minScore()) {
      // 证据不足：宁可返回空，也不输出一条自己都不信的归属。
      return List.of();
    }
    double runnerUp = ranked.size() > 1 ? ranked.get(1).score() : 0.0;

    List<TopicClassification> result = new ArrayList<>();
    result.add(toClassification(top, runnerUp));
    double secondaryFloor = Math.max(taxonomy.minScore(), top.score() * taxonomy.secondaryRatio());
    if (ranked.size() > 1 && runnerUp >= secondaryFloor) {
      // 次位领域的「对手」是第一名，因此它的置信度天然低于首位——这正是想表达的：
      // 同一篇成果上的第二个领域，证据总是弱于第一个。
      result.add(toClassification(ranked.get(1), top.score()));
    }
    return List.copyOf(result);
  }

  private TopicClassification toClassification(Candidate candidate, double other) {
    double strength = candidate.score() / (candidate.score() + STRENGTH_SATURATION);
    // 首位优势：与最强的对手差距越大越可信。取首位得分作为对手上界，避免「第二名的对手只有
    // 第三名」这种自欺式的高优势。
    double dominance = candidate.score() / (candidate.score() + other);
    double confidence = round(Math.min(0.99, strength * (0.5 + 0.5 * dominance)));
    return new TopicClassification(
        candidate.fieldName(), candidate.score(), confidence, candidate.evidence());
  }

  private static String evidence(List<Hit> hits) {
    List<Hit> ordered = new ArrayList<>(hits);
    // 证据按贡献降序，截断时留下的是最有分量的那几条。
    ordered.sort(
        Comparator.comparingDouble(Hit::effectiveWeight).reversed().thenComparing(Hit::term));
    StringBuilder builder = new StringBuilder("命中 ").append(ordered.size()).append(" 项：");
    int shown = Math.min(ordered.size(), MAX_EVIDENCE_HITS);
    for (int i = 0; i < shown; i++) {
      if (i > 0) {
        builder.append(", ");
      }
      Hit hit = ordered.get(i);
      builder
          .append(hit.where())
          .append(':')
          .append(hit.term())
          .append('=')
          .append(hit.effectiveWeight());
    }
    if (ordered.size() > shown) {
      builder.append(" …（另有 ").append(ordered.size() - shown).append(" 项）");
    }
    return builder.toString();
  }

  private static double round(double value) {
    return Math.round(value * 1000.0) / 1000.0;
  }

  private record Hit(String where, String term, double effectiveWeight) {}
}
