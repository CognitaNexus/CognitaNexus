-- =============================================================================
-- CognitaNexus core-service 迁移 V10：领域归属的来源与推断分离
-- 依据：SRS 5.3（按学术领域分类管理；无法可靠映射时标记待分类，不强行归类）、
--      架构 V0.2 §9.2（统计口径必须可追溯到取得方式）
-- =============================================================================
--
-- 背景：
--   本平台原有的学科领域只来自来源元数据（Crossref 的 subject、OpenAlex 的 concepts）。
--   实测下来 Crossref 的 subject 几乎从不填充（按检索词抽样 20 条为 0 条有值），OpenAlex 是
--   唯一可用的真实来源。这使「按学术领域分类管理」「分类过滤」「科研领域热点分析」在数据上
--   长期空白。
--
--   补数据的合法路径只有两条：
--     (a) 落来源给出的分类（SOURCE）；
--     (b) 用受控词表对题名/期刊/摘要作规则匹配，落一条<b>明确标注为系统推断</b>的分类
--         （INFERRED）。
--
--   不能有第三条——把推断结果写进来源字段、让它在库和界面上与来源值长得一样，等于把
--   「系统猜的」冒充成「来源说的」。领域分类会直接进入检索分面与领域热点排名，一旦来源与
--   推断不可分辨，读者就无法判断某个排名是元数据事实还是本系统的规则产出。
--
-- 本迁移做两件事：
--   1. 给 core_achievement_topic 补上「这条归属是怎么来的」；
--   2. 建 core_topic_inference 记录「推断尝试」本身。
-- =============================================================================

ALTER TABLE core_achievement_topic
    ADD COLUMN assignment_kind text NOT NULL DEFAULT 'SOURCE';

ALTER TABLE core_achievement_topic
    ADD COLUMN confidence numeric(4, 3);

ALTER TABLE core_achievement_topic
    ADD COLUMN rule_version text;

ALTER TABLE core_achievement_topic
    ADD COLUMN evidence text;

COMMENT ON COLUMN core_achievement_topic.assignment_kind IS
    '归属来源：SOURCE=来源元数据给出的分类；INFERRED=本系统按受控词表推断。两者在库、投影与界面上必须可分辨。';
COMMENT ON COLUMN core_achievement_topic.confidence IS
    '推断置信度 0..1，仅 INFERRED 有值；来源给出的分类不存在置信度这一概念，故为 NULL。';
COMMENT ON COLUMN core_achievement_topic.rule_version IS
    '推断所用的受控词表版本，仅 INFERRED 有值。换版本可整批重算，形成可复现的推理链。';
COMMENT ON COLUMN core_achievement_topic.evidence IS
    '推断命中的具体依据（命中词、所在字段、得分），仅 INFERRED 有值。无证据不得落库。';

-- 取值域
ALTER TABLE core_achievement_topic
    ADD CONSTRAINT ck_core_achievement_topic_kind
    CHECK (assignment_kind IN ('SOURCE', 'INFERRED'));

ALTER TABLE core_achievement_topic
    ADD CONSTRAINT ck_core_achievement_topic_confidence
    CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1));

-- 来源与推断两侧字段必须成对出现，不能各有一半。
-- 写成 (A) = (B) 而不是 AND/OR 串联：两个谓词本身都是 IS NULL / IS NOT NULL，值非真即假，
-- 因此这个等价式不会因 NULL 传播而放行。
ALTER TABLE core_achievement_topic
    ADD CONSTRAINT ck_core_achievement_topic_kind_fields
    CHECK (
        (assignment_kind = 'SOURCE')
            = (rule_version IS NULL AND confidence IS NULL AND evidence IS NULL)
    );

-- 推断行不得携带来源原分类：source_category 的语义是「来源自己怎么说的」，推断没有来源可引。
-- 允许它写值会让一条规则产出的行看起来像有上游出处。
ALTER TABLE core_achievement_topic
    ADD CONSTRAINT ck_core_achievement_topic_inferred_no_source_category
    CHECK (assignment_kind <> 'INFERRED' OR source_category IS NULL);

-- 重算与盘点按规则版本扫描推断行；部分索引避免把十万量级的来源行也卷进来。
CREATE INDEX ix_core_achievement_topic_inferred
    ON core_achievement_topic (rule_version, achievement_id)
    WHERE assignment_kind = 'INFERRED';

-- 主键 (achievement_id, topic_id) 已经保证「同一成果同一领域只有一行」。这条在推断场景下
-- 恰好是需要的保护：来源已给出某领域时，推断不可能再往同一格写第二条。
ALTER TABLE core_achievement_topic
    ADD CONSTRAINT ck_core_achievement_topic_evidence_required
    CHECK (assignment_kind <> 'INFERRED' OR (evidence IS NOT NULL AND btrim(evidence) <> ''));

-- -----------------------------------------------------------------------------
-- 推断尝试台账
-- -----------------------------------------------------------------------------
--
-- 为什么必须单独记录「尝试」而不是只记录「结果」：
--   分类器的设计是「证据不足就什么都不输出」（SRS 5.3：不强行归类）。这样一批成果会被正确地
--   判为「判不出领域」，但它们在库里与「还没轮到处理」完全同形——都是「查不到领域归属」。
--   于是批量推断无法增量推进：每一轮都会重新取到同一批判不出的成果，排在最前面，把后面的
--   成果永远挡在外面。这不是性能问题，是语义缺失。
--
--   台账把「判不出」变成一个明确、可查询、可展示的状态：待分类清单 = 本表 outcome='UNRESOLVED'
--   的行。它同时消除了重复劳动（同一规则版本对同一成果是确定性的，重算不会得到别的结果），
--   也让「为什么判不出」有据可查。
CREATE TABLE core_topic_inference (
    achievement_id  text        NOT NULL REFERENCES core_achievement (id) ON DELETE RESTRICT,
    rule_version    text        NOT NULL,
    outcome         text        NOT NULL,
    assigned_count  smallint    NOT NULL,
    best_confidence numeric(4, 3),
    evidence        text        NOT NULL,
    inferred_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_core_topic_inference PRIMARY KEY (achievement_id, rule_version),
    CONSTRAINT ck_core_topic_inference_outcome CHECK (outcome IN ('ASSIGNED', 'UNRESOLVED')),
    CONSTRAINT ck_core_topic_inference_confidence
        CHECK (best_confidence IS NULL OR (best_confidence >= 0 AND best_confidence <= 1)),
    -- ASSIGNED 必须有归属条数与置信度，UNRESOLVED 必须没有；两者不能各带一半。
    CONSTRAINT ck_core_topic_inference_outcome_fields
        CHECK ((outcome = 'ASSIGNED') = (best_confidence IS NOT NULL AND assigned_count > 0)),
    CONSTRAINT ck_core_topic_inference_evidence
        CHECK (btrim(evidence) <> '')
);

COMMENT ON TABLE core_topic_inference IS
    '领域推断尝试台账。UNRESOLVED 即 SRS 5.3 所说的「待分类」；同一规则版本对同一成果只记一条，重算幂等。';
COMMENT ON COLUMN core_topic_inference.assigned_count IS
    '本次推断落下的领域归属条数：ASSIGNED 时为 1 或 2，UNRESOLVED 时为 0。';
COMMENT ON COLUMN core_topic_inference.evidence IS
    'ASSIGNED 时为首位领域的命中依据；UNRESOLVED 时为判不出的原因（最高分与阈值，或无可用文本）。';

-- 待分类清单与覆盖率盘点的扫描路径。
CREATE INDEX ix_core_topic_inference_outcome
    ON core_topic_inference (rule_version, outcome);
