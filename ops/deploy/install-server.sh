#!/usr/bin/env bash
#
# CognitaNexus 服务器端安装脚本。
#
# 前置：同目录下应有 app/*.jar、web/、sql/*.dump，以及 conf/（由 upload.sh 上传）。
# 用法：bash install-server.sh
#
# 设计取舍：不在服务器上编译。2 核机器跑 Maven 构建三个 Spring 服务要三四十分钟，
# 且内存峰值不可控；改成本地构建好 jar 再传，服务器只负责运行。
set -euo pipefail

# 兼容任意登录用户名：云镜像有时是 ubuntu，有时是 root 建的普通用户。
# 第一个位置参数可显式指定「部署给哪个账号」，用于「有 sudo 的账号代人安装」这种情形：
# 此时 SUDO_USER 是执行者，而真正长期维护该实例的账号是另一个（例如 buaa）。
#   sudo bash install-server.sh buaa
CN_USER="${1:-${SUDO_USER:-$(whoami)}}"
getent passwd "$CN_USER" >/dev/null || { echo "用户 ${CN_USER} 不存在" >&2; exit 1; }
CN_HOME="$(getent passwd "$CN_USER" | cut -d: -f6)/cn"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m[失败] %s\033[0m\n' "$*" >&2; exit 1; }

# 两种运行方式都支持：
#   bash install-server.sh              自己装给自己（脚本内部逐条调 sudo）
#   sudo bash install-server.sh buaa    有 sudo 的账号替另一个账号装
# 第二种必须用 root：执行者与 CN_USER 不是同一个用户时，读 600 的 cn-env.sh
# 会 Permission denied。2026-10-22 实际踩到，表现为
# 「line 179: /home/buaa/cn/conf/cn-env.sh: Permission denied」后中断。
# 产物属主在后面的 chown 里统一交给 CN_USER，进程仍由 systemd 以 User=$CN_USER 拉起。
if [ "$(id -u)" -eq 0 ] && [ -z "${SUDO_USER:-}" ]; then
  echo "提示：以 root 直接运行，产物属主将交给 ${CN_USER}" >&2
fi

echo "登录用户：$CN_USER    安装目录：$CN_HOME"

log "检查安装包完整性"
for f in app/core-service.jar app/query-service.jar app/ingestion-service.jar \
         web/index.html sql/core_db.dump sql/query_db.dump sql/ingestion_db.dump; do
  [ -f "$SCRIPT_DIR/$f" ] || die "缺少 $f"
done
echo "三个 jar、前端产物、三个 dump 均存在"

log "安装系统依赖"
export DEBIAN_FRONTEND=noninteractive
sudo apt-get update -qq

# JDK：必须与 pom.xml 的 maven.compiler.release 一致，当前是 21。
# 装 17 会在启动时直接 UnsupportedClassVersionError（class file version 65.0（=21），
# 而 17 的运行时只认到 61.0），三个服务全部起不来且日志里只有这一行 StackTrace。
# 2026-10-22 实际踩到：脚本原先按「>=17 即可」判断，装成 17，服务全灭。
# 因此这里不再看 /usr/bin/java 指向谁（多版本共存时它可能仍指 17），
# 而是直接按目录找 21 的可执行文件，并把绝对路径写进 systemd 单元。
JAVA_MAJOR=21
JAVA_BIN="$(ls -d /usr/lib/jvm/java-${JAVA_MAJOR}-openjdk-*/bin/java 2>/dev/null | head -1)"
if [ -z "$JAVA_BIN" ]; then
  echo "未找到 Java ${JAVA_MAJOR}，安装 openjdk-${JAVA_MAJOR}-jre-headless"
  sudo apt-get install -y -qq "openjdk-${JAVA_MAJOR}-jre-headless" >/dev/null
  JAVA_BIN="$(ls -d /usr/lib/jvm/java-${JAVA_MAJOR}-openjdk-*/bin/java 2>/dev/null | head -1)"
  [ -n "$JAVA_BIN" ] || die "安装后仍未找到 Java ${JAVA_MAJOR}，请检查 apt 源是否提供 openjdk-${JAVA_MAJOR}-jre-headless"
else
  echo "已安装 Java ${JAVA_MAJOR}，跳过安装"
fi
echo "使用 $JAVA_BIN"
"$JAVA_BIN" -version 2>&1 | head -1
# 不要再用裸 java -version 判断版本：PATH 里的 java 多版本共存时可能仍指向旧版，
# 那正是 2026-10-22 那次误判的来源。以 $JAVA_BIN 与 systemd 的 ExecStart 为准。

# redis 与 nginx 缺什么装什么；已装的 apt 会直接跳过。
sudo apt-get install -y -qq redis-server nginx curl >/dev/null

log "确认 PostgreSQL 版本"
PG_VER=""
if command -v psql >/dev/null 2>&1; then
  PG_VER="$(psql --version | grep -oE '[0-9]+' | head -1)"
fi
if [ -z "$PG_VER" ] || [ "$PG_VER" -lt 16 ]; then
  echo "系统自带 PostgreSQL ${PG_VER:-未安装}，低于 16；改用 PGDG 源安装 16"
  sudo install -d /usr/share/postgresql-common/pgdg
  sudo curl -fsSL -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc \
    https://www.postgresql.org/media/keys/ACCC4CF8.asc
  . /etc/os-release
  echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt ${VERSION_CODENAME}-pgdg main" \
    | sudo tee /etc/apt/sources.list.d/pgdg.list >/dev/null
  sudo apt-get update -qq
  sudo apt-get install -y -qq postgresql-16 >/dev/null
  sudo systemctl enable --now postgresql
else
  echo "系统自带 PostgreSQL $PG_VER，满足要求"
fi
PG_BIN="$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -V | tail -1)"
echo "使用 $PG_BIN"

log "创建运行目录"
# 一律带 sudo 且用 -o/-g 直接定属主：安装者可能不是 CN_USER（有 sudo 的账号替别人装），
# 这种情形下不带 sudo 的 install -d 写不进对方 750 的家目录，会在这里就 Permission denied。
sudo install -d -m 755 -o "$CN_USER" -g "$CN_USER" "$CN_HOME"/{app,sql,conf,logs,storage}
sudo install -d -m 755 -o "$CN_USER" -g "$CN_USER" "$CN_HOME"/storage/claim-evidence "$CN_HOME"/storage/scholar-avatar
sudo cp -f "$SCRIPT_DIR"/app/*.jar "$CN_HOME/app/"
sudo cp -f "$SCRIPT_DIR"/sql/*.dump "$CN_HOME/sql/"
sudo chown -R "$CN_USER:$CN_USER" "$CN_HOME"/app "$CN_HOME"/sql "$CN_HOME"/storage

log "部署前端产物到 /var/www/cn"
# 不能放家目录：/home/ubuntu 是 750，nginx worker（www-data）无法 stat()，
# 表现为首页 500 且 error.log 写 Permission denied。
sudo rm -rf /var/www/cn
sudo install -d -m 755 /var/www/cn
sudo cp -r "$SCRIPT_DIR/web"/. /var/www/cn/
sudo chmod -R a+rX /var/www/cn
echo "首页文件：$(sudo ls /var/www/cn/index.html)"

log "生成配置与口令"
# 三个 systemd 服务共用同一份 EnvironmentFile，因此这里只能放 CN_* 前缀的变量。
# ⚠️ 绝对不要放 SPRING_DATASOURCE_URL / SPRING_FLYWAY_URL：它们会被三个服务同时读到，
# 结果 query 和 ingestion 都连到 core_db 上，表现为「表不存在 / 查到的数据不对」。
# 三个服务的连接串在各自 application.yml 里是写死的，本来就无需覆盖。
if [ -f "$CN_HOME/conf/cn-env.sh" ]; then
  echo "已存在 cn-env.sh，保留原有口令（如需重置请先备份并删除该文件）"
  # shellcheck disable=SC1091
  . "$CN_HOME/conf/cn-env.sh"
else
  # 口令生成器必须保证同时含字母与数字：PasswordPolicy 会拒绝纯字母。
  # 直接用 base64 去掉符号有概率产出纯字母（实际踩到过），所以用固定字母前缀 + 十六进制 + 一位数字。
  gen() { echo "Cn$(openssl rand -hex 11)$((RANDOM % 10))"; }
  CN_CORE_DB_OWNER_PASSWORD="$(gen)";      CN_CORE_DB_PASSWORD="$(gen)"
  CN_QUERY_DB_OWNER_PASSWORD="$(gen)";     CN_QUERY_DB_PASSWORD="$(gen)"
  CN_INGESTION_DB_OWNER_PASSWORD="$(gen)"; CN_INGESTION_DB_PASSWORD="$(gen)"
  CN_SESSION_REDIS_PASSWORD="$(gen)";      CN_CACHE_REDIS_PASSWORD="$(gen)"
  CN_INGESTION_SERVICE_TOKEN="$(gen)";     CN_QUERY_SERVICE_TOKEN="$(gen)"
  CN_BOOTSTRAP_ADMIN_PASSWORD="$(gen)"

  sudo tee "$CN_HOME/conf/cn-env.sh" >/dev/null <<EOF
# 由 install-server.sh 于 $(date '+%Y-%m-%d %H:%M:%S') 生成。含全部生产口令，权限 600。
# systemd 的 EnvironmentFile 只接受裸 KEY=VALUE，不能写 export；值含空格或括号时必须加引号。

# —— 数据库：三套账号各自独立，运行账号只有 DML 权限 ——
CN_CORE_DB_PASSWORD=$CN_CORE_DB_PASSWORD
CN_CORE_DB_OWNER_PASSWORD=$CN_CORE_DB_OWNER_PASSWORD
CN_QUERY_DB_PASSWORD=$CN_QUERY_DB_PASSWORD
CN_QUERY_DB_OWNER_PASSWORD=$CN_QUERY_DB_OWNER_PASSWORD
CN_INGESTION_DB_PASSWORD=$CN_INGESTION_DB_PASSWORD
CN_INGESTION_DB_OWNER_PASSWORD=$CN_INGESTION_DB_OWNER_PASSWORD

# —— Redis：core 用 6379（会话），query/ingestion 用 6380（缓存）——
CN_SESSION_REDIS_PASSWORD=$CN_SESSION_REDIS_PASSWORD
CN_CACHE_REDIS_PASSWORD=$CN_CACHE_REDIS_PASSWORD

# —— 服务间令牌 ——
# 三条链路，每条两侧必须是同一个值，但变量名不能混用：
#   core → query      出站 CN_QUERY_SERVICE_TOKEN      / query 入站 CN_QUERY_INBOUND_CORE_TOKEN
#   core → ingestion  出站 CN_INGESTION_SERVICE_TOKEN  / ingestion 入站 CN_INGESTION_INBOUND_CORE_TOKEN
#   query/ingestion → core  出站各自的 *_SERVICE_TOKEN / core 入站按服务名查同一组同名变量
# 为什么不共用：三个服务读同一份 EnvironmentFile，CN_QUERY_SERVICE_TOKEN 在 core 里
# 是「我调 query 时出示的」，在 query 里是「我调 core 时出示的」，同名不同义；
# 旧写法用 CN_INTERNAL_SERVICE_TOKENS_CORE 统一给接收方用一个值，结果与 core 出站
# 的三个值全都对不上，线上表现为事件投递 403、投影水位停在 21270 不动。
# ⚠️ 仍然绝不能出现 CN_CORE_SERVICE_TOKEN：它经宽松绑定会命中 cn.core.service-token，
# 把 ingestion 调 core 时出示的令牌覆盖掉，导致采集管理全线 403。
CN_INGESTION_SERVICE_TOKEN=$CN_INGESTION_SERVICE_TOKEN
CN_QUERY_SERVICE_TOKEN=$CN_QUERY_SERVICE_TOKEN
CN_QUERY_INBOUND_CORE_TOKEN=$CN_QUERY_SERVICE_TOKEN
CN_INGESTION_INBOUND_CORE_TOKEN=$CN_INGESTION_SERVICE_TOKEN
CN_CORE_BASE_URL=http://127.0.0.1:8081
CN_QUERY_INBOX_URL=http://127.0.0.1:8083/internal/v1/inbox/batches

# —— 首个平台管理员（仅在库中没有活跃管理员时创建一次）——
CN_BOOTSTRAP_ADMIN_USERNAME=cnadmin
CN_BOOTSTRAP_ADMIN_PASSWORD=$CN_BOOTSTRAP_ADMIN_PASSWORD
CN_BOOTSTRAP_ADMIN_DISPLAY_NAME=平台管理员

# —— 存储目录：默认值写死在代码里是 Windows 路径，必须覆盖 ——
CN_CLAIM_EVIDENCE_DIR=$CN_HOME/storage/claim-evidence
CN_SCHOLAR_AVATAR_DIR=$CN_HOME/storage/scholar-avatar

# —— 上传上限：与 nginx 的 client_max_body_size 保持一致 ——
SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE=15MB
SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE=20MB

# —— 采集器自我标识：Crossref 要求可联系的 UA，上线前改成真实邮箱 ——
# 值里有空格和括号，必须加引号；否则 source 这份文件时 bash 会把括号当成语法错误。
CN_COLLECT_USER_AGENT="CognitaNexus/0.2 (mailto:cn-admin@example.edu.cn)"

# —— OpenAlex 免费 API key ——
# 留空 = 匿名调用：每日预算 0.1 美元且与同出口 IP 的其他人共享，实测极易被吃光，
# 表现为 /v3/api-docs 之外的一切上游请求 403（body 里写明 budget 用完）。
# 有 key：每日 1 美元、30 req/s，补满全站引用约需 0.1 美元。
# 值在 https://openalex.org/settings/api-key 取，填完重启 cn-ingestion 生效。
# 这个文件只在首次安装时生成（见上面的 if），重装不会覆盖已有值。
CN_OPENALEX_API_KEY=
EOF
  sudo chmod 600 "$CN_HOME/conf/cn-env.sh"
fi
# shellcheck disable=SC1091
. "$CN_HOME/conf/cn-env.sh"

# 旧的（开发期手工生成的）env 可能缺少某些变量，set -u 会在这里炸出变量名，
# 比等到建角色时才报错更容易定位。
for v in CN_CORE_DB_OWNER_PASSWORD CN_CORE_DB_PASSWORD \
         CN_QUERY_DB_OWNER_PASSWORD CN_QUERY_DB_PASSWORD \
         CN_INGESTION_DB_OWNER_PASSWORD CN_INGESTION_DB_PASSWORD \
         CN_SESSION_REDIS_PASSWORD CN_CACHE_REDIS_PASSWORD \
         CN_INGESTION_SERVICE_TOKEN CN_QUERY_SERVICE_TOKEN \
         CN_QUERY_INBOUND_CORE_TOKEN CN_INGESTION_INBOUND_CORE_TOKEN \
         CN_BOOTSTRAP_ADMIN_PASSWORD; do
  [ -n "${!v:-}" ] || die "cn-env.sh 缺少 $v，请删除该文件后重跑本脚本以重新生成"
done

# 运行账号必须能写 logs 与 storage、能读 conf（systemd 以 User=$CN_USER 起进程）。
# 前面的 install -d、cp 与 cat > cn-env.sh 都是在 root 下做的，属主是 root，
# 不交还属主的话服务一起来就 Permission denied，日志里看不出根因。
chown -R "$CN_USER:$CN_USER" "$CN_HOME"

log "配置 Redis 双实例"
# 6379 用系统自带实例，只加口令；6380 另起一份配置，作为缓存独立进程。
sudo sed -i "s/^# *requirepass .*/requirepass ${CN_SESSION_REDIS_PASSWORD}/" /etc/redis/redis.conf
if ! sudo grep -q "^requirepass" /etc/redis/redis.conf; then
  echo "requirepass ${CN_SESSION_REDIS_PASSWORD}" | sudo tee -a /etc/redis/redis.conf >/dev/null
fi
sudo systemctl enable --now redis-server >/dev/null 2>&1 || sudo systemctl restart redis-server

if ! systemctl list-unit-files | grep -q '^redis-cache.service'; then
  sudo cp /etc/redis/redis.conf /etc/redis/redis-cache.conf
  sudo sed -i -e 's/^port .*/port 6380/' \
              -e 's|^pidfile .*|pidfile /run/redis/redis-cache.pid|' \
              -e 's|^logfile .*|logfile /var/log/redis/redis-cache.log|' \
              -e 's|^dbfilename .*|dbfilename dump-cache.rdb|' \
              -e 's|^dir .*|dir /var/lib/redis-cache|' /etc/redis/redis-cache.conf
  sudo sed -i "s/^# *requirepass .*/requirepass ${CN_CACHE_REDIS_PASSWORD}/" /etc/redis/redis-cache.conf
  # 缓存可以丢，关掉持久化省磁盘；同时限制内存，避免占满整机。
  sudo sed -i -e 's/^save .*/save ""/' \
              -e 's/^# *maxmemory .*/maxmemory 512mb/' \
              -e 's/^# *maxmemory-policy .*/maxmemory-policy allkeys-lru/' /etc/redis/redis-cache.conf
  sudo install -d -o redis -g redis /var/lib/redis-cache
  sudo tee /etc/systemd/system/redis-cache.service >/dev/null <<'UNIT'
[Unit]
Description=Redis cache instance (6380)
After=network.target
[Service]
# 必须照抄系统自带 redis-server 单元的做法：
# Ubuntu 的 redis.conf 里是 daemonize yes，若不在命令行强制 --daemonize no，
# redis 会 fork 到后台、父进程立刻退出，systemd 判定「服务已结束」并反复重启，
# 最终 start-limit-hit。同时要 Type=notify 并保留 --supervised systemd，否则 NOTIFY_SOCKET 缺失。
Type=notify
User=redis
Group=redis
ExecStart=/usr/bin/redis-server /etc/redis/redis-cache.conf --supervised systemd --daemonize no
Restart=always
[Install]
WantedBy=multi-user.target
UNIT
  sudo systemctl daemon-reload
fi
# 口令每次都以 cn-env.sh 为准重新写入。
# 这段必须在 if 外面：cn-env.sh 若被重新生成，redis-cache.conf 里的旧 requirepass
# 就会和应用侧对不上，现象是 WRONGPASS，而且服务本身是 active 的，很容易误判成别的问题。
sudo sed -i "s/^# *requirepass .*/requirepass ${CN_CACHE_REDIS_PASSWORD}/;s/^requirepass .*/requirepass ${CN_CACHE_REDIS_PASSWORD}/" /etc/redis/redis-cache.conf
sudo systemctl reset-failed redis-cache >/dev/null 2>&1 || true
sudo systemctl enable --now redis-cache >/dev/null 2>&1 || sudo systemctl restart redis-cache

log "建库、建角色、恢复数据"
sudo -u postgres psql -v ON_ERROR_STOP=1 -q <<SQL
DO \$\$
DECLARE r record;
BEGIN
  FOR r IN SELECT * FROM (VALUES
      ('core_owner','${CN_CORE_DB_OWNER_PASSWORD}'), ('core_app','${CN_CORE_DB_PASSWORD}'),
      ('query_owner','${CN_QUERY_DB_OWNER_PASSWORD}'), ('query_app','${CN_QUERY_DB_PASSWORD}'),
      ('ingestion_owner','${CN_INGESTION_DB_OWNER_PASSWORD}'), ('ingestion_app','${CN_INGESTION_DB_PASSWORD}')
    ) AS t(name, pw)
  LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r.name) THEN
      EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', r.name, r.pw);
    ELSE
      EXECUTE format('ALTER ROLE %I PASSWORD %L', r.name, r.pw);
    END IF;
  END LOOP;
