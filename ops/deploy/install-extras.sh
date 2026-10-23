#!/usr/bin/env bash
#
# 一条命令装好两样需要 root 的一次性能力：
#   1. 前端同步助手 cn-web-sync —— 让无 sudo 的部署账号能把 dist 覆盖到 /var/www/cn
#   2. 数据库备份定时器 cn-db-backup.timer —— 每天 02:20（北京时间）备三个库
#
# 为什么要有这个包装：
#   这两件事各自只需要 root 一次，但都会「改机器」。分开跑意味着要记住顺序、要确认
#   前一步成没成。这里把前置检查集中做一遍，任何一项不满足就在动手之前退出，
#   且全程只写下面这几个路径，别的一概不碰：
#     /usr/local/sbin/cn-web-sync            新建
#     /etc/sudoers.d/cn-web-sync             新建（只放行上面这一条命令）
#     /etc/systemd/system/cn-db-backup.{service,timer}   新建或覆盖
#     <部署账号>/cn-deploy/pg-backup.sh      新建或覆盖
#   不碰 nginx 配置、不碰 sshd、不碰 pg_hba、不碰任何业务数据目录。
#
# 用法：
#   sudo bash install-extras.sh --check     # 只体检，一个字节都不写
#   sudo bash install-extras.sh             # 体检通过后安装（账号取 SUDO_USER）
#   sudo bash install-extras.sh buaa        # 显式指定部署账号
#   sudo bash install-extras.sh buaa --skip-backup-run   # 装定时器但不立刻跑一次
#
# 卸载：
#   sudo rm -f /etc/sudoers.d/cn-web-sync /usr/local/sbin/cn-web-sync
#   sudo systemctl disable --now cn-db-backup.timer
#   sudo rm -f /etc/systemd/system/cn-db-backup.{service,timer} && sudo systemctl daemon-reload
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CHECK_ONLY=0
SKIP_BACKUP_RUN=0
TARGET_USER=""

for arg in "$@"; do
  case "$arg" in
    --check) CHECK_ONLY=1 ;;
    --skip-backup-run) SKIP_BACKUP_RUN=1 ;;
    -*) echo "未知参数: $arg" >&2; exit 2 ;;
    *) TARGET_USER="$arg" ;;
  esac
done

# --check 只读，普通账号也能跑：体检本身不需要 root，装的时候才需要。
# 这样部署账号可以先自己确认「该有的都在」，再决定要不要开一次 root。
if [ "$CHECK_ONLY" -eq 0 ] && [ "$(id -un)" != "root" ]; then
  echo "安装必须以 root 运行：sudo bash $0 ${TARGET_USER:-[部署账号]}" >&2
  echo "只想确认环境是否就绪：bash $0 --check（这个不需要 root）" >&2
  exit 1
fi
TARGET_USER="${TARGET_USER:-${SUDO_USER:-$(id -un)}}"
case "$TARGET_USER" in
  ""|root)
    echo "取不到部署账号。sudo bash $0 时用 SUDO_USER，直接 root 登录时请显式给出：sudo bash $0 buaa" >&2
    exit 1
    ;;
esac

USER_HOME="$(getent passwd "$TARGET_USER" | cut -d: -f6)"
CN_HOME="$USER_HOME/cn"
DEPLOY_DIR="$USER_HOME/cn-deploy"
WEB_ROOT=/var/www/cn
HELPER=/usr/local/sbin/cn-web-sync
SUDOERS=/etc/sudoers.d/cn-web-sync
problems=0
note() { printf '  %-10s %s\n' "$1" "$2"; }
bad() { printf '  %-10s %s\n' "[缺]" "$*"; problems=$((problems + 1)); }

echo
echo "== 体检（不改任何东西） =="
echo "  部署账号   $TARGET_USER（家目录 $USER_HOME）"

id "$TARGET_USER" >/dev/null 2>&1 || bad "用户 $TARGET_USER 不存在"
[ -d "$USER_HOME" ] || bad "家目录 $USER_HOME 不存在"
[ -d "$CN_HOME" ] || bad "$CN_HOME 不存在（后端是不是还没装？）"
[ -d "$WEB_ROOT" ] || bad "$WEB_ROOT 不存在（nginx 站点根缺失）"
[ -f "$WEB_ROOT/index.html" ] && note ok "站点根有 index.html：$(grep -o 'assets/index-[A-Za-z0-9_.-]*\.js' "$WEB_ROOT/index.html" | head -1)"
[ -d "$DEPLOY_DIR" ] && note ok "部署目录已存在：$DEPLOY_DIR" || note 新建 "$DEPLOY_DIR 将创建"

for bin in pg_dump pg_restore runuser; do
  command -v "$bin" >/dev/null 2>&1 && note ok "$bin -> $(command -v "$bin")" || bad "缺少 $bin"
done
id postgres >/dev/null 2>&1 && note ok "postgres 用户存在" || bad "postgres 用户不存在"

# 磁盘：单份备份约 160M，留 5G 余量（与 pg-backup.sh 内部判据同一阈值）。
free_mb="$(df -Pm "$CN_HOME" 2>/dev/null | awk 'NR==2 {print $4}')"
if [ "${free_mb:-0}" -ge 5120 ]; then
  note ok "磁盘剩余 ${free_mb}M"
