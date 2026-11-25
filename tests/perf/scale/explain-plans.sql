-- =============================================================================
-- 固定执行计划采集集：与 SearchMapper / AnalyticsMapper / NetworkMapper 实际发出的
-- SQL 形状一致，参数取固定值，便于跨规模、跨版本对比。
--
-- 每条语句上方注明它对应哪个 Mapper 方法。改这里必须同时看那边：如果 Mapper 换了
-- 访问路径（比如关键词从 OR 链改成 UNION），这里的「旧形状」计划就不再能解释线上延迟。
--
-- 运行：psql -v gen=gen0001 -v nature=SIMULATED -f tests/perf/scale/explain-plans.sql
-- 注意：EXPLAIN ANALYZE 会真正执行查询，只在压测库上跑。
-- =============================================================================

\set ON_ERROR_STOP on
\if :{?gen}
\else
  \set gen gen0001
\endif
\if :{?nature}
\else
  \set nature SIMULATED
\endif

\pset pager off
\timing on

-- ---------------------------------------------------------------------------
-- 0. 数据分布自检：先确认热门词 / 冷门词 / 零命中确实是这三种情况
--
-- 没有这一步，后面「热门词为什么走顺序扫描」的解释就可能是错的——
-- 第三批就出现过「标题全部相同，检索退化成全命中」的情况。
-- ---------------------------------------------------------------------------
SELECT 'S0-热门词 图神经网络' AS probe, count(*) AS hits FROM qry_achievement
 WHERE generation = :'gen' AND effective_status = 'EFFECTIVE' AND visibility = 'PUBLIC'
   AND title_norm LIKE '%图神经网络%'
UNION ALL
SELECT 'S0-冷门词 构结构分析研究', count(*) FROM qry_achievement
 WHERE generation = :'gen' AND effective_status = 'EFFECTIVE' AND visibility = 'PUBLIC'
   AND title_norm LIKE '%构结构分析研究%'
UNION ALL
SELECT 'S0-零命中 zzz不存在', count(*) FROM qry_achievement
 WHERE generation = :'gen' AND effective_status = 'EFFECTIVE' AND visibility = 'PUBLIC'
   AND title_norm LIKE '%zzz不存在%';

-- ---------------------------------------------------------------------------
-- 1. SearchMapper.countHits —— 热门词计数（五支 UNION 候选集）
-- ---------------------------------------------------------------------------
\echo '--- S1 countHits(hot keyword) ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*)
FROM qry_achievement a
JOIN (
  SELECT a1.achievement_id FROM qry_achievement a1
   WHERE a1.generation = :'gen' AND a1.effective_status = 'EFFECTIVE' AND a1.visibility = 'PUBLIC'
     AND a1.title_norm LIKE '%图神经网络%'
  UNION
  SELECT a2.achievement_id FROM qry_achievement a2
   WHERE a2.generation = :'gen' AND a2.effective_status = 'EFFECTIVE' AND a2.visibility = 'PUBLIC'
     AND lower(coalesce(a2.venue, '')) LIKE '%图神经网络%'
  UNION
  SELECT a3.achievement_id FROM qry_achievement a3
   WHERE a3.generation = :'gen' AND a3.effective_status = 'EFFECTIVE' AND a3.visibility = 'PUBLIC'
     AND lower(coalesce(a3.abstract_text, '')) LIKE '%图神经网络%'
  UNION
  SELECT a4.achievement_id FROM qry_achievement a4
   WHERE a4.generation = :'gen' AND a4.effective_status = 'EFFECTIVE' AND a4.visibility = 'PUBLIC'
     AND lower(coalesce(a4.doi, '')) = '图神经网络'
  UNION
  SELECT k.achievement_id FROM qry_contribution k
   WHERE k.generation = :'gen' AND k.relation_state IN ('AUTO', 'SELF_CONFIRMED')
     AND lower(coalesce(k.scholar_name, '')) LIKE '%图神经网络%'
) kw ON kw.achievement_id = a.achievement_id
WHERE a.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = :'nature';

