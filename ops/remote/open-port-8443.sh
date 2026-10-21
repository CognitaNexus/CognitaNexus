#!/usr/bin/env bash
#
# 放行备用 SSH 端口的主机防火墙规则。
#
# 为什么要单独做：Oracle Cloud 的 Ubuntu 镜像默认带 iptables 规则，
# 只放行 22 与 ICMP，其余一律 REJECT。所以「sshd 在监听」不等于「外面连得上」，
# 云控制台的安全列表与主机 iptables 是两道独立的门，必须都过。
#
# 幂等：已有规则就跳过；ufw 与 iptables 两种布局都处理。

set -euo pipefail

PORT="${1:-8443}"

echo "== 防火墙现状 =="
if command -v ufw >/dev/null 2>&1; then
  sudo ufw status verbose 2>/dev/null || echo "ufw 状态读不到"
fi
echo "-- iptables INPUT 前 12 条 --"
sudo iptables -S INPUT 2>/dev/null | head -12 || echo "iptables 不可读"

echo "== 放行 TCP ${PORT} =="
if command -v ufw >/dev/null 2>&1 && sudo ufw status 2>/dev/null | grep -q '^Status: active'; then
  sudo ufw allow "${PORT}/tcp" >/dev/null
  echo "ufw 已放行 ${PORT}/tcp"
else
  if sudo iptables -C INPUT -p tcp --dport "${PORT}" -j ACCEPT 2>/dev/null; then
    echo "iptables 已有 ${PORT} 的 ACCEPT 规则，无需变更"
  else
    # 插到第 1 条：Oracle 默认规则末尾是 REJECT，追加无效
    sudo iptables -I INPUT 1 -p tcp --dport "${PORT}" -j ACCEPT
    echo "iptables 已插入 ACCEPT 规则（第 1 条）"
  fi
  # 不持久化的话，重启就丢
  if command -v netfilter-persistent >/dev/null 2>&1; then
    if sudo netfilter-persistent save >/dev/null 2>&1; then
      echo "已用 netfilter-persistent 持久化"
    else
      echo "netfilter-persistent 保存失败" >&2
    fi
  elif [ -d /etc/iptables ]; then
    sudo sh -c 'iptables-save > /etc/iptables/rules.v4'
    echo "已写入 /etc/iptables/rules.v4"
  else
    echo "提示：没找到持久化机制，规则重启后会丢" >&2
  fi
fi

echo "== 改后 iptables INPUT 前 8 条 =="
sudo iptables -S INPUT 2>/dev/null | head -8 || true

echo "== 监听确认 =="
sudo ss -lntp 2>/dev/null | grep -E ":${PORT}[[:space:]]" || echo "警告：${PORT} 没有监听" >&2

echo "== 完成 =="
