-- =============================================================================
-- 分层计数（B 阶段）——query 侧（真正对外可检索的那一层）
-- =============================================================================
\pset pager off

SELECT '可检索去重成果（投影行数）' AS metric, count(*)::text AS value
FROM qry_achievement WHERE generation = 'gen0001'
UNION ALL
SELECT '按类型 ' || type, count(*)::text FROM qry_achievement
 WHERE generation = 'gen0001' GROUP BY type
UNION ALL
SELECT 'PUBLIC + EFFECTIVE', count(*)::text FROM qry_achievement
 WHERE generation = 'gen0001' AND visibility = 'PUBLIC' AND effective_status = 'EFFECTIVE'
UNION ALL
SELECT '数据性质 ' || data_nature, count(*)::text FROM qry_achievement
 WHERE generation = 'gen0001' GROUP BY data_nature
UNION ALL
SELECT '参与关系行', count(*)::text FROM qry_contribution WHERE generation = 'gen0001'
UNION ALL
SELECT '合作边行', count(*)::text FROM qry_coauthor_edge WHERE generation = 'gen0001'
UNION ALL
SELECT '领域归属行', count(*)::text FROM qry_achievement_topic WHERE generation = 'gen0001'
UNION ALL
SELECT '发生机构行', count(*)::text FROM qry_contribution_org WHERE generation = 'gen0001'
UNION ALL
SELECT '已发布修订（published_revision）', published_revision::text
  FROM qry_consumer_state WHERE consumer_id = 'query'
UNION ALL
SELECT '投影代', active_generation FROM qry_consumer_state WHERE consumer_id = 'query';

-- 索引体积：容量报告要能说清「快是因为数据少还是因为索引对」
SELECT relname AS table_name,
       pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size,
       pg_size_pretty(pg_relation_size(c.oid)) AS heap_size,
       pg_size_pretty(pg_total_relation_size(c.oid) - pg_relation_size(c.oid)) AS index_size
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'r'
  AND c.relname IN ('qry_achievement', 'qry_contribution', 'qry_coauthor_edge',
                    'qry_achievement_topic', 'qry_contribution_org')
ORDER BY pg_total_relation_size(c.oid) DESC;
