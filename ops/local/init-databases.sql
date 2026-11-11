-- CognitaNexus 本地开发数据库初始化
-- 依据：架构 V0.2 §4.1（三服务独立数据库、独立账号、独立迁移历史、禁止跨库）
--       §12.1（迁移账号与运行账号权限分离）
-- 执行者：postgres 超级用户
-- 幂等：可重复执行

DO $$
DECLARE r RECORD;
BEGIN
  FOR r IN SELECT * FROM (VALUES
      ('core_owner','cn_core_owner_dev'),
      ('core_app','cn_core_app_dev'),
      ('ingestion_owner','cn_ingestion_owner_dev'),
      ('ingestion_app','cn_ingestion_app_dev'),
      ('query_owner','cn_query_owner_dev'),
      ('query_app','cn_query_app_dev')
    ) AS t(role_name, role_pw)
  LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r.role_name) THEN
      EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', r.role_name, r.role_pw);
    END IF;
  END LOOP;
END $$;

-- 建库：owner 为迁移账号（拥有 DDL），app 为运行账号（仅 DML）
-- psql 不支持在 DO 块内创建数据库，故由调用脚本按库执行
