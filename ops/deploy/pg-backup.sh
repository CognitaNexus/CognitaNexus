#!/usr/bin/env bash
#
# 服务器侧数据库备份（三个库一起，带清单与保留策略）。
#
# 两种运行身份，脚本自动选连接方式：
#   1. root（systemd 单元 cn-db-backup.service）→ runuser -u postgres，走本地 socket 对等认证
#   2. 普通账号（部署账号自己的 crontab）→ TCP 到 127.0.0.1，用该库属主账号 + 口令
#
# 为什么要支持非 root：新机的部署账号 buaa 在 sudoers 里但口令是锁死的
# （passwd -S 显示 L），systemd 里没有终端也输不了口令，所以「以 root 跑」这件事本身
# 就要先有 root。走 TCP + 属主口令可以在没有任何 root 的前提下把备份做起来，
# 口令从 ~/cn/conf/cn-env.sh 读，脚本与 crontab 里都不出现明文。
#
# 清单格式与本地保持一致（time/mode/done/rows 四类行），恢复后核对同一套字段。
#
# 用法：
#   bash pg-backup.sh                    # 备份并清理过期
#   CN_BACKUP_MIN_KEEP=3 bash pg-backup.sh
#
# 退出码：0 成功；2 前置条件不满足；3 某个库 dump 失败；4 dump 读不回来（清单不写全）
set -uo pipefail

# 产物归谁：systemd 单元以 root 跑时 whoami 是 root，属主必须由单元显式传进来。
# 普通账号直接跑时用 whoami，结果就是自己。
CN_USER="${CN_BACKUP_OWNER:-${SUDO_USER:-$(whoami)}}"
if [ "$(id -u)" -eq 0 ] && [ "$CN_USER" = "root" ]; then
  echo "不要以 root 直接运行：无法推断产物属主，请加 CN_BACKUP_OWNER=<部署账号>" >&2
  exit 2
fi
CN_HOME="$(getent passwd "$CN_USER" | cut -d: -f6)/cn"
OUT_ROOT="${CN_BACKUP_DIR:-$CN_HOME/db-backup}"
RETENTION_DAYS="${CN_BACKUP_RETENTION_DAYS:-14}"
MIN_KEEP="${CN_BACKUP_MIN_KEEP:-7}"
STAMP="$(date +%Y%m%d-%H%M%S)"
DATABASES="core_db ingestion_db query_db"
CN_ENV_FILE="${CN_ENV_FILE:-$CN_HOME/conf/cn-env.sh}"
DB_HOST="${CN_DB_HOST:-127.0.0.1}"
DB_PORT="${CN_DB_PORT:-5432}"

log() { printf '\n==> %s\n' "$*"; }

command -v pg_dump >/dev/null 2>&1 || { echo "缺少 pg_dump" >&2; exit 2; }

# 同一时刻只允许一份在跑：cron 与 systemd timer 万一同时被装上，靠这个兜住，
# 而不是靠人记得去删其中一处。
LOCK="${CN_BACKUP_LOCK:-$OUT_ROOT/.lock}"
mkdir -p "$OUT_ROOT" || exit 2
exec 9>"$LOCK"
if ! flock -n 9; then
  echo "已有一份备份在跑（$LOCK 被占用），本次跳过" >&2
  exit 0
fi

if [ "$(id -u)" -eq 0 ]; then
  MODE=peer
  command -v runuser >/dev/null 2>&1 || { echo "缺少 runuser" >&2; exit 2; }
else
  MODE=tcp
  [ -r "$CN_ENV_FILE" ] || { echo "读不到 $CN_ENV_FILE（TCP 模式需要属主口令）" >&2; exit 2; }
  # set -a 让文件里的变量自动导出；文件里只有 KEY=value，没有命令。
  set -a
  # shellcheck disable=SC1090
  . "$CN_ENV_FILE"
  set +a
fi

