-- 摘要回填的进度与「补了到底有没有用」对账。
--
-- 用途：摘要回填（ingestion 的 AbstractHydrationWorker → core 的 /internal/v1/paper-abstracts）
-- 是每天在后台慢慢推进的任务。光看日志里的「写入=158」看不出覆盖率是否真的在涨，也看不出
-- 「来源没有」的那批是不是被误记成了「补过了」。这个脚本把四件事分开报：
--
--   1. 覆盖率：公开生效论文里，有摘要的占比（给用户看的就是这个数）。
--   2. 待补：口径与 AchievementMapper.countPendingAbstracts 逐条一致。这个数应随每轮下降，
--      停止下降即为任务停摆；它与 GET /internal/v1/papers/pending-abstracts 的 remaining 必须相等。
--   3. 已尝试但来源没有：abstract_checked_at 非空、正文为空。这批不该回到待补清单，否则会永久
--      占住前排，把真正能补的挤到后面，而日志上一切正常。
--   4. 每小时的推进量：停止增长就是停了。
--
-- 用法（库所在机器上执行，库名 core_db）：
--   PGPASSWORD=... psql -h 127.0.0.1 -U core_app -d core_db -f ops/verify/abstract-backfill-check.sql
--
-- 为什么口径要抄一遍 SQL 而不是「大致相当」：两个说法差一条过滤条件（比如 doi 与 doi_norm），
-- 「剩余待补」就会永远停在某个正数上，看起来像任务坏了，实际是脚本和代码各说各话。

\set ON_ERROR_STOP on

\echo '=== 1. 覆盖率（公开 + 生效 + 未合并 + 有 DOI 的论文）==='
SELECT count(*) AS in_scope,
       count(*) FILTER (
         WHERE p.abstract_text IS NOT NULL AND btrim(p.abstract_text) <> ''
       ) AS with_abstract,
       count(*) FILTER (
         WHERE p.abstract_text IS NULL OR btrim(p.abstract_text) = ''
       ) AS without_abstract,
       round(
         100.0 * count(*) FILTER (
           WHERE p.abstract_text IS NOT NULL AND btrim(p.abstract_text) <> ''
         ) / greatest(count(*), 1),
         2
       ) AS coverage_pct
FROM core_paper p
JOIN core_achievement a ON a.id = p.achievement_id
WHERE p.doi_norm IS NOT NULL
  AND a.visibility = 'PUBLIC'
  AND a.effective_status = 'EFFECTIVE'
  AND a.merged_into_id IS NULL;

\echo '=== 2. 待补（口径 = countPendingAbstracts，必须等于接口的 remaining）==='
SELECT count(*) AS pending
FROM core_paper p
JOIN core_achievement a ON a.id = p.achievement_id
WHERE p.abstract_checked_at IS NULL
  AND (p.abstract_text IS NULL OR btrim(p.abstract_text) = '')
  AND p.doi_norm IS NOT NULL
  AND a.visibility = 'PUBLIC'
  AND a.effective_status = 'EFFECTIVE'
  AND a.merged_into_id IS NULL;

\echo '=== 3. 已尝试但来源没有（这批不再回到待补）==='
SELECT count(*) AS checked_but_source_empty,
       min(abstract_checked_at) AS first_attempt,
       max(abstract_checked_at) AS last_attempt
FROM core_paper
WHERE abstract_checked_at IS NOT NULL
  AND (abstract_text IS NULL OR btrim(abstract_text) = '');

\echo '=== 4. 近 24 小时每小时推进量 ==='
SELECT date_trunc('hour', abstract_checked_at) AS hour,
       count(*) AS checked,
       count(*) FILTER (
         WHERE abstract_text IS NOT NULL AND btrim(abstract_text) <> ''
       ) AS filled
FROM core_paper
WHERE abstract_checked_at >= now() - interval '24 hours'
GROUP BY 1
ORDER BY 1 DESC
LIMIT 24;

\echo '=== 5. 待补按 DOI 前缀分布（决定还要不要继续跑）==='
-- 实测 10.1016（Elsevier）系普遍不提供摘要，10.2139（SSRN）也常缺；其余前缀约半数可得。
-- 前缀取自 DOI 注册前缀，不是人工标注，只用来判断「剩下这堆值不值得继续消耗配额」。
SELECT split_part(p.doi_norm, '/', 1) AS doi_prefix,
       count(*) AS pending
FROM core_paper p
JOIN core_achievement a ON a.id = p.achievement_id
WHERE p.abstract_checked_at IS NULL
  AND (p.abstract_text IS NULL OR btrim(p.abstract_text) = '')
  AND p.doi_norm IS NOT NULL
  AND a.visibility = 'PUBLIC'
  AND a.effective_status = 'EFFECTIVE'
  AND a.merged_into_id IS NULL
GROUP BY 1
ORDER BY 2 DESC
LIMIT 10;
