# CognitaNexus 学术成果分享平台 · 项目交接文档

> 编写日期：2026-10-24　｜　文档性质：面向接手人的全景交接
> 本文所有数字均在 2026-10-24 从线上库或本机构建实测取得，取数命令附在文末。未经实测的内容会明确标注「未实测」。

---

## 一、三十秒速览

| 项目 | 内容 |
| --- | --- |
| 线上地址 | **http://168.110.3.136/** |
| 接口文档 | http://168.110.3.136/core/swagger-ui/index.html （query、ingest 同理换前缀） |
| 代码仓库 | https://github.com/CognitaNexus/CognitaNexus ，分支 **dev**（GitHub 账号 DIO6666666） |
| 需求仓库 | https://github.com/CognitaNexus/CognitaNexus （另一套，本机副本 D:/code/文档/） |
| 服务器 | Oracle Cloud，4 核 23G / 194G，Ubuntu 22.04.5 aarch64，Java 21.0.12.1 |
| SSH | `ssh -i ~/.ssh/id_ed25519_github buaa@168.110.3.136` |
| 管理账号 | cnadmin，口令在服务器 `~/cn-deploy-scripts/ADMIN-PASSWORD.txt`（权限 600） |
| 技术栈 | Java 21 / Spring Boot 3.5 / MyBatis-Plus / PostgreSQL 16 / Flyway；Vue 3 + TS + Vite + Pinia |
| 规模 | 后端 908 个 Java 文件 / 5.26 万行；前端 38 个页面；35 个控制器 / 171 条端点；63 张表 / 39 个迁移 |
| 测试 | 单元 **927** 例全绿；集成 40 例（默认跳过，需真实 PostgreSQL） |
| 一句话现状 | 三个后端服务 + 前端全部已上线，公开读、写与治理、采集、门户、运维五条链路都能真跑；数据量与数据完整性还有明确缺口，见第八、九章 |

**接手第一件事**：按第十章「接手第一周」走一遍，不要先改代码。

---

## 二、这个项目是什么

一个以**学者**为中心的学术成果聚合与共享平台，覆盖论文、专利、科研项目、获奖四类成果。

它做的事情，用一条数据流概括：

```
外部来源（OpenAlex / Crossref / NSF / Nobel / Wikidata）
   ↓ 采集：按来源配置抓取 → 原始报文入库
   ↓ 交接：清洗、字段白名单、标识登记、去重检测
   ↓ 核心：成果主数据 + 贡献关系 + 机构 + 学者 + 引用边
   ↓ 投影：事件出箱（outbox）→ 查询侧读模型
   ↓ 查询：检索、画像、网络、统计、导出
```

三条贯穿全项目的设计红线（写代码前必须先接受，违反即回滚）：

1. **来源原值不可被人工覆盖**。人工修订走独立字段，带审核痕迹。
2. **自动更新不覆盖人工审核过的值**。自动流程遇到人工值必须让路。
3. **不确定就是不确定**。待核验／争议／已排除的合作关系不建边、不显示为「确定合作」；模拟数据不冒充真实数据；隐藏入口不构成授权。

需求侧的一切（FR01～FR16、NF01～NF08、IN01、AC-IMPL-*、UC-*）都在**外部需求仓库**。本仓库只做实现，不复制、不改写需求语义；发现冲突登记为「待联合评审」，不在本仓库自行改。

---

## 三、从零到现在：六个阶段

项目从 2026-10-16 的一个空目录开始，九天做到上线。下面是每个阶段**解决了什么问题、留下什么可验证的东西**。

### 阶段 0：需求与架构基线（10-16 之前，外部仓库）

需求调研、需求建模、SRS V1.1（Review Candidate）、核心 SPEC V1.0、UML 图源（PlantUML 唯一维护源）在需求仓库完成。本仓库在这一阶段产出：架构设计说明书（542 行）、6 条 ADR、编码规范与协作规范。

> 状态口径：ADR 是 **Proposed**，SRS 是 **Review Candidate**，都还没有双组签认。不要在任何对外材料里写成 Frozen。

### 阶段 1：骨架跑通（10-16）

共享内核（shared-kernel：错误码、响应外壳、标识生成、CSV）+ 三个服务 + 前端壳落地，core-service 首次跑通完整读写链路。同一天修掉公开读、交接归属、前端三处缺陷，每处都补了回归守卫。

产出：仓库可 `git clone` 后一条命令起整栈。

### 阶段 2：治理闭环（10-17）

治理工作台（打断候选积压的入口）+ **裁决后重评估发布**（这一条是关键：裁决完不重新评估，成果就永远停在旧状态，属于「看起来做完了其实没闭环」）。同一天把覆盖率门禁扩到第三个模块，并做出「合并影响预览」。

### 阶段 3：按 SRS 补齐到可上线（10-18）

12 项批次收尾，随后是第五、六、七、九批：站内通知、公众纠错、两个 CSV 导出、学者指标、学术诚信声明（关注／更正／撤稿）、引用导出四种格式（BibTeX / RIS / GB-T 7714 / APA，含批量）、discovery 纳入覆盖率门禁、浏览器断言脚本入库。

同期做了一次**性能超限归因**（检索慢的真实原因不是索引缺失，是查询写法让索引失效，详见第七章）。

### 阶段 4：待办清单清空（10-19）

