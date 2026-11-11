package com.cognitanexus.ingestion.collect.adapter;

import com.cognitanexus.ingestion.collect.domain.AdapterKind;
import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectJob;
import com.cognitanexus.ingestion.collect.domain.CollectModels.FetchPage;
import com.cognitanexus.ingestion.collect.domain.CollectModels.SourceRunConfig;

/**
 * 来源适配器。
 *
 * <p>依据：架构 V0.2 §6.2。适配器的职责边界：
 *
 * <ul>
 *   <li><b>做</b>：按来源的 API 协议发起请求、解析响应、把字段抹平为规范化记录、给出稳定标识；
 *   <li><b>不做</b>：不做持久化、不推进检查点、不判断能否发布。这些属于服务层与治理规则，
 *       放在适配器里会让每个来源各自实现一遍治理语义。
 * </ul>
 *
 * <p>所有实现必须满足的约定：
 *
 * <ol>
 *   <li>只读取并通过 {@code allowedFields} 允许的字段进入 payload；
 *   <li>网络与解析错误向上抛出，由服务层记录到任务并保留检查点不动——失败不得表现为「抓到了 0 条」；
 *   <li>返回的记录中 sourceRecordId 必须稳定，同一条目重复抓取得到同一个值。
 * </ol>
 */
public interface SourceAdapter {

  AdapterKind kind();

  /**
   * 抓取一页。
   *
   * @param config 运行配置（含分页大小、字段白名单）
   * @param cursor 上一页返回的游标；空表示首页
   * @return 本页记录与下一页游标
   */
  FetchPage fetch(SourceRunConfig config, CollectJob job, String cursor);
}
