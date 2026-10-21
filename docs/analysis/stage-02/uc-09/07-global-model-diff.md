# UC-09 分析与现有全局模型差异

**版本：** V1.0（2026-10-21）
**比较对象：** [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml)、[`01-BCE分析类模型.md`](../01-BCE分析类模型.md)、[`DGM-ACTIVITY-06`](../../../uml/governance/governance-overview-activity.puml)（治理总体流程）、[`DGM-ACTIVITY-08`](../../../uml/governance/dedup-candidate-activity.puml)（去重候选判定）、[`DGM-STATE-04`](../../../uml/governance/governance-exception-state.puml)（处理项状态）、模块细化 [`governance-dedup.md`](../../../srs/modules/governance-dedup.md)、候选类登记表 [`analysis-class-registry.md`](../automation/analysis-class-registry.md)、待决清单 [`unresolved-decisions.md`](../automation/unresolved-decisions.md)、追踪表 [`03-AC-UC-分析模型追踪.md`](../03-AC-UC-分析模型追踪.md)。
**原则：** 全局图只作比对材料，不作为本 UC 识别类的反向答案；本轮不重构全局图、不修改共享登记表、不修改他人 UML 资产。

## 1. 已存在且一致

| UC-09 类/概念 | 全局对应 | 一致点 | 证据 |
|---|---|---|---|
| `DedupCoordinator`（治理判定部分） | `GovernCtl`（“+清洗/去重/候选”）与 `01` §3 `GovernanceCoordinator`（“清洗/去重/身份/归属候选处理；无稳定键转待核验；不改写原证据”） | 都承认存在一个治理协调者，负责清洗、去重与候选，且“无稳定键转待核验、不改写原证据”与本 UC 的 M34–M41、M3/M20 一致 | FR09；DD-03/DD-06/DD-07；BR-IMP-02 |
| `SourceRecord` | 全局 `Source`（“+原值/来源/许可()”“+逻辑出处()”；`Source -- Work : 来源证据`） | 都表达来源原值、许可与逻辑出处，且与成果实体分离 | §7.1/§7.2；CL-11；INV-01 |
| `WorkEntity` | 全局 `Work`（“+去重规范值()/+成果类型()/+生效状态()”） | 都是去重后的成果规范实体；来源数、版本数不等于成果数 | INV-02；DD-11；`AC-IMPL-FR16-02` |
| `WorkVersion` | 全局 `Version`（“+版本证据()”；`Work -- Version : 明示版本`） | 都要求“明示版本证据”才建立关系 | DD-08；`AC-IMPL-FR09-05` |
| `DedupCandidate`（概念） | `GovernCtl` 的“候选”职责 + `DGM-ACTIVITY-08` 的“候选匹配/待核验记录” | 都承认候选与待核验是治理的独立结果，不等于合并 | DD-04～DD-07/DD-12；FR09-S07 |
| `AuditEvidence`（概念） | 全局 `Evidence`（“+操作者/理由/前后值()”）与 `AuditCtl ..> Evidence` | 都要求治理动作留痕（操作者/理由/前后值） | FR09-S12；BR-381/382；BR-IMP-10 |
| “重复来源不加实体” | `01` §5 第 5 条“来源/成果分离：SourceRecord 保留原值；WorkEntity 去重；重复来源/版本不重复计强度”；`DGM-ACTIVITY-08` 的“幂等保护”段 | 与本 UC BR-UC09-09 与 M19–M23 一致 | `AC-IMPL-FR09-03/06` |
| “异常与待核验分开” | `DGM-STATE-04`（异常记录与待核验记录的统一处置载体，与采集任务状态机区分） | 与本 UC `CleanRecord.governanceState`＋`DedupCandidate.matchState` 的分类一致 | FR09-S11；SPEC §3 公共状态 |
| “类型细化必填项待评审” | 无全局对应（全局未定义最小字段） | 本 UC 依 SPEC §4.1 明确该边界 | SPEC §4.1；B-01 |

## 2. 需要更名/别名统一

