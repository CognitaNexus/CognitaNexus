#!/usr/bin/env bash
# 从备份恢复并核对（NF04 的第二半：备份必须被证明能恢复，否则只是一堆文件）。
#
# 依据：NF04「恢复操作前后去重实体数/来源可核对，失败不删除历史」。
#
# 三条安全默认，都是「宁可多做一步，也不要在生产上试错」：
#   1. 默认**不覆盖现有库**：恢复到带后缀的新库（默认 _restore_<时间戳>），核对通过后由人决定是否切换。
#      这样即使 dump 是坏的，也不会把正在服务的库删掉；
#   2. 恢复到新库后**逐表核对行数**，与备份清单不一致就明确失败（而不是打印「恢复完成」）；
#   3. 覆盖式恢复必须同时给出 --in-place 与 --yes-i-know，少一个就直接拒绝。
#
# 用法：
#   bash ops/restore/pg-restore.sh --dir backups/20261018-010203
#   bash ops/restore/pg-restore.sh --dir ... --suffix _drill
#   bash ops/restore/pg-restore.sh --dir ... --in-place --yes-i-know   # 危险
#
# 退出码：0 成功且核对通过；2 前置条件不满足；4 核对不一致。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1

DIR=""
SUFFIX="_restore_$(date +%H%M%S)"
IN_PLACE=0
CONFIRM=0
MODE="${CN_BACKUP_MODE:-docker}"

while [ $# -gt 0 ]; do
  case "$1" in
    --dir)
      DIR="$2"
      shift 2
      ;;
    --suffix)
      SUFFIX="$2"
      shift 2
      ;;
    --mode)
      MODE="$2"
      shift 2
      ;;
    --in-place)
      IN_PLACE=1
      shift
      ;;
    --yes-i-know)
      CONFIRM=1
      shift
      ;;
    *)
      echo "未知参数：$1" >&2
      exit 2
      ;;
  esac
done

if [ -z "$DIR" ] || [ ! -f "${DIR}/manifest.txt" ]; then
  echo "必须给出包含 manifest.txt 的备份目录：--dir <dir>" >&2
  exit 2
fi
if [ "$IN_PLACE" = "1" ] && [ "$CONFIRM" != "1" ]; then
  echo "覆盖式恢复会删除现有数据库。确认请同时给出 --in-place 与 --yes-i-know" >&2
  exit 2
fi

COMPOSE_SERVICE="${CN_BACKUP_COMPOSE_SERVICE:-postgres}"
SUPERUSER="${CN_PG_DUMP_USER:-postgres}"

case "$MODE" in
  docker)
    run_pg() { docker compose exec -T "$COMPOSE_SERVICE" "$@"; }
    copy_in() { docker compose cp "$1" "${COMPOSE_SERVICE}:$2" >/dev/null; }
    ;;
  host)
    export PGHOST="${PGHOST:-127.0.0.1}"
    export PGPORT="${PGPORT:-5432}"
    export PGUSER="${PGUSER:-postgres}"
    export PGPASSWORD="${PGPASSWORD:?宿主模式必须给出 PGPASSWORD}"
    run_pg() { "$@"; }
    copy_in() { cp "$1" "$2"; }
    ;;
  *)
    echo "未知模式：$MODE" >&2
    exit 2
    ;;
esac

echo "== 从 ${DIR} 恢复（模式 ${MODE}，$( [ "$IN_PLACE" = "1" ] && echo '覆盖现有库' || echo "恢复到新库后缀 ${SUFFIX}" )）"

FAILED=0
for db in core_db ingestion_db query_db; do
  dump="${DIR}/${db}.dump"
  if [ ! -f "$dump" ]; then
    echo "缺少 ${dump}" >&2
    FAILED=1
    continue
  fi

  target="$db"
  if [ "$IN_PLACE" = "0" ]; then
    target="${db}${SUFFIX}"
  fi

  # 恢复到容器内路径再 pg_restore：直接把宿主机文件喂给 docker exec 的 stdin 在 Windows
  # 上会因为 CRLF 与管道缓冲出问题，先 cp 进去最稳。
  run_pg dropdb -U "$SUPERUSER" --if-exists "$target" >/dev/null 2>&1 || true
  if ! run_pg createdb -U "$SUPERUSER" -O "${db%_db}_owner" "$target" 2>/dev/null; then
    # owner 角色可能叫别的名字（或本次演练用的是超级用户），退回默认属主而不是中断：
    # 恢复到临时库的目的只是核对数据，属主不是核对项。
    run_pg createdb -U "$SUPERUSER" "$target" || {
      echo "创建 ${target} 失败" >&2
      FAILED=1
      continue
    }
  fi

  copy_in "$dump" "/tmp/${db}.dump"
  if ! run_pg pg_restore -U "$SUPERUSER" -d "$target" --no-owner --no-privileges --exit-on-error \
    "/tmp/${db}.dump" >/dev/null 2>&1; then
    echo "pg_restore ${db} 失败（dump 损坏或与当前 schema 不兼容）" >&2
    FAILED=1
    continue
  fi
  run_pg rm -f "/tmp/${db}.dump" >/dev/null 2>&1 || true

  # 逐表核对：行数必须与备份清单一致。不一致说明 dump 不完整或恢复到错的库。
  echo "-- 核对 ${target}"
  while read -r line; do
    case "$line" in
      "rows ${db}."*)
        key="${line#rows }"
        table="${key%%=*}"
        expected="${line##*=}"
        actual="$(run_pg psql -U "$SUPERUSER" -d "$target" -X -A -t \
          -c "SELECT count(*) FROM ${table}" 2>/dev/null | tr -d '[:space:]')"
        if [ "$expected" = "NA" ] || [ -z "$expected" ]; then
          echo "   ${table}: 备份时未取到行数，恢复后 ${actual:-NA}（跳过核对）"
        elif [ "$expected" != "$actual" ]; then
          echo "   ${table}: 期望 ${expected}，实际 ${actual:-NA} —— 不一致" >&2
          FAILED=1
        else
          echo "   ${table}: ${actual}"
        fi
        ;;
    esac
  done <"${DIR}/manifest.txt"
done

if [ "$FAILED" != "0" ]; then
  echo "== 恢复或核对失败。原库未改动（除非你用了 --in-place）。" >&2
  exit 4
fi

echo "== 恢复完成且核对通过"
if [ "$IN_PLACE" = "0" ]; then
  cat <<TXT
   核对用的库带有后缀 ${SUFFIX}。确认无误后有两种收尾方式：
     1. 直接切换连接串到这些库（推荐在维护窗口做）；
     2. 或再次执行 --in-place --yes-i-know 做原地恢复（会删掉现有库）。
   演练结束后清理：docker compose exec postgres dropdb -U ${SUPERUSER} <库名>
TXT
fi
