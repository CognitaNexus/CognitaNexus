# UC-10 分析与现有全局模型差异

**版本：** V1.0（2026-10-21）
**比较对象：** [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml)、[`01-BCE分析类模型.md`](../01-BCE分析类模型.md)、[`identity-disambiguation-activity.puml`](../../../uml/governance/identity-disambiguation-activity.puml)（`DGM-ACTIVITY-07`）、[`relation-decision-sequence.puml`](../../../uml/governance/relation-decision-sequence.puml)（`DGM-SEQ-01`）、[`scholar-achievement-relation-state.puml`](../../../uml/governance/scholar-achievement-relation-state.puml)（`DGM-STATE-05`）、模块细化 [`scholar-disambiguation.md`](../../../srs/modules/scholar-disambiguation.md)、候选类登记表 [`analysis-class-registry.md`](../automation/analysis-class-registry.md)、待决清单 [`unresolved-decisions.md`](../automation/unresolved-decisions.md)、追踪表 [`03-AC-UC-分析模型追踪.md`](../03-AC-UC-分析模型追踪.md)。
**原则：** 全局图只作比对材料，不作为本 UC 识别类的反向答案；本轮不重构全局图、不修改共享登记表、不修改他人 UML 资产。

## 1. 已存在且一致

| UC-10 类/概念 | 全局对应 | 一致点 | 证据 |
|---|---|---|---|
| `IdentityVerificationCoordinator`（身份/归属部分） | `GovernCtl`（“+清洗/去重/候选”）；`01` §3 `GovernanceCoordinator`（“清洗/去重/身份/归属候选处理；无稳定键转待核验；不改写原证据”） | 都承认治理协调者负责“身份/归属候选”且“不改写原证据”“证据不足转待核验” | FR15-S04；BR-342；INV-01；M16/M29 |
| `Scholar` | 全局 `Scholar`（“+已核验身份()/+公开学者档案()”；`Scholar -- Authorship : 身份参与`）；`01` §4 “平台采集/核验的学者身份，可无账号；同名不合并；认领前仍可公开” | 档案与账号分离、同名不合并、经 `Authorship` 参与成果 | FR03；BR-342；`AC-IMPL-FR03-04`；BR-353 |
| `Authorship` | 全局 `Authorship`（“+角色/顺序/证据()/+独立关系状态()”；`Authorship -- Work`）；`01` §4 “管理员审核不等于本人确认；解除不删成果” | 角色/顺序/证据/独立状态；审核≠本人确认；解除不删成果 | §6.4；ID-06/ID-09；FR15-S11；M84/M88 |
| `WorkEntity` | 全局 `Work`（“+去重规范值()/+成果类型()/+生效状态()”） | 去重后的成果规范实体；来源数不等于成果数 | INV-02；BR-341 |
| `SourceRecord` | 全局 `Source`（“+原值/来源/许可()”；`Source -- Work : 来源证据`） | 原值、许可与逻辑出处分离；原值不改写 | INV-01；§7.2；M3 |
| `AuditEvidence`（概念） | 全局 `Evidence`（“+操作者/理由/前后值()”；`AuditCtl ..> Evidence`）；`Evidence ..> Claim/Authorship` | 受保护写入留痕；判定须可追溯 | BR-IMP-10；INV-10；BR-381/382 |
| “关系五状态与优先级” | `DGM-STATE-05` | 五状态、优先级 已排除>争议>待核验>本人确认>自动关联；`待核验→自动关联 [管理员核验通过]`；认领通过不改变状态 | §6.4；BR-356；FR15-S09；M22/M84 |
| “审核≠本人确认” | `01` §5 第 6 条；`DGM-STATE-05` 注释 | 管理员批准不写成本人确认 | SPEC ID-06/ID-10；`AC-IMPL-FR15-03/06`；M49/M79/M84 |
| “证据不足转候选/待核验” | `DGM-ACTIVITY-07`“生成身份候选（禁止仅凭姓名合并）” | 只出候选、不改公开归属结论 | FR15-S04；`AC-IMPL-FR15-01`；M26/M27/M29 |
| “授权不自动转移” | `01` §4 `ScholarAuthorization`“不等于本人确认；不自动转移”；`DGM-STATE-05` 注释 | 判定/纠正不改管理权 | BR-349；M78/M82/M83 |

## 2. 需要更名/别名统一

