#!/usr/bin/env bash
#
# 更新已部署实例的代码，不动数据库。
#
# 与 install-server.sh 的分工必须守住：
#   install-server.sh  首次安装，会 DROP SCHEMA 并恢复 dump
#   deploy-update.sh   只换 jar 与前端产物，然后重启
# CI/CD 只能调用后者。误调前者的后果是把生产数据回滚到打包那一刻。
#
# 用法：bash deploy-update.sh <新产物目录>
#   新产物目录里应有 app/*.jar 与 web/（前端 dist 内容）
set -euo pipefail

CN_USER="${SUDO_USER:-$(whoami)}"
CN_HOME="$(getent passwd "$CN_USER" | cut -d: -f6)/cn"
SRC="${1:-}"
[ -n "$SRC" ] || { echo "用法: bash deploy-update.sh <产物目录>" >&2; exit 1; }
[ -d "$SRC" ] || { echo "目录不存在: $SRC" >&2; exit 1; }

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

log "备份当前产物"
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$CN_HOME/backup/$STAMP"
cp -f "$CN_HOME"/app/*.jar "$CN_HOME/backup/$STAMP/" 2>/dev/null || true
[ -d /var/www/cn ] && cp -r /var/www/cn "$CN_HOME/backup/$STAMP/web"
echo "已备份到 $CN_HOME/backup/$STAMP"
# 只保留最近 5 次，避免磁盘被 jar 堆满（三个 jar 约 140M/次）。
ls -1dt "$CN_HOME"/backup/* 2>/dev/null | tail -n +6 | xargs -r rm -rf

log "替换产物"
for j in core-service query-service ingestion-service; do
  [ -f "$SRC/app/$j.jar" ] || { echo "缺少 app/$j.jar" >&2; exit 1; }
  cp -f "$SRC/app/$j.jar" "$CN_HOME/app/$j.jar"
done
if [ -d "$SRC/web" ]; then
  # 前端放在 /var/www/cn（家目录 750，nginx 读不到）。
  # 先铺到临时目录再整体替换，避免替换到一半 nginx 拿到半套文件。
  sudo rm -rf /var/www/cn.new
  sudo install -d -m 755 /var/www/cn.new
  sudo cp -r "$SRC/web"/. /var/www/cn.new/
  sudo chmod -R a+rX /var/www/cn.new
  sudo rm -rf /var/www/cn
  sudo mv /var/www/cn.new /var/www/cn
fi
echo "产物已替换"

log "重启服务（按依赖顺序：core 先起，就绪后再起另外两个）"
sudo systemctl restart cn-core
for i in $(seq 1 45); do
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://127.0.0.1:8081/actuator/health || true)"
  [ "$code" = "200" ] && break
  sleep 2
done
sudo systemctl restart cn-query cn-ingestion
sleep 15

log "自检"
fail=0
for p in 8081 8082 8083; do
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:$p/actuator/health" || true)"
  printf '%s %s\n' "$p" "$code"
  [ "$code" = "200" ] || fail=1
done
code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 'http://127.0.0.1/query/api/v1/public/search/achievements?page=1&size=1' || true)"
printf '经 nginx 的公开检索 %s\n' "$code"
[ "$code" = "200" ] || fail=1

if [ "$fail" -eq 0 ]; then
  echo "更新完成"
else
  echo "自检未全通过，可用 $CN_HOME/backup/$STAMP 回滚" >&2
  exit 1
fi
