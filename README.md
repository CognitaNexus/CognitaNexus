# CognitaNexus 学术成果分享平台（工程实现仓库）

以学者为中心的学术成果聚合、共享与管理平台，覆盖论文、专利、科研项目、获奖四类成果。

本仓库是**工程实现仓库**。需求基线、SRS、核心 SPEC 与 UML 图源维护在独立的**需求仓库**中，本仓库不复制、不改写需求语义。

## 当前阶段

项目处于**实现阶段**：三个后端服务与前端已完成主体实现，并在本机以真实公开来源数据跑通了完整读写链路。

| 阶段 | 状态 |
| --- | --- |
| 需求调研 / 需求建模 / SRS 与核心 SPEC | 已在需求仓库完成（V1.9 基线、双组实施候选 SRS V1.1、核心 SPEC V1.0） |
| 架构设计 | 已完成，本仓库承载（架构说明书、6 条 ADR、编码规范与协作规范） |
| 构件设计（数据库、接口、UI） | 已随实现落地：迁移脚本、接口、前端页面均已产出 |
| 实现与测试 | 进行中 |

**状态口径**：本文中「已实测」指在当前本机环境执行过并留有输出；「未实测」一律标明，不得按已完成引用。需求侧的性质（P95、覆盖率、并发上限）仍**待实测**，本文不给出任何未经测量的数值承诺。

## 需求依据（外部仓库）

本仓库不维护需求，下列材料是架构与编码的唯一上位依据：

| 依据 | 说明 |
| --- | --- |
| 需求基线 V1.9（学术成果分享平台需求分析与可行性报告） | 决定 FR01～FR16、NF01～NF08、IN01、O01/O02 的语义与优先级 |
| 双组工程实施共同 SRS V1.1（Implementation RC） | 当前唯一工程实施候选，AC-IMPL-* 验收编号来源；尚未双组签认 |
| 核心 SPEC V1.0（Review Candidate） | 清洗、去重、消歧、检索排序等关键规则细化；规则未冻结 |
| UML 图源（docs/uml/） | PlantUML 为唯一维护源 |

需求仓库地址：https://github.com/CognitaNexus/CognitaNexus 。本机副本位于 D:/code/文档/CognitaNexus-main (1)/CognitaNexus-main 。

**冲突处理**：本仓库文档与需求基线冲突时，以需求基线为准；不得在本仓库自行修改业务语义。发现冲突应登记为待联合评审事项。

## 仓库结构

```text
academicplatform/
├── compose.yaml                 # 一键起全栈（PostgreSQL + 两个 Redis + 三服务 + nginx）
├── .env.example                 # 配置样例，仅变量名与开发默认值
├── .dockerignore
├── pom.xml                      # Maven 父 POM（Java 21 / Spring Boot 3.5）
├── libraries/shared-kernel/     # 跨服务共享的最小内核（错误码、响应外壳、标识生成）
├── services/
│   ├── core-service/            # 8081：身份、目录、门户、治理、事实来源（含 Dockerfile）
│   ├── ingestion-service/       # 8082：来源采集与交接（含 Dockerfile）
│   └── query-service/           # 8083：读投影、检索、统计、合作网络（含 Dockerfile）
├── frontend/                    # Vue 3 + TypeScript + Vite（含 Dockerfile 与 nginx.conf）
├── ops/
│   ├── local/                   # 本机原生二进制启停、采集驱动、库重置
│   └── compose/                 # 容器拓扑的服务器覆盖与数据库初始化
├── tests/api/                   # 三个服务的端到端 HTTP 自检
└── docs/
    ├── architecture/            # 架构设计说明书与 ADR
    └── standards/               # 编码规范、协作规范
```

三个服务各自拥有独立数据库、独立迁移历史与独立运行账号，**不存在跨库访问**：它们只通过 HTTP 内部端点与投递事件通信。

## 本机运行

本机 Docker Desktop 依赖 WSL2，而本机 WSL 被安全策略拦截，容器无法启动。因此本机开发使用 D 盘原生二进制运行 PostgreSQL 16 与 Redis 8，服务器部署使用 `compose.yaml`。