else
  bad "磁盘剩余 ${free_mb:-未知}M，低于 5G，备份会被拒绝"
fi

# 幂等：装过的会显示「已装」，重跑只是覆盖成仓库版本。
[ -x "$HELPER" ] && note 已装 "$HELPER 已存在（将覆盖）" || note 新建 "$HELPER 将创建"
[ -f "$SUDOERS" ] && note 已装 "$SUDOERS 已存在（将覆盖，写入后 visudo -c 校验）" || note 新建 "$SUDOERS 将创建"
if systemctl list-unit-files 2>/dev/null | grep -q '^cn-db-backup\.timer'; then
  note 已装 "cn-db-backup.timer 已注册（$(systemctl is-enabled cn-db-backup.timer 2>&1)，$(systemctl is-active cn-db-backup.timer 2>&1)）"
else
  note 新建 "cn-db-backup.timer 将注册"
fi

# 没有 root 也能做备份：部署账号自己的 crontab 里跑 pg-backup.sh 即可（脚本会走 TCP +
# 属主口令，不需要切 postgres）。下面这条只提示现状，不替人改别人的 crontab。
if crontab -l 2>/dev/null | grep -q 'pg-backup\.sh'; then
  note 已在跑 "部署账号 crontab 里已有每日备份（装 timer 后记得删掉那条，flock 会兜住重复运行）"
fi

# 现存单元最容易出的问题不是「没装」，而是「装了但指向的脚本不在」——那种状态下
# 定时器照样触发、每次都以 203/EXEC 失败，而 systemctl status 里只是几行红字。
cur_exec="$(systemctl show -p ExecStart --value cn-db-backup.service 2>/dev/null | grep -oE '/[^ ;]+pg-backup\.sh' | head -1 || true)"
if [ -n "$cur_exec" ] && [ ! -f "$cur_exec" ]; then
  note 有问题 "cn-db-backup.service 指向的 $cur_exec 不存在（现在触发也只会失败）"
fi
# 现有 sudo 能力：只用来判断要不要提示「无密码 sudo」，不据此做任何决定。
if sudo -n true 2>/dev/null; then
  note ok "$TARGET_USER 有免密 sudo（部署脚本会走 systemctl 路径）"
else
  note ok "$TARGET_USER 无免密 sudo（部署脚本会走 kill -TERM + cn-web-sync 路径）"
fi

echo
echo "  受影响文件（仅此 4 个路径）："
echo "    $HELPER"
echo "    $SUDOERS"
echo "    /etc/systemd/system/cn-db-backup.service"
echo "    /etc/systemd/system/cn-db-backup.timer"
echo "    $DEPLOY_DIR/pg-backup.sh"
echo "  不碰：nginx 配置、sshd 配置、pg_hba、数据库内容、/var/www/cn 的内容（安装阶段只读）"

if [ "$problems" -gt 0 ]; then
  echo
  echo "体检未通过：$problems 项缺失，什么都没改。" >&2
  exit 1
fi
echo
echo "体检通过。"

if [ "$CHECK_ONLY" -eq 1 ]; then
  echo "只做了体检（--check），未做任何修改。"
  exit 0
fi

# --- 安装前留一份现状，出问题能照单还原 -------------------------------------
STAMP="$(date +%Y%m%d-%H%M%S)"
SNAP="$USER_HOME/cn/backup/root-extras-$STAMP"
install -d -m 755 "$SNAP"
for f in "$HELPER" "$SUDOERS" /etc/systemd/system/cn-db-backup.service /etc/systemd/system/cn-db-backup.timer; do
  [ -f "$f" ] && cp -a "$f" "$SNAP/" 2>/dev/null || true
done
systemctl list-timers --all --no-pager 2>/dev/null | grep -i cn-db-backup >"$SNAP/timer-before.txt" || true
echo "现状快照：$SNAP（空文件说明原来没装）"

echo
echo "== 1/2 安装前端同步助手 =="
bash "$SCRIPT_DIR/install-web-sync.sh" "$TARGET_USER"

echo
echo "== 2/2 安装数据库备份定时器 =="
if [ "$SKIP_BACKUP_RUN" -eq 1 ]; then
  bash "$SCRIPT_DIR/install-backup.sh"
else
  bash "$SCRIPT_DIR/install-backup.sh" --run-now
fi

echo
echo "== 结果 =="
if sudo -u "$TARGET_USER" sudo -n "$HELPER" --probe >/dev/null 2>&1; then
  echo "  [ok] $TARGET_USER 可以执行 cn-web-sync --probe"
else
  echo "  [失败] cn-web-sync 探测不通过，检查 $SUDOERS" >&2
fi
systemctl list-timers cn-db-backup.timer --no-pager 2>/dev/null | sed -n '1,3p' | sed 's/^/  /'
if [ -d "$CN_HOME/db-backup" ]; then
  echo "  备份目录：$(ls -1dt "$CN_HOME"/db-backup/*/ 2>/dev/null | head -1)"
fi
echo "  快照：$SNAP"
echo
echo "回滚：把 $SNAP 里的文件拷回原位并 systemctl daemon-reload；"
echo "      或直接 sudo rm -f $SUDOERS $HELPER /etc/systemd/system/cn-db-backup.* && sudo systemctl daemon-reload"
