-- V17：回填「成果自助提交」批准建档时缺失的人工确认公开审计
--
-- 为什么需要这一段：
--   红线是「没有稳定标识的成果，未经人工确认不得公开」。平台里唯一会记录这次确认的动
--   作是治理端的「确认发布」（action = ACHIEVEMENT_PUBLISH_CONFIRMED）。成果自助提交的
--   审核通过路径直接把成果写成 PUBLIC/EFFECTIVE，却没有落这条事件——决定做了，留痕没有。
--   结果是：核对不变量时，8 条经人工批准、合规公开的成果被判成「未经确认就公开」。
--
-- 为什么不是伪造：
--   这条事件不新造决定。操作者、时间、理由全部取自该提交件上已经存在的
--   SUBMISSION_APPROVED 审计（同一个人、同一句话、同一时刻），after 值取成果当前真实
--   的公开状态；理由末尾追加「依据 SUBMISSION_APPROVED 审计回填」，读审计的人能看出
--   这一行是回填出来的，不是当时就写下的。
--
-- 幂等：已经存在 ACHIEVEMENT_PUBLISH_CONFIRMED 的成果不会重复插入；提交件没有对应
--   SUBMISSION_APPROVED 审计（即找不到可依据的人工决定）的成果也不会被补，
--   宁可让核对报出来，也不替它编一个操作者。
--
-- 代码侧同步修复：SubmissionService#approve 现在会登记 DOI 并写这条事件，
--   本迁移只处理修复之前已经产生的数据。

INSERT INTO aud_audit_event (
    id,
    actor_account_id,
    actor_username,
    action,
    target_type,
    target_id,
    before_value,
    after_value,
    reason,
    occurred_at,
    trace_id
)
SELECT
    'aud_' || substr(md5(random()::text || a.id), 1, 24),
    e.actor_account_id,
    e.actor_username,
    'ACHIEVEMENT_PUBLISH_CONFIRMED',
    'ACHIEVEMENT',
    a.id,
    NULL,
    jsonb_build_object('effectiveStatus', 'EFFECTIVE', 'visibility', 'PUBLIC'),
    coalesce(nullif(s.decision_reason, ''), '成果自助提交审核通过，人工确认公开')
        || '（依据 SUBMISSION_APPROVED 审计回填）',
    coalesce(s.decided_at, e.occurred_at),
    e.trace_id
FROM core_achievement a
JOIN core_submission s
    ON s.achievement_id = a.id
   AND s.status = 'APPROVED'
JOIN LATERAL (
    SELECT x.actor_account_id, x.actor_username, x.trace_id, x.occurred_at
    FROM aud_audit_event x
    WHERE x.action = 'SUBMISSION_APPROVED'
      AND x.target_type = 'SUBMISSION'
      AND x.target_id = s.id
    ORDER BY x.occurred_at DESC
    LIMIT 1
) e ON true
WHERE a.visibility = 'PUBLIC'
  AND a.effective_status = 'EFFECTIVE'
  AND a.merged_into_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM core_identifier_registry r WHERE r.entity_id = a.id)
  AND NOT EXISTS (
      SELECT 1 FROM aud_audit_event c
      WHERE c.target_id = a.id
        AND c.action = 'ACHIEVEMENT_PUBLISH_CONFIRMED'
  );
