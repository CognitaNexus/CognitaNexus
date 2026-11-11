-- =============================================================================
-- CognitaNexus core-service 迁移 V2
-- 1. 跨表不变量：成果的 type 与类型扩展必须一致，且可发布成果必须恰好有一个扩展
-- 2. 只追加表禁止业务更新与删除（审计、Outbox 正文、原始来源版本、合并台账）
-- 依据：架构 V0.2 §5.1（每个成果恰好一个类型扩展）、§5.2（写入策略）、§5.4
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 类型扩展一致性
-- 语句级触发器校验类型匹配；提交级（DEFERRABLE INITIALLY DEFERRED）校验
-- 「可发布成果必须有且仅有一个匹配扩展」，从而允许治理过程中先建实体后补扩展。
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_core_check_extension_type()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_expected text;
    v_actual   text;
BEGIN
    v_expected := TG_ARGV[0];
    SELECT type INTO v_actual FROM core_achievement WHERE id = NEW.achievement_id;
    IF v_actual IS NULL THEN
        RAISE EXCEPTION '成果 % 不存在，无法建立类型扩展', NEW.achievement_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF v_actual <> v_expected THEN
        RAISE EXCEPTION '类型扩展不匹配：成果 % 的类型为 %，不允许建立 % 扩展',
            NEW.achievement_id, v_actual, v_expected
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_core_paper_type
    AFTER INSERT OR UPDATE ON core_paper
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION fn_core_check_extension_type('PAPER');

CREATE CONSTRAINT TRIGGER ct_core_patent_type
    AFTER INSERT OR UPDATE ON core_patent
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION fn_core_check_extension_type('PATENT');

CREATE CONSTRAINT TRIGGER ct_core_research_project_type
    AFTER INSERT OR UPDATE ON core_research_project
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION fn_core_check_extension_type('PROJECT');

CREATE CONSTRAINT TRIGGER ct_core_award_type
    AFTER INSERT OR UPDATE ON core_award
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION fn_core_check_extension_type('AWARD');

-- 可发布成果（PUBLIC 或 EFFECTIVE）必须恰好存在一个与 type 匹配的扩展。
CREATE OR REPLACE FUNCTION fn_core_require_matching_extension()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_count integer;
BEGIN
    SELECT (
        (SELECT count(*) FROM core_paper            WHERE achievement_id = NEW.id)
      + (SELECT count(*) FROM core_patent           WHERE achievement_id = NEW.id)
      + (SELECT count(*) FROM core_research_project WHERE achievement_id = NEW.id)
      + (SELECT count(*) FROM core_award            WHERE achievement_id = NEW.id)
    ) INTO v_count;

    IF (NEW.visibility = 'PUBLIC' OR NEW.effective_status = 'EFFECTIVE') AND v_count <> 1 THEN
        RAISE EXCEPTION '可发布成果 % 必须恰好有一个类型扩展，当前为 % 个', NEW.id, v_count
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_core_achievement_require_extension
    AFTER INSERT OR UPDATE ON core_achievement
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_core_require_matching_extension();

-- -----------------------------------------------------------------------------
-- 2. 只追加表：回收业务运行账号的 UPDATE/DELETE 权限
-- 迁移以库所有者执行，可回收自己授予运行账号的权限；超级用户仍保留维护能力。
-- 角色不存在时静默跳过，保证迁移可在不同环境重复执行。
-- 说明：evt_outbox 投递状态存放在 evt_outbox_delivery，正文表本身保持只追加。
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    v_app text := 'core_app';
    v_table text;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_app) THEN
        RAISE NOTICE '角色 % 不存在，跳过只追加表权限回收', v_app;
        RETURN;
    END IF;

    FOREACH v_table IN ARRAY ARRAY[
        'aud_audit_event', 'evt_outbox', 'raw_source_record', 'gov_merge_ledger'
    ]
    LOOP
        EXECUTE format('REVOKE UPDATE, DELETE ON TABLE %I FROM %I', v_table, v_app);
    END LOOP;
END $$;
