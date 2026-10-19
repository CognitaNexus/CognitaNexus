package com.cognitanexus.core.catalog.infrastructure;

import com.cognitanexus.core.catalog.application.CatalogRepositories.CitationRepository;
import com.cognitanexus.sharedkernel.AchievementType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 引用网络的仓储实现。
 *
 * <p>依据：编码规范 §2.2。这里只做「行 → 端口记录」的翻译工作，业务判断（什么算站内、能不能
 * 解析、缺数据怎么表达）留在 {@code CitationNetworkService}。唯一一处在 SQL 侧完成的判断是
 * 「公开资格」，因为它必须与计数用的是同一份条件，否则明细与数字会不一致。
 */
@Repository
public class CitationRepositoryAdapter implements CitationRepository {

  private final CitationMapper mapper;

  public CitationRepositoryAdapter(CitationMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<Observation> findObservation(String achievementId) {
    CitationMapper.ObservationRow row = mapper.findObservation(achievementId);
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        new Observation(
            row.achievementId(),
            row.provider(),
            row.providerWorkId(),
            row.referencesTotal(),
            row.referencesListed(),
            row.outcome(),
            row.fetchedAt()));
  }

  @Override
  public void saveObservation(Observation observation) {
    mapper.upsertObservation(
        observation.achievementId(),
        observation.provider(),
        observation.providerWorkId(),
        observation.referencesTotal(),
        observation.referencesListed(),
        observation.outcome(),
        observation.fetchedAt());
  }

  @Override
  public int deleteReferencesOf(String citingAchievementId) {
    return mapper.deleteReferencesOf(citingAchievementId);
  }

  @Override
  public void insertReference(Reference reference) {
    mapper.insertReference(
        reference.id(),
        reference.citingAchievementId(),
        reference.citedKind(),
        reference.citedValueNorm(),
        reference.citedAchievementId(),
        reference.resolution(),
        reference.observedAt(),
        "RESOLVED".equals(reference.resolution()) ? reference.observedAt() : null);
  }

  @Override
  public List<CitedWork> listResolvedReferences(String citingAchievementId) {
    return toWorks(mapper.listResolvedReferences(citingAchievementId));
  }

  @Override
  public List<CitedWork> listCitedBy(String citedAchievementId) {
    return toWorks(mapper.listCitedBy(citedAchievementId));
  }

  @Override
  public ResolutionCounts countReferencesOf(String citingAchievementId) {
    CitationMapper.ResolutionCountsRow row = mapper.countReferencesOf(citingAchievementId);
    if (row == null) {
      return new ResolutionCounts(0, 0, 0);
    }
    return new ResolutionCounts(row.resolved(), row.external(), row.selfReferences());
  }

  @Override
  public List<CitedKey> listUnresolvedKeys(int limit) {
    List<CitedKey> keys = new ArrayList<>();
    for (CitationMapper.UnresolvedKeyRow row : mapper.listUnresolvedKeys(limit)) {
      keys.add(new CitedKey(row.citedKind(), row.citedValueNorm()));
    }
    return keys;
  }

  @Override
  public int resolveByKey(String kind, String valueNorm, String citedAchievementId, Instant at) {
    return mapper.resolveByKey(kind, valueNorm, citedAchievementId, at);
  }

  @Override
  public int countObservedWorks() {
    return mapper.countObservedWorks();
  }

  @Override
  public int countNotFoundWorks() {
    return mapper.countNotFoundWorks();
  }

  @Override
  public int countPublicPapers() {
    return mapper.countPublicPapers();
  }

  @Override
  public List<PendingWork> listPendingWorks(int limit, String search, String topicId) {
    List<PendingWork> works = new ArrayList<>();
    for (CitationMapper.PendingWorkRow row : mapper.listPendingWorks(limit, search, topicId)) {
      works.add(new PendingWork(row.achievementId(), row.doi(), row.citationCount()));
    }
    return works;
  }

  @Override
  public int countPendingWorks(String search, String topicId) {
    return mapper.countPendingWorks(search, topicId);
  }

  private static List<CitedWork> toWorks(List<CitationMapper.CitedWorkRow> rows) {
    List<CitedWork> works = new ArrayList<>(rows.size());
    for (CitationMapper.CitedWorkRow row : rows) {
      AchievementType type = null;
      try {
        type = AchievementType.valueOf(row.type());
      } catch (IllegalArgumentException | NullPointerException ex) {
        // 类型值不在枚举内说明数据已损坏；这一条按「类型未知」展示，不让整页查询失败
        type = null;
      }
      works.add(
          new CitedWork(
              row.achievementId(),
              row.title(),
              type,
              row.publicationYear(),
              "YEAR".equals(row.yearPrecision()),
              row.venue(),
              row.doi()));
    }
    return works;
  }
}