-- ---------------------------------------------------------------------------
-- 2. SearchMapper.listHits —— 热门词首页（relevance 排序 + 稳定 ID 兜底）
-- ---------------------------------------------------------------------------
\echo '--- S2 listHits(hot keyword, page 1) ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT a.achievement_id, a.title, similarity(a.title_norm, '图神经网络') AS relevance
FROM qry_achievement a
JOIN (
  SELECT a1.achievement_id FROM qry_achievement a1
   WHERE a1.generation = :'gen' AND a1.effective_status = 'EFFECTIVE' AND a1.visibility = 'PUBLIC'
     AND a1.title_norm LIKE '%图神经网络%'
  UNION
  SELECT a2.achievement_id FROM qry_achievement a2
   WHERE a2.generation = :'gen' AND a2.effective_status = 'EFFECTIVE' AND a2.visibility = 'PUBLIC'
     AND lower(coalesce(a2.venue, '')) LIKE '%图神经网络%'
  UNION
  SELECT a3.achievement_id FROM qry_achievement a3
   WHERE a3.generation = :'gen' AND a3.effective_status = 'EFFECTIVE' AND a3.visibility = 'PUBLIC'
     AND lower(coalesce(a3.abstract_text, '')) LIKE '%图神经网络%'
  UNION
  SELECT a4.achievement_id FROM qry_achievement a4
   WHERE a4.generation = :'gen' AND a4.effective_status = 'EFFECTIVE' AND a4.visibility = 'PUBLIC'
     AND lower(coalesce(a4.doi, '')) = '图神经网络'
  UNION
  SELECT k.achievement_id FROM qry_contribution k
   WHERE k.generation = :'gen' AND k.relation_state IN ('AUTO', 'SELF_CONFIRMED')
     AND lower(coalesce(k.scholar_name, '')) LIKE '%图神经网络%'
) kw ON kw.achievement_id = a.achievement_id
WHERE a.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = :'nature'
ORDER BY relevance DESC, a.publication_year DESC NULLS LAST, a.achievement_id
LIMIT 20 OFFSET 0;

-- ---------------------------------------------------------------------------
-- 3. SearchMapper.listHits —— 深翻页（year_desc，offset 100：size=20 的第 6 页）
-- ---------------------------------------------------------------------------
\echo '--- S3 listHits(deep page, year_desc, offset 100) ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT a.achievement_id
FROM qry_achievement a
WHERE a.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = :'nature'
ORDER BY a.publication_year DESC NULLS LAST, a.achievement_id
LIMIT 20 OFFSET 100;

-- ---------------------------------------------------------------------------
-- 4. SearchMapper.listHits —— 类型 + 年份 + 机构复合筛选
-- ---------------------------------------------------------------------------
\echo '--- S4 listHits(type + year range + organization) ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT a.achievement_id
FROM qry_achievement a
WHERE a.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = :'nature'
  AND a.type = 'PAPER'
  AND a.publication_year >= 2018 AND a.publication_year <= 2026
  AND EXISTS (SELECT 1 FROM qry_contribution_org o
              WHERE o.generation = a.generation AND o.achievement_id = a.achievement_id
                AND o.organization = '机构00001')
ORDER BY a.publication_year DESC NULLS LAST, a.achievement_id
LIMIT 20 OFFSET 0;

-- ---------------------------------------------------------------------------
-- 5. SearchMapper.listHits —— 领域筛选（多对多 JOIN）
-- ---------------------------------------------------------------------------
\echo '--- S5 listHits(topic join) ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT a.achievement_id
FROM qry_achievement a
JOIN qry_achievement_topic t
  ON t.generation = a.generation AND t.achievement_id = a.achievement_id
 AND t.topic_id = 'topic-perf-007'
WHERE a.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = :'nature'
ORDER BY a.publication_year DESC NULLS LAST, a.achievement_id
LIMIT 20 OFFSET 0;

-- ---------------------------------------------------------------------------
-- 6. SearchMapper.searchScholars —— 学者聚合（同名不同人）
-- ---------------------------------------------------------------------------
\echo '--- S6 searchScholars(name like) ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT c.scholar_id, max(c.scholar_name), count(DISTINCT c.achievement_id) AS cnt,
       min(a.publication_year)::int
