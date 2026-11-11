-- =============================================================================
-- CognitaNexus ingestion-service 迁移 V1
-- 采集运行配置、采集任务（租约 + 检查点）、交接暂存、交接批次、运行日志
-- 依据：架构 V0.2 §4.2（服务归属）、§5.3（任务与租约）、§6.2（采集交接）
-- =============================================================================
--
-- 边界声明（架构 §4.2、§6.2 明确要求）：
--   1. 本库只保存「可靠交接」所需的暂存，不作为来源的长期证据；长期证据在 core 的
--      raw_source_record。暂存行在 core 确认持久接收后可依保留策略清理。
--   2. 来源许可与允许展示规则以 core 配置为权威，本库保存的是执行所需副本与配置版本；
--      任务每批开始前仍要重查 core 侧来源启用状态。
--   3. 不保存来源响应原文：只落适配器按字段白名单筛出的规范化记录。不以「保留原始响应」
--      为由持久化来源意外返回的凭证、私人联系方式或与业务无关的个人信息。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 采集运行配置（core 侧来源配置的执行副本）
-- -----------------------------------------------------------------------------
CREATE TABLE ing_source_run_config (
    id                    text PRIMARY KEY,
    source_code           text        NOT NULL,
    display_name          text        NOT NULL,
    adapter_kind          text        NOT NULL,
    base_endpoint         text        NOT NULL,
    query_expression      text        NOT NULL,
    page_size             integer     NOT NULL DEFAULT 100,
    -- 字段白名单：适配器只保留这些键进入 payload；空数组表示不额外限制
    allowed_fields        text[]      NOT NULL DEFAULT ARRAY[]::text[],
    -- 与 core 侧 raw_source_config.config_version 对应的执行版本；变更即代表需重新对账
    config_version        text        NOT NULL,
    license_review_state  text        NOT NULL DEFAULT 'PENDING_REVIEW',
    license_note          text,
    rate_limit_per_minute integer     NOT NULL DEFAULT 30,
    enabled               boolean     NOT NULL DEFAULT false,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_ing_source_run_config_code UNIQUE (source_code),
    -- 已实现的适配器种类。值域刻意只列出确有实现的适配器：留一个没有实现的枚举值，
    -- 会让人误以为该来源已可采集。新增来源时同步实现适配器并扩展此约束。
    CONSTRAINT ck_ing_source_adapter_kind
        CHECK (adapter_kind IN ('CROSSREF', 'OPENALEX')),
    CONSTRAINT ck_ing_source_license_state
        CHECK (license_review_state IN ('PENDING_REVIEW', 'APPROVED', 'REVOKED')),
    CONSTRAINT ck_ing_source_page_size CHECK (page_size BETWEEN 1 AND 200),
    CONSTRAINT ck_ing_source_rate_limit CHECK (rate_limit_per_minute BETWEEN 1 AND 600),
    -- 许可未批准或已撤销的来源不得处于启用状态：把「不得据未获批来源采集」变成库级约束，
    -- 而不是只靠应用层自觉。停用来源仍可保留配置，只是不能跑任务。
    CONSTRAINT ck_ing_source_enabled_requires_license
        CHECK (NOT enabled OR license_review_state = 'APPROVED')
);

COMMENT ON TABLE ing_source_run_config IS
    '采集运行配置：core 为权威，本表为执行副本与配置版本；enabled 必须与许可状态相容';

-- -----------------------------------------------------------------------------
-- 2. 采集任务：租约与检查点分离
-- -----------------------------------------------------------------------------
-- lease_until 解决「同一时刻只能有一个实例在处理」，checkpoint 解决「已经确认交接完成到哪」。
-- 两者语义不同：租约过期可被其他实例接管，检查点只在前移时才更新，且绝不回退。
CREATE TABLE ing_collect_job (
    id                 text PRIMARY KEY,
    source_config_id   text        NOT NULL REFERENCES ing_source_run_config (id) ON DELETE RESTRICT,
    job_key            text        NOT NULL,
    status             text        NOT NULL DEFAULT 'PENDING',
    -- 适配器游标：下一次抓取从这里继续（页码 / offset / next-cursor）
    cursor             text,
    -- 已确认交接完成的游标：只在 core ACK 之后前移
    checkpoint         text,
    total_fetched      integer     NOT NULL DEFAULT 0,
    total_staged       integer     NOT NULL DEFAULT 0,
    total_handed_off   integer     NOT NULL DEFAULT 0,
    total_rejected     integer     NOT NULL DEFAULT 0,
    lease_owner        text,
    lease_until        timestamptz,
    last_error         text,
    created_by         text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    finished_at        timestamptz,
    CONSTRAINT uk_ing_collect_job_key UNIQUE (source_config_id, job_key),
    CONSTRAINT ck_ing_collect_job_status
        CHECK (status IN ('PENDING', 'RUNNING', 'PAUSED', 'SUCCEEDED', 'FAILED'))
);

-- 待处理任务的领取扫描：只找非终态任务
CREATE INDEX ix_ing_collect_job_runnable
    ON ing_collect_job (status, lease_until)
    WHERE status IN ('PENDING', 'RUNNING');

COMMENT ON TABLE ing_collect_job IS
    '采集任务：cursor 为抓取位置，checkpoint 为已确认交接位置；检查点只前移不回退（架构 §6.2 第 5 条）';

