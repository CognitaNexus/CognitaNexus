#!/usr/bin/env bash
#
# 把 ops/deploy 打包上传到目标服务器。
# 用法：bash upload.sh ubuntu@1.2.3.4        （端口非 22 时加 -p，如 ssh 配置 / ~/.ssh/config）
#
# 上传后在服务器上执行 install-server.sh 完成安装。
set -euo pipefail

DST="${1:-}"
if [ -z "$DST" ]; then
  echo "用法: bash upload.sh 用户名@IP" >&2
  echo "例如: bash upload.sh ubuntu@203.0.113.10" >&2
  exit 1
fi

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARBALL="/tmp/cn-deploy-$(date +%m%d-%H%M).tar.gz"
# 本机 MSYS 的 ssh 读不到中文 HOME 下的 ~/.ssh/config，也不便写 known_hosts，
# 因此把额外参数做成可传入的，例如：
#   CN_SSH_OPTS="-i /c/Users/xxx/.ssh/id_ed25519_github -o UserKnownHostsFile=/tmp/cn_known_hosts" bash upload.sh ubuntu@ip
SSH_OPTS="${CN_SSH_OPTS:-}"

echo "==> 检查包内容"
for f in app/core-service.jar web/index.html sql/core_db.dump systemd/cn-core.service; do
  [ -e "$SRC/$f" ] || { echo "缺少 $f，先确认 ops/deploy 结构完整" >&2; exit 1; }
done

echo "==> 打包"
tar -czf "$TARBALL" -C "$SRC" --exclude=.gitignore .
du -h "$TARBALL" | awk '{print "    包大小 " $1}'

echo "==> 测试连通性"
# shellcheck disable=SC2086
ssh $SSH_OPTS -o ConnectTimeout=15 -o BatchMode=yes "$DST" 'echo ok' >/dev/null \
  || { echo "SSH 连接失败。检查密钥是否已加到对方服务器，或手动 ssh $DST 试一次。" >&2; exit 1; }

# shellcheck disable=SC2086
REMOTE_HOME="$(ssh $SSH_OPTS "$DST" 'echo $HOME')"
REMOTE_DIR="$REMOTE_HOME/cn-deploy"
echo "==> 上传到 $DST:$REMOTE_DIR"
# shellcheck disable=SC2086
scp $SSH_OPTS "$TARBALL" "$DST:/tmp/cn-deploy.tar.gz"
# shellcheck disable=SC2086
ssh $SSH_OPTS "$DST" "mkdir -p '$REMOTE_DIR' && tar -xzf /tmp/cn-deploy.tar.gz -C '$REMOTE_DIR' && rm -f /tmp/cn-deploy.tar.gz && du -sh '$REMOTE_DIR'"

rm -f "$TARBALL"

cat <<EOF

上传完成。在服务器上执行：

    cd $REMOTE_DIR && bash install-server.sh

脚本会装 JDK17 / PostgreSQL16 / Redis / nginx，建库并恢复数据，然后起三个服务。
全程约 10 到 15 分钟，最后会打印管理员账号和口令。
EOF
