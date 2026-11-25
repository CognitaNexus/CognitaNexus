-- =============================================================================
-- 批量导入的索引开关：先落数据、再建索引
--
-- 为什么需要：500 万行逐条 INSERT 时每行都要维护 6~8 个索引，索引维护通常占导入时间的
-- 一半以上，而且随机 IO 让 B-Tree 页分裂更严重、表也更膨胀。先把数据写进堆、再一次性
-- 建索引，得到的表体积更接近真实生产（生产的一次性重建也是这样做的），并且能把
-- 「导入时间」与「索引构建时间」分开报——任务要求这两项分开记录。
--
-- 保留主键索引：主键承载 (generation, *_id) 的幂等性。删掉它虽然更快，但会让
-- 「重复执行会先清空再重建」这条不变式变成靠脚本自觉；宁可慢一点也要保住约束。
--
-- 快照而不是写死 DDL：索引定义来自 pg_indexes，随迁移自动跟随。
-- 若迁移新增索引，本脚本无需修改；若有人手工改了索引，快照也会如实体现在重建里。
--
-- 用法：
--   psql -v mode=drop    -f tests/perf/scale/pg-index-toggle.sql
--   psql -v mode=restore -f tests/perf/scale/pg-index-toggle.sql
-- =============================================================================

\set ON_ERROR_STOP on
\if :{?mode}
\else
  \set mode drop
\endif
\if :{?maintenance_mem}
\else
  \set maintenance_mem 1GB
\endif

\timing on
\pset pager off

CREATE SCHEMA IF NOT EXISTS perf;

CREATE TABLE IF NOT EXISTS perf.index_snapshot (
    index_name  text PRIMARY KEY,
    table_name  text NOT NULL,
    ddl         text NOT NULL,
    captured_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE perf.index_snapshot IS
    '容量压测期间被临时摘下的索引定义快照。与业务无关，不属于 query-service 的迁移对象。';

-- 受管的表（与 capacity-model.mjs 的 PG_PER_ROW 完全一致）。
-- 刻意写成三处内联字面量而不是一个 \set 变量：psql 变量里再嵌单引号要靠双写转义，
-- 一旦写错会静默变成「表名带引号、匹配不到任何索引」，而脚本仍然显示成功。
-- 三处重复的代价远小于这种静默失败。

-- psql 的 \if 只接受布尔量，不接受字符串比较，因此先把 mode 折成 1/0 变量。
SELECT (:'mode' = 'drop')::int AS mode_drop \gset
SELECT (:'mode' = 'restore')::int AS mode_restore \gset
\if :mode_drop
\else
\if :mode_restore
\else
DO $$ BEGIN RAISE EXCEPTION 'mode 必须是 drop 或 restore'; END $$;
\endif
\endif

\if :mode_drop

-- 1) 快照「不承载任何约束」的索引（重复执行不会覆盖快照：第二次已查不到这些索引）
--
-- 为什么不能只按名字过滤 `%_pkey`：本 schema 的主键是以显式约束名建的
-- （CONSTRAINT pk_qry_achievement PRIMARY KEY），PostgreSQL 沿用约束名做索引名，
-- 于是索引叫 pk_qry_achievement 而不是 qry_achievement_pkey。按名字过滤会漏掉它，
-- 随后 DROP INDEX 直接报 "cannot drop index ... because constraint requires it"。
-- 所以这里按「是否被约束引用」判断（pg_constraint.conindid），而不是按名字猜。
INSERT INTO perf.index_snapshot (index_name, table_name, ddl)
SELECT c.relname, t.relname, pg_get_indexdef(i.indexrelid)
FROM pg_index i
JOIN pg_class c ON c.oid = i.indexrelid
JOIN pg_class t ON t.oid = i.indrelid
JOIN pg_namespace n ON n.oid = t.relnamespace
WHERE n.nspname = 'public'
  AND t.relname IN ('qry_achievement', 'qry_contribution', 'qry_coauthor_edge',
                    'qry_contribution_org', 'qry_achievement_topic')
  AND NOT i.indisprimary
  AND NOT EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conindid = i.indexrelid)
ON CONFLICT (index_name) DO UPDATE SET ddl = EXCLUDED.ddl, captured_at = now();

SELECT '快照索引数（累积）' AS metric, count(*)::text AS value FROM perf.index_snapshot;

-- 2) 摘掉它们（DROP 走 \gexec：一条语句一行，便于逐条执行并保留输出）
SELECT format('DROP INDEX IF EXISTS public.%I', index_name)
FROM perf.index_snapshot
WHERE table_name IN ('qry_achievement', 'qry_contribution', 'qry_coauthor_edge',
                     'qry_contribution_org', 'qry_achievement_topic')
ORDER BY table_name, index_name
\gexec

SELECT '剩余可摘索引数（应为 0）' AS metric, count(*)::text AS value
FROM pg_index i
JOIN pg_class t ON t.oid = i.indrelid
JOIN pg_namespace n ON n.oid = t.relnamespace
WHERE n.nspname = 'public'
  AND t.relname IN ('qry_achievement', 'qry_contribution', 'qry_coauthor_edge',
                    'qry_contribution_org', 'qry_achievement_topic')
  AND NOT i.indisprimary
  AND NOT EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conindid = i.indexrelid);

\endif

\if :mode_restore

SET maintenance_work_mem = :'maintenance_mem';
SET max_parallel_maintenance_workers = 0;   -- 2 核机器上并行建索引只会互相抢 CPU

-- 重建：逐个执行（不并发），这样每条索引的耗时都能单独读出来
SELECT ddl
FROM perf.index_snapshot
WHERE table_name IN ('qry_achievement', 'qry_contribution', 'qry_coauthor_edge',
                     'qry_contribution_org', 'qry_achievement_topic')
ORDER BY table_name, index_name
\gexec

SELECT '重建后可摘索引数（应等于快照数）' AS metric, count(*)::text AS value
FROM pg_index i
JOIN pg_class t ON t.oid = i.indrelid
JOIN pg_namespace n ON n.oid = t.relnamespace
WHERE n.nspname = 'public'
  AND t.relname IN ('qry_achievement', 'qry_contribution', 'qry_coauthor_edge',
                    'qry_contribution_org', 'qry_achievement_topic')
  AND NOT i.indisprimary
  AND NOT EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conindid = i.indexrelid);

-- 重建索引后必须重新收集统计信息：索引在、统计信息旧，规划器仍然会给出错误的计划。
ANALYZE qry_achievement;
ANALYZE qry_contribution;
ANALYZE qry_coauthor_edge;
ANALYZE qry_contribution_org;
ANALYZE qry_achievement_topic;

\endif
