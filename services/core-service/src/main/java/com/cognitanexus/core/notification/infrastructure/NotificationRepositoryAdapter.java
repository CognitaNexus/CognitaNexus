package com.cognitanexus.core.notification.infrastructure;

import com.cognitanexus.core.notification.application.NotificationRepositories;
import com.cognitanexus.core.notification.domain.Notification;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 通知仓储实现。 */
@Repository
public class NotificationRepositoryAdapter
    implements NotificationRepositories.NotificationRepository {

  private final NotificationMapper mapper;

  public NotificationRepositoryAdapter(NotificationMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void insert(Notification notification) {
    mapper.insert(
        notification.id(),
        notification.accountId(),
        notification.kind(),
        notification.title(),
        notification.body(),
        notification.refType(),
        notification.refId());
  }

  @Override
  public List<Notification> listByAccount(
      String accountId, boolean unreadOnly, String kind, int limit, int offset) {
    return mapper.listByAccount(accountId, unreadOnly, kind, limit, offset).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public long countUnread(String accountId) {
    return mapper.countUnread(accountId);
  }

  @Override
  public int markRead(String accountId, Collection<String> ids, int limit) {
    return mapper.markRead(accountId, ids, limit);
  }

  @Override
  public int markAllRead(String accountId) {
    return mapper.markAllRead(accountId);
  }

  @Override
  public int archive(String accountId, Collection<String> ids, int limit) {
    return mapper.archive(accountId, ids, limit);
  }

  @Override
  public int archiveAllRead(String accountId) {
    return mapper.archiveAllRead(accountId);
  }

  @Override
  public List<String> kindsOf(String accountId) {
    return mapper.kindsOf(accountId);
  }

  private Notification toDomain(NotificationMapper.NotificationRow row) {
    return new Notification(
        row.id(),
        row.accountId(),
        row.kind(),
        row.title(),
        row.body(),
        row.refType(),
        row.refId(),
        row.createdAt(),
        row.readAt());
  }
}
