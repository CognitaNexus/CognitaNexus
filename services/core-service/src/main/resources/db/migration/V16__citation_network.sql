-- =============================================================================
-- CognitaNexus core-service 迁移 V16
-- 引用网络：参考文献观测与引用边（待办第 1 条「没有引用网络，只有合作网络」）
-- 依据：架构 V0.2 §5.1（成果关系）、SRS FR06（引用关系查询）、BR-IMP-04（人工值不被覆盖）
-- =============================================================================
--
-- 为什么需要两张表而不是一张：
--   core_citation_observation 记的是「这一篇的参考文献列表被谁在哪一天取到过」，一篇一行；
--   core_citation_reference   记的是「这一篇引用了哪些文献」，一篇多行。两者生命周期不同：
--   参考文献列表重采会整体替换（引用是快照，不是增量累加），而「是否采过」这件事只能有一行，
--   否则「采过但列表为空」和「没采过」就分不开——前者应当显示「本篇无参考文献」，
--   后者必须显示「尚未采集」，混成一个空列表就是把未知说成了确定。
--
-- 为什么引用边要区分 RESOLVED / EXTERNAL：
--   参考文献里的标识大多指向本平台未收录的文献。把它当成边会让「站内被引」变成「全世界被引」，
--   而把它丢掉又会丢掉真实的引用总量。因此：能落到本平台成果的记 RESOLVED（可显示为边），
--   落不到的记 EXTERNAL（只计数量，绝不渲染成关系）。解析状态是数据事实，不是展示偏好。
--
-- 为什么 cited_value_norm 而不是直接存 DOI：
--   OpenAlex 的参考文献列表只给作品 ID（W...），Crossref 的给 DOI。两种标识都必须能存，
--   才能在不重采的前提下用「后到的标识」把既有行升级为 RESOLVED（见 CitationNetworkService
--   的重新解析）。按 (citing, kind, value) 唯一，保证重采是幂等的整体替换而不是越插越多。
-- =============================================================================

CREATE TABLE core_citation_observation (
    achievement_id    text        PRIMARY KEY
        REFERENCES core_achievement (id) ON DELETE RESTRICT,
    provider          text        NOT NULL,
    provider_work_id  text,
    references_total  integer,
    references_listed integer     NOT NULL,
    outcome           text        NOT NULL DEFAULT 'OBSERVED',
    fetched_at        timestamptz NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_core_citation_observation_listed CHECK (references_listed >= 0),
    CONSTRAINT ck_core_citation_observation_outcome CHECK (
        outcome IN ('OBSERVED', 'NOT_FOUND')
    ),
    CONSTRAINT ck_core_citation_observation_total CHECK (
        references_total IS NULL OR references_total >= 0
    )
);

COMMENT ON TABLE core_citation_observation IS
    '参考文献列表的采集证据：一篇成果一行，代表「这一篇的参考文献被采到过」。'
    'references_total 是来源声明的引用总数，references_listed 是实际拿到的条目数；'
    '两者不相等时不补齐、不推断，页面必须同时给出这两个数字。';
COMMENT ON COLUMN core_citation_observation.references_total IS
    '来源声明的参考文献总数；NULL 表示来源未声明，不等于 0。';
COMMENT ON COLUMN core_citation_observation.outcome IS
    'OBSERVED=来源确实返回了这一篇（列表可能为空）；NOT_FOUND=来源按标识查不到这一篇。'
    '两者必须分开：前者「没有参考文献」是事实，后者「取不到」是缺口，页面上不能都说成 0 篇。'
    'NOT_FOUND 的行存在的意义是让「补采」不再重复命中同一批永远取不到的 DOI。';

CREATE TABLE core_citation_reference (
    id                    text        PRIMARY KEY,
    citing_achievement_id text        NOT NULL
        REFERENCES core_achievement (id) ON DELETE RESTRICT,
    cited_kind            text        NOT NULL,
    cited_value_norm      text        NOT NULL,
    cited_achievement_id  text
        REFERENCES core_achievement (id) ON DELETE RESTRICT,
    resolution            text        NOT NULL,
    observed_at           timestamptz NOT NULL,
    resolved_at           timestamptz,
    CONSTRAINT uk_core_citation_reference
        UNIQUE (citing_achievement_id, cited_kind, cited_value_norm),
    CONSTRAINT ck_core_citation_reference_kind CHECK (cited_kind IN ('OPENALEX_ID', 'DOI')),
    CONSTRAINT ck_core_citation_reference_resolution CHECK (
        resolution IN ('RESOLVED', 'EXTERNAL')
    ),
    CONSTRAINT ck_core_citation_reference_resolved CHECK (
        (resolution = 'RESOLVED' AND cited_achievement_id IS NOT NULL)
        OR (resolution = 'EXTERNAL' AND cited_achievement_id IS NULL)
    )
);

COMMENT ON TABLE core_citation_reference IS
    '参考文献条目。RESOLVED 表示该引用指向本平台内的一条成果（可作为引用边显示），'
    'EXTERNAL 表示指向平台外文献（只计数量，不显示为关系）。';
COMMENT ON COLUMN core_citation_reference.cited_value_norm IS
    '归一后的被引标识（小写、去 URL 前缀）。归一规则须与来源记录导入时一致，'
    '否则同一篇文献的两种写法会被当成两条不同引用。';

CREATE INDEX ix_core_citation_reference_cited
    ON core_citation_reference (cited_achievement_id)
    WHERE cited_achievement_id IS NOT NULL;
CREATE INDEX ix_core_citation_reference_pending
    ON core_citation_reference (cited_kind, cited_value_norm)
    WHERE resolution = 'EXTERNAL';
