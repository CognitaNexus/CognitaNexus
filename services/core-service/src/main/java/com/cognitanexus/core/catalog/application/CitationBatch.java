package com.cognitanexus.core.catalog.application;

import com.cognitanexus.core.catalog.domain.CitationFormat;
import com.cognitanexus.core.catalog.domain.CitationWarning;
import java.util.List;

/**
 * 一次批量导出的结果：一份文件，加上「这份文件少说明了什么」。
 *
 * <p>与单条 {@link CitationBundle} 的差别不在数据量，而在<b>必须交代两种单条不存在的情况</b>：
 *
 * <ol>
 *   <li>{@code skipped}：选中的成果里有不可公开的（已下架、未生效、被合并到别处、或者干脆不存在）。
 *       批量导出不能因为其中一条不可见就整批 500，也不能悄悄少给一条——读者看到「导出 4 条」而自己
 *       选了 6 条，会以为是自己漏点了。<b>但原因一律写同一个码</b>：区分「不存在」与「不可公开」等于
 *       给受限内容留一条探测通道，这与单条端点统一返回 404 的口径一致。
 *   <li>{@code warnings}：文件级的局限。目前只有一种——引用键撞了并被改过。它不属于任何单条成果，
 *       却是读者必须知道的事（自己 .bib 里的键可能因此对不上）。
 * </ol>
 *
 * @param requested 去重后实际请求的条数，等于 {@code entries.size() + skipped.size()}
 * @param text 整份文件正文，与下载得到的内容逐字一致
 * @param entryFileName 建议的文件名；只含 ASCII，避免浏览器对非 ASCII 文件名的处理差异
 */
public record CitationBatch(
    CitationFormat format,
    String entryFileName,
    String mediaType,
    boolean machineReadable,
    int requested,
    List<Entry> entries,
    List<Skipped> skipped,
    List<CitationWarning> warnings,
    String text,
    String provenance,
    String exportedOn) {

  public CitationBatch {
    entries = entries == null ? List.of() : List.copyOf(entries);
    skipped = skipped == null ? List.of() : List.copyOf(skipped);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }

  /**
   * 文件里的一条条目。
   *
   * @param citeKey 实际写进文件的键（可能与单条导出时不同，撞键时加了后缀）
   * @param text 该条的正文，<b>未编号</b>：编号属于「文件里的第几条」，由装配时统一加
   * @param warnings 该条的元数据告警 + 该格式的告警
   */
  public record Entry(
      String achievementId, String citeKey, String text, List<CitationWarning> warnings) {

    public Entry {
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
  }

  /**
   * 一条没能写进文件的成果。
   *
   * @param reason 稳定的原因码：{@link #REASON_NOT_AVAILABLE}（不可公开导出）或
   *     {@link #REASON_DUPLICATE_TARGET}（与已选中的另一条指向同一条公开记录）
   */
  public record Skipped(String achievementId, String reason, String message) {}

  /** 该成果不可公开导出（不存在、未生效、已下架或已被合并到别处，均为此码）。 */
  public static final String REASON_NOT_AVAILABLE = "NOT_AVAILABLE";

  /**
   * 该成果解析后与本次导出中另一条指向同一条公开记录（合并重定向），因此不重复写入。
   *
   * <p>为什么单列一个原因而不是悄悄丢掉：读者勾了两条、只拿到一条，必须知道为什么。这条也给不出
   * 新的信息——被合并的成果本来就会重定向到终点实体，公开访问者早就能看到这件事。
   */
  public static final String REASON_DUPLICATE_TARGET = "DUPLICATE_TARGET";
}
