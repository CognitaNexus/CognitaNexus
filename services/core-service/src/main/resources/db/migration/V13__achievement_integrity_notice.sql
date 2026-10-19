-- =============================================================================
-- CognitaNexus core-service 迁移 V13
-- 学术诚信声明（关注声明 / 更正 / 撤稿）
-- 依据：FR02（成果详情须给出可追溯的来源）、BR-IMP 红线「来源原值不可被人工修订覆盖」
-- =============================================================================
--
-- 为什么单独建表而不是给 core_achievement 加几个字段：
--   ① 一次声明有它自己的主体（期刊 / 机构 / 平台治理）、日期、理由与可核查链接，这些不属于成果
--      本身，塞进成果表会把「成果是什么」与「我们对它做过什么判断」混在一起；
--   ② 声明会撤销。撤销不是删除——撤销本身是要留痕的事实（谁在什么时候基于什么理由推翻了它），
--      而字段只能表达「当前值」，表达不了「曾经有过、后来撤销」；
--   ③ 同一成果可能先后被多次关注与更正，需要按时间排列，字段装不下序列。
--
-- 红线：本表只增加「关于成果的声明」，不修改成果的任何原始字段（题名、年份、期刊、作者）。
-- 声明即使成立，纠正内容的路径仍然是既有的修订与裁决流程，不是由声明直接改写。

CREATE TABLE core_achievement_integrity_notice (
    id             text        PRIMARY KEY,
    achievement_id text        NOT NULL REFERENCES core_achievement (id) ON DELETE RESTRICT,
    -- 三类声明，严重度不同：RETRACTION > CONCERN > CORRECTION。
    -- CONCERN：对成果可靠性表达关注，结论未定；CORRECTION：已发布更正，成果整体仍成立；
    -- RETRACTION：声明作废，成果不应再被当作有效研究引用。
    notice_type    text        NOT NULL,
    status         text        NOT NULL DEFAULT 'ACTIVE',
    -- 声明主体（期刊名、机构名或平台治理）。空值会让「谁说的」无从追溯。
    declared_by    text        NOT NULL,
    -- 登记理由：必填，且要有实质内容。它同时是给读者的说明与给审计的依据。
    reason         text        NOT NULL,
    -- 指向原始声明的可核查地址（DOI 页面、期刊更正页）。可为空：内部治理判断不一定有外部链接。
    source_url     text,
    -- 声明发布日期（外部事实），与登记时间（本平台事实）分开存，二者不可互相取代。
    announced_on   date,
    recorded_by    text        NOT NULL REFERENCES iam_account (id) ON DELETE RESTRICT,
    recorded_at    timestamptz NOT NULL DEFAULT now(),
    revoked_by     text        REFERENCES iam_account (id) ON DELETE RESTRICT,
    revoked_at     timestamptz,
    revoke_reason  text,
    CONSTRAINT ck_core_integrity_type CHECK (
        notice_type IN ('CONCERN', 'CORRECTION', 'RETRACTION')
    ),
    CONSTRAINT ck_core_integrity_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_core_integrity_declared_by CHECK (btrim(declared_by) <> ''),
    CONSTRAINT ck_core_integrity_reason CHECK (btrim(reason) <> ''),
    -- 撤销必须成套：有状态就得有撤销人、时间与理由。允许「状态是 REVOKED 但没有撤销理由」，
    -- 等于让撤销变成不可解释的静默操作，事后无法判断它是纠正了误登记还是被谁压下去了。
    CONSTRAINT ck_core_integrity_revoked CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL AND revoked_by IS NULL AND revoke_reason IS NULL)
        OR (status = 'REVOKED'
            AND revoked_at IS NOT NULL AND revoked_by IS NOT NULL
            AND revoke_reason IS NOT NULL AND btrim(revoke_reason) <> '')
    )
);

-- 同一成果、同一类型同时只能有一条生效声明。
-- 没有它，「同一篇撤稿被三个人各登记一次」会让详情页出现三条内容几乎相同的声明，
-- 而撤销其中一条之后剩下的仍然显示为生效，读者无法判断这条成果到底处于什么状态。
CREATE UNIQUE INDEX uk_core_integrity_active
    ON core_achievement_integrity_notice (achievement_id, notice_type)
    WHERE status = 'ACTIVE';

-- 详情页按成果取全部声明（含历史撤销），按登记时间倒序。
CREATE INDEX ix_core_integrity_achievement
    ON core_achievement_integrity_notice (achievement_id, recorded_at DESC);

-- 治理台按时间看最近的声明。
CREATE INDEX ix_core_integrity_recent
    ON core_achievement_integrity_notice (recorded_at DESC);

COMMENT ON TABLE core_achievement_integrity_notice IS
    '学术诚信声明。只追加与撤销，不修改、不删除；'
    '声明不修改成果的任何原始字段，纠正内容仍走既有修订与裁决路径。';
COMMENT ON COLUMN core_achievement_integrity_notice.notice_type IS
    'CONCERN（关注声明）/ CORRECTION（更正）/ RETRACTION（撤稿）；严重度依次升高。';
COMMENT ON COLUMN core_achievement_integrity_notice.announced_on IS
    '声明方公布的日期，与 recorded_at（本平台登记时间）分开记录，二者不可互相替代。';
