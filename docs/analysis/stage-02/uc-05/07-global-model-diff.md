# UC-05 分析与现有全局分析模型差异

**版本：** V1.0（2026-10-21）
**比较对象：**
- 全局分析类图 [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml) 与 [`01-BCE分析类模型.md`](../01-BCE分析类模型.md)；
- 合并认领序列 [`DGM-SEQ-02-claim.puml`](../../../uml/analysis/DGM-SEQ-02-claim.puml)、认领活动图 [`DGM-ACTIVITY-01-claim-review.puml`](../../../uml/claim/DGM-ACTIVITY-01-claim-review.puml)、申请状态图 [`DGM-STATE-02-claim-request.puml`](../../../uml/claim/DGM-STATE-02-claim-request.puml)；
- 核心 SPEC [`CognitaNexus-Core-SPEC-V1.0.md`](../../../spec/CognitaNexus-Core-SPEC-V1.0.md) §3、§6（ID-07/ID-12）；
- 候选类登记表 [`analysis-class-registry.md`](../automation/analysis-class-registry.md)、待决清单 [`unresolved-decisions.md`](../automation/unresolved-decisions.md)。

**原则：** 全局图只作比对材料，不作为本 UC 识别类的反向答案；本轮不重构全局图、不修改共享追踪/登记文件。

## 1. 已存在且一致

| UC-05 类/概念 | 全局对应 | 一致点 | 证据 |
|---|---|---|---|
| `ClaimUI` | `ClaimUI`（`DGM-CLASS-01` 第 15 行；`01` §2“提交/查询/审核认领”） | 都承接认领的提交与查询交互；本 UC 只用提交/查询侧，审核侧展示属 UC-06 | FR05；UC-05 |
| `ClaimCoordinator` | `ClaimCtl`（第 29 行“证据/授权/争议”）、`01` §3 `ClaimReviewCoordinator` | 都负责证据校验、状态流转与“授权失败不虚报、原授权不自动转移、不建第二主页” | FR05-01～04；BR-IMP-05 |
| `User` | `User`（第 49 行，账号/对象授权主体） | 都是账号与对象授权主体，1:1 UserProfile，不因注册生成 Authorship | FR04；SRS §12 |
| `Scholar` | `Scholar`（第 58 行，平台采集/核验的学者身份） | 都是认领目标，可无账号，认领前仍公开 | FR05；SRS §12 |
| `ClaimApplication` | 全局 `Claim`（第 62 行“证据材料/申请状态”） | 都是“申请+状态”的独立实体，不是 Authorship 状态 | FR05；SPEC §3 |
| `ScholarAuthorization` | 全局 `Auth`（第 66 行“目标Scholar/授权范围”） | 都是审核通过后的对象级管理权；不等于本人确认 | AC-IMPL-FR05-02/03；SPEC ID-07 |
| 竞争认领保留原授权 | `01` §3 守卫“原授权不自动转移”；`01` §5.6“审计与授权分离” | 一致：提交侧不写授权，竞争只记录事实 | AC-IMPL-FR05-03；BR-IMP-05 |
| 认领不确认成果归属 | 全局 `Scholar -- Authorship` 与 `Authorship` 独立状态；`01` §5.6 | 一致：本 UC 无 Authorship 消息 | FR05；AC-IMPL-FR15-06；SPEC ID-07 |
| 不建第二主页 | 全局 `User "1" -- "1" UserProfile`；`01` §5.1 | 一致：认领不新建/不复制主页 | AC-IMPL-FR05-04；SRS §12 |
| 申请状态集 | SPEC §3“门户认领申请 `待审核/待补充/已通过/已驳回/争议`”并列于治理/匹配状态之外 | 一致：本 UC 只产生待审核（及 A1b 待补充），其余只读 | SPEC §3；`DGM-STATE-02` |

## 2. 命名/别名差异

