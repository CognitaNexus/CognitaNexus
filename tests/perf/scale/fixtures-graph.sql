-- =============================================================================
-- 阶段六结构 fixture：单学者、多学者、环、多条等长最短路径、树状深度、枢纽、高作者数成果
--
-- 为什么需要它：随机数据只能证明「有图」，不能证明「图的结论对」。下面这些问题全部
-- 需要一个**已知正确答案**的拓扑才能验证：
--   * 1/2/3 跳子图的节点与边数是否等于真实邻域（而不是把边界外的一层也算进来）
--   * 最短路径的跳数是否等于实际边数、逐跳证据是否与跳一一对应
--   * 等长路径是否按「规范 ID 序列字典序最小」稳定选出一条，而不是每次运行换一条
--   * 超过 6 跳是否如实报「N 跳内未找到」，而不是报「全图不连通」
--   * 单作者成果是否确实不产生任何合作边（不伪造合作者）
--   * 作者数 > 20 的成果在 Neo4j 上不物化边，二部图能否仍然推导出共作者关系
--
-- 所有 ID 都带 `fix-` 前缀且长度固定，加载器（tests/perf/load.mjs）按同一约定直接算出来，
-- 不需要额外的清单文件；两边的约定只要有一处漂移，路径断言就会立刻失败——这正是目的。
--
-- 参数：-v gen=gen0001
-- =============================================================================

\set ON_ERROR_STOP on
\if :{?gen}
\else
  \set gen gen0001
\endif

\timing on

-- -----------------------------------------------------------------------------
-- 0. 清理旧 fixture（按 ID 前缀删，不依赖 n）
-- -----------------------------------------------------------------------------
DELETE FROM qry_coauthor_edge     WHERE generation = :'gen' AND achievement_id LIKE 'fix-%';
DELETE FROM qry_contribution_org  WHERE generation = :'gen' AND achievement_id LIKE 'fix-%';
DELETE FROM qry_achievement_topic WHERE generation = :'gen' AND achievement_id LIKE 'fix-%';
DELETE FROM qry_contribution      WHERE generation = :'gen' AND achievement_id LIKE 'fix-%';
DELETE FROM qry_achievement       WHERE generation = :'gen' AND achievement_id LIKE 'fix-%';

-- -----------------------------------------------------------------------------
-- 工具：把一批 (achievement_id, scholar_id, order) 写进参与关系，并顺手建边。
--
-- fixture 的作者对全部合格（AUTO），不掺 PENDING_VERIFY：fixture 的用途是拓扑断言，
-- 掺入待核验会让「边数是否等于预期」变成一个概率问题。
-- 单独写成一个语句级 CTE 不可行（同一批要为 4 张表服务），因此这里落一张临时表。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS perf_fixture_author;
CREATE TEMP TABLE perf_fixture_author (
    achievement_id text NOT NULL,
    scholar_id     text NOT NULL,
    author_order   integer NOT NULL
);

-- 成果小工具：所有 fixture 成果共用同一套公开资格与数据性质
CREATE TEMP TABLE perf_fixture_work (
    achievement_id text PRIMARY KEY
);

-- =============================================================================
-- 1. 环：200 位学者围成一圈，相邻两位各合作一次
--    → 200 条边；i 到 j 的最短距离 = min(|i-j|, 200-|i-j|)
--    断言用：1↔4 = 3 跳；1↔7 = 6 跳（边界可达）；1↔10 = 8 跳 → 应在 6 跳内不可达
-- =============================================================================
INSERT INTO perf_fixture_work (achievement_id)
SELECT 'fix-ring-w-' || lpad(g::text, 4, '0') FROM generate_series(1, 200) g;

INSERT INTO perf_fixture_author (achievement_id, scholar_id, author_order)
SELECT 'fix-ring-w-' || lpad(g::text, 4, '0'),
       'sch-fix-ring-' || lpad((((g - 1) % 200) + 1)::text, 4, '0'), 1
FROM generate_series(1, 200) g
UNION ALL
SELECT 'fix-ring-w-' || lpad(g::text, 4, '0'),
       'sch-fix-ring-' || lpad(((g % 200) + 1)::text, 4, '0'), 2
FROM generate_series(1, 200) g;

