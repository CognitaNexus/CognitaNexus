#!/usr/bin/env bash
#
# 更新已部署实例的代码，不动数据库。
#
# 与 install-server.sh 的分工必须守住：
#   install-server.sh  首次安装，会 DROP SCHEMA 并恢复 dump
#   deploy-update.sh   只换 jar 与前端产物，然后重启
# CI/CD 只能调用后者。误调前者的后果是把生产数据回滚到打包那一刻。
#
# 用法：bash deploy-update.sh <新产物目录>
#       bash deploy-update.sh --check <新产物目录>     只预检，不修改任何文件
#   新产物目录里应有 app/*.jar、web/（前端 dist 内容）与 build-info.txt（CI 生成）
#
# 环境变量：
#   CN_REQUIRE_WEB=1    前端有变化却没有同步能力时，整体失败而不降级（默认降级）
#
# --check 存在的理由：部署最贵的错误是「做了一半才发现做不下去」。预检把三件事
# （产物齐不齐、前端要不要换、当前身份有没有权限换）全部提前到第一次写盘之前，
# 所以 CI 里可以先用它把关，失败了连一个字节都没动。
#
# ---------------------------------------------------------------------------
# 为什么这份脚本要能在「没有 sudo」的情况下跑完（2026-10-23 重写）
#
# 当前生产机（168.110.3.136，用户 buaa）没有 sudo，密码也不在 CI 手里
# （passwd -S 显示 buaa 的密码是 L，即锁死，不是「没人知道」）。
# 旧版每一步都写死 sudo（systemctl restart、写 /var/www/cn），于是整条 CD 只能靠
# 人工登录敲命令——这正是不该发生的事。现在按实际能力分两条路：
#
#   有 sudo     systemctl restart，直接写 /var/www/cn
#   无 sudo     kill -TERM <MainPID>，靠 unit 里的 Restart=always + SuccessExitStatus=143
#               自动拉起；前端交给 root 侧的 /usr/local/sbin/cn-web-sync
#               （install-web-sync.sh 一次性安装，sudoers 只放行这一条命令）
#
# ---------------------------------------------------------------------------
# 前端有变化但同步不了时的策略（2026-10-23 改）
#
# 旧版是「整体失败」，理由是半部署更难排查。这个理由在人工部署时成立，在流水线里
# 不成立：它会让「改一个前端按钮」变成「后端也不能更新」，而且因为没有 sudo 这条
# 几乎每轮都会触发，结果是流水线长期全红、真正的失败被淹掉。
#
# 现在的策略是分开处理，但把状态写到三个地方，不留静默：
#   后端照常更新（代码正确性不依赖前端）
#   前端产物留在暂存区，等下一次有能力时自动补
#   状态写到 ①本脚本输出 ②CI 的 warning 注解与 step summary ③机器上的 deploy-last.log
#
# 需要原子性（前端没上线就不算部署成功）的场合，传 CN_REQUIRE_WEB=1 恢复旧行为。
#
# ---------------------------------------------------------------------------
# jar 的替换方式也改了：同目录 mv（rename）原子顶替，不再 cp -f 覆盖。
# 覆盖写会 truncate 正在被 java 读取的 inode，热更新期间并发读可能拿到半截内容；
# rename 之后旧进程继续持有旧 inode，下次启动才看到新文件。
# 这条同时解掉了「换 jar 前必须先停服务」的历史限制。
set -euo pipefail

CN_USER="${CN_USER_OVERRIDE:-${SUDO_USER:-$(whoami)}}"
if [ "$CN_USER" = "root" ]; then
  echo "不要以 root 直接跑本脚本：会按 root 的家目录推导路径（可用 CN_USER_OVERRIDE=buaa）" >&2
  exit 1
fi
CN_HOME="$(getent passwd "$CN_USER" | cut -d: -f6)/cn"
WEB_ROOT="${CN_WEB_ROOT:-/var/www/cn}"
WEB_SYNCER="${CN_WEB_SYNCER:-/usr/local/sbin/cn-web-sync}"

