-- 补齐执行副本的权威版本列
--
-- 背景：来源运行配置需要同时记录两个版本，缺一不可。
--   config_version         = 本地副本当前生效的版本
--   synced_config_version  = 最近一次从 core 同步到的权威版本
-- 两者不一致（或 synced 落后）时不得继续按旧副本抓取：字段白名单可能已变，
-- 继续运行会把新白名单之外的字段写进暂存 payload（见 SourceRunConfig.runnable）。
-- V1 建表时遗漏了后者，导致「启用采集」与「同步权威配置」两条路径都会写失败。
--
-- 默认 0 表示「从未与 core 对账过」，而不是「已与版本 0 一致」：core 的版本号从 1 起，
-- 因此 0 只能表示未同步，不会与任何真实权威版本相等。

ALTER TABLE ing_source_run_config
    ADD COLUMN synced_config_version integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN ing_source_run_config.synced_config_version IS
    '最近一次从 core 同步到的权威配置版本；0 表示尚未对账，此时该来源不允许执行抓取。';

-- 说明：这里刻意不为「启用即必须已对账」加 CHECK 约束。
-- 本地开关与「能否抓取」是两件事：前者是运维意图，后者由 core 的许可、权威版本一致性
-- 与本服务每批重查共同决定（SourceRunConfig.runnable）。若在库级禁止「已启用但未对账」，
-- 登记流程就被迫变成「先同步后启用」两步，而同步又要求来源已在 core 登记——这不必要地
-- 把两套系统的操作顺序绑死，且并不比 runnable() 更安全：真正的防线是执行前的判据。
