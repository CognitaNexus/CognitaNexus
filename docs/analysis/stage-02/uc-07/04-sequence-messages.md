# UC-07 分析序列与逐消息职责

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-SEQ-12-scholar-maintenance.puml`](diagrams/DGM-SEQ-12-scholar-maintenance.puml)
**导出：** [SVG](diagrams/generated/DGM-SEQ-12-scholar-maintenance.svg) · [PNG](diagrams/generated/DGM-SEQ-12-scholar-maintenance.png)
**方法：** 教材 §5.4.3；只表达分析对象协作，不写 Java、数据库、HTTP/API 或消息中间件。

## 1. 顺序图范围

本序列只实现 UC-07 的“维护已授权 Scholar 档案”：主流程 E1–E10，以及 A1（未认证/停用）、A2（无有效对象授权）、A3（超范围/stale scope）、A4（含普通 UserProfile 字段）、A5（可直接生效）、A6（写入/生效失败）、A7（待审版本冲突）、A8（审核无权限/理由缺失）分支；A9（审计留痕失败）与 A10（待审期间外部读取）为分析级约束，无独立消息。

参与对象（共 7 个）：

- `ScholarMaintenanceUI`（Boundary）
- `ScholarMaintenanceCoordinator`（Control）
- `User`（Entity）
- `Scholar`（Entity）
- `ScholarAuthorization`（Entity）
- `ScholarChangeRequest`（Entity）
- `AuditEvidence`（Entity）

两位参与者（已获授权 Scholar 管理者、有权审核的管理员）只在图外作为 Actor，不画成系统类。

## 2. 逐消息职责表

| 消息 | 名称 | 发送者 | 接收者 | 事件步骤 | 接收者职责 | 依据/假设 |
|---|---|---|---|---|---|---|
| M1 | 打开被授权 Scholar 档案维护入口并提交档案事实变更 | 已获授权管理者 | ScholarMaintenanceUI | E1 | 接收维护入口请求与变更内容 | FR06；`DGM-USECASE-07`；字段清单待确认（B-02） |
| M2 | 请求维护档案变更（目标 Scholar 引用＋变更字段＋已认证请求上下文） | ScholarMaintenanceUI | ScholarMaintenanceCoordinator | E1→E2 | 启动维护协调，转交请求内容与请求者上下文 | FR06；SRS §8；分析推导 |
| M3 | 校验请求者身份与账号状态 | Coordinator | User | E2 | 提供账号标识与账号状态，供判定“是否可用账号发起受保护写入” | NF03；`AC-IMPL-NF03-01`；SRS §8 |
| M4 | 读取请求者对该目标 Scholar 的有效对象级授权与授权范围 | Coordinator | ScholarAuthorization | E3 | 回答“是否持有有效管理授权、范围到哪里”，只读 | FR05；`AC-IMPL-FR05-02`；BR-IMP-05；SPEC ID-07 |
| M5 | 确认目标档案正是授权所指 Scholar，读取当前公开事实与生效版本 | Coordinator | Scholar | E3 | 提供档案身份引用、当前公开字段与生效版本，供一致性判定与后续生效比较 | FR06；§6.4；BR-IMP-10 |
| M6 | 判定 账号可用 ∧ 授权有效 ∧ 目标一致 | Coordinator | Coordinator | E3 | 三条守卫的合取；不满足转 A1/A2，满足转 M13 | `AC-IMPL-FR06-02`；BR-IMP-05；SRS §8 |
| M7 | 拒绝，无任何写入 | Coordinator | ScholarMaintenanceUI | A1 | 返回拒绝结论，不返回成功或待审视图 | NF03；`AC-IMPL-NF03-01`；FR04-02 |
| M8 | 显示拒绝（与越权/失败区分） | ScholarMaintenanceUI | 已获授权管理者 | A1 | 显示“未认证/账号不可用”，不复用越权或失败文案 | 分析推导；NF01；具体文案未冻结 |
| M9 | 记录受控拒绝审计（不含凭据） | Coordinator | AuditEvidence | A1 | 记录被拒绝的受保护写入尝试 | BR-IMP-10；INV-10；NF03 |
| M10 | 拒绝越权维护，目标档案无变更 | Coordinator | ScholarMaintenanceUI | A2 | 返回越权结论并声明目标档案未变化 | `AC-IMPL-FR06-02`；BR-IMP-05；SRS §8 |
| M11 | 显示越权结果 | ScholarMaintenanceUI | 已获授权管理者 | A2 | 显示越权结果而非保存成功 | `AC-IMPL-FR06-02`；SRS §8（不以入口隐藏代替拒绝） |
| M12 | 记录受控越权尝试审计 | Coordinator | AuditEvidence | A2 | 记录请求者/目标对象/结果/时间 | `AC-IMPL-FR06-02`；BR-IMP-10；INV-10 |
| M13 | 判定字段范围：授权范围 ∧ Scholar 学术事实 ∧ 排除普通 UserProfile 字段 | Coordinator | Coordinator | E4 | 区分可维护的 Scholar 事实、越界字段与普通主页字段 | FR06；SRS §12；`AC-IMPL-FR06-02`；B-02/B-05 |
| M14 | 拒绝并说明授权范围（stale scope），无写入 | Coordinator | ScholarMaintenanceUI | A3 | 返回“超出授权范围”结论，目标档案无变化 | `AC-IMPL-FR05-02`；`AC-IMPL-FR06-02`；BR-IMP-05 |
| M15 | 显示“超出授权范围” | ScholarMaintenanceUI | 已获授权管理者 | A3 | 显示范围不足与可用范围，不显示为成功 | `AC-IMPL-FR06-02`；分析推导 |
| M16 | 记录受控范围外尝试审计 | Coordinator | AuditEvidence | A3 | 记录越界尝试与判定依据 | BR-IMP-10；`AC-IMPL-FR06-02`；INV-10 |
| M17 | 本 UC 不写普通主页字段；转 UC-22 | Coordinator | ScholarMaintenanceUI | A4 | 声明普通 `UserProfile` 字段不在本 UC 写入范围 | FR06；SRS §12；BR-IMP-05 |
| M18 | 说明普通资料维护走 UC-22 | ScholarMaintenanceUI | 已获授权管理者 | A4 | 告知普通资料须由本人主页编辑处理 | FR06；UC-22；混合提交处置待确认（B-06） |
| M19 | 判定变更性质：需审核的重要变更 / 可直接生效的公开字段 | Coordinator | Coordinator | E5 | 依据审核规则分类本次变更，并记录判定结论 | FR06“按Scholar对象授权和审核规则办理”；`AC-IMPL-FR06-01`；B-01 待确认 |
| M20 | 登记待审变更（旧值/新值/提交者/理由/证据引用/版本）；公开版保持旧值 | Coordinator | ScholarChangeRequest | E6 | 建立待审变更并保持公开版为旧值 | FR06“待审版本不覆盖公开版”；`AC-IMPL-FR06-01`；§6.4；BR-IMP-10 |
| M21 | 记录待审登记审计（前后值标记为未生效） | Coordinator | AuditEvidence | E6/E7 | 为待审登记留痕，明确“未生效” | BR-IMP-10；`AC-IMPL-FR06-02`；INV-10 |
| M22 | 已提交待审：公开仍为旧值，本人可查待审状态 | Coordinator | ScholarMaintenanceUI | E7 | 返回待审结论与“公开未变更”事实 | `AC-IMPL-FR06-01`；BR-IMP-10 |
| M23 | 显示待审状态与“公开未变更” | ScholarMaintenanceUI | 已获授权管理者 | E7 | 显示待审而非“已公开” | `AC-IMPL-FR06-01`；分析推导 |
| M24 | 写入该变更范围内的公开字段并生成生效版本 | Coordinator | Scholar | A5（直接生效分支） | 只更新该变更范围字段并生成生效版本，不覆盖原始来源事实 | FR06；§6.4；§7.2；BR-IMP-04；B-01/B-02 待确认 |
| M25 | 失败：公开保持旧值 | Coordinator | ScholarMaintenanceUI | A6 | 返回失败结论，声明公开版未变 | `AC-IMPL-FR06-01`；BR-IMP-10；分析推导 |
| M26 | 显示失败与重试，不显示为已生效 | ScholarMaintenanceUI | 已获授权管理者 | A6 | 失败与成功、拒绝三态可区分 | 分析推导；NF01 |
| M27 | 记录生效写入审计（身份/对象/前后值/时间/理由） | Coordinator | AuditEvidence | A5 | 为生效写入留可追溯留痕，不含秘密 | BR-IMP-10；INV-10；NF03 |
| M28 | 已生效：公开按生效版本读取 | Coordinator | ScholarMaintenanceUI | A5 | 返回生效结论与生效版本事实 | FR06；NF05；BR-IMP-10 |
| M29 | 显示生效结果 | ScholarMaintenanceUI | 已获授权管理者 | A5 | 显示已生效（不含待审状态） | FR06；分析推导 |
| M30 | 查询本人提交的待审变更状态 | 已获授权管理者 | ScholarMaintenanceUI | E7（事后查询） | 接收待审状态查询请求 | `AC-IMPL-FR06-01`“申请人可查待审状态” |
| M31 | 请求待审状态（目标档案＋提交者上下文） | ScholarMaintenanceUI | Coordinator | E7（事后查询） | 转交查询上下文，按可见范围裁决 | `AC-IMPL-FR06-01`；NF06；BR-IMP-11 |
| M32 | 读取该提交者的待审变更状态与版本（仅本人或有权审核者） | Coordinator | ScholarChangeRequest | E7（事后查询） | 按可见范围提供本人待审状态与版本 | `AC-IMPL-FR06-01`；BR-IMP-11；NF06 |
| M33 | 待审状态=待审核；公开版仍为旧值 | ScholarChangeRequest | Coordinator | E7（事后查询） | 返回状态与“公开未生效”事实 | `AC-IMPL-FR06-01`；§6.4 |
| M34 | 返回待审状态与公开未变更事实 | Coordinator | ScholarMaintenanceUI | E7（事后查询） | 组装只含本人可见范围的响应 | `AC-IMPL-FR06-01`；BR-IMP-11 |
| M35 | 显示待审状态（不显示新值为已公开） | ScholarMaintenanceUI | 已获授权管理者 | E7（事后查询） | 显示待审状态，不混淆为已生效 | `AC-IMPL-FR06-01`；分析推导 |
| M36 | 打开待审变更并提交决定（批准/驳回）与理由 | 有权审核的管理员 | ScholarMaintenanceUI | E8 | 接收待审变更的复核与决定输入 | FR06 参与者（审核管理员）；§11.4；`AC-IMPL-FR06-01` |
| M37 | 提交审核决定（待审变更引用＋理由＋已认证请求上下文） | ScholarMaintenanceUI | Coordinator | E8 | 转交决定与理由，启动审核守卫 | FR14（边界）；`AC-IMPL-FR06-01`；B-10 |
| M38 | 校验审核者身份、账号状态与审核权限 | Coordinator | User | E8 | 提供账号状态与审核权限事实 | NF03；`AC-IMPL-NF03-01`；SRS §8；权限划分待确认（沿用 UC-06 C-01） |
| M39 | 复核守卫：权限有效 ∧ 理由完整 ∧ 待审变更仍为待审核且版本未变 | Coordinator | Coordinator | E8 | 三条守卫的合取；不满足转 A8/A7 | FR14“记录…原因”；§6.4；分析推导；B-09 |
| M40 | 拒绝该决定：待审状态不变，公开版不变 | Coordinator | ScholarMaintenanceUI | A8 | 返回拒绝结论，不改变状态 | NF03；`AC-IMPL-NF03-01`；FR14（边界） |
| M41 | 显示拒绝原因（无权限/理由必填） | ScholarMaintenanceUI | 有权审核的管理员 | A8 | 显示与“状态已变化”可区分的拒绝原因 | 分析推导；NF01 |
| M42 | 拒绝并返回当前状态，不覆盖已有决定 | Coordinator | ScholarMaintenanceUI | A7 | 返回已变化后的当前状态 | §6.4；BR-IMP-04/10；分析推导；B-09 |
| M43 | 显示“状态已变化”与当前状态 | ScholarMaintenanceUI | 有权审核的管理员 | A7 | 显示当前状态，不复用无权限文案 | 分析推导；`DGM-STATE-02` 过期守卫类比 |
| M44 | 记录决定：状态=通过（理由/时间/操作者） | Coordinator | ScholarChangeRequest | E9（批准） | 置为通过并记录决定事实 | `AC-IMPL-FR06-01`；FR14（边界）；BR-IMP-10 |
| M45 | 以该待审变更生成生效版本并更新公开字段（仅变更范围内字段） | Coordinator | Scholar | E9（批准） | 只把该待审变更范围内的字段写入生效版本；不覆盖原始来源事实 | `AC-IMPL-FR06-01`；§6.4；§7.2；BR-IMP-04；NF05 |
| M46 | 记录批准与生效（对象/前后值/时间/理由） | Coordinator | AuditEvidence | E10 | 为批准与生效留痕；无此留痕不得报告生效 | BR-IMP-10；INV-10；A9 |
| M47 | 已批准并生效：检索/门户/统计使用同一生效版本 | Coordinator | ScholarMaintenanceUI | E10 | 返回生效结论与跨视图一致口径 | `AC-IMPL-FR06-01`；NF05；BR-IMP-10；传播时限待确认（B-03） |
| M48 | 显示生效结果与传播说明 | ScholarMaintenanceUI | 有权审核的管理员 | E10 | 显示已生效并说明公开视图按生效版本 | `AC-IMPL-FR06-01`；NF05 |
| M49 | 记录决定：状态=驳回（理由/时间） | Coordinator | ScholarChangeRequest | E9（驳回） | 置为驳回并记录决定事实 | `AC-IMPL-FR06-01`；FR14（边界）；BR-IMP-10 |
| M50 | 记录驳回（前后值：公开值不变） | Coordinator | AuditEvidence | E10 | 为驳回留痕，明确公开值未变 | BR-IMP-10；`AC-IMPL-FR06-02`；INV-10 |
| M51 | 已驳回：公开仍为旧值 | Coordinator | ScholarMaintenanceUI | E10 | 返回驳回结论与“公开未变更”事实 | `AC-IMPL-FR06-01`；§6.4 |
| M52 | 显示驳回结果 | ScholarMaintenanceUI | 有权审核的管理员 | E10 | 显示驳回与公开未变，不显示为已生效 | `AC-IMPL-FR06-01`；分析推导 |

## 3. 关键协作说明

### 3.1 授权校验在服务端完成，且先于任何写入

M3/M4/M5/M6 是写入前的强制守卫：

- M3 由 `User` 提供账号标识与账号状态；
- M4 由 `ScholarAuthorization` 提供“该请求者对该目标 Scholar 的有效授权与范围”（只读）；
- M5 由 `Scholar` 确认目标档案正是该授权所指对象，并给出当前公开事实与生效版本；
- M6 是三者合取；不满足即进入 M7–M9（A1）或 M10–M12（A2），**不进入 M20/M24**。

因此“未授权用户直接调用接口改档案”在分析层就落到 A2，而不是靠 `ScholarMaintenanceUI` 隐藏入口（`AC-IMPL-FR06-02`；SRS §8）。同一学者的另一条档案、同名的另一位 Scholar 都不是授权对象；争议期间既有授权保留，本 UC 既不放宽也不撤销（`AC-IMPL-FR05-03`；SPEC ID-12）。

### 3.2 字段范围：Scholar 学术事实 vs 普通 UserProfile 字段

M13 在写入前判定字段范围：

- 超出授权范围或目标已非同一对象 → M14–M16（A3，stale scope）；
- 命中普通 `UserProfile` 字段（昵称/头像/简介/兴趣领域/展示设置）→ M17–M18（A4），本 UC **不写入**，引导 UC-22；
- 仅 Scholar 学术事实且在授权范围内 → M19 进入性质判定。

本序列没有向 `UserProfile` 发送任何消息，也不创建第二个主页：字段分离不依赖任何“迁移/复制主页”的动作（FR06；SRS §12；BR-IMP-05）。

### 3.3 待审变更与公开生效版本并存

M20 是`AC-IMPL-FR06-01`的分析级落实：

- 待审内容（旧值/新值/提交者/理由/证据引用/版本）写入 `ScholarChangeRequest`；
- `Scholar` 的公开字段与生效版本**不变**，公开视图继续读到旧机构；
- M21 的审计把前后值标记为“未生效”，避免把待审内容误读为已公开。

M30–M35 提供申请人本人对待审状态的可读性（`AC-IMPL-FR06-01`“申请人可查待审状态”）：查询按可见范围裁决，只返回本人提交的待审状态与“公开未变更”事实；他人不可读该待审内容（A10；NF06；BR-IMP-11）。

### 3.4 审批只在待审变更存在且版本未变时生效

M36–M52 表达“审批后再生效”：

- M38 重新校验审核者身份、账号状态与审核权限（不依赖前一请求的授权结论）；
- M39 复核“权限有效 ∧ 理由完整 ∧ 待审变更仍为待审核且版本未变”；
- 批准：M44 置为通过 → M45 生成生效版本（只覆盖该变更范围内字段）→ M46 留痕 → M47/M48 返回已生效；
- 驳回：M49 置为驳回 → M50 留痕（公开值不变）→ M51/M52 返回公开未变。

“批准”不确认任何成果归属：本序列没有 `Authorship`/`WorkEntity` 消息（`AC-IMPL-FR15-06`；BR-UC07-07）。

### 3.5 可直接生效的变更

M24 只在“按审核规则该变更无需先行审批”时触发（A5）。该分类口径未冻结（B-01/B-02）；若规则要求 Scholar 事实一律审核，则 A5 不触发、M24 不执行，全部变更走 M20。本分析不预设哪一种口径。

### 3.6 失败、冲突与审计的边界

- M25/M26（A6）：写入或生成生效版本失败时公开值保持旧值，不显示为已生效；
- M40–M43（A8/A7）：无权限、理由缺失与“状态已变化”分开返回，均不改变待审状态与公开值；
- A9 是顺序约束而非独立消息：M46/M27 未成功时不得发送 M47/M28（不得报告已生效），也不得留下无审计的生效决定（BR-IMP-10；INV-10）；
- 审计内容不含凭据与认证材料原文（NF03）；审计的保留期限与查询权限属 FR14/UC-17（D-09）。

## 4. 事件覆盖核对

| 事件/分支 | 是否需要独立对象交互 | 对应消息 | 结论 |
|---|---|---|---|
| E1 提交档案事实变更 | 是 | M1、M2 | 已覆盖 |
| E2 身份/账号状态校验 | 是 | M3 | 已覆盖 |
| E3 对象授权与目标一致性校验 | 是 | M4–M6 | 已覆盖 |
| E4 字段范围判定 | 是 | M13 | 已覆盖（Scholar 事实 / 越界 / 普通主页字段） |
| E5 变更性质判定 | 是 | M19 | 已覆盖；分类口径待 B-01 |
| E6 登记待审、公开版保持旧值 | 是 | M20、M21 | 已覆盖 |
| E7 返回待审结果 + 本人可查待审状态 | 是 | M22、M23；M30–M35 | 已覆盖 |
| E8 审核者复核与决定 | 是 | M36–M39 | 已覆盖 |
| E9 批准生成生效版本 / 驳回保持旧值 | 是 | M44、M45；M49 | 已覆盖 |
| E10 决定与生效审计 + 返回结果 | 是 | M46–M48；M50–M52 | 已覆盖 |
| A1 未认证/停用 | 是 | M7–M9 | 已覆盖；不删除既有公开档案是“无写入”的直接结果 |
| A2 无有效对象授权（他人档案/同名另一 Scholar/授权失效） | 是 | M10–M12 | 已覆盖；含直接调用场景 |
| A3 超范围（stale scope） | 是 | M14–M16 | 已覆盖 |
| A4 含普通 UserProfile 字段 | 是 | M17、M18 | 已覆盖；无 UserProfile 写消息 |
| A5 可直接生效 | 是（条件） | M24、M27–M29 | 已覆盖；口径待 B-01/B-02 |
| A6 写入/生效失败 | 是 | M25、M26 | 已覆盖 |
| A7 待审版本冲突/已处理 | 是 | M42、M43 | 已覆盖 |
| A8 审核无权限/理由缺失 | 是 | M40、M41 | 已覆盖 |
| A9 审计留痕失败 | 否 | 顺序约束（M46/M27 与 M47/M28 之间） | 约束表达，无独立消息 |
| A10 待审期间外部读取 | 否（读侧属 UC-03） | M20/M22 的“公开仍为旧值” + 可见范围约束 | 写侧保证；读侧不重画 |
| 不创建第二 UserProfile / Scholar / Claim / Auth 转移 / Authorship 确认 | 否 | 无相应消息 | 不变量成立 |

## 5. 消息完整性检查

| 检查 | 结果 |
|---|---|
| 每条消息是否有发送者/接收者 | 是，M1–M52 |
| 接收者是否有职责描述 | 是 |
| 是否对应事件流步骤 | 是：M1–M6/E1–E3，M13/E4，M19/E5，M20–M23/E6–E7，M30–M35/E7，M36–M39/E8，M44–M52/E9–E10，M7–M18 对应 A1–A4，M24–M29 对应 A5–A6，M40–M43 对应 A7–A8 |
| 是否出现实现技术 | 否 |
| 是否出现参与者作为系统类 | 否；两位参与者只在图外作为 Actor |
| 是否有无依据的新业务规则 | 否；B-01/B-02/B-05/B-06/B-09/B-10 已在消息与 01/02 中标为待确认 |
| 是否引入全局图未证明的关系 | 否；关系见 05，均有消息或 FR05/FR06/§6.4 依据 |
| 是否出现 UserProfile/Claim/Authorship 消息 | 否 |
