-- =============================================================================
-- 固定查询集（B／C 阶段共用）——与 SearchService / AnalyticsService / NetworkService
-- 实际发出的 SQL 形状保持一致，参数取固定值，便于两次运行对比。
--
-- 运行：psql -f tests/perf/explain.sql -d query_perf
-- 说明：EXPLAIN ANALYZE 会真正执行查询；只在隔离库上跑。
-- =============================================================================
\pset pager off
\timing on

-- 0. 数据分布抽样：确认热门词、冷门词、零命中确实是这三种情况
SELECT '热门词（图神经网络）' AS probe, count(*) FROM qry_achievement
 WHERE generation = 'gen0001' AND title_norm LIKE '%图神经网络%'
UNION ALL
SELECT '冷门词（构结构分析方法）', count(*) FROM qry_achievement
 WHERE generation = 'gen0001' AND title_norm LIKE '%构结构分析方法%'
UNION ALL
SELECT '零命中（zzz不存在）', count(*) FROM qry_achievement
 WHERE generation = 'gen0001' AND title_norm LIKE '%zzz不存在%';

-- 1. 热门词检索：默认排序（relevance DESC），第 1 页
EXPLAIN (ANALYZE, BUFFERS)
SELECT a.achievement_id, a.type, a.title, a.publication_year, a.citation_count,
       similarity(a.title_norm, '图神经网络') AS relevance
FROM qry_achievement a
WHERE a.generation = 'gen0001'
  AND a.effective_status = 'EFFECTIVE'
  AND a.visibility = 'PUBLIC'
  AND a.data_nature = 'SIMULATED'
  AND (a.title_norm LIKE '%' || '图神经网络' || '%'
       OR lower(coalesce(a.venue, '')) LIKE '%' || '图神经网络' || '%'
       OR lower(coalesce(a.doi, '')) = '图神经网络')
ORDER BY relevance DESC, a.publication_year DESC NULLS LAST, a.achievement_id
LIMIT 20 OFFSET 0;

-- 2. 同一热门词的计数（与列表同条件，验证两者都能走同一个索引）
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*)
FROM qry_achievement a
WHERE a.generation = 'gen0001'
  AND a.effective_status = 'EFFECTIVE'
  AND a.visibility = 'PUBLIC'
  AND a.data_nature = 'SIMULATED'
  AND (a.title_norm LIKE '%' || '图神经网络' || '%'
       OR lower(coalesce(a.venue, '')) LIKE '%' || '图神经网络' || '%'
       OR lower(coalesce(a.doi, '')) = '图神经网络');

-- 3. 深翻页：按年份排序的第 450 页（offset 8980）
EXPLAIN (ANALYZE, BUFFERS)
SELECT a.achievement_id
FROM qry_achievement a
WHERE a.generation = 'gen0001'
  AND a.effective_status = 'EFFECTIVE'
  AND a.visibility = 'PUBLIC'
  AND a.data_nature = 'SIMULATED'
ORDER BY a.publication_year DESC NULLS LAST, a.achievement_id
LIMIT 20 OFFSET 8980;

-- 4. 复合筛选：类型 + 年份区间 + 机构
EXPLAIN (ANALYZE, BUFFERS)
SELECT a.achievement_id
FROM qry_achievement a
WHERE a.generation = 'gen0001'
  AND a.effective_status = 'EFFECTIVE'
  AND a.visibility = 'PUBLIC'
  AND a.data_nature = 'SIMULATED'
  AND a.type = 'PAPER'
  AND a.publication_year >= 2018
  AND a.publication_year <= 2026
  AND EXISTS (
    SELECT 1 FROM qry_contribution_org o
    WHERE o.generation = a.generation AND o.achievement_id = a.achievement_id
      AND o.organization = '机构1')
ORDER BY a.publication_year DESC NULLS LAST, a.achievement_id
LIMIT 20 OFFSET 0;

-- 5. 领域筛选（多对多）
EXPLAIN (ANALYZE, BUFFERS)
SELECT a.achievement_id
FROM qry_achievement a
JOIN qry_achievement_topic t
  ON t.generation = a.generation AND t.achievement_id = a.achievement_id
 AND t.topic_id = 'topic-perf-7'
WHERE a.generation = 'gen0001'
  AND a.effective_status = 'EFFECTIVE'
  AND a.visibility = 'PUBLIC'
  AND a.data_nature = 'SIMULATED'
ORDER BY a.publication_year DESC NULLS LAST, a.achievement_id
LIMIT 20 OFFSET 0;

-- 6. 学者聚合（同名不同人）：按产出数排序
EXPLAIN (ANALYZE, BUFFERS)
SELECT c.scholar_id, max(c.scholar_name) AS name, count(DISTINCT c.achievement_id) AS cnt
FROM qry_contribution c
JOIN qry_achievement a
  ON a.generation = c.generation AND a.achievement_id = c.achievement_id
WHERE c.generation = 'gen0001'
  AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = 'SIMULATED'
  AND c.scholar_name = '作者1234'
GROUP BY c.scholar_id
ORDER BY cnt DESC, c.scholar_id
LIMIT 20;

-- 7. 领域统计（含来源/推断分列）
EXPLAIN (ANALYZE, BUFFERS)
SELECT t.topic_id, max(t.canonical_name) AS name,
       count(DISTINCT t.achievement_id) AS total,
       count(DISTINCT t.achievement_id) FILTER (WHERE t.assignment_kind = 'SOURCE') AS src,
       count(DISTINCT t.achievement_id) FILTER (WHERE t.assignment_kind = 'INFERRED') AS inf
FROM qry_achievement_topic t
JOIN qry_achievement a
  ON a.generation = t.generation AND a.achievement_id = t.achievement_id
WHERE t.generation = 'gen0001'
  AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = 'SIMULATED'
GROUP BY t.topic_id
ORDER BY total DESC, t.topic_id
LIMIT 20;

-- 8. 机构统计
EXPLAIN (ANALYZE, BUFFERS)
SELECT o.organization, count(DISTINCT o.achievement_id) AS cnt
FROM qry_contribution_org o
JOIN qry_achievement a
  ON a.generation = o.generation AND a.achievement_id = o.achievement_id
WHERE o.generation = 'gen0001'
  AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = 'SIMULATED'
GROUP BY o.organization
ORDER BY cnt DESC, o.organization
LIMIT 20;

-- 9. 合作网络一跳：某个高合作度学者的邻接边
EXPLAIN (ANALYZE, BUFFERS)
SELECT e.scholar_b AS neighbor, count(DISTINCT e.achievement_id) AS strength
FROM qry_coauthor_edge e
WHERE e.generation = 'gen0001' AND e.scholar_a = 'sch-perf-1'
GROUP BY e.scholar_b
UNION
SELECT e.scholar_a, count(DISTINCT e.achievement_id)
FROM qry_coauthor_edge e
WHERE e.generation = 'gen0001' AND e.scholar_b = 'sch-perf-1'
GROUP BY e.scholar_a;
