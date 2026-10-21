# UC-18 分析与现有全局模型差异

**版本：** V1.0（2026-10-21）
**比较对象：** [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml)、[`01-BCE分析类模型.md`](../01-BCE分析类模型.md)、[`DGM-USECASE-03`](../../../uml/collection/collection-local-usecase.puml)、[`DGM-STATE-01`](../../../uml/collection/collection-task-state.puml)、[`DGM-SEQ-01`](../../../uml/analysis/DGM-SEQ-01-collection.puml)、[`collection-update-activity`](../../../uml/collection/collection-update-activity.puml)、[`02-用例模型与规约.md`](../02-用例模型与规约.md)、[`05-分析序列与用例实现.md`](../05-分析序列与用例实现.md)、UC-09/10/17 已交付分析包、候选类登记表 [`analysis-class-registry.md`](../automation/analysis-class-registry.md)、待决清单 [`unresolved-decisions.md`](../automation/unresolved-decisions.md)、追踪表 [`03-AC-UC-分析模型追踪.md`](../03-AC-UC-分析模型追踪.md)。
**原则：** 全局图只作比对材料，不作为本 UC 识别类的反向答案；本轮不重构全局图、不修改共享登记表、不修改他人 UML 资产。

## 1. 已存在且一致

| UC-18 类/概念 | 全局对应 | 一致点 | 证据 |
|---|---|---|---|
| `AdminConsole` | `01-BCE` §2 `AdminConsole`（UC-17/18/19，“管理待办、来源、任务、审计查询”；“不负责：绕过具体权限”） | 统一管理入口；入口不等于授权 | FR08；§8；M1–M7 |
| `SourceAdapter` | `01-BCE` §2 `SourceAdapter`（UC-18/19，“与获准来源交换记录和失败信息”；“不自行决定公开/归属”） | 外部交换边界、不决策 | FR08；M34/M35/M37 |
| `CollectionCoordinator` | `01-BCE` §3 `CollectionCoordinator`（“任务生命周期、检查点、读取、处置汇总”；守卫“队列交接失败不算成功；重试不增重复实体”） | 生命周期、检查点、完成判据、重试幂等 | §6.2；M22–M58、M76–M81 |
| `CollectionTask` | 全局 `Task`（“+任务状态()/+检查点()”；`CollectCtl ..> Task`）；`01-BCE` §4 `CollectionTask`（“可停止/重试/检查点的任务”“不等于调度器/线程”） | 任务状态、检查点、可停止/重试 | §6.2；`DGM-STATE-01` |
| `SourceRecord` | 全局 `Source`；UC-02/09 已用 `SourceRecord`；`01-BCE` §4（原始来源、许可、逻辑出处、到达事件；“不等于成果实体”） | 原值不可改写、重复到达不增成果 | INV-01；FR09-06；A12 |
| `User` | 全局 `User`（“+账号状态()/+对象授权校验()”） | 账号状态与服务端校验 | §8；NF03；M3/M4/M78/M97 |
| `AuditEvidence` | 全局 `Evidence`；UC-09/10/17 已用 `AuditEvidence` | 受保护写入留痕可追溯 | BR-IMP-10；INV-10；M13/M26/M60/M67/M71/M82 |
| 任务六状态（待执行/运行中/成功/失败/已停止/重试） | `DGM-STATE-01` 与 §6.2 完全一致 | 状态集合、转移与守卫 | §6.2；`DGM-STATE-01`；M27/M59/M65/M70/M80 |
| 成功不等于全部公开/已核验 | `DGM-STATE-01` 图例“成功不等于全部公开；单列待核验、异常及未公开数量”；`DGM-SEQ-01` 注释“采集成功不等于全部公开或全部归属已核验” | 计数独立＋措辞约束 | FR08 规范性要求；§6.2；M60–M62 |
| 失败/停止保留已完成项、重试不增实体 | `DGM-STATE-01` 图例“失败/停止保留已完成项；重试不增实体和唯一出处” | 失败保留＋幂等 | `AC-IMPL-FR08-02`；INV-07；A3/A4/A6/A12 |
| “两种独立来源输入（API/文件任选）” | `DGM-USECASE-03` actor“外部数据来源”连接 UC-18；FR08 同句 | 外部来源参与者、两种输入 | FR08；E3/E11 |
| 系统/调度器不作为参与者 | `DGM-USECASE-03` 图例“系统、调度器不作为外部参与者” | 不画系统参与者、不建调度类 | 03 §1 原则 4；05 §1 |

