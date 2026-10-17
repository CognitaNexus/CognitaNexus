#!/usr/bin/env bash
# 回溯裁决「已合并对」上遗留的待治理候选
#
# 背景：合并成果时，候选提出的问题（「这两条是不是同一条」）已经被合并本身回答了。现在
# GovernanceService.mergeAchievements 会在合并的同一步里把这一对之间的 OPEN 候选裁决为
# MERGED，但本规则生效之前执行的那些合并没有这一步，于是遗留下一批候选：它们指向的两条
# 成果早已并成一条，任何一次合并预览都会把它们判为「当前不可合并」。
#
# 这不是「处理不掉」而是「处理错了位置」：候选按相似度降序排在待治理列表最前面（同标题的
# 相似度就是 1.0），于是工作台打开第一屏全是这些死条目，真正需要判断的候选被压在下面。
#
# 判据刻意收得很窄，只匹配「候选的两端恰好就是某条 MERGE 台账的 survivor 与 merged」：
# 只把「合并已经回答了这个问题」的那一批裁决掉。被合并方与第三方的候选不在范围内——那些
# 问题合并并没有回答，必须留给人工逐条判断改判还是驳回。
#
# 幂等：只更新 status = 'OPEN' 的行，可重复执行；重复执行第二次影响 0 行。
#
# 用法：
#   bash ops/local/backfill-merged-pair-candidates.sh            # 预演，只列不写
#   bash ops/local/backfill-merged-pair-candidates.sh --apply    # 实际裁决

set -euo pipefail

PG_HOME="D:/dev-tools/pgsql"
PG_HOST=127.0.0.1
PG_PORT=5432
DB=core_db
RESOLVED_BY="backfill:merged-pair"
REASON="回溯裁决：这一对成果在合并时未一并处置候选（规则生效前），合并已经回答了候选提出的问题。"

APPLY=0
if [ "${1:-}" = "--apply" ]; then
  APPLY=1
fi

psql_app() {
  PGPASSWORD=cn_core_app_dev PGCLIENTENCODING=UTF8 "$PG_HOME/bin/psql.exe" \
    -h "$PG_HOST" -p "$PG_PORT" -U core_app -d "$DB" \
    -v ON_ERROR_STOP=1 -q -t -A -F'|' "$@"
}

# 含中文的 SQL 一律走 stdin，不能用 -c。
#
# Windows 上 psql.exe 的 argv 由系统按 ANSI 码页（本机 CP936）转出来，UTF-8 编码的中文在
# 这一步就被破坏成非法字节，服务端报「invalid byte sequence for encoding "UTF8"」；同一个
# 字符串走 stdin 则原样透传。本脚本文件本身是 UTF-8，因此这里的 heredoc 展开后是正确的字节流。
psql_stdin() {
  PGPASSWORD=cn_core_app_dev PGCLIENTENCODING=UTF8 "$PG_HOME/bin/psql.exe" \
    -h "$PG_HOST" -p "$PG_PORT" -U core_app -d "$DB" \
    -v ON_ERROR_STOP=1 -q -t -A -F'|' -f -
}

open_total() {
  psql_app -c "SELECT count(*) FROM gov_dedup_candidate WHERE status = 'OPEN';"
}

stale_total() {
  psql_app -c "
    SELECT count(*)
    FROM gov_dedup_candidate c
    WHERE c.status = 'OPEN'
      AND EXISTS (
        SELECT 1 FROM gov_merge_ledger l
        WHERE l.operation = 'MERGE'
          AND l.entity_type = 'ACHIEVEMENT'
          AND ((l.survivor_id = c.left_achievement_id AND l.merged_id = c.right_achievement_id)
            OR (l.survivor_id = c.right_achievement_id AND l.merged_id = c.left_achievement_id)));"
}

echo "=== 已合并对遗留候选的回溯裁决 ==="
echo "待治理候选（OPEN）总数：$(open_total)"
echo "其中属于「候选两端就是某条合并台账」的：$(stale_total)"
echo

echo "样例（最多 10 条）："
psql_app -c "
  SELECT c.id, c.left_achievement_id, c.right_achievement_id,
         round(coalesce((c.evidence->>'similarity')::numeric, 0), 3)
  FROM gov_dedup_candidate c
  WHERE c.status = 'OPEN'
    AND EXISTS (
      SELECT 1 FROM gov_merge_ledger l
      WHERE l.operation = 'MERGE'
        AND l.entity_type = 'ACHIEVEMENT'
        AND ((l.survivor_id = c.left_achievement_id AND l.merged_id = c.right_achievement_id)
          OR (l.survivor_id = c.right_achievement_id AND l.merged_id = c.left_achievement_id)))
  ORDER BY coalesce((c.evidence->>'similarity')::numeric, 0) DESC
  LIMIT 10;"

if [ "$APPLY" != "1" ]; then
  echo
  echo "预演模式：未写入。加 --apply 才会实际裁决。"
  exit 0
fi

echo
echo "开始裁决…"
# 用 RETURNING 把实际影响行数打出来：只说「执行完成」而不说改了几行，无法判断这次运行到底
# 是修好了还是空跑（例如判据写错、台账表名写错时都会静默 0 行）。
resolved=$(psql_stdin <<SQL
  WITH updated AS (
    UPDATE gov_dedup_candidate c
    SET status = 'MERGED',
        resolved_at = now(),
        resolved_by = '${RESOLVED_BY}',
        resolution_reason = '${REASON}'
    WHERE c.status = 'OPEN'
      AND EXISTS (
        SELECT 1 FROM gov_merge_ledger l
        WHERE l.operation = 'MERGE'
          AND l.entity_type = 'ACHIEVEMENT'
          AND ((l.survivor_id = c.left_achievement_id AND l.merged_id = c.right_achievement_id)
            OR (l.survivor_id = c.right_achievement_id AND l.merged_id = c.left_achievement_id)))
    RETURNING 1)
  SELECT count(*) FROM updated;
SQL
)
echo "本次裁决：${resolved:-0} 条"

echo "裁决后待治理候选（OPEN）总数：$(open_total)"
echo "剩余属于已合并对的：$(stale_total)"
echo "=== 完成 ==="
