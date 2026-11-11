package com.cognitanexus.core.notification.application;

import com.cognitanexus.core.notification.domain.Notification;
import java.util.Collection;
import java.util.List;

/** 通知模块的持久化出站端口（编码规范 §2.2：端口在应用层定义，基础设施实现）。 */
public interface NotificationRepositories {

  interface NotificationRepository {

    void insert(Notification notification);

    /**
     * 按账号倒序取最近通知；unreadOnly 为 true 时只取未读。
     *
     * @param kind 非空时只取该类型；null 表示全部类型。类型筛选放在 SQL 里而不是取回再过滤：
     *     账号通知量大的时候，「先取 20 条再筛掉 18 条」会让分页看起来是空的。
     */
    List<Notification> listByAccount(
        String accountId, boolean unreadOnly, String kind, int limit, int offset);

    long countUnread(String accountId);

    /**
     * 标记已读。WHERE 带账号条件：只能操作自己的通知。返回受影响行数——调用方据此区分
     * 「全部成功」与「部分 id 不是本人的」，但两种情况都不报错（ADR-003 §2 第 4 条）。
     */
    int markRead(String accountId, Collection<String> ids, int limit);

    /** 本人的全部未读置为已读，返回受影响行数。 */
    int markAllRead(String accountId);

    /**
     * 从本人列表移除（置 archived_at），不是物理删除。
     *
     * <p>行仍在库里：账号「收到过什么」是平台行为的证据，删掉之后就无法区分「用户说没收到」
     * 与「通知确实没发」。用户视角的删除诉求通过「不再出现在我的列表与未读数里」满足。
     */
    int archive(String accountId, Collection<String> ids, int limit);

    /** 移除本人已读的全部通知，返回受影响行数。 */
    int archiveAllRead(String accountId);

    /** 本人列表中出现过的通知类型，用于「按类型筛选」下拉。 */
    List<String> kindsOf(String accountId);
  }

  /** 学者 → 活跃认领账号的解析端口。实现在 identity 模块装配，此处只声明需要的能力。 */
  interface ScholarClaimantResolver {

    /** 返回这批学者中每个有活跃门户授权的账号 id，已去重。学者未被认领时返回空。 */
    List<String> resolveActiveAccountIds(Collection<String> scholarIds);
  }
}
