-- =============================================================================
-- CognitaNexus ingestion-service 迁移 V2
-- 运行账号权限自举（与 core 的 V6 同一目的：让迁移自足，不依赖建库脚本的手工步骤）
-- 依据：架构 V0.2 §12.1（迁移账号与运行账号权限分离）
-- =============================================================================

DO $$
DECLARE
    v_owner text := current_user;
    v_app   text := 'ingestion_app';
    v_append_only text[] := ARRAY['ing_fetch_log', 'ing_audit_event'];
    v_table text;
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

    -- 覆盖上面的普遍授予：只追加表恢复为仅 SELECT/INSERT
    FOREACH v_table IN ARRAY v_append_only
    LOOP
        EXECUTE format('REVOKE UPDATE, DELETE ON TABLE %I FROM %I', v_table, v_app);
    END LOOP;

    EXECUTE format('REVOKE CREATE ON SCHEMA public FROM %I', v_app);
    RAISE NOTICE '运行账号 % 权限自举完成', v_app;
END $$;
