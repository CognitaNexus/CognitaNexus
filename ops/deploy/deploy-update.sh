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
#   新产物目录里应有 app/*.jar 与 web/（前端 dist 内容）
#
# --check 存在的理由：部署最贵的错误是「做了一半才发现做不下去」。预检把三件事
# （产物齐不齐、前端要不要换、当前身份有没有权限换）全部提前到第一次写盘之前，
# 所以 CI 里可以先用它把关，失败了连一个字节都没动。
#
# ---------------------------------------------------------------------------
# 为什么这份脚本要能在「没有 sudo」的情况下跑完（2026-10-23 重写）
#
# 当前生产机（168.110.3.136，用户 buaa）没有 sudo，密码也不在 CI 手里。
# 旧版每一步都写死 sudo（systemctl restart、写 /var/www/cn），于是整条 CD 只能靠
# 人工登录敲命令——这正是不该发生的事。现在按实际能力分两条路：
#
#   有 sudo     systemctl restart，直接写 /var/www/cn
#   无 sudo     kill -TERM <MainPID>，靠 unit 里的 Restart=always + SuccessExitStatus=143
#               自动拉起；前端交给 root 侧的 /usr/local/sbin/cn-web-sync
#               （install-web-sync.sh 一次性安装，sudoers 只放行这一条命令）
#
# 两条路都先判断再动手：**前端需要更新而当前没有同步能力时，在替换任何文件之前就退出**。
# 半部署比不部署更难排查——jar 换了、页面还是旧的，流水线却是绿的。
#
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

# 前端「是否需要同步」的判据：比 index.html 引用的资源文件名集合，而不是整个文件的哈希。
#
# 为什么不能用整文件 sha256（第一版就是这么写的，被自己坑了一次）：
# 同一个 lockfile 在同一台机器上重建，Vite 产出的 js/css 文件名由内容哈希决定，完全稳定；
# 但 index.html 里注入的 preload 顺序、空白、换行在不同 Node 版本与不同机器上会变。
# 实测：本地重建的入口集合与线上逐字一致（13 个文件同名），整文件哈希却不同。
# 拿整文件哈希当判据，等于「每次重新构建都判定前端有变化」，于是永远要求同步能力。
#
# 文件名集合相同就意味着产物等价：任何一个源文件改动都会改变它的内容哈希，
# 也就必然改变被 index.html 引用的文件名。
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
# ---------------------------------------------------------------------------
WEB_STAGE="$SRC/web"
WEB_NEEDS_SYNC=0
if [ -d "$WEB_STAGE" ] && [ -f "$WEB_STAGE/index.html" ]; then
  want="$(web_signature "$WEB_STAGE/index.html")"
  have=""
  if [ -r "$WEB_ROOT/index.html" ]; then
    have="$(web_signature "$WEB_ROOT/index.html")"
  fi
  if [ -n "$want" ] && [ "$want" != "$have" ]; then
    WEB_NEEDS_SYNC=1
    echo "前端产物与线上不一致，需要同步到 $WEB_ROOT"
    if [ "$HAS_WEB_SYNC" -eq 0 ] && [ "${CN_SKIP_WEB:-0}" != "1" ]; then
      cat >&2 <<'EOF'

[失败] 前端产物与线上不一致，但当前身份没有同步 /var/www/cn 的能力。

  这一步刻意放在替换 jar 之前：先换 jar 再报错，线上会停在
  「后端是新的、页面还是旧的」的状态，而流水线是红的——没人知道哪一半生效了。

  二选一：
    a) 让 root 执行一次 ops/deploy/install-web-sync.sh（幂等），之后 CI 自动同步前端；
    b) 本轮只更新后端：设 CN_SKIP_WEB=1 再跑（线上页面将保持旧版本）。

EOF
      exit 1
    elif [ "${CN_SKIP_WEB:-0}" = "1" ]; then
      warn "按 CN_SKIP_WEB=1 跳过前端同步，线上页面保持旧版本"
      WEB_NEEDS_SYNC=0
    fi
  else
    echo "前端产物与线上一致（index.html 引用的资源文件名相同），跳过前端同步"
  fi
elif [ "${CN_SKIP_WEB:-0}" != "1" ]; then
  warn "产物目录里没有 web/index.html，本轮不更新前端"
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
  printf '前端：%s\n' "$([ "${WEB_NEEDS_SYNC:-0}" -eq 1 ] && echo '需要同步到 /var/www/cn' || echo '无需同步')"
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
    sudo rm -rf "$WEB_ROOT.new"
    sudo install -d -m 755 "$WEB_ROOT.new"
    sudo cp -r "$WEB_STAGE"/. "$WEB_ROOT.new/"
    sudo chmod -R a+rX "$WEB_ROOT.new"
    sudo rm -rf "$WEB_ROOT"
    sudo mv "$WEB_ROOT.new" "$WEB_ROOT"
  else
    # root 侧脚本会自己校验源目录：必须在调用者家目录下、必须有 index.html。
    # 这条校验是「sudoers 只放行一条命令」仍然安全的前提。
    sudo -n "$WEB_SYNCER" --from "$WEB_STAGE"
  fi
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

# 前端是否真的换了：拿线上 index.html 与产物再比一次，别只信「命令没报错」。
if [ "${WEB_NEEDS_SYNC:-0}" -eq 1 ]; then
  want="$(web_signature "$WEB_STAGE/index.html")"
  now=""
  if [ -r "$WEB_ROOT/index.html" ]; then
    now="$(web_signature "$WEB_ROOT/index.html")"
  fi
  if [ "$want" = "$now" ]; then
    echo "前端已更新：$(grep -oE 'assets/index-[A-Za-z0-9_.-]+\.js' "$WEB_ROOT/index.html" | head -1)"
  else
    echo "前端未生效：线上 index.html 引用的资源与产物不一致" >&2
    fail=1
  fi
fi

if [ "$fail" -eq 0 ]; then
  echo "更新完成"
else
  echo "自检未全通过，可用 $CN_HOME/backup/$STAMP 回滚" >&2
  exit 1
fi
