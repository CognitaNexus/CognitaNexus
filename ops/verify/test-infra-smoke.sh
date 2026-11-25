#!/usr/bin/env bash
# 隔离测试基础设施冒烟检查（PostgreSQL 16 + Redis 8 + Neo4j 5.26）。
#
# 目的：把「测试基础设施真的起来了、三个依赖真的能连」变成一条可重复执行、退出码可信的命令，
# 而不是每次手工 docker exec 试一遍。它不做业务写入，不碰开发库，也不删除数据卷。
#
# 用法：
#   export CN_TEST_PG_SUPERUSER_PASSWORD='...'
#   export CN_TEST_REDIS_PASSWORD='...'
#   export CN_TEST_NEO4J_PASSWORD='...'     # 至少 8 位
#   bash ops/verify/test-infra-smoke.sh up       # 起服务 + 等健康 + 三项连通性检查
#   bash ops/verify/test-infra-smoke.sh check    # 只检查已在运行的服务
#   bash ops/verify/test-infra-smoke.sh down     # 停止，保留数据卷
#   bash ops/verify/test-infra-smoke.sh purge --yes   # 停止并删除测试数据卷（不可恢复）
#
# 可用环境变量覆盖端口：CN_TEST_PG_PORT / CN_TEST_REDIS_PORT / CN_TEST_NEO4J_BOLT_PORT 等，
# 与 ops/compose/compose.test-infra.yaml 使用同一组变量名。
#
# 注意：本机 Docker 不可用时（例如 WSL 被安全策略拦截）脚本以退出码 2 明确报告「未执行」，
# 不伪装成通过。Neo4j 集成测试的默认连接参数见 Neo4jSchemaIT。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/ops/compose/compose.test-infra.yaml"
ACTION="${1:-up}"

log() { printf '[test-infra] %s\n' "$*"; }
fail() {
  printf '[test-infra] 失败：%s\n' "$*" >&2
  exit 1
}

require_docker() {
  command -v docker >/dev/null 2>&1 || fail "未找到 docker 命令"
  if ! docker info >/dev/null 2>&1; then
    printf '[test-infra] 未执行：当前用户无法连接 Docker（检查 docker 服务或权限）。\n' >&2
    exit 2
  fi
}

require_password() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    fail "必须设置 ${name}（测试实例同样不允许空口令）"
  fi
}

require_env() {
  if [[ "${ACTION}" == "down" || "${ACTION}" == "purge" ]]; then
    # 停止不需要口令。但 docker compose 仍会解析文件里的 ${VAR:?}，缺失时会直接报错，
    # 因此这里给一个仅用于解析文件的占位值（不会用于认证，也不会写入任何数据）。
    export CN_TEST_PG_SUPERUSER_PASSWORD="${CN_TEST_PG_SUPERUSER_PASSWORD:-unused-for-parse}"
    export CN_TEST_REDIS_PASSWORD="${CN_TEST_REDIS_PASSWORD:-unused-for-parse}"
    export CN_TEST_NEO4J_PASSWORD="${CN_TEST_NEO4J_PASSWORD:-unused-for-parse}"
    return
  fi
  require_password CN_TEST_PG_SUPERUSER_PASSWORD
  require_password CN_TEST_REDIS_PASSWORD
  require_password CN_TEST_NEO4J_PASSWORD
  if (( ${#CN_TEST_NEO4J_PASSWORD} < 8 )); then
    fail "CN_TEST_NEO4J_PASSWORD 至少 8 位（Neo4j 的最小口令长度）"
  fi
}

compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

wait_for_healthy() {
  local timeout_seconds="${CN_TEST_HEALTH_TIMEOUT:-180}"
  local deadline=$((SECONDS + timeout_seconds))
  local services=(postgres redis neo4j)
  while (( SECONDS < deadline )); do
    local all_healthy=1
    for service in "${services[@]}"; do
      local container
      container="$(compose ps -q "${service}" 2>/dev/null || true)"
      if [[ -z "${container}" ]]; then
        all_healthy=0
        continue
      fi
      local status
      status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container}" 2>/dev/null || echo unknown)"
      if [[ "${status}" != "healthy" ]]; then
        all_healthy=0
        log "等待 ${service}：${status}"
        break
      fi
    done
    if (( all_healthy == 1 )); then
      log "三个基础设施服务均为 healthy"
      return 0
    fi
    sleep 5
  done
  compose ps || true
  fail "等待健康检查超时（${timeout_seconds}s）；用 docker compose -f ${COMPOSE_FILE} logs 查看原因"
}

check_pg() {
  local user="${CN_TEST_PG_SUPERUSER:-postgres}"
  compose exec -T postgres pg_isready -U "${user}" -d postgres >/dev/null ||
    fail "PostgreSQL 不健康"
  log "PostgreSQL 就绪：127.0.0.1:${CN_TEST_PG_PORT:-55432}"
}

check_redis() {
  local pong
  pong="$(compose exec -T redis sh -c 'redis-cli -a "$CN_TEST_REDIS_PASSWORD" --no-auth-warning ping' 2>/dev/null | tr -d '\r')"
  [[ "${pong}" == "PONG" ]] || fail "Redis 不健康（期望 PONG，实际 ${pong:-空}）"
  log "Redis 就绪：127.0.0.1:${CN_TEST_REDIS_PORT:-56379}"
}

check_neo4j() {
  local user="${CN_TEST_NEO4J_USER:-neo4j}"
  local result
  result="$(
    compose exec -T neo4j cypher-shell \
      -a bolt://127.0.0.1:7687 \
      -u "${user}" -p "${CN_TEST_NEO4J_PASSWORD}" \
      'RETURN 1 AS ok' 2>/dev/null | tr -d '\r'
  )"
  [[ "${result}" == *"1"* ]] || fail "Neo4j 不健康（RETURN 1 未返回 1）"
  log "Neo4j 就绪：bolt://127.0.0.1:${CN_TEST_NEO4J_BOLT_PORT:-17687}"
  log "提示：图库 schema 由 query-service 启动或 Neo4jSchemaIT 幂等创建；本脚本只验证连通性。"
}

case "${ACTION}" in
  up)
    require_docker
    require_env
    compose up -d
    wait_for_healthy
    check_pg
    check_redis
    check_neo4j
    log "完成。接下来可运行："
    log "  sh ./mvnw -B -pl services/query-service -am verify -Dcn.it.skip=false -Dit.test=Neo4jSchemaIT -Dfailsafe.failIfNoSpecifiedTests=false"
    ;;
  check)
    require_docker
    require_env
    check_pg
    check_redis
    check_neo4j
    ;;
  down)
    require_docker
    compose down
    log "已停止，数据卷保留（再次 up 会继续使用原数据）"
    ;;
  purge)
    require_docker
    [[ "${2:-}" == "--yes" ]] || fail "purge 会删除测试数据卷，必须显式加 --yes"
    compose down -v
    log "已停止并删除测试数据卷"
    ;;
  *)
    fail "未知动作：${ACTION}（可用：up / check / down / purge --yes）"
    ;;
esac