FROM qry_contribution c
JOIN qry_achievement a
  ON a.generation = c.generation AND a.achievement_id = c.achievement_id
WHERE c.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = :'nature'
  AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
  AND lower(coalesce(c.scholar_name, '')) LIKE '%作者1%'
GROUP BY c.scholar_id
ORDER BY cnt DESC, c.scholar_id
LIMIT 20 OFFSET 0;

-- ---------------------------------------------------------------------------
-- 7. SearchMapper.suggestTitles —— 建议（相似度排序 + LIMIT 6）
-- ---------------------------------------------------------------------------
\echo '--- S7 suggestTitles ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT a.achievement_id, a.title
FROM qry_achievement a
WHERE a.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = :'nature'
  AND (a.title_norm LIKE '%图%' OR similarity(a.title_norm, '图') > 0.08)
ORDER BY (a.title_norm LIKE '图%') DESC, similarity(a.title_norm, '图') DESC,
         a.publication_year DESC NULLS LAST, a.achievement_id
LIMIT 6;

-- ---------------------------------------------------------------------------
-- 8. SearchMapper.listTopicFacets / listOrganizationFacets —— 分面
-- ---------------------------------------------------------------------------
\echo '--- S8a listTopicFacets ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT t.topic_id, max(t.canonical_name), count(DISTINCT t.achievement_id),
       count(DISTINCT t.achievement_id) FILTER (WHERE t.assignment_kind = 'SOURCE'),
       count(DISTINCT t.achievement_id) FILTER (WHERE t.assignment_kind = 'INFERRED')
FROM qry_achievement_topic t
JOIN qry_achievement a
  ON a.generation = t.generation AND a.achievement_id = t.achievement_id
WHERE t.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = :'nature'
GROUP BY t.topic_id
ORDER BY count(DISTINCT t.achievement_id) DESC, t.topic_id
LIMIT 100;

\echo '--- S8b listOrganizationFacets ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT o.organization, count(DISTINCT o.achievement_id)
FROM qry_contribution_org o
JOIN qry_achievement a
  ON a.generation = o.generation AND a.achievement_id = o.achievement_id
WHERE o.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = :'nature'
GROUP BY o.organization
ORDER BY count(DISTINCT o.achievement_id) DESC, o.organization
LIMIT 100;

-- ---------------------------------------------------------------------------
-- 9. AnalyticsMapper 的五个计数 —— 统计总览的主要成本
-- ---------------------------------------------------------------------------
\echo '--- A1 countAchievements ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM qry_achievement a
WHERE a.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = :'nature';

\echo '--- A2 countScholars ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(DISTINCT c.scholar_id)
FROM qry_contribution c
JOIN qry_achievement a ON a.generation = c.generation AND a.achievement_id = c.achievement_id
WHERE c.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED') AND a.data_nature = :'nature';

\echo '--- A3 countContributions ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*)
FROM qry_contribution c
JOIN qry_achievement a ON a.generation = c.generation AND a.achievement_id = c.achievement_id
WHERE c.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = :'nature';

\echo '--- A4 countCoauthorPairs ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM (
  SELECT DISTINCT e.scholar_a, e.scholar_b
  FROM qry_coauthor_edge e
  JOIN qry_achievement a ON a.generation = e.generation AND a.achievement_id = e.achievement_id
  WHERE e.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
    AND a.data_nature = :'nature') pairs;

\echo '--- A5 topOrganizations ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT o.organization, count(DISTINCT o.achievement_id) AS amount
FROM qry_contribution_org o
JOIN qry_achievement a ON a.generation = o.generation AND a.achievement_id = o.achievement_id
WHERE o.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = :'nature'
GROUP BY o.organization ORDER BY amount DESC, o.organization LIMIT 20;