## 2. 需要更名/别名统一

| 本 UC 名称 | 全局名称 | 差异 | R5 建议 |
|---|---|---|---|
| `CollectionCoordinator` | `DGM-CLASS-01`：`CollectCtl`；`01-BCE` §3：`CollectionCoordinator` | 长名/缩写两套并存 | R5 统一为 `CollectionCoordinator`（D-11 类） |
| `CollectionTask` | 全局 `Task`；`01-BCE` §4：`CollectionTask` | 命名差异 | R5 统一为 `CollectionTask`，并在职责注明“不等于调度器/线程” |
| `SourceRecord` | 全局 `Source`；UC-02/09 已用 `SourceRecord` | 命名差异（既有） | 沿用 `SourceRecord`（登记表已定“以 `SourceRecord` 为正式名”） |
| `AuditEvidence` | 全局 `Evidence` | 一名多义（材料＋留痕，D-13） | 沿用 `AuditEvidence`（UC-09/10/17 同结论） |
| `GovernanceCoordinator` | 全局 `GovernCtl`；UC-09 `DedupCoordinator`；UC-10 `IdentityVerificationCoordinator` | **三种写法并存** | R5 决定“一个总治理协调者还是按治理域拆分”，并统一命名（D-11；B-10） |
| `SourceDefinition`（新） | 全局无对应类 | 全局把配置与记录都隐含在 `Source` 中 | R5 新增 `SourceDefinition`，并在 `Source`/`SourceRecord` 职责写明“不含配置” |
| `GovernanceException`（新） | 全局无对应类；全局异常多以 `Evidence` 承载 | 全局把“业务异常项”与“动作留痕”混用 | R5 新增 `GovernanceException`（业务待办），并在 `Evidence`/`AuditEvidence` 职责限定为“动作留痕” |
| `AdminConsole` | 全局 `AdminUI` | 命名差异（UC-17 已记录） | R5 统一（同 UC-17 结论） |

## 3. 职责差异/需要补充

