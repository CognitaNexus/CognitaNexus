-- =============================================================================
-- 容量联调造数（B 阶段）——core 规范实体侧
--
-- 与 seed-query.sql 使用同一批 id 与同一批分布，使「规范去重成果数」与「可检索去重成果数」
-- 能够逐层对上。仍然全部是 SIMULATED：合成数据不得冒充真实收录。
--
-- 有意不造的部分（报告里要写清，不能拿别的东西顶替）：
--   * raw_source_record / core_achievement_source：跨来源对应关系只在 A 阶段的样本集里有，
--     这里不批量造假来源，避免「两个镜像文件当两个独立来源」这种伪多源。
--   * core_identifier_registry：批量生成 DOI 注册表对检索容量没有影响，但对「去重」口径有影响，
--     所以宁可留空并说明，也不编一个看起来完整的数字。
-- =============================================================================

\set ON_ERROR_STOP on

SELECT setseed(:'seed'::float8);

DELETE FROM core_paper WHERE achievement_id LIKE 'perf-%';
DELETE FROM core_patent WHERE achievement_id LIKE 'perf-%';
DELETE FROM core_research_project WHERE achievement_id LIKE 'perf-%';
DELETE FROM core_award WHERE achievement_id LIKE 'perf-%';
DELETE FROM core_achievement WHERE id LIKE 'perf-%';

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

INSERT INTO core_achievement (
    id, type, title, title_norm, data_nature, visibility, effective_status,
    publication_year, year_precision, version)
WITH base AS (
    SELECT i,
           CASE WHEN r < 0.55 THEN 'PAPER'
                WHEN r < 0.70 THEN 'PATENT'
                WHEN r < 0.90 THEN 'PROJECT'
                ELSE 'AWARD' END AS type,
           r2, r3
    FROM (SELECT i, random() AS r, random() AS r2, random() AS r3
          FROM generate_series(1, :n) i) s
),
titled AS (
    -- 与 seed-query.sql 用同一套派生方式，保证两侧标题逐字一致。
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
SELECT 'perf-' || lpad(i::text, 8, '0'),
       type,
       core_title || CASE WHEN r2 < 0.20 THEN '（样本' || i || '）' ELSE '' END,
       lower(core_title || CASE WHEN r2 < 0.20 THEN '（样本' || i || '）' ELSE '' END),
       'SIMULATED',
       -- core 侧合法取值是 HIDDEN，query 侧读模型写的是 PRIVATE，这是既有映射，不是笔误
       CASE WHEN r2 < 0.92 THEN 'PUBLIC' WHEN r2 < 0.98 THEN 'RESTRICTED' ELSE 'HIDDEN' END,
       CASE WHEN r3 < 0.90 THEN 'EFFECTIVE' WHEN r3 < 0.97 THEN 'PENDING_GOVERNANCE' ELSE 'WITHDRAWN' END,
       CASE WHEN r3 < 0.05 THEN NULL ELSE (1990 + floor(power(random(), 0.6) * 37))::int END,
       CASE WHEN r3 < 0.05 THEN NULL ELSE 'YEAR' END,
       1
FROM titled;

INSERT INTO core_paper (achievement_id, doi, doi_norm, venue, citation_count, citation_source)
SELECT a.id,
       '10.9999/perf.' || substring(a.id from 6),
       lower('10.9999/perf.' || substring(a.id from 6)),
       '期刊第' || (1 + (abs(hashtext(a.id)) % 500)) || '卷',
       (abs(hashtext(a.id || 'c')) % 5000),
       'PERF_SEED'
FROM core_achievement a
WHERE a.type = 'PAPER' AND a.id LIKE 'perf-%';

INSERT INTO core_patent (achievement_id, application_no, application_no_norm)
SELECT a.id, 'CNPERF' || substring(a.id from 6), lower('CNPERF' || substring(a.id from 6))
FROM core_achievement a
WHERE a.type = 'PATENT' AND a.id LIKE 'perf-%';

INSERT INTO core_research_project (achievement_id, project_no, project_no_norm, funding_agency)
SELECT a.id, 'PERFP' || substring(a.id from 6), lower('PERFP' || substring(a.id from 6)),
       '机构' || (1 + (abs(hashtext(a.id)) % 500))
FROM core_achievement a
WHERE a.type = 'PROJECT' AND a.id LIKE 'perf-%';

INSERT INTO core_award (achievement_id, award_name, award_year)
SELECT a.id, a.title, a.publication_year
FROM core_achievement a
WHERE a.type = 'AWARD' AND a.id LIKE 'perf-%';

ANALYZE core_achievement;
ANALYZE core_paper;
