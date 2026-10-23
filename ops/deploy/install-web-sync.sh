#!/usr/bin/env bash
#
# 一次性安装：让「无 sudo 的部署账号」能把前端产物同步到 /var/www/cn。
#
# 为什么需要它：
#   前端必须放在 /var/www/cn（家目录 750，nginx worker 读不到家目录里的东西），
#   而这个目录属主是 root。部署账号 buaa 没有 sudo，于是「后端能自动部署、前端只能
#   人工上传」——这条差额藏在流程里，半年后没人记得线上页面为什么是旧的。
#
# 为什么不直接给 buaa 一条 systemctl 或 ALL 的 sudoers：
#   那等于把整台机器的 root 交给 CI 的私钥，代价远大于收益。
#   这里只放行一条固定路径的命令，命令内部再校验源目录必须是调用者自己的家目录下
#   且带 index.html——拿到这把钥匙也只能覆盖 /var/www/cn。
#
# 用法（需要 root，跑一次就够，幂等）：
#   sudo bash ops/deploy/install-web-sync.sh            # 给当前部署账号装
#   sudo bash ops/deploy/install-web-sync.sh buaa       # 显式指定部署账号
#
# 装完自己验一遍：
#   sudo -u buaa sudo -n /usr/local/sbin/cn-web-sync --probe; echo $?
#
# 卸载：
#   sudo rm -f /etc/sudoers.d/cn-web-sync /usr/local/sbin/cn-web-sync
set -euo pipefail

if [ "$(id -un)" != "root" ]; then
  echo "必须以 root 运行：sudo bash $0 [部署账号]" >&2
  exit 1
fi

TARGET_USER="${1:-${SUDO_USER:-}}"
if [ -z "$TARGET_USER" ] || [ "$TARGET_USER" = "root" ]; then
  echo "用法: sudo bash $0 <部署账号>（例如 buaa）" >&2
  echo "没有指定时也会尝试用 SUDO_USER，而 sudo bash 下它是空的。" >&2
  exit 1
fi
id "$TARGET_USER" >/dev/null 2>&1 || { echo "用户不存在: $TARGET_USER" >&2; exit 1; }

HELPER=/usr/local/sbin/cn-web-sync
SUDOERS=/etc/sudoers.d/cn-web-sync
WEB_ROOT=/var/www/cn

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

log "写入 $HELPER"
# 注意：脚本里不能出现 $HOME 之类的环境依赖——sudo 会重置环境，
# 一律用「调用者」的身份信息反查，而不是继承。
cat > "$HELPER" <<'HELPER_EOF'
#!/usr/bin/env bash
#
# 把部署账号暂存区里的前端产物覆盖到 /var/www/cn。由 sudoers 放行、以 root 执行。
#
# 只认两种调用：
#   cn-web-sync --probe                 能否执行（供部署脚本探测能力，不做任何修改）
#   cn-web-sync --from <产物目录>        同步（目录必须属于调用者、在其家目录下、带 index.html）
#
# 安全边界：root 只对「调用者自己提交的 dist」生效，不接受任意路径，
# 也不会去动 /var/www/cn 之外的任何东西。
set -euo pipefail

WEB_ROOT=/var/www/cn
REAL_USER="${SUDO_USER:-}"
if [ -z "$REAL_USER" ] || [ "$REAL_USER" = "root" ]; then
  echo "必须经由 sudo 由普通账号调用（SUDO_USER 为空）" >&2
  exit 1
fi
USER_HOME="$(getent passwd "$REAL_USER" | cut -d: -f6)"
[ -n "$USER_HOME" ] || { echo "取不到 $REAL_USER 的家目录" >&2; exit 1; }

case "${1:-}" in
  --probe)
    echo "ok user=$REAL_USER root=$WEB_ROOT"
    exit 0
    ;;
  --from)
    SRC="${2:-}"
    ;;
  *)
    echo "用法: cn-web-sync --probe | --from <产物目录>" >&2
    exit 2
    ;;
esac

[ -n "$SRC" ] || { echo "--from 缺少目录参数" >&2; exit 2; }
SRC="$(cd "$SRC" && pwd -P)"
[ -f "$SRC/index.html" ] || { echo "源目录里没有 index.html: $SRC" >&2; exit 1; }

