-- =============================================================================
-- CognitaNexus query-service 迁移 V3：领域读模型带上归属来源
-- 依据：架构 V0.2 §9.2（统计口径可追溯）、§4.2（投影可重建，但重建不等于可以丢语义）
-- =============================================================================
--
-- 为什么读模型也必须带这一列：
--   检索分面与「领域热点」直接在 qry_achievement_topic 上聚合。若投影把 assignment_kind
--   丢掉，查询侧就只能把来源分类与系统推断混在一起排名，界面上也无法标注哪些领域是规则
--   产出的——core 侧辛苦分出来的语义会在投影这一步被抹平。
--
-- 投影是可重建的派生数据，本迁移只加列不搬家：既有行按 SOURCE 回填（它们确实全部来自来源），
-- 随后由重建把 core 的真实取值刷进来。
-- =============================================================================

ALTER TABLE qry_achievement_topic
    ADD COLUMN assignment_kind text NOT NULL DEFAULT 'SOURCE';

ALTER TABLE qry_achievement_topic
    ADD COLUMN confidence numeric(4, 3);

ALTER TABLE qry_achievement_topic
    ADD CONSTRAINT ck_qry_achievement_topic_kind
    CHECK (assignment_kind IN ('SOURCE', 'INFERRED'));

COMMENT ON COLUMN qry_achievement_topic.assignment_kind IS
    '归属来源，与 core_achievement_topic 同义：SOURCE=来源元数据，INFERRED=系统按受控词表推断。';
COMMENT ON COLUMN qry_achievement_topic.confidence IS
    '推断置信度 0..1，仅 INFERRED 有值；来源分类为 NULL。';
