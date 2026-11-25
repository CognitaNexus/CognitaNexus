-- =============================================================================
-- 阶段六分级容量造数：query 读投影侧（1 万 / 10 万 / 100 万 / 500 万）
--
-- 运行方式（由 ops/perf/run-scale-benchmark.sh 调用，参数已封装）：
--   psql -v n=1000000 -v gen=gen0001 -v pool=545000 -v ... -f tests/perf/scale/seed-scale-query.sql
--
-- ## 口径声明（每一行都必须是 SIMULATED）
--
-- 本脚本造出的所有 qry_achievement 行 data_nature = 'SIMULATED'。合成数据不得冒充真实收录：
-- 默认 REAL 口径下这些行一条都检索不到——这不是缺陷，是数据性质隔离在生效。容量探索必须
-- 显式 dataNature=ALL 或 dataNature=SIMULATED，报告里也要写明是演示口径。
--
-- ## 与 tests/perf/seed-query.sql（100 万历史脚本）的区别，逐条说明理由
--
-- 1. **全确定性**：历史脚本用 random()+setseed。setseed 只对「按插入顺序调用 random()」确定，
--    而 INSERT ... SELECT 的执行顺序在并行计划下不保证，于是「同一 seed 得到同一批数据」这个
--    承诺在 500 万规模上并不成立（并行度提高后首先失效）。本脚本一律用 hashtext(id||':salt')，
--    与行序、并行度、是否重跑都无关。setseed 仍被调用一次，只为让后续任何 random() 有确定值。
--
-- 2. **作者数由分布决定，且同一成果内作者必不重复**：作者索引写成 (base + i) mod pool，
--    i = 1..k 连续取值。模运算下连续偏移必然互不相同，因此 k ≤ pool 时同一成果内不存在
--    重复学者。历史脚本用独立哈希取作者，靠 ON CONFLICT DO NOTHING 吞掉碰撞——那会让
--    「作者关系行数」静默少于预期，产量与预期对不上时无法区分是分布问题还是碰撞。
--
-- 3. **合作边直接由分布推导，不再自连接参与关系表**：边数 = Σ 论文/成果的 C(k_合格, 2)。
--    历史脚本 `qry_contribution c1 JOIN c2 ON 同成果 AND scholar_id >` 在 300 万参与关系上
--    可跑，在 1600 万上会退化成 500 万次索引探测的嵌套循环。本脚本按成果展开作者对，
--    成本正比于边数本身。
--
-- 4. **建边不按 type 过滤**，与生产实现保持一致：
--    `ProjectionApplier.pairsOf` 与 `EventPayloadReader.pairs` 只看
--    `ProjectedContribution.qualifiedForGraph()`，**没有** type='PAPER' 判断。
--    历史 seed-query.sql 只给论文建边（注释写「仅论文建边」），于是同一次压测里
--    PG 侧边表比按生产逻辑应有的少 45%。这是一个真实的实现／文档不一致，
--    已登记为发现项；本分支不修改业务语义，造数按实现走，这样 PG 与 Neo4j 才不会
--    因为「一边按论文、一边按全部」而产生影子对照假差异。
--
-- 5. **可重跑**：先删除同名 generation 的全部投影行，再重建。同一 (n, seed, pool) 必然
--    得到同一批数据，两次运行可以直接比对体积与计划。
-- =============================================================================

\set ON_ERROR_STOP on

-- 默认参数（调用方通常显式传入）。\if :{?var} 用于「未传则取默认」。
\if :{?n}
\else
  \set n 100000
\endif
\if :{?gen}
\else
  \set gen gen0001
\endif
\if :{?seed}
\else
  \set seed 0.42
\endif
\if :{?pool}
\else
  \set pool 60000
\endif
\if :{?org_pool}
\else
  \set org_pool 500
\endif
\if :{?topic_pool}
\else
  \set topic_pool 60
\endif
\if :{?name_pool}
\else
  \set name_pool 5000
\endif
\if :{?p_single}
\else
  \set p_single 0.06
\endif
\if :{?p23}
\else
  \set p23 0.66
\endif
\if :{?p45}
\else
  \set p45 0.23
\endif
\if :{?p610}
\else
  \set p610 0.04
\endif
\if :{?pending_pct}
\else
  \set pending_pct 3
\endif
\if :{?abstracts}
\else
  \set abstracts 1
\endif
\if :{?public_pct}
\else
  \set public_pct 92
\endif
\if :{?effective_pct}
\else
  \set effective_pct 90
\endif

\timing on
\pset pager off

SELECT setseed(:'seed'::float8);

