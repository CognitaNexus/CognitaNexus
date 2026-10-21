#!/usr/bin/env bash
#
# 给 sshd 增加一个备用监听端口。
#
# 为什么需要：部分网络（含常见代理节点）会封 22 端口——实测 CONNECT 到
# 150.230.223.11:22 与 github.com:22 都是「握手成功、一个字节不回」，
# 而 80/443 正常。这时运维通道只剩 CI 里的 ssh，而 CI 只能跑固定脚本，
# 没法交互排查。sshd 同时听 22 与备用端口后，两条路都可用，互不影响。
#
# 幂等：已是目标状态直接返回；配置校验不过自动回滚，不留半截配置。
# 用 reload 不用 restart：reload 是 SIGHUP 重执行并重新绑定端口，
# 不会掐断正在进行的连接（部署本身就跑在 ssh 上）。

set -euo pipefail

PORT="${1:-8443}"
DROP=/etc/ssh/sshd_config.d/20-cn-extra-port.conf
SSHD_CONFIG=/etc/ssh/sshd_config

echo "== 目标：为 sshd 增加 Port ${PORT} =="

if [ -f "$DROP" ] && grep -qx "Port ${PORT}" "$DROP"; then
  echo "已存在 ${DROP}，配置无需变更"
else
  if grep -qE '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config\.d/\*\.conf' "$SSHD_CONFIG"; then
    printf 'Port %s\n' "$PORT" | sudo tee "$DROP" >/dev/null
    sudo chmod 644 "$DROP"
    echo "已写入 ${DROP}"
  else
    # 老发行版才有这种布局，正常 Ubuntu 22.04 走不到这里
    echo "提示：${SSHD_CONFIG} 未 Include sshd_config.d，改为直接追加" >&2
    if grep -qx "Port ${PORT}" "$SSHD_CONFIG"; then
      echo "${SSHD_CONFIG} 已含 Port ${PORT}"
    else
      printf 'Port %s\n' "$PORT" | sudo tee -a "$SSHD_CONFIG" >/dev/null
      echo "已追加到 ${SSHD_CONFIG}"
    fi
  fi

  if sudo sshd -t; then
    sudo systemctl reload ssh 2>/dev/null || sudo systemctl restart ssh
    echo "配置校验通过，已重载 sshd"
  else
    echo "sshd -t 校验失败，回滚这次改动" >&2
    sudo rm -f "$DROP"
    sudo sed -i "/^Port ${PORT}$/d" "$SSHD_CONFIG" 2>/dev/null || true
    exit 1
  fi
fi

echo "== 监听状态 =="
sudo ss -lntp 2>/dev/null | grep -E ":(${PORT}|22)[[:space:]]" || echo "未看到 22/${PORT} 的监听行"

echo "== 本机回环连通自检 =="
for p in 22 "$PORT"; do
  if timeout 3 bash -c "cat < /dev/null > /dev/tcp/127.0.0.1/${p}" 2>/dev/null; then
    echo "127.0.0.1:${p} 可连"
  else
    echo "127.0.0.1:${p} 不可连"
  fi
done

echo "== 完成 =="
