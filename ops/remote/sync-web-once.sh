#!/usr/bin/env bash
# 把 ~/cn-incoming-new/cn-web.tar.gz 同步到 /var/www/cn。必须由 root 执行一次。
#
# 为什么单独写一个脚本：buaa 的 sudo 口令是 locked（passwd -S 显示 L），拿不到 root；
# /var/www/cn 属主是 root:root，buaa 写不进去。所以这一步只能从有 root 的通道做一次。
#
# 用法（在 root 会话、或 Oracle 控制台的 root shell 里）：
#   bash /home/buaa/cn-deploy-scripts/sync-web-once.sh
#
# 可选第一个参数用于演练（换一个目标目录，不做 HTTP 自检）：
#   bash sync-web-once.sh /tmp/fake-web
#
# 安全性，按顺序：
#   1) 先校验压缩包完整（index.html 引用的每个资源都必须真实存在），不完整的包不解到线上去；
#   2) 检查磁盘空间（按包体积 2 倍算）；
#   3) 整目录备份到 <目标>.bak-<时间戳>，备份失败就退出，线上一个字节都不动；
#   4) 新目录先落到与目标同一分区，再用两次 rename 顶替 —— rename 是原子的，
#      nginx 不会看到「新旧混杂的 assets」这种半成品状态（那会白屏）；
#   5) 就位后再校验一次 index.html，缺失则回滚；
#   6) 对生产目标做一次 HTTP 自检；
#   7) 只保留最近 3 个备份，避免长期累积。
set -euo pipefail

SRC_TAR="${SRC_TAR:-/home/buaa/cn-incoming-new/cn-web.tar.gz}"
WEB="${1:-/var/www/cn}"
WEB="${WEB%/}"
STAMP="$(date +%Y%m%d%H%M%S)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fail() { echo "失败：$*" >&2; exit 1; }

[ -f "$SRC_TAR" ] || fail "找不到 $SRC_TAR"
[ -d "$WEB" ] || fail "目标目录 $WEB 不存在"
[ -f "$WEB/index.html" ] || fail "$WEB 下没有 index.html，不像前端目录，拒绝覆盖"

echo "== 1/6 解包并校验"
tar xzf "$SRC_TAR" -C "$WORK"
NEW="$WORK/dist"
[ -f "$NEW/index.html" ] || fail "包内没有 dist/index.html"

missing=0
while read -r ref; do
  [ -f "$NEW/$ref" ] || { echo "  缺文件：$ref"; missing=1; }
done < <(grep -oE 'assets/[A-Za-z0-9._-]+' "$NEW/index.html" | sort -u)
[ "$missing" -eq 0 ] || fail "包不完整：index.html 引用的资源有缺失"

entry="$(grep -oE 'assets/index-[A-Za-z0-9._-]+\.js' "$NEW/index.html" | head -1)"
[ -n "$entry" ] || fail "index.html 里找不到入口 chunk"
echo "  文件数 $(find "$NEW" -type f | wc -l)，入口 $entry"

echo "== 2/6 检查空间"
need=$(( $(du -sk "$NEW" | cut -f1) * 2 ))
avail=$(df -Pk "$(dirname "$WEB")" | awk 'NR==2{print $4}')
[ "$avail" -gt "$need" ] || fail "空间不足：需要约 ${need}KB，可用 ${avail}KB"
echo "  需要 ${need}KB，可用 ${avail}KB"

echo "== 3/6 备份现有前端"
cp -a "$WEB" "${WEB}.bak-$STAMP" || fail "备份失败，未做任何改动"
echo "  已备份到 ${WEB}.bak-$STAMP"

echo "== 4/6 原子替换"
# 先搬到同分区，再两次 rename。mv 从 /tmp 直接到 /var 可能跨分区，会退化成 copy+delete，
# 那条路径中途被打断就会留下半个目录。
mv "$NEW" "${WEB}.new-$STAMP"
mv "$WEB" "${WEB}.old-$STAMP"
if ! mv "${WEB}.new-$STAMP" "$WEB"; then
  mv "${WEB}.old-$STAMP" "$WEB"
  fail "替换失败，已回滚到旧版"
fi
if [ "$(id -u)" = "0" ]; then
  chown -R root:root "$WEB"
fi
chmod -R a+rX "$WEB"

echo "== 5/6 就位校验"
if [ ! -f "$WEB/index.html" ]; then
  rm -rf "$WEB"
  mv "${WEB}.old-$STAMP" "$WEB"
  fail "替换后 index.html 缺失，已回滚到旧版"
fi
rm -rf "${WEB}.old-$STAMP"

echo "== 6/6 自检"
if [ "$WEB" = "/var/www/cn" ]; then
  code=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "http://127.0.0.1/" || echo "000")
  acode=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "http://127.0.0.1/$entry" || echo "000")
  echo "  首页 $code，入口 $entry -> $acode"
  if [ "$code" != "200" ] || [ "$acode" != "200" ]; then
    echo "  ！自检未全 200，请手动打开首页确认；回滚方法见下方"
  fi
else
  echo "  演练模式（目标 $WEB），跳过 HTTP 自检"
fi

ls -1dt "${WEB}".bak-* 2>/dev/null | tail -n +4 | xargs -r rm -rf

echo
echo "完成。备份保留在 ${WEB}.bak-$STAMP"
ls -ld "$WEB"
echo "回滚方法：rm -rf $WEB && mv ${WEB}.bak-$STAMP $WEB"
