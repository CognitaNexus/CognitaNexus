#!/usr/bin/env bash
# CognitaNexus 本地整栈启停（原生二进制模式，适配受管沙箱）
#
# 为什么需要它：
#   沙箱是「一次调用一个进程组」——前台调用一返回，整组即被回收（见 service.sh 文件头）。
#   因此本地栈必须由一个长期存活的调用承载。此前 PostgreSQL、两个 Redis、三个服务、
#   前端 dev server 是分别用临时命令拼起来的，结果：
#     1) 任务面板里散成七条，看不出它们是一套东西；
#     2) 谁都不记录组成，重启时要靠回忆复原端口、账号、日志路径；
#     3) 说不清停掉哪一条会牵连什么（例如停掉那条「看不出在跑什么」的空壳，其实是数据库）。
#   本脚本把整个本地栈收进同一个进程组并前台阻塞：
#     - 以后台任务方式运行它，本地栈就是面板上的一条受管任务；
#     - 结束它（或 Ctrl-C）即停整栈，先优雅停子进程再退出，不留半个进程。
#
# 与 service.sh / start-infra.sh 的分工：
#   那两个是细粒度脚本（起单个服务、只起基础设施），适合普通终端逐项操作；
#   本脚本是粗粒度的「整栈」，专为沙箱环境与本地演示准备，是推荐的唯一入口。
#
# 用法：
#   bash ops/local/stack.sh                 # 启动整栈并阻塞（前台持有，直到被停止）
#   bash ops/local/stack.sh --no-web        # 不启动前端 dev server
#   bash ops/local/stack.sh stop            # 停止整栈（含基础设施，任一调用都可用）
#   bash ops/local/stack.sh status          # 只读查看整栈状态
#
# 日志：ops/local/run/{core,ingestion,query}.log 与 run/{postgres,redis-*,web,stack}.log

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_DIR="$ROOT/ops/local/run"

PG_HOME="D:/dev-tools/pgsql"
PG_DATA="D:/dev-tools/pgdata"
PG_PORT=5432
REDIS_HOME="D:/dev-tools/redis"
NODE_BIN_DIR="/c/Users/白皓宇/.workbuddy/binaries/node/versions/22.22.2-6"

# 整栈端口清单：用于就绪探测与看门狗
ALL_PORTS="5432 6379 6380 8081 8082 8083 5173"

# 服务名 → 端口 / jar
port_of() {
  case "$1" in
    core) echo 8081 ;;
    ingestion) echo 8082 ;;
    query) echo 8083 ;;
    *) echo "" ;;
  esac
}
jar_of() {
  case "$1" in
    core) echo "$ROOT/services/core-service/target/core-service-0.2.0.jar" ;;
    ingestion) echo "$ROOT/services/ingestion-service/target/ingestion-service-0.2.0.jar" ;;
    query) echo "$ROOT/services/query-service/target/query-service-0.2.0.jar" ;;
    *) echo "" ;;
  esac
}

port_up() {
  netstat -ano 2>/dev/null | grep -q ":$1 .*LISTENING"
}
port_pid() {
  netstat -ano 2>/dev/null | awk -v p=":$1" '$0 ~ p && $0 ~ /LISTENING/ {print $NF}' | head -1
}

# 等待一组端口全部监听，最多 tries 秒
wait_ports() {
  local tries="$1"; shift
  local p n=0
  while [ "$n" -lt "$tries" ]; do
    local ok=1
    for p in "$@"; do port_up "$p" || ok=0; done
    [ "$ok" = "1" ] && return 0
    sleep 1
    n=$((n + 1))
  done
  return 1
}

# PostgreSQL 是否已能接受连接（而非仅端口在监听）。
#
# 必需单独判断：PostgreSQL 在做崩溃恢复时会先打开监听端口，此时任何连接都被回以
# 「FATAL: the database system is starting up」。若只用 wait_ports 判就绪，服务会
# 抢在恢复完成前启动，Flyway 拿不到连接、整个应用启动失败——本脚本第一版就踩了这个坑
# （run/core.log 里那条 flywayInitializer 的 FATAL 就是它）。恢复耗时随 WAL 量变化，
# 没有固定的安全等待秒数，只能按「真能连上」判。
pg_ready() {
  "$PG_HOME/bin/pg_isready.exe" -h 127.0.0.1 -p "$PG_PORT" >/dev/null 2>&1
}

wait_pg_ready() {
  local tries="$1" n=0
  while [ "$n" -lt "$tries" ]; do
    pg_ready && return 0
    sleep 1
    n=$((n + 1))
  done
  return 1
}

