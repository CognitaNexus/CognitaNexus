-- =============================================================================
-- CognitaNexus ingestion-service 迁移 V8
-- 来源业务更新周期：与「任务轮询周期」「失败退避」三者分离
-- 依据：架构 V0.2 §6.1、§6.2；老师指导 WB-13（更新周期不得套用统一定值）
-- =============================================================================

-- 背景：此前的三个"周期"其实只有一个。
--   1. 调度轮询：cn.collect.worker-interval-ms（默认 2000ms），含义是"多久看一次队列"；
--   2. 失败退避：ing_collect_job.next_attempt_at（V5），含义是"这个任务多久之后才允许再试"；
--   3. 来源业务更新：**没有**。任务只有人工创建（默认键 run-YYYY-MM-DD，等于"每天一份"），
--      一旦 SUCCEEDED 就再没有任何东西让它按来源自身的更新节奏重跑。
-- 前两者属于"执行机制"，第三个属于"业务事实"。把它们混成一个数字，就会出现两类错误：
-- 用 2 秒的轮询推断"每 2 秒抓一次学术来源"，或用"每 7 天"这种无处可查的数字当成需求。
--
-- 为什么周期允许为 NULL：'NULL = 未设定' 是唯一诚实的默认值。需求与调研都还没有给出各来源的
-- 更新节奏（WB-13 明确写"月度／半年只是可讨论候选，不对所有字段套用"），此时填任何数字都是
-- 编造。NULL 的行为是"不按周期自动排期"，与迁移前完全一致，因此本迁移不改变任何现有任务的行为。
--
-- 为什么把"取值理由"落库且要求非空：一个没有理由的周期，下次没人能判断它是否还成立。
-- 设置周期必须同时写下依据（来源声明的更新频率／许可条款／人工评估），审计里也留同一句。
--
-- 为什么区分"新记录发现"与"字段刷新"：两者是被来源限流共同约束的两件不同的事。引用量、
-- 姓名写法会变，但发现的节奏通常更慢；用一个数字统一，要么把引用量拖得太旧，要么把新记录的
-- 发现压成无节制的翻页。两列都可以为 NULL，各自独立设定。

ALTER TABLE ing_source_run_config
    ADD COLUMN new_record_interval_minutes  integer,
    ADD COLUMN field_refresh_interval_minutes integer,
    ADD COLUMN update_plan_note             text,
    ADD COLUMN next_due_at                  timestamptz;

ALTER TABLE ing_source_run_config
    -- 周期下限 60 分钟：比这更密的"业务更新"不是更新周期问题，而是采集任务本身该怎么分批。
    -- 上限 365 天：超过一年等于事实上不再更新，应该直接停用来源而不是留一个好看的周期。
    ADD CONSTRAINT ck_ing_source_new_record_interval
        CHECK (new_record_interval_minutes IS NULL
               OR new_record_interval_minutes BETWEEN 60 AND 525600),
    ADD CONSTRAINT ck_ing_source_field_refresh_interval
        CHECK (field_refresh_interval_minutes IS NULL
               OR field_refresh_interval_minutes BETWEEN 60 AND 525600),
    -- 设了周期就必须写理由；两列都为 NULL（未设定）时不要求理由。
    ADD CONSTRAINT ck_ing_source_update_plan_note
        CHECK ((new_record_interval_minutes IS NULL AND field_refresh_interval_minutes IS NULL)
               OR (update_plan_note IS NOT NULL AND length(btrim(update_plan_note)) > 0));

COMMENT ON COLUMN ing_source_run_config.new_record_interval_minutes IS
    '发现新记录的期望周期（分钟）；NULL 表示未设定，不按周期自动排期。不是任务轮询间隔。';
COMMENT ON COLUMN ing_source_run_config.field_refresh_interval_minutes IS
    '历史字段与引用量的期望刷新周期（分钟）；NULL 表示未设定。与"发现新记录"分开设定。';
COMMENT ON COLUMN ing_source_run_config.update_plan_note IS
    '该周期的取值依据（来源声明频率／许可条款／人工评估）；设了周期就必须写，避免出现无出处的数字。';
COMMENT ON COLUMN ing_source_run_config.next_due_at IS
    '下一次允许按业务周期执行任务的时刻；NULL 表示未设定周期，任务随时可跑（由人工创建驱动）。';

-- 执行器的取任务扫描要按到期时间过滤；未设定周期（NULL）的行永远可跑，因此索引只覆盖已设定周期的行。
CREATE INDEX ix_ing_source_run_config_next_due
    ON ing_source_run_config (next_due_at)
    WHERE next_due_at IS NOT NULL;
