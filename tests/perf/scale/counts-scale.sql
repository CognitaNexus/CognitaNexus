-- =============================================================================
-- 阶段六分层计数与体积：一次跑完拿到「成果 / 学者 / 作者关系 / 合作边 / 领域 / 机构」
-- 六项实体数，以及 PG 堆、索引、总计与逐索引体积
--
-- 口径要求（来自 AGENTS.md §3.1）：原始抓取数、暂存数、入库/拒绝数、去重成果实体数、
-- 公开有效数、读投影可检索数必须分别记录。本脚本覆盖其中属于 query 读投影的部分，
-- 并把「数据性质」单列，杜绝把 SIMULATED 当作 REAL 上报。
--
-- 用法：psql -v gen=gen0001 -f tests/perf/scale/counts-scale.sql
-- =============================================================================

\set ON_ERROR_STOP on
\if :{?gen}
\else
  \set gen gen0001
\endif

\pset pager off
\pset footer off

-- ---------------------------------------------------------------------------
-- 1. 实体数与资格分层
-- ---------------------------------------------------------------------------
SELECT '成果-投影行数'  AS metric, count(*)::text AS value FROM qry_achievement WHERE generation = :'gen'
UNION ALL
SELECT '成果-公开且生效', count(*)::text FROM qry_achievement
 WHERE generation = :'gen' AND visibility = 'PUBLIC' AND effective_status = 'EFFECTIVE'
UNION ALL
SELECT '成果-类型 ' || type, count(*)::text FROM qry_achievement
 WHERE generation = :'gen' GROUP BY type
UNION ALL
SELECT '成果-数据性质 ' || data_nature, count(*)::text FROM qry_achievement
 WHERE generation = :'gen' GROUP BY data_nature
UNION ALL
SELECT '成果-随机部分（perf-%）', count(*)::text FROM qry_achievement
 WHERE generation = :'gen' AND achievement_id LIKE 'perf-%'
UNION ALL
SELECT '成果-结构 fixture（fix-%）', count(*)::text FROM qry_achievement
 WHERE generation = :'gen' AND achievement_id LIKE 'fix-%'
UNION ALL
SELECT '学者-去重 scholar_id（全部关系）', count(DISTINCT scholar_id)::text FROM qry_contribution
 WHERE generation = :'gen'
UNION ALL
SELECT '学者-去重 scholar_id（合格关系）', count(DISTINCT scholar_id)::text FROM qry_contribution
 WHERE generation = :'gen' AND relation_state IN ('AUTO', 'SELF_CONFIRMED')
UNION ALL
SELECT '作者关系行' , count(*)::text FROM qry_contribution WHERE generation = :'gen'
UNION ALL
SELECT '作者关系-合格 ' || relation_state, count(*)::text FROM qry_contribution
 WHERE generation = :'gen' GROUP BY relation_state
UNION ALL
SELECT '作者关系-单作者成果数', count(*)::text FROM (
  SELECT achievement_id FROM qry_contribution
   WHERE generation = :'gen' GROUP BY achievement_id HAVING count(*) = 1) s
UNION ALL
SELECT '作者关系-作者数>20 成果数', count(*)::text FROM (
  SELECT achievement_id FROM qry_contribution
   WHERE generation = :'gen' GROUP BY achievement_id HAVING count(*) > 20) s
UNION ALL
SELECT '作者关系-最大作者数', coalesce(max(cnt), 0)::text FROM (
  SELECT count(*) AS cnt FROM qry_contribution
   WHERE generation = :'gen' GROUP BY achievement_id) s
UNION ALL
SELECT '合作边行', count(*)::text FROM qry_coauthor_edge WHERE generation = :'gen'
UNION ALL
SELECT '合作边-不同作者对', count(*)::text FROM (
  SELECT DISTINCT scholar_a, scholar_b FROM qry_coauthor_edge WHERE generation = :'gen') p
UNION ALL
SELECT '合作边-位于公开生效成果上', count(*)::text FROM qry_coauthor_edge e
  JOIN qry_achievement a ON a.generation = e.generation AND a.achievement_id = e.achievement_id
 WHERE e.generation = :'gen' AND a.visibility = 'PUBLIC' AND a.effective_status = 'EFFECTIVE'
