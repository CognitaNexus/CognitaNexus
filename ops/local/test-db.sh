#!/usr/bin/env bash
# =============================================================================
# CognitaNexus 集成测试专用 PostgreSQL 实例
# =============================================================================
#
# 为什么不用 Testcontainers：
#   本机 Docker Desktop 依赖 WSL2，而 WSL 被安全策略拦截（见 start-infra.sh 头注释），
#   守护进程起不来。用容器做集成测试在本机不可执行，若把测试写成「Docker 在才跑」，
#   实际效果是这一层永远不会被执行，等于没有。
#
# 本脚本用同一套 PG 16 二进制另起一个**独立实例**：
#   - 端口 55432，与共享开发实例 5432 完全分开，互不影响
#   - 数据目录 D:/dev-tools/pgdata-it，与共享实例的 pgdata 分开
#   - 初始化时为 localhost 免密（trust），仅监听 127.0.0.1，只放测试数据
#   - 测试库为 core_it / ingestion_it / query_it，**不碰** core_db / ingestion_db / query_db
#
# 这样得到的证据与容器等价：真实 PostgreSQL 16、真实迁移脚本、真实 SQL、真实事务隔离；
# 差别只在「实例由本脚本管理」而不是「由容器运行时管理」——报告里按此如实描述，不写成
# 「已使用 Testcontainers」。
#
# 用法：
#   bash ops/local/test-db.sh start     # 初始化（首次）并启动，随后建角色与测试库
#   bash ops/local/test-db.sh stop      # 停止实例（保留数据目录，下次启动更快）
#   bash ops/local/test-db.sh status    # 查看状态与端口
#   bash ops/local/test-db.sh reset     # 删掉数据目录并重建（迁移脚本改动后用）
#   bash ops/local/test-db.sh psql      # 连 core_it 交互排查
# =============================================================================

set -euo pipefail

PG_HOME="D:/dev-tools/pgsql"
# 数据目录放在仓库内而非 D:/dev-tools：一是随项目一起清理，二是沙箱对仓库外的新建目录
# 会拦截（initdb 会被静默中断，只剩半个数据目录，pg_ctl 启动时报「不是有效数据目录」）。
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IT_DATA="$REPO_ROOT/ops/local/run/it-pg/data"
IT_PORT=55432
IT_LOG="$REPO_ROOT/ops/local/run/it-pg/server.log"

CORE_IT="core_it"
INGESTION_IT="ingestion_it"
QUERY_IT="query_it"

# 迁移脚本里的迁移账号与运行账号（V6/V2 的权限自举会按名字查找，缺角色则跳过授权，
# 于是测试跑的是「超级用户全权」而不是真实权限边界。这里必须建出来。）
ROLES="core_owner core_app ingestion_owner ingestion_app query_owner query_app"

psql_super() {
  "$PG_HOME/bin/psql.exe" -h 127.0.0.1 -p "$IT_PORT" -U postgres -d postgres \
    -v ON_ERROR_STOP=1 -At "$@"
}

is_running() {
  "$PG_HOME/bin/pg_ctl.exe" -D "$IT_DATA" status >/dev/null 2>&1
}

init_cluster() {
  if [ -d "$IT_DATA" ] && [ -f "$IT_DATA/PG_VERSION" ]; then
    echo "  数据目录已存在，跳过 initdb"
    return
  fi
  mkdir -p "$IT_DATA"
  # trust 认证仅用于本地临时实例：只监听 127.0.0.1、无真实数据、测试结束可整体删除。
  "$PG_HOME/bin/initdb.exe" -D "$IT_DATA" -U postgres --auth=trust \
    --encoding=UTF8 --locale=C >/dev/null 2>&1 < /dev/null
  echo "  initdb 完成"
}