| 项目 | 本 UC 证据 | 全局现状 | 差异/影响 | R5 处理 |
|---|---|---|---|---|
| 来源配置作为独立事实 | M10–M12/M18/M19；FR08“配置来源”；FR16 | `01-BCE` 只有 `SourceRecord`（原始证据）；全局 `Source` 未区分“配置 vs 到达” | 实现时可能把许可/数据性质写在每条记录上，或无法在启动前判定来源是否获准 | 在 `SourceRecord` 职责写明“不含配置”，新增 `SourceDefinition` |
| 启动分两段（创建待执行→启动运行中） | §6.2；`DGM-STATE-01`；M23/M27 | **`DGM-SEQ-01` 直接“创建运行中任务与检查点”，跳过待执行** | 会把“待执行”状态与启动守卫（权限/来源/范围）在实现中消失 | 按 §6.2/STATE-01 改为“创建待执行→守卫通过后→运行中”，或在 SEQ-01 加注记 |
| 异常项与留痕分离 | M42（异常项）vs M13/M26/M60……（留痕）；A11 | **`DGM-SEQ-01` 用 `Evidence` 记录异常/待核验与原因** | “业务待办”与“动作留痕”混为一类，异常计数与关闭责任不清 | 新增 `GovernanceException`；`Evidence` 只保留动作留痕 |
| 完成判据的可观察定义 | M54–M58；§6.2 | `DGM-SEQ-01` 只写“全部已读记录均有处置”，未区分“读到”与“记录到结果/异常队列” | 可能把“读完但交接失败”判成功 | 在 SEQ-01 与 `CollectionCoordinator` 职责补“记录到结果或异常队列” |
| 待核验/异常计数独立与措辞约束 | M60–M62；FR08 规范性要求 | `DGM-SEQ-01` 返回“任务成功 + 待核验数量”，未约束“不得称人工治理完成” | 结论文本可能夸大治理完成度 | 在返回契约中补“单列计数＋限定措辞” |
| 检查点与任务历史的追加保存 | M25/M44/M45/M89；§6.2 | `01-BCE` §4 `CollectionTask` 只写“检查点”，未写任务历史 | 重试与失败定位可能丢失历史 | 在 `CollectionTask` 职责补“追加式任务历史（每次尝试）” |
| 重试上限与检查点间隔未冻结 | A7；B-01/B-02；D-10 | 全局无 | 可能被实现为固定次数/固定间隔并当成需求 | 分析层不写数值，留需求确认 |
| 来源停用/更换的归属 | B-06（沿用 UC-17 B-08 归 UC-19） | `DGM-USECASE-03` 同时列 UC-18/UC-19；`collection-update-activity` 覆盖两者 | 归属不清会让 UC-18 扩进增量语义 | 由父 Agent/评审确认；本 UC 只读来源有效性 |
| 不建执行框架类 | 03 §4；05 §4 | 全局无调度类（`DGM-USECASE-03` 明确“调度器不作为外部参与者”） | 一致，无需修改 | 保持；R5 不新增 `Scheduler`/`TaskQueue` |

## 4. 关系差异

### 4.1 `CollectCtl ..> Task`（全局）

- 全局以一条依赖表达“任务生命周期”，未表达来源定义与任务的一对多、任务与来源记录的归属。
- 本 UC 增加：`SourceDefinition 1:0..* CollectionTask`、`SourceDefinition 1:0..* SourceRecord`、`CollectionTask 1:0..* SourceRecord`、`CollectionTask 1:0..* GovernanceException`、`SourceRecord 0..1:0..* GovernanceException`。
- **R5 建议：** 保留 `CollectionCoordinator → CollectionTask`（生命周期），并新增上述 5 条业务关联；任务→记录写“首次登记”，避免与 `SourceDefinition → SourceRecord` 重复语义。

### 4.2 `AdminUI ..> CollectCtl / GovernCtl / AuditCtl`（全局）

- 全局把后台边界同时连到采集、治理、审计三个协调者，图上无法区分“导航”与“执行”。
- 本 UC 的 `AdminConsole` 只连 `CollectionCoordinator`，外部来源交换由独立边界 `SourceAdapter` 承担；治理只通过 `CollectionCoordinator → GovernanceCoordinator` 的移交/回执表达。
- **R5 建议：** 保留 `AdminConsole → CollectionCoordinator`；把 `AdminUI → GovernCtl` 改为导航语义或加注记；`SourceAdapter` 保持独立边界。

### 4.3 `Collect -> Adapter : 读取获准来源批次`（`DGM-SEQ-01`）

- 一致：外部读取由边界承接，协调者不直接实现协议。
- 本 UC 增加“按来源定义与快照请求”“失败/断连反馈”两条可观察结果（M34/M37）。
- **R5 建议：** 在 SEQ-01 的 Adapter 消息上补“失败反馈”分支标签，避免把断连当作“无新数据”。

### 4.4 新增业务关联（全局无）

- `AuditEvidence 0..* : 0..1 User`（操作者；系统动作无账号）——与 UC-09/10/17 同源。
- **R5 建议：** 新增该边并保留 `0..1`；若评审要求“凡留痕必有操作者账号”，需先修正“系统自动动作”的执行者类型（SPEC §3）。

### 4.5 未新增的边（有意省略）

