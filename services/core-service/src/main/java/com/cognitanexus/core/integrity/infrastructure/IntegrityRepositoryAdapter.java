package com.cognitanexus.core.integrity.infrastructure;

import com.cognitanexus.core.integrity.application.IntegrityNoticeRepository;
import com.cognitanexus.core.integrity.domain.IntegrityNotice;
import com.cognitanexus.core.integrity.domain.IntegrityNoticeType;
import com.cognitanexus.core.integrity.infrastructure.IntegrityMapper.NoticeRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 诚信声明仓储实现。行类型与领域对象在这一处转换，其它层不接触 MyBatis 行。 */
@Repository
public class IntegrityRepositoryAdapter implements IntegrityNoticeRepository {

  private final IntegrityMapper mapper;

  public IntegrityRepositoryAdapter(IntegrityMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void insert(IntegrityNotice notice) {
    mapper.insert(toRow(notice));
  }

  @Override
  public Optional<IntegrityNotice> findById(String id) {
    return mapper.findById(id).map(IntegrityRepositoryAdapter::toDomain);
  }

  @Override
  public List<IntegrityNotice> findByAchievement(String achievementId) {
    return mapper.findByAchievement(achievementId).stream()
        .map(IntegrityRepositoryAdapter::toDomain)
        .toList();
  }

  @Override
  public Optional<IntegrityNotice> findActive(String achievementId, String noticeTypeCode) {
    return mapper
        .findActive(achievementId, noticeTypeCode)
        .map(IntegrityRepositoryAdapter::toDomain);
  }

  @Override
  public int revoke(String id, String revokedBy, Instant revokedAt, String reason) {
    return mapper.revoke(id, revokedBy, revokedAt, reason);
  }

  @Override
  public List<IntegrityNotice> listRecent(int limit, boolean activeOnly) {
    return mapper.listRecent(limit, activeOnly).stream()
        .map(IntegrityRepositoryAdapter::toDomain)
        .toList();
  }

  private static NoticeRow toRow(IntegrityNotice notice) {
    return new NoticeRow(
        notice.id(),
        notice.achievementId(),
        notice.noticeType().code(),
        notice.status(),
        notice.declaredBy(),
        notice.reason(),
        notice.sourceUrl(),
        notice.announcedOn(),
        notice.recordedBy(),
        notice.recordedAt(),
        notice.revokedBy(),
        notice.revokedAt(),
        notice.revokeReason());
  }

  private static IntegrityNotice toDomain(NoticeRow row) {
    return new IntegrityNotice(
        row.id(),
        row.achievementId(),
        IntegrityNoticeType.of(row.noticeType()),
        row.status(),
        row.declaredBy(),
        row.reason(),
        row.sourceUrl(),
        row.announcedOn(),
        row.recordedBy(),
        row.recordedAt(),
        row.revokedBy(),
        row.revokedAt(),
        row.revokeReason());
  }
}