第十批（待办 #33–#35）、第十一批（引用网络全链路）、第十二批（账号安全／越权矩阵／备份恢复）、第十三批（部署前八项：种子包、BibTeX/RIS、相关推荐、通知补全+公告、账号导出与注销、OpenAPI、移动端、收藏）。

同一天首次推送到 GitHub dev 分支（823 个文件）。

### 阶段 5：上线（10-20 ～ 10-23）

- **10-20**：Oracle 首台服务器部署完成，CI/CD 跑通，数据库定时备份上线，日志上限与 429 退避修掉，交接卡死问题修复。
- **10-21**：SSH 断连事故（三层原因，其中一层是自己把 22 端口关了）。详见 `PITFALLS-OPS.md`。
- **10-22**：旧机被 Oracle 回收，**换机重建**。数据没丢（从 dump 恢复：core 45 表 / query 11 表 / ingestion 7 表）。同一天做检索性能优化、摘要回填上线、慢速采集方案落地。
- **10-23**：CI/CD 重建并两轮真跑全绿；建立**三层验收**（路由／控件／接口）；修掉三个真实缺陷。

### 阶段 6：部署后数据补齐（10-22 ～ 10-24）

这一段是「部署完才做得了」的工作，也是当前数据缺口的来源：

- 摘要回填：`AbstractHydrationWorker` 每 30 分钟补 400 篇，公开论文摘要覆盖率从 62.5% 提到 **70.6%**（19055/26973）。
- 引用网络重新解析：修掉「调用成功但边数不变」的原地打转，一次点亮 16850 条边。
- **引文反向回填**（本轮新增）：把参考文献指向的站外文献反向收进站内，单批上限 20，靠加轮数补量。
- **治理按档批量驳回**（本轮新增）：按相似度档位（HIGH ≥ 0.90 / MID ≥ 0.70 / LOW < 0.70）批量处理积压。
- **机构聚合页**（本轮新增）：12,147 个有成果的机构按成果数／学者数聚合（机构字典 15,692 条），后端已上线。
- **专利接入**（本轮新增）：Wikidata 采到 1093 条，公开 1091 条 —— 从 0 到有真实专利。

---

## 四、系统架构

### 4.1 服务划分

| 服务 | 端口 | 数据库 | 职责 |
| --- | --- | --- | --- |
| core-service | 8081 | core_db（45 表） | 主数据与写入：成果、学者、机构、贡献关系、引用、治理、身份、通知、门户、完整性 |
| ingestion-service | 8082 | ingestion_db（7 表） | 采集与交接：来源配置、采集任务、原始报文、清洗映射、交接批次、两个后台 Worker |
| query-service | 8083 | query_db（11 表） | 读模型：检索、画像、网络、统计、一致性视图（只读，由出箱事件驱动） |
| frontend | — | — | Vue 3 SPA，nginx 静态托管 |
| PostgreSQL 16 | 5432（仅本机） | 三个库 | 一服务一库，互不跨库直连 |
| Redis × 2 | 6379 / 6380 | — | 会话 / 缓存，两套独立 |

**服务间只走 HTTP**（内部端点在 `/internal/v1/**`，用服务令牌鉴权），不共享数据库、不共享事务。这是刻意的：跨服务一致性靠出箱事件 + 幂等消费，不靠分布式事务。

### 4.2 网关与路径

nginx 是唯一对外入口，前缀剥离后转发（`location` 与 `proxy_pass` 尾部都带斜杠）：

```
/            → 前端静态（/var/www/cn）
/core/       → 127.0.0.1:8081
/ingest/     → 127.0.0.1:8082
/query/      → 127.0.0.1:8083
/core/v3/api-docs、/core/swagger-ui → 单独 location，允许匿名
```

**踩过的坑**：前端代码里写请求路径时，服务内路径与网关前缀是两套。写成 `/api/v1/...` 会被 nginx 兜底返回前端 index.html（HTTP 200 + 一段 HTML），看起来「接口通了」，实际什么都没调到。正确路径是 `/query/api/v1/public/search/achievements`。判断依据：响应体是不是 JSON。

### 4.3 一致性机制

写侧在同一个事务里写业务表 + 写 `evt_outbox`；投递器把事件推到查询侧 `qry_inbox_event`；投影器按事件重放读模型；`/query/api/v1/public/consistency` 暴露两侧水位差。

**已知语义限制**（不是 bug，是设计取舍）：水位是单调的，对「迟到事件」无解 —— 一条比当前水位更早的事件到达时不会推进水位，也就不会被发现。运维页能看到水位差，但看不见「迟到且被丢弃」的个案。

### 4.4 后台任务全清单

| 任务 | 所在服务 | 周期 | 作用 | 停摆的征兆 |
| --- | --- | --- | --- | --- |
| CollectWorker | ingestion | 2 秒轮询 | 领任务、抓取、交接 | 任务 RUNNING 但记录不增 |
| HandoffBacklogSweeper | ingestion | 15 分钟 | 补交接已暂停任务的遗留批次 | 批次停 PENDING、记录停 STAGED、**无人报错** |
| CitationHydrationWorker | ingestion | 30 分钟 | 引用补水（300 篇/轮） | 观测表 `created_at` 无新增 |
| AbstractHydrationWorker | ingestion | 30 分钟 | 摘要回填（400 篇/轮） | 摘要覆盖率不动 |
| AccountClosureSweeper | core | 每小时 | 冷静期届满自动注销 | 申请停在冷静期 |
| RetentionSweeper | core | 每天 | 清会话撤销表 | 表持续膨胀 |