SELECT now() AS started_at,
       :n AS random_works,
       '作者分布 p(k=1)=' || :p_single || ' p(2-3)=' || :p23
         || ' p(4-5)=' || :p45 || ' p(6-10)=' || :p610 || ' p(11-20)=' ||
         (1 - :p_single - :p23 - :p45 - :p610)::text AS author_distribution,
       :pool AS scholar_pool,
       :'gen' AS generation;

-- -----------------------------------------------------------------------------
-- 0. 清空同名 generation（可重跑）
-- -----------------------------------------------------------------------------
DELETE FROM qry_achievement_topic WHERE generation = :'gen';
DELETE FROM qry_contribution_org WHERE generation = :'gen';
DELETE FROM qry_coauthor_edge     WHERE generation = :'gen';
DELETE FROM qry_contribution      WHERE generation = :'gen';
DELETE FROM qry_achievement       WHERE generation = :'gen';

-- 批量导入期的会话参数。这些只影响本次会话，不写回数据库配置：
--   synchronous_commit=off —— 导入阶段不需要逐条 WAL 落盘（崩溃重跑即可，不涉及生产数据）
--   maintenance_work_mem   —— 索引重建阶段用，见 pg-index-toggle.sql
SET synchronous_commit = off;
SET work_mem = '64MB';
-- JIT 必须关掉：本机这套 PostgreSQL 16 编译时带了 LLVM，但运行时缺少 libLLVM-17.so，
-- 一旦某条语句的估算代价越过 jit_above_cost 就会在执行时报
--   could not load library ".../llvmjit.so"
-- 造数脚本里一次失败就要从头再来。关掉它同时也让「导入耗时」更可比：JIT 编译本身
-- 会随语句数波动，而造数是同一条语句跑上百万行，JIT 的收益远小于一次编译失败的风险。
SET jit = off;

-- -----------------------------------------------------------------------------
-- 1. 成果主表
--
--    分布（与 capacity-model.mjs 一致，改这里必须同步改那里，否则对账失败）：
--      type       论文 55% / 专利 15% / 项目 20% / 获奖 10%
--      visibility PUBLIC 92% / RESTRICTED 6% / PRIVATE 2%
--      status     EFFECTIVE 90% / PENDING_GOVERNANCE 7% / WITHDRAWN 3%
--      年份       1990–2026，偏向近年；5% 未知（NULL，不补 1 月 1 日）
--      引用量     仅论文，三次方偏斜长尾，上限 5000
--      摘要       60% 有，约 40–60 字（:abstracts=0 时全 NULL，用于与历史基线对齐）
--
--    标题由 400 个词根按 power(u,2) 偏斜拼成，每个词位用 hashtext(id||':w'||k) 派生，
--    因此标题在行之间不同。历史脚本踩过「标量子查询只求值一次 → 10 万行同名」的坑，
--    这里的 LATERAL 是**必要的**：它让每行各自求值。
-- -----------------------------------------------------------------------------
INSERT INTO qry_achievement (
    generation, achievement_id, type, title, title_norm, data_nature, visibility,
    effective_status, integrity_status, publication_year, doi, venue, citation_count,
    citation_source, published_date, abstract_text, aggregate_version, revision, updated_at)
