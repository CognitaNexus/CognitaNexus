# 前端浏览器级断言（tests/ui）

两个脚本，都对着**真实运行的整栈**跑，不使用任何 mock：

| 脚本 | 断言什么 | 为什么必须它来断言 |
| --- | --- | --- |
| `shoot-pages.mjs` | 逐页截图，同时采集每页的控制台 error、未捕获异常、失败的 XHR/fetch；详情页与窄屏（820 / 390）还核对页面上真的画出了内容 | 只截图不看运行期异常，等于只验证了「长得像」。管理类页面最容易出的问题恰恰是渲染时抛错，页面白屏或半渲染，截图看不太出来 |
| `check-batch-citation.mjs` | 检索页「勾选 → 批量导出引用」全链路：勾选框与结果卡片对齐、已选计数、预览面板、下载地址返回 200 且正文与预览逐字一致、换格式后旧预览作废、国标编号 `[1]`、全程无 4xx/5xx | 勾选与预览的纯函数有单测（`frontend/tests/citationView.test.ts`），但「DOM 上点得到、点完真有反应」只有真浏览器能回答 |

## 前置

1. 整栈已启动：`bash ops/local/stack.sh`（PostgreSQL、两个 Redis、三个服务、前端 dev server 5173）。
2. 本目录装一次依赖：`cd tests/ui && npm install`。
3. 本机有 Chrome。默认取 `C:/Program Files/Google/Chrome/Application/chrome.exe`，其他位置用 `CN_CHROME` 覆盖。

## 运行

```bash
cd tests/ui
npm run check          # 两个脚本依次跑
npm run shoot          # 只跑逐页截图 + 异常采集
npm run batch          # 只跑批量引用导出
```

## 可覆盖的环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `CN_CHROME` | `C:/Program Files/Google/Chrome/Application/chrome.exe` | 浏览器可执行文件 |
| `CN_WEB` | `http://127.0.0.1:5173` | 前端地址 |
| `CN_QUERY` | `http://127.0.0.1:8083` | query 服务地址（脚本直连它取真实成果/学者/领域标识） |
| `CN_SHOTS` | `tests/ui/.shots/` | 截图目录 |
| `CN_USER` / `CN_PASS` | `cnadmin` / 本地种子口令 | 登录账号，脚本走真实登录表单而不是注入 cookie |

## 判读

- 两个脚本都是**全量跑完再汇总**，失败项逐条打印 `FAIL … -> 细节`，进程退出码非 0。
- `shoot-pages.mjs` 里有一份 `EXPECTED` 白名单（`/core/api/v1/auth/me` 未登录返回 401、`/core/internal/` 前缀），这些是设计行为，不算异常。新增页面若出现新的「设计内 4xx」，要加进白名单并写明原因，而不是放宽断言。
- 截图落在 `tests/ui/.shots/`，属可重建产物，不入库。

## 已知边界

- 登录口令默认取本地种子账号。换环境必须用 `CN_USER` / `CN_PASS` 覆盖，脚本不自带账号创建。
- 这两套是**人工触发的断言**，没有接进 `mvn verify` 或 `npm test`；它们依赖整栈与真实浏览器，属交付前自检，不是 CI 门禁。
- 覆盖的是「页面能打开、内容画得出、导出链路通」；不做视觉回归（没有基线图比对），也不做并发与性能（那属 `tests/perf/`）。
