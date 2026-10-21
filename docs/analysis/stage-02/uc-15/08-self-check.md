# UC-15 自检与待评审问题

**版本：** V1.0（2026-10-21）
**检查性质：** 静态文档/模型检查；不是产品运行测试，不是教师评分，不是人工评审签字。
**范围：** 只检查 UC-15 分析包（`README`、01–08、两张图源与导出），以及本包的渲染与文档链接结果。

## 1. 范围与基线检查

| 检查 | 结果 | 证据 |
|---|---|---|
| 分支 | `analysis/s01-s02-69ac` | `git branch --show-current` |
| 正式 SRS/SPEC/S01 冻结材料 | 未修改 | 本轮只写 `docs/analysis/stage-02/uc-15/` 下本包文件 |
| 69 条 AC | 未新增、未删除、未改号 | `python3 scripts/docs/verify_69_ac.py` → PASS（§4.2） |
| 全局 UML（`DGM-CLASS-01-analysis.puml`） | 未修改 | 只读比对，差异与 R5 建议见 07 §2–§4 |
| 用例图/活动图/状态图（`DGM-USECASE-02`、`DGM-ACTIVITY-03`、`DGM-ACTIVITY-08`、`DGM-STATE-03`） | 未修改 | 只读比对；冲突与缺口登记 07 §5（C-01/C-04/C-06） |
| 模块细化 `analytics-graph.md` | 未修改 | 只引用 IN01-S01～S09、BR-513～530、Q-4/Q-6；差异登记 C-02/C-03/C-05/C-06 |
| 合作图与路径规范 `FR12-IN01` | 未修改 | 只引用 §1–§5（含 T37～T40 样例） |
| 上游 UC-14 目录 | 未修改 | 只读引用 `uc-14/` 的 01/03/05/07；本 UC 事件流与消息自行推导（04 §6） |
| 其他 UC 目录 | 未修改 | 未写入 `uc-01`～`uc-14`、`uc-16`～`uc-22` |
| 共享追踪/登记文件 | 未修改 | `../03-AC-UC-分析模型追踪.md`、`../automation/analysis-class-registry.md`、`../automation/unresolved-decisions.md`、`../automation/progress.json` 保持原状；登记建议见 07 §6 |
| 其他成员未提交修改 | 未覆盖 | 工作区原有 `docs/uml/analytics/DGM-ACTIVITY-03-collab-path.{puml,svg}`、`docs/uml/collection/*`、`scripts/docs/*`、`docs/reports/srs/*`、`docs/srs/integration/*` 等改动保持原状 |
| 渲染范围 | 只渲染本包 2 张图 | §4.1 命令显式指向 `uc-15/diagrams/*.puml`，未做全仓库批处理，不覆盖他人图资产 |
| 需求基线引用 | 只用 SRS V2.2（69 AC）与 `69-ac-baseline.json` | 01 首部基线声明；02 §1 证据等级说明区分基线/细化/待确认 |
| 越界内容 | 未写入数据库、实现接口、框架、缓存、部署、BFS 代码或实现类 | 03 §4、05 §4、06 §11 |

> `IN01-Sxx`/`BR-5xx` 为模块 SRS `analytics-graph.md` 的细化条目，`FR12-IN01` §3 自述为“整改 RC”，均不是 69 AC 正文；本包在引用处逐条标注“（细化）”，其效力低于 SRS V2.2/69 AC。

## 2. 教材步骤对应

