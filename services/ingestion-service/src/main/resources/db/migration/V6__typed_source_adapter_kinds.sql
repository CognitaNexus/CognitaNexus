-- 项目与获奖两类来源的适配器种类。
--
-- 一期原本只有 CROSSREF / OPENALEX 两个论文来源，约束值域也照着这两个写死。接入 NSF（科研项目）
-- 与 Nobel（获奖）后必须同步扩展：约束不放开时，登记会在插入时失败，而接口只回「服务内部错误」，
-- 运维看到的是一个没有原因的错误码。约束与已实现适配器保持一致，仍然不放未实现的种类进来。
ALTER TABLE ing_source_run_config DROP CONSTRAINT ck_ing_source_adapter_kind;

ALTER TABLE ing_source_run_config
    ADD CONSTRAINT ck_ing_source_adapter_kind
        CHECK (adapter_kind IN ('CROSSREF', 'OPENALEX', 'NSF', 'NOBEL'));
