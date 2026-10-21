# UC-05 分析序列与逐消息职责

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-SEQ-10-claim.puml`](diagrams/DGM-SEQ-10-claim.puml)
**导出：** [SVG](diagrams/generated/DGM-SEQ-10-claim.svg) · [PNG](diagrams/generated/DGM-SEQ-10-claim.png)
**方法：** 教材 §5.4.3；只表达分析对象协作，不写 Java、数据库、HTTP/API、文件上传或消息中间件。

## 1. 顺序图范围

本序列只实现 UC-05 的提交与查询两段：

- 提交段：E1–E8，含 A1a/A1b（缺必要证据）、A2（未登录/停用）、A3（目标不可认领）、A4（保存失败）；A5（竞争认领）由 M17/M18/M19/M23 表达，不另设分支。
- 查询段：E9–E11，含 A6（他人申请/证据）、A7（查询失败）、A8（无匹配）。

参与对象（共 7 个）：

- `ClaimUI`（Boundary）
- `ClaimCoordinator`（Control）
- `User`（Entity）
- `Scholar`（Entity）
- `ScholarAuthorization`（Entity，只读）
- `ClaimApplication`（Entity）
- `ReviewEvidence`（Entity）

登录申请人只在图外作为 Actor；“审核管理员”属 UC-06，不进入本序列。`User`/`Scholar`/`ClaimApplication` 的校验应答在图内以隐式返回表达（与 UC-22 的 M3/M4 一致），只有 M18 显式画出授权读取的应答，因为它是“只读事实”而非校验结论。

## 2. 逐消息职责表

| 消息 | 名称 | 发送者 | 接收者 | 事件步骤 | 接收者职责 | 依据/假设 |
|---|---|---|---|---|---|---|
| M1 | 选择平台已有 Scholar 档案并提交可核验证据 | 登录申请人 | ClaimUI | E1 | 接收认领对象选择与材料，准备提交 | FR05；SRS §2.2 |
| M2 | 请求提交认领申请（含已认证请求上下文） | ClaimUI | ClaimCoordinator | E1→E2 | 启动提交协调，转交目标引用、材料与请求者上下文 | FR05；SRS §8；分析推导 |
| M3 | 校验请求者身份与账号状态 | ClaimCoordinator | User | E2 | 提供账号标识与账号状态，供判定“是否可用登录用户” | FR04（服务端校验）；NF03；AC-IMPL-NF03-01 |
| M4 | 确认目标是平台已收录 Scholar 档案（不新建、不改档） | ClaimCoordinator | Scholar | E3 | 提供学者档案身份与状态，供判定目标是否可认领 | FR05；SRS §12 |
| M5 | 判定身份/账号状态/目标对象类型 | ClaimCoordinator | ClaimCoordinator | E2/E3 | 三项判定的合取；不满足转 A2/A3，满足转 M10 | FR05；NF03；BR-UC05-01 |
| M6 | 拒绝提交，无任何写入 | ClaimCoordinator | ClaimUI | A2 | 返回拒绝结论，不返回成功视图 | NF03；AC-IMPL-NF03-01 |
| M7 | 显示拒绝与原因（与提交失败区分） | ClaimUI | 登录申请人 | A2 | 显示“未登录/账号停用/无提交权”，不复用失败文案 | SRS §8；NF01（分析推导） |
| M8 | 拒绝；不新建 Scholar、不以 UserProfile 代替 | ClaimCoordinator | ClaimUI | A3 | 声明目标不可认领，未创建任何学者或主页 | FR05；SRS §12；AC-IMPL-FR05-04 |
| M9 | 显示目标不可认领与原因 | ClaimUI | 登录申请人 | A3 | 显示不可认领及原因，不提示“已提交” | 分析推导；SRS §12 |
| M10 | 校验必要证据是否齐备（最低集合待确认） | ClaimCoordinator | ClaimCoordinator | E4 | 判定证据齐备或列出缺失项；不在此判定证据真伪 | AC-IMPL-FR05-01；B-01/B-02 |
| M11 | 拒绝提交并说明缺失项 | ClaimCoordinator | ClaimUI | A1a | 不创建申请，返回缺失项清单 | AC-IMPL-FR05-01（“不进入审核”） |
| M12 | 显示缺失项与补证说明 | ClaimUI | 登录申请人 | A1a | 显示缺失项与补证要求，不显示为已提交 | AC-IMPL-FR05-01 |
| M13 | 创建申请，状态=待补充，记录缺失项与理由 | ClaimCoordinator | ClaimApplication | A1b | 保存申请人、目标、状态、缺失项与理由 | AC-IMPL-FR05-01（“标待补充并说明缺失”）；FR05 状态集 |
| M14 | 保存本次已提交材料（私有可见） | ClaimCoordinator | ReviewEvidence | A1b | 保存材料与可见范围（本人+有权审核者） | BR-IMP-11；NF06；AC-IMPL-NF06-01 |
| M15 | 返回申请标识、待补充状态与缺失原因 | ClaimCoordinator | ClaimUI | A1b | 返回可查询的申请标识与待补说明 | AC-IMPL-FR05-01；AC-IMPL-FR05-02 |
| M16 | 显示待补充与需补材料 | ClaimUI | 登录申请人 | A1b | 显示待补充状态与补证入口，不显示为待审核 | AC-IMPL-FR05-01 |
| M17 | 读取目标 Scholar 当前有效授权（只读，识别竞争；不修改） | ClaimCoordinator | ScholarAuthorization | E5、A5 | 回答目标是否已有有效授权及其主体；不因读取而变更 | AC-IMPL-FR05-03；BR-IMP-05；SPEC ID-12 |
| M18 | 现有授权事实（授权主体/有效性） | ScholarAuthorization | ClaimCoordinator | E5、A5 | 返回当前有效授权事实，供标记竞争 | AC-IMPL-FR05-03 |
| M19 | 创建申请，状态=待审核（记录竞争事实；不置通过/驳回/争议） | ClaimCoordinator | ClaimApplication | E6 | 保存申请人、目标、待审核状态、竞争事实与提交信息 | FR05；AC-IMPL-FR05-03；SPEC §3 |
| M20 | 登记私有证据并关联申请（仅本人与有权审核者可见） | ClaimCoordinator | ReviewEvidence | E7 | 保存材料并关联申请，限定可见范围 | BR-IMP-11；NF06；AC-IMPL-NF06-01 |
| M21 | 提交失败；不虚报已提交、不留可审核半成品 | ClaimCoordinator | ClaimUI | A4 | 返回失败，声明无待审核申请残留 | AC-IMPL-FR05-01（缺证据不得进入审核）；分析推导 |
| M22 | 显示失败与重试（区别于待补充/待审核） | ClaimUI | 登录申请人 | A4 | 显示失败与重试，不显示“已提交” | NF01（分析推导）；BR-UC05-10 |
| M23 | 申请标识、状态=待审核、可查询入口；原授权未变更 | ClaimCoordinator | ClaimUI | E8 | 返回提交结果并声明未改变任何授权 | FR05；AC-IMPL-FR05-02/03 |
| M24 | 显示已提交待审核与可查询入口 | ClaimUI | 登录申请人 | E8 | 显示待审核与查询入口，不显示“已通过” | FR05；AC-IMPL-FR05-02 |
| M25 | 查询本人认领申请的状态与决定 | 登录申请人 | ClaimUI | E9 | 接收查询意图（本人范围） | AC-IMPL-FR05-02；SRS §8 |
| M26 | 请求查询本人申请 | ClaimUI | ClaimCoordinator | E9→E10 | 启动查询协调，转交请求者上下文与查询范围 | SRS §8；分析推导 |
| M27 | 校验请求者身份与账号状态 | ClaimCoordinator | User | E10 | 提供账号标识与账号状态，供判定可查询主体 | NF03；AC-IMPL-NF03-01；SRS §8 |
| M28 | 读取申请并核验申请人归属 | ClaimCoordinator | ClaimApplication | E10、E11 | 返回本人申请的状态、理由/缺失项与（若有）决定；他人申请不返回 | AC-IMPL-FR05-02；AC-IMPL-NF03-01 |
| M29 | 拒绝读取，不返回他人申请或证据原文 | ClaimCoordinator | ClaimUI | A6 | 返回无权结论，不返回任何他人内容 | AC-IMPL-NF03-01；AC-IMPL-NF06-01 |
| M30 | 显示无权读取 | ClaimUI | 登录申请人 | A6 | 显示无权结果，与“无申请”区分 | SRS §8；分析推导 |
| M31 | 返回查询失败，不伪装为“无申请” | ClaimCoordinator | ClaimUI | A7 | 返回执行失败信号 | NF01（分析推导）；BR-UC05-10 |
| M32 | 显示失败与重试 | ClaimUI | 登录申请人 | A7 | 显示失败与重试，不显示“无申请” | NF01（分析推导） |
| M33 | 返回“无该认领申请” | ClaimCoordinator | ClaimUI | A8 | 返回空结果，不放宽为他人申请 | 分析推导 |
| M34 | 显示无申请 | ClaimUI | 登录申请人 | A8 | 显示无申请，与失败/无权区分 | 分析推导 |
| M35 | 状态（待审核/待补充/通过/驳回/争议）+ 理由/缺失项；不回显他人证据 | ClaimCoordinator | ClaimUI | E11 | 返回本人可读状态与理由；材料原文按可见范围控制 | AC-IMPL-FR05-02；BR-IMP-11；AC-IMPL-NF06-01 |
| M36 | 显示本人可读状态与理由 | ClaimUI | 登录申请人 | E11 | 按 M35 显示状态与理由，不展示他人证据 | AC-IMPL-FR05-02；AC-IMPL-NF06-01 |

## 3. 关键协作说明

### 3.1 目标必须是“已收录 Scholar”，不是新建学者或主页

M4 读取既有 Scholar 档案后才进入后续步骤：

- 目标不是 Scholar（例如指向 UserProfile、已被合并迁移的对象）时进入 A3（M8/M9），**不创建** Scholar，也不把主页数据当学者档案；
- 本序列没有任何“创建 Scholar / 创建 UserProfile / 合并主页”的消息（FR05；SRS §12；AC-IMPL-FR05-04）。

### 3.2 缺必要证据：两个被允许的处置都保留

M10 判定齐备性后：

- A1a：M11/M12 直接拒绝，不创建申请；
- A1b：M13/M14/M15/M16 创建状态为“待补充”的申请并说明缺失；A1b 不读取/不记录 `competitionFact`；竞争事实仅在证据齐备的 A5 路径由 M17/M18 读取并随 M19 记录，是否进入争议属 UC-06。

AC-IMPL-FR05-01 原文允许“不进入审核**或**标待补充并说明缺失”，因此本序列两种都画、都标为待确认（B-01），不擅自固定一种。两种分支都满足“不进入审核”。

### 3.3 提交阶段不写授权：M17/M18 只读，M19 只登记申请

- M17/M18 只读取目标当前有效授权，用于识别竞争认领并记录事实；
- 本序列**没有**任何 `ClaimCoordinator → ScholarAuthorization` 的创建/修改/撤销/转移消息；
- A5（竞争认领）因此不是独立分支，而是 M17/M18 的读取结果进入 M19 的申请记录与 M23 的结果说明：原授权保持原状，是否进入争议由 UC-06 决定（AC-IMPL-FR05-03；BR-IMP-05；SPEC ID-12）。

### 3.4 状态归属：本 UC 只产生待审核（及 A1b 的待补充）

- M19 只写 `待审核`；M13 只写 `待补充`；
- `通过/驳回/争议` 在 M35/M36 只作为**只读**取值出现，由 UC-06 产生；
- 审核侧的 `待审核 → 待补充`（要求补证）与 `待补充 → 待审核`（放行重提）不在本序列（BR-UC05-04；B-04）。

### 3.5 证据私有与归属过滤

- M14/M20 登记材料时即限定可见范围：仅本人与有权审核者（BR-IMP-11；NF06）；
- 查询段以“本人申请”为范围（M27/M28）；指向他人申请或他人材料转 A6（M29/M30），不返回原文；
- M35 只返回状态、理由与缺失项，不回显他人证据（AC-IMPL-NF06-01）。

### 3.6 失败与拒绝、空结果分开

- A2/A3/A6 是**拒绝**（无写入）；
- A1a 是**证据不足的拒绝**，A1b 是**可继续补证的待补充**；查询证据的可见范围守卫由 ClaimApplication 的归属判定继承，不向 ReviewEvidence 发独立消息（NF06-01 的读侧拒绝由 M28/M29 表达，证据读取只在本人/有权审核者范围内）。
- A4 是**执行失败**（不虚报成功）；
- A7 是**查询失败**，A8 是**无匹配**；
- 六类结果（待审核/待补充/拒绝/失败/无申请/无权）不共用同一消息或同一文案（BR-UC05-10；NF01 分析推导）。

### 3.7 不涉及的对象

`UserProfile`、`Authorship`、`WorkEntity`、`SourceRecord`、`AuditEvidence`、审核管理员、受控待办、通知对象在本序列中**没有消息**；对应职责分别属 UC-04/22、UC-08/10、UC-09/10、UC-17、UC-06、UC-06/17，以及未冻结的需求（B-05/B-06/B-07）。

## 4. 消息完整性检查

| 检查 | 结果 |
|---|---|
| 每条消息有发送者/接收者 | 是，M1–M36 |
| 接收者有明确职责 | 是 |
| 每条消息对应事件步骤或分支 | 是：E1–E11、A1a/A1b、A2–A4、A6–A8；A5 由 M17/M18/M19/M23 表达 |
| 是否出现实现技术 | 否 |
| 是否出现 Actor 作为系统类 | 否；审核管理员不入图 |
| 是否把拒绝/失败/空结果混用 | 否；见 3.6 |
| 是否有审核决定或授权生效的消息 | 否；明确排除（UC-06） |
| 是否创建 Scholar/UserProfile/Authorship | 否；无相应消息 |
| 是否修改既有授权 | 否；仅 M17/M18 只读 |