| 教材步骤 | 本包证据 | 状态 |
|---|---|---|
| §5.4.1 p154–155 完善用例文档 | [01-UC-15用例规约](01-UC-15-use-case-spec.md) | 已完成（E1–E20、A1–A20、BR-UC15-01～20、待确认 B-01～B-12/C-01～C-06） |
| §5.4.2 p155–161 识别分析类 | [03-BCE分析类识别](03-bce-identification.md)、[02-事件流与需求来源](02-event-flow-trace.md) | 已完成；7 个参与类、18 组拒绝候选 |
| §5.4.3 p161–181 分析交互 | [04-顺序图与逐消息职责](04-sequence-messages.md)、`diagrams/DGM-SEQ-23-shortest-path.puml` | 已完成；M1–M53，11 个分组 |
| §5.4.4 p181–183 构造 VOPC | [05-VOPC与关系依据](05-vopc.md)、`diagrams/DGM-VOPC-19-shortest-path.puml` | 已完成；7 个参与类，独立于全局 BCE |
| §5.5.1 p188–190 职责 | [06-分析类定义](06-class-definition.md) §2–§8 的 Do/Know/候选分析操作 | 已完成 |
| §5.5.2 p190–191 属性 | 同 06 各节“业务属性”行、05 §2.4 | 已完成本 UC 可证实部分；fieldClassification/occurredInstitutionRef/k 口径标待确认 |
| §5.5.3 p192–200 关系/多重性 | 同 05 §2/§2.1–§2.3、06 §9 | 六条业务关联均给多重性（含 `0..*:2`、`0..*:1..*`、`0..*:1..*`、`0..*:0..*`、`0..*:1`、`0..*:0..1`）；控制/边界到实体不定义持久多重性；十三类事实属性化 |
| §5.5.4 p200–201 分析机制 | 同 06 §10 | 已定义 13 项机制，并在 06 §11 显式列出 10 项“不定义的职责” |
| §5.5.5 p201–204 统一分析类 | [07-与全局模型差异](07-global-model-diff.md) | 只提交 R5 输入，不重构全局图、不改共享登记表 |

## 3. 模型完整性检查

| 检查 | 结果 | 说明 |
|---|---|---|
| UC-15 参与者 | 主要：访客；系统参与者：系统（由 `CollaborationPathCoordinator` 实现，不画成边界类）；次要（外部前置、图中不画）：身份与归属治理方、成果治理与来源方、机构/领域分类维护方；上游：UC-14；下游导航：UC-02/UC-03 | 依 IN01-R1、FR15、FR16、§8；01 §1、04 §1 |
| 主流程编号 | E1–E20（20 步，连续无缺号） | 脚本核对 {E1…E20}；每步在 01/02 有来源 |
| 备选/异常 | A1–A20（20 条，连续无缺号） | 脚本核对 {A1…A20}；均有正式来源或标注细化/分析推导/待确认 |
| 业务规则 | BR-UC15-01～20（20 条） | 01 §8；每条挂 IN01/AC/BR-IMP/SPEC 依据 |
| BCE 候选类 | 7 个：`CollaborationNetworkUI`/`CollaborationPathCoordinator`/`Scholar`/`WorkEntity`/`Authorship`/`CollaborationEdge`/`PathResult` | 03 §2/§6 |
| 被拒绝候选 | 18 组：机构/领域/映射实体、图快照/图版本、结果对象与状态机、BFS 数据结构、逐跳对象、查询参数对象、账号与普通主页、来源记录与版本、去重/清洗候选、身份候选与合并对象、质量/影响力/排名/路径排行、强度/证据阈值、审计证据、会话、DTO 与展示投影、图存储/索引/缓存/任务、推荐与行为画像、通知与时限 | 03 §4 |
| 顺序图消息 | M1–M53 | 每条有发送者/接收者/事件步骤/职责/依据；编号、名称、收发方脚本核对（§4.4） |
| 顺序图分组 | 11 组，覆盖 E1–E20 与 A1–A20 的关键分支 | 组名与 04 §5 列举一致 |
| VOPC 类数 | 7 | 与顺序图内部对象集合一致（访客为参与者，不入 VOPC；§4.4） |
| VOPC 是否含持久结果/图快照/图版本/机构/领域/账号/评分/来源/版本/BFS 结构类 | 否 | 只含 UI/Coordinator/`Scholar`/`WorkEntity`/`Authorship`/论文投影 `CollaborationEdge`/`PathResult` |
| 是否把全局 `DGM-CLASS-01` 当作本 UC VOPC | 否 | 05 §6；07 §1–§3 只做差异比较 |
| 图是否只用合格共同论文证据 | 是（有守卫） | M5/M15/M18；A8/A10；BR-UC15-01 |
| 是否出现专利/项目/获奖边或证据 | 否（显式排除） | M5/M15/M18/M51；05 §5；07 §4.4 |
| 端点与节点是否只用公开学者 `canonical_id` | 是（有守卫） | M12–M14/M23–M25；A3–A6/A15；BR-UC15-04 |
| 是否用同机构/同领域/相似姓名推断路径 | 否（显式排除） | M14/M19/M51；A9；BR-UC15-16；07 §4.4 |
| 长度是否用边数、而非强度/k | 是 | M29；BR-UC15-07；SPEC §4 |
| 等长是否字典序唯一并说明规则 | 是（有守卫＋披露） | M29/M37/M39；A11；`AC-IMPL-IN01-02`；BR-UC15-08 |
| 每跳是否有至少一篇可点击论文证据 | 是（有守卫） | M31–M36；IN01-R3；BR-UC15-10 |
| k 是否与 FR12 强度分别声明 | 是 | M18/M35/M37；BR-UC15-06/18；A17 |
| 待核验/争议/已排除是否进入子图或作证据 | 否（守卫排除） | M11；A9/A10；`AC-IMPL-FR12-04` |
| 无路径/无效端点/超规模/失败/超时是否分开 | 是，含图版本失效共七类可区分 | M22/M25/M38/M49–M50；BR-UC15-14 |
| 筛选是否用发生时口径、未知不命中、先筛后建边 | 是（有守卫） | M19–M21；A14；BR-UC15-11/12 |
| 披露是否同屏含范围/筛选语义/图版本/长度定义/等长规则/规模/不可推断 | 是 | M37–M39；E17；BR-UC15-15 |
| 性能与规模是否只作为目标声明、未声称达标 | 是 | 01 首部、E13、A19；08 §5 NOT RUN |
| 是否出现跨范围推断或补边 | 否 | M5/M19/M35/M51；A7/A9/A20 |
| 是否有写消息或持久路径结果/图快照对象 | 否：全图 6 条只读请求（M6/M9/M12/M23/M33/M42），无写消息；`CollaborationEdge`/`PathResult` 为查询内投影 | 04 §4；05 §4；BR-UC15-19 |
| 是否引入设计技术（图库、索引、缓存、任务、接口、DTO、BFS 代码） | 未引入 | 03 §4；05 §4；06 §11 |
| 待确认项是否被写成已确认规则 | 否 | B-01～B-12、C-01～C-06 在 01/02/03/05/06/07 就近标注 `待确认` |
| README 交付表 9 项是否齐全 | 是（01–08 与 2 张图的源/SVG/PNG） | `README.md` §1/§2 与实际文件一致 |

