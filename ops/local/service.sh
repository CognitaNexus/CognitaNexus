#!/usr/bin/env bash
# CognitaNexus 本地服务启停（原生二进制模式）
#
# 为什么需要这个脚本：
#   1) 宿主环境会向子进程注入 SERVER__PORT，Spring Boot 的宽松绑定会把它当作
#      server.port。若不显式覆盖，应用会去抢宿主自己的端口并启动失败。
#      命令行参数优先级高于环境变量，故此处一律显式传 --server.port。
#   2) 运行中的 java -jar 会锁定 target 下的 jar，导致下一次 mvn install 在
#      repackage 阶段失败（无法重命名 jar）。因此构建前必须先停服务。
#   3) 代理环境：宿主注入了 http_proxy，本机回环请求也会被送去代理并得到 502。
#      任何针对本机服务的探测都要显式 --noproxy '*'，否则测的不是本机服务。
#
# 在受管沙箱中执行的注意事项（已实测）：
#   本脚本内部用 nohup ... & 脱离终端，但在「一次调用一个沙箱」的执行模型下，这次调用返回时
#   整个进程组会被回收——status 会显示三个端口都未运行，而日志里连启动失败的记录都没有。
#   因此在本机沙箱里启动服务必须由调用方以「后台任务」方式承载本次调用（而不是本脚本自己
#   再 fork）；在普通终端里直接执行本脚本则不受影响。
#
# 用法：
#   bash ops/local/service.sh start  [core|ingestion|query|all]
#   bash ops/local/service.sh stop   [core|ingestion|query|all]
#   bash ops/local/service.sh restart [core|ingestion|query|all]
#   bash ops/local/service.sh status
#   bash ops/local/service.sh logs   [core|ingestion|query]

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_DIR="$ROOT/ops/local/run"
JAVA_BIN="${JAVA_BIN:-java}"

# 服务名 → 端口
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

pidfile_of() {
  echo "$RUN_DIR/$1.pid"
}

logfile_of() {
  echo "$RUN_DIR/$1.log"
}

# 占用指定端口的 PID（仅取 LISTENING）
port_pid() {
  netstat -ano 2>/dev/null | awk -v p=":$1" '$0 ~ p && $0 ~ /LISTENING/ {print $NF}' | head -1
}

is_running() {
  local pid
  pid="$(port_pid "$(port_of "$1")")"
  [ -n "$pid" ]
}

start_one() {
  local name="$1" port jar log pidfile
  port="$(port_of "$name")"
  jar="$(jar_of "$name")"
  log="$(logfile_of "$name")"
  pidfile="$(pidfile_of "$name")"

  if [ -z "$port" ]; then
    echo "  未知服务：$name"
    return 1
  fi
  if is_running "$name"; then
    echo "  $name 已在端口 $port 运行（PID $(port_pid "$port")）"
    return 0
  fi
  if [ ! -f "$jar" ]; then
    echo "  $name 未构建：缺少 $jar"
    return 1
  fi

  mkdir -p "$RUN_DIR"
  : > "$log"
  # 显式覆盖 server.port / server.address，理由见文件头
  nohup "$JAVA_BIN" -Dfile.encoding=UTF-8 -jar "$jar" \
    --server.port="$port" --server.address=127.0.0.1 >> "$log" 2>&1 &
  echo $! > "$pidfile"

  local waited=0
  while [ "$waited" -lt 60 ]; do
    if grep -q "Started .*Application in" "$log" 2>/dev/null; then
      echo "  $name 已启动，端口 $port（PID $(port_pid "$port")）"
      return 0
    fi
    if grep -qE "APPLICATION FAILED TO START|Error starting ApplicationContext" "$log" 2>/dev/null; then
      echo "  $name 启动失败，见 $log"
      return 1
    fi
    sleep 1
    waited=$((waited + 1))
  done
  echo "  $name 启动超时（60 秒），见 $log"
  return 1
}

stop_one() {
  local name="$1" port pid
  port="$(port_of "$name")"
  pid="$(port_pid "$port")"
  if [ -z "$pid" ]; then
    echo "  $name 未在运行"
    rm -f "$(pidfile_of "$name")"
    return 0
  fi
  # 先请求优雅停机（应用已开启 graceful shutdown），超时再强杀
  taskkill //PID "$pid" >/dev/null 2>&1
  local waited=0
  while [ "$waited" -lt 20 ] && [ -n "$(port_pid "$port")" ]; do
    sleep 1
    waited=$((waited + 1))
  done
  if [ -n "$(port_pid "$port")" ]; then
    taskkill //F //PID "$pid" >/dev/null 2>&1
    sleep 1
  fi
  rm -f "$(pidfile_of "$name")"
  echo "  $name 已停止"
}

targets() {
  case "${1:-all}" in
    core | ingestion | query) echo "$1" ;;
    all) echo "core ingestion query" ;;
    *) echo "" ;;
  esac
}

action="${1:-status}"
scope="${2:-all}"
list="$(targets "$scope")"

if [ -z "$list" ]; then
  echo "未知服务：$scope（可选 core|ingestion|query|all）"
  exit 2
fi

case "$action" in
  start)
    echo "=== 启动服务 ==="
    for s in $list; do start_one "$s"; done
    ;;
  stop)
    echo "=== 停止服务 ==="
    for s in $list; do stop_one "$s"; done
    ;;
  restart)
    echo "=== 重启服务 ==="
    for s in $list; do stop_one "$s"; done
    for s in $list; do start_one "$s"; done
    ;;
  status)
    echo "=== 服务状态 ==="
    for s in core ingestion query; do
      port="$(port_of "$s")"
      pid="$(port_pid "$port")"
      if [ -n "$pid" ]; then
        echo "  $s  端口 $port 运行中（PID $pid）"
      else
        echo "  $s  端口 $port 未运行"
      fi
    done
    echo "=== 依赖状态 ==="
    if netstat -ano 2>/dev/null | grep -q ":5432 .*LISTENING"; then
      echo "  PostgreSQL 5432 运行中"
    else
      echo "  PostgreSQL 5432 未运行"
    fi
    for p in 6379 6380; do
      if netstat -ano 2>/dev/null | grep -q ":$p .*LISTENING"; then
        echo "  Redis $p 运行中"
      else
        echo "  Redis $p 未运行"
      fi
    done
    ;;
  logs)
    for s in $list; do
      echo "===== $s （$(logfile_of "$s")） ====="
      tail -n 40 "$(logfile_of "$s")" 2>/dev/null || echo "  无日志"
    done
    ;;
  *)
    echo "用法：bash ops/local/service.sh {start|stop|restart|status|logs} [core|ingestion|query|all]"
    exit 2
    ;;
esac
