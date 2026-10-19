package com.cognitanexus.core.catalog.domain;

import java.time.Instant;

/**
 * 学者档案里「本人可自助维护」的那部分。
 *
 * <p>为什么与 {@link Scholar} 分开：{@code Scholar} 承载的是来源事实与认领状态，构造点遍布
 * 采集与治理链路；把这几个「自述字段」加进去，会让每一个构造点都被迫关心它们。这里单独一条记录，
 * 读档案时按需再查一次，代价是一次主键命中。
 *
 * <p>字段语义：
 *
 * <ul>
 *   <li>{@code homepageUrl}：本人填写的主页地址，只作为外链展示，平台不抓取内容；
 *   <li>{@code avatarRef}：头像在受控卷内的相对引用。为空表示没有头像，公开页退回姓名首字占位；
 *   <li>{@code profileUpdatedAt}：最后一次自助修改时间，公开页用来标注「资料由本人维护」。
 * </ul>
 */
public record ScholarProfile(
    String scholarId,
    String homepageUrl,
    String avatarRef,
    String avatarMediaType,
    Instant profileUpdatedAt) {

  public boolean hasAvatar() {
    return avatarRef != null && !avatarRef.isBlank() && avatarMediaType != null;
  }

  /**
   * 公开读取地址。
   *
   * <p>地址形状只在这一个地方定义：公开视图与门户编辑视图都调它，两处各拼一遍的话，
   * 换缓存策略时必然漏改一处，表现为「门户里能看到的头像，档案页 404」。
   *
   * <p>带 {@code v} 参数而不是带文件名：换头像后文件名会变，但地址形状不变，
   * 前端不必在换图后重新计算链接。
   *
   * <p><b>返回的是服务内路径</b>，不含网关前缀（开发期的 {@code /core}、生产环境可能没有）。
   * 后端不知道浏览器前面挂了几层前缀，写死等于替部署形状下结论；补前缀由调用方负责，
   * 前端集中在 {@code serviceUrl()} 一处做。
   */
  public String avatarUrl() {
    if (!hasAvatar()) {
      return null;
    }
    long version = profileUpdatedAt == null ? 0L : profileUpdatedAt.getEpochSecond();
    return "/api/v1/public/scholars/" + scholarId + "/avatar?v=" + version;
  }

  /** 未维护过资料时的空档案：与其让调用方到处判 null，不如给一个各项皆空的对象。 */
  public static ScholarProfile empty(String scholarId) {
    return new ScholarProfile(scholarId, null, null, null, null);
  }
}
