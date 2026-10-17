-- =============================================================================
-- CognitaNexus core-service 迁移 V11
-- 把「暂停采集」与「撤销展示许可」拆成两件独立的事（ADR-001 方案 A）
-- 依据：ADR-001、AC-FR01-S01-02、BR-606、AC-IMPL-NF07-01
-- =============================================================================
--
-- 为什么必须拆：
--   在此之前 raw_source_config.enabled 一个布尔同时表达两种意图。运维为了「上游限流，先别抓」
--   把它置 false，系统却把「唯一来源是该来源」的公开成果整批改成 RESTRICTED 并推进公开水位，
--   于是检索、统计、合作网络与旧链接同时失效。SRS 要求来源停用后既有合法成果仍可检索。
--
-- 本迁移做四件事，都是为「拆开」服务的：
--   1. 来源级展示许可状态独立成列（enabled 退回纯采集开关）；
--   2. 公开资格 = 至少一个仍具展示许可的来源（由查询层表达，见 AchievementMapper）；
--   3. 撤权成为可查询、可续跑的作业，不再是「一次事务里最多改 5000 条」；
--   4. 成果记录「我是因为哪个来源撤权才被收紧」，恢复许可时才敢只放开这些，
--      不碰因治理未通过或人工限制而 RESTRICTED 的成果。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 来源级展示许可
-- -----------------------------------------------------------------------------
ALTER TABLE raw_source_config
    ADD COLUMN display_license_status text NOT NULL DEFAULT 'GRANTED',
    ADD COLUMN display_license_changed_at timestamptz,
    ADD COLUMN display_license_note text;

ALTER TABLE raw_source_config
    ADD CONSTRAINT ck_raw_source_config_display_license
        CHECK (display_license_status IN ('GRANTED', 'REVOKED')),
    -- 撤销必须留时间点：只留一个状态位时，事后无法回答「这条成果是撤权之后才下线的，还是之前」
    ADD CONSTRAINT ck_raw_source_config_display_license_changed
        CHECK (display_license_status = 'GRANTED' OR display_license_changed_at IS NOT NULL);

COMMENT ON COLUMN raw_source_config.display_license_status IS
    '展示许可：GRANTED 允许依赖它的成果公开；REVOKED 不再允许。与 enabled（是否继续采集）无关——'
    '暂停采集不动它，撤销许可也不停止采集（仍可为已授权范围更新元数据）。ADR-001 §3 方案 A。';
COMMENT ON COLUMN raw_source_config.enabled IS
    '采集开关。V11 起只影响任务调度，不再触发任何可见性收紧；撤权走 display_license_status。';

-- 历史数据的处理：全部置 GRANTED。
-- 这不是「默认允许」，而是「不改变既有事实」——V11 之前 enabled=false 的那批来源，其独占成果
-- 已经被旧逻辑收紧成 RESTRICTED 了，把许可标成 GRANTED 不会让任何东西重新公开。
-- 反过来若把停用来源标成 REVOKED，则是对历史操作意图的臆测（暂停与撤权当时不可区分）。
UPDATE raw_source_config SET display_license_status = 'GRANTED' WHERE display_license_status IS NULL;

-- -----------------------------------------------------------------------------
-- 2. 撤权作业
-- -----------------------------------------------------------------------------
CREATE TABLE core_source_display_revocation (
    id                  text        PRIMARY KEY,
    source_id           text        NOT NULL REFERENCES raw_source_config (id) ON DELETE RESTRICT,
    requested_by        text        NOT NULL,
    reason              text        NOT NULL,
    -- 撤权那一刻「仅依赖该来源且仍公开」的条数。它是完成判据的基数：
    -- 作业结束时 tightened_count 必须等于它，且残留核对必须为 0。
    target_count        bigint      NOT NULL DEFAULT 0,
    tightened_count     bigint      NOT NULL DEFAULT 0,
    status              text        NOT NULL,
    last_error          text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    finished_at         timestamptz,
    CONSTRAINT ck_core_source_revocation_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'RUNNING_RESTORE')),
    CONSTRAINT ck_core_source_revocation_counts
        CHECK (tightened_count >= 0 AND target_count >= 0),
    CONSTRAINT ck_core_source_revocation_reason CHECK (btrim(reason) <> '')
);

COMMENT ON TABLE core_source_display_revocation IS
    '来源展示许可撤销作业。存在它的理由：撤权可能涉及上百万条成果，一次大事务既改不完也回滚不起；'
    '而「请求已受理但一条都没落地」与「已全部落地」在响应上必须能区分。ADR-001 §4。';
COMMENT ON COLUMN core_source_display_revocation.target_count IS
    '受理时刻统计的目标条数。执行期间该来源可能又有新成果公开（采集已停，但治理动作可能放开旧成果），'
    '因此完成判据用「残留核对为 0」而不是「tightened_count = target_count」。';
COMMENT ON COLUMN core_source_display_revocation.status IS
    'RUNNING 执行中；SUCCEEDED 残留核对为 0；FAILED 中断且可续跑（tightened_count 保留已处理进度）；'
    'RUNNING_RESTORE 恢复许可的分批回填执行中。';

-- 同一来源同时只允许一个未完成作业：两个并发撤权会把 target_count 各算一遍，
-- 完成判据随之后互相矛盾。
CREATE UNIQUE INDEX uk_core_source_revocation_active
    ON core_source_display_revocation (source_id)
    WHERE status IN ('RUNNING', 'FAILED', 'RUNNING_RESTORE');

CREATE INDEX ix_core_source_revocation_source
    ON core_source_display_revocation (source_id, created_at DESC);

-- -----------------------------------------------------------------------------
-- 3. 成果上的「收紧原因」
-- -----------------------------------------------------------------------------
ALTER TABLE core_achievement
    ADD COLUMN display_restriction_source_id text
        REFERENCES raw_source_config (id) ON DELETE RESTRICT;

COMMENT ON COLUMN core_achievement.display_restriction_source_id IS
    '该成果是因为哪个来源撤销展示许可而被收紧为 RESTRICTED 的。恢复许可时只放开这类成果，'
    '不碰因治理未通过、人工限制或另一来源撤权而受限的对象。历史撤权（V11 之前）无法归因，保持 NULL：'
    '宁可少恢复（需要人工复核），也不能误放开。';

-- 部分索引：绝大多数成果该列为 NULL，只为「按来源找回被撤权收紧的成果」建索引
CREATE INDEX ix_core_achievement_display_restriction
    ON core_achievement (display_restriction_source_id)
    WHERE display_restriction_source_id IS NOT NULL;

-- -----------------------------------------------------------------------------
-- 4. 按来源找成果的索引
-- -----------------------------------------------------------------------------
-- V1 只建了 UNIQUE (achievement_id, source_record_id)。按 source_id 反查（撤权、独占判定、
-- 来源溯源）此前是全表扫。这不是为了压测才加的：撤权作业每批都要按来源取待处理集合。
CREATE INDEX ix_core_achievement_source_source
    ON core_achievement_source (source_id, achievement_id);
