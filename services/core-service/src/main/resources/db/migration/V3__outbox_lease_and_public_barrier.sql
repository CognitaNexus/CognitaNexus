-- =============================================================================
-- V3：投递租约与公开资格屏障水位（架构 V0.2 §6.3、§7.2）
--
-- 1) required_public_revision
--    降低可见性、撤销来源展示许可、撤销合格关系等「收紧」变更必须在同一事务推进权威公开水位，
--    使 query 立刻停止返回旧缓存、旧计数或旧路径证据，而不是等 TTL 过期。
--
-- 2) evt_outbox_delivery.lease_until
--    原先只有 next_attempt_at 一个时间列，它同时承担「重试时刻」与「租约到期」两种语义：
--    若把首次失败的重试延迟（1 秒）当作租约，慢请求期间同一事件会被第二个线程重复领取；
--    若把租约（数十秒）当作重试延迟，又会把 60 秒传播目标耗在等待上。
--    两个语义分开后：领取仅延长 lease_until；发送失败才写 next_attempt_at 的退避时刻。
--
-- 重复投递本身是被允许的（至少一次语义），接收端 Inbox 以 (consumerId, eventId) 唯一键消除重复业务效果；
-- 但发送端的重复领取应当尽量少，因为它会放大接收端负载并使指标失真。
-- =============================================================================

ALTER TABLE evt_revision_counter
    ADD COLUMN required_public_revision bigint NOT NULL DEFAULT 0;

COMMENT ON COLUMN evt_revision_counter.required_public_revision IS
    '隐私收紧类变更的同事务水位。query 的已发布水位低于此值时，公开请求必须返回 503 VIEW_UPDATING（架构 §7.2）。';

ALTER TABLE evt_outbox_delivery
    ADD COLUMN lease_until timestamptz;

COMMENT ON COLUMN evt_outbox_delivery.lease_until IS
    '投递租约。领取时延长；旧租约持有者恢复后不得直接推进状态，须由当前租约持有者写入。';

-- 领取扫描按接收者 + 可就绪时刻；把租约状态并入索引条件，避免全表过滤。
DROP INDEX IF EXISTS ix_evt_outbox_delivery_pending;
CREATE INDEX ix_evt_outbox_delivery_pending
    ON evt_outbox_delivery (consumer_id, next_attempt_at)
    WHERE status = 'PENDING';
