#!/usr/bin/env bash
# =============================================================================
# 阶段六分级容量压测编排：预检 → 造数 → 索引 → 计数 → 计划 → 图投影 → 并发压测
#
# 设计原则（与 AGENTS.md §3.1、§5 一致，逐条对应）：
#   1. **导入前必须过安全预检**。磁盘不足、内存不足、存在 OOM 风险或会影响其他业务时，
#      不启动大规模导入。预检不通过时本脚本直接退出（退出码 3），只写报告不改数据；
#      要跳过必须显式 --force 并留下理由（写进报告，不能悄悄越线）。
#   2. **阶段串行、结果分目录**。每个规模一个独立数据库（qperf_10k / qperf_100k /
#      qperf_1m / qperf_5m），互不覆盖：跑完 1 万再跑 10 万时，1 万的数据仍在，
#      可以回头复测而不用重造。
#   3. **所有合成数据标记 SIMULATED**。造数脚本自己保证；本脚本在造数后核对
#      data_nature 分布，发现非 SIMULATED 行立即中止。
#   4. **每阶段记录**：成果/学者/作者关系/合作边实体数、PG 堆与索引体积、Neo4j store
#      与索引体积、导入耗时、索引构建耗时、投影耗时、CPU/内存峰值、OOM/错误信息。
#      缺任何一项都算这一阶段没跑完，不能写「通过」。
#   5. **不在生产环境跑**。脚本拒绝指向非 127.0.0.1 的数据库主机，也不读生产配置。
#
# 用法：
#   bash ops/perf/run-scale-benchmark.sh --only=preflight --stage=5m
#   bash ops/perf/run-scale-benchmark.sh --only=all --stage=10k --vus=10
#   bash ops/perf/run-scale-benchmark.sh --stage=1m --vus=10,30,50,100
#   bash ops/perf/run-scale-benchmark.sh --only=graph --stage=1m   # 只做图投影装载
#   bash ops/perf/run-scale-benchmark.sh --only=load --stage=1m --skip-services
#   bash ops/perf/run-scale-benchmark.sh --dry-run --stage=5m      # 只算容量、不碰数据库
#
# --dry-run 的定位：打印本次将要执行的每一步、容量模型读数、主机读数与门禁结论，
# **不连数据库、不建库、不造数、不启服务**。改了参数或换机器时先跑它，避免
# 「压测跑了 20 分钟才发现磁盘不够 / psql 不在 PATH / jar 没构建」。
# 门禁阻断时 --dry-run 也以退出码 4 结束（要预览越线计划加 --force）。
#
# 环境变量（默认值适用于本机零特权测试运行）：
#   CN_RUNTIME=/home/admin/cn-test-runtime   PGBIN=$CN_RUNTIME/pgsql/usr/lib/postgresql/16/bin
#   PG_HOST=127.0.0.1  PG_IT_PORT=55432  PG_USER=postgres
#   CN_TEST_NEO4J_HTTP_PORT=17474  CN_TEST_NEO4J_BOLT_PORT=17687
#   CN_TEST_NEO4J_USER=neo4j  CN_TEST_NEO4J_PASSWORD=<必填，仅在 --only=graph/load 且图库启用时>
#   PG_PASSWORD / PGPASSWORD=<CI service 容器必填；本机 trust 认证可留空>
#   CN_PERF_REPORTS=<报告根目录，默认 $ROOT/reports/perf/<时间戳>>
#   CN_PERF_DISK_PATH=<容量模型要测量可用空间的路径，默认仓库根；云上应指向数据卷>
#   CN_PERF_ALLOW_REMOTE_DB=1 <允许 PG_HOST 不是回环地址；上云自测数据库时显式开启>
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
REPORTS="${CN_PERF_REPORTS:-$ROOT/reports/perf/$STAMP}"

# ---------------------------------------------------------------------------
# 参数
# ---------------------------------------------------------------------------
STAGE="10k"
ONLY="all"
VUS_LIST="10,30,50,100"
WARMUP=120
SECONDS_STEADY=300
THINK=1000
TIMEOUT_MS=5000
FORCE=0
FORCE_REASON=""
SKIP_SERVICES=0
SERVICES_ALREADY_UP=0
GRAPH_MODE="POSTGRES_ONLY"
SKIP_GRAPH=0
MAX_DB_GB=0     # 0 = 用容量模型算出的需求
DISK_HEADROOM_GB=4
DRY_RUN=0

for arg in "$@"; do
  case "$arg" in
    --stage=*) STAGE="${arg#--stage=}" ;;
    --only=*) ONLY="${arg#--only=}" ;;
    --vus=*) VUS_LIST="${arg#--vus=}" ;;
    --warmup=*) WARMUP="${arg#--warmup=}" ;;
    --seconds=*) SECONDS_STEADY="${arg#--seconds=}" ;;
    --think=*) THINK="${arg#--think=}" ;;
    --timeout=*) TIMEOUT_MS="${arg#--timeout=}" ;;
    --graph-mode=*|--query-mode=*) GRAPH_MODE="${arg#*=}" ;;
    --max-db-gb=*) MAX_DB_GB="${arg#--max-db-gb=}" ;;
    --disk-headroom-gb=*) DISK_HEADROOM_GB="${arg#--disk-headroom-gb=}" ;;
    --skip-graph) SKIP_GRAPH=1 ;;
    --skip-services) SKIP_SERVICES=1 ;;
    --dry-run|--plan) DRY_RUN=1 ;;
    --force) FORCE=1 ;;
    --force-reason=*) FORCE_REASON="${arg#--force-reason=}" ;;
    -h|--help) sed -n '2,45p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数：$arg（--help 看用法）" >&2; exit 2 ;;
  esac
done

# ---------------------------------------------------------------------------
# 环境
# ---------------------------------------------------------------------------
if [ -f "${CN_RUNTIME:-/home/admin/cn-test-runtime}/bin/env.sh" ]; then
  # shellcheck disable=SC1091
  . "${CN_RUNTIME:-/home/admin/cn-test-runtime}/bin/env.sh"
