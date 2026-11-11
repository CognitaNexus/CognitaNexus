-- 核心库结构自检：核对迁移 V1~V5 声明的关键不变量确实落到数据库
-- 用法：psql -h 127.0.0.1 -U core_app -d core_db -f verify-core-schema.sql

\pset border 0
\echo '=== 1. 分域表清单 ==='
SELECT left(table_name, 30) AS 表名,
       CASE split_part(table_name, '_', 1)
         WHEN 'ref' THEN '参考' WHEN 'iam' THEN '账号授权' WHEN 'raw' THEN '来源'
         WHEN 'core' THEN '成果主体' WHEN 'gov' THEN '治理' WHEN 'wf' THEN '流程'
         WHEN 'evt' THEN '事件' WHEN 'aud' THEN '审计' ELSE '其他'
       END AS 分域
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'
ORDER BY table_name;

\echo ''
\echo '=== 2. 部分唯一索引（承担业务不变量）==='
SELECT indexname AS 索引名
FROM pg_indexes
WHERE schemaname = 'public' AND indexdef ILIKE '%WHERE%'
ORDER BY indexname;

\echo ''
\echo '=== 3. 延迟约束触发器（类型扩展一致性）==='
SELECT tgname AS 触发器, tgdeferrable AS 可延迟, tginitdeferred AS 初始延迟
FROM pg_trigger WHERE NOT tgisinternal ORDER BY tgname;

\echo ''
\echo '=== 4. append-only 授权：core_app 对追加表不得持有 UPDATE/DELETE ==='
SELECT c.relname AS 表名,
       COALESCE(string_agg(DISTINCT p.privilege_type, ','), '无') AS core_app权限
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
LEFT JOIN information_schema.table_privileges p
       ON p.table_name = c.relname AND p.grantee = 'core_app'
WHERE c.relname IN ('aud_audit_event', 'evt_outbox', 'raw_source_record', 'gov_merge_ledger')
GROUP BY c.relname
ORDER BY c.relname;

\echo ''
\echo '=== 5. 版本计数器（目录修订号提交序来源）==='
SELECT id, current_revision, required_public_revision FROM evt_revision_counter;

\echo ''
\echo '=== 6. 登录节流列 ==='
SELECT column_name AS 列名, data_type AS 类型, is_nullable AS 可空
FROM information_schema.columns
WHERE table_name = 'iam_account'
  AND column_name IN ('failed_login_attempts', 'locked_until', 'last_login_at', 'auth_version')
ORDER BY column_name;

\echo ''
\echo '=== 7. 相似度索引（仅用于候选生成）==='
SELECT indexname AS 索引名 FROM pg_indexes
WHERE schemaname = 'public' AND indexdef ILIKE '%gin_trgm_ops%' ORDER BY indexname;