## 4. 实际执行的验证

### 4.1 PlantUML 渲染

实际执行（仓库根目录）：

```bash
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-15/diagrams/DGM-SEQ-23-shortest-path.puml docs/analysis/stage-02/uc-15/diagrams/DGM-VOPC-19-shortest-path.puml
PLANTUML_LIMIT_SIZE=8192 plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-15/diagrams/DGM-SEQ-23-shortest-path.puml docs/analysis/stage-02/uc-15/diagrams/DGM-VOPC-19-shortest-path.puml
```

结果（两条命令均 `exit=0`，无 PlantUML 语法错误图）：

| 模型 | PUML | SVG | PNG | 目视检查 |
|---|---|---|---|---|
| UC-15 论文子图筛选与最短路径分析序列 | 有 | 有（viewBox `0 0 3131 4964`；`Syntax Error` 计数 0；69743 字节） | 有（3129×4962；1296276 字节≈1.24 MB） | 已查看（整体＋局部裁切）：8 条生命线（访客＋7 类）；11 个分组标题；M1–M53 编号与文字可读；图例 12 条可读 |
| UC-15 VOPC | 有 | 有（viewBox `0 0 3314 1745`；`Syntax Error` 计数 0；24019 字节） | 有（3312×1745；520491 字节≈508 KB） | 已查看（整体）：7 个类；6 条业务关联多重性（`0..*:2`、`0..*:1..*`、`0..*:1..*`、`0..*:0..*`、`0..*:1`、`0..*:0..1`）可读；5 条只读依赖与 1 条 UI→Coordinator 关联可读；无 `GraphSnapshot`/`PathState`/`BfsQueue`/`Institution`/`Field`/`UserProfile`/评分或 DTO |