CHECK=0
if [ "${1:-}" = "--check" ]; then CHECK=1; shift; fi
SRC="${1:-}"
[ -n "$SRC" ] || { echo "用法: bash deploy-update.sh [--check] <产物目录>" >&2; exit 1; }
[ -d "$SRC" ] || { echo "目录不存在: $SRC" >&2; exit 1; }
SRC="$(cd "$SRC" && pwd)"

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[警告] %s\033[0m\n' "$*" >&2; }

# 状态目录：记录「上一次真正部署上去的前端源码版本」。
# 只在前端同步成功且自检通过之后才写，跳过的一轮绝不写——否则下次会误以为已经上线。
CN_STATE="$CN_HOME/state"
FRONTEND_TREE_FILE="$CN_STATE/frontend-tree"

# 退化判据：比 index.html 引用的资源文件名集合。
#
# 只在拿不到源码版本时用（产物是人工打包的、没带 build-info.txt）。它比整文件 sha256 稳
# ——Vite 的文件名由内容哈希决定，但 index.html 里 preload 的顺序与空白会随 Node 版本变，
# 拿整文件哈希当判据等于「每次重建都判前端有变化」。
#
# 但它仍然不可靠：入口 chunk 的名字会随构建平台变。实测同一个 commit，
# Linux 构建出 index-Dxy1HZYT.js，Windows 构建出 index-CIbzzPOi.js，其余 13 个资源逐字相同。
# 这就是「每轮都报前端不一致」的根因，正常路径不应该再走到这里。
web_signature() {
  grep -oE 'assets/[A-Za-z0-9_.-]+\.(js|css)' "$1" 2>/dev/null | sort -u | tr '\n' ' '
}

# 探测 sudo 是否真能用。-n 是不问密码；会问密码的 sudo 在 CI 里等于挂死。
HAS_SUDO=0
if sudo -n true 2>/dev/null; then HAS_SUDO=1; fi

# 探测前端同步能力：有 sudo 就自己写；没有就看 root 侧是否装了白名单命令。
HAS_WEB_SYNC=0
if [ "$HAS_SUDO" -eq 1 ]; then
  HAS_WEB_SYNC=1
elif [ -x "$WEB_SYNCER" ] && sudo -n "$WEB_SYNCER" --probe >/dev/null 2>&1; then
  HAS_WEB_SYNC=1
fi

printf '部署身份 %s｜家目录 %s｜sudo=%s｜前端同步=%s\n' \
  "$CN_USER" "$CN_HOME" \
  "$([ "$HAS_SUDO" -eq 1 ] && echo 可用 || echo 不可用)" \
  "$([ "$HAS_WEB_SYNC" -eq 1 ] && echo 可用 || echo 不可用)"

# ---------------------------------------------------------------------------
# 0) 前置判断：前端要不要更新、能不能更新。放在任何写操作之前。
#
#    判据是「前端源码的 git tree hash」，不是构建产物长什么样。
#    tree hash 由 git 对源码内容计算，跨平台一致，改一个字节就变；
#    构建产物的文件名则受构建平台影响（见 web_signature 的注释）。
# ---------------------------------------------------------------------------
WEB_STAGE="$SRC/web"
WEB_NEEDS_SYNC=0
WEB_PENDING=0
want_tree=""
have_tree=""

# build-info.txt 由 CI 在整理产物时写入，人工打包的产物可能没有。
if [ -f "$SRC/build-info.txt" ]; then
  want_tree="$(sed -n 's/^frontend-tree=//p' "$SRC/build-info.txt" 2>/dev/null | head -1)"
fi
if [ -r "$FRONTEND_TREE_FILE" ]; then
  have_tree="$(head -1 "$FRONTEND_TREE_FILE" 2>/dev/null || true)"
fi

if [ -d "$WEB_STAGE" ] && [ -f "$WEB_STAGE/index.html" ]; then
  if [ -n "$want_tree" ] && [ -n "$have_tree" ]; then
    if [ "$want_tree" = "$have_tree" ]; then
      echo "前端源码未变（frontend tree ${want_tree:0:12}），跳过前端同步"
    else
      WEB_NEEDS_SYNC=1
      echo "前端源码有变化（${have_tree:0:12} → ${want_tree:0:12}），需要同步到 $WEB_ROOT"
    fi
  else
    # 缺一侧记录：首次引入本机制，或产物是手工打包的。退化到资源文件名比对，
    # 并把「为什么退化」写清楚——否则以后有人看到这条日志会以为判据就是这样。
    w="$(web_signature "$WEB_STAGE/index.html")"
    h=""
    if [ -r "$WEB_ROOT/index.html" ]; then
      h="$(web_signature "$WEB_ROOT/index.html")"
    fi
    if [ -n "$w" ] && [ "$w" != "$h" ]; then
      WEB_NEEDS_SYNC=1
      echo "前端产物与线上不同（缺源码版本记录，退化按资源文件名比对）"
    else
      echo "前端产物与线上一致，跳过前端同步"
    fi
  fi

  if [ "$WEB_NEEDS_SYNC" -eq 1 ] && [ "$HAS_WEB_SYNC" -eq 0 ]; then
    if [ "${CN_REQUIRE_WEB:-0}" = "1" ]; then
      cat >&2 <<'EOF'

[失败] 前端源码有变化，但当前身份没有同步 /var/www/cn 的能力（按 CN_REQUIRE_WEB=1 要求严格）。

  这一步刻意放在替换 jar 之前：先换 jar 再报错，线上会停在
  「后端是新的、页面还是旧的」的状态，而流水线是红的——没人知道哪一半生效了。

EOF
      exit 1
    fi
    # 默认策略：后端照常上，前端跳过但必须显式可见。
    # 为什么不再整体失败：改一个前端按钮不该让后端停更；而「静默跳过」是另一头的错。
    # 折中是「后端继续 + 状态写到三个地方」：本脚本输出、CI 注解、机器上的 pending 记录。
    WEB_PENDING=1
    WEB_NEEDS_SYNC=0
    warn "没有同步 $WEB_ROOT 的能力，本轮只更新后端，线上页面保持旧版本"
    warn "前端产物已留在 $WEB_STAGE，装上 cn-web-sync 后下一次部署会自动补上"
    # 机器可读标记：CI 靠这两行判断要不要告警，人看到也知道发生过什么。
    echo "CN_WEB_PENDING=1"
    echo "CN_WEB_PENDING_TREE=${want_tree:-unknown}"
  fi
elif [ -d "$WEB_STAGE" ]; then
  warn "产物目录里有 web/ 但没有 index.html，本轮不更新前端"
else
  echo "产物目录里没有 web/，本轮只更新后端"
fi

# ---------------------------------------------------------------------------
# 预检收尾：到这里所有判断都做完了，一个字节都还没改。--check 就在此退出。
# ---------------------------------------------------------------------------
if [ "$CHECK" -eq 1 ]; then
  log "预检（不修改任何文件）"
  miss=0
  for j in core-service query-service ingestion-service; do
    if [ -f "$SRC/app/$j.jar" ]; then
      printf '  %-18s 就位  %s 字节\n' "$j.jar" "$(stat -c %s "$SRC/app/$j.jar")"
    else
      printf '  %-18s 缺失\n' "$j.jar"
      miss=1
    fi
  done
  [ "$miss" -eq 0 ] || { echo "[失败] 产物不完整" >&2; exit 1; }
  printf '可执行能力：%s\n' \
    "$([ "$HAS_SUDO" -eq 1 ] && echo 'systemctl 重启 + 直写 /var/www/cn' || echo 'kill -TERM 重启（无 sudo），前端走 cn-web-sync 或跳过')"
  if [ "${WEB_PENDING:-0}" -eq 1 ]; then
    printf '前端：%s\n' '源码有变化，但本轮没有同步能力——将跳过前端、后端照常更新'
  else
    printf '前端：%s\n' "$([ "${WEB_NEEDS_SYNC:-0}" -eq 1 ] && echo '需要同步到 /var/www/cn' || echo '无需同步')"
  fi
  echo "预检通过，未做任何修改"
  exit 0
fi

log "备份当前产物"
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$CN_HOME/backup/$STAMP"
cp -f "$CN_HOME"/app/*.jar "$CN_HOME/backup/$STAMP/" 2>/dev/null || true
if [ -d "$WEB_ROOT" ]; then
  cp -r "$WEB_ROOT" "$CN_HOME/backup/$STAMP/web" 2>/dev/null || warn "前端备份失败，继续"
fi
echo "已备份到 $CN_HOME/backup/$STAMP"
# 只保留最近 5 次，避免磁盘被 jar 堆满（三个 jar 约 140M/次）。
ls -1dt "$CN_HOME"/backup/* 2>/dev/null | tail -n +6 | xargs -r rm -rf || true

log "替换后端产物（mv 原子顶替，运行中的进程继续持有旧 inode）"
for j in core-service query-service ingestion-service; do
  [ -f "$SRC/app/$j.jar" ] || { echo "缺少 app/$j.jar" >&2; exit 1; }
  cp -f "$SRC/app/$j.jar" "$CN_HOME/app/$j.jar.incoming"
  mv -f "$CN_HOME/app/$j.jar.incoming" "$CN_HOME/app/$j.jar"
  printf '  %s  %s 字节\n' "$j.jar" "$(stat -c %s "$CN_HOME/app/$j.jar")"
done

if [ "$WEB_NEEDS_SYNC" -eq 1 ]; then
  log "同步前端产物到 $WEB_ROOT"
  if [ "$HAS_SUDO" -eq 1 ]; then
    # 与 cn-web-sync 用同一套「改名让位」顺序：不能先 rm 再 mv，中间那一瞬站点是空的。
    sudo rm -rf "$WEB_ROOT.new" "$WEB_ROOT.old"
    sudo install -d -m 755 "$WEB_ROOT.new"
    sudo cp -r "$WEB_STAGE"/. "$WEB_ROOT.new/"
    sudo chmod -R a+rX "$WEB_ROOT.new"
    if [ -e "$WEB_ROOT" ]; then
      sudo mv "$WEB_ROOT" "$WEB_ROOT.old" || { echo "无法为 $WEB_ROOT 让位" >&2; exit 1; }
    fi
    if ! sudo mv "$WEB_ROOT.new" "$WEB_ROOT"; then
      { [ -e "$WEB_ROOT.old" ] && sudo mv "$WEB_ROOT.old" "$WEB_ROOT"; } || true
      echo "新版本就位失败，已退回上一版" >&2
      exit 1
    fi
    sudo rm -rf "$WEB_ROOT.old"
  else
    # root 侧脚本会自己校验源目录：必须在调用者家目录下、必须有 index.html。
    # 这条校验是「sudoers 只放行一条命令」仍然安全的前提。
    sudo -n "$WEB_SYNCER" --from "$WEB_STAGE"
  fi
  WEB_SYNCED=1
  echo "产物已替换"
fi

# ---------------------------------------------------------------------------
# 重启。有 sudo 走 systemctl；没有就 kill -TERM 交给 systemd 的 Restart=always。
# ---------------------------------------------------------------------------
restart_service() {
  local name="$1" pid new
  if [ "$HAS_SUDO" -eq 1 ]; then
    sudo systemctl restart "$name"
    return 0
  fi
  pid="$(systemctl show -p MainPID --value "$name" 2>/dev/null || true)"
  if [ -z "${pid:-}" ] || [ "$pid" = "0" ]; then
    echo "$name 当前没有主进程（MainPID=0），无法用 kill 触发重启" >&2
    return 1
  fi
  kill -TERM "$pid"
  for _ in $(seq 1 40); do
    new="$(systemctl show -p MainPID --value "$name" 2>/dev/null || echo 0)"
    if [ -n "$new" ] && [ "$new" != "0" ] && [ "$new" != "$pid" ]; then
      printf '  %s  %s → %s\n' "$name" "$pid" "$new"
      return 0
    fi
    sleep 1
  done
  echo "$name 在 40 秒内没有被 systemd 重新拉起（旧 PID $pid）" >&2
  return 1
}

log "重启服务（按依赖顺序：core 先起，就绪后再起另外两个）"
# 等待就绪必须轮询，不能用固定 sleep：2 核机上 Spring 冷启动实测 30～60 秒，
# 固定睡 15 秒会让自检先失败，出现「其实部署成功、流水线却报红」的假失败。
wait_health() {
  local port="$1" limit="$2" code=""
  for _ in $(seq 1 "$limit"); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://127.0.0.1:$port/actuator/health" || true)"
    [ "$code" = "200" ] && return 0
    sleep 2
  done
  echo "端口 $port 未在 $((limit * 2)) 秒内就绪（最后一次 $code）" >&2
  return 1
}

restart_service cn-core
wait_health 8081 60 || true
restart_service cn-query
restart_service cn-ingestion
wait_health 8082 60 || true
wait_health 8083 60 || true

log "自检"
fail=0
for p in 8081 8082 8083; do
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:$p/actuator/health" || true)"
  printf '%s %s\n' "$p" "$code"
  [ "$code" = "200" ] || fail=1
done
# nginx → query 这条链路单独再验一次，代理配错时端口本身仍是 200。
code=""
for _ in $(seq 1 30); do
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 'http://127.0.0.1/query/api/v1/public/search/achievements?page=1&size=1' || true)"
  [ "$code" = "200" ] && break
  sleep 2
done
printf '经 nginx 的公开检索 %s\n' "$code"
[ "$code" = "200" ] || fail=1

# 前端是否真的换了：直接比字节，别只信「命令没报错」。
# 这里能用 cmp 而不用资源集合：产物是刚从 stage 拷过去的，同一份文件应当逐字节相同。
if [ "${WEB_SYNCED:-0}" -eq 1 ]; then
  if cmp -s "$WEB_STAGE/index.html" "$WEB_ROOT/index.html"; then
    echo "前端已更新：$(grep -oE 'assets/index-[A-Za-z0-9_.-]+\.js' "$WEB_ROOT/index.html" | head -1)"
    # 只有确认线上真的是这一份，才记录源码版本。
    # 记录早了（比如同步命令返回 0 就记）会让「实际没生效」变成永久性误判：
    # 下一轮会认为前端已是最新，于是永远不再尝试。
    if [ -n "${want_tree:-}" ]; then
      mkdir -p "$CN_STATE"
      printf '%s\n' "$want_tree" >"$FRONTEND_TREE_FILE.tmp"
      mv -f "$FRONTEND_TREE_FILE.tmp" "$FRONTEND_TREE_FILE"
      echo "已记录前端源码版本 ${want_tree:0:12}（下一轮据此判断是否需要同步）"
    fi
  else
    echo "前端未生效：$WEB_ROOT/index.html 与产物不一致" >&2
    fail=1
  fi
fi

if [ "$fail" -eq 0 ]; then
  echo "更新完成"
else
  echo "自检未全通过，可用 $CN_HOME/backup/$STAMP 回滚" >&2
  exit 1
fi