| 本 UC 名称 | 全局/既有名称 | 差异 | R5 建议 |
|---|---|---|---|
| `ClaimApplication` | 全局 `Claim`；`01` §4 `ClaimApplication` | 图与文档两名并存 | 统一为 `ClaimApplication`（可读、明确是申请而非法学意义的权利主张）；`Claim` 作为别名 |
| `ScholarAuthorization` | 全局 `Auth`；`01` §4 `ScholarAuthorization`；SRS §12“User—ScholarAuth/Claim 授权记录” | 缩写 vs 全名 | 统一为 `ScholarAuthorization`；避免 `Auth` 与“认证/登录”混淆 |
| `ClaimCoordinator` | 全局 `ClaimCtl`；`01` §3 `ClaimReviewCoordinator` | 三名一概念 | R5 决定：单一 `ClaimCoordinator` 承担提交/查询/审核，或拆为 `ClaimCoordinator`（提交/查询，本 UC）与 `ClaimReviewCoordinator`（审核决定/授权，UC-06）；无论哪种，两侧职责必须显式写出（B-09） |
| `ReviewEvidence` | `01` §4 `ReviewEvidence`（“审核/修正/授权的可追溯决定”）；全局只有 `Evidence`；UC-22 用 `AuditEvidence` | 同一词被用于“认领材料/审核决定记录”与“受保护写入留痕” | 已登记 D-13；R5 需拆分命名：认领材料与审核决定记录 → `ReviewEvidence`，受保护写入留痕 → `AuditEvidence`；本 UC 只用 `ReviewEvidence` 表示认领材料 |
| `ClaimUI` | `ClaimUI` | 名称一致；职责范围不同（本 UC 无审核展示） | 保留；R5 可在职责说明中标注“提交/查询侧（UC-05）与审核侧（UC-06）” |

## 3. 职责差异/需要补充

| 项目 | 本 UC 证据 | 全局现状 | 差异/影响 | R5 处理 |
|---|---|---|---|---|
| 提交/查询侧职责边界 | 本 UC 只有 E1–E11；审核决定属 UC-06 | `ClaimCtl` 的“证据/授权/争议”把提交与审核合成一个控制类，未区分谁产生 `通过/驳回/争议` | 若按全局图实现，提交路径可能被误做成“可自行置为通过/争议” | 在职责中区分“提交侧只产生待审核/待补充”“审核侧产生通过/驳回/争议” |
| 缺证两态处置 | AC-IMPL-FR05-01 允许拒绝或待补充 | 全局 `Claim` 只有“申请状态”，没有缺证处置口径 | 实现者可能自选一种或干脆放行 | 需求先定策略（B-01）；模型保留两条分支 |
| 服务端身份/账号校验 | M3/M27；NF03；SRS §8 | 全局 `ClaimCtl` 没有到 `User` 的依赖（只有 `User -- Claim : 提交`） | “请求者是否为可用账号/本人”没有模型支撑 | `ClaimCtl` 补“校验请求者身份与账号状态”，评估补 `ClaimCtl → User` |
| 目标必须是已收录 Scholar | M4；A3 | 全局只有 `Claim -- Scholar`，未写“只接受既有 Scholar、不新建学者” | 认领可能被实现成“认领即创建学者档案” | 在 `ClaimCtl` 职责与 `ClaimUI` 提示中写明目标对象类型守卫 |
| 只读读取现有授权 | M17/M18；AC-IMPL-FR05-03 | 全局只有 `ClaimCtl ..> Auth` 与 `Claim ..> Auth : 批准后授予`，没有“提交侧读取授权识别竞争”的表达 | 竞争认领可能被实现成“直接改写/覆盖授权” | 明确 `ClaimCtl → Auth` 在提交侧是只读；写入只属 UC-06 |
| 证据私有可见性 | M14/M20/M29/M35；BR-IMP-11；NF06 | 全局 `Claim +证据材料()` 未写可见范围；`Evidence` 只表达留痕 | 证据可能被当作档案资料公开，或在查询中回显给他人 | 在 `Claim`/`ReviewEvidence` 职责中加入可见范围与最小化 |
| 查询归属过滤 | M27/M28；AC-IMPL-NF03-01；SRS §8 | 全局 `ClaimUI` 写“查询”，未写“仅本人” | 可能实现成任意用户可按申请号读取 | 在 `ClaimUI`/`ClaimCtl` 职责中写明归属过滤与拒绝 |
| 重复提交/撤回/申诉 | 未在 FR05/AC 定义 | 全局无表达 | 实现口径不一致 | 先需求确认（B-03）；不在模型中虚构 |

## 4. 关系差异

### 4.1 `User -- Claim : 提交`（全局第 117 行）