> 重复渲染幂等：再次执行上述两条命令后，两张 SVG 与两张 PNG 的字节内容与绘制前完全一致（`md5sum -c` 全部 OK），说明当前 `.puml` 与本包导出一致、无手工改图。
> 序列图 PNG 使用 `PLANTUML_LIMIT_SIZE=8192`（默认上限 4096 px）；SVG 不受该限制。
> 渲染成功只证明语法与可读性，不证明业务运行或评审通过；SVG/PNG 由 PlantUML 生成，未手工修改。
> 布局观察（如实记录）：守卫自消息文本较长，序列图纵向达 4962 px、横向 3129 px（与上游 `DGM-SEQ-22` 的 5257 px 同量级）；整体缩放预览时部分长消息与右侧生命线显得拥挤，已在原始分辨率下局部裁切复核 M1–M11、M47–M53 与底部图例，编号、文字与分组标题完整可读。VOPC 采用 `left to right direction`：UI→Coordinator→五类实体分层清楚，`Scholar` 与 `WorkEntity` 附近的 `1..*`/`0..1`/`0..*` 端标在原始分辨率下可区分，但整体缩放预览时略显拥挤；本轮未进一步改图源。
> 消息字号设为 12 以改善长消息换行（13 时出现 `AC-IMPL-FR12-01/`＋`05` 与孤立 `）` 的断行）；调整后横向 3131、纵向 4964，引用串不再被拆断。

### 4.2 69 AC 与基线

```text
$ python3 scripts/docs/verify_69_ac.py
PASS: 69 AC unique; SRS text matches ledger; trace has all 69; UC-01..19/22 present; A1c and scale markers present; active references resolve.
GROUPS: {'FR': 54, 'IN01': 4, 'NF': 11}
exit=0
```

### 4.3 文档检查

本文件写入前（保留，不隐藏）：`README.md` 已链接 `08-self-check.md` 而该文件尚未写入，检查为 `status: FAIL`、`relative_links: 2175`、`uml_svg_pairs: 30`、`errors: ["docs/analysis/stage-02/uc-15/README.md: missing 08-self-check.md"]`、`exit=1`。

本文件写入后重跑：

```text
$ python3 scripts/docs/check_documentation.py
{
  "status": "STATIC_PASS_ONLY",
  "relative_links": 2182,
  "uml_svg_pairs": 30,
  "errors": [],
  "external_urls_and_anchors": "NOT_CHECKED",
  "human_approval": "NOT_VERIFIED",
  "runtime_tests": "NOT_EXECUTED"
}
exit=0
```

> 上面按 UC-13/UC-14 同一格式省略了与本包无关的 `word`（5 个 `.docx` 的 ZIP/XML＋AC 对齐摘要，均无缺失 AC）与 `local_source_dependencies: []`。
> 相对链接数由 2175 增至 2182，差值即本文件新增的 7 条相对链接（01–07）；`errors` 由 1 条（缺 `08-self-check.md`）归零。
> `uml_svg_pairs: 30` 只统计 `docs/uml/` 下的源图；本包 `docs/analysis/stage-02/uc-15/diagrams/` 的 SVG/PNG 由 §4.1 的渲染命令与相对链接检查共同覆盖。

### 4.4 编号与结构脚本核对

```text
$ python3 - <<'EOF'  # 提取 puml 消息号/收发方与 04 表逐行比对；比对 VOPC 类与顺序图对象
puml messages 53 min 1 max 53 dups False
doc rows 53 missing in puml(doc-only) [] missing in doc []
send/recv mismatch count: 0   （文档自消息写作“自身”，按 Coordinator 归一后比对）
name mismatch count: 0
VOPC classes 7 ['CollaborationNetworkUI', 'CollaborationPathCoordinator', 'Scholar', 'WorkEntity', 'Authorship', 'CollaborationEdge', 'PathResult']
vopc not in seq: set() | seq not in vopc: set()
business associations: [('Edge','0..*','2','Sch'), ('Edge','0..*','1..*','Work'), ('Path','0..*','1..*','Sch'), ('Path','0..*','0..*','Edge'), ('Auth','0..*','1','Work'), ('Auth','0..*','0..1','Sch')]
message-ref coverage: 05-vopc.md 53/53、06-class-definition.md 53/53（区间写法如 M3–M5、M28–M30 展开后）
out-of-range message refs: []
EOF
```

