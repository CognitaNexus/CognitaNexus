package com.cognitanexus.core.integrity.application;

import com.cognitanexus.core.integrity.domain.IntegrityNotice;
import com.cognitanexus.core.integrity.domain.IntegritySummary;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 诚信声明的对外视图。
 *
 * <p>放在一处而不是各自拼装：详情页与治理台看到的字段名必须一致，否则前端要按页面写两套取值，
 * 而这种不一致只有在某个页面漏字段时才会暴露。本仓库已经因为「两个出口各自拼装」出过一次字段集
 * 分叉（C-09）。
 *
 * <p><b>对外视图不含登记账号标识。</b>「谁在平台上点了这条声明」是内部事实，读者需要知道的是
 * 声明主体（期刊、机构）与理由；把账号暴露出去既无助于判断，也会让治理人员成为被针对的对象。
 */
public final class IntegrityNoticeViews {

  private IntegrityNoticeViews() {}

  public static Map<String, Object> of(IntegrityNotice notice) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", notice.id());
    view.put("type", notice.noticeType().code());
    view.put("typeLabel", notice.noticeType().label());
    view.put("description", notice.noticeType().description());
    view.put("status", notice.status());
    view.put("active", notice.active());
    view.put("declaredBy", notice.declaredBy());
    view.put("reason", notice.reason());
    view.put("sourceUrl", notice.sourceUrl());
    view.put("announcedOn", notice.announcedOn() == null ? null : notice.announcedOn().toString());
    view.put("recordedAt", notice.recordedAt() == null ? null : notice.recordedAt().toString());
    view.put("revokedAt", notice.revokedAt() == null ? null : notice.revokedAt().toString());
    view.put("revokeReason", notice.revokeReason());
    return view;
  }

  /** 汇总视图。六个字段全部非空，前端不需要判 undefined。 */
  public static Map<String, Object> of(IntegritySummary summary) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("status", summary.status());
    view.put("label", summary.label());
    view.put("description", summary.description());
    view.put("activeTypes", summary.activeTypes());
    view.put("activeCount", summary.activeCount());
    view.put("historyCount", summary.historyCount());
    view.put("noticed", summary.activeCount() > 0);
    return view;
  }
}
