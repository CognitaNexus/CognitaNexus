-- =============================================================================
-- V5：标题相似度支撑（架构 V0.2 §5.3、AC-IMPL-FR09-02）
--
-- 仅标题相似不得自动合并，只产生候选；但「产生候选」这件事本身需要一个可扩展的相似度检索，
-- 否则只能全表扫描，在 100 万条规模上不可用。
--
-- 采用 PostgreSQL 自带的 pg_trgm：三元组索引使相似度过滤可以走索引而不是顺序扫描。
-- 中文标题按字符三元组处理，对「同一标题的轻微改写」这类场景足够；
-- 这不等同于中文分词检索，检索层的中文分词方案另在 query-service 评估（架构 §9.1），
-- 本迁移不声称已解决中文检索相关性。
--
-- 相似度阈值是候选生成参数，属 SPEC 中待评审的业务参数，这里只给出索引与调用入口，
-- 不把任何阈值写成已确认结论。
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX ix_core_achievement_title_trgm
    ON core_achievement USING gin (title_norm gin_trgm_ops);

CREATE INDEX ix_core_scholar_name_trgm
    ON core_scholar USING gin (name_norm gin_trgm_ops);

COMMENT ON INDEX ix_core_achievement_title_trgm IS
    '标题三元组索引：用于生成去重候选，不用于自动合并。'
    '注意 pg_trgm 对中文按字符三元组处理，与中文分词检索不是同一件事。';