```bash
# 1) 基础设施（PostgreSQL 5432 + redis-session 6379 + redis-cache 6380）
bash ops/local/start-infra.sh

# 2) 应用构建
./mvnw -B -DskipTests install                     # 三个服务 + 共享内核
cd frontend && npm install && cd ..

# 3) 启动三个服务
bash ops/local/service.sh start all
bash ops/local/service.sh status

# 4) 前端开发服务器（http://127.0.0.1:5173，经代理转发三个后端）
cd frontend && npm run dev
```

首次启动时，`core-service` 会在库中不存在活跃平台管理员的情况下，用 `CN_BOOTSTRAP_ADMIN_*` 创建一次引导管理员账号。

驱动一次真实来源采集：

```bash
# 以管理员登录、同步来源权威配置、创建幂等采集任务并执行一轮
bash ops/local/collect-once.sh crossref
```

### 开发期必须知道的三件事

1. **代理**：宿主注入了 `http_proxy`，对本机服务的探测必须显式 `--noproxy '*'`，否则会把回环请求送去代理并得到 502。
2. **端口注入**：宿主会注入 `SERVER__PORT`，Spring 的宽松绑定会把它当成 `server.port`。`service.sh` 因此一律显式传 `--server.port`。
3. **`java -jar` 会锁住 jar**：运行中执行 `mvn install` 会在 `repackage` 阶段失败。构建前先 `service.sh stop`。

## 验证状态

### 已实测（本机，真实来源数据）

| 项目 | 结果 |
| --- | --- |
| 三服务端到端自检 `tests/api/smoke-core.sh` | 通过 17 / 失败 0 |
| 三服务端到端自检 `tests/api/smoke-query.sh` | 通过 88 / 失败 0（含公开资格一致性抽样） |
| 真实来源采集（Crossref REST） | 公开成果 6 999 篇、引用量覆盖 5 306 篇（合计 54 178），来源均标注为 Crossref |
| 读链路规模 | 学者 19 807 名、参与关系 25 555 条、合作者对 29 341 对（发布修订 10 270） |
| 投影水位一致性 | 接收 / 已见最高 / 已发布三水位齐平，无修订空洞，`serving=true` |
| 公开资格一致性 | 抽样 30 条检索命中项，权威侧公开详情 200/30 |

上表数字来自一次本地运行中某一时刻的读数。入库任务在持续写入，数字会继续增长；引用「规模」时必须同时给出时刻。

### 未实测（不得按已完成引用）

- **容器拓扑**：`compose.yaml` 与 `ops/compose/compose.server.yaml` 只通过了 `docker compose config` 的配置解析校验，**镜像未构建、容器未启动**（Docker 守护进程在本机不可用）。详见 `ops/compose/README.md` 的待实测清单。
- **服务器部署**：未在任何服务器上部署过。
- **单元测试与覆盖率**：仓库尚无 Java 测试源文件，`jacoco` 无数据。当前验证手段是端到端 HTTP 自检。
- **性能**：未做压测。P95 延迟、并发上限、投影吞吐均为**待实测**，架构文档中的上限值是起始配置而非测量结论。
- **前端**：类型检查与生产构建通过，页面已在本机真机浏览器渲染核对；未做跨浏览器与无障碍验证。

### 已修复的一处真实缺陷（回归守卫在案）

来源接入在「既有成果刷新引用量指标」这条路径上外发事件时，把可见性写死成 `PUBLIC`，而该成果在权威表里仍是 `RESTRICTED / PENDING_GOVERNANCE`。后果是公开检索能搜到它、点进详情却是 404——读者看到「平台收录了这条」，而平台并未确认过它。

修复方式是把可见性改为取自持久化实体，取不到实体时**失败关闭**（按受限外发），而不是沿用「本方法只在发布之后被调用」这个隐含前提。`tests/api/smoke-query.sh` 第 16 节把「检索可命中 ⇒ 详情可打开」固化为回归断言，且该断言不依赖字段命名与数据库连接。

## 贡献流程

1. 从 main 切出分支，命名与提交信息遵循 docs/standards 下的协作规范。
2. 变更必须关联 FR / AC-IMPL 编号，并在 PR 描述中说明验证方式与未验证项。
3. 禁止直接向 main 推送、禁止强制推送、禁止提交密钥与本地数据文件。
4. 文档类变更至少 1 人评审；涉及业务语义的变更须回到需求仓库走联合评审。

## 环境前置

JDK 21、Node.js 20+、Maven 3.9+（或使用仓库自带的 `mvnw`）。服务器部署另需 Docker 与 Compose 插件。
