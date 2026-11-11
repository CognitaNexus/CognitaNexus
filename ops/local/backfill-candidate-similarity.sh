#!/usr/bin/env bash
# 回填去重候选的证据快照（相似度、标题、年份、可见性）
#
# 背景：早期写入候选时，evidence.similarity 填的是「过线阈值」而不是真实相似度。阈值只说明
# 「像到足以成为候选」，把它当相似度存下来，会让治理工作台的分档失效——7333 条候选里只剩
# 0.55 与 1.0 两个取值，「高/中/低」三档实际退化成两档，且 0.55 那一档无法判断谁更值得先看。
# 同一批数据里标题是按调用方传入方向写的、实体标识却按字典序排过，候选 A 与候选 B 的标题
# 可能互换。本脚本按左右实体的 title_norm 重算相似度，并把标题/年份/可见性按标识字典序重新绑定。
#
# 幂等：只更新「相似度与重算值不一致」或「证据缺可见性字段」的行，可重复执行。
# 判据必须覆盖每个被写入的字段，而不只是相似度：只比对相似度时，那些相似度本来就对
# （例如精确同标题的 1.0）的行会被整行跳过，于是永远补不上后加的可见性字段。
#
# 用法：bash ops/local/backfill-candidate-similarity.sh [--dry-run]

set -euo pipefail

PG_HOME="D:/dev-tools/pgsql"
PG_HOST=127.0.0.1
PG_PORT=5432
DB=core_db

DRY_RUN=0
if [ "${1:-}" = "--dry-run" ]; then
  DRY_RUN=1
fi

psql_app() {
  PGPASSWORD=cn_core_app_dev "$PG_HOME/bin/psql.exe" -h "$PG_HOST" -p "$PG_PORT" -U core_app -d "$DB" \
    -v ON_ERROR_STOP=1 -q -t -A -F'|' "$@"
}

buckets() {
  psql_app -c "
    SELECT count(*) AS total,
           count(*) FILTER (WHERE sim >= 0.9) AS high,
           count(*) FILTER (WHERE sim >= 0.7 AND sim < 0.9) AS mid,
           count(*) FILTER (WHERE sim < 0.7) AS low
    FROM (
      SELECT coalesce((evidence->>'similarity')::numeric, 0) AS sim
      FROM gov_dedup_candidate WHERE status = 'OPEN'
    ) t;"
}

echo "=== 去重候选证据回填 ==="
echo "回填前（总|高|中|低）："
buckets

if [ "$DRY_RUN" = "1" ]; then
  echo "预演模式：未写入。待更新行数："
  psql_app -c "
    SELECT count(*)
    FROM gov_dedup_candidate c
    JOIN core_achievement la ON la.id = c.left_achievement_id
    JOIN core_achievement ra ON ra.id = c.right_achievement_id
    WHERE c.status = 'OPEN'
      AND (abs(coalesce((c.evidence->>'similarity')::numeric, -1)
               - round(similarity(la.title_norm, ra.title_norm)::numeric, 4)) > 0.0001
           OR c.evidence->>'leftVisibility' IS NULL);"
  exit 0
fi

psql_app -c "
  UPDATE gov_dedup_candidate c
  SET evidence = c.evidence || jsonb_build_object(
        'similarity', round(similarity(la.title_norm, ra.title_norm)::numeric, 4),
        'leftTitle', la.title,
        'rightTitle', ra.title,
        'leftYear', la.publication_year,
        'rightYear', ra.publication_year,
        'leftVisibility', la.visibility,
        'rightVisibility', ra.visibility,
        'leftEffectiveStatus', la.effective_status,
        'rightEffectiveStatus', ra.effective_status)
  FROM core_achievement la, core_achievement ra
  WHERE la.id = c.left_achievement_id
    AND ra.id = c.right_achievement_id
    AND c.status = 'OPEN'
    AND (abs(coalesce((c.evidence->>'similarity')::numeric, -1)
             - round(similarity(la.title_norm, ra.title_norm)::numeric, 4)) > 0.0001
         OR c.evidence->>'leftVisibility' IS NULL);"

echo "回填后（总|高|中|低）："
buckets
echo "=== 完成 ==="
