package com.cognitanexus.core.catalog.api;

import com.cognitanexus.core.catalog.domain.CitationFormat;
import com.cognitanexus.core.catalog.domain.CitationWarning;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 引用接口的报文拼装。
 *
 * <p>单独抽出来的原因很实际：告警清单在两处出口（单条、批量）都要给，而 {@code code} 与 {@code message}
 * 两个字段<b>一个都不能少</b>（服务配了 {@code non_null}，少写一个字段它就从报文里消失，前端拿到
 * {@code undefined} 后与「服务端漏写」无法区分）。一个字段的名字在两处各写一遍，早晚只剩一处被改。
 */
final class CitationViews {

  private CitationViews() {}

  /** 格式的规范书写（小写、下划线），与 {@link CitationFormat#codes()} 一致。 */
  static String formatCode(CitationFormat format) {
    return format.name().toLowerCase(Locale.ROOT);
  }

  static List<Map<String, Object>> warningsView(List<CitationWarning> warnings) {
    List<Map<String, Object>> views = new ArrayList<>();
    for (CitationWarning warning : warnings) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("code", warning.code());
      item.put("message", warning.message());
      views.add(item);
    }
    return views;
  }
}