调度线程池显式配为 4。手工触发注销执行：`POST /internal/v1/maintenance/closure-sweep`（带服务令牌）。

**外部还有一条**：服务器 crontab 每 2 小时跑 `collect-drip.py`，走「resume → 跑一轮 → pause」，实现慢速采集（平台本身没有「每 N 小时抓一条」的配置项）。

---

## 五、已实现功能清单

按业务域列。每条给出**能做什么 + 在哪个页面 + 靠什么接口**。前端 26 个页面（27 条路由含 404）。

### 5.1 公开读（无需登录）

| 功能 | 页面 | 说明 |
| --- | --- | --- |
| 总览 | `/` | 平台统计、入口聚合 |
| 成果检索 | `/search` | 题名／作者／摘要／DOI／载体；检索范围切换、搜索建议、结果导出 |
| 成果详情 | `/achievements/:id` | 三值数据性质标注、来源溯源、引用导出、相关推荐、引用网络面板、收藏 |
| 学者档案 | `/scholars/:id` | 成果数、合作者、引用量（累计／篇均／单篇最高／按年份） |
| 领域洞察 | `/topics/:id` | 主题下的成果与趋势 |
| 关键词热点 | `/hotspots` | 关键词热度 |
| **机构** | `/organizations` | 机构榜（成果数／学者数）+ 单机构成果清单，共 12,147 条。**后端已上线，前端页面待一次同步** |
| 合作网络 | `/network` | 机构／学者合作图 |
| 相关推荐 | 成果详情内 | 四条信号（同作者／共被引／共享参考文献／同主题），逐条给理由，分数 < 6 不展示，空结果明说「引用采集是抽样的」 |
| 引用导出 | 成果详情 / 检索结果 | BibTeX、RIS、GB-T 7714、APA；单篇与批量 |
| CSV 导出 | 管理端多处 | 成果、候选、审计等 |
| 公开纠错 | 成果详情内 | 公众提交纠错，不进主数据，进审核队列 |

**机构聚合的口径**（页面 `countingRules` 字段会原样展示，不要自己另算一套）：按来源给出的机构原值分组，**同一机构的不同写法各自成条，不做启发式合并**（这是红线的直接后果：同名不直接合并）；成果按去重实体计数，同成果同机构只计一次、跨机构各计一次；学者是该机构字符串下出现过的人数，未按机构实体归并。

**不做**：h-index、影响因子、引用量逐年增长趋势（平台只有当前累计值，没有增长历史）。

### 5.2 引用网络

三块能力：本篇引用了谁（出边）、站内谁引用了本篇（入边）、以及**覆盖率自陈**。

前端 `CitationNetworkPanel` 会同时显示三个数：来源给出多少条、其中收录在本平台多少条、全平台覆盖率多少。这是刻意的诚实设计 —— 空结果不等于「没有引用」，而是「采集没覆盖到」。

当前实测：边 826,812 条，其中解析到站内 46,211 条（5.59%），指向站外 780,601 条。原因见第八章。

运维入口：`/governance/citations`（引用网络采集页），含补水进度、标识解析、**引文反向回填面板**（选标识种类、每批上限、可连跑 N 轮，实时显示收进多少节点、点亮多少边、还剩多少待解析）。

### 5.3 采集与交接

- 来源配置（`/ingestion`）：适配器种类 Crossref / OpenAlex / NSF / Nobel / Wikidata 专利 / 文件导入；字段白名单；分页与限速。
- 采集任务：RUNNING / PAUSED / STOPPED / SUCCEEDED / FAILED；每轮抓取量、失败退避、租约。
- 原始报文：入库保留原文，字段映射留溯源（`core_field_provenance`）。
- 交接：批次 + 逐条记录，幂等键防重，`STAGED → HANDED_OFF → APPLIED`。
- 慢速采集：外部 cron 驱动 `collect-drip.py`（每 2 小时一页）。

**三个必须知道的事实**：
1. `runOnce` 对 PAUSED 任务直接跳过 —— 不 resume 就只返回「本轮跳过」。
2. 建完任务到 pause 之间几百毫秒，后台执行器会抢跑（实测几分钟抓了 21 条）。
3. 交接重试只写在 `runOnce` 里，而 `runOnce` 跳过 PAUSED —— 这就是「批次停在 PENDING、记录停在 STAGED、无人报错」的根因，`HandoffBacklogSweeper` 就是补这个洞的。

### 5.4 治理与去重

- 候选生成：跨源同标题／同 DOI／同专利号生成去重候选，带相似度。
- 三档分档：HIGH ≥ 0.90、MID ≥ 0.70、LOW < 0.70。
- 裁决：合并（留重定向）、驳回（记理由）、重评估（`REBASED`）。
- **裁决后重评估发布**：裁决完必须重新评估成果是否可发布，否则成果永远停在旧状态。
- 合并影响预览：合并前看会影响多少成果／关系／边。
- 关系争议裁决（`/review/relations`）：待核验／争议／已排除的关系不建边。
- **按档批量驳回**（本轮新增，`/governance`）：选档位 + 上限 + 理由，一次处理一批。
- 数据质量扫描：异常登记（`gov_anomaly`）。

**当前积压**：OPEN 28,407（HIGH 3,583 / MID 6,140 / LOW 18,668），已合并 19、已驳回 9、重评估 1。

