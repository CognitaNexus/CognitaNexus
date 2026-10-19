-- 关键词检索扩展到摘要与作者名之后，LIKE '%…%' 必须有 trigram 索引支撑。
--
-- 依据：架构 V0.2 §9.1、核心 SPEC §7.2。
--
-- 为什么单独立一条迁移而不是改 V1：V1 已在所有环境执行过，改它会改掉 Flyway 的校验和，
-- 已被迁移过的库会直接拒绝启动。迁移只增不改。
--
-- 索引选择：
--   * abstract_text 用 gin_trgm_ops：LIKE '%kw%' 这种中间匹配只有 trigram 能走索引，
--     btree 对这种模式一律退化为全表扫描。摘要通常比标题长一个数量级，缺这个索引的代价
--     会随数据量线性放大。
--   * scholar_name 同样用 gin_trgm_ops：作者名建议与「按作者搜成果」都是包含匹配。
--     该列在 qry_contribution 上是多行重复（一位学者可能对应多篇成果），trigram 索引对
--     这种重复值仍然有效，代价是索引比 btree 大。
--
-- 两个索引都不加 generation 前缀：generation 的选择性由 qry_achievement 上的其它索引承担，
-- trigram 索引只负责在候选集内快速定位，组合成 (generation, gin) 的复合索引在 PostgreSQL 中
-- 并不存在——gin 不能与 btree 列组成普通复合索引。

CREATE INDEX IF NOT EXISTS ix_qry_achievement_abstract_trgm
    ON qry_achievement USING gin (abstract_text gin_trgm_ops);

CREATE INDEX IF NOT EXISTS ix_qry_contribution_name_trgm
    ON qry_contribution USING gin (scholar_name gin_trgm_ops);

COMMENT ON INDEX ix_qry_achievement_abstract_trgm IS
    '摘要关键词检索；缺它时 LIKE ''%kw%'' 对 qry_achievement 全表扫描';
COMMENT ON INDEX ix_qry_contribution_name_trgm IS
    '作者名检索与检索建议；只用于包含匹配，不参与排序';
