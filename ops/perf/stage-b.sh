#!/usr/bin/env bash
# =============================================================================
# B／C 阶段容量联调：独立部署 + 合成数据 + 固定查询集 + 活跃用户压测
#
# 为什么单独一套：老师指导 §7 要求压测「不占用当前共享服务」。本脚本把 core 与 query
# 各起一个独立进程（18081 / 18083），连的是 55432 隔离实例上的 core_perf / query_perf，
# 与开发库 core_db / query_db 完全不共享表。ingestion 不参与公开读负载，故不启动。
#
# 与生产的差别（报告里必须原样写出，不得当生产等价环境）：
#   * 数据库、Redis、服务、负载机全部在同一台机器上，CPU 与磁盘 IO 互相争抢；
#   * 合成数据全部 SIMULATED，公开检索必须以 dataNature=ALL 显式放宽才看得到；
#   * 数据直接写投影表，不经过 Outbox→Inbox 事件链路（传播延迟另由 C 阶段的恢复演练评估）。
#
# 用法：
#   bash ops/perf/stage-b.sh up    [--n=1000000]   # 建库→起服务→造数→计数→查询计划→阻塞等待
#   bash ops/perf/stage-b.sh counts                # 只重跑分层计数
#   bash ops/perf/stage-b.sh explain               # 只重跑固定查询集
#   bash ops/perf/stage-b.sh status
#   bash ops/perf/stage-b.sh down
#
# 沙箱注意：`up` 内部前台阻塞（同 ops/local/stack.sh），必须由调用方以后台任务承载，
# 否则这次调用一返回，整个进程组连同两个 JVM 一起被回收。
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_DIR="$ROOT/ops/perf/run"
LOG_DIR="$ROOT/reports/perf"
IT_PORT=55432
PG_HOME="D:/dev-tools/pgsql"
PSQL="$PG_HOME/bin/psql.exe"
CORE_PORT=18081
QUERY_PORT=18083
JAVA_BIN="${JAVA_BIN:-java}"

N=1000000
SEED=0.42
for arg in "$@"; do
  case "$arg" in
    --n=*) N="${arg#--n=}" ;;
    --seed=*) SEED="${arg#--seed=}" ;;
  esac
done

STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="$LOG_DIR/$STAMP"

mkdir -p "$RUN_DIR" "$OUT_DIR"

psql_perf() {
  local db="$1"
  shift
  # </dev/null 不是可选项：批处理下的 psql 会继承调用方的 stdin，没有它时命令看似成功、
  # 进程却不再返回（实测过一次 DROP DATABASE ... WITH (FORCE) 卡死 20 秒以上）。
  PGCLIENTENCODING=UTF8 PGPASSWORD=postgres_it "$PSQL" -h 127.0.0.1 -p "$IT_PORT" -U postgres -d "$db" "$@" < /dev/null
}

wait_http() {
  local url="$1" name="$2" limit="${3:-90}"
  for _ in $(seq 1 "$limit"); do
    if curl --noproxy '*' -sf "$url" >/dev/null 2>&1; then
      echo "  $name 就绪"
      return 0
    fi
    sleep 1
  done
  echo "  $name 在 ${limit}s 内未就绪，日志见 $RUN_DIR"
  return 1
}

port_busy() {
  netstat -ano 2>/dev/null | grep -q "LISTENING" && \
  netstat -ano 2>/dev/null | grep -E "[:.]$1[[:space:]]" | grep -q "LISTENING"
}

start_services() {
  echo "=== 启动 core(18081) / query(18083)：连 $IT_PORT 的 core_perf / query_perf ==="
  JAVA_ARGS_COMMON=(-Duser.timezone=Asia/Shanghai)
  nohup "$JAVA_BIN" "${JAVA_ARGS_COMMON[@]}" \
    -jar "$ROOT/services/core-service/target/core-service-0.2.0.jar" \
    --server.port=$CORE_PORT \
    --spring.datasource.url="jdbc:postgresql://127.0.0.1:$IT_PORT/core_perf" \
    --spring.datasource.username=postgres --spring.datasource.password=postgres_it \
    --spring.flyway.url="jdbc:postgresql://127.0.0.1:$IT_PORT/core_perf" \
    --spring.flyway.user=postgres --spring.flyway.password=postgres_it \
    --cn.outbox.dispatcher-enabled=false \
    > "$RUN_DIR/core-perf.log" 2>&1 &
  echo $! > "$RUN_DIR/core-perf.pid"

  CN_CORE_BASE_URL="http://127.0.0.1:$CORE_PORT" \
  nohup "$JAVA_BIN" "${JAVA_ARGS_COMMON[@]}" \
    -jar "$ROOT/services/query-service/target/query-service-0.2.0.jar" \
    --server.port=$QUERY_PORT \
    --spring.datasource.url="jdbc:postgresql://127.0.0.1:$IT_PORT/query_perf" \
    --spring.datasource.username=postgres --spring.datasource.password=postgres_it \
    --spring.flyway.url="jdbc:postgresql://127.0.0.1:$IT_PORT/query_perf" \
    --spring.flyway.user=postgres --spring.flyway.password=postgres_it \
    --cn.projection.worker-enabled=false \
    > "$RUN_DIR/query-perf.log" 2>&1 &
  echo $! > "$RUN_DIR/query-perf.pid"

  wait_http "http://127.0.0.1:$CORE_PORT/actuator/health" "core" 120 || return 1
  wait_http "http://127.0.0.1:$QUERY_PORT/actuator/health" "query" 120 || return 1
}