END
\$\$;
SQL

for pair in "core_db:core_owner" "query_db:query_owner" "ingestion_db:ingestion_owner"; do
  db="${pair%%:*}"; owner="${pair##*:}"
  if ! sudo -u postgres psql -Atc "SELECT 1 FROM pg_database WHERE datname='$db'" | grep -q 1; then
    sudo -u postgres createdb -O "$owner" "$db"
    echo "已创建 $db（owner=$owner）"
  fi
done

# 恢复：dump 里带 flyway_schema_history，恢复后应用启动时 Flyway 会校验通过，
# 不会重放迁移；千万不要在恢复后又手工清空该表。
#
# 两点必须注意：
#  1) pg_restore 以 postgres 用户运行，进不去 /home/ubuntu（家目录是 750），
#     所以先复制到 /tmp 再恢复，不要为了省这一步去改家目录权限。
#  2) 不能用 --no-acl：dump 里 core_app/query_app 等角色的 GRANT 会被一起丢掉，
#     恢复后应用连得上库却读不了表，报权限不足。角色已在上一步建好，正常恢复即可。
for db in core_db query_db ingestion_db; do
  echo "恢复 $db ..."
  sudo -u postgres psql -q -d "$db" -c "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;" >/dev/null
  tmpdump="/tmp/cn-$db.dump"
  cp -f "$CN_HOME/sql/$db.dump" "$tmpdump"
  chmod 644 "$tmpdump"
  sudo -u postgres "$PG_BIN/pg_restore" -d "$db" --jobs=2 "$tmpdump"
  rm -f "$tmpdump"
  n=$(sudo -u postgres psql -Atc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'" -d "$db")
  echo "$db: $n 张表"
done

log "安装 systemd 服务"
# unit 与 nginx 配置里写的是 ubuntu 与 /home/ubuntu/cn；按实际用户替换后再装。
# 同时覆盖 .service 与 .timer：备份用的是定时器，漏掉 *.timer 会让备份永远不触发。
for u in "$SCRIPT_DIR"/systemd/cn-*.service "$SCRIPT_DIR"/systemd/cn-*.timer; do
  [ -e "$u" ] || continue
  sed -e "s|User=ubuntu|User=$CN_USER|" \
      -e "s|Group=ubuntu|Group=$CN_USER|" \
      -e "s|/home/ubuntu/cn|$CN_HOME|g" \
      -e "s|/usr/bin/java|$JAVA_BIN|g" "$u" \
    | sudo tee "/etc/systemd/system/$(basename "$u")" >/dev/null
done
sudo systemctl daemon-reload
sudo systemctl enable cn-core cn-query cn-ingestion >/dev/null

# 日志上限：首次安装就设好，否则要等第一次磁盘告警才想起来。
# CI 每次部署也会再调一次（脚本幂等），保证规则与仓库一致。
if [ -f "$SCRIPT_DIR/install-logging.sh" ]; then
  CN_LOGGING_USER="$CN_USER" bash "$SCRIPT_DIR/install-logging.sh" \
    || echo "警告：日志上限配置失败，不影响本次安装，可稍后单独重跑"
fi

log "配置 nginx"
sed "s|/home/ubuntu/cn|$CN_HOME|g" "$SCRIPT_DIR/nginx-cn.conf" | sudo tee /etc/nginx/sites-available/cn >/dev/null
sudo ln -sf /etc/nginx/sites-available/cn /etc/nginx/sites-enabled/cn
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
# reload 只对已在运行的服务有效；镜像里 nginx 可能装了但从未启动（Oracle 的 Ubuntu 镜像就是）。
# 直接 enable --now 幂等：已在跑就是空操作，没跑就启动。
sudo systemctl enable nginx >/dev/null 2>&1 || true
sudo systemctl restart nginx

log "启动服务"
sudo systemctl restart cn-core
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://127.0.0.1:8081/actuator/health || true)
  [ "$code" = "200" ] && { echo "core 就绪（${i}0 秒内）"; break; }
  sleep 2
done
sudo systemctl restart cn-query cn-ingestion
sleep 20

log "自检"
for p in 8081 8082 8083; do
  printf '%s ' "$p"
  curl -s -o /dev/null -w '%{http_code}\n' --max-time 5 "http://127.0.0.1:$p/actuator/health" || echo "无响应"
done
printf 'nginx 首页 '
curl -s -o /dev/null -w '%{http_code}\n' --max-time 5 http://127.0.0.1/
printf '经 nginx 的公开检索 '
curl -s -o /dev/null -w '%{http_code}\n' --max-time 8 'http://127.0.0.1/query/api/v1/public/search/achievements?page=1&size=1'

cat <<EOF

部署完成。

  管理员账号：${CN_BOOTSTRAP_ADMIN_USERNAME}
  管理员密码：$(grep '^CN_BOOTSTRAP_ADMIN_PASSWORD=' "$CN_HOME/conf/cn-env.sh" | cut -d= -f2)
  （同时保存在 $CN_HOME/conf/cn-env.sh，权限 600，登录后请尽快改掉）

  站点：http://<服务器公网IP>/
  注意：还要在云控制台的安全组/防火墙里放行 TCP 80，这一步脚本改不了。

  常用命令：
    sudo systemctl status cn-core cn-query cn-ingestion
    sudo systemctl restart cn-core
    tail -f $CN_HOME/logs/core.log
EOF
