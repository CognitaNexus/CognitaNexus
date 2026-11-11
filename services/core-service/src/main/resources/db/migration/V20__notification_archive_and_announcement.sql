-- =============================================================================
-- CognitaNexus core-service 迁移 V20
-- 通知中心补全（归档、按类型筛选）与系统公告
-- 依据：ADR-003、需求基线「站内通知」扩展项
-- =============================================================================
--
-- 关于「删除」为什么做成归档而不是 DELETE：
--   ADR-003 §2 定的是「通知只追加，不支持编辑与删除」。这条不是懒，是审计需要：一个账号
--   「收到过什么」是平台行为的证据，删掉之后，「用户说没收到通知」与「通知确实没发」就再也
--   分不开了。用户视角的「删除」诉求同样真实（列表太长），所以这里加 archived_at：
--   行还在，只是从该账号的列表与未读数里消失。两者都满足，且没有信息损失。
--   注释与文档里统一叫「移除」，不叫「删除」，避免后来的人以为真的删了。

ALTER TABLE core_notification
    ADD COLUMN archived_at timestamptz;

COMMENT ON COLUMN core_notification.archived_at IS
    '用户从自己的列表移除的时刻；置位后列表与未读数都不再包含该行，数据保留供审计';

-- 列表主查询要同时过滤归档：部分索引只覆盖未归档行，归档后行不占索引体积。
DROP INDEX IF EXISTS idx_core_notification_account;
CREATE INDEX idx_core_notification_visible
    ON core_notification (account_id, created_at DESC)
    WHERE archived_at IS NULL;

-- 按类型筛选 + 未读的组合查询。kind 基数很小（十余种），与账号组合后选择性足够。
CREATE INDEX idx_core_notification_kind
    ON core_notification (account_id, kind, created_at DESC)
    WHERE archived_at IS NULL;

-- =============================================================================
-- 系统公告
-- =============================================================================
--
-- 为什么公告要单独记一张表、而不是「发完通知就完了」：
--   广播是对全部账号的写操作，必须有一个地方能回答「谁在什么时候以什么名义发了这条路公告、
--   发给了多少人」。没有这张表，运营发错内容时只能从 127 个账号的通知里倒推。
--
-- 公告正文在发布时被复制进每条通知，之后不再引用本表：通知是不可变的历史记录，
-- 如果通知内容靠 JOIN 本表渲染，改一次公告正文就会改写所有人已经收到的内容。
CREATE TABLE core_announcement (
    id           text        PRIMARY KEY,
    title        text        NOT NULL,
    body         text        NOT NULL,
    -- INFO 是普通通知，WARNING 会在列表里高亮。不收自由枚举：多的等级没人维护。
    level        text        NOT NULL DEFAULT 'INFO',
    published_by text        NOT NULL REFERENCES iam_account (id) ON DELETE RESTRICT,
    published_at timestamptz NOT NULL DEFAULT now(),
    -- 发布时的启用账号数。事后账号增减不会改动它，它记录的是「当时发给了多少人」。
    recipients   integer     NOT NULL,
    CONSTRAINT ck_core_announcement_level CHECK (level IN ('INFO', 'WARNING')),
    CONSTRAINT ck_core_announcement_title CHECK (btrim(title) <> ''),
    CONSTRAINT ck_core_announcement_body CHECK (btrim(body) <> '')
);

CREATE INDEX idx_core_announcement_published
    ON core_announcement (published_at DESC);

COMMENT ON TABLE core_announcement IS
    '系统公告的发布台账。正文同时被复制进 core_notification，本表只用于追溯与运营查看。';
COMMENT ON COLUMN core_announcement.recipients IS
    '发布时刻的启用账号数；不是「已读人数」，已读状态在 core_notification 里';

-- 公告通知的类型标记。写在这里而不是只写进 Java 常量：运维查库时能直接对上。
COMMENT ON COLUMN core_notification.kind IS
    '事件种类：CLAIM_DECIDED / PORTAL_CHANGE_DECIDED / ACHIEVEMENT_MERGED / RELATION_DECIDED / '
    'SOURCE_LICENSE_REVOKED / SOURCE_LICENSE_RESTORED / SUBMISSION_DECIDED / SYSTEM_ANNOUNCEMENT。';