- `GovernanceCoordinator → SourceRecord/GovernanceException`：治理侧的读取与关闭属 UC-09/10，本 UC 不重复画。
- `GovernanceException → 治理候选/成果`：本 UC 不判定、不转换，故不画。
- `CollectionTask → WorkEntity`：本 UC 不创建/不更新成果（A11）。
- `SourceAdapter → SourceRecord/SourceDefinition`：边界只交换记录与失败信息，不写业务实体、不决定许可。

## 5. 与现有用例图/状态图/活动图的一致性

| 全局资产 | 与本 UC 的一致点 | 需登记/需修正的差异 |
|---|---|---|
| [`DGM-USECASE-03`](../../../uml/collection/collection-local-usecase.puml) | `管理员 -- UC-18`、`外部数据来源 -- UC-18`；图例“调度器不作为外部参与者” | 图中用例名“UC-18 管理数据来源与采集任务”与 [`02-用例模型与规约`](../02-用例模型与规约.md)／本包的“管理来源及采集任务”用词略有差异（“数据来源”vs“来源”）。**R5 建议：** 统一名称，避免同一 UC 两种叫法 |
| [`DGM-STATE-01`](../../../uml/collection/collection-task-state.puml) | 六状态、转移与守卫与本 UC 完全一致；图例“失败/停止保留已完成项；重试不增实体和唯一出处”“成功/停止后另建关联任务”直接支撑 A3/A6/A9/A12 | **无冲突，无需修改。** 本 UC 不新增状态、不画状态机；重试上限与检查点间隔不在图中，故也不在此新增转移 |
| [`collection-update-activity`](../../../uml/collection/collection-update-activity.puml) | “权限/来源/范围有效→保存快照→待执行→运行中”“读取失败→失败并保留恢复范围”“处理或队列交接失败→失败”“保存本批互斥处置计数”“成功不表示全部公开或已核验归属”与本 UC 一致 | ①该图同时覆盖 UC-18/19（含增量判定），本 UC 只取采集部分；②该图标题写作 `DGM-ACTIVITY-05`，与 [`04-活动图与状态图`](../04-活动图与状态图.md) 中 `DGM-ACTIVITY-05`（注册并自动建立公开主页）**编号冲突**；③该图 `repeat` 循环以“本轮范围仍未读完或仍有未完成项”为条件，未体现“计划输入是否全部记录到结果/异常队列”的判据。**R5 建议：** 重新编号并在循环结束处补完成判据（本包不修改该资产） |
| [`DGM-SEQ-01`](../../../uml/analysis/DGM-SEQ-01-collection.puml) | 外部来源、任务、来源记录、异常/待核验的分工与本 UC 同向 | ①“创建运行中任务”跳过待执行（§3）；②用 `Evidence` 承载异常/待核验（§3）；③同图内含治理建成果分支，属 UC-09/10；④无 `SourceDefinition`。**R5 建议：** 按 §3/§4 修订或加注记，并把治理分支指向 UC-09 |
| [`01-BCE分析类模型`](../01-BCE分析类模型.md) §2/§3/§4 | `AdminConsole`/`SourceAdapter`/`CollectionCoordinator`/`CollectionTask`/`SourceRecord` 职责与本 UC 一致 | §4 未定义 `SourceDefinition`/`GovernanceException`，且未写“任务历史”“完成判据”“异常无公开可见性”。**R5 建议：** 补两类的独立存在理由与上述守卫 |
| UC-09/10/17 已交付分析 | UC-09 把 `CollectionTask` 标为“不参与、由 UC-18/19 持有”；UC-10 的候选/归属状态不作本 UC 输入；UC-17 只读 `CollectionTask` 的状态与计数 | 无冲突。本 UC 明确“只移交与取回处置结果”“异常项不设公开可见性”“待核验/异常计数独立”，与三者结论一致；`GovernanceCoordinator` 与 `DedupCoordinator`/`IdentityVerificationCoordinator` 的命名关系见 §2 |

## 6. 追踪文件与登记表（只提建议，不改文件）

