-- =============================================================================
-- CognitaNexus ingestion-service 迁移 V9
-- 采集任务的「停止」与「失败重试」
-- 依据：SRS V1.1 §6.2（待执行 → 运行中 → 成功/失败/已停止；失败 → 重试 → 运行中/失败）、
--       AC-IMPL-FR08-02、NF04
-- =============================================================================
--
-- 迁移前只有五种状态（PENDING/RUNNING/PAUSED/SUCCEEDED/FAILED），其中 FAILED 是终态：
-- 一个抓了 10 万条、在第 9 万条遇上上游 503 的任务，既不能续跑，也不能明确终止，只能留在
-- 列表里当一条「失败记录」，然后人工建一条新任务从头再抓——这既浪费配额，也让「失败点在哪」
-- 这个问题失去意义（AC-IMPL-FR08-02 要求的正是「能定位失败点」并重试）。
--
-- 本迁移补两件事：
--   1. STOPPED：与 PAUSED（可恢复）不同，它是「不再继续这个任务」的终态。已交接的记录保留，
--      未交接的暂存记录保留，历史一条不删。要接着抓应当新建任务——停止的语义就是「别再用这个
--      游标继续」，若允许从 STOPPED 恢复，停止与暂停就没有区别了。
--   2. retry_count：失败重试次数。它与 consecutive_failures 不是一回事：
--      consecutive_failures 是「连续抓取失败」的退避计数（V5），会在成功一次后清零；
--      retry_count 是「这个人一共让这个任务从失败里重来过几次」，只增不减，用于审计与
--      「别拿重试当同步机制」的观察。
--
-- 为什么重试不需要新表：重试不是一次新的执行记录，而是把同一条任务放回队列，检查点与统计
-- 计数都保持不变。给它单独建表会让「这个任务抓到哪了」分裂成两处事实。

ALTER TABLE ing_collect_job
    ADD COLUMN retry_count integer NOT NULL DEFAULT 0;

ALTER TABLE ing_collect_job
    DROP CONSTRAINT ck_ing_collect_job_status,
    ADD CONSTRAINT ck_ing_collect_job_status
        CHECK (status IN ('PENDING', 'RUNNING', 'PAUSED', 'SUCCEEDED', 'FAILED', 'STOPPED')),
    ADD CONSTRAINT ck_ing_collect_job_retry_count CHECK (retry_count >= 0);

COMMENT ON COLUMN ing_collect_job.retry_count IS
    '人工触发的失败重试次数，只增不减。与 consecutive_failures（退避计数，成功一次清零）无关。';

-- 领取租约的扫描条件必须同步：STOPPED 与 SUCCEEDED/FAILED 一样不可领取。
-- 原索引已经按 status IN ('PENDING','RUNNING') 建了部分索引（V1/V5），新增状态不影响它，
-- 因为 STOPPED 不在索引条件里。这里只需确认没有任何地方按「非终态」的宽条件扫描：
-- 全库检索 `status <> 'SUCCEEDED'` 一类写法为零，故无需改索引。

-- 重试是「有意的重来」，不是「按周期自动排期」：把 next_attempt_at 清空，
-- 让重试不被上一轮的退避窗口挡住（人点重试的意图通常就是「上游可能已经恢复」）。
COMMENT ON COLUMN ing_collect_job.next_attempt_at IS
    '抓取失败后的退避截止时刻（V5）。人工重试会把它清空；清空表示不受退避窗口限制。';
