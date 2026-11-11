-- 交接批次对账：回答「交付有没有丢数据」这一个问题。
--
-- 为什么要有这个脚本：批次状态机里 FAILED 与「数据丢了」不是一回事，但看统计数字时很容易混。
-- 线上曾出现 127 个 FAILED 批次（声称 6542 条记录），一眼看像丢了一批数据；实际核下来是重试
-- 留下的空壳批次——批次行建了、记录已经随另一批次交付，于是「按批次查记录」得到空集合，
-- 被判定成「数据不一致」并标记失败。这类空壳不影响目录，但会长期污染监控数字。
--
-- 判据分三层，从粗到细：
--   1. 状态分布：FAILED 批次的条数合计，与真正挂在 FAILED 批次下的记录数（应为 0）；
--   2. 记录去向：失败批次所属任务的暂存记录，最终挂在 ACKED 批次上还是根本没挂；
--   3. 账目核对：每个任务「暂存总数 = 挂在 ACKED 批次上的记录数」，差额就是真丢的记录。
--
-- 跑法（在服务器上，用只读账号）：
--   set -a; . /home/ubuntu/cn/conf/cn-env.sh; set +a
--   PGPASSWORD=$CN_INGESTION_DB_PASSWORD psql -h 127.0.0.1 -U ingestion_app -d ingestion_db \
--     -f audit-handoff-backlog.sql
--
-- 结论口径：第 2 步「挂在 FAILED 批次下」与第 3 步「未挂批次」都必须是 0；
-- 不是 0 才是真的需要人工处置的数据缺口。

\pset border 0
\echo '--- 1. 批次状态分布 ---'
SELECT status,
       count(*) AS batches,
       sum(record_count) AS claimed_records,
       min(created_at)::date AS first_day,
       max(created_at)::date AS last_day
FROM ing_handoff_batch
GROUP BY 1
ORDER BY 1;

\echo '--- 2. 失败批次的错误归类 ---'
SELECT substring(coalesce(last_error, '<null>') from 1 for 80) AS last_error,
       count(*) AS batches,
       max(attempts) AS max_attempts
FROM ing_handoff_batch
WHERE status = 'FAILED'
GROUP BY 1
ORDER BY 2 DESC;

\echo '--- 3. 记录实际挂在哪种批次下（应全部在 ACKED，未挂批次应为 0）---'
SELECT coalesce(b.status, '<未挂批次>') AS batch_status,
       count(*) AS records,
       count(DISTINCT r.job_id) AS jobs
FROM ing_staged_record r
LEFT JOIN ing_handoff_batch b ON b.id = r.handoff_batch_id
GROUP BY 1
ORDER BY 2 DESC;

\echo '--- 4. 逐任务核对暂存与交付（差额非 0 才是真缺口）---'
SELECT j.id AS job_id,
       j.status AS job_status,
       count(*) FILTER (WHERE r.handoff_state = 'HANDED_OFF') AS handed_off,
       count(*) FILTER (WHERE r.handoff_state = 'STAGED') AS still_staged,
       count(*) FILTER (WHERE r.handoff_state = 'FAILED') AS failed,
       count(*) FILTER (WHERE b.status = 'ACKED') AS on_acked_batch,
       count(*) FILTER (WHERE b.status = 'FAILED') AS on_failed_batch,
       count(*) FILTER (WHERE b.id IS NULL) AS orphan
FROM ing_staged_record r
JOIN ing_collect_job j ON j.id = r.job_id
LEFT JOIN ing_handoff_batch b ON b.id = r.handoff_batch_id
GROUP BY j.id, j.status
HAVING count(*) FILTER (WHERE r.handoff_state <> 'HANDED_OFF') > 0
    OR count(*) FILTER (WHERE b.id IS NULL) > 0
ORDER BY 5 DESC;

\echo '--- 5. 到期未重试的 PENDING 批次（补交接巡逻应当已经收掉它们）---'
SELECT b.id, b.job_id, j.status AS job_status, b.record_count, b.attempts,
       b.next_attempt_at, b.last_error
FROM ing_handoff_batch b
JOIN ing_collect_job j ON j.id = b.job_id
WHERE b.status = 'PENDING' AND b.next_attempt_at <= now()
ORDER BY b.next_attempt_at
LIMIT 20;