UNION ALL
SELECT '领域归属行', count(*)::text FROM qry_achievement_topic WHERE generation = :'gen'
UNION ALL
SELECT '领域-去重 topic_id', count(DISTINCT topic_id)::text FROM qry_achievement_topic WHERE generation = :'gen'
UNION ALL
SELECT '发生机构行', count(*)::text FROM qry_contribution_org WHERE generation = :'gen'
UNION ALL
SELECT '发生机构-去重 organization', count(DISTINCT organization)::text FROM qry_contribution_org WHERE generation = :'gen'
UNION ALL
SELECT '水位-published_revision', published_revision::text FROM qry_consumer_state WHERE consumer_id = 'query'
UNION ALL
SELECT '水位-active_generation', active_generation FROM qry_consumer_state WHERE consumer_id = 'query';

-- ---------------------------------------------------------------------------
-- 2. 表体积（堆 / 索引 / 总计）与 TOAST
-- ---------------------------------------------------------------------------
SELECT c.relname AS table_name,
       to_char(c.reltuples::bigint, 'FM999,999,999') AS est_rows,
       pg_size_pretty(pg_relation_size(c.oid)) AS heap,
       pg_size_pretty(pg_indexes_size(c.oid)) AS indexes,
       pg_size_pretty(pg_total_relation_size(c.oid)) AS total,
       pg_total_relation_size(c.oid) AS total_bytes,
       pg_size_pretty(pg_total_relation_size(c.reltoastrelid)) AS toast
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
  AND c.relkind = 'r'
  AND c.relname IN ('qry_achievement', 'qry_contribution', 'qry_coauthor_edge',
                    'qry_contribution_org', 'qry_achievement_topic',
                    'qry_inbox_event', 'qry_generation', 'qry_consumer_state',
                    'qry_rebuild_run', 'qry_id_redirect', 'qry_graph_projection_checkpoint')
ORDER BY pg_total_relation_size(c.oid) DESC;

-- ---------------------------------------------------------------------------
-- 3. 逐索引体积（回答「索引到底花在哪」，也是重建耗时的解释项）
-- ---------------------------------------------------------------------------
SELECT i.relname AS index_name,
       t.relname AS table_name,
       pg_size_pretty(pg_relation_size(i.oid)) AS size,
       pg_relation_size(i.oid) AS bytes
FROM pg_class i
JOIN pg_index x ON x.indexrelid = i.oid
JOIN pg_class t ON t.oid = x.indrelid
JOIN pg_namespace n ON n.oid = t.relnamespace
WHERE n.nspname = 'public'
  AND t.relname IN ('qry_achievement', 'qry_contribution', 'qry_coauthor_edge',
                    'qry_contribution_org', 'qry_achievement_topic')
ORDER BY pg_relation_size(i.oid) DESC
LIMIT 30;

-- ---------------------------------------------------------------------------
-- 4. 库级体积与膨胀信号（dead tuple 会让「体积」虚高，报告必须能解释）
-- ---------------------------------------------------------------------------
SELECT '数据库体积' AS metric, pg_size_pretty(pg_database_size(current_database())) AS value
UNION ALL
SELECT '当前库 WAL 目录（需在 shell 侧用 du 核对）', pg_size_pretty(coalesce(sum(size), 0))
  FROM pg_ls_waldir()
UNION ALL
SELECT 'shared_buffers', current_setting('shared_buffers')
UNION ALL
SELECT 'work_mem', current_setting('work_mem')
UNION ALL
SELECT 'effective_cache_size', current_setting('effective_cache_size')
UNION ALL
SELECT 'max_parallel_workers_per_gather', current_setting('max_parallel_workers_per_gather')
UNION ALL
SELECT 'server_version', current_setting('server_version');

SELECT relname AS table_name, n_live_tup, n_dead_tup, n_mod_since_analyze,
       to_char(last_analyze, 'YYYY-MM-DD HH24:MI:SS') AS last_analyze,
       to_char(last_vacuum, 'YYYY-MM-DD HH24:MI:SS') AS last_vacuum
FROM pg_stat_user_tables
WHERE relname IN ('qry_achievement', 'qry_contribution', 'qry_coauthor_edge',
                  'qry_contribution_org', 'qry_achievement_topic')
ORDER BY relname;