| 本 UC 名称 | 全局名称 | 差异 | R5 建议 |
|---|---|---|---|
| `DedupGovernanceUI` | `AdminUI`（`DGM-CLASS-01`，仅三条 `..>`：CollectCtl/GovernCtl/AuditCtl）；`01` §2 `AdminConsole`（UC-17/18/19） | 全局把治理写入口混在管理控制台中，且未区分“批次结论/候选证据/合并拆分决定/字段确认”；`01` 的 `SourceUI` 只覆盖来源交换 | R5 明确治理写入口的归属：或在 `AdminConsole` 内显式列出“治理核验与合并/拆分/字段确认”职责与两套守卫，或保留独立 `DedupGovernanceUI` |
| `DedupCoordinator` | `GovernCtl`（“+清洗/去重/候选”）；`01` §3 `GovernanceCoordinator`（“清洗/去重/身份/归属候选处理”） | 全局把“成果去重（FR09）”与“身份/归属候选（FR15）”压在同一个协调者上，本 UC 只需要成果侧 | R5 拆分职责：成果治理（本 UC）与学者身份/归属（UC-10）分别定义协调职责，避免“同名合并”与“成果合并”共用一条判定路径 |
| `CleanRecord` | 全局**无对应类**；`Source` 只有“原值/来源/许可()”，`Work` 只有“去重规范值()” | 全局无法表达“逐字段原值＋规范值＋规则/字典版本＋治理状态＋异常原因＋字段冲突项”；原值/规范值的可回放性没有承载者 | R5 新增 `CleanRecord`（规范记录），并在 `GovernCtl` 职责中写明“建立规范记录、置治理状态”；否则“原值不被改写”只能落在文字约束上 |
| `DedupCandidate` | 全局**无对应类**；只有 `GovernCtl` 的“候选”字样与活动图中的“待核验记录” | 全局无法表达候选清单、逐项证据、候选规模、匹配状态与处置结论 | R5 新增 `DedupCandidate`；与 UC-08 的 `CorrectionRequest`、UC-07 的 `ScholarChangeRequest` 一并评估“待处理对象”的命名族 |
| `WorkEntity` | 全局 `Work` | 命名差异（登记表 D-11 已登记） | R5 统一为 `WorkEntity` 或 `Work`，二选一 |
| `SourceRecord` | 全局 `Source` | 命名差异（登记表 D-11 已登记） | R5 统一 |
| `AuditEvidence` / `CleanRecord` / `DedupCandidate` / `ReviewEvidence` | 全局 `Evidence` 一名多义 | 全局把“来源原值/审核材料/治理留痕/规范记录”压缩进一个 `Evidence` 类 | R5 拆分：`SourceRecord`（原值）、`CleanRecord`（规范记录）、`ReviewEvidence`（材料）、`AuditEvidence`（留痕）；见 D-13 |

## 3. 职责差异/需要补充

