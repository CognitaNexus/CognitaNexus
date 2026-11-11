#!/usr/bin/env bash
# 容器首启时的数据库初始化（挂载为 /docker-entrypoint-initdb.d/init-databases.sh）。
#
# 依据：架构设计说明书 §4.1（三服务独立数据库、独立账号、独立迁移历史、禁止跨库）
#       与 §12.1（迁移账号与运行账号权限分离）。
#
# 为什么用 shell 而不是 .sql：口令必须来自环境变量，不能写死在仓库里。docker-entrypoint
# 会把容器环境变量传给脚本，而 .sql 文件无法读取它们（psql 的 -v 变量需要定制调用方式）。
#
# 只在数据卷为空的首次启动时执行一次。改这里的角色或口令不会作用于已存在的数据卷——
# 那种情况下需要显式迁移，静默改口令会让已有会话失效而这看起来像「登录突然坏了」。

set -euo pipefail

CORE_OWNER_PW="${CN_CORE_DB_OWNER_PASSWORD:-cn_core_owner_dev}"
CORE_APP_PW="${CN_CORE_DB_PASSWORD:-cn_core_app_dev}"
INGESTION_OWNER_PW="${CN_INGESTION_DB_OWNER_PASSWORD:-cn_ingestion_owner_dev}"
INGESTION_APP_PW="${CN_INGESTION_DB_PASSWORD:-cn_ingestion_app_dev}"
QUERY_OWNER_PW="${CN_QUERY_DB_OWNER_PASSWORD:-cn_query_owner_dev}"
QUERY_APP_PW="${CN_QUERY_DB_PASSWORD:-cn_query_app_dev}"

psql_do() {
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -c "$1"
}

echo "[init-databases] 创建角色"

# 角色：owner 拥有 DDL，app 只有 DML。两者分离，是为了让「服务运行时账号无法改表结构」
# 成为数据库层面的强制约束，而不是靠代码不写 DDL 来自律。
for pair in \
  "core_owner:${CORE_OWNER_PW}" \
  "core_app:${CORE_APP_PW}" \
  "ingestion_owner:${INGESTION_OWNER_PW}" \
  "ingestion_app:${INGESTION_APP_PW}" \
  "query_owner:${QUERY_OWNER_PW}" \
  "query_app:${QUERY_APP_PW}"
do
  role="${pair%%:*}"
  pw="${pair#*:}"

  # 口令含单引号时直接用字面量拼接会破坏 SQL。这里直接拒绝，而不是尝试转义：
  # 一个初始化脚本对复杂口令做半套转义，比明确报错更危险（会得到「角色建好了但口令不是
  # 你以为的那个」，而现象只是登录失败）。
  case "$pw" in
    *"'"*)
      echo "[init-databases] 错误：角色 ${role} 的口令包含单引号，请改用不含单引号的强口令" >&2
      exit 1
      ;;
  esac

  exists="$(psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='${role}'" --username "$POSTGRES_USER" --dbname postgres)"
  if [ -z "$exists" ]; then
    psql_do "CREATE ROLE ${role} LOGIN PASSWORD '${pw}'"
    echo "  已创建 $role"
  else
    echo "  $role 已存在，跳过（不会改写口令）"
  fi
done

echo "[init-databases] 创建数据库并归属迁移账号"

# 每个库的 owner 是迁移账号；运行账号通过 V6/V2 迁移脚本内的 GRANT 获得具体权限，
# 不在初始化阶段一次性授予全表权限——把授权留在迁移历史里，才能随表结构演进被审计。
for spec in "core_db:core_owner:core_app" "ingestion_db:ingestion_owner:ingestion_app" "query_db:query_owner:query_app"
do
  db="${spec%%:*}"
  rest="${spec#*:}"
  owner="${rest%%:*}"
  app="${rest#*:}"

  exists="$(psql -tAc "SELECT 1 FROM pg_database WHERE datname='${db}'" --username "$POSTGRES_USER" --dbname postgres)"
  if [ -z "$exists" ]; then
    psql_do "CREATE DATABASE ${db} OWNER ${owner} ENCODING 'UTF8'"
    echo "  已创建 $db（owner=$owner, app=$app）"
  else
    echo "  $db 已存在，跳过"
  fi

  # 禁止跨库访问的最后一层保障：运行账号只能连自己的库。
  psql_do "REVOKE ALL ON DATABASE ${db} FROM PUBLIC"
  psql_do "GRANT CONNECT ON DATABASE ${db} TO ${owner}"
  psql_do "GRANT CONNECT ON DATABASE ${db} TO ${app}"
done

echo "[init-databases] 完成"