### 5.5 门户与账号

- 注册 / 登录 / 会话（Redis 会话，`auth_version` 做全局失效）。
- 本人改口令：其他设备会话立即失效，当前会话续期。
- 管理员重置为一次性口令：**这是唯一的找回路径**（平台不接邮件与短信）。
- 我的成果、提交成果（表单 + BibTeX/RIS 批量，最多 200 条/次，先预览后写入，逐条独立入库）。
- 学者主页自助维护、认领与材料核验。
- 待审成果队列（`/governance/submissions`）。
- 收藏（`/favorites`，只公开收藏数量，不公开收藏者）。
- 通知（`/notifications`，按类型筛选、全部已读、从列表移除＝归档不是删除）。
- 待办（`/todos`）。
- 系统公告（管理员发布，扇形写进每个启用账号，**发出不可撤回**）。
- 本人数据导出（JSON，不含口令哈希、会话版本、来源报文原文）。
- 注销申请：7 天冷静期由数据库 `now()` 判定，管理员不能批自己；**执行注销只停用账号、撤销角色、踢会话，不删除任何业务数据**（审计 actor 悬空不可接受）。

**明确不做**：邮件找回、短信验证码、第三方登录 —— 都依赖本阶段不接入的外部通道。

### 5.6 管理端与运维

- 成果管理（确认发布／撤回／字段修订，字段白名单）。
- 审核工作台（`/review`）、审计查询（`/audit`）、公告管理。
- 运维页（`/ops`）：一致性与投递水位、投递积压、手工补偿入口。
- 采集管理台（`/ingestion`）。
- 引用网络采集页（`/governance/citations`）。
- 学术诚信声明：关注／更正／撤稿（**撤稿不下架**，加标注）。
- 账号管理、注销审批。

### 5.7 完整性（数据性质）

每条成果带三值数据性质标注：REAL（真实来源）/ SIMULATED（模拟）/ UNKNOWN（未知）。

这是平台最容易被误解的一点：模拟数据**必须能被一眼看出是模拟数据**，任何页面不得把模拟数据渲染成真实数据。三个服务都是 `non_null` 序列化，所以表达「未知」必须另给永不缺失的字段，前端判空必须同时挡 undefined。

---

## 六、数据现状（2026-10-24 实测）

### 6.1 核心库 core_db

| 表 | 行数 | 说明 |
| --- | --- | --- |
| core_citation_reference | 826,812 | 引用边 |
| core_identifier_registry | 148,129 | 标识登记（DOI／专利号等） |
| core_contribution_org | 143,190 | 贡献—机构 |
| core_contribution | 132,411 | 贡献关系 |
| core_field_provenance | 122,426 | 字段溯源 |
| core_scholar | 86,096 | 学者（含大量未认领） |
| core_achievement | 28,616 | 成果主表 |
| gov_dedup_candidate | 28,436 | 去重候选 |
| evt_outbox | 28,322 | 出箱事件 |
| core_paper | 26,973 | 论文明细 |
| core_citation_observation | 17,546 | 引用观测（补水记录） |
| ref_organization | 15,692 | 机构字典 |
| core_patent | 1,093 | 专利明细 |
| core_award | 487 | 获奖明细 |

### 6.2 成果分布

| 类型 | 总数 | 公开（PUBLIC + EFFECTIVE + 未合并） |
| --- | --- | --- |
| 论文 PAPER | 26,973 | 17,568 |
| 专利 PATENT | 1,093 | 1,091 |
| 获奖 AWARD | 487 | 487 |
| 项目 PROJECT | 63 | 55 |
| **合计** | **28,616** | **19,201** |

### 6.3 数据完整度

| 指标 | 数值 | 判断 |
| --- | --- | --- |
| 论文摘要覆盖 | 19,055 / 26,973 = **70.6%** | 回填在跑，每 30 分钟 400 篇；实测可得率约 39.5%，剩余部分来源本身没有摘要 |
| 引用边解析到站内 | 46,211 / 826,812 = **5.59%** | 低，但不是「没跑」，是站内没有那些被引文献 |
| 治理候选积压 | OPEN 28,407 | 大，按档批量驳回是主要出口 |
| 账号 | 148（其中 smoke_／lock_ 测试账号 43） | 需清理 |

### 6.4 query_db 读模型

`qry_coauthor_edge` 790,567 / `qry_contribution` 335,962 / `qry_contribution_org` 289,301 / `qry_achievement` 82,373 / `qry_achievement_topic` 79,544 / `qry_inbox_event` 28,458 / `qry_id_redirect` 16 / `qry_generation` 9。

### 6.5 ingestion_db

采集任务：STOPPED 23（测试残留，已处置）／PAUSED 8（prod-drip + 7 条 10-17 的真实任务）／SUCCEEDED 2。来源配置 11 条：OPENALEX 4、CROSSREF 3、NSF 1、NOBEL 1、WIKIDATA_PATENT 1、FILE 1。

---

## 七、工程质量与验证

### 7.1 测试

| 模块 | 单元测试 |
| --- | --- |
| shared-kernel | 14 |
| core-service | 586 |
| ingestion-service | 167 |
| query-service | 160 |
| **合计** | **927（全绿）** |

集成测试 11 个文件 / 40 例，默认跳过，需要真实 PostgreSQL：