| 本 UC 名称 | 全局名称 | 差异 | R5 建议 |
|---|---|---|---|
| `IdentityVerificationUI` | `AdminUI`（`DGM-CLASS-01`，三条 `..>`：CollectCtl/GovernCtl/AuditCtl）；`01` §2 `AdminConsole`（UC-17/18/19） | 全局把治理写入口混在管理控制台，未区分“核验证据/判定/结果与影响范围”；`ScholarPortalUI` 只覆盖档案展示与维护 | R5 明确写入口归属：或在 `AdminConsole` 内显式列出“身份/归属核验”职责与两套守卫，或保留独立 `IdentityVerificationUI`（`UC-09 DedupGovernanceUI` 同理） |
| `IdentityVerificationCoordinator` | `GovernCtl`；`01` §3 `GovernanceCoordinator` | 全局把成果去重（FR09）与身份/归属判定（FR15）压在同一个协调者上 | R5 拆分：成果治理（UC-09 `DedupCoordinator`）≠ 身份/归属判定（本 UC）≠ 采集任务（UC-18/19）；避免“同名合并”与“成果合并”共用一条判定路径 |
| `IdentityCandidate` | 全局**无对应类**；只有 `GovernCtl` 的“候选”字样与 `DGM-ACTIVITY-07`“生成身份候选” | 全局无法表达候选成员（≥2 档案）、逐项证据命中/冲突、候选规模、候选状态与“关闭后不重复建议” | R5 新增 `IdentityCandidate` |
| `AttributionCandidate` | 全局**无对应类**；`AC-IMPL-FR15-01` 的“成果关系进入候选”无承载者 | 全局无法表达“归属未定”（署名＋候选学者＋证据），只能借 `Authorship` 状态，易被误读为关系已成立 | R5 新增 `AttributionCandidate`（或按 B-05 结论并入 `Authorship` 待核验＋候选属性） |
| `AuditEvidence` | 全局 `Evidence` | 命名差异（D-13 已登记） | R5 统一为 `AuditEvidence` 或 `Evidence` |
| `ReviewEvidence` | 全局 `Evidence`；`01` §4 描述为“审核/修正/授权的可追溯决定” | **语义差异：** 全局把“材料”和“留痕”混为一类；UC-05/06/08/10 用 `ReviewEvidence` 表示“材料＋可见范围”，`AuditEvidence` 表示“动作留痕” | R5 拆分并统一命名（D-13）；同时修正 `01` §4 的描述 |
| `WorkEntity` / `SourceRecord` | 全局 `Work` / `Source` | 命名差异（D-11 已登记） | R5 统一 |
| `Scholar` 的证据集合（属性） | 全局 `Scholar` 只有“+已核验身份()/+公开学者档案()” | 无法表达姓名表达/机构任职期/外部标识（命名空间、可信度）/合作者/领域/证据来源与到达时间 | R5 在 `Scholar` 职责中补“身份证据集合（追加式）”与合并链 |

## 3. 职责差异/需要补充

