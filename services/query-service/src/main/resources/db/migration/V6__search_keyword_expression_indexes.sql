-- 修正 V5 的索引表达式：V5 建在原始列上，而查询一律写成 lower(coalesce(col, ''))。
--
-- 依据：架构 V0.2 §9.1、核心 SPEC §7.2。
--
-- 问题：PostgreSQL 的表达式索引只对「逐字相同」的表达式生效。V5 建的是
--   gin (abstract_text gin_trgm_ops)
-- 而 SearchMapper 里写的是
--   lower(coalesce(a.abstract_text, '')) LIKE '%kw%'
-- 两者不是同一个表达式，规划器不会用这个索引。V5 上线后这一条索引从未被任何查询使用过，
-- 代价却一直在付：投影每写入一行摘要都要维护一棵 47 MB 的 GIN 树。
--
-- 实测（gen0009，18,001 篇公开生效成果）：
--   索引修正前  计数 438.7 ms / 取页 417.3 ms
--   索引修正后  计数 354.2 ms / 取页 326.9 ms
-- 剩下的时间花在 OR 链退化为 Filter 上，已在 SearchMapper 里改成候选集 UNION 解决：
--   再改 SQL   计数 9.0 ms / 取页 7.8 ms
-- 也就是说：这条迁移只负责「让索引能用」，两者缺一不可。
--
-- 为什么单独立 V6 而不改 V5：V5 已在所有环境执行过，改它会改掉 Flyway 校验和，
-- 已被迁移过的库会直接拒绝启动。迁移只增不改。

CREATE INDEX IF NOT EXISTS ix_qry_achievement_venue_lower_trgm
    ON qry_achievement USING gin ((lower(coalesce(venue, ''))) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS ix_qry_achievement_abstract_lower_trgm
    ON qry_achievement USING gin ((lower(coalesce(abstract_text, ''))) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS ix_qry_achievement_doi_lower
    ON qry_achievement USING btree ((lower(coalesce(doi, ''))));

CREATE INDEX IF NOT EXISTS ix_qry_contribution_name_lower_trgm
    ON qry_contribution USING gin ((lower(coalesce(scholar_name, ''))) gin_trgm_ops);

COMMENT ON INDEX ix_qry_achievement_venue_lower_trgm IS
    '载体关键词检索；表达式必须与 SearchMapper 的 lower(coalesce(venue, '''')) 逐字一致';
COMMENT ON INDEX ix_qry_achievement_abstract_lower_trgm IS
    '摘要关键词检索；表达式必须与 SearchMapper 的 lower(coalesce(abstract_text, '''')) 逐字一致';
COMMENT ON INDEX ix_qry_achievement_doi_lower IS
    'DOI 等值命中（检索关键词直接粘一个 DOI 时走这里），不是包含匹配';
COMMENT ON INDEX ix_qry_contribution_name_lower_trgm IS
    '作者名检索与检索建议；同样必须带 lower(coalesce(...)) 才能被用上';

-- 删掉 V5 那两条从未被使用的索引。
--
-- 判断依据不是「看着像重复」，而是把 query-service 里所有涉及这两列的 SQL 都查了一遍：
-- 9 处 LIKE 全部写成 lower(coalesce(...))，没有一处对原始列做匹配，也没有等值匹配。
-- 留着它们只会让每次投影写入多维护两棵 GIN 树。
--
-- 如果将来有人要按原始大小写做包含匹配，需要重新建索引，而不是假设它还在这里。
DROP INDEX IF EXISTS ix_qry_achievement_abstract_trgm;
DROP INDEX IF EXISTS ix_qry_contribution_name_trgm;