-- -----------------------------------------------------------------------------
-- 3. 交接暂存
-- -----------------------------------------------------------------------------
CREATE TABLE ing_staged_record (
    id                  text PRIMARY KEY,
    job_id              text        NOT NULL REFERENCES ing_collect_job (id) ON DELETE CASCADE,
    source_config_id    text        NOT NULL REFERENCES ing_source_run_config (id) ON DELETE RESTRICT,
    -- 稳定交接键：sourceId + sourceRecordId + contentFingerprint。
    -- 指纹只由业务字段计算，不含抓取时刻，因此重试不会造出新的来源版本。
    source_record_id    text        NOT NULL,
    content_fingerprint text        NOT NULL,
    payload             jsonb       NOT NULL,
    -- 数据性质必须显式声明，不允许缺省为 REAL（架构 §6.2、需求三值数据性质）
    data_nature         text        NOT NULL,
    source_updated_at   timestamptz,
    source_published_at date,
    fetched_at          timestamptz NOT NULL DEFAULT now(),
    handoff_state       text        NOT NULL DEFAULT 'STAGED',
    handoff_batch_id    text,
    handoff_attempts    integer     NOT NULL DEFAULT 0,
    last_error          text,
    -- core 返回的接收结论：ACCEPTED / ATTACHED / DUPLICATE / REJECTED
    core_receipt_status text,
    core_achievement_id text,
    CONSTRAINT uk_ing_staged_handoff_key
        UNIQUE (source_config_id, source_record_id, content_fingerprint),
    CONSTRAINT ck_ing_staged_data_nature
        CHECK (data_nature IN ('REAL', 'SIMULATED', 'MIXED')),
    CONSTRAINT ck_ing_staged_handoff_state
        CHECK (handoff_state IN ('STAGED', 'HANDED_OFF', 'FAILED'))
);

CREATE INDEX ix_ing_staged_pending
    ON ing_staged_record (handoff_state, source_config_id)
    WHERE handoff_state <> 'HANDED_OFF';

COMMENT ON TABLE ing_staged_record IS
    '交接暂存：唯一键即稳定交接键，重复抓取命中该键时不产生新行；core 确认后标记 HANDED_OFF';

-- -----------------------------------------------------------------------------
-- 4. 交接批次
-- -----------------------------------------------------------------------------
-- batch_key 是幂等键：交接超时后必须用同一个 batch_key 重试，不能另造批次，
-- 否则同一批记录会以两个批次身份到达 core（架构 §6.2 第 5 条）。
CREATE TABLE ing_handoff_batch (
    id             text PRIMARY KEY,
    job_id         text        NOT NULL REFERENCES ing_collect_job (id) ON DELETE CASCADE,
    batch_key      text        NOT NULL,
    status         text        NOT NULL DEFAULT 'PENDING',
    record_count   integer     NOT NULL,
    attempts       integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_until    timestamptz,
    lease_owner    text,
    acked_at       timestamptz,
    last_error     text,
    core_receipt   jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_ing_handoff_batch_key UNIQUE (batch_key),
    CONSTRAINT ck_ing_handoff_batch_status
        CHECK (status IN ('PENDING', 'ACKED', 'FAILED'))
);

CREATE INDEX ix_ing_handoff_batch_pending
    ON ing_handoff_batch (status, next_attempt_at)
    WHERE status = 'PENDING';

COMMENT ON TABLE ing_handoff_batch IS
    '交接批次：batch_key 为幂等键，超时重试复用同一键；core 202 仅表示持久接收，不表示治理通过';

-- -----------------------------------------------------------------------------
-- 5. 采集运行日志（只追加）
-- -----------------------------------------------------------------------------
CREATE TABLE ing_fetch_log (
    id              text PRIMARY KEY,
    job_id          text REFERENCES ing_collect_job (id) ON DELETE CASCADE,
    source_config_id text,
    phase           text        NOT NULL,
    outcome         text        NOT NULL,
    fetched_count   integer,
    staged_count    integer,
    rejected_count  integer,
    detail          jsonb,
    trace_id        text,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ing_fetch_log_phase
        CHECK (phase IN ('PLAN', 'FETCH', 'PARSE', 'STAGE', 'HANDOFF', 'CHECKPOINT'))
);

CREATE INDEX ix_ing_fetch_log_job ON ing_fetch_log (job_id, occurred_at DESC);

COMMENT ON TABLE ing_fetch_log IS
    '采集运行日志：只追加；用于管理后台展示已抓取、已交接、待核验与失败数，不参与业务判定';

-- -----------------------------------------------------------------------------
-- 6. 管理操作审计（只追加）
-- -----------------------------------------------------------------------------
-- 为什么采集服务自己也要审计：架构 §4.2 规定各服务拥有自己的数据，而 §5.4 要求管理操作可追溯。
-- 采集侧的登记、启用、参数变更、任务创建与暂停都发生在本地，若不在此留痕，事后只能从 core 的
-- 鉴权日志里看到「谁访问过」，看不出「做了什么」。跨服务的统一审计视图由管理端聚合各服务审计，
-- 而不是让 core 成为所有服务写审计的单点。
--
-- 与 core 的 aud_audit_event 保持同样的字段语义，便于管理端合并展示。
CREATE TABLE ing_audit_event (
    id                text PRIMARY KEY,
    actor_account_id  text,
    actor_username    text,
    action            text        NOT NULL,
    target_type       text        NOT NULL,
    target_id         text,
    before_value      jsonb,
    after_value       jsonb,
    reason            text,
    trace_id          text,
    occurred_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_ing_audit_event_target ON ing_audit_event (target_type, target_id, occurred_at DESC);

COMMENT ON TABLE ing_audit_event IS
    '采集管理操作审计：只追加；actor 来自 core 的会话鉴权结论，本服务不自行判定操作者身份';
