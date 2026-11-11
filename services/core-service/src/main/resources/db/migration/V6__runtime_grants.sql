-- =============================================================================
-- CognitaNexus core-service 迁移 V6
-- 运行账号权限自举：让迁移自己声明运行账号需要哪些权限，不再依赖建库脚本的手工步骤
-- 依据：架构 V0.2 §12.1（迁移账号与运行账号权限分离）、编码规范 §7.2
-- =============================================================================
--
-- 背景与必要性：
--   架构要求迁移账号（core_owner）与运行账号（core_app）分离：前者拥有 DDL、后者只有 DML。
--   若权限只由建库脚本设置，则任何一次 schema 重建（例如本地重置、CI 起库）都会丢掉这些授权，
--   表现为应用启动正常、一旦查询就报“relation does not exist”——因为运行账号对新 schema
--   没有 USAGE。把授权写进迁移之后，重建 schema 即恢复完整可用状态。
--
-- 顺序注意（重要）：
--   本迁移会在全部表上授予 DML，因此必须在授予之后重新回收只追加表的 UPDATE/DELETE，
--   否则会覆盖 V2 建立的保护。V2 与本节共同构成“先普遍授予、再按例外回收”的规则，
--   两处的表清单必须保持一致；新增只追加表时需要同时更新。
-- =============================================================================

DO $$
DECLARE
    v_owner text := current_user;
    v_app   text := 'core_app';
    v_append_only text[] := ARRAY[
        'aud_audit_event', 'evt_outbox', 'raw_source_record', 'gov_merge_ledger'
    ];
    v_table text;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_app) THEN
        RAISE NOTICE '角色 % 不存在，跳过运行账号权限自举', v_app;
        RETURN;
    END IF;

    -- 1. 模式级 USAGE：没有它，运行账号连表名都看不到
    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', v_app);

    -- 2. 既有表的 DML 权限（本次迁移之前创建的表）
    EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', v_app);

    -- 3. 之后新建表的默认权限：由迁移账号创建的对象自动带上同样的 DML
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public '
        'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', v_owner, v_app);

    -- 4. 覆盖第 2 步的普遍授予：只追加表恢复为仅 SELECT/INSERT
    FOREACH v_table IN ARRAY v_append_only
    LOOP
        EXECUTE format('REVOKE UPDATE, DELETE ON TABLE %I FROM %I', v_table, v_app);
    END LOOP;

    -- 5. 运行账号不得持有 DDL 能力：schema 上不给 CREATE
    EXECUTE format('REVOKE CREATE ON SCHEMA public FROM %I', v_app);

    RAISE NOTICE '运行账号 % 权限自举完成（% 张只追加表已回收 UPDATE/DELETE）', v_app, array_length(v_append_only, 1);
END $$;