| 项目 | 本 UC 证据 | 全局现状 | 差异/影响 | R5 处理 |
|---|---|---|---|---|
| 类型最小字段与异常不入公开实体 | M4/M5/M7；CL-01；SPEC §4.1/§4.4 | 全局 `Work` 无“最小字段判定”，`GovernCtl` 只写“清洗/去重/候选” | 缺字段记录可能被直接建成公开实体 | 在治理职责写明“最小字段门槛→异常待处理”，`CleanRecord` 承载异常原因 |
| 原值/规范值分离与可回放 | M9/M11–M13；CL-11；INV-01 | 全局 `Source` 有“原值()”，`Work` 有“去重规范值()”，但没有中间层与规则版本 | 无法回答“这个规范值按哪条规则、哪个字典版本产生” | 新增 `CleanRecord`，补规则/字典版本与逐字段原值 |
| 时间语义与精度 | M12/M14；CL-06/CL-07 | 全局无时间精度职责 | 年份可能被补成日期、采集时间可能冒充发生时间 | 在 `CleanRecord` 与 `WorkEntity` 职责中写明事件语义＋精度 |
| 标识作用域（辖区/体系/授予机构） | M13；CL-03/CL-04；`AC-IMPL-FR09-05` | 全局无标识归一职责 | 未知辖区可能被当稳定键、跨体系可能误配 | 在治理职责写明“按作用域归一＋未知辖区不成键” |
| 稳定标识唯一命中守卫 | M24–M30；DD-03/DD-04 | 全局 `GovernCtl ..> Work` 只表达“清洗/去重”，未写“命中唯一且证据相容才关联” | 多命中时可能任选或误合并 | 在治理职责与 `WorkEntity` 操作中写明“唯一命中＋相容”；多命中列候选 |
| 无标识只出候选（标题不合并） | M34–M41；`AC-IMPL-FR09-02`；DD-06/DD-07 | **全局 `DGM-ACTIVITY-06` 与 `DGM-ACTIVITY-08` 都保留“证据达到条件→自动合并到候选实体”分支** | 与现行 69 AC/SPEC 冲突：现行明确“缺稳定标识只生成多证据候选”“无标识自动合并未批准” | **R5 必须修正两张活动图**（见 §5）；本 UC 不修改他人 UML 资产 |
| 候选证据与规模 | M35/M36/M40；DD-05/DD-12 | 全局只有“候选/待核验”字样 | 无法表达逐项证据与“候选过多”的判定证明 | 新增 `DedupCandidate`，补证据项与截断标注 |
| 版本证据与计数 | M46–M48；DD-08；`AC-IMPL-FR09-05` | 全局 `Work -- Version : 明示版本` 未标多重性，也无“无证据保持独立” | 版本数可能被当作实体数，或疑似版本被静默合并 | `WorkEntity 1→0..* WorkVersion`；职责写明“无明示证据不建立、不压缩实体数” |
| 幂等两计数 | M19–M23、M49；DD-01/DD-11 | 全局 `DGM-ACTIVITY-08` 有“幂等保护：实体计数保持不变”，但只提实体数 | 有效出处数（`distinct(source_id,source_record_key)`）未被表达 | 在治理职责与 `SourceRecord` 中写明两个计数与“到达事件可增” |
| 已确认值优先与冲突累积 | M28、M85–M88；CL-09；BR-IMP-04 | 全局 `Work` 无“已确认值”与“字段冲突项”职责 | 来源更新可能静默覆盖人工确认值 | `WorkEntity` 补 `confirmedValues`；`CleanRecord` 补 `fieldConflictItems`；治理职责补“冲突项累积” |
| 合并/拆分守卫与合并链 | M71–M84；DD-09/DD-10；FR09-S09/S12 | 全局 `GovernCtl ..> Work` 无合并守卫、无合并链 | 循环合并、部分合并、拆分抹除历史无法避免 | 在治理职责写明“类型一致∧无循环∧版本未变”“旧ID重定向”“拆分/撤销追加记录” |
| 审计覆盖拒绝与异常 | M57、M73、M95；FR09-S12 E1 | 全局 `Evidence` 只写“操作者/理由/前后值”，未表达结果/拒绝 | 越权与异常无法追溯 | `AuditEvidence` 补 `actionKind`/`outcome`/`targetRefs`/`occurredAt` |
| 不做身份判定 | A14；BR-UC09-17 | `01` §3 把“身份/归属候选处理”并入 `GovernanceCoordinator`；`DGM-STATE-04` 的注释把 UC-09/UC-10 并列 | 职责重叠，易把“同名合并”做成“成果合并”的同一路径 | R5 明确两协调者边界：成果实体合并（本 UC）≠ 学者身份合并/拆分（UC-10） |
| 不做采集与发布 | M1 只接收批次；无发布/删除消息 | 全局 `AdminUI ..> CollectCtl`、`SourceUI ..> CollectCtl` | 若按全局图实现，治理入口可能被做成采集/发布入口 | 在边界与职责中写明“只消费已到达记录；不采集、不重试、不发布、不下架” |

## 4. 关系差异

### 4.1 `GovernCtl ..> Work` / `GovernCtl ..> Authorship`（全局图）

- 全局把 `Work` 与 `Authorship` 的写入都挂在 `GovernCtl` 上；本 UC 的 `DedupCoordinator` 只写 `WorkEntity`/`WorkVersion`/`CleanRecord`/`DedupCandidate`，**不写 `Authorship`**（身份/归属属 UC-10）。
- **R5 建议：** 保留 `GovernCtl ..> Authorship` 仅用于“关系候选/改派”的治理处置（UC-10），并把“成果实体合并/拆分”与“关系归属判定”分成两条职责，避免两个语义共用一条写入路径。

### 4.2 `Work -- Version`（全局未标多重性）

- 本 UC 沿用 UC-02 的 `WorkEntity 1 : 0..* WorkVersion`，并强调“无明示证据不建立”。
- **R5 建议：** 统一标 `1 : 0..*`，并在 `Version` 注记“无证据不合并、版本数不增实体数”。

### 4.3 `Source -- Work : 来源证据`（全局未标多重性）

- 本 UC 沿用 UC-02 的 `WorkEntity 1 : 0..* SourceRecord`，并强调“合并/拆分/来源失效都不删除出处”。
- **R5 建议：** 统一标 `1 : 0..*`，并在 `Source` 注记“重复到达不新增有效出处、到达事件可增”。

### 4.4 `Version → Source`（全局无此边）

- 全局只有 `Work -- Version` 与 `Source -- Work`，没有“版本关系的明示证据来源”。
- 本 UC 依 DD-08 与 M46 新建 `WorkVersion 0..* → 1 SourceRecord : 明示版本指向`；UC-02 的 06 §13 已明确“`SourceRecord — WorkVersion`：本 UC 不建立，留待 UC-09/16”。
- **R5 建议：** 采纳该边并在全局图中补上；这是“补上被明示预留的关系”，不是改变语义。