WITH words AS (
    SELECT i AS idx,
           (ARRAY['图', '学', '计', '网', '识', '析', '据', '优', '医', '材',
                  '能', '源', '感', '知', '推', '理', '验', '测', '模', '构'])[1 + (i - 1) % 20]
           || (ARRAY['神经网络', '学术图谱', '数据治理', '知识推理', '图像识别', '语言模型',
                     '系统设计', '网络协作', '优化方法', '医学影像', '材料计算', '能源调度',
                     '传感网络', '推理引擎', '模型压缩', '算法评测', '实验平台', '检测方法',
                     '仿真系统', '结构分析'])[1 + ((i - 1) / 20) % 20]
           || (CASE WHEN i % 5 = 0 THEN '研究' WHEN i % 7 = 0 THEN '方法' ELSE '' END) AS word
    FROM generate_series(1, 400) i
),
seed_rows AS (
    SELECT g AS seq,
           'perf-' || lpad(g::text, 8, '0') AS achievement_id
    FROM generate_series(1, :n) g
),
hashed AS (
    SELECT seq,
           achievement_id,
           (abs(hashtext(achievement_id || ':type')::bigint) % 10000) / 10000.0 AS u_type,
           (abs(hashtext(achievement_id || ':vis')::bigint) % 10000) / 10000.0 AS u_vis,
           (abs(hashtext(achievement_id || ':st')::bigint) % 10000) / 10000.0 AS u_st,
           (abs(hashtext(achievement_id || ':yr')::bigint) % 10000) / 10000.0 AS u_yr,
           (abs(hashtext(achievement_id || ':yr2')::bigint) % 10000) / 10000.0 AS u_yr2,
           (abs(hashtext(achievement_id || ':ab')::bigint) % 10000) / 10000.0 AS u_ab
    FROM seed_rows
),
typed AS (
    SELECT h.*,
           CASE
             WHEN u_type < 0.55 THEN 'PAPER'
             WHEN u_type < 0.70 THEN 'PATENT'
             WHEN u_type < 0.90 THEN 'PROJECT'
             ELSE 'AWARD'
           END AS type
    FROM hashed h
),
titled AS (
    SELECT t.*,
           (SELECT string_agg(w.word, '' ORDER BY ks.k)
              FROM (
                SELECT k,
                       1 + floor(
                           power((abs(hashtext(t.achievement_id || ':w' || k)::bigint) % 1000) / 1000.0, 2)
                           * 399)::int AS idx
                FROM generate_series(1, 6) k
              ) ks
              JOIN words w ON w.idx = ks.idx) AS core_title
    FROM typed t
)
SELECT :'gen',
       achievement_id,
       type,
       core_title || CASE WHEN u_vis < 0.20 THEN '（样本' || seq || '）' ELSE '' END,
       lower(core_title || CASE WHEN u_vis < 0.20 THEN '（样本' || seq || '）' ELSE '' END),
       'SIMULATED',
       CASE WHEN u_vis < :public_pct / 100.0 THEN 'PUBLIC'
            WHEN u_vis < :public_pct / 100.0 + 0.06 THEN 'RESTRICTED'
            ELSE 'PRIVATE' END,
       CASE WHEN u_st < :effective_pct / 100.0 THEN 'EFFECTIVE'
            WHEN u_st < :effective_pct / 100.0 + 0.07 THEN 'PENDING_GOVERNANCE'
            ELSE 'WITHDRAWN' END,
       CASE WHEN u_ab < 0.004 THEN 'RETRACTION' WHEN u_ab < 0.012 THEN 'CORRECTION' ELSE 'NONE' END,
       CASE WHEN u_yr < 0.05 THEN NULL
            ELSE (1990 + floor(power(u_yr2, 0.6) * 37))::int END,
       CASE WHEN type = 'PAPER' THEN '10.9999/perf.' || lpad(seq::text, 8, '0') END,
       CASE WHEN type = 'PAPER' THEN '期刊第' || (1 + (abs(hashtext(achievement_id || ':v')::bigint) % 500)::int) || '卷' END,
       CASE WHEN type = 'PAPER' THEN floor(power(u_yr2, 3) * 5000)::bigint END,
       CASE WHEN type = 'PAPER' THEN 'PERF_SEED' END,
       NULL,
       CASE WHEN :abstracts = 0 THEN NULL
            WHEN u_ab < 0.60 THEN (SELECT string_agg(w.word, ' ' ORDER BY ks.k)
              FROM (
                SELECT k, 1 + (abs(hashtext(achievement_id || ':a' || k)::bigint) % 400)::int AS idx
                FROM generate_series(1, 3) k
              ) ks JOIN words w ON w.idx = ks.idx)
            ELSE NULL END,
       1,
       seq,
       now()
FROM titled;

-- -----------------------------------------------------------------------------
-- 2. 作者分布 + 参与关系
--
--    k 由 u_k 分段映射（与 capacity-model.mjs 的 PROFILES['graph-heavy'].buckets 逐段对应）：
--      [0, 0.06)           → 1        单作者成果（无合作边）
--      [0.06, 0.72)        → 2..3
--      [0.72, 0.95)        → 4..5
--      [0.95, 0.99)        → 6..10
--      [0.99, 1.00)        → 11..20
--    上限 20 = Neo4j 物化阈值默认值，使随机部分在 PG 与 Neo4j 上产生同一批边。
--    > 20 位的高作者数成果只由 fixtures-graph.sql 提供（专测「不物化 → 二部图」路径）。
-- -----------------------------------------------------------------------------
CREATE TEMP TABLE perf_work_k AS
SELECT a.achievement_id,
       p.k,
       p.base
