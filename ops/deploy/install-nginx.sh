#!/usr/bin/env bash
#
# 把仓库里的 nginx 站点配置同步到目标机（幂等）。
#
# 为什么需要这个脚本：完整安装脚本 install-server.sh 会 DROP SCHEMA 重建数据库，
# 部署流程绝不能调它；而 nginx 配置又确实会随版本变化（本轮为 OpenAPI 文档加了
# 六条限流路由）。缺了这一步的结果是「仓库改了 nginx-cn.conf，线上仍是安装那天的旧版」，
# 表现为文档路径被前端兜底规则接走、返回 200 的 index.html——一个看起来正常的错误。
#
# 两个保守点：
#   1. 内容没变就直接退出，不 reload，避免每次部署都无谓地打断连接
#   2. nginx -t 失败时把旧配置装回去再退出，绝不把站点留在坏配置上
#      （配置已替换但没 reload，下一次任何 reload 都会让整站 502）
set -euo pipefail

CN_HOME="${CN_HOME:-/home/ubuntu/cn}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${1:-$SCRIPT_DIR/nginx-cn.conf}"
TARGET=/etc/nginx/sites-available/cn
STAGE=/tmp/cn-nginx-stage.conf
PREV=/tmp/cn-nginx-prev.conf

[ -f "$SRC" ] || {
  echo "缺少 nginx 配置源文件：$SRC" >&2
  exit 1
}

# 安装脚本按实际用户名替换路径前缀；sed 在这里是恒等变换（默认就是 /home/ubuntu/cn），
# 保留是为了换用户部署时不至于指错目录。
sed "s|/home/ubuntu/cn|$CN_HOME|g" "$SRC" > "$STAGE"

if [ -f "$TARGET" ] && sudo cmp -s "$STAGE" "$TARGET"; then
  echo "nginx 配置无变化，跳过 reload"
  exit 0
fi

if [ -f "$TARGET" ]; then
  sudo cp -a "$TARGET" "$PREV"
fi

sudo install -m 644 "$STAGE" "$TARGET"
sudo ln -sf "$TARGET" /etc/nginx/sites-enabled/cn
sudo rm -f /etc/nginx/sites-enabled/default

if ! sudo nginx -t 2>&1; then
  echo "nginx -t 未通过，回滚到上一版配置" >&2
  if [ -f "$PREV" ]; then
    sudo install -m 644 "$PREV" "$TARGET"
    sudo nginx -t 2>&1 || true
  fi
  exit 1
fi

# reload 而非 restart：不断开现有连接。镜像里 nginx 可能装了但从未启动过（Oracle 的 Ubuntu 镜像），
# reload 对它无效，所以先确保已启动。
sudo systemctl enable nginx >/dev/null 2>&1 || true
if ! systemctl is-active --quiet nginx; then
  sudo systemctl start nginx
else
  sudo systemctl reload nginx
fi

echo "nginx 配置已更新并生效"
