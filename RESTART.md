# 关机后从这里重启

本机不能用 Docker（WSL 被安全策略拦截），PostgreSQL 与 Redis 走 D 盘原生二进制，三个服务与前端走 jar 与 vite。
整栈只有**一个入口**：`ops/local/stack.sh`。

## 一、起整栈（一条命令）

```bash
cd /d/code/academicplatform
bash ops/local/stack.sh
```

它会依次起 PostgreSQL 5432、redis-session 6379、redis-cache 6380、core 8081、ingestion 8082、query 8083、
前端 dev server 5173，然后**前台阻塞**。所以：

- 在普通终端里执行：别关这个窗口，按 Ctrl-C 即停整栈。
- 在对话里让 WorkBuddy 执行：必须以后台任务承载（沙箱是「一次调用一个进程组」，前台调用一返回整组就被回收，
  日志里会出现「Started」但端口不监听）。任务列表里只会多一条记录，结束它就是停整栈。

## 二、确认起好了

```bash
bash ops/local/stack.sh status
```

七个组件全部「运行中」才算起好。服务启动慢，第一次别急，等 core 日志出现 `Started CoreServiceApplication`。
浏览器打开 http://127.0.0.1:5173 。

## 三、账号

| 用途 | 用户名 | 密码 | 能看到什么 |
| --- | --- | --- | --- |
| 管理员 | cnadmin | cn-admin-Dev-2026 | 治理台、审核、关系争议、采集、审计、账号 |
| 普通用户 | demo | Demo12345678 | 我的门户（空的）、认领申请、通知、我的成果 |

读者侧（检索、热点、合作网络、成果详情）不需要登录。demo 的「我的门户」为空是真实状态：
它没有绑定任何学者身份，要走「认领学者」并让 cnadmin 审批后才有内容。

## 四、想验证功能还活着

```bash
bash tests/api/smoke-core.sh          # 21 项
bash tests/api/smoke-query.sh         # 90 项
cd frontend && npm test               # 11 项纯函数
```

浏览器级断言（需本机 Chrome，首次先 `cd tests/ui && npm install`）：

```bash
cd tests/ui && npm run check
```

## 五、关机前停栈（2026-10-18 已执行，实测干净）

```bash
bash ops/local/stack.sh stop
```

顺序是服务优雅停机（20 秒超时后强制）→ Redis 6379 落盘、6380 nosave → PG `pg_ctl stop -m fast`。

**判据看端口，不看命令输出**：在受管沙箱里这条命令可能长时间不返回（前台调用还会被自己发出的 SIGTERM 打断），
但它已经在后台把栈停完 —— 本次实测命令挂了 4 分半，检查时七个端口全释放、java / postgres / redis 进程数均为 0。

```bash
for p in 5432 6379 6380 8081 8082 8083 5173; do
  (echo > /dev/tcp/127.0.0.1/$p) >/dev/null 2>&1 && echo "$p 仍在" || echo "$p 已停"
done
```

若确实还在，`taskkill //F //IM java.exe` 兜底（成对杀掉 javapath 启动器与真实 JDK 进程）。
直接关 Windows 也不会坏库（PG 会收到终止信号做快速关闭），但先停一次更稳妥，明天起来端口也是干净的。

**不要**在整栈运行时执行 `./mvnw install`：jar 被运行中的进程锁住，`repackage` 会以
`Unable to rename ... .jar.original` 失败。要先停栈，构建完再起。

## 六、起不来时先看这三条

1. **端口被占**：上次没停干净。`bash ops/local/stack.sh status` 看是谁占着，再 `stop`；
   别用 `start-infra.sh` 和 `stack.sh` 混着起，会各记一份状态。
2. **回环请求得到 502**：宿主注入了 `http_proxy`，本机探测必须加 `--noproxy '*'`。
3. **psql 报 `invalid byte sequence for encoding "UTF8"`**：中文别走 `-c` 参数（Windows argv 按 CP936 转码会破坏 UTF-8），
   一律走标准输入，并设 `PGCLIENTENCODING=UTF8`。

更多踩过的坑见 `.workbuddy/memory/PITFALLS-OPS.md`（运行与构建）与 `PITFALLS-CODE.md`（业务与代码约束）。
