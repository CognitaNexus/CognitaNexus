-- =============================================================================
-- V4：登录失败计数与锁定（架构 V0.2 §8.1、§10.2）
--
-- 安全限流计数不得放入可随内存压力淘汰的实例。redis-session 只提供短窗防护；
-- 账号的失败次数与锁定状态必须落在核心库，Redis 不健康或数据丢失时仍能拒绝口令爆破。
--
-- 锁定到期时间用数据库时钟比较，不依赖应用各机器的本地时间。
-- =============================================================================

ALTER TABLE iam_account
    ADD COLUMN failed_login_attempts integer     NOT NULL DEFAULT 0,
    ADD COLUMN locked_until          timestamptz,
    ADD COLUMN last_login_at         timestamptz;

COMMENT ON COLUMN iam_account.failed_login_attempts IS
    '连续失败次数。登录成功时清零；该计数与 locked_until 共同构成权威的爆破防护，Redis 计数只是短窗补充。';
COMMENT ON COLUMN iam_account.locked_until IS
    '锁定截止时刻，使用数据库时钟比较。为 NULL 表示未锁定。';
