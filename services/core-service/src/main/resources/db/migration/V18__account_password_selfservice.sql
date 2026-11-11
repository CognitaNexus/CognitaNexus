-- 账号口令自助变更与管理员重置。
--
-- 为什么需要 must_change_password：
--   平台不接邮件，也没有短信通道，所以「忘记口令」不存在自助找回路径。唯一可行的路径是
--   管理员重置为一个一次性口令并线下告知本人。若不标记「需修改」，这个一次性口令就会长期有效，
--   等于把临时凭据变成长期凭据。
--
-- 为什么标记只用于提示、不阻断登录：
--   硬阻断会把「忘记了口令、又联系不上管理员」的用户彻底锁在门外，而本平台没有第二条找回通道。
--   登录照常成功，页面顶部提示，直到本人改密为止；这一取舍写在接口说明里，不作为安全承诺。
--
-- 为什么记 password_changed_at 而不区分是谁改的：
--   区分记在审计（PASSWORD_CHANGED / PASSWORD_RESET_BY_ADMIN）里，账号表只保留「最近一次变更时刻」。
--   在账号表里再存一份「变更人」会与审计形成两个事实来源，且必然出现不同步。
ALTER TABLE iam_account
    ADD COLUMN must_change_password boolean NOT NULL DEFAULT false,
    ADD COLUMN password_changed_at timestamptz;

COMMENT ON COLUMN iam_account.must_change_password IS
    '管理员重置口令后为 true，本人改密成功后清零。仅用于页面提示，不阻断登录（本平台无邮件找回通道）。';
COMMENT ON COLUMN iam_account.password_changed_at IS
    '最近一次口令变更时刻；是本人改密还是管理员重置，见审计事件 PASSWORD_CHANGED / PASSWORD_RESET_BY_ADMIN。';