- 本 UC 需要的是“申请 → 申请人”的**明确角色关联**（`0..*:1`），并在查询时用于归属过滤。
- 一致方向：User 是申请人；差异仅为多重性与角色标注，本 UC 依 FR05/SRS §8 给出 `0..*:1`。
- R5 建议：全局关系改为 `User "1" -- "0..*" ClaimApplication : 提交/申请人`，明确一侧多个申请。

### 4.2 `Claim -- Scholar : 认领目标`（全局第 118 行）

- 一致：申请指向既有 Scholar。
- 本 UC 补 `0..*:1`：一次申请只认领一个 Scholar，同一 Scholar 可有多个（含竞争）申请（FR05；AC-IMPL-FR05-03）。
- R5 建议：全局补多重性与角色名“认领目标”。

### 4.3 `Claim ..> Auth : 批准后授予`（全局第 119 行）与 `ClaimCtl ..> Auth`（第 109 行）

- 本 UC **没有**授权创建消息：`批准后授予` 属 UC-06；本 UC 只有 `ClaimCoordinator ⇢ ScholarAuthorization`（只读，M17/M18）。
- 风险：若把 `Claim ..> Auth` 读作“提交申请即产生授权”，则违反 FR05“无冲突审核通过后授予”与 AC-IMPL-FR05-02/03。
- R5 建议：把该依赖的触发条件写成“审核批准且无冲突（UC-06）”，或从 UC-05 相关视图移出；`Auth` 在提交侧的语义限定为只读。

### 4.4 `Auth -- Scholar : 目标档案`（全局第 120 行）

- 一致：授权指向目标 Scholar；本 UC 以目标 Scholar 为读取键（M17）。
- 差异：有效授权数量与转移条件未定义（SPEC ID-12），本 UC 不标多重性。

### 4.5 `Evidence ..> Claim : 决定留痕`（全局第 126 行）

- 全局用 `Evidence` 同时承担“审核决定留痕”和“证据材料”，本 UC 的 `ReviewEvidence` 只承担提交材料与可见范围，决定留痕属 UC-06（`DGM-SEQ-02` 的 `Ctl -> Evidence : 留痕操作者/理由/前后值/时间`）。
- D-13 已登记该命名冲突；R5 需拆分。

### 4.6 `User — Scholar`、`UserProfile — Scholar`、`Claim — Authorship`

- 本 UC **不建立**任何直接关系：认领只通过 `ClaimApplication` 与（UC-06 的）`ScholarAuthorization` 关联；不合并主页与学者档案，也不确认成果归属（AC-IMPL-FR05-04；FR05；SPEC ID-07）。

## 5. 与合并序列、活动图、状态图的差异

| 对象 | 既有内容 | 本 UC 处理 | 说明 |
|---|---|---|---|
| `DGM-SEQ-02-claim`（合并序列） | 一个序列同时画申请人提交与管理员审核，含 `Ctl -> Auth : 授予目标Scholar对象管理权`、`Ctl -> Profile : 仅展示批准关联，不创建第二主页`、`Ctl -> Evidence : 留痕` | 本 UC 只取提交段与查询段；审核段、`Profile` 消息、`Evidence` 留痕不入图 | UC-05/UC-06 分属两个用例，合并序列不能作为本 UC VOPC 来源；`Profile` 消息属 UC-06 结果展示，认领本身无 UserProfile 消息 |
| `DGM-ACTIVITY-01-claim-review` | 提交有效→创建待审核；无效→反馈错误；管理员核验→补证/争议/批准驳回 | 本 UC 对应“创建待审核 / 反馈错误”两支，并把 AC 允许的“标待补充”补为 A1b | 活动图的“需要补证”由管理员触发，属 UC-06 的 `待审核→待补充`；本 UC 的 A1b 是提交侧直接标待补充，需 B-01 确认 |
| `DGM-STATE-02-claim-request` | `[*]→待审核`（本人提交有效）、`Pending→Supplement`（审核侧要求补证）、`Supplement→Pending`（本人重提校验通过）、`→Approved/Rejected/Dispute` | 本 UC 只用 `[*]→待审核` 与 A1b 的 `[*]→待补充`；其余迁移归 UC-06 | 状态图未画“提交时缺证直接进入待补充”的边，说明 B-01 需要在需求/模型层统一；本 UC 不擅自改状态图 |
| 核心 SPEC §3 | 认领申请状态与治理/匹配/Authorship 状态分离 | 一致：本 UC 不混用 Authorship 关系状态 | SPEC §3；FR15 |
| 状态名口径 | FR05 写 `待审核/待补充/通过/驳回/争议`；SPEC §3 与 `DGM-STATE-02` 写 `待审核/待补充/已通过/已驳回/争议` | 本 UC 以 FR05 为需求表述，图与类定义统一用 `通过/驳回` 并在 05/06 注明同义 | 名称不一致属 R5 枚举统一事项（B-11），不影响状态语义 |
| 核心 SPEC ID-07/ID-12 | 认领通过只授予门户管理权、不使成果关系自动本人确认；原管理人权限不得因冲突申请自动转移，转移与申诉待评审 | 一致：本 UC 不创建授权、不确认 Authorship；竞争只记录事实 | AC-IMPL-FR05-03；AC-IMPL-FR15-06 |

