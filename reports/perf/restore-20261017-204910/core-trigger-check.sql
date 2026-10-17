-- 恢复库上「可发布成果必须有类型扩展」这条延迟约束是否仍然生效。
-- 延迟触发器在提交时检查，因此这里必须是一条独立的自动提交 INSERT，不能用 DO 块包住：
-- 包住之后 RAISE 会先于提交执行，检查根本没机会发生（首次演练就这样误判过一次）。
INSERT INTO core_achievement (id, type, title, title_norm, data_nature, visibility, effective_status)
VALUES ('restore-check-1','PAPER','恢复校验用','恢复校验用','SIMULATED','PUBLIC','EFFECTIVE');