```bash
./mvnw -B -ntp verify -Dcn.it.skip=false -Dspotless.check.skip=true
```

**计数口径（很容易算错）**：以构建汇总行 `Tests run:` 为准，源码 `@Test` 计数只作旁证。`TEST-*.xml` 加总会偏低也不准（JUnit5 嵌套类的类级汇总会显示 0）。

### 7.2 覆盖率门禁

4 条门禁覆盖 9 个包：governance、catalog.domain、analytics、discovery。

**新增门禁必须同时登记进 `ops/verify-coverage-gates.sh` 的 GATES 数组**，否则门禁存在但没人知道它是否真在拦。

已知局限：`query` 与 `discovery` 的 100% 是**行覆盖**，不是 SQL 覆盖；`tests/api/` 不在 `verify` 流水线内，SQL 没有集成断言。

### 7.3 CI/CD

推送 dev 分支触发：build（`verify` + 全部单元测试 + 覆盖率门禁 + 前端 build）→ deploy（写目标机）。

- **后端自动部署可用**：无 sudo 时靠 `kill -TERM MainPID` + systemd `Restart=always` + `SuccessExitStatus=143` 拉起；jar 用 `mv` 原子顶替。
- **前端自动部署不可用**：`/var/www/cn` 属主 root，而 buaa 的 sudo 口令是锁死状态。
- 前端「要不要同步」的判据是 `git rev-parse HEAD:frontend` 的 tree hash 与服务器 `~/cn/state/frontend-tree` 比对。**不要退回比构建产物文件名** —— 跨平台构建入口 chunk 必然换名，会导致每轮误报。
- GitHub 会把 secret 值在整个输出里替换成 `***`，所以注解里的命令必须用 `cd ~` + 相对路径。

### 7.4 三层验收（缺一层就有一类「坏」看不见）

| 层 | 脚本 | 验什么 | 最近结果 |
| --- | --- | --- | --- |
| ① 路由 | `tests/ui/audit-site.mjs` | 解析 `router/index.ts` 得路由表，验死链、孤儿路由、链接可点 | 26/26 路由、0 死链、0 孤儿、100 次点击全过 |
| ② 控件 | `tests/ui/audit-controls.mjs` | 白名单只读控件探针，验「点下去有反应」 | 20 项覆盖 7 页全过 |
| ③ 接口 | `tests/api/run-all.sh` | 按目录跑 `smoke-*` / `verify-*`，31 个脚本 | 会写本地库，**只能对本地整栈跑** |

三层都不能对生产站点的写路径动手。所有前端控件查询必须限定 `#main-content` —— 页头另有同名「检索」按钮与「数据口径」下拉，会冒充页面内的按钮和第一个 select。

### 7.5 可观测性

- 每个响应带 traceId。
- `/audit` 页可查审计事件（7,837 条）。
- `/ops` 页看一致性水位与投递积压。
- 日志：systemd journald（已设上限，无界会把磁盘吃满）；采集日志在 `~/cn/logs/`。

---

## 八、线上运维

### 8.1 部署目录

| 路径 | 内容 | 属主 |
| --- | --- | --- |
| `/home/buaa/cn/app/` | 三个 jar | buaa（可写） |
| `/home/buaa/cn/conf/cn-env.sh` | 全部密钥与配置 | buaa |
| `/home/buaa/cn/logs/` | 采集、备份、部署日志 | buaa |
| `/home/buaa/cn/backup/` | 数据库 dump | buaa |
| `~/cn-deploy-scripts/` | 运维脚本 | buaa |
| `/var/www/cn/` | 前端静态产物 | **root（不可写）** |

systemd 单元：`cn-core`、`cn-query`、`cn-ingestion`。

### 8.2 常用命令

```bash
# 状态
systemctl is-active cn-core cn-query cn-ingestion
journalctl -u cn-core -n 100 --no-pager

# 重启单个服务（无 sudo，靠 unit 的 Restart=always）
kill -TERM $(systemctl show -p MainPID --value cn-core)

# 换 jar（原子）
mv /tmp/new-core.jar /home/buaa/cn/app/core-service.jar
kill -TERM $(systemctl show -p MainPID --value cn-core)

# 接口自检
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8081/actuator/health
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8083/actuator/health

# 查库（属主账号，口令在 cn-env.sh）
export PGPASSWORD=$(grep CN_CORE_DB_OWNER_PASSWORD ~/cn/conf/cn-env.sh | cut -d= -f2)
psql -h 127.0.0.1 -U core_owner -d core_db -At -c "select count(*) from core_achievement;"
```

### 8.3 备份

buaa 的 crontab 每天 18:20 UTC（北京 02:20）跑 `~/cn-deploy/pg-backup.sh`（TCP + 属主口令，不需要 root）。

**不要 enable `cn-db-backup.timer`** —— 那是个坏壳（timer disabled，service 指向不存在的路径）。

### 8.4 定时任务（crontab -l）

```
0 */2 * * *  collect-drip.py                       # 慢速采集，每 2 小时一页
20 18 * * *  pg-backup.sh                          # 数据库备份
```

### 8.5 root 问题（必须知道）

`buaa` 在 sudoers 里但**口令锁定**（`passwd -S` 显示 L），sudo 用不了；也没有其他 root 通路（oracle-cloud-agent inactive、无 root cron 指向家目录、其他账号不认我们的密钥）。

影响只有一个：**前端无法更新**。要 root 只能从 Oracle Cloud 控制台一侧进（VNC／串口）。