| 项目 | 本 UC 证据 | 全局现状 | 差异/影响 | R5 处理 |
|---|---|---|---|---|
| 同名不是同人证据（独立守卫） | M16；BR-342；ID-01/ID-03；`AC-IMPL-FR15-01/03` | `GovernCtl …> Authorship` 只写“清洗/去重/候选”，无“姓名不构成证据”的职责 | 若按全局图实现，姓名相似可能被当作匹配键 | 在协调者职责写明“姓名仅召回，不作为判定依据” |
| 姓名归一仅产生候选 | M7/M8；FR15-S02；ID-01/ID-04 | 全局无归一职责 | 归一结果可能被用来直接合并或替换展示名 | 在 `Scholar` 与协调者职责中补“比对用形式 + 别名候选” |
| 证据追加与来源时效 | M5；FR15-S01；BR-325 | 全局 `Scholar` 无证据集合 | 证据可被覆盖、无法回答“凭什么判定” | `Scholar` 补证据项（类型/来源/到达时间），只追加 |
| 私人联系方式剔除 | M6；FR15-S01 E1；BR-343/371；BR-IMP-11 | 全局无 | 私人手机/邮箱可能进入消歧证据 | 在协调者职责写明“不采集、登记剔除” |
| 保守判定门槛与规则批准状态 | M21；SPEC ID-02、§6.3、§6.5；B-03 | `DGM-ACTIVITY-07` 把条件（a)/(b) 直接连到“判定为同一人并合并档案” | 与“标识一致仅支持候选、规则未冻结转人工”存在张力（§5 C-01） | R5 修正活动图分支，或在图上注明“仅在已批准规则成立时执行” |
| 身份候选与归属候选分离 | M26/M27；`AC-IMPL-FR15-01`；B-05 | 全局无两个候选类 | “成果关系进入候选”无处承载 | 新增两个类（§2） |
| 管理员核验只写自动关联 | M49/M76/M79/M84；ID-06/ID-10；§6.4 | 全局 `Authorship` 注释已写“管理员审核不等于本人确认”，但无“核验后写哪个状态”的说明 | 可能被实现为“审核通过→本人确认” | 在协调者与 `Authorship` 职责中写明“人工核验→自动关联＋独立审核证据” |
| 已排除不被自动恢复 | M11–M13、M89；`AC-IMPL-FR15-04`；FR15-S09 E1 | `DGM-STATE-05` 有 `已排除` 终态注释；`DGM-SEQ-01` 没有该守卫 | 再次采集可能把已排除改回自动关联 | 在协调者职责中补“排除检查先于召回”，并在 `DGM-SEQ-01` 补守卫 |
| 合并证据并集与合并链 | M70/M71；BR-347；FR15-S03 E3 | 全局 `Scholar` 无合并链、无“同角色去重” | 合并丢别名/证据或产生重复关系 | `Scholar` 补 `mergedIntoRef`；协调者补“同成果同角色去重、保留双方证据” |
| 拆分后无法确定置待核验 | M93/M94；FR15-S10 A2；BR-358 | 全局无拆分职责 | 拆分可能擅自分配关系 | 协调者与 `Authorship` 职责补“无法确定→待核验” |
| 授权只读与冲突处理项 | M78/M82/M83；BR-349；A13 | 全局 `Auth` 存在，但 `GovernCtl` 无“判定不改授权”的说明 | 判定/合并可能顺带改管理权 | 协调者职责补“只读授权；冲突认领转处理项（UC-17）” |
| 影响范围与跨视图一致 | M44/M97–M99；NF05；FR15-S12 | `DGM-SEQ-01` 有“门户/检索/统计同步”段，但未含 FR12/IN01 派生结果与逐视图传播记录 | 统计或网络可能读到旧归属 | 在协调者职责与 `DGM-SEQ-01` 中补“派生结果同版本 + 传播记录” |
| 材料与留痕分离 | M48/M49；FR15-S08；ID-10；BR-IMP-11 | 全局 `Evidence` 一名多义（§2） | 材料可能被公开或被当作确认 | 拆分 `ReviewEvidence`/`AuditEvidence` |
| 不做成果去重/不改共享字段 | 无 `WorkVersion`/`DedupCandidate` 消息；M97 只声明；BR-341 | `GovernCtl ..> Work` 同时覆盖清洗去重 | 身份判定可能改成果实体或字段 | 明确两个协调者分界（§2） |
| 不做认领审核与本人确认 | 无 `ClaimApplication`/`Claim` 写消息；无写 `本人确认` 消息 | `GovernCtl` 与 `ClaimCtl` 职责邻近 | 入口混用会把审核做成认领或本人确认 | 明确 `ClaimCtl`（UC-05/06）与 UC-08 的边界 |

## 4. 关系差异

### 4.1 `GovernCtl ..> Authorship`（全局图）

- 全局把 `Authorship` 的写入挂在 `GovernCtl` 上；本 UC 的 `IdentityVerificationCoordinator` 确实写 `Authorship`（`自动关联/待核验/争议/已排除`），但**不写** `本人确认`，也不写成果共享字段。
- **R5 建议：** 保留该边但注明写入集合与禁止集合；把“本人确认”的写入唯一归属 UC-08。

### 4.2 `Scholar -- Authorship`（全局未标多重性）

- 本 UC 沿用 UC-08 的 `Authorship 0..* : 1 Scholar`（BR-353；ID-05/ID-11）。
- **R5 建议：** 统一标 `0..* : 1`，并注明“一条关系只归一位学者”。

### 4.3 `Work -- Source` 与 `Authorship -- Work`（全局未标多重性）

- 本 UC 沿用 `WorkEntity 1 : 0..* SourceRecord`、`Authorship 0..* : 1 WorkEntity`，并强调排除/拆分不删除成果与来源。
- **R5 建议：** 统一标多重性并在 `Source` 注记“不因关系排除/档案拆分删除”。

### 4.4 新增业务关联（全局无）

