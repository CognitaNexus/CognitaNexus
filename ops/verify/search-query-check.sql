-- 检索 SQL 的语义对账与性能对照。
--
-- 用途：改动 SearchMapper 里关键词条件之后，用它证明「结果没变」并且「确实变快」。
-- 两件事必须一起做——只看耗时无法发现结果变少，只看条数也发现不了退化。
--
-- 用法（在库所在的机器上执行，库名 query_db）：
--   PGPASSWORD=... psql -h 127.0.0.1 -U query_owner -d query_db \
--     -v gen=gen0009 -f ops/verify/search-query-check.sql
--
-- 两个函数分别代表两种实现：
--   ref_count  顺序过滤（OR 链）。这是原始实现，作为语义基准，不再出现在生产代码里。
--   idx_count  候选集 UNION。这是当前实现，每一支各走自己的 trigram 索引。
-- 语义基准里作者名那一支是相关子查询，当前实现是不相关的一支——这正是它快的原因，
-- 所以两者必须给出相同的条数，否则就是改写引入了错误。

\set ON_ERROR_STOP on

CREATE OR REPLACE FUNCTION pg_temp.ref_count(g text, kw text)
RETURNS bigint LANGUAGE sql STABLE AS $fn$
  SELECT count(*)
  FROM qry_achievement a
  WHERE a.generation = g
    AND a.effective_status = 'EFFECTIVE'
    AND a.visibility = 'PUBLIC'
    AND (a.title_norm LIKE '%' || kw || '%'
         OR lower(coalesce(a.venue, '')) LIKE '%' || kw || '%'
         OR lower(coalesce(a.abstract_text, '')) LIKE '%' || kw || '%'
         OR lower(coalesce(a.doi, '')) = kw
         OR EXISTS (
           SELECT 1 FROM qry_contribution k
           WHERE k.generation = a.generation
             AND k.achievement_id = a.achievement_id
             AND k.relation_state IN ('AUTO', 'SELF_CONFIRMED')
             AND lower(coalesce(k.scholar_name, '')) LIKE '%' || kw || '%'));
$fn$;

CREATE OR REPLACE FUNCTION pg_temp.idx_count(g text, kw text)
RETURNS bigint LANGUAGE sql STABLE AS $fn$
  SELECT count(*)
  FROM qry_achievement a
  JOIN (
    SELECT a1.achievement_id FROM qry_achievement a1
     WHERE a1.generation = g AND a1.effective_status = 'EFFECTIVE' AND a1.visibility = 'PUBLIC'
       AND a1.title_norm LIKE '%' || kw || '%'
    UNION
    SELECT a2.achievement_id FROM qry_achievement a2
     WHERE a2.generation = g AND a2.effective_status = 'EFFECTIVE' AND a2.visibility = 'PUBLIC'
       AND lower(coalesce(a2.venue, '')) LIKE '%' || kw || '%'
    UNION
    SELECT a3.achievement_id FROM qry_achievement a3
     WHERE a3.generation = g AND a3.effective_status = 'EFFECTIVE' AND a3.visibility = 'PUBLIC'
       AND lower(coalesce(a3.abstract_text, '')) LIKE '%' || kw || '%'
    UNION
    SELECT a4.achievement_id FROM qry_achievement a4
     WHERE a4.generation = g AND a4.effective_status = 'EFFECTIVE' AND a4.visibility = 'PUBLIC'
       AND lower(coalesce(a4.doi, '')) = kw
    UNION
    SELECT k.achievement_id FROM qry_contribution k
     WHERE k.generation = g
       AND k.relation_state IN ('AUTO', 'SELF_CONFIRMED')
       AND lower(coalesce(k.scholar_name, '')) LIKE '%' || kw || '%'
  ) w ON w.achievement_id = a.achievement_id
  WHERE a.generation = g
    AND a.effective_status = 'EFFECTIVE'
    AND a.visibility = 'PUBLIC';
$fn$;

\echo '=== 一、语义对账（diff 必须全为 0）==='
\echo '   覆盖：题名 / 载体 / 摘要 / DOI / 作者名 五支各自单独命中、五支同时命中、以及零命中。'
WITH words(kw) AS (
  VALUES ('train rescheduling'), ('software engineering'), ('learning'),
         ('neural network'), ('deep'), ('wang'), ('zhang'),
         ('10.1007'), ('a'), ('no-such-keyword-xyz-42')
)
SELECT w.kw,
       pg_temp.ref_count(:'gen', w.kw) AS ref_cnt,
       pg_temp.idx_count(:'gen', w.kw) AS idx_cnt,
       pg_temp.idx_count(:'gen', w.kw) - pg_temp.ref_count(:'gen', w.kw) AS diff
FROM words w;

\echo '=== 二、性能对照（当前实现，长词）==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM qry_achievement a
JOIN (
  SELECT a1.achievement_id FROM qry_achievement a1
   WHERE a1.generation = :'gen' AND a1.effective_status = 'EFFECTIVE' AND a1.visibility = 'PUBLIC'
     AND a1.title_norm LIKE '%train rescheduling%'
  UNION
  SELECT a2.achievement_id FROM qry_achievement a2
   WHERE a2.generation = :'gen' AND a2.effective_status = 'EFFECTIVE' AND a2.visibility = 'PUBLIC'
     AND lower(coalesce(a2.venue, '')) LIKE '%train rescheduling%'
  UNION
  SELECT a3.achievement_id FROM qry_achievement a3
   WHERE a3.generation = :'gen' AND a3.effective_status = 'EFFECTIVE' AND a3.visibility = 'PUBLIC'
     AND lower(coalesce(a3.abstract_text, '')) LIKE '%train rescheduling%'
  UNION
  SELECT a4.achievement_id FROM qry_achievement a4
   WHERE a4.generation = :'gen' AND a4.effective_status = 'EFFECTIVE' AND a4.visibility = 'PUBLIC'
     AND lower(coalesce(a4.doi, '')) = 'train rescheduling'
  UNION
  SELECT k.achievement_id FROM qry_contribution k
   WHERE k.generation = :'gen'
     AND k.relation_state IN ('AUTO', 'SELF_CONFIRMED')
     AND lower(coalesce(k.scholar_name, '')) LIKE '%train rescheduling%'
) w ON w.achievement_id = a.achievement_id
WHERE a.generation = :'gen'
  AND a.effective_status = 'EFFECTIVE'
  AND a.visibility = 'PUBLIC';

\echo '=== 三、性能对照（短词，走顺序过滤；此例即 trigram 取不出三元组的情形）==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*)
FROM qry_achievement a
WHERE a.generation = :'gen'
  AND a.effective_status = 'EFFECTIVE'
  AND a.visibility = 'PUBLIC'
  AND (a.title_norm LIKE '%a%'
       OR lower(coalesce(a.venue, '')) LIKE '%a%'
       OR lower(coalesce(a.abstract_text, '')) LIKE '%a%'
       OR lower(coalesce(a.doi, '')) = 'a'
       OR EXISTS (
         SELECT 1 FROM qry_contribution k
         WHERE k.generation = a.generation
           AND k.achievement_id = a.achievement_id
           AND k.relation_state IN ('AUTO', 'SELF_CONFIRMED')
           AND lower(coalesce(k.scholar_name, '')) LIKE '%a%'));