### 4.5 `CleanRecord` / `DedupCandidate` 的新关系

- 全局无这两个类，因此也无 `SourceRecord 1→0..* CleanRecord`、`DedupCandidate 0..*→1 CleanRecord`、`DedupCandidate 1→0..* WorkEntity`。
- 不建议把“记录归入实体”画成 `CleanRecord → WorkEntity` 的独立边（会与 `WorkEntity→SourceRecord` 重复并在待核验时悬空），也不建议把合并链画成 `WorkEntity` 自关联（历史是追加式审计）。理由见 05 §2.3。
- **R5 建议：** 新增两个类与上述三条业务关联，并在 `WorkEntity` 注记“合并链/重定向由 `redirectRefs` 与审计表达”。

### 4.6 审计与实体

- 全局只有 `AuditCtl ..> Evidence`、`Evidence ..> Claim/Authorship`；本 UC 把对象指向写成 `AuditEvidence.targetRefs`（05 §2.3），因为审计必须覆盖被拒绝、未写入的对象。
- **R5 建议：** 统一“审计对象引用”的表达方式（属性 vs 关联），并补 `outcome`/`actionKind`。

## 5. 与现有活动图/状态图的一致性（含必须登记的冲突）

| 全局资产 | 与本 UC 的一致点 | 需登记/需修正的差异 |
|---|---|---|
| [`DGM-ACTIVITY-06`](../../../uml/governance/governance-overview-activity.puml)（治理总体流程） | 泳道划分（系统清洗/系统去重/系统字段合并/管理员人工处置/系统留痕生效）与本 UC E2–E17 的分段一致；异常→“进入异常或待核验队列”与 A1/A3/A5/A8 一致；“与已确认或已审核值冲突→保留、不覆盖”与 M28/M85 一致；“处置为合并→保留全部出处”“处置为新实体→记录人工依据”与 M74–M79/M67 一致 | **冲突：** 该图“无稳定标识 → 生成候选并比对多项证据 → 若‘证据达到自动合并条件’则‘合并到候选成果实体（FR09-S09）’”。现行基线为 `AC-IMPL-FR09-02`＋SPEC DD-06（“无稳定标识只生成多证据候选；历史四证据自动合并提案未获批准，不可执行”）＋SPEC §5.3（阈值“不生效”）。**建议 R5 修改该分支为“达到条件也只生成候选/待核验”；本 UC 不修改该图** |
| [`DGM-ACTIVITY-08`](../../../uml/governance/dedup-candidate-activity.puml)（去重候选判定） | 稳定标识路径（存在标识→按类型查找→命中唯一→关联既有实体；命中多个→待核验列全部候选）与本 UC M24–M33 一致；无候选→新建（依据“无标识新建”）与 M42 一致；版本段“有明确来源关系或已审核匹配→并入版本列表（实体计数不增加）”与 M46/M47 一致；幂等段与 M19–M23 一致 | **冲突：** “四项证据全部命中 → 自动合并到该候选实体（FR09-S07）”，与 DD-06/AC-IMPL-FR09-02 冲突（同 §3 行）。另：管理员段写“确认合并/确认非同一成果”，本 UC 另补“拆分/确认新实体/字段确认/修正/忽略”（FR09-S11 主流程 3、FR09-S12 A3）。**建议 R5 修正；本 UC 不修改该图** |
| [`DGM-STATE-04`](../../../uml/governance/governance-exception-state.puml)（处理项状态） | “异常记录与待核验记录的统一处置载体”“与采集任务状态机不得混用”“留痕只追加不覆盖”“同一处理项不得并列重复”与本 UC BR-UC09-13/14、M63（状态版本）一致 | 本 UC 只建模 `CleanRecord.governanceState`（有效/异常待处理/待核验/已处理）与 `DedupCandidate.matchState`（候选待核验/确定不同/已处理）的可观察子集；`处理中/已转人工复核/超期标记` 属 FR14/UC-17（B-06）。**是否把“处理项”升为独立分析类留 R5** |
| 模块细化 [`governance-dedup.md`](../../../srs/modules/governance-dedup.md) | FR09-S01～S12 的规则与验收多数与 SPEC CL/DD 一一对应；本 UC 逐条引用 | **需登记的文本差异：** `AC-FR09-S07-02`（“四项证据全部一致 → 自动合并”）与现行 DD-06/`AC-IMPL-FR09-02` 冲突；`AC-FR09-S07-03/04` 的候选截断与“无标识新建”仍有效。本 UC 以 V2.2 与 SPEC 为准，不改模块 SRS，登记为待评审（B-03/B-06；见 08 §6） |

