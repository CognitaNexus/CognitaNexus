#!/usr/bin/env bash
#
# 给日志设上限：journald 的磁盘配额 + 服务日志的 logrotate。幂等，可反复执行。
#
# 为什么需要这一步：
#   部署后实测 `journalctl --disk-usage` 已 4.0G 且 journald 未设 SystemMaxUse（默认按磁盘百分比
#   无界增长）；三个服务的 StandardOutput=append: 直接写文件，既没有 logrotate 规则也不会自己截断。
#   两者叠加的结局是磁盘被日志吃满，而磁盘满最先坏的是 PostgreSQL（写 WAL 失败即停库）。
#
# 为什么用 copytruncate 而不是 create：
#   systemd 用 append: 打开日志文件后一直持有 fd，rename 走 create 模式的话服务还在往旧 inode
#   里写，新文件永远是空的，`tail -f` 看不到任何东西——这是排查时最容易误判成「服务不打日志」的坑。
#   copytruncate 先复制再原地截断，fd 不变，代价是截断瞬间可能丢几行，对运行日志可以接受。
#
# 用法：bash install-logging.sh [--vacuum-now]
#      CN_LOGGING_USER=buaa bash install-logging.sh   # 由 install-server.sh 代传，
# 因为「sudo bash install-server.sh buaa」时 SUDO_USER 是代装人，不是部署目标用户，
# 直接用 SUDO_USER 会把 logrotate 规则指到 /home/ubuntu/cn/logs，日志永远不会被轮转。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CN_USER="${CN_LOGGING_USER:-${SUDO_USER:-$(whoami)}}"
CN_HOME="$(getent passwd "$CN_USER" | cut -d: -f6)/cn"
LOG_DIR="$CN_HOME/logs"

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

# ---------------------------------------------------------------------------
# 1) journald 磁盘配额
# ---------------------------------------------------------------------------
log "配置 journald 磁盘配额（drop-in，不改系统自带 journald.conf）"
sudo install -d -m 755 /etc/systemd/journald.conf.d
sudo tee /etc/systemd/journald.conf.d/cn.conf >/dev/null <<'EOF'
# 由 ops/deploy/install-logging.sh 写入；改这份文件请改仓库里的同名模板。
#
# 只限制 journal 自己的占用，不影响三个服务的 append: 日志文件（那些由 logrotate 管）。
# 500M 的取法：默认无上限时实测占用 4.0G，其中绝大部分是历史积累与本次部署的启动日志；
# 500M 足够留下最近一两天的 systemd/内核/sshd 记录用于事后排查。
[Journal]
SystemMaxUse=500M
SystemMaxFileSize=50M
# 按时间再兜一层：即使写入量很小（长期空闲的机器），也不留超过一个月。
MaxRetentionSec=1month
EOF
sudo systemctl restart systemd-journald

# ---------------------------------------------------------------------------
# 2) 服务日志的 logrotate
# ---------------------------------------------------------------------------
log "安装 logrotate 规则到 /etc/logrotate.d/cn"
# 日志文件属主是 root（systemd 以 root 打开后交给服务的 stdout），所以 su 写成 root root；
# 目录属于 $CN_USER 不影响，root 有完全权限。
sudo tee /etc/logrotate.d/cn >/dev/null <<EOF
# 由 ops/deploy/install-logging.sh 写入；改这份文件请改仓库里的同名模板。
#
# 管的是三个服务的 StandardOutput=append: 日志与备份脚本日志。
# deploy-last.log 不在此列：CI 每次覆盖写，尺寸不会累积。
$LOG_DIR/core.log
$LOG_DIR/query.log
$LOG_DIR/ingestion.log
$LOG_DIR/db-backup.log
{
    # 每天检查一次（系统 logrotate.timer），单个文件超过 50M 也当场轮转。
    # 只写 daily 不写 maxsize 的话，一次异常刷屏就能把盘写满；
    # 只写 maxsize 不写 daily 的话，文件长期不满就永远不轮转，看不出时间边界。
    daily
    maxsize 50M
    rotate 14
    compress
    # 与 copytruncate 配套：刚轮转出的 .1 可能还在被写，下一轮再压缩。
    delaycompress
    copytruncate
    missingok
    notifempty
    su root root
}
EOF

log "校验 logrotate 配置"
# -d 是 dry-run：只解析并打印将要做什么，不改任何文件。配置语法错会在这里就报出来。
sudo logrotate -d /etc/logrotate.d/cn 2>&1 | grep -vE "^considering|^Creating|^$" | tail -12 || true

# ---------------------------------------------------------------------------
# 3) 立刻回收一次已占用的空间
# ---------------------------------------------------------------------------
if [ "${1:-}" = "--vacuum-now" ]; then
  log "清理 journal 到 500M"
  echo "清理前：$(sudo journalctl --disk-usage)"
  sudo journalctl --vacuum-size=500M
  echo "清理后：$(sudo journalctl --disk-usage)"
fi

log "完成"
echo "当前占用："
sudo journalctl --disk-usage
du -sh "$LOG_DIR" 2>/dev/null || true
# 用 sed 而不是 head：head 读够行数就关闭管道，上游 systemctl 会收到 SIGPIPE（退出码 141），
# 在 set -o pipefail 下整条命令算失败——CI 里表现为「同步日志上限」这一步莫名红掉，
# 而日志上限其实已经装好了。
systemctl list-timers logrotate.timer --no-pager 2>/dev/null | sed -n '2p' || true
