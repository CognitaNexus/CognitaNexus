package com.cognitanexus.core.catalog.infrastructure;

import com.cognitanexus.core.catalog.application.CatalogRepositories.ScholarRepository;
import com.cognitanexus.core.catalog.domain.Scholar;
import com.cognitanexus.core.catalog.domain.ScholarProfile;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 学者仓储实现。 */
@Repository
public class CatalogScholarRepository implements ScholarRepository {

  private final ScholarMapper mapper;

  public CatalogScholarRepository(ScholarMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void insert(Scholar scholar) {
    mapper.insert(
        scholar.id(),
        scholar.displayName(),
        scholar.nameNorm(),
        scholar.dataNature().name(),
        scholar.claimedStatus().name(),
        scholar.organizationName(),
        scholar.researchSummary());
  }

  @Override
  public Optional<Scholar> findById(String id) {
    return mapper.findById(id).map(mapper::toDomain);
  }

  @Override
  public List<Scholar> findByExactNameNorm(String nameNorm, int limit) {
    return mapper.findByExactNameNorm(nameNorm, limit).stream().map(mapper::toDomain).toList();
  }

  @Override
  public Optional<String> findScholarIdByOrcid(String orcidNorm) {
    return mapper.findRegisteredScholar("ORCID", orcidNorm);
  }

  @Override
  public Optional<String> findScholarIdByOpenAlexId(String openAlexIdNorm) {
    return mapper.findRegisteredScholar("OPENALEX_ID", openAlexIdNorm);
  }

  @Override
  public void insertNameVariant(
      String id, String scholarId, String variant, String variantNorm, String evidence) {
    mapper.insertNameVariant(id, scholarId, variant, variantNorm, evidence);
  }

  @Override
  public void upsertOrganizationHint(String scholarId, String organizationName) {
    mapper.upsertOrganizationHint(scholarId, organizationName);
  }

  @Override
  public Optional<String> resolveSurvivorId(String scholarId) {
    return mapper.findMergedIntoId(scholarId);
  }

  @Override
  public int updateClaimedStatus(String scholarId, String claimedStatus, long expectedVersion) {
    return mapper.updateClaimedStatus(scholarId, claimedStatus, expectedVersion);
  }

  @Override
  public int transitionClaimedStatus(String scholarId, String fromStatus, String toStatus) {
    return mapper.transitionClaimedStatus(scholarId, fromStatus, toStatus);
  }

  @Override
  public int updateResearchSummary(String scholarId, String researchSummary) {
    return mapper.updateResearchSummary(scholarId, researchSummary);
  }

  @Override
  public int updateOrganizationName(String scholarId, String organizationName) {
    return mapper.updateOrganizationName(scholarId, organizationName);
  }

  @Override
  public Optional<ScholarProfile> findProfile(String scholarId) {
    return mapper.findProfile(scholarId).map(mapper::toProfile);
  }

  @Override
  public int updateHomepageUrl(String scholarId, String homepageUrl) {
    return mapper.updateHomepageUrl(scholarId, homepageUrl);
  }

  @Override
  public int updateAvatar(String scholarId, String avatarRef, String avatarMediaType) {
    return mapper.updateAvatar(scholarId, avatarRef, avatarMediaType);
  }

  @Override
  public int clearAvatar(String scholarId) {
    return mapper.clearAvatar(scholarId);
  }

  @Override
  public List<String> listFeatured(String scholarId) {
    return mapper.listFeatured(scholarId);
  }

  @Override
  public void replaceFeatured(String scholarId, List<String> achievementIds) {
    mapper.deleteFeatured(scholarId);
    int rank = 1;
    for (String achievementId : achievementIds) {
      mapper.insertFeatured(scholarId, achievementId, rank++);
    }
  }

  @Override
  public long countAll() {
    return mapper.countAll();
  }
}