-- =============================================================================
-- 2. 链：长度 10 / 30 / 60
--    → 9 / 29 / 59 条边；链 10 的两端距离 9 > 6，用于验证「深度上限如实报告」
--    链 30、60 用于验证大直径下 BFS 不会被提前截断成错的「不可达」
-- =============================================================================
INSERT INTO perf_fixture_work (achievement_id)
SELECT 'fix-chain' || len || '-w-' || lpad(g::text, 3, '0')
FROM (VALUES (10), (30), (60)) AS c(len), generate_series(1, 1000) g
WHERE g < len;

INSERT INTO perf_fixture_author (achievement_id, scholar_id, author_order)
SELECT 'fix-chain' || c.len || '-w-' || lpad(g::text, 3, '0'),
       'sch-fix-chain' || c.len || '-' || lpad(g::text, 3, '0'), 1
FROM (VALUES (10), (30), (60)) AS c(len), generate_series(1, 1000) g
WHERE g < c.len
UNION ALL
SELECT 'fix-chain' || c.len || '-w-' || lpad(g::text, 3, '0'),
       'sch-fix-chain' || c.len || '-' || lpad((g + 1)::text, 3, '0'), 2
FROM (VALUES (10), (30), (60)) AS c(len), generate_series(1, 1000) g
WHERE g < c.len;

-- =============================================================================
-- 3. 多条等长最短路径：a 与 d 之间 5 条互不相交的 2 跳路径
--    a - m_i - d（i = 1..5）
--    断言用：hops=2、path=['sch-fix-eq-a','sch-fix-eq-m1','sch-fix-eq-d']
--    （5 条等长路径里规范 ID 序列字典序最小的是经过 m1 的那条）。
--    这一条钉住的是「等长时选规范 ID 序列字典序最小」而不是「随便一条最短路径」。
-- =============================================================================
INSERT INTO perf_fixture_work (achievement_id)
SELECT 'fix-eq-w-' || lpad(g::text, 2, '0') FROM generate_series(1, 5) g
UNION ALL
SELECT 'fix-eq-w2-' || lpad(g::text, 2, '0') FROM generate_series(1, 5) g;

INSERT INTO perf_fixture_author (achievement_id, scholar_id, author_order)
SELECT 'fix-eq-w-' || lpad(g::text, 2, '0'), 'sch-fix-eq-a', 1 FROM generate_series(1, 5) g
UNION ALL
SELECT 'fix-eq-w-' || lpad(g::text, 2, '0'),
       'sch-fix-eq-m' || g, 2
FROM generate_series(1, 5) g
UNION ALL
SELECT 'fix-eq-w2-' || lpad(g::text, 2, '0'),
       'sch-fix-eq-m' || g, 1
FROM generate_series(1, 5) g
UNION ALL
SELECT 'fix-eq-w2-' || lpad(g::text, 2, '0'), 'sch-fix-eq-d', 2 FROM generate_series(1, 5) g;

-- =============================================================================
-- 4. 深度树：root → 3 子 → 9 孙 → 27 曾孙
--    depth=1 → 4 节点 / 3 边；depth=2 → 13 / 12；depth=3 → 40 / 39
--    断言用：numbers 与 edges 必须精确相等；少一个说明边界节点没补边，多一个说明越层
-- =============================================================================
INSERT INTO perf_fixture_work (achievement_id)
SELECT 'fix-tree-w-c' || c FROM generate_series(1, 3) c
UNION ALL
SELECT 'fix-tree-w-g' || c || '-' || g FROM generate_series(1, 3) c, generate_series(1, 3) g
UNION ALL
SELECT 'fix-tree-w-gg' || c || '-' || g || '-' || h
FROM generate_series(1, 3) c, generate_series(1, 3) g, generate_series(1, 3) h;