结论：顺序图与 04 逐消息表的消息号 M1–M53 完全一致，无缺号、无重复；表内发送者/接收者与图中对象（按别名映射 `Visitor/UI/Ctl/Sch/Work/Auth/Edge/Path` → `访客/CollaborationNetworkUI/CollaborationPathCoordinator/Scholar/WorkEntity/Authorship/CollaborationEdge/PathResult`）逐条一致（自消息统一记为“自身”）；消息名称逐条一致。VOPC 参与类 7 个，与顺序图内部对象集合一致（访客为参与者，按 §5.4.4 不入 VOPC）；业务关联 6 条、只读依赖 5 条、UI→Coordinator 关联 1 条，与 05 §2、06 §9 的表行一一对应；05/06 的消息引用并集覆盖 M1–M53，无越界引用。

**核对中记录的差异（本轮不修改文档，按要求只报告）：**

| 位置 | 观察 | 判定 |
|---|---|---|
| 06 §3“类型”行 | 用区间简写 `M3–M37、M46、M49、M51` 描述协调者参与范围，字面上包含了不含协调者的 UI→访客消息（M39/M45/M48/M53） | 区间写法不精确，非类/编号缺失；留待后续编辑（R5 前顺手修正为分段区间） |
| 05 §1 协调者行 | 只列举“接收（M2/M41）”，未列举它接收的 8 条应答（M7/M10/M13/M17/M21/M24/M27/M30/M32/M34/M36/M43 中的实体应答） | 列举不穷尽但不矛盾；对应实体行以“（应答）”标出，VOPC 图例以“其余为返回”概括 |
| 04 §2 M38 状态枚举 | 列出“有结果/无匹配边/无路径/端点不可用/已合并/超规模/失败/超时”，而 06 §3 `outcome` 另含“版本失效/拒绝” | 两处为同一状态集合的不同粒度表述；06 为分析态全集，04 为本次显示分支，非编号冲突 |

## 5. NOT RUN / 未验证

| 项目 | 状态 | 说明 |
|---|---|---|
| `AC-IMPL-IN01-01/02/03/04` 的实际验收 | NOT RUN | 需求层 AC 为计划验收；本包只做静态分析 |
| 运行时行为：条件校验、图版本固定、论文资格、关系资格、节点/端点资格、论文边投影、筛选重建、规模守卫、端点分类、BFS、等长裁决、逐跳证据、披露、导航、版本一致性 | NOT RUN | 无实现代码 |
| 最短路径正确性实测（含 T37/T39 样例的 A→B→C 两跳、等长字典序同一条） | NOT RUN | 无实现与样例执行；SPEC §5 的 T37～T40 为规范预期，不是已执行结果 |
| 规模与超限分支实测（≤1 万节点/≤10 万边；超规模可观察） | NOT RUN | `AC-IMPL-IN01-04`（NOT RUN）；本包只声明守卫与披露 |
| 性能（路径 P95≤2 秒）与 NF02 闭环负载（≥500 万实体、100 活跃用户、5 秒单请求超时、P50/P95/P99） | NOT RUN | 目标值来自 `AC-IMPL-IN01-04` 与 §11.2；本包不声称已达成 |
| 逐视图一致性与传播实测（NF05：检索/门户/统计/基础网络/IN01 同一生效关系版本；60 秒目标） | NOT RUN | 60 秒传播目标不在本轮已批准量化清单内 |
| 筛选口径的真实表现（发生时机构/领域、未知不命中、跨维度交集、先筛后建边） | NOT RUN | 口径细化自述为整改 RC（B-01/B-02），未实测 |
| 端点不可用三类提示、合并后重算、禁止推断同机构合作 | NOT RUN | 无实现；本包只声明守卫 |
| 字符集/字体在生成环境的表现 | 已目视检查（本机 Noto Sans CJK SC，`fc-list` 命中 2 条） | 跨环境未验证；SVG/PNG 由本机 PlantUML 1.2020.02 生成 |
| 人工评审/教师评分 | NOT VERIFIED | 自动静态检查不等于签认 |