## 6. 追踪文件与登记表（只提建议，不改文件）

| 文件 | 现状 | 建议（由父 Agent 决定） |
|---|---|---|
| [`03-AC-UC-分析模型追踪.md`](../03-AC-UC-分析模型追踪.md) | FR09 行的“分析模型”列只列 `governance-dedup / CLASS-01`；六条 `AC-IMPL-FR09-*` 的 UC 列为 UC-09；NF02 行 UC 列传 UC-01/02/15，NF07 行传 UC-18/19 | 为 FR09 六行与 UC-09 反向行增补 `SEQ-14 / VOPC-10`；NF02/NF07 是否增列 UC-09 由父 Agent 决定（本 UC 只提供计数口径与“不级联删除”的补充证据，不主张覆盖） |
| [`analysis-class-registry.md`](../automation/analysis-class-registry.md) | 已有 `SourceRecord`、`WorkEntity`、`WorkVersion`、`AuditEvidence` 行；`WorkEntity` 的“后续参与 UC”列已含 UC-09 | 增量登记 `DedupGovernanceUI`、`DedupCoordinator`（或并入 `GovernCtl` 并注明拆分）、`CleanRecord`、`DedupCandidate`；`WorkEntity` 行补“计数单位 canonical_id／已确认值／合并重定向”；`SourceRecord` 行补“到达事件与逻辑出处幂等、UC-09 只读原值”；`WorkVersion` 行补“明示证据来源（Version→SourceRecord）” |
| [`unresolved-decisions.md`](../automation/unresolved-decisions.md) | D-07“自动关联阈值/人工复核比例”已覆盖本 UC B-03；D-11/D-13 覆盖命名统一 | 可评估新增 B-01（类型细化必填项）、B-02（字段优先级）、B-04（候选年份窗与截断）、B-06（处理项状态机与待办边界）；本包不直接修改该文件 |
| 全局 `DGM-CLASS-01-analysis.puml` | `GovernCtl ..> Work/Authorship`；`Work`/`Source`/`Version` 无原值-规范值区分、无候选与规范记录、无 `Version→Source` | 按 §3–§4 在 R5 统一分析类时处理；本 UC 不修改全局图 |
| `DGM-ACTIVITY-06` / `DGM-ACTIVITY-08` | 保留“无标识自动合并”分支 | 与现行 AC/SPEC 冲突，建议 R5 修正；本 UC 不改动（§5） |

## 7. 差异结论

UC-09 分析支持以下全局模型调整建议：

1. 保留 `WorkEntity`（全局 `Work`）、`SourceRecord`（全局 `Source`）、`WorkVersion`（全局 `Version`）的既有分离与“明示证据”门槛；
2. 新增 `CleanRecord`（逐字段原值＋规范值＋规则/字典版本＋治理状态＋异常/冲突项）与 `DedupCandidate`（候选清单＋逐项证据＋规模＋匹配状态），否则“原值不被改写”“候选不自动合并”“已确认值不被覆盖”没有可验证的承载者；
3. 把治理写入口与协调职责从 `AdminUI`/`GovernCtl` 的混合职责中分离：成果实体治理（本 UC）≠ 学者身份/归属（UC-10）≠ 采集任务（UC-18/19）；
4. `WorkEntity 1:0..* SourceRecord`、`WorkEntity 1:0..* WorkVersion` 统一标多重性，并补 `WorkVersion 0..*:1 SourceRecord`（明示版本证据来源）；
5. 拆分 `Evidence` 为 `SourceRecord`（原值）/`CleanRecord`（规范记录）/`ReviewEvidence`（材料）/`AuditEvidence`（留痕），审计补 `actionKind`/`targetRefs`/`outcome`/`occurredAt`；
6. **修正 `DGM-ACTIVITY-06` 与 `DGM-ACTIVITY-08` 的“无稳定标识自动合并”分支**，使其与 `AC-IMPL-FR09-02`／DD-06／SPEC §5.3 一致；并同步登记模块细化 `AC-FR09-S07-02` 的冲突；
7. 类型细化必填项、字段优先级、相似度阈值与年份窗、候选截断长度、处理项状态机仍需需求确认（B-01～B-06；D-07）。

本轮**不直接修改** `DGM-CLASS-01-analysis.puml`、`DGM-ACTIVITY-06`、`DGM-ACTIVITY-08`、`DGM-STATE-04`、`governance-dedup.md` 与共享追踪/登记文件。