fi
CN_RT="${CN_RT:-/home/admin/cn-test-runtime}"
PGROOT="${PGROOT:-$CN_RT/pgsql}"
PGBIN="${PGBIN:-$PGROOT/usr/lib/postgresql/16/bin}"
PG_HOST="${PG_HOST:-127.0.0.1}"
PG_IT_PORT="${PGIT_PORT:-${PG_IT_PORT:-55432}}"
PG_USER="${PG_USER:-postgres}"
NEO4J_HTTP_PORT="${NEO4J_HTTP_PORT:-17474}"
NEO4J_BOLT_PORT="${NEO4J_BOLT_PORT:-17687}"
NEO4J_USER="${CN_TEST_NEO4J_USER:-neo4j}"
NEO4J_PASSWORD="${CN_TEST_NEO4J_PASSWORD:-}"
GRAPH_SCOPE="${CN_NEO4J_SCOPE:-query-public}"
GENERATION="gen0001"
SEED="0.42"

QUERY_PORT="${QUERY_PORT:-18083}"
CORE_PORT="${CORE_PORT:-18081}"

PSQL="$PGBIN/psql"
[ -x "$PSQL" ] || PSQL="$(command -v psql || true)"

# 容量模型要测「哪个文件系统的可用空间」：本机是仓库根，云上应显式指向数据卷。
DISK_PATH="${CN_PERF_DISK_PATH:-$ROOT}"

# psql 认证：本机零特权运行时是 trust/peer，不需要口令；CI 的 service 容器必须给。
# 只从环境变量取，脚本里不写死任何口令（安全扫描 A 类就是查这个）。
PG_PASSWORD="${PG_PASSWORD:-${PGPASSWORD:-}}"
if [ -n "$PG_PASSWORD" ]; then export PGPASSWORD="$PG_PASSWORD"; fi

# 服务 jar 的路径允许覆盖：版本号一升，写死的文件名就会变成「找不到 jar」。
QUERY_JAR="${QUERY_JAR:-}"
CORE_JAR="${CORE_JAR:-}"
if [ -z "$QUERY_JAR" ]; then
  QUERY_JAR=$(ls "$ROOT"/services/query-service/target/query-service-*.jar 2>/dev/null | head -1)
fi
if [ -z "$CORE_JAR" ]; then
  CORE_JAR=$(ls "$ROOT"/services/core-service/target/core-service-*.jar 2>/dev/null | head -1)
fi

# ---------------------------------------------------------------------------
# 阶段参数（n / 学者池 / 数据库名）
#   学者池 = n * 作者数均值 / 每学者承载成果数(6)，与 capacity-model.mjs 的
#   worksPerScholar 保持一致；漂移会让「学者数」对不上模型预测。
# ---------------------------------------------------------------------------
case "$STAGE" in
  10k)   N=10000;    DB="qperf_10k";  POOL=5400 ;;
  100k)  N=100000;   DB="qperf_100k"; POOL=54000 ;;
  1m)    N=1000000;  DB="qperf_1m";   POOL=540000 ;;
  5m)    N=5000000;  DB="qperf_5m";   POOL=2700000 ;;
  *) echo "未知 --stage=$STAGE（可选 10k / 100k / 1m / 5m）" >&2; exit 2 ;;
esac

OUT="$REPORTS/$STAGE"
mkdir -p "$OUT"
LOG="$OUT/pipeline.log"

log()  { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" | tee -a "$LOG"; }
warn() { printf '%s [warn] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" | tee -a "$LOG" >&2; }
fail() { printf '%s [FAIL] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" | tee -a "$LOG" >&2; exit "${2:-1}"; }

# 不在生产/共享库上跑：本脚本会造数、写图库、跑 EXPLAIN ANALYZE。
# 回环地址以外的目标必须显式 CN_PERF_ALLOW_REMOTE_DB=1，否则直接拒绝。
case "$PG_HOST" in
  127.0.0.1|localhost|::1) : ;;
  *)
    if [ "${CN_PERF_ALLOW_REMOTE_DB:-0}" != "1" ]; then
      fail "PG_HOST=$PG_HOST 不是回环地址。本脚本会造数与重建索引，拒绝在远程/共享库上运行。\n        确需在云上自建压测库上执行时，显式设置 CN_PERF_ALLOW_REMOTE_DB=1 并在报告里写明目标实例。" 2
    fi
    warn "CN_PERF_ALLOW_REMOTE_DB=1：目标库 $PG_HOST:$PG_IT_PORT 是远程实例，确认它是压测库而不是任何共享/生产库"
    ;;
esac

psql_admin() { PGCONNECT_TIMEOUT=5 PGCLIENTENCODING=UTF8 "$PSQL" -h "$PG_HOST" -p "$PG_IT_PORT" -U "$PG_USER" "$@" </dev/null; }
psql_db()    { psql_admin -d "$DB" "$@"; }

# ---------------------------------------------------------------------------
# 采样器：/proc 读数，1 秒一次，阶段结束时汇总峰值
#
# 为什么要记进程级 RSS 而不只记系统 MemAvailable：OOM 的判定发生在「谁在涨」上。
# 只记系统总量时，一次 5 分钟压测的内存上升到底是 PG 的页缓存、Neo4j 的 pagecache，
# 还是 JVM 堆，事后无法区分，也就无法给出「需要多少内存」的结论。
# ---------------------------------------------------------------------------
SAMPLER_PID=""
start_sampler() {
  local file="$OUT/resources-$1.tsv"
  : > "$file"
  printf 'ts\tmem_avail_mb\tmem_used_mb\tload1\tdisk_avail_gb\tpostgres_rss_mb\tneo4j_rss_mb\tjava_rss_mb\n' >> "$file"
  (
    while true; do
      local mem_avail mem_total load1 disk avail
      mem_avail=$(awk '/^MemAvailable:/{print int($2/1024)}' /proc/meminfo)
      mem_total=$(awk '/^MemTotal:/{print int($2/1024)}' /proc/meminfo)
      load1=$(awk '{print $1}' /proc/loadavg)
      disk=$(df -BG --output=avail / | tail -1 | tr -dc '0-9')
      local pg_rss neo_rss java_rss
      pg_rss=$(ps -eo rss,args --no-headers 2>/dev/null | awk '/postgres: /{s+=$1} END{print int(s/1024)}')
      neo_rss=$(ps -eo rss,args --no-headers 2>/dev/null | awk '/neo4j|Neo4jBoot|CommunityEntryPoint/{s+=$1} END{print int(s/1024)}')
      java_rss=$(ps -eo rss,args --no-headers 2>/dev/null | awk '/query-service|core-service/{s+=$1} END{print int(s/1024)}')
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$mem_avail" "$((mem_total-mem_avail))" "$load1" \
        "$disk" "${pg_rss:-0}" "${neo_rss:-0}" "${java_rss:-0}" >> "$file"
      sleep 1
    done
  ) &
  SAMPLER_PID=$!
}

stop_sampler() {
  if [ -n "$SAMPLER_PID" ] && kill -0 "$SAMPLER_PID" 2>/dev/null; then
    kill "$SAMPLER_PID" 2>/dev/null
    wait "$SAMPLER_PID" 2>/dev/null
  fi
  SAMPLER_PID=""
}

summarize_resources() {
  local file="$1"
  [ -s "$file" ] || { echo "（无采样数据）"; return; }
  awk -F'\t' 'NR>1 {
      if ($2+0 < minavail || NR==2) minavail=$2+0;
      if ($8+0 > maxjava) maxjava=$8+0;
      if ($6+0 > maxpg) maxpg=$6+0;
      if ($7+0 > maxneo) maxneo=$7+0;
      if ($4+0 > maxload) maxload=$4+0;
      n++;
      last=$5
    } END {
      printf "采样 %d 次；MemAvailable 最低 %d MB；postgres RSS 峰值 %d MB；neo4j RSS 峰值 %d MB；JVM RSS 峰值 %d MB；load1 峰值 %.2f；结束时磁盘可用 %s GB\n",
        n, minavail, maxpg, maxneo, maxjava, maxload, last
    }' "$file"
}

