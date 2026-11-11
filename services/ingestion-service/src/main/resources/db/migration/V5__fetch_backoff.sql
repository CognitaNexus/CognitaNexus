-- =============================================================================
-- CognitaNexus ingestion-service 迁移 V5
-- 抓取失败的退避：连续失败计数 + 下一次允许尝试的时刻
-- 依据：架构 V0.2 §6.1、§6.2（来源限流与失败重试）
-- =============================================================================

-- 背景：退避此前只实现在交接批次上（1、2、4、8… 秒，上限取运行参数），抓取失败没有。
-- 任务由固定间隔（默认 2 秒）的调度驱动，因此上游持续失败时——例如来源对某个查询串稳定返回
-- 400——同一个任务每 2 秒重试一次且永不停止，每轮还从同一个游标重新翻页（现场一轮的典型
-- 计数是「暂存 4 条、重复 396 条」）。这不是「重试得有点勤」，而是把一次持续故障放大成持续
-- 负载：白耗上游配额，把运行日志淹掉，也让真正需要人看的「来源坏了」这一个事实淹没在重复行里。
--
-- 为什么把「下一次允许尝试的时刻」落库，而不是在调度线程里 sleep：sleep 会占住调度线程，
-- 让同一批里其它健康的任务跟着一起等。落库之后退避是每个任务自己的属性，多实例部署下也天然
-- 一致，且任务列表上可以直接看到「它为什么没在跑」。
--
-- 为什么单独记「连续失败次数」而不是复用 total_fetched 之类：退避时长只应由「一直没成功」
-- 这件事决定。用累计计数做退避，会让一个已经跑了很久、偶发失败一次的任务直接跳到最大退避。

ALTER TABLE ing_collect_job
    ADD COLUMN consecutive_failures integer NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at      timestamptz;

ALTER TABLE ing_collect_job
    ADD CONSTRAINT ck_ing_collect_job_consecutive_failures CHECK (consecutive_failures >= 0);

COMMENT ON COLUMN ing_collect_job.consecutive_failures IS
    '连续抓取失败次数：成功抓到一页即归零。仅用于计算退避时长，不影响检查点（架构 §6.2）。';
COMMENT ON COLUMN ing_collect_job.next_attempt_at IS
    '下一次允许尝试的时刻；NULL 表示立即可试。退避在取任务时过滤，不靠调度线程等待。';

-- 取任务时会带上 next_attempt_at 的过滤条件，索引与既有可执行扫描保持同一组条件。
CREATE INDEX ix_ing_collect_job_next_attempt
    ON ing_collect_job (next_attempt_at)
    WHERE status IN ('PENDING', 'RUNNING');
