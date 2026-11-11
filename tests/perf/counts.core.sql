-- =============================================================================
-- 分层计数（B 阶段）——core 侧
-- 目的：让「100 万条」有可核对的分子分母，而不是拿一个行数概括全部。
-- =============================================================================
\pset pager off

SELECT '规范实体总数' AS metric, count(*)::text AS value FROM core_achievement
UNION ALL
SELECT '按类型 ' || type, count(*)::text FROM core_achievement GROUP BY type
UNION ALL
SELECT 'PUBLIC + EFFECTIVE', count(*)::text FROM core_achievement
 WHERE visibility = 'PUBLIC' AND effective_status = 'EFFECTIVE'
UNION ALL
SELECT '数据性质 ' || data_nature, count(*)::text FROM core_achievement GROUP BY data_nature
UNION ALL
SELECT '已合并（merged_into 非空）', count(*)::text FROM core_achievement WHERE merged_into_id IS NOT NULL
UNION ALL
SELECT '论文 DOI 已归一', count(*)::text FROM core_paper WHERE doi_norm IS NOT NULL
UNION ALL
SELECT '原始来源记录', count(*)::text FROM raw_source_record
UNION ALL
SELECT '来源归属行', count(*)::text FROM core_achievement_source
UNION ALL
SELECT '稳定标识登记', count(*)::text FROM core_identifier_registry
UNION ALL
SELECT '规范参与关系', count(*)::text FROM core_contribution
UNION ALL
SELECT '合格参与关系（AUTO/SELF_CONFIRMED）', count(*)::text FROM core_contribution
 WHERE relation_state IN ('AUTO', 'SELF_CONFIRMED')
UNION ALL
SELECT '审定修订号', current_revision::text FROM evt_revision_counter;
