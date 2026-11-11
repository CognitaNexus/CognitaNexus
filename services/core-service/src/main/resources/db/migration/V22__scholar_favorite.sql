-- =============================================================================
-- CognitaNexus core-service 迁移 V22
-- 学者收藏（关注）
-- 依据：需求基线「学者关注」扩展项（超出 V1.9 基线，标注为扩展功能）
-- =============================================================================
--
-- 语义边界：这里的「关注」是**收藏**，不是订阅。收藏只影响「我的收藏」列表与公开的收藏数，
-- 不会因为该学者有新成果就给收藏者发通知。原因：新成果入库是高频事件（一次采集可能上千条），
-- 沿关注关系扩散会产生扇出风暴，而通知一旦发出无法撤回。要做订阅必须先把「合并/批量」做完，
-- 否则上线第一天就是几百条通知。
--
-- 为什么要有 favorite_count 的公开读数：它是最轻的社会证明，且不泄露「谁收藏了谁」。
-- 接口只返回数量，不返回收藏者列表——收藏行为在学术平台上有一定敏感性（例如跟踪某位学者）。
CREATE TABLE core_scholar_favorite (
    account_id text        NOT NULL REFERENCES iam_account (id) ON DELETE CASCADE,
    scholar_id text        NOT NULL REFERENCES core_scholar (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- 主键即「一个账号对一个学者只能有一条收藏」；重复收藏由 ON CONFLICT DO NOTHING 吸收，
    -- 不需要应用层先查后插（并发下那种写法照样会插入两条）。
    PRIMARY KEY (account_id, scholar_id)
);

-- 「我的收藏」按时间倒序取。主键已覆盖按账号查询，这里补上排序所需的时间列。
CREATE INDEX idx_core_scholar_favorite_account
    ON core_scholar_favorite (account_id, created_at DESC);

-- 公开收藏数：按 scholar_id 反查。
CREATE INDEX idx_core_scholar_favorite_scholar
    ON core_scholar_favorite (scholar_id);

COMMENT ON TABLE core_scholar_favorite IS
    '账号对学者的收藏。收藏是个人列表语义，不产生订阅通知；公开侧只暴露数量，不暴露收藏者。';
