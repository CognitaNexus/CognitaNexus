-- =============================================================================
-- CognitaNexus query-service 迁移 V4
-- 公开投影增加学术诚信状态
-- 依据：FR02、core 侧 V13__achievement_integrity_notice.sql
-- =============================================================================
--
-- 为什么投影里也要有它：检索列表必须能标出「已撤稿」。若只有详情页知道，读者在结果页点进去之前
-- 无从判断，而「先看到标题、再看到撤稿标记」已经足以让人误以为这是一条正常成果。
--
-- 为什么只存状态码、不存声明正文：声明正文（理由、主体、链接）属于详情接口，由 core 直接提供；
-- 投影只需要一个能渲染标记的短字段。把长文本塞进每次事件的载荷与每一行投影，会让一次声明变更
-- 复制出成千上万份同样的文字，而投影的价值在于「按字段检索与统计」，不是保存原文。
--
-- 默认值 NONE 是**显式的「无声明」**，不是缺省。读路径据此可以确定：没有标记就是真的没有声明，
-- 而不是事件里漏了这个字段。

ALTER TABLE qry_achievement
    ADD COLUMN integrity_status text NOT NULL DEFAULT 'NONE';

ALTER TABLE qry_achievement
    ADD CONSTRAINT ck_qry_achievement_integrity CHECK (
        integrity_status IN ('NONE', 'CONCERN', 'CORRECTION', 'RETRACTION')
    );

COMMENT ON COLUMN qry_achievement.integrity_status IS
    '学术诚信状态：NONE / CONCERN / CORRECTION / RETRACTION。取生效声明中最严重的一种；'
    '撤稿成果仍可检索（学术记录需可查证），但列表与详情必须显示标记。';

-- 治理与运营需要「当前有多少条已撤稿的公开成果」，这是一个低基数条件的部分索引。
CREATE INDEX ix_qry_achievement_integrity
    ON qry_achievement (generation, integrity_status)
    WHERE integrity_status <> 'NONE';