### 8.6 怎么确认线上跑的是哪份代码（产物一致性核对）

不要靠「部署脚本跑成功了」来推断线上是什么代码。用下面四条独立证据，每一条都能单独证伪。

**① class 文件联合哈希（最硬的判据）**

整包 `sha256sum` **不能跨平台比**。本地是 Windows 工作区，资源文件（如 `application.yml`）带 CRLF 进 jar；CI 在 Linux 检出的是 LF。整包哈希必然不同，而代码可能完全一样 —— 2026-10-24 就踩过：同一份代码，本地构建与 CI 构建整包哈希不同，差点误判成「线上不是这份代码」或「构建不可重现」。

要比就比编译产物本身：

```bash
# 本地与线上各跑一次：对 BOOT-INF/classes 下所有 .class 的内容算联合哈希
python3 -c "
import zipfile,hashlib,glob,os
for p in sorted(glob.glob('*.jar')):
    z=zipfile.ZipFile(p)
    cl=[n for n in z.namelist() if n.startswith('BOOT-INF/classes/') and n.endswith('.class')]
    h=hashlib.sha256()
    for n in sorted(cl):
        h.update(n.encode()); h.update(hashlib.sha256(z.read(n)).digest())
    print(os.path.basename(p).ljust(26), len(cl), h.hexdigest()[:20])
"
```

实测（2026-10-24，本地构建 vs 线上 CI 构建）：

| 服务 | class 数 | 联合哈希 | 结果 |
| --- | --- | --- | --- |
| core | 456 | `8d28b3664f18b7e09054` | 相同 |
| ingestion | 128 | `00b1c6fdd72a32403388` | 相同 |
| query | 112 | `1b6224621c98c047e9de` | 相同 |

class 哈希相等即「编译出的字节码相同」，可判定线上就是这份代码。资源文件那部分哈希会不同，逐一比对可确认差异只在 `application.yml` 的行尾 —— YAML 两种行尾都能解析，无功能影响。**只有本地和线上都在同一操作系统构建时，整包 sha256 才是合法判据。**

**② 进程启动时间晚于 jar 写入时间**

```bash
for s in cn-core cn-query cn-ingestion; do
  echo "$s $(ps -o lstart= -p $(systemctl show -p MainPID --value $s))"
done
ls -la --time-style=full-iso ~/cn/app/*.jar
```

jar 换了但进程没重启，跑的还是旧类。这个坑很安静：文件是新的，接口行为是旧的。

**③ 新增端点探针（401 = 路由存在，404 = 不存在）**

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8081/api/v1/admin/core/citations/backfill-candidates?limit=1
```

比 ② 更直接：只有含该功能的构建才会注册这条路由。

**④ Flyway 版本对齐**

```bash
psql -h 127.0.0.1 -U core_owner -d core_db -At \
  -c "select version, description from flyway_schema_history order by installed_rank desc limit 1;"
