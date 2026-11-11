-- 文件导入来源的适配器种类。
--
-- 专利类没有免密钥的公开检索 API，按 AC-IMPL-FR08 的「API/文件任选」走文件导入。该类来源同样
-- 需要在 ing_source_run_config 里登记，否则无法关联许可状态与字段白名单。它没有对应的
-- SourceAdapter：导入不经过抓取与游标翻页，给这类来源建采集任务会被明确拒绝。
ALTER TABLE ing_source_run_config DROP CONSTRAINT ck_ing_source_adapter_kind;

ALTER TABLE ing_source_run_config
    ADD CONSTRAINT ck_ing_source_adapter_kind
        CHECK (adapter_kind IN ('CROSSREF', 'OPENALEX', 'NSF', 'NOBEL', 'FILE'));
