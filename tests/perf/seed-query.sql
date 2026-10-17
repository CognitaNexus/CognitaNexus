-- =============================================================================
-- 容量联调造数（B 阶段）——query 读投影侧
--
-- 运行方式（由 ops/perf/run-stage-b.sh 调用）：
--   psql -v n=1000000 -v seed=0.42 -f tests/perf/seed-query.sql -d query_perf
--
-- 口径声明（必读）：
--   1. 本脚本造出的每一行 data_nature 都是 SIMULATED。合成数据不得冒充真实收录，
--      默认 REAL 口径下这些行一条都不会被检索到——这不是缺陷，是数据性质隔离在生效。
--      容量探索必须以 dataNature=ALL 显式放宽，报告里也要写明是演示口径。
--   2. 标题、作者、机构、领域都是按固定词表与固定随机种子拼出来的，不是任何真实来源。
--   3. 分布是「按经验设计」的，不是实测分布：类型比例、年份偏斜、引用量长尾、冷热词
--      都只是为了接近真实查询的选择性，便于观察索引与深翻页的表现。
--   4. 重复执行会先清空同名 generation 的行再重建，因此可重复运行且结果确定（固定种子）。
-- =============================================================================

\set ON_ERROR_STOP on

-- 固定种子：同一 seed 与同一 n 必须得到同一批数据，便于两次运行对比。
SELECT setseed(:'seed'::float8);

-- -----------------------------------------------------------------------------
-- 1. 词表：400 个「双词根」术语，按 Zipf 式偏斜取用（低编号更常被取到）
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS perf_word;
CREATE TABLE perf_word AS
SELECT i AS idx,
       (ARRAY['图', '学', '计', '网', '识', '析', '据', '优', '医', '材',
              '能', '源', '感', '知', '推', '理', '验', '测', '模', '构'])[1 + (i - 1) % 20]
       || (ARRAY['神经网络', '学术图谱', '数据治理', '知识推理', '图像识别', '语言模型',
                 '系统设计', '网络协作', '优化方法', '医学影像', '材料计算', '能源调度',
                 '传感网络', '推理引擎', '模型压缩', '算法评测', '实验平台', '检测方法',
                 '仿真系统', '结构分析'])[1 + ((i - 1) / 20) % 20]
       || (CASE WHEN i % 5 = 0 THEN '研究' WHEN i % 7 = 0 THEN '方法' ELSE '' END) AS word
FROM generate_series(1, 400) i;
CREATE INDEX ON perf_word (idx);

-- -----------------------------------------------------------------------------
-- 2. 成果主表
--    type 比例：论文 55% / 专利 15% / 项目 20% / 获奖 10%
--    visibility：PUBLIC 92% / RESTRICTED 6% / PRIVATE 2%
--    effective_status：EFFECTIVE 90% / PENDING_GOVERNANCE 7% / WITHDRAWN 3%
--    年份：1990–2026，偏向近年；5% 未知（NULL，不补 1 月 1 日）
--    引用量：仅论文，三次方偏斜的长尾，最大约 5000
-- -----------------------------------------------------------------------------
DELETE FROM qry_achievement WHERE generation = 'gen0001';
DELETE FROM qry_contribution WHERE generation = 'gen0001';
DELETE FROM qry_contribution_org WHERE generation = 'gen0001';
DELETE FROM qry_achievement_topic WHERE generation = 'gen0001';
DELETE FROM qry_coauthor_edge WHERE generation = 'gen0001';

INSERT INTO qry_achievement (
    generation, achievement_id, type, title, title_norm, data_nature, visibility,
    effective_status, publication_year, doi, venue, citation_count, citation_source,
    published_date, aggregate_version, revision, updated_at)
WITH base AS (
    SELECT i,
           CASE
             WHEN r < 0.55 THEN 'PAPER'
             WHEN r < 0.70 THEN 'PATENT'
             WHEN r < 0.90 THEN 'PROJECT'
             ELSE 'AWARD'
           END AS type,
           r,
           r2,
           r3
    FROM (
        SELECT i, random() AS r, random() AS r2, random() AS r3
        FROM generate_series(1, :n) i
    ) s
),
titled AS (
    -- 每个词位都按「行号 + 词位」的哈希派生，偏斜形式仍是 power(x,2)：
    -- 不能写成不带外层引用的标量子查询——那样 PostgreSQL 只求值一次，
    -- 全表标题会变成同一条（首次实测就这样，10 万行全部同名，关键词检索退化成全命中/零命中）。
    SELECT b.*, t.core_title
    FROM base b
    CROSS JOIN LATERAL (
        SELECT string_agg(w.word, '' ORDER BY ks.k) AS core_title
        FROM (
            SELECT k,
                   1 + floor(
                         power((abs(hashtext(b.i::text || ':' || k)::bigint) % 1000) / 1000.0, 2)
                         * 399)::int AS idx
            FROM generate_series(1, 6) k
        ) ks
        JOIN perf_word w ON w.idx = ks.idx
    ) t
)
SELECT 'gen0001',
       'perf-' || lpad(i::text, 8, '0'),
       type,
       core_title || CASE WHEN r2 < 0.20 THEN '（样本' || i || '）' ELSE '' END,
       lower(core_title || CASE WHEN r2 < 0.20 THEN '（样本' || i || '）' ELSE '' END),
       'SIMULATED',
       CASE WHEN r2 < 0.92 THEN 'PUBLIC' WHEN r2 < 0.98 THEN 'RESTRICTED' ELSE 'PRIVATE' END,
       CASE WHEN r3 < 0.90 THEN 'EFFECTIVE' WHEN r3 < 0.97 THEN 'PENDING_GOVERNANCE' ELSE 'WITHDRAWN' END,
       CASE WHEN r3 < 0.05 THEN NULL
            ELSE (1990 + floor(power(random(), 0.6) * 37))::int END,
       CASE WHEN type = 'PAPER' THEN '10.9999/perf.' || i ELSE NULL END,
       CASE WHEN type = 'PAPER' THEN '期刊第' || (1 + floor(random() * 500))::int || '卷' END,
       CASE WHEN type = 'PAPER' THEN floor(power(random(), 3) * 5000)::bigint END,
       CASE WHEN type = 'PAPER' THEN 'PERF_SEED' END,
       NULL,
       1,
       i,
       now()
