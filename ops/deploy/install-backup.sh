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
# User/Group 一律 root（见单元文件里的说明），这里只替换「产物属主」与路径。
# 注意不能用 s|User=ubuntu|User=$CN_USER|：那会把服务降权到需要口令的账号，备份静默失败。
for u in "$SCRIPT_DIR"/systemd/cn-db-backup.service "$SCRIPT_DIR"/systemd/cn-db-backup.timer; do
  [ -f "$u" ] || { echo "缺少 $(basename "$u")" >&2; exit 1; }
  sed -e "s|CN_BACKUP_OWNER=ubuntu|CN_BACKUP_OWNER=$CN_USER|" \
      -e "s|^User=.*|User=root|" \
      -e "s|^Group=.*|Group=root|" \
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
  # 先清失败状态，否则上一次的 failed 会让 systemctl start 直接报错、看不出真实原因。
  sudo systemctl reset-failed cn-db-backup.service >/dev/null 2>&1 || true
  sudo systemctl start cn-db-backup.service || true
  sudo systemctl status cn-db-backup.service --no-pager -n 20 || true

  # 不看 status 的措辞，直接查产物：三个库各一份 dump，且清单里有对应的 done 行。
  # ls 在目录为空时退出码非 0，pipefail 下会连累整条赋值，所以兜底 true。
  latest="$(ls -1dt "$CN_HOME"/db-backup/*/ 2>/dev/null | head -1 || true)"
  if [ -z "$latest" ]; then
    echo "[失败] $CN_HOME/db-backup 下没有生成任何备份目录" >&2
    echo "详细输出：tail -n 40 $CN_HOME/logs/db-backup.log" >&2
    exit 1
  fi
  echo "最新备份：$latest"
  missing=0
  for db in core_db ingestion_db query_db; do
    if [ -s "$latest/$db.dump" ] && grep -q "^done $db " "$latest/manifest.txt"; then
      printf '  %-13s %s 字节  ok\n' "$db" "$(stat -c %s "$latest/$db.dump")"
    else
      printf '  %-13s 缺失或未记入清单\n' "$db" >&2
      missing=$((missing + 1))
    fi
  done
  [ "$missing" -eq 0 ] || { echo "[失败] $missing 个库没备成，见 $CN_HOME/logs/db-backup.log" >&2; exit 1; }
  echo "三个库全部备份成功"
  echo "详细输出：tail -n 40 $CN_HOME/logs/db-backup.log"
fi