## 6. 候选类登记表缺口（不修改登记表）

`analysis-class-registry.md` 现有行包含 `User`（后续参与 UC-05/06/22）与 `Scholar`（后续参与 UC-03/05/07/10/12/14/15），但没有申请、证据、授权三类行。本 UC 新增候选如下，建议由父 Agent 登记到 R5 输入（本 UC 未改动该文件）：

| 正式名称 | BCE | 首次发现 UC | 后续参与 UC | 主要职责 | 必要业务属性 | 已有关系 | 未定多重性 | 别名/冲突 | R5 建议 |
|---|---|---|---|---|---|---|---|---|---|
| ClaimApplication | Entity | UC-05 | UC-06 | 认领申请、状态与理由、竞争事实、本人可读投影 | applicationRef/applicantRef/targetScholarRef/evidenceRefs/status/statusReason/competitionFact/submittedAt | →User、→Scholar、→ReviewEvidence | →ReviewEvidence 未定 | 全局 `Claim` | 统一为 ClaimApplication |
| ReviewEvidence | Entity | 当前登记指向 UC-22/17 的 `AuditEvidence`；认领材料首次在 UC-05 明确 | UC-06/17 | 私有证明材料、审核决定记录、可见范围 | evidenceKind/materialRef/submittedByRef/attachedApplicationRef/visibilityScope | 被 ClaimApplication 引用 | 未定 | `Evidence`/`AuditEvidence`（D-13） | 拆分命名：材料/决定 vs 写入留痕 |
| ScholarAuthorization | Entity | UC-05（只读） | UC-06/07 | 目标对象级管理权事实；提交侧只读 | targetScholarRef/granteeRef/scope/effectiveState | →Scholar；User—Auth 属 UC-06 | 未定 | 全局 `Auth` | 统一为 ScholarAuthorization |
| ClaimCoordinator | Control | UC-05（提交/查询侧） | UC-06 | 身份/账号校验、目标守卫、证据校验、申请与证据登记、归属查询 | 单次请求分析状态 | →UI、→User、→Scholar、⇢Auth、→ClaimApplication、→ReviewEvidence | 不定义 | `ClaimCtl`/`ClaimReviewCoordinator`（B-09） | R5 决定是否拆分提交侧与审核侧 |

## 7. 差异结论

建议 R5 统一分析类时：

1. 统一 `ClaimApplication`/`Claim`、`ScholarAuthorization`/`Auth` 的正式名与别名；
2. 明确 `ClaimCtl`/`ClaimCoordinator`/`ClaimReviewCoordinator` 是否拆分提交侧与审核侧（B-09）；
3. 拆分 `ReviewEvidence`（材料/决定记录）与 `AuditEvidence`（受保护写入留痕），落实 D-13；
4. 在 `ClaimCtl` 职责中补：服务端身份/账号校验、目标必须为已收录 Scholar、缺证两态处置、竞争只读授权、证据私有、查询归属过滤；
5. 把 `Claim ..> Auth : 批准后授予` 的触发条件明确为“审核批准且无冲突（UC-06）”，提交侧只读；
6. 明确 `ClaimApplication→User`、`ClaimApplication→Scholar` 的多重性与角色名，其余关系不擅自填多重性；
7. 需求层先解决 B-01/B-02/B-03/B-04，再决定状态图是否需要“提交时缺证→待补充”的边。

本轮**不直接修改** `DGM-CLASS-01-analysis.puml`、`DGM-SEQ-02-claim.puml`、`DGM-STATE-02-claim-request.puml`、`DGM-ACTIVITY-01-claim-review.puml`、`01-BCE分析类模型.md` 或共享登记文件。
