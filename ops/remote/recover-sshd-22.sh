#!/usr/bin/env bash
# 恢复 sshd 的 22 端口监听。
#
# 背景：2026-10-21 为绕过「代理节点封 22」给 sshd 加了备用端口，
# 但 Ubuntu 的 sshd_config 里 Port 22 是注释状态、靠内置默认值生效，
# 一旦写入任何 Port 指令默认值就失效，结果只监听 8443，22 变成 refused，
# 连 CI 的部署通道一起断掉，手上所有入口全没了。
#
# 本脚本把 drop-in 改写为「22 + 备用端口」两者都写，并逐层自检：
#   1. 配置里有 Port 22
#   2. sshd -t 语法通过
#   3. reload 之后 ss 确实看到 22 在听
# 任一层不满足都以非零码退出，避免再次出现「配置看着对、端口其实没听」。
#
# 执行方式（不需要 SSH，root 身份）：
#   - Oracle 控制台「运行命令 / Run command」粘贴本文件内容，或
#   - 已有任一 SSH 入口时：bash recover-sshd-22.sh

set -uo pipefail

PORT="${1:-8443}"
DROP=/etc/ssh/sshd_config.d/20-cn-extra-port.conf
SSHD_CONFIG=/etc/ssh/sshd_config

echo "== 恢复 sshd：Port 22 必须显式写回（备用端口 ${PORT}）=="

# 备份现状
if [ -f "$DROP" ]; then
  bak="/root/20-cn-extra-port.conf.bak.$(date +%Y%m%d-%H%M%S)"
  cp -a "$DROP" "$bak" 2>/dev/null && echo "已备份现有 drop-in 到 ${bak}"
  echo "--- 改动前的 drop-in ---"
  cat "$DROP"
fi

# 确认主配置确实 Include 了 drop-in 目录；没有就退回直接写主配置
if grep -qE '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config\.d/\*\.conf' "$SSHD_CONFIG"; then
  mkdir -p /etc/ssh/sshd_config.d
  printf 'Port 22\nPort %s\n' "$PORT" > "$DROP"
  chmod 644 "$DROP"
  echo "已写入 ${DROP}："
else
  echo "提示：${SSHD_CONFIG} 未 Include drop-in，改为直接改主配置" >&2
  sed -i -E 's/^[[:space:]]*#?[[:space:]]*Port[[:space:]]+[0-9]+[[:space:]]*$//' "$SSHD_CONFIG"
  printf '\nPort 22\nPort %s\n' "$PORT" >> "$SSHD_CONFIG"
  echo "已在 ${SSHD_CONFIG} 追加 Port 22 与 Port ${PORT}"
fi
cat "$DROP" 2>/dev/null || tail -4 "$SSHD_CONFIG"

# 校验一：配置里必须有 Port 22
if ! { cat "$DROP" 2>/dev/null; cat "$SSHD_CONFIG"; } | grep -qE '^[[:space:]]*Port[[:space:]]+22[[:space:]]*$'; then
  echo "FAIL: 配置里找不到 Port 22，放弃 reload（保持现有连接不断）" >&2
  exit 1
fi

# 校验二：语法
if ! sshd -t; then
  echo "FAIL: sshd -t 语法不通过，不 reload" >&2
  exit 1
fi
echo "sshd -t 通过"

# 校验三：reload 后实际监听
systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || systemctl restart ssh
sleep 2
echo "--- reload 后监听情况 ---"
ss -lnt | awk 'NR==1 || /:22 |:8443 /' || true

if ss -lnt | grep -qE '(:|\.)22[[:space:]]'; then
  echo "OK: 22 已恢复监听"
else
  echo "FAIL: reload 后仍未看到 22 监听" >&2
  exit 1
fi

if ss -lnt | grep -qE ":${PORT}[[:space:]]"; then
  echo "OK: 备用端口 ${PORT} 也在监听（还需云安全列表放行才可从外部连）"
fi

echo "== 完成 =="