# 说明：停止一律按端口定位（见 stop_all）。不靠记录子进程 PID，是因为服务可能先于
# 本脚本存在（例如只重启应用层），按端口找保证「不管谁起的，都停得掉」。

start_infra() {
  echo "=== 基础设施 ==="
  if port_up "$PG_PORT"; then
    echo "  PostgreSQL $PG_PORT 已在运行（PID $(port_pid $PG_PORT)），跳过"
  else
    # 直接以子进程方式运行 postgres，不做 daemon 化：这样它属于本进程组，
    # 整栈停止时一起退出，不会留下没人管的数据库进程。
    "$PG_HOME/bin/postgres.exe" -D "$PG_DATA" -p "$PG_PORT" >> "$RUN_DIR/postgres.log" 2>&1 &
    echo "  PostgreSQL 启动中（PID $!），日志 run/postgres.log"
  fi

  for spec in "6379:redis-session.conf:redis-session" "6380:redis-cache.conf:redis-cache"; do
    local port="${spec%%:*}"
    local rest="${spec#*:}"
    local conf="${rest%%:*}"
    local log="${rest##*:}"
    if port_up "$port"; then
      echo "  Redis $port 已在运行（PID $(port_pid $port)），跳过"
    else
      ( cd "$REDIS_HOME" && ./redis-server.exe "$conf" >> "$RUN_DIR/$log.log" 2>&1 ) &
      echo "  Redis $port 启动中（PID $!），日志 run/$log.log"
    fi
  done

  # Redis 判端口即可（REDIS 一监听就能服务）；PostgreSQL 必须另判「已可接受连接」。
  if wait_ports 30 6379 6380 && wait_pg_ready 60; then
    echo "  基础设施就绪（PostgreSQL 已接受连接）"
  else
    echo "  基础设施未在 90 秒内就绪，见 run/postgres.log 与 run/redis-*.log"
    return 1
  fi
}

start_services() {
  echo "=== 服务 ==="
  local s port jar log
  for s in core ingestion query; do
    port="$(port_of "$s")"
    jar="$(jar_of "$s")"
    log="$RUN_DIR/$s.log"
    if port_up "$port"; then
      echo "  $s 已在端口 $port 运行（PID $(port_pid "$port")），跳过"
      continue
    fi
    if [ ! -f "$jar" ]; then
      echo "  $s 未构建：缺少 $jar"
      return 1
    fi
    # 显式覆盖 server.port / server.address：宿主会注入 SERVER__PORT，
    # Spring Boot 的宽松绑定会把它当成 server.port（理由见 service.sh 文件头）。
    java -Dfile.encoding=UTF-8 -jar "$jar" \
      --server.port="$port" --server.address=127.0.0.1 >> "$log" 2>&1 &
    echo "  $s 启动中（PID $!），端口 $port，日志 run/$s.log"
  done

  if wait_ports 90 8081 8082 8083; then
    echo "  三个服务就绪"
  else
    echo "  服务未在 90 秒内就绪，见 run/*.log"
    return 1
  fi
}

start_web() {
  echo "=== 前端 dev server ==="
  if port_up 5173; then
    echo "  已在 5173 运行（PID $(port_pid 5173)），跳过"
    return 0
  fi
  ( cd "$ROOT/frontend" && PATH="$NODE_BIN_DIR:$PATH" \
      "$NODE_BIN_DIR/node.exe" node_modules/vite/bin/vite.js \
      --host 127.0.0.1 --port 5173 >> "$RUN_DIR/web.log" 2>&1 ) &
  if wait_ports 60 5173; then
    echo "  前端就绪：http://127.0.0.1:5173"
  else
    echo "  前端未在 60 秒内就绪，见 run/web.log"
    return 1
  fi
}

print_status() {
  local p name
  echo "=== 整栈状态 ==="
  for p in "$PG_PORT"; do
    port_up "$p" && echo "  PostgreSQL $p 运行中（PID $(port_pid $p)）" || echo "  PostgreSQL $p 未运行"
  done
  for p in 6379 6380; do
    port_up "$p" && echo "  Redis $p 运行中（PID $(port_pid $p)）" || echo "  Redis $p 未运行"
  done
  for name in core ingestion query; do
    p="$(port_of "$name")"
    port_up "$p" && echo "  $name $p 运行中（PID $(port_pid $p)）" || echo "  $name $p 未运行"
  done
  port_up 5173 && echo "  前端 5173 运行中（PID $(port_pid 5173)）" || echo "  前端 5173 未运行"
}