INSERT INTO perf_fixture_author (achievement_id, scholar_id, author_order)
SELECT 'fix-tree-w-c' || c, 'sch-fix-tree-root', 1 FROM generate_series(1, 3) c
UNION ALL
SELECT 'fix-tree-w-c' || c, 'sch-fix-tree-c' || c, 2 FROM generate_series(1, 3) c
UNION ALL
SELECT 'fix-tree-w-g' || c || '-' || g, 'sch-fix-tree-c' || c, 1
FROM generate_series(1, 3) c, generate_series(1, 3) g
UNION ALL
SELECT 'fix-tree-w-g' || c || '-' || g, 'sch-fix-tree-g' || c || '-' || g, 2
FROM generate_series(1, 3) c, generate_series(1, 3) g
UNION ALL
SELECT 'fix-tree-w-gg' || c || '-' || g || '-' || h, 'sch-fix-tree-g' || c || '-' || g, 1
FROM generate_series(1, 3) c, generate_series(1, 3) g, generate_series(1, 3) h
UNION ALL
SELECT 'fix-tree-w-gg' || c || '-' || g || '-' || h, 'sch-fix-tree-gg' || c || '-' || g || '-' || h, 2
FROM generate_series(1, 3) c, generate_series(1, 3) g, generate_series(1, 3) h;

-- =============================================================================
-- 5. 孤立学者：每位只有一篇单作者成果
--    → 0 条边；作为网络种子时必须返回 seedInScope=false 并给出说明，
--      不得伪造一个「他自己」的孤立节点（那会让人以为他有档案但没有合作者）
-- =============================================================================
INSERT INTO perf_fixture_work (achievement_id)
SELECT 'fix-iso-w-' || lpad(g::text, 3, '0') FROM generate_series(1, 50) g;

INSERT INTO perf_fixture_author (achievement_id, scholar_id, author_order)
SELECT 'fix-iso-w-' || lpad(g::text, 3, '0'),
       'sch-fix-iso-' || lpad(g::text, 3, '0'), 1
FROM generate_series(1, 50) g;

-- =============================================================================
-- 6. 枢纽：1 位核心学者与 300 位各合作一次
--    断言用：depth=1 且 maxNodes 缺省（20000）→ 301 节点 / 300 边；
--            depth=1 且 maxNodes=100 → 必须 503 GRAPH_SCOPE_TOO_LARGE，
--            不是「返回前 100 个当作完整子图」
-- =============================================================================
INSERT INTO perf_fixture_work (achievement_id)
SELECT 'fix-hub-w-' || lpad(g::text, 3, '0') FROM generate_series(1, 300) g;

INSERT INTO perf_fixture_author (achievement_id, scholar_id, author_order)
SELECT 'fix-hub-w-' || lpad(g::text, 3, '0'), 'sch-fix-hub-core', 1
FROM generate_series(1, 300) g
UNION ALL
SELECT 'fix-hub-w-' || lpad(g::text, 3, '0'),
       'sch-fix-hub-' || lpad(g::text, 3, '0'), 2
FROM generate_series(1, 300) g;

-- =============================================================================
-- 7. 高作者数成果：200 篇成果，每篇 40 位作者（> 物化阈值 20）
--    → PG：每篇 C(40,2)=780 条边，共 156,000 条（PG 侧上限是 200 位作者，40 位不触隔离）
--    → Neo4j：author_count=40 > 阈值 → 不物化 COOPERATED_WITH，只剩 AUTHORED，
--      查询必须走二部图（UNMATERIALIZED_BIPARTITE_QUERY）才能得到共作者关系。
--    这一条是「PG 边多、图库边少」这个结构性差异的显式覆盖，影子对照若只看边数会误报。
-- =============================================================================
INSERT INTO perf_fixture_work (achievement_id)
SELECT 'fix-ha-w-' || lpad(g::text, 3, '0') FROM generate_series(1, 200) g;

INSERT INTO perf_fixture_author (achievement_id, scholar_id, author_order)
SELECT 'fix-ha-w-' || lpad(g::text, 3, '0'),
       'sch-fix-ha-' || lpad(g::text, 3, '0') || '-' || lpad(a::text, 2, '0'),
       a
FROM generate_series(1, 200) g, generate_series(1, 40) a;

-- =============================================================================
-- 8. 落库：成果 → 参与关系 → 机构 → 领域 → 合作边
-- =============================================================================
INSERT INTO qry_achievement (
    generation, achievement_id, type, title, title_norm, data_nature, visibility,
    effective_status, integrity_status, publication_year, doi, venue, citation_count,
    citation_source, published_date, abstract_text, aggregate_version, revision, updated_at)
