package com.cognitanexus.core.catalog.application;

import com.cognitanexus.core.catalog.domain.CitationFormat;
import com.cognitanexus.core.catalog.domain.CitationWarning;
import java.util.List;

/**
 * 一份成果的全部引用视图。
 *
 * <p>一次给全四种格式，而不是让前端按需逐次请求：引用面板上四个标签页之间来回切换时，逐次请求会让
 * 每个标签页都闪一次加载态，而且四次请求之间若成果恰好被撤稿，读者会在同一屏里看到两种结论。
 * 四种文本的总量在千字节量级，一次给完是更诚实的取舍。
 *
 * <p>{@code warnings} 是<b>元数据层面</b>的局限（缺年份、署名顺序未知、已被撤稿），对四个格式都成立；
 * {@code formats[].warnings} 是<b>格式层面</b>的局限（例如 APA 要求拆姓名而平台不拆），只影响那一个
 * 格式。分开放是因为把「APA 的简化」提升到顶层，会让人误以为国标与 BibTeX 也不可靠。
 *
 * @param defaultFormat 前端默认选中的格式，由服务端给出，避免前后端各写一份默认值
 * @param provenance 平台侧出处说明。<b>刻意不写进引用正文</b>：引用文本会被粘进论文的参考文献表，
 *     任何非书目信息都会跟着进那张表；出处与导出时刻是平台对读者的说明，属于界面的一部分。
 * @param exportedOn 生成时刻（{@code yyyy-MM-dd}）。元数据会随后续修订变化，读者需要知道自己复制
 *     的那一段是什么时候取自本平台的。
 */
public record CitationBundle(
    String achievementId,
    String citeKey,
    CitationFormat defaultFormat,
    String provenance,
    String exportedOn,
    List<CitationWarning> warnings,
    List<FormatEntry> formats) {

  public CitationBundle {
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
    formats = formats == null ? List.of() : List.copyOf(formats);
  }

  /**
   * 单个格式的渲染结果。
   *
   * @param fileName 下载时的建议文件名；只含 ASCII，避免不同浏览器对非 ASCII 文件名的处理差异
   */
  public record FormatEntry(
      CitationFormat format,
      String text,
      String fileName,
      String mediaType,
      boolean machineReadable,
      List<CitationWarning> warnings) {

    public FormatEntry {
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
  }
}
