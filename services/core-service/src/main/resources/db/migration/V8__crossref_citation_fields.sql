-- 把引用量与其来源纳入 Crossref 来源字段白名单，并提升配置版本
--
-- 问题：core_paper 从 V1 起就有 citation_count / citation_source 两列，接收与公开查询链路
-- （SourceIntakeService、PublicCatalogQueryService、AdminCatalogService）也都已经读写它们，
-- OpenAlex 适配器也已在映射 cited_by_count。唯独 Crossref 适配器没有映射
-- is-referenced-by-count，且本表 allowed_fields 白名单里没有这两个字段名。两处缺失叠加的结果是：
-- 真实采集进来的成果全部没有引用量，公开统计中 citationsBySource 恒为空数组。
--
-- 这不是显示层的小瑕疵。「按来源分别展示、不跨来源相加、不提供总引用」这条规则写在架构里，
-- 前提就是先得有按来源的引用数据；一条都收不到时，规则成了空转，看起来像功能已实现。
--
-- 为什么同时提升 config_version：ingestion 在每批开始前重查本行并同步执行副本。若只改内容
-- 而不动版本号，已在运行的采集器不会察觉配置变化，仍按旧白名单过滤，改了等于没改。
--
-- 只追加、不删除已有白名单项：白名单是「允许写入 payload」的集合，收紧它会静默丢弃适配器
-- 已经采集到的字段。追加时按「首次出现位置」排序并去重：jsonb 数组合并本身不去重，直接 ||
-- 撞上已有同名项会写出重复条目；而按值排序会打乱既有顺序，让这行配置以后难以人工比对。
UPDATE raw_source_config
SET allowed_fields = (
        SELECT jsonb_agg(value ORDER BY first_seen)
        FROM (
            SELECT value, min(ord) AS first_seen
            FROM (
                SELECT value, ordinality AS ord
                FROM jsonb_array_elements_text(allowed_fields)
                     WITH ORDINALITY AS t(value, ordinality)
                UNION ALL
                SELECT extra, 100000 + row_number() OVER () AS ord
                FROM (VALUES ('citationCount'), ('citationSource')) AS e(extra)
            ) merged
            GROUP BY value
        ) deduped
    ),
    config_version = config_version + 1,
    updated_at = now()
WHERE source_code = 'crossref';

COMMENT ON COLUMN core_paper.citation_source IS
    '引用量来源标识。与 citation_count 成对出现：只有来源明确时引用量才可对外展示，'
    '禁止跨来源相加制造「总引用」。';