## 6. 待评审/待确认

| 编号 | 问题 | 为什么不能自行决定 | 当前处理 |
|---|---|---|---|
| B-01 | **筛选维度与取值语义**：是否接受年份条件？每个维度单值还是多值？多值是“任一命中”还是“全部命中”？跨维度是否取交集？ | 基线 IN01-R1 只写“按领域、机构筛选”；SPEC §3 与模块 IN01-S01/S02 的细则自述为整改 RC / 联合评审项；`DGM-USECASE-02` 又写“时间窗口” | 按基线建模领域/机构两维；SPEC §3 的“所处时间/交集/单值”作为细化引用并留空；年份条件不建模（M3/M19） |
| B-02 | **机构与领域口径**：机构规范名与别名归并归谁（Q-4/D-04）；领域分类是否含下级（IN01-S01 声明项）；映射版本如何可见 | 属需求/治理规则，不能由分析者代定 | 按 §11.5“发生时已核验事实优先、不得回填当前机构”建模（M5/M6/M19）；归并与映射来源留空 |
| B-03 | **规模上限口径**：`AC-IMPL-IN01-04` 的 1 万节点/10 万边是测试图规模与超限分支；模块 Q-6 另提“节点≤150、边≤400”的**展示上限**候选 | 两者用途不同且 Q-6 未决；混用会导致“合法图被判超规模”或“截断后仍称完整” | 只按 AC 声明超规模分支（M22）；展示上限与“部分结果”标注留空（C-06） |
| B-04 | **未知机构/领域与时间未知**：不命中为 SPEC §3 明文；披露计数、是否影响端点可用性、模拟/混合在演示语境的展示属推导 | 披露口径与数据类别展示规则未冻结 | 未知不命中并计入披露（A14）；其余留空 |
| B-05 | **论文作者“合格关系”的证据门槛**：本人确认 vs 已批准自动规则/独立人工核验证据的判定标准未冻结（SPEC §1；与 UC-14 B-06 同源） | 门槛属需求规则 | 只写“身份明确、可公开、状态合格”的守卫（M11）；门槛留空；身份未定/缺证据不进入（A10） |
| B-06 | **路径证据计数 k 的展示口径**：BR-518 要求与 FR12 强度分别声明；是否并列展示、k 是否须等于 `weight` 的论文部分未规定 | 展示与校验口径属需求 | 只写“k 只计本范围合格共同论文、与 weight 分别声明”（M18/M35/M37）；并列方式留空 |
| B-07 | **图版本标识的展示与旧版本可用性**：访客是否可见版本标识、是否允许指定旧版本重查 | SPEC §2 提到“旧快照”，但未规定对外语义 | 要求“可给出标识并同屏披露”（M37/M47）；可见性留空 |
| B-08 | **图版本对象化程度与上一版结果**：`AC-IMPL-IN01-02` 要求固定图版本；V2.2 未定义快照对象/生成时机/失效/回退；BR-523 细化要求失败时展示上一版可用结果 | 对象化程度决定状态机、持久化与验收方式，超出本 UC 授权 | 按“查询内固定版本＋读时投影”建模（06 §8/§11）；对象化与上一版结果留空（C-01） |
| B-09 | **等长路径总条数是否必须披露**：基线只要求返回一条＋说明规则；模块 IN01-S08/BR-525 要求“全部列出或说明规则与总条数” | 基线未规定；若要求总条数会影响性能目标 | 保证“一条＋说明规则”（M29/M37）；总条数留空（C-02） |
| B-10 | **超时与超规模的可观察阈值/文案**：P95≤2 秒目标与 NF02 单请求超时 5 秒的关系、提示文案未冻结 | 参数与文案属非功能验收细则 | 只要求“明确超时/超规模反馈、不无限加载、不截断冒充完整”（M22/M49） |
| B-11 | **路径与筛选的视图归属与命名**：本 UC 沿用上游 `CollaborationNetworkUI` 作为入口，控制类新建 `CollaborationPathCoordinator`；全局只有 `AnalyticsUI ..> GraphCtl` | 命名统一属 R5 全局职责（D-11） | 沿用上游边界并自建协调者（03 §3、07 §2）；不修改全局图与登记表 |
| B-12 | **端点选择方式与不可用提示粒度**：按 `canonical_id`、门户链接还是检索结果选择；三类不可用的提示粒度与是否提供合并前后对照 | 交互细则未规定；同时受 NF06“不泄露未公开信息”约束 | 只要求“区分不存在/未公开/已合并并给合并后入口”（M25/A3–A5）；选择方式留空 |
| C-01 | **取向差异：** 本包按“查询内固定图版本＋读时投影、不建持久路径结果/图快照/结果状态机”建模；BR-523 细化、`DGM-STATE-03` 与 SPEC §2 的“旧快照”语义隐含结果/快照对象 | 本 UC 无权修改模块 SRS 与既有图；持久对象需未定义的版本/失效/回退规则 | 按投影建模（05 §4、06 §8/§11、07 §5）；交评审决定 |
| C-02 | **文本冲突：** 模块 IN01-S08/BR-525“全部列出，或按约定规则展示并说明规则与总条数” vs 基线 `IN01-R2`/`AC-IMPL-IN01-02`“字典序选择一条” | 基线优先（SRS V2.2 与 69 AC 为现行基线，模块 SRS 为细化） | 按基线只返回一条＋说明规则（M29）；总条数列 B-09，差异登记 07 §5 |
| C-03 | **范围差异：** 模块 IN01-S01/S02 把领域/机构筛选挂在**基础合作网络**（四类成果）上，基线 IN01-R1 与 SPEC §3 只在**合格共同论文子图**筛选 | 基线优先；把筛选扩展到四类基础网络属需求变更 | 只对论文子图筛选（M5/M19）；差异登记 07 §5、01 §11 |
| C-04 | **文本冲突（继承 UC-14 C-04）：** `DGM-USECASE-02` 图例“FR12 和 IN01 均只接受合格共同论文证据”与基线“FR12 使用四类合格共同成果”冲突 | 修图属全局资产与 R5/评审职责 | 本 UC 侧口径正确（IN01 只论文），但建议按基线修订图例（07 §5/§7） |
| C-05 | **行为冲突：** 端点相同——SPEC §4 与 `DGM-ACTIVITY-03` 为“0 跳、无边证据”，模块 IN01-S03 A1 为“提示起点与终点相同、不返回路径” | 基线未规定，两处上游文本冲突 | 按 SPEC §4/活动图建模 0 跳（M25/A6），并登记待确认 |
| C-06 | **口径差异：** 模块 Q-6 的网络展示上限（节点≤150/边≤400）与 `AC-IMPL-IN01-04` 的 1 万/10 万用途不同（展示截断 vs 测试图规模与超限分支） | 两者都未与 AC 显式对齐；如何对外披露需评审 | 只按 AC 声明（M22）；展示截断口径留空（B-03） |

## 7. 结论

```text
UC-15 R1: STATIC COMPLETE (check_documentation status=STATIC_PASS_ONLY, errors=0)
UML RENDER: 2/2 OK (SVG+PNG, no syntax error; re-render byte-identical)
AC BASELINE: 69 UNCHANGED (verify_69_ac PASS)
MESSAGE CONSISTENCY: M1-M53 numbers+names+senders+receivers exact match
VOPC: 7 classes, 6 business associations, 5 read-only dependencies, 1 UI->Coordinator association
PERF/SCALE: P95<=2s and <=10k nodes/<=100k edges are TARGETS; NOT RUN
RUNTIME TEST: NOT RUN
HUMAN REVIEW: 待评审
S02 STATUS: PARTIAL（总状态与共享追踪由父 Agent 维护）
```

UC-15 已按教材 §5.4.1–§5.4.4 与 §5.5.1–§5.5.4 完成一次可追溯的用例实现分析，并产出独立于全局 BCE 的 VOPC。B-01～B-12 与 C-01～C-06 仍需需求确认或 R5 处理；在评审人确认前，不把 UC-15 标为“业务已批准/教师已验收”，也不声称路径性能目标已达成。
