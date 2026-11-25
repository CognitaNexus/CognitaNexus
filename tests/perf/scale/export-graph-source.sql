-- =============================================================================
-- 把 PG 读投影导出成图投影的四个输入流（works / scholars / authored / cooperated）
--
-- 为什么要有这一步：生产链路的图投影是**事件驱动、单成果事务**（GraphProjectionSynchronizer
-- 每次 replaceWork 一个事务，内部还有 4~6 条语句）。按那个速率把 500 万成果写进图库需要
-- 数十万次往返，压测窗口内跑不完，也测不到「图库能不能装下」这个容量问题。
-- 因此容量阶段用同一套**口径**（同一批过滤条件、同一批 label/属性/关系）做批量装载，
-- 只把「怎么写」换成批量 UNWIND；「写在多少时间内完成、占多少磁盘」仍是真实容量指标。
-- 单成果事务的真实吞吐另由 1 万阶段的小规模回填实测，两者不可混为一谈。
--
-- 口径与生产实现逐条对齐（对应 GraphProjectionSourceAdapter#assemble）：
--   * Work 节点：generation 内**全部**成果（含非公开，is_public=false 作为墓碑）；
--     is_public = visibility='PUBLIC' AND effective_status='EFFECTIVE'
--   * Scholar 节点：**关系合格**（AUTO / SELF_CONFIRMED）的学者，不论成果是否公开
--     （生产侧 upsertScholars 不受 is_public 影响）
--   * AUTHORED：合格关系 且 成果公开生效；relation_state / author_order / work_title 齐全
--   * COOPERATED_WITH：成果公开生效 且 合格作者数在 2..物化阈值(20) 之间，逐对一条；
--     每条只代表一篇成果（强度由 count(DISTINCT work_id) 得出），pair_key = a || chr(1) || b
--
-- 用法（输出到 stdout，由编排脚本重定向到文件）：
--   psql -v gen=gen0001 -v part=works -f tests/perf/scale/export-graph-source.sql > works.tsv
-- =============================================================================

\set ON_ERROR_STOP on
\if :{?gen}
\else
  \set gen gen0001
\endif
\if :{?part}
\else
  \set part works
\endif
\if :{?materialized_threshold}
\else
  \set materialized_threshold 20
\endif

\pset pager off
\pset tuples_only on
\pset format unaligned

-- 选择哪一个流：psql 的 \if 只接受布尔量（1/0、true/false），不接字符串比较，
-- 因此先把 'part' 的取值折成 1/0 变量，再用 \if 分支。
SELECT (:'part' = 'works')::int      AS is_works      \gset
SELECT (:'part' = 'scholars')::int   AS is_scholars   \gset
SELECT (:'part' = 'authored')::int   AS is_authored   \gset
SELECT (:'part' = 'cooperated')::int AS is_cooperated \gset

SELECT ((:'part' = 'works')::int + (:'part' = 'scholars')::int
      + (:'part' = 'authored')::int + (:'part' = 'cooperated')::int) AS part_count \gset

-- \if 只接受布尔量，不接受算术表达式：所以把「四个取值里命中了几个」先算成 1/0 变量。
-- 取值非法时用一条真实 SQL 错误中止（ON_ERROR_STOP 会让 psql 以非零码退出），
-- 而不是静默输出空文件——空文件在下游看起来像「这一阶段本来就没有数据」。
\if :part_count
\else
DO $$ BEGIN RAISE EXCEPTION 'part 必须是 works / scholars / authored / cooperated 之一'; END $$;
\endif

