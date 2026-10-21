# UC-06 分析序列与逐消息职责

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-SEQ-11-claim-review.puml`](diagrams/DGM-SEQ-11-claim-review.puml)
**导出：** [SVG](diagrams/generated/DGM-SEQ-11-claim-review.svg) · [PNG](diagrams/generated/DGM-SEQ-11-claim-review.png)
**方法：** 教材 §5.4.3；只表达分析对象协作，不写 Java、数据库、HTTP/API、队列或消息中间件。

## 1. 顺序图范围

本序列实现 UC-06 的三段：

- **审核与决定段**（E1–E11）：含 A1（无权限/停用）、A2（已决定/版本变化）、A8（理由缺失）、A5（冲突/记录争议）、A6（授权失败）、A7（驳回）、A3（要求补证）。
- **补证复核段**（A4）：待补充申请在本人重提（UC-05）后由审核者复核并放行或保持。
- **决定查询段**（E12）。

参与对象（共 8 个）：

- `ClaimReviewUI`（Boundary）
- `ClaimReviewCoordinator`（Control）
- `User`（Entity）
- `ClaimApplication`（Entity）
- `ReviewEvidence`（Entity）
- `Scholar`（Entity）
- `ScholarAuthorization`（Entity）
- `AuditEvidence`（Entity）

“有权审核的管理员”只在图外作为 Actor；**登录申请人不进入本序列**：其提交属 UC-05，对本人结果的读取属 UC-05 的 E9–E11（只读），本序列只把决定记录为可读投影（M32/M36/M40/M48/M52）。`User`/`ClaimApplication` 的校验与读取应答在图内以隐式返回表达；只有 M16（现有授权事实）显式画出，因为它是被判定用的只读事实。

## 2. 逐消息职责表

| 消息 | 名称 | 发送者 | 接收者 | 事件步骤 | 接收者职责 | 依据/假设 |
|---|---|---|---|---|---|---|
| M1 | 打开待审核/待补充申请，请求审核上下文 | 有权审核的管理员 | ClaimReviewUI | E1 | 接收申请引用与审核意图，准备上下文请求 | FR05；FR14；SRS §8 |
| M2 | 请求审核上下文（申请引用＋已认证请求上下文） | ClaimReviewUI | ClaimReviewCoordinator | E1→E2 | 启动审核协调，转交申请引用与请求者上下文 | 分析推导 |
| M3 | 校验审核者身份、账号状态与审核权限 | ClaimReviewCoordinator | User | E2 | 提供账号标识、账号状态与权限判定所需的账号主体事实 | FR14；NF03；AC-IMPL-NF03-01；SRS §8 |
| M4 | 判定是否具备审核权限且账号可用 | ClaimReviewCoordinator | ClaimReviewCoordinator | E2 | 权限/状态合取判定；不满足转 A1 | FR14；NF03；BR-UC06-01 |
| M5 | 拒绝，无任何写入 | ClaimReviewCoordinator | ClaimReviewUI | A1 | 返回拒绝结论，不返回申请或证据内容 | NF03；AC-IMPL-NF03-01 |
| M6 | 显示拒绝（与“状态已变化”区分） | ClaimReviewUI | 有权审核的管理员 | A1 | 显示未登录/停用/无审核权限，不复用其他文案 | SRS §8；NF01（分析推导） |
| M7 | 读取申请状态、申请人/目标/证据引用、版本与最近理由 | ClaimReviewCoordinator | ClaimApplication | E3 | 返回当前状态、引用、版本与最近理由 | FR05；`DGM-STATE-02` |
| M8 | 判定申请是否仍为待审核且版本未变；待补充须先走A4放行 | ClaimReviewCoordinator | ClaimReviewCoordinator | E3/E7 | 状态与版本守卫；不满足转 A2；待补充不直接进入决定分支 | BR-UC06-02；`DGM-STATE-02` |
| M9 | 拒绝本次决定并返回当前状态，不覆盖已有决定 | ClaimReviewCoordinator | ClaimReviewUI | A2 | 返回拒绝与当前状态；无写入 | `DGM-STATE-02`“过期审核不改变原状态” |
| M10 | 显示“状态已变化”与当前状态 | ClaimReviewUI | 有权审核的管理员 | A2 | 显示状态冲突结果，不显示为失败或无权 | NF01（分析推导）；BR-UC06-10 |
| M11 | 读取该申请的私有材料（仅有权审核者可见） | ClaimReviewCoordinator | ReviewEvidence | E4 | 按可见范围返回材料/材料引用；无权限者不返回 | BR-IMP-11；NF06；AC-IMPL-NF06-01 |
| M12 | 返回申请摘要与证据材料 | ClaimReviewCoordinator | ClaimReviewUI | E4 | 组装申请摘要与材料，供核对 | FR05；BR-IMP-11 |
| M13 | 显示申请信息与私有证据 | ClaimReviewUI | 有权审核的管理员 | E4 | 显示申请人/目标/材料，仅在审核视图 | AC-IMPL-NF06-01；BR-IMP-11 |
| M14 | 确认目标仍为已收录 Scholar（不新建、不改写档案） | ClaimReviewCoordinator | Scholar | E5 | 提供档案身份与已收录状态；接受只读确认 | FR05；SRS §12；分析推导 |
| M15 | 读取目标当前有效授权（只读，不改动） | ClaimReviewCoordinator | ScholarAuthorization | E5 | 回答目标是否已有有效授权及其主体 | AC-IMPL-FR05-03；BR-IMP-05；SPEC ID-12 |
| M16 | 现有授权事实（授权主体/有效性） | ScholarAuthorization | ClaimReviewCoordinator | E5 | 返回有效授权事实，供冲突判定 | AC-IMPL-FR05-03 |
| M17 | 判定是否存在未决冲突（已有他人有效授权/身份冲突未决） | ClaimReviewCoordinator | ClaimReviewCoordinator | E5/E7 | 冲突判定；批准路径受影响时转 A5 | AC-IMPL-FR05-02/03；BR-UC06-04 |
| M18 | 选择决定（批准/驳回/要求补证/记录争议）并填写理由 | 有权审核的管理员 | ClaimReviewUI | E6 | 接收决定类型与理由 | FR05 状态集；`DGM-STATE-02`；FR14 |
| M19 | 提交决定与理由 | ClaimReviewUI | ClaimReviewCoordinator | E6 | 启动决定处理，转交决定类型与理由 | 分析推导 |
| M20 | 复核守卫：权限仍有效、申请状态未变、理由完整、批准时无未决冲突 | ClaimReviewCoordinator | ClaimReviewCoordinator | E7 | 四类守卫合取；分别转 A1/A2/A8/A5 | `DGM-STATE-02`“审核必须校验当前版本和权限”；AC-IMPL-FR05-02/03 |
| M21 | 拒绝该决定并要求填写理由；状态不变 | ClaimReviewCoordinator | ClaimReviewUI | A8 | 返回拒绝与“理由必填”，无写入 | `DGM-STATE-02` 理由完整守卫；FR14 |
| M22 | 显示“理由必填” | ClaimReviewUI | 有权审核的管理员 | A8 | 显示理由必填，不显示失败文案 | NF01（分析推导） |
| M23 | 记录争议：状态=争议，理由=冲突事实；不批准、不创建授权 | ClaimReviewCoordinator | ClaimApplication | A5 | 写入状态与冲突理由；不创建/不转移授权 | AC-IMPL-FR05-03；BR-IMP-05；SPEC ID-12 |
| M24 | 记录争议决定（操作者/对象/时间/前后值/理由） | ClaimReviewCoordinator | AuditEvidence | A5/E10 | 保存决定留痕与前后值 | FR14；BR-IMP-10；INV-10 |
| M25 | 已记录争议；原授权不变；争议待人工结论 | ClaimReviewCoordinator | ClaimReviewUI | A5/E11 | 返回争议结果并声明未改授权 | AC-IMPL-FR05-03；SRS §6.3 |
| M26 | 显示争议已登记、原授权保留 | ClaimReviewUI | 有权审核的管理员 | A5 | 显示争议与“原授权不变” | AC-IMPL-FR05-03 |
| M27 | 创建对象级管理授权（被授权=申请人；目标=该 Scholar/门户；仅此范围） | ClaimReviewCoordinator | ScholarAuthorization | E8 | 保存授权主体、目标与对象级范围 | AC-IMPL-FR05-02；BR-IMP-05；SPEC ID-07 |
| M28 | 保持原状态并记录失败原因；不写“通过” | ClaimReviewCoordinator | ClaimApplication | A6 | 保持原状态，记录失败原因摘要 | `DGM-STATE-02`；`DGM-ACTIVITY-01` |
| M29 | 记录授权失败与前后值（状态不变） | ClaimReviewCoordinator | AuditEvidence | A6/E10 | 保存失败留痕与未变的前后值 | BR-IMP-10；INV-10；`DGM-STATE-02` |
| M30 | 授权失败：保持原状态，不虚报通过 | ClaimReviewCoordinator | ClaimReviewUI | A6/E11 | 返回失败与“保持原状态”，不返回通过 | `DGM-STATE-02`；BR-UC06-10 |
| M31 | 显示失败与重试 | ClaimReviewUI | 有权审核的管理员 | A6 | 显示失败与重试，不显示“已通过” | NF01（分析推导） |
| M32 | 状态=通过，记录决定理由与时间 | ClaimReviewCoordinator | ClaimApplication | E9 | 写入通过状态、理由与决定时间（形成本人可读投影） | AC-IMPL-FR05-02；FR05；BR-UC06-11 |
| M33 | 记录批准与授权（操作者/对象/时间/前后值/理由） | ClaimReviewCoordinator | AuditEvidence | E10 | 保存批准与授权的前后值、操作者、时间与理由 | FR14；BR-IMP-10；INV-10 |
| M34 | 批准生效：授权仅对目标门户；未建第二主页；未确认任何 Authorship | ClaimReviewCoordinator | ClaimReviewUI | E11 | 返回生效结果与授权范围边界 | AC-IMPL-FR05-02/04；SPEC ID-07 |
| M35 | 显示批准结果与授权范围 | ClaimReviewUI | 有权审核的管理员 | E11 | 显示批准、授权范围与“不确认成果” | AC-IMPL-FR05-02/04 |
| M36 | 状态=驳回，记录理由 | ClaimReviewCoordinator | ClaimApplication | A7/E9 | 写入驳回状态与理由 | AC-IMPL-FR05-03；`DGM-STATE-02` |
| M37 | 记录驳回（前后值/理由/时间/操作者） | ClaimReviewCoordinator | AuditEvidence | A7/E10 | 保存驳回留痕 | FR14；BR-IMP-10 |
| M38 | 已驳回；不撤销、不改动原授权 | ClaimReviewCoordinator | ClaimReviewUI | A7/E11 | 返回驳回结果并声明未改授权 | AC-IMPL-FR05-03；`DGM-ACTIVITY-01` |
| M39 | 显示驳回结果 | ClaimReviewUI | 有权审核的管理员 | A7 | 显示驳回与理由入口 | FR05；NF01 |
| M40 | 状态=待补充，记录缺失项/需补材料与理由 | ClaimReviewCoordinator | ClaimApplication | A3/E9 | 写入待补充状态、缺失项与理由 | AC-IMPL-FR05-01；`DGM-ACTIVITY-01` |
| M41 | 记录补证决定 | ClaimReviewCoordinator | AuditEvidence | A3/E10 | 保存补证决定留痕 | FR14；BR-IMP-10 |
| M42 | 已要求补证；等待本人重提（重提属 UC-05） | ClaimReviewCoordinator | ClaimReviewUI | A3/E11 | 返回待补充与需补说明 | AC-IMPL-FR05-01 |
| M43 | 显示待补充与需补材料 | ClaimReviewUI | 有权审核的管理员 | A3 | 显示待补充与缺失项清单 | AC-IMPL-FR05-01 |
| M44 | 提交补证复核结论（本人已重提补证） | 有权审核的管理员 | ClaimReviewUI | A4 | 接收复核请求 | `DGM-STATE-02`“本人重提[校验通过]”；C-07 |
| M45 | 请求复核补证齐备性 | ClaimReviewUI | ClaimReviewCoordinator | A4 | 启动复核，转交申请引用 | 分析推导 |
| M46 | 读取本人重提交的材料版本 | ClaimReviewCoordinator | ReviewEvidence | A4 | 返回最新提交的材料版本（可见范围内） | BR-IMP-11；AC-IMPL-FR05-01 |
| M47 | 先校验申请仍为待补充且版本未变，再判定补证是否齐备；否则转A2 | ClaimReviewCoordinator | ClaimReviewCoordinator | A4 | 状态/版本守卫后做齐备性判定；转 A4a/A4b | AC-IMPL-FR05-01；C-02；`DGM-STATE-02` |
| M48 | 状态=待审核，记录复核理由 | ClaimReviewCoordinator | ClaimApplication | A4a | 放行回可审核状态并记录理由 | `DGM-STATE-02`“待补充→待审核” |
| M49 | 记录复核与放行 | ClaimReviewCoordinator | AuditEvidence | A4a | 保存复核结论与前后状态 | FR14；BR-IMP-10 |
| M50 | 已放行回待审核 | ClaimReviewCoordinator | ClaimReviewUI | A4a | 返回放行结果 | `DGM-STATE-02` |
| M51 | 显示已放行回待审核 | ClaimReviewUI | 有权审核的管理员 | A4a | 显示放行结果，不显示为“通过” | 分析推导 |
| M52 | 保持待补充，更新缺失项与理由（无状态迁移） | ClaimReviewCoordinator | ClaimApplication | A4b | 保持原状态，更新缺失项与理由 | AC-IMPL-FR05-01 |
| M53 | 记录复核未通过 | ClaimReviewCoordinator | AuditEvidence | A4b | 保存复核未通过留痕 | FR14；BR-IMP-10 |
| M54 | 仍待补充 | ClaimReviewCoordinator | ClaimReviewUI | A4b | 返回仍缺项结果 | AC-IMPL-FR05-01 |
| M55 | 显示仍缺项 | ClaimReviewUI | 有权审核的管理员 | A4b | 显示仍缺项，不显示为失败 | NF01（分析推导） |
| M56 | 查询该申请的决定与审计依据 | 有权审核的管理员 | ClaimReviewUI | E12 | 接收查询意图 | FR14；AC-IMPL-FR05-02 |
| M57 | 请求决定查询（申请引用） | ClaimReviewUI | ClaimReviewCoordinator | E12 | 启动只读查询，转交申请引用 | 分析推导 |
| M58 | 校验审核者身份与审核权限 | ClaimReviewCoordinator | User | E12 | 提供账号状态与权限事实 | NF03；AC-IMPL-NF03-01；SRS §8 |
| M59 | 读取当前状态与决定理由 | ClaimReviewCoordinator | ClaimApplication | E12 | 返回状态与理由（只读） | AC-IMPL-FR05-02；FR05 |
| M60 | 读取该决定的审计记录 | ClaimReviewCoordinator | AuditEvidence | E12 | 返回决定、操作者、时间、前后值、理由 | FR14；AC-IMPL-FR14-02（边界） |
| M61 | 返回决定、理由与审计摘要；无权限则拒绝 | ClaimReviewCoordinator | ClaimReviewUI | E12 | 组装只读结果；无权限返回拒绝 | FR14；NF03 |
| M62 | 显示决定与审计依据 | ClaimReviewUI | 有权审核的管理员 | E12 | 仅显示决定与审计摘要，不显示材料原文等私有内容 | AC-IMPL-NF06-01；BR-IMP-11 |

## 3. 关键协作说明

### 3.1 批准只在“无未决冲突”时发生

- M15/M16 读取目标当前有效授权，M17 作冲突判定；M20 把“无未决冲突”作为批准守卫的一部分。
- 存在他人有效授权或未决身份冲突时，**批准路径不执行**，改走 A5：M23 记录争议（理由=冲突事实），并由 M24 审计。
- 序列中**没有**任何“改写/撤销/转移既有授权”的消息；M27 只在无冲突分支创建一条新授权（AC-IMPL-FR05-02/03；BR-IMP-05；SPEC ID-12）。

### 3.2 授权范围：仅目标 Scholar/门户的对象级管理权

- M27 的授权内容只有：被授权主体=该申请的申请人、目标=该申请的目标 Scholar、范围=该目标门户/档案的对象级管理权。
- 序列中**没有** UserProfile 消息、Scholar 写消息、Authorship 消息、WorkEntity 消息：不建第二个主页、不改学者档案字段、不确认任何成果归属（AC-IMPL-FR05-04；SPEC ID-07）。
- M34 明确把这三条边界回给审核视图，避免“批准即全部确认”的误读。

### 3.3 竞争认领：保留原授权，进入受控争议

- 竞争事实来自 M15/M16 的只读读取；本 UC 不修改、不撤销、不转移原授权（BR-UC06-06）。
- “受控”体现为：状态=争议（M23）＋审计留痕（M24）＋返回“原授权不变、待人工结论”（M25）。
- 争议结案、授权解封/转移与申诉程序**不在本序列**（SPEC ID-12 待评审；C-05）。

### 3.4 状态归属：本 UC 产生的迁移

- M40 `待审核→待补充`；M23 `待审核→争议`；M32 `待审核→通过`；M36 `待审核→驳回`；M48 `待补充→待审核`；复核前 M47 校验状态/版本，失败转 A2。
- M28 授权失败**不改状态**（保持原状态）；M9 过期拒绝**不改状态**；M52 复核仍缺项**不改状态**（只更新缺失项与理由）。
- 序列**不产生** `[*]→待审核/待补充`：那是 UC-05 的提交侧（E5–E7）。
- `DGM-STATE-02` 未定义 `待补充→驳回/争议`；本序列不新增该边（C-06）。

### 3.5 失败、拒绝、不变状态分开

- A1（无权/停用/未登录）、A2（已决定/版本变化）、A8（理由缺失）都是**拒绝且无写入**，但结果文案与语义分开（M5/M9/M21）。
- A6（授权写入失败）是**执行失败**：M28/M30 明确“保持原状态、不虚报通过”。
- A9（审计留痕失败）由图上注记表达：决定不得报告为已生效（INV-10；BR-IMP-10；分析推导）。
- 五类结果（通过/驳回/待补充/争议/失败）不与“已通过”共用同一返回（BR-UC06-10）。

### 3.6 决定必须留痕且可查

- 每次改变状态的路径都有对应审计消息：M24（争议）、M29（授权失败）、M33（批准）、M37（驳回）、M41（补证）、M49（放行）、M53（复核未通过）。
- 审计内容按 FR14 要求覆盖：决定、操作者、对象、时间、前后值、理由。
- M56–M62 是只读决定查询：再校验权限（M58）、读取状态（M59）与审计（M60），不改变任何状态。
- 审计记录本身的查询界面与导出属 UC-17（FR14）；本 UC 只产生与读取该申请的审计事实。

### 3.7 证据私有与权限边界

- M11/M46 只在“有权审核者”范围内读取材料；M12/M13 只在审核视图展示；本序列没有把材料投影到公开视图的消息。
- M58/M61 对决定查询重新做权限校验；普通登录用户读取他人申请/材料在 UC-05 的 A6 中拒绝。
- 证据最低集合与“可核验”判定阈值未冻结（C-02），本序列只表达“齐备/缺失”的判定结论。

### 3.8 不涉及的对象

`UserProfile`、`Authorship`、`WorkEntity`、`WorkVersion`、`SourceRecord`、`ConflictQueue`、`Notification`、`ReviewerRole`、`Session`、UC-05 的 `ClaimUI`/`ClaimCoordinator` 在本序列中**没有消息**；对应职责分别属 UC-04/22、UC-08/10、UC-09/10、UC-17、未冻结需求（C-03/C-05/C-11）以及 UC-05。

## 4. 消息完整性检查

| 检查 | 结果 |
|---|---|
| 每条消息有发送者/接收者 | 是，M1–M62 |
| 接收者有明确职责 | 是 |
| 每条消息对应事件步骤或分支 | 是：E1–E12、A1–A9 |
| 是否出现实现技术 | 否 |
| 是否出现 Actor 作为系统类 | 否；审核管理员不入系统类，申请人不在本序列 |
| 是否把拒绝/失败/不变状态混用 | 否；见 3.5 |
| 是否有“改写/撤销/转移原授权”的消息 | 否；只读读取（M15）+ 无冲突创建（M27） |
| 是否创建 Scholar/UserProfile/Authorship | 否 |
| 是否把 UC-05 提交或本人查询重画一遍 | 否；提交与本人查询仅作为输入/后置条件引用 |
| 是否为审核角色等级/SLA 造类 | 否；C-01/C-03 |
| 是否把争议结案/授权转移画进本 UC | 否；C-05 |
| 改变状态的每条路径是否有审计消息 | 是：M24/M29/M33/M37/M41/M49/M53 |