start_instance() {
  init_cluster
  if is_running; then
    echo "  实例已在运行"
    return
  fi
  # 三个重定向缺一不可：pg_ctl 拉起 postgres 后，若该子进程仍持有调用方的 stdout 句柄，
  # 任何 `... | tail` 之类的管道都不会收到 EOF，调用方会一直挂着（本节实测过）。
  "$PG_HOME/bin/pg_ctl.exe" -D "$IT_DATA" -l "$IT_LOG" \
    -o "-p $IT_PORT -h 127.0.0.1" -w start >/dev/null 2>&1 < /dev/null
  echo "  已启动 (端口 $IT_PORT)"
}

ensure_roles() {
  # 幂等：角色存在则跳过。用 DO 块避免 CREATE ROLE IF NOT EXISTS 的语法缺失。
  psql_super -c "DO \$\$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'core_owner') THEN CREATE ROLE core_owner LOGIN; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'core_app') THEN CREATE ROLE core_app LOGIN; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ingestion_owner') THEN CREATE ROLE ingestion_owner LOGIN; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ingestion_app') THEN CREATE ROLE ingestion_app LOGIN; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'query_owner') THEN CREATE ROLE query_owner LOGIN; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'query_app') THEN CREATE ROLE query_app LOGIN; END IF;
  END \$\$;" >/dev/null
}

ensure_database() {
  local db="$1" owner="$2"
  local exists
  exists=$(psql_super -c "SELECT 1 FROM pg_database WHERE datname = '$db'")
  if [ "$exists" != "1" ]; then
    psql_super -c "CREATE DATABASE $db OWNER $owner"
    echo "  已建库 $db (owner $owner)"
  fi
}

case "${1:-start}" in
  start)
    echo "=== 集成测试实例 (端口 $IT_PORT) ==="
    start_instance
    ensure_roles
    ensure_database "$CORE_IT" core_owner
    ensure_database "$INGESTION_IT" ingestion_owner
    ensure_database "$QUERY_IT" query_owner
    echo "=== 连通性自检 ==="
    for db in "$CORE_IT" "$INGESTION_IT" "$QUERY_IT"; do
      "$PG_HOME/bin/psql.exe" -h 127.0.0.1 -p "$IT_PORT" -U postgres -d "$db" -Atc "select '$db OK'"
    done
    echo "=== 就绪：jdbc:postgresql://127.0.0.1:$IT_PORT/<db> ==="
    ;;
  stop)
    if is_running; then
      "$PG_HOME/bin/pg_ctl.exe" -D "$IT_DATA" -m fast -w stop >/dev/null
      echo "已停止"
    else
      echo "未在运行"
    fi
    ;;
  status)
    if is_running; then
      echo "集成测试实例运行中 (端口 $IT_PORT, 数据目录 $IT_DATA)"
      "$PG_HOME/bin/psql.exe" -h 127.0.0.1 -p "$IT_PORT" -U postgres -d postgres -Atc \
        "select string_agg(datname, ', ') from pg_database where datname like '%_it'"
    else
      echo "未运行"
      exit 1
    fi
    ;;
  reset)
    echo "=== 重建测试实例（会删除 $IT_DATA）==="
    if is_running; then
      "$PG_HOME/bin/pg_ctl.exe" -D "$IT_DATA" -m immediate -w stop >/dev/null
    fi
    rm -rf "$IT_DATA"
    bash "$0" start
    ;;
  psql)
    exec "$PG_HOME/bin/psql.exe" -h 127.0.0.1 -p "$IT_PORT" -U postgres -d "$CORE_IT"
    ;;
  run)
    # 前台承载模式：postgres 直接前台运行，由调用方（后台任务）负责生命周期。
    # 沙箱里必须用这个模式：pg_ctl 会 daemon 化，承载调用一结束整组被回收，
    # 日志里能看到「ready to accept connections」然后 backend 以 0xC0000142 失败。
    init_cluster
    exec "$PG_HOME/bin/postgres.exe" -D "$IT_DATA" -p "$IT_PORT" -h 127.0.0.1
    ;;
  *)
    echo "用法：bash ops/local/test-db.sh [start|stop|status|reset|psql|run]" >&2
    exit 2
    ;;
esac