```

三个库当前分别是 core v23 / query v6 / ingestion v10，与仓库里 `db/migration/` 的脚本数一致。

**前端另算一套**：`/var/www/cn` 与 `frontend/dist` 逐文件比 sha256。注意区分两种比法：按「哈希+路径」配对会得出 0 个相同（因为 Vite 每次构建 chunk 文件名都变），按**纯内容哈希**比才有意义 —— 2026-10-24 实测 51 个文件里 18 个内容相同、33 个不同。nginx 配置同理：`md5sum /etc/nginx/sites-enabled/cn` 与 `ops/deploy/nginx-cn.conf` 比对，当前一致（`72fec38c…`，线上文件名是 `cn` 不是 `cn.conf`）。

---

## 九、已知限制与未完成（诚实清单）

这一章是交接的重点。已上线不等于已完成。

| # | 问题 | 现状 | 影响 | 判定依据 |
| --- | --- | --- | --- | --- |
| 1 | **前端未同步** | `/var/www/cn` 还是 10-22 的产物（50 个文件）；最新产物是 10-24（51 个文件）。逐内容比哈希只有 18 个相同（favicon 与部分公共 chunk），其余全变，且线上缺 `OrganizationView` 这个页面 chunk。机构页 `/organizations` 在网页上看不到（后端接口正常） | 用户看不到机构页与新 UI | 服务器 assets 目录里没有 `OrganizationView-*.js`；`~/cn-incoming-new/cn-web.tar.gz` 与本地最新 `dist` **逐字节一致**，只差安装 |
| 2 | 专利年份严重偏历史 | 1093 条里 1900–1949 占 747（68%），2020 年后仅 112 | 「专利」这一类的当代覆盖几乎为零 | Wikidata `P31 Q253623` 这个类目就这么多，已采尽 |
| 3 | 引用网络覆盖率 5.59% | 826,812 条边里只有 46,211 条解析到站内 | 引用网络看起来稀疏 | `unresolvedRemaining` 已归零，说明剩下的 78 万条是节点真的不在库内 |
| 4 | 治理积压 28,407 | 按档驳回能批量消化，但量太大 | 治理工作台一直有货 | `gov_dedup_candidate` 按 status 分组 |
| 5 | 无常态化生产采集 | 来源配置 11 条，但只有 prod-drip 在跑，且每 2 小时 1 页 | 数据不会自己长 | `ing_collect_job` 状态分布 |
| 6 | 后端监听 0.0.0.0 | 8081/8082/8083 监听所有网卡 | 纵深防御不足（当前靠云安全列表挡住，外网实测 000） | `ss -ltnp` |
| 7 | 43 个测试账号未清 | `iam_account` 148 个账号里 smoke_/lock_ 前缀 43 个 | 排查故障时会误判；统计口径不准 | `select count(*) where username like 'smoke_%'` |
| 8 | 管理口令文件仍在服务器 | `~/cn-deploy-scripts/ADMIN-PASSWORD.txt`（600） | 拿到 SSH 就拿到口令 | `ls -la` |
| 9 | 运维手册部分章节过时 | `docs/ops/部署与运维手册.md` 里 3～8 章是 Docker Compose 时代的写法，线上实际是 systemd + 直跑 jar | 照文档做会走错路 | 对照 `~/cn-deploy-scripts/deploy-update.sh` |
| 10 | SQL 无集成断言 | `tests/api/` 不在 `verify` 流水线内 | 改 SQL 不会被自动拦住 | 查 workflow |
| 11 | 集成测试未覆盖新索引与冷静期条件 | V19–V22 的部分唯一索引、冷静期条件没有集成断言 | 迁移回滚可能不报错 | 查 `*IT.java` |
| 12 | 37 个文件未提交 | 本轮（专利、回填、分档驳回、机构页）改动还在工作区 | 换机或误删会丢 | `git status --porcelain` |
| 13 | 前端自动部署不可用 | 见第 8.5 节 | 每次前端改动都要人工 root 干预一次 | CI 里 `CN_WEB_PENDING=1` |
| 14 | 迟到事件不可见 | 单调水位对迟到事件无解 | 一致性页看不见「被丢弃的个案」 | 设计取舍，不是 bug |

---

## 十、后续待办（按优先级）

### P0：不处理会继续恶化或看不见真问题

1. **同步前端一次**（需要 root，一条命令）。产物已按部署脚本要求的布局摆在 `~/cn-incoming-new/dist`（51 个文件，属主 buaa，含 `OrganizationView-DKaBjWt9.js`）：

   ```bash
   # 一次搞定：装好免密同步能力 + 立即上线本次前端 + 自检
   sudo bash /home/buaa/cn-deploy-scripts/bootstrap-web-deploy.sh
   ```

   跑完会打印首页、入口 chunk、机构页 chunk 三项自检结果，并在 `$WEB_ROOT.bak-<时间戳>` 留一份可回滚的整目录备份。

   之后 buaa 就能自己部署前端（CI 也能）：
   ```bash
   sudo /usr/local/sbin/cn-web-sync --from /home/buaa/cn-incoming-new/dist
   ```

   只想单次上线、不想开长期权限的，用备用脚本（同样一条命令，自带校验与回滚）：
   ```bash
   sudo bash /home/buaa/cn-deploy-scripts/sync-web-once.sh
   ```

   验收：`/organizations` 能看到机构榜；`ls /var/www/cn/assets | grep OrganizationView` 有输出。

   > **为什么一定要 root**：`/var/www`、`/var/www/cn`、`/etc/nginx` 全是 root:root 755，buaa 既不在任何特权组、也没有 ACL；`sudo -l` 里 buaa 只有 apt update、chown/chmod shared_project、systemctl restart nginx、journalctl/tail nginx 日志这几条免密规则，**没有一条能写文件**；helper、ubuntu、opc 三个账号都在 sudo 组，但都不认我们的公钥，登不进去。所以这一条命令必须由有 root 的人执行一次。

2. **提交本轮改动**（37 个文件）。

3. **清理 43 个测试账号 + 重置并删除管理口令文件**。改密后删 `ADMIN-PASSWORD.txt`。

4. **把后端端口收回 127.0.0.1**（`server.address=127.0.0.1`）。当前靠云安全列表挡着，但主机层不该开。改完必须验证 nginx 四条前缀仍然 200，以及 CI 部署脚本不依赖直连端口。

### P1：补齐明确的功能缺口

5. **专利换源**。要当代专利必须用注册制数据源：

   | 来源 | 需要什么 | 覆盖 |
   | --- | --- | --- |
   | EPO OPS | 免费注册，申请 key | 全球专利，含法律状态 |
   | PatentsView | 免费申请 key | 美国专利，含 IPC 全文 |
   | WIPO PATENTSCOPE | 需申请 | PCT 国际申请 |

   适配器骨架参考 `WikidataPatentAdapter`。注意：`core_patent.ipc_codes` 现在写的是 **CPC**（Wikidata `P5778`），不能当 IPC 对外报。

6. **让采集常态化**。当前只能靠 cron 滴灌。要真正常态化，需要先修「PAUSED 不会自动恢复」的逻辑，再恢复那 7 条 `manual-*` 任务（恢复会一次抓几千条，OpenAlex 配额紧张时会把补水一起拖进 429）。

7. **扩大引文反向回填**。单批上限 20 是安全线不是性能参数：每收一条成果 core 都要与全库比对标题去重登记（单条 0.5–1.2 秒），limit=50 必定 22 秒超时并整批回滚。补量只能加轮数。

### P2：工程债与体验

8. 把 `tests/api/` 接进 CI（需要 CI 里有 PostgreSQL service）。
9. 补集成测试覆盖 V19–V22 的新索引与冷静期条件。
10. 重写 `docs/ops/部署与运维手册.md` 的 3～8 章为 systemd 版本。
11. 治理积压分档清理（HIGH 优先，3,583 条）。
12. 一致性页补「迟到事件」可见性（改成多水位向量，或对每个聚合单独记水位）。

---

## 十一、接手第一周

### 第 1 天：能自己跑起来

1. 克隆仓库，切 dev：`git clone` → `git checkout dev`。
2. 本机起整栈：`bash ops/local/stack.sh`（沙箱下必须用后台任务承载）。停栈用 `stack.sh stop`；如果被回收，用 `taskkill //F //IM java.exe` 收尾。
3. 打开 http://localhost:5173 ，随便搜一个词，点开一篇成果，看详情页有没有报错。
4. 跑单元测试：`./mvnw -o test`，确认 927 全绿。
5. 读三份文档：本文 → `docs/architecture/架构设计说明书.md` → `.workbuddy/memory/PITFALLS-OPS.md`。

