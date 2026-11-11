-- =============================================================================
-- CognitaNexus core-service 迁移 V21
-- 账号注销申请（合规向）
-- 依据：需求基线「账号管理」扩展项；架构 V0.2 §10.2
-- =============================================================================
--
-- 三个决定，都是「不做」比「做」更重要：
--
-- 1. 注销**不删数据**，而是停用账号（status='DISABLED'）+ 撤销角色 + 提升认证版本。
--    原因：这个账号可能是一批认领申请、成果提交、审计记录的 actor。物理删除会让
--    「谁在什么时候批准了这条成果」变成悬空的 actor_id；级联删 audit 又等于销毁审计。
--    停用之后账号无法登录、会话立即失效、邮箱/用户名不再占用待定——用户看到的效果与注销一致。
--
-- 2. 有冷静期。申请时写入 effective_at（默认 +7 天），管理员在冷静期内不能执行，
--    本人可以随时撤销。防止「一气之下注销，第二天后悔但数据已经停了」。
--
-- 3. 同一账号同时只能有一个 PENDING 申请。用部分唯一索引保证，不靠应用层判断——
--    并发点两次「申请注销」时，应用层的先查后插会同时通过。
CREATE TABLE core_account_closure_request (
    id            text        PRIMARY KEY,
    account_id    text        NOT NULL REFERENCES iam_account (id) ON DELETE CASCADE,
    reason        text        NOT NULL,
    status        text        NOT NULL DEFAULT 'PENDING',
    requested_at  timestamptz NOT NULL DEFAULT now(),
    -- 冷静期结束时刻。到点之前管理员执行会被拒绝；本人可随时撤销。
    effective_at  timestamptz NOT NULL,
    decided_at    timestamptz,
    decided_by    text        REFERENCES iam_account (id) ON DELETE RESTRICT,
    decision_note text,
    CONSTRAINT ck_closure_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_closure_reason CHECK (btrim(reason) <> '')
);

-- 同一账号只能有一个待处理申请。
CREATE UNIQUE INDEX uk_core_closure_open
    ON core_account_closure_request (account_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_core_closure_pending
    ON core_account_closure_request (requested_at)
    WHERE status = 'PENDING';

COMMENT ON TABLE core_account_closure_request IS
    '账号注销申请。执行注销 = 停用账号 + 撤销角色 + 提升认证版本；不物理删除任何业务数据。';
COMMENT ON COLUMN core_account_closure_request.effective_at IS
    '冷静期结束时刻；在此之前管理员不能执行注销，本人可撤销申请';
COMMENT ON COLUMN core_account_closure_request.status IS
    'PENDING 待处理 / APPROVED 已执行注销 / REJECTED 驳回 / CANCELLED 本人撤销';
