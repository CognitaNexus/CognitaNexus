#!/usr/bin/env bash
#
# 安装数据库备份：脚本 + systemd 定时器。幂等，可反复执行。
#
# 为什么不塞进 install-server.sh：
#   install-server.sh 是「首次安装」，会 DROP SCHEMA 并恢复 dump；备份属于日常运维，
#   需要能在不动数据的前提下单独更新。CI 每次部署也会调本脚本，保证服务器上的备份脚本
#   与仓库一致（否则改了脚本不生效，跟 deploy-update.sh 原来的坑一样）。
#
# 用法：bash install-backup.sh [--run-now]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CN_USER="${SUDO_USER:-$(whoami)}"
CN_HOME="$(getent passwd "$CN_USER" | cut -d: -f6)/cn"
DEPLOY_DIR="$(getent passwd "$CN_USER" | cut -d: -f6)/cn-deploy"

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

log "安装备份脚本到 $DEPLOY_DIR/pg-backup.sh"
install -d -m 755 "$DEPLOY_DIR"
install -m 755 "$SCRIPT_DIR/pg-backup.sh" "$DEPLOY_DIR/pg-backup.sh"

log "安装 systemd 单元"
for u in "$SCRIPT_DIR"/systemd/cn-db-backup.service "$SCRIPT_DIR"/systemd/cn-db-backup.timer; do
  [ -f "$u" ] || { echo "缺少 $(basename "$u")" >&2; exit 1; }
  sed -e "s|User=ubuntu|User=$CN_USER|" \
      -e "s|Group=ubuntu|Group=$CN_USER|" \
      -e "s|/home/ubuntu/cn|$CN_HOME|g" "$u" \
    | sudo tee "/etc/systemd/system/$(basename "$u")" >/dev/null
done
sudo systemctl daemon-reload

# enable --now 对 timer 是幂等的：已启用就是空操作，否则立刻登记并计算下次触发。
sudo systemctl enable --now cn-db-backup.timer >/dev/null
echo "已启用 cn-db-backup.timer"
# 不用 head 截断：它关闭管道会让上游 systemctl 收到 SIGPIPE，pipefail 下整条命令算失败。
systemctl list-timers cn-db-backup.timer --no-pager | sed -n '1,3p' || true

if [ "${1:-}" = "--run-now" ]; then
  log "立刻执行一次备份（验证脚本可用）"
  sudo systemctl start cn-db-backup.service
  sudo systemctl status cn-db-backup.service --no-pager -n 20 || true
  echo "详细输出：tail -n 40 $CN_HOME/logs/db-backup.log"
fi