# 边界 1：必须在调用者家目录之下。否则等于给了「任意目录内容 → 站点根」的写权限。
case "$SRC" in
  "$USER_HOME"/*) ;;
  *) echo "拒绝：源目录 $SRC 不在 $REAL_USER 的家目录 $USER_HOME 之下" >&2; exit 1 ;;
esac

# 边界 2：必须由调用者自己拥有，防止被塞入别人的文件。
owner="$(stat -c %U "$SRC")"
[ "$owner" = "$REAL_USER" ] || { echo "拒绝：源目录属主是 $owner，不是 $REAL_USER" >&2; exit 1; }

# 边界 3：整目录替换，但要能退回上一版。
# 原实现是 rm -rf 现行目录再 mv 新的进去：这两步之间站点是空的，如果 mv 失败（磁盘满、
# 权限、告警阻塞），/var/www/cn 就没了，nginx 直接 403。改成「改名让位 → 新版本就位 →
# 可读性自检 → 才清旧的」，每一步失败都能退回去。
if [ ! -d "$(dirname "$WEB_ROOT")" ]; then
  echo "拒绝：$(dirname "$WEB_ROOT") 不存在" >&2
  exit 1
fi
rm -rf "$WEB_ROOT.new" "$WEB_ROOT.old"
install -d -m 755 "$WEB_ROOT.new"
cp -r "$SRC"/. "$WEB_ROOT.new"/
chmod -R a+rX "$WEB_ROOT.new"
[ -f "$WEB_ROOT.new/index.html" ] || { echo "拒绝：拷贝后没有 index.html" >&2; rm -rf "$WEB_ROOT.new"; exit 1; }

if [ -e "$WEB_ROOT" ]; then
  mv "$WEB_ROOT" "$WEB_ROOT.old" || { echo "拒绝：无法让位 $WEB_ROOT" >&2; exit 1; }
fi
if ! mv "$WEB_ROOT.new" "$WEB_ROOT"; then
  [ -e "$WEB_ROOT.old" ] && mv "$WEB_ROOT.old" "$WEB_ROOT"
  echo "拒绝：新版本就位失败，已退回上一版" >&2
  exit 1
fi

# nginx worker 读不到就等于站点白屏。此时 .old 还在，直接退回，不让流水线绿着出事。
if command -v runuser >/dev/null 2>&1 && id www-data >/dev/null 2>&1; then
  if ! runuser -u www-data -- test -r "$WEB_ROOT/index.html"; then
    rm -rf "$WEB_ROOT"
    [ -e "$WEB_ROOT.old" ] && mv "$WEB_ROOT.old" "$WEB_ROOT"
    echo "拒绝：替换后 nginx 用户读不到 index.html，已退回上一版" >&2
    exit 1
  fi
fi
rm -rf "$WEB_ROOT.old"
echo "前端已同步：$SRC → $WEB_ROOT（$(du -sh "$WEB_ROOT" | cut -f1)）"
HELPER_EOF
chmod 755 "$HELPER"
chown root:root "$HELPER"

log "写入 $SUDOERS"
# 只放行这一条命令，且不带任何通配；NOPASSWD 是必需的，CI 里没有终端可以输密码。
printf '%s ALL=(root) NOPASSWD: %s\n' "$TARGET_USER" "$HELPER" > "$SUDOERS"
chmod 440 "$SUDOERS"
chown root:root "$SUDOERS"

log "校验 sudoers 语法"
# 写错一个字符就可能让整个 sudo 不可用，必须当场 -c 检查，失败就撤掉刚写的文件。
if ! visudo -cf "$SUDOERS"; then
  rm -f "$SUDOERS"
  echo "[失败] sudoers 语法错误，已移除 $SUDOERS" >&2
  exit 1
fi

log "以 $TARGET_USER 身份自检"
if sudo -u "$TARGET_USER" sudo -n "$HELPER" --probe; then
  echo "自检通过：$TARGET_USER 可以执行 $HELPER --probe"
else
  echo "[失败] $TARGET_USER 仍然无法执行 $HELPER，请检查 $SUDOERS" >&2
  exit 1
fi

cat <<EOF

装好了。之后 deploy-update.sh 会自己探测到这条能力，CI 就能把前端一起部署上去。
如果以后不想再给这个权限，执行：
  sudo rm -f $SUDOERS
$TARGET_USER 便只剩「后端能部署、前端跳过」这一种行为。
EOF