- `IdentityCandidate 0..* : 2..* Scholar`（候选成员）、`AttributionCandidate 0..* : 1..* Scholar`（候选学者）。
- **R5 建议：** 新增这两个类及两条关联；把候选的署名原值来源、确认后关系、授权引用写成属性（05 §2.3），避免与 `Authorship→Scholar` 重复表达。

### 4.5 审计对象引用表达

- 全局 `Evidence ..> Claim/Authorship` 用关联表达对象指向；本 UC 用 `AuditEvidence.targetRefs` 属性（审计需覆盖被拒绝、未写入的对象）。
- **R5 建议：** 与 UC-06/07/08/09/22 一致地统一为“属性引用”，或在全局图补“审计可指向多类对象”的注记。

## 5. 与现有活动图/状态图/模块细化的一致性（含必须登记的冲突）

| 全局资产 | 与本 UC 的一致点 | 需登记/需修正的差异 |
|---|---|---|
| [`DGM-ACTIVITY-07`](../../../uml/governance/identity-disambiguation-activity.puml)（学者消歧与归属判定） | 泳道划分（系统证据收集→系统身份判定→系统参与关系判定→管理员判定与纠正→系统生效传播）与本 UC E3–E18 对应；“剔除私人联系方式”“姓名表达归一”“禁止仅凭姓名合并”“争议保持原授权与原结论”与本 UC 一致 | **C-01 冲突：** 该图把“存在同类型外部标识一致→判定为同一人并合并档案”“机构一致且合作者与领域支持→合并档案”画成无条件分支；`SPEC ID-02` 规定“标识一致**仅支持候选**；生效须已批准规则或人工核验”，`SPEC §6.5` 规定“外部 ID 的充分性…不得做成默认自动确认；自动规则未冻结时转人工待核验”，`FR15-S04` 要求证据不足只出候选。**建议 R5 修正该分支为“条件成立且规则已批准才判同一人；否则生成候选”**；本 UC 按现行基线以候选为兜底（M21/A3/A4） |
| [`DGM-SEQ-01`](../../../uml/governance/relation-decision-sequence.puml)（归属判定与传播） | 触发（学者申请/证据变化）→判定→留痕→传播的门户/检索/统计三段与本 UC E11–E18 对应；“留痕失败不得表现为成功”“解除只终止一条关系”“授权不自动转移”与本 UC 一致 | 差异：①无“已排除不自动恢复”守卫（本 UC M11–M13/M89）；②未区分“管理员核验→自动关联”与“本人确认”（本 UC M79/M84）；③未含 FR12/IN01 派生结果与逐视图传播记录（本 UC M99）；④把“证据满足自动判定条件”当作可直接判定，未体现“规则未冻结转人工”（C-01 同源）。**建议 R5 补守卫与状态写入集合；本 UC 不修改该图** |
| [`DGM-STATE-05`](../../../uml/governance/scholar-achievement-relation-state.puml)（关系五状态） | 五状态、优先级、`待核验→自动关联 [管理员核验通过]`、`争议→…`、`已排除` 终态、认领通过不改变状态，与本 UC M22/M62/M84/M88 一致 | 本 UC 只使用其中四个写入方向（`自动关联/待核验/争议/已排除`），**不使用** `→本人确认`（属 UC-08）。差异仅为“本 UC 不覆盖该转移”，无需修改 |
| 模块细化 [`scholar-disambiguation.md`](../../../srs/modules/scholar-disambiguation.md) | FR15-S01～S13 的规则与本 UC M3–M96 逐条对应；“认领不等同本人确认”“解除不删成果”“合并证据并集”“拆分无法确定置待核验”均有对应消息 | **C-01 文本差异（同源）：** `FR15-S03` 主流程 2–3 允许满足（a)/(b) 即自动合并，而 `SPEC ID-02/§6.5` 要求“已批准规则或人工核验”。本 UC 以现行 69 AC 与 SPEC 为准（规则未冻结转候选），不改模块 SRS，登记待评审（B-03） |
| 全局 [`01-BCE分析类模型.md`](../01-BCE分析类模型.md) §4 `ReviewEvidence` | 都用 `ReviewEvidence` 表示审核侧证据 | **语义差异：** 该表写“审核/修正/授权的可追溯决定”，与 UC-05/06/08/10 的“材料＋可见范围”不一致；动作留痕已由 `AuditEvidence` 承担。**建议 R5 修正描述**（D-13） |