\echo '--- A6 topTopics（来源/推断分列）---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT t.topic_id, max(t.canonical_name), count(DISTINCT t.achievement_id) AS amount,
       count(DISTINCT t.achievement_id) FILTER (WHERE t.assignment_kind = 'SOURCE'),
       count(DISTINCT t.achievement_id) FILTER (WHERE t.assignment_kind = 'INFERRED')
FROM qry_achievement_topic t
JOIN qry_achievement a ON a.generation = t.generation AND a.achievement_id = t.achievement_id
WHERE t.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = :'nature'
GROUP BY t.topic_id ORDER BY amount DESC, t.topic_id LIMIT 20;

\echo '--- A7 byYear / byType / citationsBySource 之一（byYear）---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT a.publication_year AS year, count(*) AS amount
FROM qry_achievement a
WHERE a.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = :'nature'
GROUP BY a.publication_year ORDER BY a.publication_year NULLS FIRST;

-- ---------------------------------------------------------------------------
-- 10. NetworkMapper —— 一跳子图的三条语句
--
--     两侧各自一条（scholar_a IN / scholar_b IN），而不是一条 OR：实测 OR 形状在某些
--     ID 集合上会被规划成「按主键扫整代再过滤」的 2.2 秒计划（见 NetworkMapper 注释）。
-- ---------------------------------------------------------------------------
\echo '--- N1 countNodes ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM (
  SELECT e.scholar_a AS s FROM qry_coauthor_edge e
  JOIN qry_achievement a ON a.generation = e.generation AND a.achievement_id = e.achievement_id
  WHERE e.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
    AND a.data_nature = :'nature'
  UNION
  SELECT e.scholar_b AS s FROM qry_coauthor_edge e
  JOIN qry_achievement a ON a.generation = e.generation AND a.achievement_id = e.achievement_id
  WHERE e.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
    AND a.data_nature = :'nature') nodes;

\echo '--- N2 listEdges (a 端，one hop) ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT e.scholar_a, e.scholar_b, count(DISTINCT e.achievement_id) AS strength
FROM qry_coauthor_edge e
JOIN qry_achievement a ON a.generation = e.generation AND a.achievement_id = e.achievement_id
WHERE e.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = :'nature'
  AND e.scholar_a IN ('sch-fix-tree-root')
GROUP BY e.scholar_a, e.scholar_b
ORDER BY e.scholar_a, e.scholar_b
LIMIT 20000;

\echo '--- N3 listEdges (b 端，one hop) ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT e.scholar_a, e.scholar_b, count(DISTINCT e.achievement_id) AS strength
FROM qry_coauthor_edge e
JOIN qry_achievement a ON a.generation = e.generation AND a.achievement_id = e.achievement_id
WHERE e.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = :'nature'
  AND e.scholar_b IN ('sch-fix-tree-root')
GROUP BY e.scholar_a, e.scholar_b
ORDER BY e.scholar_a, e.scholar_b
LIMIT 20000;

\echo '--- N4 带领域/机构筛选的扩边（走 EXISTS，代价最高的一种）---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT e.scholar_a, e.scholar_b, count(DISTINCT e.achievement_id) AS strength
FROM qry_coauthor_edge e
JOIN qry_achievement a ON a.generation = e.generation AND a.achievement_id = e.achievement_id
WHERE e.generation = :'gen' AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
  AND a.data_nature = :'nature'
  AND e.scholar_a IN ('sch-fix-ring-0001')
  AND EXISTS (SELECT 1 FROM qry_achievement_topic t
              WHERE t.generation = a.generation AND t.achievement_id = a.achievement_id
                AND t.topic_id = 'topic-fix-graph')
GROUP BY e.scholar_a, e.scholar_b
ORDER BY e.scholar_a, e.scholar_b
LIMIT 20000;

\echo '--- N5 listScholarNames（节点视图的姓名与合格成果数）---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT c.scholar_id, max(c.scholar_name), count(DISTINCT c.achievement_id)
FROM qry_contribution c
WHERE c.generation = :'gen'
  AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
  AND c.scholar_id IN ('sch-fix-tree-root', 'sch-fix-tree-c1', 'sch-fix-tree-c2')
GROUP BY c.scholar_id;
