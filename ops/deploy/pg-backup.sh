#!/usr/bin/env bash
#
# 服务器侧数据库备份（三个库一起，带清单与保留策略）。
#
# 与本地 ops/backup/pg-backup.sh 的区别只有连接方式：
#   本地脚本走 docker 或带口令的 host 模式；服务器上 PG 是原生实例、应用用对等认证，
#   所以这里统一用 sudo -u postgres，脚本里不出现任何口令。
# 清单格式与本地保持一致（time/mode/done/rows 四类行），恢复后核对同一套字段。
#
# 为什么 dump 先写 /tmp 再 mv：
#   postgres 用户进不去 /home/ubuntu（家目录 750），直接往家目录里重定向会 Permission denied。
#   注意重定向由调用方 shell（ubuntu）完成，所以 /tmp 下的文件属主是 ubuntu，
#   postgres 只往继承来的 fd 里写，不涉及目录遍历。
#
# 用法：
#   bash pg-backup.sh                    # 备份并清理过期
#   CN_BACKUP_MIN_KEEP=3 bash pg-backup.sh
#
# 退出码：0 成功；2 前置条件不满足；3 某个库 dump 失败；4 dump 读不回来（清单不写全）
set -uo pipefail

CN_USER="${SUDO_USER:-$(whoami)}"
CN_HOME="$(getent passwd "$CN_USER" | cut -d: -f6)/cn"
OUT_ROOT="${CN_BACKUP_DIR:-$CN_HOME/db-backup}"
RETENTION_DAYS="${CN_BACKUP_RETENTION_DAYS:-14}"
MIN_KEEP="${CN_BACKUP_MIN_KEEP:-7}"
STAMP="$(date +%Y%m%d-%H%M%S)"
DATABASES="core_db ingestion_db query_db"

log() { printf '\n==> %s\n' "$*"; }

command -v pg_dump >/dev/null 2>&1 || { echo "缺少 pg_dump" >&2; exit 2; }
pg() { sudo -u postgres "$@"; }

# 磁盘余量：单份备份约 160M，留 5G 余量再写，避免把盘写满连库都起不来。
free_mb="$(df -Pm "$(dirname "$OUT_ROOT")" | awk 'NR==2 {print $4}')"
if [ "${free_mb:-0}" -lt 5120 ]; then
  echo "磁盘剩余 ${free_mb}M，低于 5G，放弃本次备份" >&2
  exit 2
fi

OUT_DIR="$OUT_ROOT/$STAMP"
mkdir -p "$OUT_DIR" || exit 2
MANIFEST="$OUT_DIR/manifest.txt"
{
  echo "# CognitaNexus 备份清单"
  echo "time=$STAMP"
  echo "mode=peer"
  echo "host=$(hostname 2>/dev/null || echo unknown)"
  echo "databases=$DATABASES"
} >"$MANIFEST"

# 关键表：恢复后必须逐项核对行数。只核对「表存在」没有意义——迁移会建表，
# 真正要证明的是数据回来了。
CORE_TABLES="core_achievement core_scholar core_contribution core_achievement_source iam_account"
INGESTION_TABLES="ing_collect_job ing_staged_record ing_source_run_config"
QUERY_TABLES="qry_achievement qry_contribution qry_achievement_topic qry_coauthor_edge"

log "备份到 $OUT_DIR（保留 ${RETENTION_DAYS} 天，至少留 ${MIN_KEEP} 份）"

for db in $DATABASES; do
  tmp="$(mktemp "/tmp/cn-backup-${db}-XXXXXX.dump")" || exit 3
  chmod 644 "$tmp"
  # -Fc：支持按表恢复与并行；-Z6 压缩。纯 SQL 只能「全有或全无」，排查时没法只恢复一张表。
  if ! pg pg_dump -d "$db" -Fc -Z6 >"$tmp"; then
    echo "备份 $db 失败" >&2
    rm -f "$tmp"
    exit 3
  fi
  # 立刻证明这份 dump 能被读：pg_restore -l 列出归档目录，条目数为 0 说明文件是空的或坏的。
  entries="$(pg_restore -l "$tmp" 2>/dev/null | grep -c '^[0-9]' || true)"
  if [ "${entries:-0}" -lt 1 ]; then
    echo "$db 的 dump 读不出归档目录（entries=${entries:-0}）" >&2
    rm -f "$tmp"
    exit 4
  fi
  mv -f "$tmp" "$OUT_DIR/${db}.dump"
  chmod 600 "$OUT_DIR/${db}.dump"
  size="$(wc -c <"$OUT_DIR/${db}.dump" | tr -d ' ')"
  sum="$(sha256sum "$OUT_DIR/${db}.dump" | cut -d' ' -f1)"
  echo "done ${db} size=${size} sha256=${sum} entries=${entries}" >>"$MANIFEST"
  printf '   %-13s %8s bytes  %s 个归档条目\n' "$db" "$size" "$entries"

  case "$db" in
    core_db) tables="$CORE_TABLES" ;;
    ingestion_db) tables="$INGESTION_TABLES" ;;
    query_db) tables="$QUERY_TABLES" ;;
    *) tables="" ;;
  esac
  for table in $tables; do
    count="$(pg psql -d "$db" -X -A -t -c "SELECT count(*) FROM ${table}" 2>/dev/null | tr -d '[:space:]')"
    echo "rows ${db}.${table}=${count:-NA}" >>"$MANIFEST"
  done
done

log "清理过期备份"
# 先按时间排序，最新的 MIN_KEEP 份无条件保留；更老的只有超过 RETENTION_DAYS 才删。
mapfile -t dirs < <(find "$OUT_ROOT" -maxdepth 1 -mindepth 1 -type d -printf '%T@ %p\n' | sort -n | cut -d' ' -f2-)
total="${#dirs[@]}"
keep_from=$((total - MIN_KEEP))
removed=0
for i in "${!dirs[@]}"; do
  [ "$i" -lt "$keep_from" ] || continue
  d="${dirs[$i]}"
  if [ -n "$(find "$d" -maxdepth 0 -mtime "+$RETENTION_DAYS" 2>/dev/null)" ]; then
    rm -rf "$d"
    removed=$((removed + 1))
  fi
done
echo "   现有 $total 份，本次删除 $removed 份"
du -sh "$OUT_ROOT" 2>/dev/null | sed 's/^/   合计 /'

echo
echo "备份完成：$OUT_DIR"
echo "清单：$MANIFEST"