SELECT :'gen',
       w.achievement_id,
       'PAPER',
       '结构夹具 ' || w.achievement_id,
       lower('结构夹具 ' || w.achievement_id),
       'SIMULATED',
       'PUBLIC',
       'EFFECTIVE',
       'NONE',
       2020,
       NULL,
       '夹具期刊',
       0,
       'PERF_FIXTURE',
       NULL,
       NULL,
       1,
       1,
       now()
FROM perf_fixture_work w
WHERE NOT EXISTS (
  SELECT 1 FROM qry_achievement a
  WHERE a.generation = :'gen' AND a.achievement_id = w.achievement_id);

INSERT INTO qry_contribution (
    generation, achievement_id, scholar_id, scholar_name, role, author_order, relation_state)
SELECT :'gen', f.achievement_id, f.scholar_id,
       CASE WHEN f.scholar_id LIKE 'sch-%' THEN '夹具学者' || right(f.scholar_id, 4)
            ELSE '夹具学者' END,
       'AUTHOR', f.author_order, 'AUTO'
FROM perf_fixture_author f;

-- 机构：只有 fixture 的外圈学者带机构，便于用 organization 过滤缩小到 fixture 子图
INSERT INTO qry_contribution_org (generation, achievement_id, scholar_id, organization)
SELECT :'gen', f.achievement_id, f.scholar_id,
       CASE WHEN f.scholar_id LIKE '%ring%' THEN '机构夹具环'
            WHEN f.scholar_id LIKE '%tree%' THEN '机构夹具树'
            ELSE '机构夹具其他' END
FROM perf_fixture_author f
WHERE f.scholar_id LIKE '%ring%' OR f.scholar_id LIKE '%tree%';

INSERT INTO qry_achievement_topic (
    generation, achievement_id, topic_id, canonical_name, assignment_kind, confidence)
SELECT :'gen', w.achievement_id, 'topic-fix-graph', '合作网络夹具', 'SOURCE', NULL
FROM perf_fixture_work w;

INSERT INTO qry_coauthor_edge (generation, achievement_id, scholar_a, scholar_b)
SELECT :'gen', a.achievement_id,
       LEAST(a.scholar_id, b.scholar_id),
       GREATEST(a.scholar_id, b.scholar_id)
FROM perf_fixture_author a
JOIN perf_fixture_author b
  ON b.achievement_id = a.achievement_id
 AND b.scholar_id > a.scholar_id;

ANALYZE qry_achievement;
ANALYZE qry_contribution;
ANALYZE qry_coauthor_edge;

-- -----------------------------------------------------------------------------
-- 9. 自检：fixture 的拓扑必须与注释里写的一致，否则后续断言全部无意义
-- -----------------------------------------------------------------------------
SELECT 'fix 成果数' AS metric, count(*)::text AS value FROM qry_achievement
 WHERE generation = :'gen' AND achievement_id LIKE 'fix-%'
UNION ALL
SELECT 'fix 参与关系', count(*)::text FROM qry_contribution
 WHERE generation = :'gen' AND achievement_id LIKE 'fix-%'
UNION ALL
SELECT 'fix 合作边', count(*)::text FROM qry_coauthor_edge
 WHERE generation = :'gen' AND achievement_id LIKE 'fix-%'
UNION ALL
SELECT 'fix 环边数（应为 200）', count(*)::text FROM qry_coauthor_edge
 WHERE generation = :'gen' AND achievement_id LIKE 'fix-ring-%'
UNION ALL
SELECT 'fix 树边数（应为 39）', count(*)::text FROM qry_coauthor_edge
 WHERE generation = :'gen' AND achievement_id LIKE 'fix-tree-%'
UNION ALL
SELECT 'fix 高作者成果边数（应为 156000）', count(*)::text FROM qry_coauthor_edge
 WHERE generation = :'gen' AND achievement_id LIKE 'fix-ha-%'
UNION ALL
SELECT 'fix 孤立成果边数（应为 0）', count(*)::text FROM qry_coauthor_edge
 WHERE generation = :'gen' AND achievement_id LIKE 'fix-iso-%';

DROP TABLE perf_fixture_author;
DROP TABLE perf_fixture_work;
