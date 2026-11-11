-- =============================================================================
-- CognitaNexus core-service 迁移 V12
-- 站内通知最小闭环（ADR-003）
-- 依据：ADR-003、AC-IMPL-FR05-02（查询 ≠ 送达，补通道）、ADR-001 §8.4 第 2 条
-- =============================================================================

CREATE TABLE core_notification (
    id          text        PRIMARY KEY,
    account_id  text        NOT NULL REFERENCES iam_account (id) ON DELETE CASCADE,
    -- kind 是业务事件种类，不是自由文本：接收端按 kind 决定图标与跳转去向。
    -- 为多通道（邮件等）预留语义：将来同一 kind 可同时驱动站内信与邮件。
    kind        text        NOT NULL,
    title       text        NOT NULL,
    body        text        NOT NULL,
    -- 跳转目标（如 ACHIEVEMENT / SCHOLAR / CLAIM_REQUEST）+ 目标 id。
    -- 允许为空：撤权类通知指向的是一批成果，没有单一跳转目标。
    ref_type    text,
    ref_id      text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    read_at     timestamptz,
    CONSTRAINT ck_core_notification_kind CHECK (btrim(kind) <> ''),
    CONSTRAINT ck_core_notification_title CHECK (btrim(title) <> ''),
    CONSTRAINT ck_core_notification_body CHECK (btrim(body) <> '')
);

-- 列表主查询：按账号倒序取最近通知。账号量小，普通索引即可。
CREATE INDEX idx_core_notification_account
    ON core_notification (account_id, created_at DESC);

-- 未读数：部分索引只为未读行服务，账号通知量大后仍能 O(未读数) 计数。
CREATE INDEX idx_core_notification_unread
    ON core_notification (account_id)
    WHERE read_at IS NULL;

COMMENT ON TABLE core_notification IS
    '站内通知。只追加 + 标记已读，不支持编辑与删除（ADR-003 §2）；'
    '系统单向写入，用户之间不能互发。';
COMMENT ON COLUMN core_notification.kind IS
    '事件种类：CLAIM_DECIDED / PORTAL_CHANGE_DECIDED / ACHIEVEMENT_MERGED / RELATION_DECIDED / '
    'SOURCE_LICENSE_REVOKED。';