FROM qry_achievement a
CROSS JOIN LATERAL (
    SELECT
      CASE
        WHEN u < :p_single
          THEN 1
        WHEN u < :p_single + :p23
          THEN 2 + floor((u - :p_single) / :p23 * 2)::int
        WHEN u < :p_single + :p23 + :p45
          THEN 4 + floor((u - :p_single - :p23) / :p45 * 2)::int
        WHEN u < :p_single + :p23 + :p45 + :p610
          THEN 6 + floor((u - :p_single - :p23 - :p45) / :p610 * 5)::int
        ELSE 11 + floor((u - :p_single - :p23 - :p45 - :p610)
                        / (1 - :p_single - :p23 - :p45 - :p610) * 10)::int
      END AS k,
      (abs(hashtext(a.achievement_id || ':b')::bigint) % :pool) AS base
    FROM (
      SELECT (abs(hashtext(a.achievement_id || ':k')::bigint) % 10000) / 10000.0 AS u
    ) x
) p
WHERE a.generation = :'gen';

-- 临时表的统计信息供下方 LATERAL 选择计划；不加 ANALYZE 时规划器会按默认 1000 行估算。
ANALYZE perf_work_k;

INSERT INTO qry_contribution (
    generation, achievement_id, scholar_id, scholar_name, role, author_order, relation_state)
SELECT :'gen',
       w.achievement_id,
       'sch-' || lpad(s.idx::text, 8, '0'),
       '作者' || (s.idx % :name_pool),
       'AUTHOR',
       s.i,
       CASE WHEN (abs(hashtext(w.achievement_id || ':' || s.i)::bigint) % 100) < :pending_pct
            THEN 'PENDING_VERIFY' ELSE 'AUTO' END
FROM perf_work_k w
CROSS JOIN LATERAL (
    SELECT g AS i, 1 + ((w.base + g - 1) % :pool) AS idx
    FROM generate_series(1, w.k) AS g
) s;

-- -----------------------------------------------------------------------------
-- 3. 发生时机构：一半参与关系带机构；机构由学者身份派生（同一学者同一机构），
--    这样「按机构筛选」才有真实的复现性，而不是每次随机换机构。
-- -----------------------------------------------------------------------------
INSERT INTO qry_contribution_org (generation, achievement_id, scholar_id, organization)
SELECT c.generation, c.achievement_id, c.scholar_id,
       '机构' || lpad((1 + abs(hashtext(c.scholar_id)::bigint) % :org_pool)::text, 5, '0')
FROM qry_contribution c
WHERE c.generation = :'gen'
  AND (abs(hashtext(c.achievement_id || c.scholar_id)::bigint) % 2) = 0;

-- -----------------------------------------------------------------------------
-- 4. 领域：每篇 1–2 个，领域池 60；SOURCE / INFERRED 混合（与 V3 的语义一致：
--    INFERRED 才带 confidence）
-- -----------------------------------------------------------------------------
INSERT INTO qry_achievement_topic (
    generation, achievement_id, topic_id, canonical_name, assignment_kind, confidence)
SELECT :'gen', a.achievement_id,
       'topic-perf-' || lpad(t.idx::text, 3, '0'),
       '领域' || t.idx,
       CASE WHEN t.idx % 3 = 0 THEN 'INFERRED' ELSE 'SOURCE' END,
       CASE WHEN t.idx % 3 = 0 THEN 0.500 + (t.idx % 50) / 100.0 ELSE NULL END
FROM qry_achievement a
CROSS JOIN LATERAL (
    -- 每篇 1–2 个**互不相同**的领域：两个词位独立取哈希时会撞到同一个 topic_id，
    -- 直接插入就会违反主键（首次小样本验证就是这样暴露的）。DISTINCT 让「1–2 个」变回
    -- 「1 到 2 个不重复」，代价只是少量成果只有 1 个领域，比吞掉一条主键冲突安全得多。
    SELECT DISTINCT ON (idx) idx
    FROM (
      SELECT 1 + (abs(hashtext(a.achievement_id || '#t' || k)::bigint) % :topic_pool)::int AS idx, k
      FROM generate_series(1, 1 + (abs(hashtext(a.achievement_id)::bigint) % 2)) k
    ) x
    ORDER BY idx, k
) t
WHERE a.generation = :'gen';

-- -----------------------------------------------------------------------------
-- 5. 合作边：由作者分布直接推导，两端按字典序存放
--
--    只对**合格**关系建边（AUTO / SELF_CONFIRMED）：PENDING_VERIFY 与 EXCLUDED 都不建边
--    （BR-IMP-08）。因此 i、j 两端各自都要过一遍「是否合格」判断。
--    k=1 的成果天然不产生任何行。
-- -----------------------------------------------------------------------------
INSERT INTO qry_coauthor_edge (generation, achievement_id, scholar_a, scholar_b)
SELECT :'gen', w.achievement_id,
       LEAST(  'sch-' || lpad(sa.idx::text, 8, '0'), 'sch-' || lpad(sb.idx::text, 8, '0')),
       GREATEST('sch-' || lpad(sa.idx::text, 8, '0'), 'sch-' || lpad(sb.idx::text, 8, '0'))