FROM titled;

-- -----------------------------------------------------------------------------
-- 3. 参与关系：每篇 2–4 位作者，学者池 20 万，姓名池只有 5000 个（刻意制造大量同名）
--    3% 的关系为 PENDING_VERIFY：按红线它们不参与建边，也不计入"确定合作"
-- -----------------------------------------------------------------------------
INSERT INTO qry_contribution (
    generation, achievement_id, scholar_id, scholar_name, role, author_order, relation_state)
SELECT 'gen0001',
       a.achievement_id,
       'sch-perf-' || s.idx,
       '作者' || (s.idx % 5000),
       'AUTHOR',
       s.ord,
       CASE WHEN (abs(hashtext(a.achievement_id || ':' || s.ord)) % 100) < 3
            THEN 'PENDING_VERIFY' ELSE 'AUTO' END
FROM qry_achievement a
CROSS JOIN LATERAL (
    SELECT ord, 1 + (abs(hashtext(a.achievement_id || '#' || ord)) % 200000) AS idx
    FROM generate_series(1, 2 + (abs(hashtext(a.achievement_id)) % 3)) ord
) s
WHERE a.generation = 'gen0001'
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 4. 发生时机构：约一半参与关系带一个机构，机构池 500
-- -----------------------------------------------------------------------------
INSERT INTO qry_contribution_org (generation, achievement_id, scholar_id, organization)
SELECT 'gen0001', c.achievement_id, c.scholar_id,
       '机构' || (1 + abs(hashtext(c.scholar_id || c.achievement_id)) % 500)
FROM qry_contribution c
WHERE c.generation = 'gen0001'
  AND (abs(hashtext(c.achievement_id || c.scholar_id)) % 2) = 0
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 5. 领域：每篇 1–2 个，领域池 60；来源分类与系统推断各占一部分
-- -----------------------------------------------------------------------------
INSERT INTO qry_achievement_topic (generation, achievement_id, topic_id, canonical_name, assignment_kind)
SELECT 'gen0001', a.achievement_id,
       'topic-perf-' || t.idx,
       '领域' || t.idx,
       CASE WHEN t.idx % 3 = 0 THEN 'INFERRED' ELSE 'SOURCE' END
FROM qry_achievement a
CROSS JOIN LATERAL (
    SELECT 1 + (abs(hashtext(a.achievement_id || '#t' || k)) % 60) AS idx
    FROM generate_series(1, 1 + (abs(hashtext(a.achievement_id)) % 2)) k
) t
WHERE a.generation = 'gen0001'
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 6. 合作边：仅论文、仅 AUTO/SELF_CONFIRMED 关系，两端按字典序存放
-- -----------------------------------------------------------------------------
INSERT INTO qry_coauthor_edge (generation, achievement_id, scholar_a, scholar_b)
SELECT 'gen0001', c1.achievement_id,
       LEAST(c1.scholar_id, c2.scholar_id),
       GREATEST(c1.scholar_id, c2.scholar_id)
FROM qry_contribution c1
JOIN qry_contribution c2
  ON c2.generation = c1.generation
 AND c2.achievement_id = c1.achievement_id
 AND c2.scholar_id > c1.scholar_id
JOIN qry_achievement a
  ON a.generation = c1.generation
 AND a.achievement_id = c1.achievement_id
 AND a.type = 'PAPER'
WHERE c1.generation = 'gen0001'
  AND c1.relation_state IN ('AUTO', 'SELF_CONFIRMED')
  AND c2.relation_state IN ('AUTO', 'SELF_CONFIRMED')
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 7. 水位：本次直接写投影表，不经过事件，因此把「已发布修订」显式对齐到 core 的权威水位。
--    真实链路里这一步由消费者推进；这里是为了让公开屏障能通过，报告里必须写明这一差别。
-- -----------------------------------------------------------------------------
UPDATE qry_consumer_state
SET published_revision = GREATEST(published_revision, 0),
    received_revision = GREATEST(received_revision, 0),
    updated_at = now()
WHERE consumer_id = 'query';

ANALYZE qry_achievement;
ANALYZE qry_contribution;
ANALYZE qry_contribution_org;
ANALYZE qry_achievement_topic;
ANALYZE qry_coauthor_edge;