# ---------------------------------------------------------------------------
# 1. 安全预检（Disk & Memory Safety Pre-flight Check）
# ---------------------------------------------------------------------------
PREFLIGHT_JSON=""

preflight() {
  log "=== 安全预检：stage=$STAGE（n=$N，库=$DB）==="
  [ -x "$PSQL" ] || fail "找不到 psql（PGBIN=$PGBIN）。先跑 bash $CN_RT/bin/up.sh" 2

  # --- 容量模型给出的需求 ---
  # 一律用 --avail-gb=auto / --mem-gb=auto：手填的 21 GB / 7.4 GiB 只描述开发机那一刻，
  # 换一台 runner 就不再成立，而门禁的结论必须来自「这台机器现在」的读数。
  PREFLIGHT_JSON="$OUT/capacity-model.json"
  if command -v node >/dev/null 2>&1; then
    node "$ROOT/tests/perf/capacity-model.mjs" \
      --avail-gb=auto --mem-gb=auto --disk-path="$DISK_PATH" \
      --disk-headroom-gb="$DISK_HEADROOM_GB" \
      --json="$PREFLIGHT_JSON" --markdown="$OUT/capacity-model.md" \
      >/dev/null 2>&1 \
      || fail "容量模型自检未通过（capacity-model.mjs 退出码非 0）：模型与实测基线已漂移，先修模型再导入" 3
  else
    fail "没有 node，无法运行容量模型；不允许凭估算直接导入" 2
  fi

  local need_disk need_mem_min
  need_disk=$(node -e '
    const s=require(process.argv[1]);
    const st=s.stages.find(x=>x.stage===process.argv[2]);
    if(!st){console.error("stage not found");process.exit(1)}
    console.log((st.pg.planningGb + (process.env.SKIP_GRAPH==="1"?0:st.neo4j.diskGb)).toFixed(2));
  ' "$PREFLIGHT_JSON" "$(stage_label)")
  need_mem_min=$(node -e '
    const s=require(process.argv[1]);
    const st=s.stages.find(x=>x.stage===process.argv[2]);
    console.log(Math.ceil(st.memory.minimalTotalGb*1024));
  ' "$PREFLIGHT_JSON" "$(stage_label)")
  local verdict
  verdict=$(node -e '
    const s=require(process.argv[1]);
    const st=s.stages.find(x=>x.stage===process.argv[2]);
    console.log(st.verdict);
  ' "$PREFLIGHT_JSON" "$(stage_label)")

  local disk_avail_gb mem_avail_mb swap_mb
  disk_avail_gb=$(df -BG --output=avail "$DISK_PATH" | tail -1 | tr -dc '0-9')
  mem_avail_mb=$(awk '/^MemAvailable:/{print int($2/1024)}' /proc/meminfo)
  swap_mb=$(awk '/^SwapTotal:/{print int($2/1024)}' /proc/meminfo)

  log "容量模型判定：$verdict"
  log "预计需要：磁盘 ${need_disk} GB（$( [ "$SKIP_GRAPH" = 1 ] && echo '仅 PG + WAL，不含 Neo4j' || echo 'PG + Neo4j + WAL/事务日志' )）、内存下限 ${need_mem_min} MB"
  log "当前可用：磁盘 ${disk_avail_gb} GB、MemAvailable ${mem_avail_mb} MB、Swap ${swap_mb} MB"

  {
    echo "stage=$STAGE n=$N db=$DB"
    echo "model_verdict=$verdict"
    echo "need_disk_gb=$need_disk"
    echo "need_mem_min_mb=$need_mem_min"
    echo "disk_avail_gb=$disk_avail_gb"
    echo "mem_avail_mb=$mem_avail_mb"
    echo "swap_mb=$swap_mb"
  } > "$OUT/preflight.env"

  local problems=()
  if [ -n "$MAX_DB_GB" ] && [ "$MAX_DB_GB" != "0" ]; then
    need_disk="$MAX_DB_GB"
    log "使用 --max-db-gb=$MAX_DB_GB 覆盖磁盘需求（仅在你已手工核对过容量模型时这样做）"
  fi
  awk -v need="$need_disk" -v avail="$disk_avail_gb" -v head="$DISK_HEADROOM_GB" \
    'BEGIN{ if (avail - head < need) exit 1; exit 0 }' \
    || problems+=("磁盘不足：需要 ${need_disk} GB + ${DISK_HEADROOM_GB} GB 余量，可用 ${disk_avail_gb} GB")

  awk -v need="$need_mem_min" -v avail="$mem_avail_mb" \
    'BEGIN{ if (avail < need) exit 1; exit 0 }' \
    || problems+=("内存不足：下限 ${need_mem_min} MB，MemAvailable ${mem_avail_mb} MB")

  if [ "$swap_mb" != "0" ]; then
    warn "本机有 ${swap_mb} MB swap：内存压力下会出现与 8 G 无 swap 环境不同的延迟分布，报告须注明"
  fi

  # 库是否已存在且非空（重复跑到同一阶段时提示体积会叠加）
  if psql_admin -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname = '$DB'" 2>/dev/null | grep -q 1; then
    local cur_size
    cur_size=$(psql_admin -d "$DB" -Atc "SELECT pg_size_pretty(pg_database_size(current_database()))" 2>/dev/null)
    log "目标库 $DB 已存在（当前 $cur_size）：造数会先清空同名 generation，不会叠加"
  fi

  if [ "${#problems[@]}" -gt 0 ]; then
    for p in "${problems[@]}"; do warn "预检未通过：$p"; done
    if [ "$FORCE" = "1" ]; then
      [ -n "$FORCE_REASON" ] || fail "--force 必须同时给 --force-reason，否则越线无可追溯" 3
      warn "--force 越线执行，理由：$FORCE_REASON"
      echo "forced=true" >> "$OUT/preflight.env"
      echo "force_reason=$FORCE_REASON" >> "$OUT/preflight.env"
    else
      fail "预检未通过，拒绝导入。按任务书要求：磁盘不足或存在 OOM 风险时不得直接启动大规模导入。修好资源，或用 --force --force-reason=... 显式越线并留痕。" 3
    fi
  else
    log "预检通过"
  fi
  echo "preflight=passed" >> "$OUT/preflight.env"
}

stage_label() {
  case "$STAGE" in
    10k) echo "1万" ;;
    100k) echo "10万" ;;
    1m) echo "100万" ;;
    5m) echo "500万" ;;
  esac
}