FROM perf_work_k w
CROSS JOIN LATERAL (
    SELECT i, 1 + ((w.base + i - 1) % :pool) AS idx
    FROM generate_series(1, w.k) AS i
) sa
CROSS JOIN LATERAL (
    SELECT j, 1 + ((w.base + j - 1) % :pool) AS idx
    FROM generate_series(sa.i + 1, w.k) AS j
) sb
WHERE w.k >= 2
  AND (abs(hashtext(w.achievement_id || ':' || sa.i)::bigint) % 100) >= :pending_pct
  AND (abs(hashtext(w.achievement_id || ':' || sb.j)::bigint) % 100) >= :pending_pct;

-- -----------------------------------------------------------------------------
-- 6. 水位：本次直接写投影表，不经过事件链，因此把「已发布修订」显式置为非零，
--    让公开屏障在本库内自洽。真实链路里这一步由消费者推进，报告必须写明这一差别。
-- -----------------------------------------------------------------------------
INSERT INTO qry_generation (generation, status, ready_at, note)
VALUES (:'gen', 'READY', now(), '阶段六容量造数（SIMULATED，直接写投影表，不经事件链）')
ON CONFLICT (generation) DO UPDATE SET status = 'READY', ready_at = now();

UPDATE qry_consumer_state
SET active_generation = :'gen',
    received_revision = GREATEST(received_revision, 1),
    published_revision = GREATEST(published_revision, 1),
    updated_at = now()
WHERE consumer_id = 'query';

-- -----------------------------------------------------------------------------
-- 7. 统计信息
--
--    没有 ANALYZE 时规划器按默认选择性估算，EXPLAIN 与真实执行计划会两回事；
--    交接报告里出现过「加了索引却没用上」的判断，根因都在统计信息。
-- -----------------------------------------------------------------------------
ANALYZE qry_achievement;
ANALYZE qry_contribution;
ANALYZE qry_contribution_org;
ANALYZE qry_achievement_topic;
ANALYZE qry_coauthor_edge;

-- -----------------------------------------------------------------------------
-- 8. 产量自报：与 capacity-model.mjs 的预测逐项对账
-- -----------------------------------------------------------------------------
SELECT '随机成果' AS metric, count(*)::text AS value
FROM qry_achievement WHERE generation = :'gen' AND achievement_id LIKE 'perf-%'
UNION ALL
SELECT '成果合计（含 fixture 前）', count(*)::text
FROM qry_achievement WHERE generation = :'gen'
UNION ALL
SELECT '参与关系', count(*)::text
FROM qry_contribution WHERE generation = :'gen'
UNION ALL
SELECT '去重学者', count(DISTINCT scholar_id)::text
FROM qry_contribution WHERE generation = :'gen'
UNION ALL
SELECT '合格参与关系', count(*)::text
FROM qry_contribution WHERE generation = :'gen'
  AND relation_state IN ('AUTO', 'SELF_CONFIRMED')
UNION ALL
SELECT '合作边', count(*)::text
FROM qry_coauthor_edge WHERE generation = :'gen'
UNION ALL
SELECT '领域归属行', count(*)::text
FROM qry_achievement_topic WHERE generation = :'gen'
UNION ALL
SELECT '发生机构行', count(*)::text
FROM qry_contribution_org WHERE generation = :'gen'
UNION ALL
SELECT '数据性质 ' || data_nature, count(*)::text
FROM qry_achievement WHERE generation = :'gen' GROUP BY data_nature
UNION ALL
SELECT '作者数 k=1 的成果', count(*)::text FROM qry_achievement a
 WHERE a.generation = :'gen' AND a.achievement_id LIKE 'perf-%'
   AND (SELECT count(*) FROM qry_contribution c
         WHERE c.generation = a.generation AND c.achievement_id = a.achievement_id) = 1
UNION ALL
SELECT '作者数 k>20 的成果', count(*)::text FROM qry_achievement a
 WHERE a.generation = :'gen' AND a.achievement_id LIKE 'perf-%'
   AND (SELECT count(*) FROM qry_contribution c
         WHERE c.generation = a.generation AND c.achievement_id = a.achievement_id) > 20;

SELECT 'seed-scale 完成' AS status, now() AS finished_at;

DROP TABLE perf_work_k;