### 第 2 天：认识线上

6. SSH 上机，`systemctl is-active cn-core cn-query cn-ingestion`。
7. 打三个健康检查，看 `/ops` 页的一致性水位。
8. 读一遍 `~/cn-deploy-scripts/deploy-update.sh`，搞清 CI 到底怎么部署的。
9. 手工触发一次注销扫描（服务令牌），确认内部端点通路正常。

### 第 3–5 天：碰真数据

10. 在 `/governance` 按 HIGH 档批量驳回 2 条，**驳回前逐条核对两侧 DOI 与出版渠道**（服务端阈值是对的，问题通常在自己写的预览 SQL）。
11. 在 `/governance/citations` 跑一轮回填，观察「收进节点数／点亮边数／剩余数」三者的关系。
12. 用 `ops/verify/` 下的对账 SQL 核对一次引用网络与摘要进度。
13. 从 P0 的第 1 项开始动手。

---

## 十二、速查

### 12.1 关键文件

| 路径 | 作用 |
| --- | --- |
| `README.md` | 项目说明与本机运行 |
| `docs/architecture/架构设计说明书.md` | 542 行，架构决策与模块职责 |
| `docs/architecture/adr/` | 6 条 ADR（均为 Proposed） |
| `docs/ops/部署与运维手册.md` | 运维手册（3～8 章待更新） |
| `.workbuddy/memory/PITFALLS-OPS.md` | 运行／构建／测试／部署的坑（28 KB，必读） |
| `.workbuddy/memory/PITFALLS-CODE.md` | 业务与代码约束（28 KB，写代码前必读） |
| `.workbuddy/memory/2026-10-*.md` | 逐日过程与实测数据（含所有「为什么这么改」） |
| `ops/local/stack.sh` | 本机整栈唯一入口 |
| `ops/verify-coverage-gates.sh` | 覆盖率门禁登记处 |
| `ops/verify/*.sql` | 各类对账 SQL |
| `ops/remote/collect-drip.py` | 慢速采集 |
| `tests/ui/audit-site.mjs`、`audit-controls.mjs` | 前端两层验收 |
| `tests/api/run-all.sh` | 接口层验收 |

### 12.2 容易记错的几个事实

- 检索真实路径是 `/query/api/v1/public/search/achievements?q=…&page=1&size=3`，`page` **从 1 开始**，传 0 返回 400。
- 治理阈值是 **0.90 / 0.70**，不是 0.95 / 0.80。
- 回填单批上限 **20**，不是性能参数。
- 发布专利的接口是 `/api/v1/admin/core/achievements/{id}/confirm`（**没有** catalog 段）。
- CSRF 令牌在每次写操作后轮换，批量脚本必须每次请求前重取。
- 短关键词（< 3 字符）必须走另一套 SQL 写法，pg_trgm 三个字符才取得到一个完整三元组；中文两个汉字正落在这个坑里。
- 换 jar 前先想 Flyway：`validate-on-migrate: true`，schema 升到 vN 后用旧 jar 启动会被拒绝。回滚必须同时 `DELETE FROM flyway_schema_history WHERE version='N'`。
- 判断采集类任务是否推进，看 `created_at` 分布，**不要看短时间内的总量快照**。

### 12.3 本文取数命令

```bash
# 成果分布与摘要覆盖
psql -h 127.0.0.1 -U core_owner -d core_db -c "
SELECT type, count(*) FROM core_achievement WHERE visibility='PUBLIC'
  AND effective_status='EFFECTIVE' AND merged_into_id IS NULL GROUP BY type;"
psql -h 127.0.0.1 -U core_owner -d core_db -c "
SELECT count(*) FILTER (WHERE abstract_text IS NOT NULL AND length(abstract_text)>0), count(*) FROM core_paper;"
# 引用边解析率
psql -h 127.0.0.1 -U core_owner -d core_db -c "
SELECT resolution, count(*) FROM core_citation_reference GROUP BY resolution;"
# 治理积压
psql -h 127.0.0.1 -U core_owner -d core_db -c "
SELECT status, count(*) FROM gov_dedup_candidate GROUP BY status;"
# 测试计数
./mvnw -o test | grep "Tests run:" | grep -v " -- in "
```

---

**最后一句话给接手人**：这个项目最值钱的部分不是功能列表，而是 `.workbuddy/memory/` 里那三十万字的过程记录。里面每一条「坑」都是真实踩过、真金白银换回来的。改任何东西之前，先搜一下那里有没有人踩过。
