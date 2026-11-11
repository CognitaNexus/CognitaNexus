-- Wikidata 专利来源的适配器种类。
--
-- 专利没有像 Crossref／OpenAlex 那样的通用元数据服务：USPTO ODP 匿名 401、EPO OPS 匿名 403、
-- PatentsView 旧接口已下线。Wikidata SPARQL 是唯一免密钥、可按属性投影、且条款允许再分发的
-- 来源（CC0）。适配器 WikidataPatentAdapter 已实现并注册进 SourceAdapterRegistry，此处把
-- 约束值域同步放开。
--
-- 与 V6/V7 同样的教训：枚举加了、约束没跟，登记时插入失败，而接口只回「服务内部错误」，
-- 运维看到的是一个没有原因的错误码。约束与已实现适配器保持一致，仍然不放未实现的种类进来。
ALTER TABLE ing_source_run_config DROP CONSTRAINT ck_ing_source_adapter_kind;

ALTER TABLE ing_source_run_config
    ADD CONSTRAINT ck_ing_source_adapter_kind
        CHECK (adapter_kind IN ('CROSSREF', 'OPENALEX', 'NSF', 'NOBEL', 'WIKIDATA_PATENT', 'FILE'));