# 三个库各自的属主与口令变量名（与 application.yml 的 flyway 账号一致）。
db_owner() { case "$1" in core_db) echo core_owner ;; ingestion_db) echo ingestion_owner ;; query_db) echo query_owner ;; *) echo "" ;; esac; }
db_pwvar() { case "$1" in core_db) echo CN_CORE_DB_OWNER_PASSWORD ;; ingestion_db) echo CN_INGESTION_DB_OWNER_PASSWORD ;; query_db) echo CN_QUERY_DB_OWNER_PASSWORD ;; *) echo "" ;; esac; }

# run_as_db <库名> <程序> [参数...]
run_as_db() {
  local db="$1" prog="$2"
  shift 2
  if [ "$MODE" = "peer" ]; then
    runuser -u postgres -- "$prog" -d "$db" "$@"
    return
  fi
  local owner pwvar pw
  owner="$(db_owner "$db")"
  pwvar="$(db_pwvar "$db")"
  [ -n "$owner" ] || { echo "未知库 $db，没有对应属主账号" >&2; return 1; }
  pw="$(eval "printf '%s' \"\${$pwvar:-}\"")"
  [ -n "$pw" ] || { echo "$pwvar 为空，无法连接 $db（检查 $CN_ENV_FILE）" >&2; return 1; }
  PGPASSWORD="$pw" "$prog" -h "$DB_HOST" -p "$DB_PORT" -U "$owner" -d "$db" "$@"
}

# 只读归档目录，不需要连库。root 模式下 dump 属主是 postgres（重定向由 root 做，
# 文件其实是 root 的，这里仍走 runuser 以免将来权限收紧时失效）。
list_dump() {
  if [ "$MODE" = "peer" ]; then
    runuser -u postgres -- pg_restore -l "$1"
  else
    pg_restore -l "$1"
  fi
}

# 磁盘余量：单份备份约 220M，留 5G 余量再写，避免把盘写满连库都起不来。
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
  echo "mode=$MODE"
  echo "host=$(hostname 2>/dev/null || echo unknown)"
  echo "databases=$DATABASES"
} >"$MANIFEST"

# 关键表：恢复后必须逐项核对行数。只核对「表存在」没有意义——迁移会建表，
# 真正要证明的是数据回来了。
CORE_TABLES="core_achievement core_scholar core_contribution core_achievement_source iam_account"
INGESTION_TABLES="ing_collect_job ing_staged_record ing_source_run_config"
QUERY_TABLES="qry_achievement qry_contribution qry_achievement_topic qry_coauthor_edge"

log "备份到 $OUT_DIR（模式 $MODE，保留 ${RETENTION_DAYS} 天，至少留 ${MIN_KEEP} 份）"

for db in $DATABASES; do
  tmp="$(mktemp "/tmp/cn-backup-${db}-XXXXXX.dump")" || exit 3
  chmod 644 "$tmp"
  # -Fc：支持按表恢复与并行；-Z6 压缩。纯 SQL 只能「全有或全无」，排查时没法只恢复一张表。
  if ! run_as_db "$db" pg_dump -Fc -Z6 >"$tmp"; then
    echo "备份 $db 失败" >&2
    rm -f "$tmp"
    exit 3
  fi
  # 立刻证明这份 dump 能被读：pg_restore -l 列出归档目录，条目数为 0 说明文件是空的或坏的。
  entries="$(list_dump "$tmp" 2>/dev/null | grep -c '^[0-9]' || true)"
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
    count="$(run_as_db "$db" psql -X -A -t -c "SELECT count(*) FROM ${table}" 2>/dev/null | tr -d '[:space:]')"
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

# root 跑出来的目录与清单属主是 root，交还给部署账号：否则 buaa 连「删一份旧备份」都做不到，
# 出问题时只能再要一次 root。
if [ "$(id -u)" -eq 0 ]; then
  chown -R "$CN_USER:$CN_USER" "$OUT_DIR" 2>/dev/null || true
  chown "$CN_USER:$CN_USER" "$CN_HOME/logs/db-backup.log" 2>/dev/null || true
fi