# ---------------------------------------------------------------------------
# 2. 建库 + 迁移（靠服务启动跑 Flyway，不手写迁移脚本）
# ---------------------------------------------------------------------------
ensure_database() {
  local exists
  exists=$(psql_admin -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname = '$DB'" 2>/dev/null)
  if [ "$exists" != "1" ]; then
    psql_admin -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $DB" >/dev/null \
      || fail "建库失败：$DB"
    log "已建库 $DB（Flyway 将在服务启动时执行 V1..V7）"
  fi
  # core 侧的权威水位库必须预先存在：start_services 用 Flyway 建表，但 Flyway 不会建
  # 数据库本身。本机靠 ops/local/test-db.sh 提前建好，CI/云上新起的 PG 没有这一步——
  # 少了它，core-service 会在连接阶段就死，而报错看起来像「core 启动慢」。
  if [ "$SKIP_SERVICES" != "1" ]; then
    exists=$(psql_admin -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname = 'core_perf'" 2>/dev/null)
    if [ "$exists" != "1" ]; then
      psql_admin -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE core_perf" >/dev/null \
        || fail "建库失败：core_perf（公开屏障需要 core 汇报权威水位）"
      log "已建库 core_perf"
    fi
  fi
  # 迁移是否已经跑过：qry_achievement 存在即视为已迁移
  if [ "$(psql_admin -d "$DB" -Atc "SELECT to_regclass('public.qry_achievement') IS NOT NULL" 2>/dev/null)" != "t" ]; then
    log "目标库尚未迁移，先启动一次 query-service 让 Flyway 建表"
    start_services
    stop_services
  fi
}

# ---------------------------------------------------------------------------
# 3. 造数（导入 + 索引构建分开计时）
# ---------------------------------------------------------------------------
seed() {
  log "=== 造数 stage=$STAGE n=$N pool=$POOL（全部 SIMULATED）==="

  # 3.1 先摘索引，再灌数据：这样「导入耗时」与「索引构建耗时」可以分别报
  local t0 t1
  t0=$(date +%s)
  psql_db -v ON_ERROR_STOP=1 -q -v mode=drop -f "$ROOT/tests/perf/scale/pg-index-toggle.sql" \
    > "$OUT/index-drop.log" 2>&1 || fail "摘索引失败，见 $OUT/index-drop.log"
  log "已摘除非主键索引（$(( $(date +%s) - t0 ))s，快照在 perf.index_snapshot）"

  # 3.2 灌数据
  t0=$(date +%s)
  psql_db -v ON_ERROR_STOP=1 -q \
    -v n="$N" -v gen="$GENERATION" -v seed="$SEED" -v pool="$POOL" \
    -f "$ROOT/tests/perf/scale/seed-scale-query.sql" \
    > "$OUT/seed-scale.log" 2>&1
  local rc=$?
  t1=$(date +%s)
  IMPORT_SECONDS=$(( t1 - t0 ))
  echo "import_seconds=$IMPORT_SECONDS" > "$OUT/timings.env"
  [ $rc -ne 0 ] && fail "造数失败（rc=$rc），见 $OUT/seed-scale.log"
  log "造数完成：${IMPORT_SECONDS}s"

  # 3.3 数据性质核对：不允许非 SIMULATED 行存在
  local nature_rows
  nature_rows=$(psql_db -Atc "SELECT string_agg(data_nature || '=' || c, ', ') FROM (
      SELECT data_nature, count(*) AS c FROM qry_achievement WHERE generation = '$GENERATION'
      GROUP BY data_nature) s" 2>/dev/null)
  log "data_nature 分布：$nature_rows"
  if echo "$nature_rows" | grep -q -v '^\s*SIMULATED='; then
    echo "$nature_rows" | tr ',' '\n' | grep -v 'SIMULATED=' | grep -q '[A-Z]' \
      && fail "数据性质核对失败：存在非 SIMULATED 行（$nature_rows）。合成数据不得冒充真实收录" 4
  fi

  # 3.4 建索引（单独计时，单独记日志）
  t0=$(date +%s)
  psql_db -v ON_ERROR_STOP=1 -q -v mode=restore -f "$ROOT/tests/perf/scale/pg-index-toggle.sql" \
    > "$OUT/index-create.log" 2>&1 || fail "建索引失败，见 $OUT/index-create.log"
  INDEX_SECONDS=$(( $(date +%s) - t0 ))
  echo "index_seconds=$INDEX_SECONDS" >> "$OUT/timings.env"
  log "索引构建完成：${INDEX_SECONDS}s"

  # 3.5 结构夹具（环/链/等长路径/树/孤立/枢纽/高作者数）
  t0=$(date +%s)
  psql_db -v ON_ERROR_STOP=1 -q -v gen="$GENERATION" \
    -f "$ROOT/tests/perf/scale/fixtures-graph.sql" > "$OUT/fixtures.log" 2>&1 \
    || fail "夹具造数失败，见 $OUT/fixtures.log"
  FIXTURE_SECONDS=$(( $(date +%s) - t0 ))
  echo "fixture_seconds=$FIXTURE_SECONDS" >> "$OUT/timings.env"
  log "结构夹具完成：${FIXTURE_SECONDS}s"
}

# ---------------------------------------------------------------------------
# 4. 计数与体积
# ---------------------------------------------------------------------------
counts() {
  log "=== 分层计数与体积 ==="
  psql_db -v gen="$GENERATION" -f "$ROOT/tests/perf/scale/counts-scale.sql" \
    > "$OUT/counts.txt" 2>&1 || fail "计数失败，见 $OUT/counts.txt"
  # 数据库级体积（表 + 索引 + WAL + 膨胀后）
  {
    echo "--- 数据库体积 ---"
    psql_db -Atc "SELECT pg_size_pretty(pg_database_size(current_database()))"
    echo "--- 库目录 du ---"
    du -sh "$PGDATA/base" 2>/dev/null || true
    echo "--- 磁盘可用 ---"
    df -BG --output=avail,used,pcent /
  } >> "$OUT/counts.txt" 2>&1
  grep -E "成果-投影行数|学者-去重 scholar_id（合格关系）|作者关系行|合作边行|领域归属行|发生机构行" \
    "$OUT/counts.txt" | tee -a "$LOG"
}

# ---------------------------------------------------------------------------
# 5. 图投影：导出 → 批量装载
# ---------------------------------------------------------------------------
graph_projection() {
  if [ "$SKIP_GRAPH" = "1" ]; then
    log "=== 跳过图投影（--skip-graph）==="
    return 0
  fi
  if [ -z "$NEO4J_PASSWORD" ]; then
    fail "图投影需要 CN_TEST_NEO4J_PASSWORD（Neo4j 最小口令长度 8）。不想跑图库请加 --skip-graph" 2
  fi
  if ! curl --noproxy '*' -sf -m 3 "http://${PG_HOST}:${NEO4J_HTTP_PORT}/" >/dev/null 2>&1; then
    fail "Neo4j 未在 ${PG_HOST}:${NEO4J_HTTP_PORT} 就绪（bash $CN_RT/bin/up.sh）" 2
  fi

  log "=== 图投影导出（PG → TSV）==="
  local gdir="$OUT/graph-source"
  mkdir -p "$gdir"
  local t0 t1
  t0=$(date +%s)
  for part in works scholars authored cooperated; do
    psql_db -q -v gen="$GENERATION" -v part="$part" \
      -f "$ROOT/tests/perf/scale/export-graph-source.sql" > "$gdir/$part.tsv" 2> "$gdir/$part.err" \
      || fail "导出 $part 失败，见 $gdir/$part.err"
    log "  导出 $part：$(wc -l < "$gdir/$part.tsv") 行，$(du -h "$gdir/$part.tsv" | cut -f1)"
  done
  EXPORT_SECONDS=$(( $(date +%s) - t0 ))
  echo "graph_export_seconds=$EXPORT_SECONDS" >> "$OUT/timings.env"
  log "导出完成：${EXPORT_SECONDS}s"

  log "=== 图投影批量装载（Neo4j）==="
  t0=$(date +%s)
  node "$ROOT/tests/perf/neo4j-project-load.mjs" \
    --uri="http://${PG_HOST}:${NEO4J_HTTP_PORT}" \
    --user="$NEO4J_USER" --password="$NEO4J_PASSWORD" \
    --database="${CN_NEO4J_DATABASE:-neo4j}" \
    --dir="$gdir" --gen="$GENERATION" --scope="$GRAPH_SCOPE" \
    --batch="${GRAPH_BATCH:-5000}" --clean \
    --graph-dir="${NEO4J_HOME:-$CN_RT/neo4j-rootfs/var/lib/neo4j}/data/databases" \
    --projected-version=1 --catalog-revision=1 \
    > "$OUT/graph-load.log" 2>&1
  local rc=$?
  PROJECTION_SECONDS=$(( $(date +%s) - t0 ))
  echo "graph_projection_seconds=$PROJECTION_SECONDS" >> "$OUT/timings.env"
  [ $rc -ne 0 ] && fail "图投影装载失败（rc=$rc），见 $OUT/graph-load.log"
  log "图投影装载完成：${PROJECTION_SECONDS}s"
  grep -E "Scholar=|store 体积|合计" "$OUT/graph-load.log" | tee -a "$LOG"
}

# ---------------------------------------------------------------------------
# 6. 执行计划采集（SQL EXPLAIN + Cypher PROFILE）
# ---------------------------------------------------------------------------
explain() {
  log "=== 执行计划采集 ==="
  bash "$ROOT/tests/perf/explain-graph-and-search.sh" \
    --out="$OUT/plans" --gen="$GENERATION" --db="$DB" \
    $( [ "$SKIP_GRAPH" = 1 ] && echo --no-cypher ) >> "$LOG" 2>&1 \
    || warn "计划采集有失败项，见 $OUT/plans/"
  log "计划已写入 $OUT/plans/"
}

# ---------------------------------------------------------------------------
# 7. 服务起停
# ---------------------------------------------------------------------------
QUERY_PID_FILE="$OUT/query.pid"
CORE_PID_FILE="$OUT/core.pid"

start_services() {
  if [ "$SKIP_SERVICES" = "1" ]; then
    log "=== 跳过服务启动（--skip-services），假定 $QUERY_PORT 上已有服务 ==="
    SERVICES_ALREADY_UP=1
    return 0
  fi
  [ -f "$QUERY_JAR" ] \
    || fail "缺少 query-service jar：先在主工作区跑 ./mvnw -B -ntp -pl services/query-service -am package -DskipTests" 2
  [ -f "$CORE_JAR" ] \
    || fail "缺少 core-service jar（公开屏障需要 core 汇报权威水位）" 2
  [ -f "$CORE_JAR" ] \
    || fail "缺少 core-service jar（公开屏障需要 core 汇报权威水位）：先在主工作区跑 ./mvnw -B -ntp -pl services/core-service -am package -DskipTests" 2

  log "=== 启动 core($CORE_PORT) + query($QUERY_PORT)，库=$DB ==="
  nohup java -Duser.timezone=Asia/Shanghai \
    -jar "$CORE_JAR" \
    --server.port="$CORE_PORT" \
    --spring.datasource.url="jdbc:postgresql://${PG_HOST}:${PG_IT_PORT}/core_perf" \
    --spring.datasource.username="$PG_USER" --spring.datasource.password="${PG_PASSWORD:-}" \
    --spring.flyway.url="jdbc:postgresql://${PG_HOST}:${PG_IT_PORT}/core_perf" \
    --spring.flyway.user="$PG_USER" --spring.flyway.password="${PG_PASSWORD:-}" \
    --cn.outbox.dispatcher-enabled=false \
    > "$OUT/core.log" 2>&1 &
  echo $! > "$CORE_PID_FILE"

  local graph_env=()
  if [ "$GRAPH_MODE" != "POSTGRES_ONLY" ]; then
    graph_env=(
      CN_QUERY_GRAPH_ENABLED=true
      CN_QUERY_GRAPH_URI="bolt://${PG_HOST}:${NEO4J_BOLT_PORT}"
      CN_QUERY_GRAPH_USER="$NEO4J_USER"
      CN_QUERY_GRAPH_PASSWORD="$NEO4J_PASSWORD"
      CN_QUERY_GRAPH_SCOPE="$GRAPH_SCOPE"
      CN_QUERY_GRAPH_QUERY_MODE="$GRAPH_MODE"
    )
    log "图查询模式：$GRAPH_MODE（bolt://${PG_HOST}:${NEO4J_BOLT_PORT}）"
  fi

  env CN_CORE_BASE_URL="http://${PG_HOST}:${CORE_PORT}" "${graph_env[@]}" \
    nohup java -Duser.timezone=Asia/Shanghai \
    -Xmx${QUERY_HEAP:-768m} \
    -jar "$QUERY_JAR" \
    --server.port="$QUERY_PORT" \
    --spring.datasource.url="jdbc:postgresql://${PG_HOST}:${PG_IT_PORT}/$DB" \
    --spring.datasource.username="$PG_USER" --spring.datasource.password="${PG_PASSWORD:-}" \
    --spring.flyway.url="jdbc:postgresql://${PG_HOST}:${PG_IT_PORT}/$DB" \
    --spring.flyway.user="$PG_USER" --spring.flyway.password="${PG_PASSWORD:-}" \
    --cn.projection.worker-enabled=false \
    >> "$OUT/query.log" 2>&1 &
  echo $! > "$QUERY_PID_FILE"

  wait_http "http://${PG_HOST}:${CORE_PORT}/actuator/health" "core" 180 || return 1
  wait_http "http://${PG_HOST}:${QUERY_PORT}/actuator/health" "query" 240 || return 1
}

wait_http() {
  local url="$1" name="$2" limit="${3:-90}"
  for _ in $(seq 1 "$limit"); do
    if curl --noproxy '*' -sf -m 2 "$url" >/dev/null 2>&1; then
      log "  $name 就绪"
      return 0
    fi
    sleep 1
  done
  warn "  $name 在 ${limit}s 内未就绪，日志见 $OUT"
  return 1
}

stop_services() {
  for pair in "query:$QUERY_PID_FILE" "core:$CORE_PID_FILE"; do
    local name="${pair%%:*}" pidf="${pair#*:}"
    [ -f "$pidf" ] || continue
    local pid; pid=$(cat "$pidf")
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null
      for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
      kill -9 "$pid" 2>/dev/null
    fi
    rm -f "$pidf"
    log "  $name 已停止"
  done
}

# ---------------------------------------------------------------------------
# 8. 并发压测（10 / 30 / 50 / 100 活跃用户）
# ---------------------------------------------------------------------------
load() {
  log "=== 并发压测：VUS=$VUS_LIST（预热 ${WARMUP}s，稳态 ${SECONDS_STEADY}s，超时 ${TIMEOUT_MS}ms）==="
  curl --noproxy '*' -sf -m 3 "http://${PG_HOST}:${QUERY_PORT}/actuator/health" >/dev/null \
    || fail "query 服务未就绪，无法压测" 2

  local vus
  IFS=',' read -r -a arr <<< "$VUS_LIST"
  for vus in "${arr[@]}"; do
    log "--- vus=$vus ---"
    start_sampler "load-$vus"
    node "$ROOT/tests/perf/load.mjs" \
      --vus="$vus" --warmup="$WARMUP" --seconds="$SECONDS_STEADY" \
      --think="$THINK" --timeout="$TIMEOUT_MS" \
      --query="http://${PG_HOST}:${QUERY_PORT}" --core="http://${PG_HOST}:${CORE_PORT}" \
      --nature="${LOAD_NATURE:-SIMULATED}" --maxId="$N" \
      --scale="$STAGE" --query-mode="$GRAPH_MODE" \
      ${CACHE_MODE:+--cache="$CACHE_MODE"} \
      --out="$OUT/load-$vus.json" > "$OUT/load-$vus.log" 2>&1
    local rc=$?
    stop_sampler
    summarize_resources "$OUT/resources-load-$vus.tsv" | tee -a "$LOG"
    if [ $rc -ne 0 ]; then
      warn "load vus=$vus 退出码 $rc，见 $OUT/load-$vus.log"
    else
      grep -E "^稳态 " "$OUT/load-$vus.log" | tee -a "$LOG"
    fi
    # 每轮之间留出安静期，避免上一轮的队列/连接回收影响下一轮
    sleep 15
  done
}

# ---------------------------------------------------------------------------
# 9. 汇总
# ---------------------------------------------------------------------------
summarize() {
  log "=== 汇总 ==="
  local out="$OUT/summary.md"
  {
    echo "# 阶段六容量压测：$STAGE"
    echo
    echo "- 时间：$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "- 机器：$(nproc) 核 / $(awk '/^MemTotal:/{printf "%.1f", $2/1048576}' /proc/meminfo) GiB / Swap $(awk '/^SwapTotal:/{printf "%.1f", $2/1048576}' /proc/meminfo) GiB"
    echo "- 数据：$STAGE 随机成果 n=$N + 结构夹具（全部 SIMULATED），generation=$GENERATION"
    echo "- 库：$DB @ ${PG_HOST}:${PG_IT_PORT}"
    echo "- 图查询模式：$GRAPH_MODE$([ "$SKIP_GRAPH" = 1 ] && echo '（图投影已跳过）')"
    echo
    echo "## 预检"
    echo '```'
    cat "$OUT/preflight.env" 2>/dev/null
    echo '```'
    echo
    echo "## 容量精算（capacity-model.mjs，全量表格见 capacity-model.md）"
    echo '```'
    grep -E "主机读数|^阶段 |^1万 |^10万 |^100万 |^500万 |作者数/成果|自检结论" \
      "$OUT/capacity-model.md" 2>/dev/null | head -20
    echo '```'
    echo
    echo "## 耗时（秒）"
    echo '```'
    cat "$OUT/timings.env" 2>/dev/null
    echo '```'
    echo
    echo "## 实体数与体积"
    echo '```'
    grep -E "成果-投影行数|成果-公开且生效|成果-数据性质|学者-去重|作者关系行|作者关系-合格|作者关系-单作者|作者关系-作者数|合作边行|合作边-不同作者对|合作边-位于公开|领域归属行|发生机构行|数据库体积" \
      "$OUT/counts.txt" 2>/dev/null
    echo
    sed -n '/table_name/,/^$/p' "$OUT/counts.txt" 2>/dev/null | head -20
    echo '```'
    echo
    echo "## 图投影"
    echo '```'
    grep -E "Scholar=|store 体积|合计|行每秒" "$OUT/graph-load.log" 2>/dev/null
    echo '```'
    echo
    for f in "$OUT"/load-*.json; do
      [ -f "$f" ] || continue
      echo "## 并发：$(basename "$f" .json)"
      echo '```'
      node -e '
        const r = require(process.argv[1]);
        console.log(`活跃用户 ${r.vus}，稳态 ${r.measuredSteadySeconds}s，请求 ${r.steadyRequests}，RPS ${r.steadyRps}，成功率 ${(r.overallSuccessRate*100).toFixed(1)}%，峰值在途 ${r.peakInFlight}`);
        console.log("接口".padEnd(46) + "请求".padStart(8) + "成功率".padStart(9) + "P50".padStart(9) + "P95".padStart(9) + "P99".padStart(9));
        for (const e of r.endpoints) console.log(e.endpoint.padEnd(46) + String(e.requests).padStart(8) + String(e.successRate===null?"-":(e.successRate*100).toFixed(1)+"%").padStart(9) + String(e.p50).padStart(9) + String(e.p95).padStart(9) + String(e.p99).padStart(9));
      ' "$f"
      echo '```'
      echo
    done
    echo "## 资源峰值（每秒采样）"
    echo '```'
    for f in "$OUT"/resources-*.tsv; do
      [ -f "$f" ] || continue
      echo "$(basename "$f" .tsv)："
      summarize_resources "$f"
    done
    echo '```'
  } > "$out"
  log "汇总：$out"
}

# ---------------------------------------------------------------------------
# 9.5 DRY-RUN：只算容量与可行性，不连数据库
#
# 设计上故意**不连接** PG/Neo4j：本机 55432/17687 可能正在跑集成测试，一条看似无害的
# 连接也会抢连接槽与缓存。要探数据库就用 --only=preflight。
# ---------------------------------------------------------------------------
dry_run() {
  log "=== DRY-RUN：stage=$STAGE only=$ONLY（不连库、不造数、不启服务）==="
  local missing=()
  for tool in node java curl df awk; do
    command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
  done
  [ -x "$PSQL" ] || missing+=("psql(路径 $PGBIN)")
  if [ "${#missing[@]}" -gt 0 ]; then
    warn "缺少工具：${missing[*]}（完成所有步骤前必须装好；不影响本次计划输出）"
  else
    log "工具链就绪：node/java/curl/psql/df/awk"
  fi

  log "阶段参数：n=$N 学者池=$POOL 库=$DB 生成=$GENERATION 种子=$SEED"
  log "目标：PG ${PG_HOST}:${PG_IT_PORT}（库 $DB）、Neo4j http://${PG_HOST}:${NEO4J_HTTP_PORT} bolt://${PG_HOST}:${NEO4J_BOLT_PORT}"
  log "图查询模式：$GRAPH_MODE$([ "$SKIP_GRAPH" = 1 ] && echo '（--skip-graph：不装图）')"
  log "压测：VUS=$VUS_LIST 预热=${WARMUP}s 稳态=${SECONDS_STEADY}s 思考=${THINK}ms 超时=${TIMEOUT_MS}ms"
  log "报告目录：$OUT"

  local qjar="${QUERY_JAR:-（未构建）}" cjar="${CORE_JAR:-（未构建）}"
  log "query jar：$qjar"
  log "core  jar：$cjar"
  [ -f "${QUERY_JAR:-/nonexistent}" ] || warn "query jar 不存在：--only=all/load 会在 start_services 阶段失败（先 ./mvnw -pl services/query-service -am package -DskipTests）"
  [ -f "${CORE_JAR:-/nonexistent}" ] || warn "core jar 不存在：公开屏障需要它在线"

  # --- 容量门禁：与 CI 工作流完全同一份口径 ---
  local gate_rc=0
  node "$ROOT/tests/perf/capacity-model.mjs" \
    --avail-gb=auto --mem-gb=auto --disk-path="$DISK_PATH" \
    --disk-headroom-gb="$DISK_HEADROOM_GB" --require-scale="$STAGE" \
    --json="$OUT/capacity-model.json" --markdown="$OUT/capacity-model.md" \
    2>&1 | tee -a "$LOG"
  gate_rc=${PIPESTATUS[0]}

  case "$gate_rc" in
    0) log "门禁通过：本机容量足够跑 $STAGE" ;;
    3) fail "容量模型标定自检超差（退出码 3）：模型与 2026-10-17 实测基线已漂移，先重测基线再外推" 3 ;;
    4)
      if [ "$FORCE" = "1" ]; then
        [ -n "$FORCE_REASON" ] || fail "--force 必须同时给 --force-reason，否则越线无可追溯" 3
        warn "门禁阻断，但已 --force 越线预览：$FORCE_REASON"
      else
        warn "门禁阻断：本机无法承载 $STAGE。加 --force --force-reason=... 仅用于预览计划；实际压测请换更大规格主机"
        log "完成。输出目录：$OUT"
        return 4
      fi
      ;;
    *) fail "容量模型异常退出（rc=$gate_rc）" 3 ;;
  esac

  log "预检参数：磁盘路径 $DISK_PATH 可用 $(df -BG --output=avail "$DISK_PATH" | tail -1 | tr -d ' ')；MemAvailable $(awk '/^MemAvailable:/{print int($2/1024)}' /proc/meminfo) MB；Swap $(awk '/^SwapTotal:/{print int($2/1024)}' /proc/meminfo) MB"
  log "将要执行（--only=all）：preflight → ensure_database → seed → counts → explain → graph_projection → start_services → load → summarize"
  log "  1) psql_drop_index  : -f tests/perf/scale/pg-index-toggle.sql -v mode=drop"
  log "  2) seed             : -f tests/perf/scale/seed-scale-query.sql -v n=$N -v gen=$GENERATION -v pool=$POOL -v seed=$SEED"
  log "  3) index rebuild    : -f tests/perf/scale/pg-index-toggle.sql -v mode=restore"
  log "  4) fixtures         : -f tests/perf/scale/fixtures-graph.sql -v gen=$GENERATION"
  log "  5) counts           : -f tests/perf/scale/counts-scale.sql -v gen=$GENERATION"
  log "  6) explain          : bash tests/perf/explain-graph-and-search.sh --db=$DB --gen=$GENERATION"
  if [ "$SKIP_GRAPH" = "1" ]; then
    log "  7) graph            : （跳过）"
  else
    log "  7) graph            : node tests/perf/neo4j-project-load.mjs --dir=$OUT/graph-source --gen=$GENERATION --clean"
  fi
  log "  8) load             : node tests/perf/load.mjs --vus=$VUS_LIST --scale=$STAGE --query-mode=$GRAPH_MODE"
  log "所有造数语句带 data_nature='SIMULATED'；报告会分开写「随机部分 n」与「结构夹具」"
  log "DRY-RUN 通过（未连接任何数据库）。输出目录：$OUT"
  return 0
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
log "阶段六容量压测：stage=$STAGE only=$ONLY 输出=$OUT$([ "$DRY_RUN" = "1" ] && echo '（DRY-RUN）')"

if [ "$DRY_RUN" = "1" ]; then
  dry_run
  exit $?
fi

case "$ONLY" in
  preflight)
    preflight
    ;;
  seed|counts|explain|graph|load)
    preflight
    ensure_database
    start_sampler "pipeline"
    case "$ONLY" in
      seed) seed ;;
      counts) counts ;;
      explain) explain ;;
      graph) graph_projection ;;
      load) load ;;
    esac
    stop_sampler
    summarize_resources "$OUT/resources-pipeline.tsv" | tee -a "$LOG"
    summarize
    ;;
  all)
    preflight
    ensure_database
    start_sampler "pipeline"
    seed
    counts
    explain
    graph_projection
    start_services
    load
    summarize
    summarize_resources "$OUT/resources-pipeline.tsv" | tee -a "$LOG"
    stop_sampler
    stop_services
    ;;
  *)
    echo "未知 --only=$ONLY（preflight|seed|counts|explain|graph|load|all）" >&2
    exit 2
    ;;
esac

log "完成。输出目录：$OUT"