reset_databases() {
  echo "=== 重置 core_perf / query_perf ==="
  # 不 DROP DATABASE：本机实测删库一次约 20 秒（Windows 上删目录被文件系统行为拖慢），
  # 两次删库就顶掉半分钟。改成只重建 public schema——Flyway 的历史也在 public 里，
  # 一起清掉后下次启动会重跑全部迁移，效果等价而快得多。
  for db in core_perf query_perf; do
    if ! psql_perf postgres -Atc "SELECT 1 FROM pg_database WHERE datname = '$db'" 2>/dev/null | grep -q 1; then
      psql_perf postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $db" >/dev/null
      echo "  已建库 $db"
    fi
    psql_perf "$db" -v ON_ERROR_STOP=1 -q \
      -c "SET client_min_messages = warning" \
      -c "DROP SCHEMA IF EXISTS public CASCADE" -c "CREATE SCHEMA public" >/dev/null
  done
  echo "  已重置"
}

seed() {
  echo "=== 造数：n=$N seed=$SEED（全部 SIMULATED）==="
  local t0=$(date +%s)
  # -1 是必需的：core 有一条延迟约束触发器，要求「可发布成果恰好有一个类型扩展」，
  # 而成果主表与四张类型表是分开插入的。单事务提交后触发器才在提交时检查，
  # 分开提交会立刻在第一条主表插入上报错。
  psql_perf core_perf -q -1 -v ON_ERROR_STOP=1 -v n="$N" -v seed="$SEED" \
    -f "$ROOT/tests/perf/seed-core.sql" > "$OUT_DIR/seed-core.log" 2>&1
  local rc=$?
  echo "  core 造数完成 rc=$rc 用时 $(( $(date +%s) - t0 ))s"
  [ $rc -ne 0 ] && return $rc

  t0=$(date +%s)
  psql_perf query_perf -q -v ON_ERROR_STOP=1 -v n="$N" -v seed="$SEED" \
    -f "$ROOT/tests/perf/seed-query.sql" > "$OUT_DIR/seed-query.log" 2>&1
  rc=$?
  echo "  query 造数完成 rc=$rc 用时 $(( $(date +%s) - t0 ))s"
  return $rc
}

counts() {
  echo "=== 分层计数 ==="
  psql_perf core_perf -f "$ROOT/tests/perf/counts.core.sql" 2>&1 | tee "$OUT_DIR/counts-core.txt"
  psql_perf query_perf -f "$ROOT/tests/perf/counts.query.sql" 2>&1 | tee "$OUT_DIR/counts-query.txt"
}

explain() {
  echo "=== 固定查询集（EXPLAIN ANALYZE）==="
  psql_perf query_perf -f "$ROOT/tests/perf/explain.sql" > "$OUT_DIR/explain.txt" 2>&1
  echo "  计划已写入 $OUT_DIR/explain.txt"
  grep -E "Execution Time|Planning Time" "$OUT_DIR/explain.txt" | head -40
}

wait_forever() {
  echo
  echo "=== 服务已就绪，脚本保持前台阻塞（Ctrl-C 结束） ==="
  echo "  core  : http://127.0.0.1:$CORE_PORT   query: http://127.0.0.1:$QUERY_PORT"
  echo "  日志  : $RUN_DIR/core-perf.log / $RUN_DIR/query-perf.log"
  echo "  产出  : $OUT_DIR"
  echo "$OUT_DIR" > "$RUN_DIR/last-run-dir"
  while true; do
    sleep 30
    if ! curl --noproxy '*' -sf "http://127.0.0.1:$QUERY_PORT/actuator/health" >/dev/null 2>&1; then
      echo "  [warn] query 健康检查失败，见 $RUN_DIR/query-perf.log"
    fi
  done
}

down() {
  echo "=== 停止 perf 服务 ==="
  for name in core-perf query-perf; do
    pidf="$RUN_DIR/$name.pid"
    if [ -f "$pidf" ]; then
      pid="$(cat "$pidf")"
      if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null
        for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
        kill -9 "$pid" 2>/dev/null
      fi
      rm -f "$pidf"
      echo "  $name 已停止"
    fi
  done
}

case "${1:-}" in
  up)
    bash "$ROOT/ops/local/test-db.sh" start >/dev/null 2>&1
    reset_databases || exit 1
    start_services || exit 1
    seed || exit 1
    counts
    explain
    wait_forever
    ;;
  counts) counts ;;
  explain) explain ;;
  down) down ;;
  status)
    echo "=== perf 服务 ==="
    curl --noproxy '*' -sf "http://127.0.0.1:$CORE_PORT/actuator/health" >/dev/null && echo "  core 18081 运行中" || echo "  core 18081 未运行"
    curl --noproxy '*' -sf "http://127.0.0.1:$QUERY_PORT/actuator/health" >/dev/null && echo "  query 18083 运行中" || echo "  query 18083 未运行"
    ls -1 "$LOG_DIR" 2>/dev/null | tail -3
    ;;
  *)
    sed -n '20,30p' "${BASH_SOURCE[0]}"
    exit 1
    ;;
esac
