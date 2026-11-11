-- 成果自助提交（待办第 3 条）。
--
-- 为什么单独建表而不是直接写 core_achievement：
--   1) 提交件在审核通过前不是「成果」——它没有稳定的来源记录、没有经过发布判定。
--      先写成果再挂一个「待审」标记，等于让所有既有查询都要多带一个条件，
--      漏一处就会把未审核的条目露到公开页。
--   2) 审核被驳回也要留痕：作者想知道「我提过什么、为什么没过」，这张表就是那份记录的存身之处。
--   3) 查重结果（疑似重复的那条成果、命中方式）属于提交件的属性，不属于成果本身。
--
-- 状态只用这五个：SUBMITTED（待审）、NEED_MORE_INFO（补材料）、APPROVED、REJECTED、WITHDRAWN（本人撤回）。
-- 不用「审核中」这种中间态：一条提交要么还没被看，要么已经被裁决，中间态只会让「多久没人处理」
-- 这类问题多一个无从回答的分支。
CREATE TABLE core_submission (
    id               text        NOT NULL,
    account_id       text        NOT NULL,
    -- 提交者声明的作者身份。允许为空（先提交成果、之后再认领门户），
    -- 为空时审核通过只建成果、不建参与关系——不能替作者认下一条他没说过的署名。
    scholar_id       text,
    type             text        NOT NULL,
    title            text        NOT NULL,
    title_norm       text        NOT NULL,
    doi              text,
    doi_norm         text,
    venue            text,
    publication_year smallint,
    abstract_text    text,
    -- 原始署名串：来源不提供作者序，这里保留原样，不解析成份列表（解析错了会串到别人头上）。
    authors_raw      text,
    status           text        NOT NULL,
    -- 查重只做「提示」不做「合并」：相似标题不直接合并是业务红线，最终由人裁决。
    duplicate_of     text,
    duplicate_kind   text,
    duplicate_note   text,
    decision_reason  text,
    decided_by       text,
    decided_at       timestamptz,
    achievement_id   text,
    idempotency_key  text,
    version          bigint      NOT NULL DEFAULT 1,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_core_submission_status
        CHECK (status IN ('SUBMITTED', 'NEED_MORE_INFO', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_core_submission_type
        CHECK (type IN ('PAPER', 'PATENT', 'PROJECT', 'AWARD')),
    CONSTRAINT ck_core_submission_duplicate_kind
        CHECK (duplicate_kind IS NULL OR duplicate_kind IN ('DOI', 'TITLE'))
);

-- 「我的提交」列表：按账号取最近若干条。
CREATE INDEX ix_core_submission_account ON core_submission (account_id, created_at DESC);

-- 审核队列：按状态取待办，按提交时间先进先出。
CREATE INDEX ix_core_submission_queue ON core_submission (status, created_at);

-- 幂等键只在同一账号内唯一：不同人各自提交同一篇论文是正常情况（合著），不是重放。
CREATE UNIQUE INDEX ux_core_submission_idempotency
    ON core_submission (account_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- 这里不写 GRANT：V6 已经 ALTER DEFAULT PRIVILEGES，新表自动被授予运行角色。
-- 在角色不存在的环境（例如纯单元测试的库）里，一条 GRANT 会让结构迁移直接失败，
-- 而失败的原因与「这张表该不该有权限」毫无关系。