# 刷新 ops/local/run/*.pid。
# 本脚本一切判断都按端口（谁起的都停得掉），pid 文件只是给人看与给 service.sh 对账用的；
# 若不刷新，它会停在上一轮的数字上——曾因此被误读成「服务没起来」。
write_pidfiles() {
  local name p pid
  for name in core ingestion query; do
    p="$(port_of "$name")"
    pid="$(port_pid "$p")"
    [ -n "$pid" ] && printf '%s\n' "$pid" > "$RUN_DIR/$name.pid"
  done
}

stop_all() {
  echo "=== 停止整栈 ==="
  local name p pid
  for name in core ingestion query; do
    p="$(port_of "$name")"
    pid="$(port_pid "$p")"
    if [ -n "$pid" ]; then
      taskkill //PID "$pid" >/dev/null 2>&1
      echo "  $name（PID $pid）已请求停止"
    fi
  done
  # 等三个服务真正放开端口，避免数据库先停导致服务侧写错误日志
  local n=0
  while [ "$n" -lt 20 ] && { port_up 8081 || port_up 8082 || port_up 8083; }; do
    sleep 1
    n=$((n + 1))
  done
  # 仍未放开的强制结束：优雅停机是应用侧行为，不能假定它一定生效
  for name in core ingestion query; do
    p="$(port_of "$name")"
    pid="$(port_pid "$p")"
    if [ -n "$pid" ]; then
      taskkill //F //PID "$pid" >/dev/null 2>&1
      echo "  $name 优雅停机超时，已强制结束（PID $pid）"
    fi
  done

  pid="$(port_pid 5173)"
  if [ -n "$pid" ]; then
    taskkill //F //PID "$pid" >/dev/null 2>&1
    echo "  前端（PID $pid）已停止"
  fi

  for spec in "6379:cn_session_local_dev" "6380:cn_cache_local_dev"; do
    p="${spec%%:*}"
    local auth="${spec##*:}"
    if port_up "$p"; then
      # session 落盘、cache 不落盘（cache 实例本就无持久化语义）
      if [ "$p" = "6379" ]; then
        "$REDIS_HOME/redis-cli.exe" -p "$p" -a "$auth" --no-auth-warning shutdown >/dev/null 2>&1
      else
        "$REDIS_HOME/redis-cli.exe" -p "$p" -a "$auth" --no-auth-warning shutdown nosave >/dev/null 2>&1
      fi
      echo "  Redis $p 已停止"
    fi
  done

  if port_up "$PG_PORT"; then
    "$PG_HOME/bin/pg_ctl.exe" -D "$PG_DATA" stop -m fast >/dev/null 2>&1
    echo "  PostgreSQL 已停止"
  fi
  rm -f "$RUN_DIR"/core.pid "$RUN_DIR"/ingestion.pid "$RUN_DIR"/query.pid
  echo "=== 整栈已停止 ==="
}

# 优雅停机：先停子进程，再退出。整栈靠这一处保证不残留。
on_signal() {
  echo
  echo "########## 收到停止信号，开始停机 $(date '+%F %T') ##########"
  stop_all
  exit 0
}

NO_WEB=0
case "${1:-start}" in
  stop)
    stop_all
    exit 0
    ;;
  status)
    print_status
    exit 0
    ;;
  --no-web)
    NO_WEB=1
    ;;
  start)
    ;;
  *)
    echo "用法：bash ops/local/stack.sh [start|stop|status|--no-web]"
    exit 2
    ;;
esac

mkdir -p "$RUN_DIR"
echo "########## 本地整栈启动 $(date '+%F %T') ##########" >> "$RUN_DIR/stack.log"

# 处理器先于启动注册：启动途中被停也要能收尾，否则会漏下已起的子进程
trap on_signal INT TERM

start_infra || { echo "基础设施未就绪，放弃启动服务"; exit 1; }
start_services || { echo "服务未就绪，放弃启动前端"; exit 1; }
[ "$NO_WEB" = "1" ] || start_web

echo
print_status
write_pidfiles
echo
echo "本地栈已就绪并由本进程持有。结束本任务即停整栈。"
echo

# 看门狗：每 60 秒核一次整栈端口。缺谁就记一行，便于事后定位是哪个先掉的；
# 不做自动重启——本地演示更希望「坏了能看见」，而不是被悄悄掩盖。
while true; do
  sleep 60
  for p in $ALL_PORTS; do
    if ! port_up "$p"; then
      echo "[看门狗 $(date '+%F %T')] 端口 $p 已不在监听"
    fi
  done
done
