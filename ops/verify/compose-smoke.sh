#!/usr/bin/env bash
# 编排自检：证明「一份 compose 能把三个服务与它们的基础设施拉起来」。
#
# 依据：NF07「部署与任务恢复说明可复现」、NF04。此前 README 自述「服务器部署未在任何服务器上
# 部署过、compose 未实测」——那份自述是对的状态，但没法回答「到底哪一步会坏」。这个脚本把
# 「能坏在哪」拆成四步，每步单独可判断：
#
#   1. 配置可解析（含必填变量）：docker compose config
#   2. 镜像可构建：三个 Dockerfile 与前端一起构建（--build 只在 --up 时做）
#   3. 容器能起来且健康：等待 healthcheck，而不是 sleep 固定秒数
#   4. 同源入口可用：通过 web 的端口访问前端与三个服务的健康探针
#
# 用法：
#   bash ops/verify/compose-smoke.sh            # 只校验配置（不需要镜像、不启动任何东西）
#   bash ops/verify/compose-smoke.sh --up       # 构建并启动整栈（耗时较长）
#   bash ops/verify/compose-smoke.sh --up --down  # 跑完即停（CI 用）
#
# 退出码：0 通过；2 前置条件缺失；4 某一步失败。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1

UP=0
DOWN=0
for arg in "$@"; do
  case "$arg" in
    --up) UP=1 ;;
    --down) DOWN=1 ;;
    *)
      echo "未知参数：$arg" >&2
      exit 2
      ;;
  esac
done

if ! command -v docker >/dev/null 2>&1; then
  echo "没有 docker；这一步需要能运行容器的环境" >&2
  exit 2
fi

FAILED=0
step() { printf '\n== %s\n' "$1"; }

step "1/4 配置解析"
# .env 缺失时用样例里的必填项兜底：这里校验的是「文件本身自洽」，不是在准备一套真实凭据。
if [ ! -f .env ]; then
  echo "   未发现 .env，使用 .env.example 中的非空引导口令做一次临时校验"
  BOOTSTRAP_PW="$(grep -E '^CN_BOOTSTRAP_ADMIN_PASSWORD=' .env.example | cut -d= -f2-)"
  if [ -z "$BOOTSTRAP_PW" ]; then
    BOOTSTRAP_PW="smoke-only-not-a-real-password"
  fi
  if CN_BOOTSTRAP_ADMIN_PASSWORD="$BOOTSTRAP_PW" docker compose -f compose.yaml -f ops/compose/compose.server.yaml config -q; then
    echo "   compose.yaml + compose.server.yaml 可解析"
  else
    echo "   配置解析失败" >&2
    FAILED=1
  fi
else
  if docker compose -f compose.yaml -f ops/compose/compose.server.yaml config -q; then
    echo "   .env 与两份编排文件可解析"
  else
    echo "   配置解析失败" >&2
    FAILED=1
  fi
fi

if [ "$UP" = "0" ]; then
  step "只做静态校验（加 --up 才会构建并启动）"
  [ "$FAILED" = "0" ] && echo "== 通过" || exit 4
  exit 0
fi

BUILD_ARGS=(-f compose.yaml)
if [ -f .env ]; then
  BUILD_ARGS+=(-f ops/compose/compose.server.yaml)
fi

step "2/4 构建并启动"
# 先确认守护进程在：CLI 存在但 Docker Desktop 的 Linux 引擎未启动时，compose 会报
# 「failed to connect to the docker API」，这与构建失败是两件事，必须分开说。
if ! docker info >/dev/null 2>&1; then
  echo "   Docker 守护进程未运行（CLI 在，但引擎没起）。启动 Docker Desktop 后重试。" >&2
  echo "   这一步不通过就不能声称 compose 已验证——配置能解析不等于能起。" >&2
  exit 4
fi
if ! docker compose "${BUILD_ARGS[@]}" up -d --build; then
  echo "   构建或启动失败：看上面的 build 输出，最常见的是 Maven 依赖拉取失败" >&2
  FAILED=1
fi

step "3/4 等待健康"
# 等的是 healthcheck 的 healthy，不是「容器 running」：running 只说明进程没退，
# 数据库可能还在恢复、服务可能还在跑迁移。
# 期望服务清单必须写死：空输出（容器根本没起来）在「没有 unhealthy 行」的判据下会
# 被当成健康——这个假阳性比不检查更糟，它会让人以为整栈已经起来了。
EXPECTED_SERVICES="postgres redis-session redis-cache core-service ingestion-service query-service web"
healthy=0
output=""
unhealthy=""
for _ in $(seq 1 60); do
  output="$(docker compose "${BUILD_ARGS[@]}" ps --format '{{.Service}} {{.Health}}' 2>/dev/null)"
  unhealthy=""
  for svc in $EXPECTED_SERVICES; do
    line="$(printf '%s\n' "$output" | grep -E "^${svc} " || true)"
    if [ -z "$line" ] || ! printf '%s' "$line" | grep -q "healthy"; then
      unhealthy="${unhealthy}${line:-${svc} 未出现}"$'\n'
    fi
  done
  if [ -z "$unhealthy" ]; then
    healthy=1
    break
  fi
  sleep 5
done
if [ "$healthy" = "1" ]; then
  echo "   全部服务 healthy"
  printf '%s\n' "$output" | sed 's/^/   /'
else
  echo "   仍有服务未 healthy：" >&2
  printf '%s\n' "${unhealthy:-}" | sed 's/^/   /' >&2
  docker compose "${BUILD_ARGS[@]}" ps >&2
  FAILED=1
fi

step "4/4 同源入口探活"
WEB_PORT="${CN_WEB_PORT:-8080}"
probe() {
  local name="$1" url="$2"
  local code
  code="$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$url" 2>/dev/null)"
  if [ "$code" = "200" ]; then
    echo "   ${name} 200"
  else
    echo "   ${name} 返回 ${code:-无响应}" >&2
    FAILED=1
  fi
}
probe "前端首页" "http://127.0.0.1:${WEB_PORT}/"
probe "core 健康" "http://127.0.0.1:${WEB_PORT}/core/actuator/health"
probe "query 健康" "http://127.0.0.1:${WEB_PORT}/query/actuator/health"
probe "ingestion 健康" "http://127.0.0.1:${WEB_PORT}/ingest/actuator/health"

if [ "$DOWN" = "1" ]; then
  step "收尾：停止整栈"
  docker compose "${BUILD_ARGS[@]}" down
fi

if [ "$FAILED" != "0" ]; then
  echo "== 未通过。日志：docker compose logs --tail=100 <service>" >&2
  exit 4
fi
echo "== 通过：compose 能构建、能起、同源入口可用"
