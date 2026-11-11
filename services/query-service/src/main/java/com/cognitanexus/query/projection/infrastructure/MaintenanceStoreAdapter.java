package com.cognitanexus.query.projection.infrastructure;

import com.cognitanexus.query.projection.application.ProjectionRepositories.GenerationView;
import com.cognitanexus.query.projection.application.ProjectionRepositories.MaintenanceStore;
import com.cognitanexus.query.projection.application.ProjectionRepositories.RebuildRun;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * generation 与重建记录端口的 MyBatis 实现。
 *
 * <p>依据：架构 V0.2 §7.3。退役代不在这里自动清理：切换后紧接着清空旧代，会让仍在途的请求在
 * 读到「活动代是新代」之后、读取投影行之前失去数据来源。清理是独立的维护动作，由
 * {@link #purgeRetiredGenerations} 显式调用，调用方负责确认在途请求已排空。
 */
@Repository
public class MaintenanceStoreAdapter implements MaintenanceStore {

  private final MaintenanceMapper mapper;

  public MaintenanceStoreAdapter(MaintenanceMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public String activeGeneration(String consumerId) {
    return mapper.activeGeneration(consumerId);
  }

  @Override
  public void createGeneration(String generation, String note) {
    mapper.createGeneration(generation, note);
  }

  @Override
  public void markGenerationReady(String generation) {
    mapper.markGenerationReady(generation);
  }

  @Override
  public void retireGeneration(String generation) {
    mapper.retireGeneration(generation, "已被新代替换，按保留窗口等待清理");
  }

  @Override
  public void markGenerationFailed(String generation, String note) {
    mapper.markGenerationFailed(generation, note);
  }

  @Override
  public int switchGeneration(String consumerId, String generation, long publishedRevision) {
    return mapper.switchGeneration(consumerId, generation, publishedRevision);
  }

  @Override
  public String startRebuild(String id, String generation, Long baseRevision) {
    mapper.startRebuild(id, generation, baseRevision, "由服务内维护入口触发");
    return id;
  }

  @Override
  public void finishRebuild(
      String id, String status, Long replayedRevision, long importedRows, String note) {
    mapper.finishRebuild(id, status, replayedRevision, importedRows, note);
  }

  @Override
  public List<GenerationView> listGenerations() {
    return mapper.listGenerations().stream()
        .map(
            row ->
                new GenerationView(
                    row.generation(), row.status(), row.createdAt(), row.readyAt(), row.note()))
        .toList();
  }

  @Override
  public List<RebuildRun> listRebuildRuns(int limit) {
    return mapper.listRebuildRuns(limit).stream()
        .map(
            row ->
                new RebuildRun(
                    row.id(),
                    row.generation(),
                    row.status(),
                    row.startedAt(),
                    row.finishedAt(),
                    row.baseRevision(),
                    row.replayedRevision(),
                    row.importedRows(),
                    row.note()))
        .toList();
  }

  @Override
  public long countRetiredGenerations(String protectGeneration) {
    return mapper.countRetiredGenerations(protectGeneration);
  }

  @Override
  public int purgeRetiredGenerations(String protectGeneration) {
    List<String> retired = mapper.listRetiredGenerations(protectGeneration);
    int purged = 0;
    for (String generation : retired) {
      mapper.purgeGenerationRows(generation);
      purged += mapper.deleteGenerationRow(generation);
    }
    return purged;
  }
}
