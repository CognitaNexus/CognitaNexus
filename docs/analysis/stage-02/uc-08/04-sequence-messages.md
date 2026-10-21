# UC-08 分析序列与逐消息职责

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-SEQ-13-authorship-correction.puml`](diagrams/DGM-SEQ-13-authorship-correction.puml)
**导出：** [SVG](diagrams/generated/DGM-SEQ-13-authorship-correction.svg) · [PNG](diagrams/generated/DGM-SEQ-13-authorship-correction.png)
**方法：** 教材 §5.4.3；只表达分析对象协作，不写 Java、数据库、HTTP/API 或消息中间件。

## 1. 顺序图范围

本序列实现 UC-08 的“确认、补录和纠错成果”：主流程 E1–E15，以及 A1（未认证/停用）、A2（无有效对象授权）、A3（已排除/争议）、A4（他人关系）、A5（缺最低字段/不可核验）、A6（本人确认写入失败）、A7（相反归属主张→争议）、A9（状态已变化）、A10（审核无权限/理由缺失）分支；A8（审计留痕失败）、A11（改派/拆分转 UC-10）、A12（待审期间公开读取）、A13（已排除不得自动恢复）、A14（不提供投稿/出版）为分析级约束或范围边界，无独立消息。

参与对象（共 11 个）：

- `AchievementCorrectionUI`（Boundary）
- `AchievementCorrectionCoordinator`（Control）
- `User`（Entity）
- `ScholarAuthorization`（Entity）
- `Scholar`（Entity）
- `Authorship`（Entity）
- `WorkEntity`（Entity）
- `SourceRecord`（Entity）
- `CorrectionRequest`（Entity）
- `ReviewEvidence`（Entity）
- `AuditEvidence`（Entity）

两位参与者（已获授权学者、有权审核的管理员）只在图外作为 Actor，不画成系统类。

## 2. 逐消息职责表

| 消息 | 名称 | 发送者 | 接收者 | 事件步骤 | 接收者职责 | 依据/假设 |
|---|---|---|---|---|---|---|
| M1 | 打开本人成果关系视图，选定目标成果与本人参与关系并选择操作（确认/补录/纠错） | 已获授权学者 | AchievementCorrectionUI | E1 | 接收选定对象与操作类型 | FR07；`DGM-USECASE-05`（门户管理者 → UC-08）；§11.4 |
| M2 | 请求确认、补录或纠错（目标成果引用＋关系引用＋操作类型＋已认证请求上下文） | AchievementCorrectionUI | AchievementCorrectionCoordinator | E1→E2 | 启动协调，转交请求内容与请求者上下文 | FR07；SRS §8；分析推导 |
| M3 | 校验请求者身份与账号状态 | Coordinator | User | E2 | 提供账号标识与账号状态，供判定“是否可用账号发起受保护写入” | NF03；`AC-IMPL-NF03-01`；SRS §8 |
| M4 | 读取请求者对该 Scholar 的有效对象级授权与授权范围 | Coordinator | ScholarAuthorization | E3 | 回答“是否持有有效管理授权、范围到哪里”（只读） | FR05；`AC-IMPL-FR05-02`；BR-IMP-05；SPEC ID-07 |
| M5 | 确认目标学者正是授权所指学者，提供本人身份事实与本人相关关系 | Coordinator | Scholar | E4 | 提供学者身份引用与本人相关关系集合，供一致性判定 | FR07；BR-IMP-03；`AC-IMPL-FR15-01`（同名不合并） |
| M6 | 读取所选参与关系（归属学者/角色/顺序/确认状态/冲突标记） | Coordinator | Authorship | E4 | 提供该关系的归属、角色、顺序、状态与冲突标记 | FR15-S07；`DGM-STATE-05`；SPEC ID-05/ID-11 |
| M7 | 判定 账号可用 ∧ 授权有效 ∧ 目标学者一致 ∧ 关系属于本学者 | Coordinator | Coordinator | E4 | 四条守卫的合取；不满足转 A1/A2，满足转 M14 | `AC-IMPL-FR05-02`；`AC-IMPL-FR07-01`（只改自己关系）；SRS §8 |
| M8 | 拒绝，无任何写入 | Coordinator | AchievementCorrectionUI | A1 | 返回拒绝结论，不返回成功/待审视图 | NF03；`AC-IMPL-NF03-01` |
| M9 | 显示拒绝（与越权/失败区分） | AchievementCorrectionUI | 已获授权学者 | A1 | 显示“未认证/账号不可用”，不复用越权或失败文案 | 分析推导；NF01；具体文案未冻结 |
| M10 | 记录受控拒绝审计（不含凭据） | Coordinator | AuditEvidence | A1 | 记录被拒绝的受保护写入尝试 | BR-IMP-10；INV-10；NF03 |
| M11 | 拒绝越权，目标关系与成果无变更 | Coordinator | AchievementCorrectionUI | A2 | 返回越权结论并声明目标无变化 | `AC-IMPL-FR05-02`；BR-IMP-05；SRS §8 |
| M12 | 显示越权结果 | AchievementCorrectionUI | 已获授权学者 | A2 | 显示越权结果而非保存成功 | `AC-IMPL-FR07-01`（只改自己关系）；SRS §8（不以入口隐藏代替拒绝） |
| M13 | 记录受控越权尝试审计 | Coordinator | AuditEvidence | A2 | 记录请求者/目标对象/结果/时间 | BR-IMP-10；INV-10 |
| M14 | 判定操作类型：确认本人参与关系 / 补录已发表成果元数据 / 申请修改误归属或字段 | Coordinator | Coordinator | E5 | 分类本次请求并记录判定结论 | FR07 三类行为；§6.4；B-04 待确认 |
| M15 | 状态守卫：非已排除/争议 ∧ 无未决冲突 ∧ 确认范围覆盖本关系 ∧ 仅本关系 | Coordinator | Coordinator | E6 | 判定该关系当前状态是否允许本人确认，并限定作用范围 | §6.4；BR-356；`DGM-STATE-05`；`AC-IMPL-FR15-04/06`；B-02 |
| M16 | 拒绝确认：已排除/争议不得由本人改为本人确认 | Coordinator | AchievementCorrectionUI | A3 | 返回“需有权审核者处置”，关系与公开值不变 | §6.4；BR-356；`AC-IMPL-FR15-04` |
| M17 | 显示“该关系需有权审核者处置” | AchievementCorrectionUI | 已获授权学者 | A3 | 显示状态不允许而非保存成功 | `AC-IMPL-FR15-04`；分析推导 |
| M18 | 记录被拒绝的确认尝试 | Coordinator | AuditEvidence | A3 | 记录原状态、尝试类型与结果 | BR-IMP-10；INV-10；`AC-IMPL-FR15-04` |
| M19 | 该关系状态=争议（保留双方证据），不置本人确认 | Coordinator | Authorship | A7 | 承载争议状态与双方证据引用 | §6.4；BR-356；FR15-S08 E3；FR15-S09 E2 |
| M20 | 记录争议提出方/证据/时间并进入审核处置 | Coordinator | AuditEvidence | A7 | 保留提出人、证据、处理人与时间（未结案） | §11.3“争议应进入有权审核者处理队列，记录提出人、证据、处理人、结案结论及时间” |
| M21 | 本人确认未生效：关系已转争议 | Coordinator | AchievementCorrectionUI | A7 | 返回“已转争议处理”，不返回已确认 | §6.4；BR-356 |
| M22 | 显示“已转争议处理” | AchievementCorrectionUI | 已获授权学者 | A7 | 显示争议而非本人确认 | §11.3；分析推导 |
| M23 | 追加本人确认证据（确认人/时间/范围），仅该关系状态→本人确认 | Coordinator | Authorship | E7 | 追加本人确认证据事件并置该关系状态 | FR15-S08 AC-02；`DGM-STATE-05`；SPEC ID-06/ID-10 |
| M24 | 记录本人确认（身份/对象/前后状态/时间） | Coordinator | AuditEvidence | E7→E8 | 为本人的确认留可追溯留痕 | BR-IMP-10；INV-10；FR15-S08 |
| M25 | 失败：关系状态保持原值，不报告已确认 | Coordinator | AchievementCorrectionUI | A6 | 返回失败结论，声明状态未变 | FR15-S09 E4；INV-10；分析推导 |
| M26 | 显示失败与重试 | AchievementCorrectionUI | 已获授权学者 | A6 | 失败与成功、拒绝三态可区分 | 分析推导；NF01 |
| M27 | 不扩散核对：其余 N 条候选署名关系保持原状态 | Coordinator | Coordinator | E7 | 核对本次只改变一条关系，不连带其他候选 | `AC-IMPL-FR15-06`；SPEC ID-06/ID-07；§11.3 |
| M28 | 本条关系已确认，其余候选关系不变 | Coordinator | AchievementCorrectionUI | E8 | 返回确认结论与“仅本条”事实 | `AC-IMPL-FR15-06`；FR15-S08 |
| M29 | 显示“仅本条关系为本人确认” | AchievementCorrectionUI | 已获授权学者 | E8 | 显示确认范围，不暗示其余关系同样确认 | `AC-IMPL-FR15-06`；分析推导 |
| M30 | 判定对象范围：本人关系字段 / 成果共享字段 / 他人参与关系 | Coordinator | Coordinator | E5（补录/纠错分支） | 区分可申请对象，他人关系拒绝 | FR07“共享字段和争议归属进入审核”“只修改自己的参与关系”；BR-353 |
| M31 | 拒绝：只可申请修改本人关系 | Coordinator | AchievementCorrectionUI | A4 | 返回越界结论，不写他人关系 | FR07；BR-353；BR-IMP-05 |
| M32 | 显示“不得修改他人关系” | AchievementCorrectionUI | 已获授权学者 | A4 | 显示越界而非受理 | FR07；分析推导 |
| M33 | 记录受控越界尝试 | Coordinator | AuditEvidence | A4 | 记录越界对象与判定依据 | BR-IMP-10；INV-10 |
| M34 | 读取成果当前公开字段与共享字段现值 | Coordinator | WorkEntity | E9 | 提供当前生效字段值，供范围判定与前后值比较 | FR07；§7.1；BR-IMP-04；B-04 |
| M35 | 只读读取来源事实以核验补录元数据，原值保留 | Coordinator | SourceRecord | E10 | 提供来源原值/到达时间/许可，供可核验性判定 | FR07“可核验”；§7.2；BR-IMP-04；INV-01；CL-11 |
| M36 | 判定最低字段与可核验性：类型/名称/来源定位，缺失不造值 | Coordinator | Coordinator | E10 | 判定是否可登记；不可核验转 A5 | §7.1；CL-01/CL-08；BR-IMP-02；B-05 |
| M37 | 拒绝登记：说明缺项与不可核验原因 | Coordinator | AchievementCorrectionUI | A5 | 返回缺项/不可核验结论，不登记请求 | FR07“可核验”；§7.1；B-05 |
| M38 | 显示缺项/不可核验 | AchievementCorrectionUI | 已获授权学者 | A5 | 显示缺项而非受理成功 | 分析推导 |
| M39 | 记录被拒绝的补录/纠错尝试 | Coordinator | AuditEvidence | A5 | 记录缺项与判定依据 | BR-IMP-10；INV-10 |
| M40 | 登记待审纠错请求（类型/目标/前后值/理由/提交者/待审核/版本） | Coordinator | CorrectionRequest | E11 | 建立待审请求并保持公开值与关系状态为旧值 | FR07“进入审核”；`AC-IMPL-FR07-02`；BR-IMP-10；§6.4 |
| M41 | 记录本次请求的证据材料与可见范围（本人＋有权审核者） | Coordinator | ReviewEvidence | E12 | 保存材料与可见范围，限定可见者 | BR-IMP-11；NF06；`AC-IMPL-NF06-01` |
| M42 | 记录待审登记（前后值标记为未生效） | Coordinator | AuditEvidence | E12 | 为待审登记留痕并标记“未生效” | BR-IMP-10；`AC-IMPL-FR07-02` |
| M43 | 已提交待审：公开字段与关系状态保持旧值，本人可查 | Coordinator | AchievementCorrectionUI | E12 | 返回待审结论与“公开未变更”事实 | `AC-IMPL-FR07-02`；BR-IMP-10 |
| M44 | 显示待审状态与“公开未变更” | AchievementCorrectionUI | 已获授权学者 | E12 | 显示待审而非“已公开新值” | `AC-IMPL-FR07-02`；分析推导 |
| M45 | 查询本人提交的补录/纠错请求 | 已获授权学者 | AchievementCorrectionUI | E16（事后查询） | 接收本人请求状态查询 | `AC-IMPL-FR07-02`“处理过程可查” |
| M46 | 请求本人请求状态（请求引用＋提交者上下文） | AchievementCorrectionUI | AchievementCorrectionCoordinator | E16（事后查询） | 转交查询上下文，按可见范围裁决 | `AC-IMPL-FR07-02`；NF06；BR-IMP-11 |
| M47 | 读取本人请求状态、决定理由与版本（仅本人或有权审核者） | Coordinator | CorrectionRequest | E16（事后查询） | 按可见范围提供状态、决定理由与版本 | `AC-IMPL-FR07-02`；BR-IMP-11；NF06 |
| M48 | 状态=待审核/通过/驳回；公开字段与关系状态仍为旧值 | CorrectionRequest | Coordinator | E16（事后查询） | 返回状态与“公开未生效”事实 | `AC-IMPL-FR07-02`；§6.4；B-01 |
| M49 | 读取本人可见的证据摘要（不含材料原件） | Coordinator | ReviewEvidence | E16（事后查询） | 提供摘要与可见范围校验 | BR-IMP-11；NF06；`AC-IMPL-NF06-01` |
| M50 | 返回状态、决定理由与公开未变更事实 | Coordinator | AchievementCorrectionUI | E16（事后查询） | 组合状态与可见摘要返回 | `AC-IMPL-FR07-02` |
| M51 | 显示处理过程与驳回原因（可查） | AchievementCorrectionUI | 已获授权学者 | E16（事后查询） | 显示处理过程，不展示材料原件 | `AC-IMPL-FR07-02`；FR15-S11 E4 |
| M52 | 打开待审请求并提交决定（批准/驳回）与理由 | 有权审核的管理员 | AchievementCorrectionUI | E13 | 接收决定类型与理由 | FR07 参与者“审核管理员”；§11.4；FR14 |
| M53 | 提交审核决定（请求引用＋理由＋已认证请求上下文） | AchievementCorrectionUI | AchievementCorrectionCoordinator | E13 | 转交决定内容与审核者上下文 | FR14；NF03；SRS §8 |
| M54 | 校验审核者身份、账号状态与审核权限 | Coordinator | User | E13 | 提供审核者身份、账号状态与审核权限事实 | NF03；`AC-IMPL-NF03-01`；FR14；FR15-S09 权限规则 |
| M55 | 读取待审内容、证据引用与状态版本 | Coordinator | CorrectionRequest | E13 | 提供待审内容、证据引用与状态版本 | `AC-IMPL-FR07-02`；BR-IMP-10；B-01 |
| M56 | 读取证据材料（仅有权审核者） | Coordinator | ReviewEvidence | E13 | 提供材料供核对，限定可见者 | BR-IMP-11；`AC-IMPL-NF06-01`；FR15-S08 权限规则 |
| M57 | 复核守卫：权限有效 ∧ 理由完整 ∧ 仍为待审核 ∧ 版本未变 | Coordinator | Coordinator | E13 | 四项守卫的合取；不满足转 A10/A9 | FR14；`AC-IMPL-NF03-01`；§6.4；分析推导 |
| M58 | 拒绝该决定：请求状态与公开值不变 | Coordinator | AchievementCorrectionUI | A10 | 返回拒绝结论，状态不变 | FR14；`AC-IMPL-NF03-01` |
| M59 | 显示拒绝原因（无权限/理由必填） | AchievementCorrectionUI | 有权审核的管理员 | A10 | 显示拒绝原因，不复用“已驳回” | 分析推导；NF01 |
| M60 | 拒绝并返回当前状态，不覆盖已有决定 | Coordinator | AchievementCorrectionUI | A9 | 返回“状态已变化”与当前状态 | §6.4；BR-IMP-04/10；`DGM-STATE-02` 过期守卫类比 |
| M61 | 显示“状态已变化”与当前状态 | AchievementCorrectionUI | 有权审核的管理员 | A9 | 显示当前状态，不显示为成功 | 分析推导 |
| M62 | 写入生效规范值（仅批准字段；来源原值与未批准字段保留） | Coordinator | WorkEntity | E14（字段类） | 只更新批准范围内的共享字段生效值 | `AC-IMPL-FR07-02`；`AC-IMPL-FR15-02`；§7.2；BR-IMP-04 |
| M63 | 记录决定=通过（理由/时间/操作者） | Coordinator | CorrectionRequest | E14（字段类） | 置请求状态为通过并记录决定事实 | FR15-S10 A3；BR-IMP-10 |
| M64 | 记录生效（对象/前后值/时间/理由/依据来源=学者申请） | Coordinator | AuditEvidence | E15 | 为字段生效留可追溯留痕 | BR-IMP-10；INV-10；FR15-S10 AC-05 |
| M65 | 已生效：检索/门户/统计/合作网络采用同一生效值 | Coordinator | AchievementCorrectionUI | E15 | 返回生效结论与一致性声明 | NF05；`AC-IMPL-NF05-01/02`；FR15-S12 |
| M66 | 显示生效结果 | AchievementCorrectionUI | 有权审核的管理员 | E15 | 显示已生效（不展示待审内容） | NF05；分析推导 |
| M67 | 该关系状态→已排除（仅本关系） | Coordinator | Authorship | E14（解除类） | 只把该条关系置为已排除 | `AC-IMPL-FR07-01`；FR15-S11；BR-359；SPEC ID-09 |
| M68 | 核对：成果实体、全部来源与其余参与关系保留；已排除不得被自动恢复 | Coordinator | Coordinator | E14（解除类） | 核对“不删成果/来源/他人关系”，并声明不可自动恢复 | FR15-S11 主流程 3；BR-359；INV-04/INV-05；`AC-IMPL-FR15-04` |
| M69 | 记录决定=通过 | Coordinator | CorrectionRequest | E14（解除类） | 置请求状态为通过并记录决定事实 | FR15-S10 A3；BR-IMP-10 |
| M70 | 记录解除（依据/申请编号/前后状态） | Coordinator | AuditEvidence | E15 | 为解除留痕并保留历史证据 | BR-IMP-10；INV-10；FR15-S10 AC-05；FR15-S11 BR-381 |
| M71 | 仅本关系解除，成果与其他合法关系保留 | Coordinator | AchievementCorrectionUI | E15 | 返回解除结论与保留事实 | `AC-IMPL-FR07-01`；FR15-S11；§6.4 |
| M72 | 显示解除结果 | AchievementCorrectionUI | 有权审核的管理员 | E15 | 显示解除结果与其他参与者不受影响 | FR15-S11 AC-03；分析推导 |
| M73 | 记录决定=驳回（理由/时间/操作者） | Coordinator | CorrectionRequest | E14（驳回） | 置请求状态为驳回并记录理由 | `AC-IMPL-FR07-02`；FR15-S11 E4 |
| M74 | 记录驳回（公开值与关系状态不变） | Coordinator | AuditEvidence | E15 | 为驳回留痕并标记公开值未变 | BR-IMP-10；`AC-IMPL-FR07-02` |
| M75 | 已驳回：公开旧值不变，处理过程可查 | Coordinator | AchievementCorrectionUI | E15 | 返回驳回结论与“公开未变”事实 | `AC-IMPL-FR07-02` |
| M76 | 显示驳回结果 | AchievementCorrectionUI | 有权审核的管理员 | E15 | 显示驳回原因，不复用“失败”或“越权” | `AC-IMPL-FR07-02`；分析推导 |

## 3. 分支与守卫小结

| 分支 | 消息 | 守卫/条件 | 业务结果 |
|---|---|---|---|
| A1 未认证/停用 | M8–M10 | 账号状态可用 | 拒绝、无写入、留痕 |
| A2 无对象授权 | M11–M13 | 授权有效 ∧ 目标学者一致 ∧ 关系属于本学者 | 拒绝越权、目标无变化、留痕 |
| A3 已排除/争议 | M16–M18 | 状态不属于已排除/争议 | 拒绝确认、状态与公开值不变、留痕 |
| A7 相反归属主张 | M19–M22 | 无未决冲突 | 关系置争议、进入审核、不置本人确认 |
| E7/E8 本人确认 | M23、M24、M27、M28、M29 | 仅本关系 ∧ 无冲突 | 仅该关系为本人确认、不扩散、留痕 |
| A6 写入失败 | M25–M26 | 写入成功 | 状态保持原值、不报告成功 |
| A4 他人关系 | M31–M33 | 目标属于本人关系 | 拒绝、不写他人关系、留痕 |
| A5 不可核验 | M37–M39 | 最低字段齐备 ∧ 可追溯 | 不登记、说明缺项、留痕 |
| E11/E12 待审登记 | M40–M44 | 可核验 | 生成待审请求、公开值不变、材料可见范围、留痕 |
| 本人查询 | M45–M51 | 本人或有权审核者 | 返回状态/理由/摘要、不含材料原件 |
| A10 审核拒绝（权限/理由） | M58–M59 | 权限有效 ∧ 理由完整 | 拒绝决定、状态不变 |
| A9 状态已变化 | M60–M61 | 仍为待审核 ∧ 版本未变 | 拒绝覆盖、返回当前状态 |
| E14 字段生效 | M62–M66 | 批准 ∧ 仅批准字段 | 生效规范值、来源原值保留、留痕、跨视图一致 |
| E14 解除生效 | M67–M72 | 批准 ∧ 仅本关系 | 关系已排除、成果/来源/他人关系保留、留痕 |
| E14 驳回 | M73–M76 | 决定=驳回 | 请求驳回、公开值与关系状态不变、留痕 |

## 4. 完整性与一致性核对

| 检查 | 结果 | 说明 |
|---|---|---|
| 消息编号 | M1–M76 连续、无缺号 | 脚本核对（08 §4.4） |
| 每条消息有发送者/接收者/事件/职责/依据 | 是 | 表 2 全列齐备 |
| 每个参与类至少收到一条消息 | 是 | UI：M2/M43/M50/M53 等；Coordinator：M2；User：M3/M54；Auth：M4；Scholar：M5；Authorship：M6/M19/M23/M67；WorkEntity：M34/M62；SourceRecord：M35；CorrectionRequest：M40/M47/M55/M63/M69/M73；ReviewEvidence：M41/M49/M56；AuditEvidence：M10/M13/M18/M20/M24/M33/M39/M42/M64/M70/M74 |
| 是否出现他人关系写入消息 | 否 | 他人关系只走 M31（拒绝） |
| 是否出现 `WorkEntity` 新建/删除消息 | 否 | 只有 M34 读、M62 写字段；解除走 M67 且 M68 核对保留 |
| 是否出现 `SourceRecord` 写入/删除消息 | 否 | 只有 M35 只读 |
| 是否出现“确认后其余关系一并升级”消息 | 否 | M27 反向核对；M23 只作用一条关系 |
| 是否出现身份合并/拆分/改派消息 | 否 | A11 为范围边界 |
| 是否出现审核通过后“写成本人确认”的消息 | 否 | M62/M67 只写字段/已排除；本人确认只来自 M23 |
| 失败/拒绝/待审/生效是否分开表达 | 是 | A1/A2/A3/A4/A10（拒绝）、A5（不可核验）、A6（失败）、A9（状态已变化）、M43/M48（待审）、M65/M71（生效） |
| 是否含 UC-06 认领审核 / UC-07 档案维护 / UC-09/10 消歧 | 否 | 只在边界与前置中引用 |
| 是否引入实现技术 | 否 | 只有分析职责与业务消息 |

## 5. 与图的一致性

`DGM-SEQ-13` 的 76 条消息与本表逐条对应；图例中的 M3-M7（守卫）、M23/M27（确认与不扩散）、M40（待审登记）、M62/M67（生效写入）、M68（保留核对）与本表 §3 一致。图中不画 Actor 为系统类，不画 `UserProfile`、`ClaimApplication`、`WorkVersion`、`CollectionTask`、`IdentityCandidate`、`CollaborationEdge` 消息。