-- ---------------------------------------------------------------------------
-- works：work_id, title, is_public, data_origin, source_version, topic_ids,
--        organizations, author_count, edges_materialized
-- ---------------------------------------------------------------------------
\if :is_works
COPY (
  SELECT a.achievement_id,
         a.title,
         (a.visibility = 'PUBLIC' AND a.effective_status = 'EFFECTIVE') AS is_public,
         a.data_nature,
         a.aggregate_version,
         coalesce(t.topic_ids, ''),
         coalesce(o.organizations, ''),
         coalesce(c.author_count, 0),
         (a.visibility = 'PUBLIC' AND a.effective_status = 'EFFECTIVE'
          AND coalesce(c.author_count, 0) BETWEEN 2 AND :materialized_threshold)
  FROM qry_achievement a
  LEFT JOIN (
    SELECT achievement_id, string_agg(topic_id, chr(31)) AS topic_ids
    FROM qry_achievement_topic
    WHERE generation = :'gen'
    GROUP BY achievement_id
  ) t ON t.achievement_id = a.achievement_id
  LEFT JOIN (
    SELECT achievement_id, string_agg(organization, chr(31)) AS organizations
    FROM (
      SELECT DISTINCT achievement_id, organization
      FROM qry_contribution_org
      WHERE generation = :'gen'
    ) d
    GROUP BY achievement_id
  ) o ON o.achievement_id = a.achievement_id
  LEFT JOIN (
    SELECT achievement_id, count(*) AS author_count
    FROM qry_contribution
    WHERE generation = :'gen' AND relation_state IN ('AUTO', 'SELF_CONFIRMED')
    GROUP BY achievement_id
  ) c ON c.achievement_id = a.achievement_id
  WHERE a.generation = :'gen'
  ORDER BY a.achievement_id
) TO STDOUT;
\endif

-- ---------------------------------------------------------------------------
-- scholars：scholar_id, scholar_name, data_origin, source_version
--   同一学者可能出现在多个成果上，DISTINCT ON 取字典序第一条（姓名相同，取哪条都一样）
-- ---------------------------------------------------------------------------
\if :is_scholars
COPY (
  SELECT DISTINCT ON (c.scholar_id)
         c.scholar_id,
         coalesce(c.scholar_name, ''),
         a.data_nature,
         a.aggregate_version
  FROM qry_contribution c
  JOIN qry_achievement a
    ON a.generation = c.generation AND a.achievement_id = c.achievement_id
  WHERE c.generation = :'gen'
    AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
  ORDER BY c.scholar_id, c.achievement_id
) TO STDOUT;
\endif

-- ---------------------------------------------------------------------------
-- authored：scholar_id, work_id, work_title, relation_state, author_order
-- ---------------------------------------------------------------------------
\if :is_authored
COPY (
  SELECT c.scholar_id, c.achievement_id, a.title, c.relation_state, coalesce(c.author_order, 0)
  FROM qry_contribution c
  JOIN qry_achievement a
    ON a.generation = c.generation AND a.achievement_id = c.achievement_id
  WHERE c.generation = :'gen'
    AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
    AND a.visibility = 'PUBLIC'
    AND a.effective_status = 'EFFECTIVE'
  ORDER BY c.achievement_id, c.author_order NULLS LAST, c.scholar_id
) TO STDOUT;
\endif

-- ---------------------------------------------------------------------------
-- cooperated：scholar_a, scholar_b, work_id, work_title, pair_key, data_origin, source_version
--
-- 作者数用「合格贡献数」重新数一遍，而不是取 qry_coauthor_edge 的行数：
-- edge 表里含 PENDING_VERIFY 关系排除后的全部作者对，但物化判定在生产侧看的是
-- **作者数**（authors.size() <= 阈值），二者不是同一个量。
-- ---------------------------------------------------------------------------
\if :is_cooperated
COPY (
  SELECT e.scholar_a,
         e.scholar_b,
         e.achievement_id,
         a.title,
         e.scholar_a || chr(1) || e.scholar_b,
         a.data_nature,
         a.aggregate_version
  FROM qry_coauthor_edge e
  JOIN qry_achievement a
    ON a.generation = e.generation AND a.achievement_id = e.achievement_id
  WHERE e.generation = :'gen'
    AND a.visibility = 'PUBLIC'
    AND a.effective_status = 'EFFECTIVE'
    AND (
      SELECT count(*) FROM qry_contribution c
      WHERE c.generation = a.generation
        AND c.achievement_id = a.achievement_id
        AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
    ) BETWEEN 2 AND :materialized_threshold
  ORDER BY e.achievement_id, e.scholar_a, e.scholar_b
) TO STDOUT;
\endif
