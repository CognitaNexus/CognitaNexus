-- 暂存待交接记录的取件索引：由「按来源」改为「按任务」
--
-- 背景：交接取件原先按 source_config_id 过滤，取出该来源下所有 STAGED 记录。
-- 但暂存去重是按来源的（uk_ing_staged_handoff_key = 来源 + 来源记录号 + 内容指纹），
-- 同一来源被多个任务各抓一遍时，同一个来源记录只会留下先到的那一行，且行上的 job_id
-- 是「先抓到它的那个任务」。于是：
--   - 后来的任务会领走兄弟任务暂存的记录，挂到自己的批次上（批次的 job_id 取自发起任务）
--   - 交接计数记在从未抓过这些记录的任务头上，出现「交接数 > 暂存数」的假账
--   - 真正抓取它们的任务，自己的记录被领走后再也取不到，永久滞留
-- 三个后果都来自「取件维度（来源）与归属维度（任务）不一致」。取件已改为按 job_id，
-- 这里补上与之匹配的索引。
--
-- 不删 ix_ing_staged_pending：它服务于来源维度的统计（countStagedByState 按
-- source_config_id + handoff_state 聚合），与取件是两种不同的访问路径，各留一个。

CREATE INDEX ix_ing_staged_pending_by_job
    ON ing_staged_record (job_id, fetched_at)
    WHERE handoff_state = 'STAGED';

COMMENT ON INDEX ix_ing_staged_pending_by_job IS
    '交接取件：按任务取本任务自己暂存的 STAGED 记录，与批次归属维度一致。';

-- 历史残留说明（本迁移刻意不改写数据）：
-- 修复前已入库的 ing_collect_job.total_handed_off 中，部分任务的这个数被兄弟任务领走的
-- 记录抬高了，而真正抓取它们的任务则被压低。这个数是从 ing_staged_record.job_id 这一
-- 权威归属事实累加出来的，但「已经交接完成」这一历史动作没有留痕到可精确回放的程度
-- （重试窗口内的批次可能被重复计入），因此无法在不猜测的前提下重算。
-- 选择保留旧值并在此声明，而不是用一条看起来更整齐的 UPDATE 把它抹平：抹平会让一个
-- 无法证实的数看起来像实测值。新产生的交接已按任务归属正确计数。
