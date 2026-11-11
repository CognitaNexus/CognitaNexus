-- =============================================================================
-- CognitaNexus query-service 迁移 V2：运行账号权限自举
-- 与 core 的 V6、ingestion 的 V2 同一目的：迁移自足，不依赖建库脚本的手工步骤。
-- 依据：架构 V0.2 §12.1（迁移账号与运行账号权限分离）
-- =============================================================================

DO $$
DECLARE
    v_owner text := current_user;
    v_app   text := 'query_app';
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_app) THEN
        RAISE NOTICE '角色 % 不存在，跳过运行账号权限自举', v_app;
        RETURN;
    END IF;

    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', v_app);
    EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', v_app);
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
        'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', v_owner, v_app);
    EXECUTE format('REVOKE CREATE ON SCHEMA public FROM %I', v_app);

    -- 派生数据可重建，因此运行账号确实需要 UPDATE/DELETE（架构 §4.2：对权威业务只读，
    -- 对本服务派生存储可写）。这里不做只追加限制，与 core 的 evt_outbox 不同。
    RAISE NOTICE '运行账号 % 权限自举完成', v_app;
END $$;
