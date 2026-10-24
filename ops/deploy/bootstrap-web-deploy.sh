#!/usr/bin/env bash
#
# 一条命令把「前端部署」这件事彻底解决：安装同步能力 + 立刻上线一次 + 自检。
#
# 背景：后端一直能自动部署，前端不行 —— /var/www/cn 属主是 root，部署账号 buaa 的
# sudo 口令是 locked（passwd -S 显示 L），连密码都没有。于是每次前端改动都卡在
# 「产物是新的、线上是旧的」。
#
# 这个脚本要找 root 跑一次。它做三件事：
#   1) 调 install-web-sync.sh 装好 /usr/local/sbin/cn-web-sync + 一条 sudoers 规则；
#   2) 以 buaa 身份调用它，把暂存区的 dist 同步到 /var/www/cn；
#   3) 自检首页与入口 chunk 是否 200。
#
# 跑完之后，buaa 就能自己部署前端（也就能被 CI 自动调用）：
#   sudo /usr/local/sbin/cn-web-sync --from /home/buaa/cn-incoming-new/dist
#
# 用法：
#   sudo bash /home/buaa/cn-deploy-scripts/bootstrap-web-deploy.sh
#
# 撤销授权（不想再给权限时）：
#   sudo rm -f /etc/sudoers.d/cn-web-sync
#
# 安全边界（由 install-web-sync.sh 保证）：
#   - sudoers 只放行 /usr/local/sbin/cn-web-sync 这一条命令，不带任何通配；
#   - 该命令只接受「调用者家目录下、属主是调用者、带 index.html」的源目录；
#   - 只覆盖 /var/www/cn，不碰 nginx 配置、不碰服务、不重启任何东西。
set -euo pipefail

DEPLOY_USER="${1:-buaa}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALLER="$SCRIPT_DIR/install-web-sync.sh"
HELPER=/usr/local/sbin/cn-web-sync
WEB_ROOT=/var/www/cn
USER_HOME="$(getent passwd "$DEPLOY_USER" | cut -d: -f6)"
STAGE="${USER_HOME}/cn-incoming-new/dist"

if [ "$(id -un)" != "root" ]; then
  echo "必须以 root 运行：sudo bash $0 [部署账号，默认 buaa]" >&2
  exit 1
fi
[ -f "$INSTALLER" ] || { echo "找不到 $INSTALLER，请与 bootstrap 脚本放在同一目录" >&2; exit 1; }
[ -d "$STAGE" ] || { echo "暂存区不存在：$STAGE（先把 cn-web.tar.gz 解包成 dist/）" >&2; exit 1; }
[ -f "$STAGE/index.html" ] || { echo "暂存区里没有 index.html：$STAGE" >&2; exit 1; }
[ -d "$WEB_ROOT" ] || { echo "站点目录不存在：$WEB_ROOT" >&2; exit 1; }

echo "== 1/3 安装同步能力（sudoers 只放行一条固定命令）"
bash "$INSTALLER" "$DEPLOY_USER"

# cn-web-sync 成功后会把 .old 删掉，不留回滚点。这里先自己存一份整目录备份。
echo
echo "== 2/4 备份现有前端"
BK="$WEB_ROOT.bak-$(date +%Y%m%d%H%M%S)"
cp -a "$WEB_ROOT" "$BK"
echo "   $BK"

echo
echo "== 3/4 以 $DEPLOY_USER 身份同步前端"
echo "   源目录：$STAGE"
sudo -u "$DEPLOY_USER" sudo -n "$HELPER" --from "$STAGE"

echo
echo "== 4/4 自检"
entry="$(grep -oE 'assets/index-[A-Za-z0-9._-]+\.js' "$WEB_ROOT/index.html" | head -1)"
code="$(curl -s -m 5 -o /dev/null -w '%{http_code}' http://127.0.0.1/ || echo 000)"
acode="$(curl -s -m 5 -o /dev/null -w '%{http_code}' "http://127.0.0.1/$entry" || echo 000)"
org="$(ls "$WEB_ROOT/assets" | grep -c '^OrganizationView' || true)"
echo "   首页 $code ／ 入口 $entry -> $acode ／ 机构页 chunk 数量 $org"

if [ "$code" = "200" ] && [ "$acode" = "200" ] && [ "$org" -gt 0 ]; then
  cat <<EOF

完成。前端已是最新版，机构页 /organizations 现在可以访问了。
以后部署前端不需要再找 root，直接：
  sudo /usr/local/sbin/cn-web-sync --from $STAGE
本次备份留在：$BK（确认没问题后可删）
EOF
else
  cat <<EOF

注意：自检没有全绿（首页 $code，入口 $acode，机构页 chunk $org 个）。
先看 nginx 错误日志：tail -50 /var/log/nginx/cn-error.log
退回上一版：
  rm -rf $WEB_ROOT && cp -a $BK $WEB_ROOT
EOF
  exit 1
fi
