-- =============================================================================
-- CognitaNexus core-service 迁移 V19
-- 相关成果推荐所需的两个反向索引
-- 依据：需求基线「相关成果」扩展项；性能实测见 docs/ai/测试与覆盖率报告.md
-- =============================================================================
--
-- 背景：
--   推荐查询要按「主题」和「被引标识」两个方向反查，而现有索引都是正向的：
--     - core_achievement_topic 的主键是 (achievement_id, topic_id)，按 topic_id 找成果用不上；
--     - core_citation_reference 的唯一键是 (citing_achievement_id, cited_kind, cited_value_norm)，
--       按 cited_value_norm 反查同被引的成果用不上；另一个部分索引只覆盖 resolution='EXTERNAL'，
--       而共享参考文献两边都可能是 RESOLVED。
--
-- 实测（1500 篇成果、13.6 万条引用边）：
--   加索引前推荐查询 157 ms，加索引后见同批次报告的回填读数。
--   这两个索引只服务读，不影响任何写入语义与不变量。
--
-- 为什么不做成唯一索引：
--   同一主题下有多篇成果、同一被引标识被多篇引用，都是常态。唯一约束会直接让正常数据插不进去。

CREATE INDEX IF NOT EXISTS ix_core_achievement_topic_topic
    ON core_achievement_topic (topic_id);

-- 全量 (cited_value_norm) 索引：共享参考文献要按被引标识找「还有谁引了同一篇」。
-- 与 ix_core_citation_reference_pending（只覆盖 EXTERNAL）不重复：那条是给待解析队列用的。
CREATE INDEX IF NOT EXISTS ix_core_citation_reference_value
    ON core_citation_reference (cited_value_norm);

COMMENT ON INDEX ix_core_achievement_topic_topic IS
    '反向查主题下的成果，供相关成果推荐使用';
COMMENT ON INDEX ix_core_citation_reference_value IS
    '按被引标识反查引用方，供共享参考文献（bibliographic coupling）推荐使用';