| 文件 | 现状 | 建议（由父 Agent 决定） |
|---|---|---|
| [`03-AC-UC-分析模型追踪.md`](../03-AC-UC-分析模型追踪.md) | FR08 行“分析模型”列为 `DGM-USECASE-03 / STATE-01 / SEQ-01`；NF04/NF07 行同理；UC-18 反向行列出 5 条 AC | 为 FR08、NF04、NF07 与 UC-18 反向行增补 `SEQ-17 / VOPC-13` |
| [`analysis-class-registry.md`](../automation/analysis-class-registry.md) | 有 `AdminConsole`（UC-17→UC-18/19）、`AuditCoordinator`、`SourceRecord`（UC-02→UC-09/16/18/19）、`AuditEvidence`；**无** `SourceAdapter`/`CollectionCoordinator`/`CollectionTask`/`SourceDefinition`/`GovernanceException` 行 | 增量登记 `SourceAdapter`（Boundary）、`CollectionCoordinator`（Control）、`CollectionTask`（Entity）、`SourceDefinition`（Entity）、`GovernanceException`（Entity）；`SourceRecord` 行补“UC-18 写入侧起点（原值不改写、重复到达追加事件）”；`AuditEvidence` 行补“UC-18 追加配置/启动/停止/重试/完成/失败留痕” |
| [`unresolved-decisions.md`](../automation/unresolved-decisions.md) | D-10 已登记“来源 API/文件格式与重试上限” | 可评估登记：检查点间隔/批次大小（B-02）、计划输入口径（B-04）、空范围是否成功（B-05）、来源配置处置边界（B-06）、计数口径（B-08）、“结果”边界（B-09）、`GovernanceCoordinator` 命名（B-10）。本包不直接修改该文件 |
| 全局 `DGM-CLASS-01-analysis.puml` | `AdminUI`/`CollectCtl`/`Task`/`Source`/`Evidence` 命名并存；无配置类与异常项类 | 按 §2–§4 在 R5 统一；本 UC 不修改全局图 |

## 7. 差异结论

UC-18 分析支持以下全局模型调整建议：

1. **新增 `SourceDefinition`**：来源配置（来源身份/输入方式/许可与范围/数据性质）必须与到达证据 `SourceRecord` 分离；
2. **新增 `GovernanceException`**：异常项是业务待办（无公开可见性、可计数、可核对），不能由 `Evidence` 承载；
3. **完成判据写进模型**：`CollectionCoordinator` 的成功守卫＝“计划输入全部记录到结果或异常队列”，并把“记录到”与“已读取”区分开；
4. **启动分两段**：待执行→（守卫）→运行中，与 `DGM-STATE-01` 对齐；
5. **统一命名**：`CollectionCoordinator`/`CollectionTask`/`SourceRecord`/`AuditEvidence`（不是 `CollectCtl`/`Task`/`Source`/`Evidence`）；`GovernanceCoordinator` 与 UC-09/10 的拆分命名待 R5；
6. **不新增执行框架类**：`Scheduler`/`TaskQueue`/`JobRunner`/`RetryPolicy`/`Checkpoint`/`ImportBatch`/`ExceptionQueue` 一律不建；
7. **补齐未冻结参数**：重试上限、检查点间隔/批次大小、接入格式与计数口径仍需需求确认（D-10；B-01～B-03、B-08）。

本轮**不直接修改** `DGM-CLASS-01-analysis.puml`、`DGM-USECASE-03`、`DGM-STATE-01`、`DGM-SEQ-01`、`collection-update-activity`、`01-BCE分析类模型.md` 与共享追踪/登记文件。

> 追踪差异：`03-AC-UC-分析模型追踪.md` 第64行把 `AC-IMPL-NF04-02` 归 UC-18/UC-19，但本包将 RTO/RPO 恢复目标留给部署/运维验证；不把未运行的恢复演练写成已覆盖，交父 Agent/R5 确认归属。
> 分析类冲突：UC-09 用 `CleanRecord.governanceState`/`DGM-STATE-04` 表示“异常待处理”，本 UC 使用独立 `GovernanceException`；两者对“异常待处理”的单一所有者未定，登记为 R5 统一项。