## 6. 追踪文件与登记表（只提建议，不改文件）

| 文件 | 现状 | 建议（由父 Agent 决定） |
|---|---|---|
| [`03-AC-UC-分析模型追踪.md`](../03-AC-UC-分析模型追踪.md) | FR15 六行的“分析模型”列只列 `governance-relation / SEQ-02 / CLASS-01`；UC-10 反向行只列 `AC-IMPL-FR15-01`～`06`；NF03 行 UC 列为 UC-04/05/06/07/08/17；NF05 行 UC 列为 UC-08/14/15 | 为 FR15 六行与 UC-10 反向行增补 `SEQ-15 / VOPC-11`。NF03/NF05 是否增列 UC-10 由父 Agent 决定（本 UC 只提供“拒绝/越权/授权只读/传播声明”的补充证据，不主张覆盖 UC-04/06/08/14） |
| [`analysis-class-registry.md`](../automation/analysis-class-registry.md) | 已有 `Scholar`、`Authorship`、`WorkEntity`、`SourceRecord`、`ReviewEvidence`、`AuditEvidence`、`ScholarAuthorization` 行；无两个候选类；无身份核验 UI/Coordinator | 增量登记 `IdentityVerificationUI`、`IdentityVerificationCoordinator`、`IdentityCandidate`、`AttributionCandidate`；`Scholar` 行补“身份证据集合（追加式）／合并链／`identityState`”；`Authorship` 行补“UC-10 不写本人确认”；`ReviewEvidence` 行补“材料＋可见范围，不构成本人确认” |
| [`unresolved-decisions.md`](../automation/unresolved-decisions.md) | D-07“自动关联阈值/人工复核比例”覆盖本 UC B-03；D-11/D-12/D-13 覆盖命名与授权关系 | 可评估新增 B-01（候选/争议状态集合与结案载体）、B-02（时限与责任人）、B-04（候选规模参数）、B-05（归属候选是否独立成类）、B-08（与 UC-08 的入口分工）；本包不直接修改该文件 |
| 全局 `DGM-CLASS-01-analysis.puml` | `GovernCtl ..> Work/Authorship`；无候选类；`Scholar` 无证据集合；`Evidence` 一名多义 | 按 §2–§4 在 R5 统一分析类时处理；本 UC 不修改全局图 |
| `DGM-ACTIVITY-07` / `DGM-SEQ-01` | 条件（a)/(b) 直接连到合并；无已排除守卫、无“核验→自动关联”、无派生结果传播 | 建议 R5 修正（§5）；本 UC 不改动这两张图 |

## 7. 差异结论

UC-10 分析支持以下全局模型调整建议：

1. 保留 `Scholar`（档案，含追加式身份证据集合与合并链）、`Authorship`（角色/顺序/独立状态）、`WorkEntity`、`SourceRecord` 的既有分离；
2. 新增 `IdentityCandidate`（档案组合待核验）与 `AttributionCandidate`（署名归属待核验）——否则“同名不合并、关系进入候选、候选关闭后不重复建议”没有可验证的承载者；
3. 把“身份/归属判定”从 `GovernCtl`/`GovernanceCoordinator` 的混合职责中分离：成果治理（UC-09）≠ 身份与归属判定（本 UC）≠ 采集任务（UC-18/19）；
4. 明确 `Authorship` 状态写入集合：本 UC 只写 `自动关联/待核验/争议/已排除`，`本人确认` 仅属 UC-08；管理员核验只形成 `自动关联`＋独立审核证据；
5. 拆分 `Evidence` 为 `ReviewEvidence`（材料＋可见范围）与 `AuditEvidence`（动作留痕），并修正 `01-BCE` §4 对 `ReviewEvidence` 的描述；
6. **修正 `DGM-ACTIVITY-07` 与 `DGM-SEQ-01` 的“条件成立即合并/判定”分支**，使其与 `SPEC ID-02`／§6.5／`FR15-S04` 一致（规则未冻结时转候选或人工核验），并补“已排除不自动恢复”守卫；
7. 阈值、候选规模、时限、候选状态集合、授权转移条件与归属候选的表达方式仍需需求确认（B-01～B-08；D-07；SPEC ID-12）。

本轮**不直接修改** `DGM-CLASS-01-analysis.puml`、`DGM-ACTIVITY-07`、`DGM-SEQ-01`、`DGM-STATE-05`、`scholar-disambiguation.md`、`01-BCE分析类模型.md` 与共享追踪/登记文件。
