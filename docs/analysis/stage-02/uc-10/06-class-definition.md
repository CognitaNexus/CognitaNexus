# UC-10 分析类职责、属性、关系与分析机制

**版本：** V1.0（2026-10-21）
**方法：** 教材 §5.5.1 职责、§5.5.2 属性、§5.5.3 关系、§5.5.4 分析机制。
**范围：** 只定义 UC-10 能够证实的部分；跨用例统一关系不在此擅自补齐。

## 1. 定义原则

1. “做（Do）”型职责说明对象能执行/协调什么；“知道（Know）”型职责说明对象必须知道/提供什么。
2. 属性只写业务信息，不写数据库字段、主键类型、索引或 API 参数；匹配算法与相似度计算不在此定义。
3. 关系只写有消息或正式业务约束支持的；无依据的跨用例关系留待 R5。
4. 控制类没有长期业务属性；候选详情、判定上下文与影响范围是单次用例的分析状态。
5. 阈值、候选规模、时限、授权转移条件与候选状态集合等未冻结项不伪装成类定义，统一标 `待确认`（B-01～B-08）。

## 2. IdentityVerificationUI（Boundary）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Boundary | §5.4.2；M1/M2/M32/M33/M46/M47/M51/M52/M75/M76；M13/M20/M25/M31/M35/M36/M38/M39/M45/M50/M55/M56/M60/M64/M68/M74/M81/M83/M87/M92/M96/M101/M102/M103 |
| Do | 接收“待归属的署名事实”“候选/争议处理项打开”“判定与理由”“学者说明材料”；显示候选成员与逐项证据、影响范围、可判定动作、拒绝（未认证/越权/状态已变）、保持已排除、候选过多、补充证据请求、争议提示、合并/保持独立/归属确认/排除/拆分结果与“是否已生效” | E1、E8–E13、E18；A1–A3、A7–A10、A12、A14、A17 |
| 候选分析操作（`/`） | `/接收待归属署名事实`、`/显示候选成员与逐项证据`、`/显示影响范围与可判定动作`、`/接收判定与理由`、`/接收身份材料`、`/显示拒绝或越权`、`/显示争议或候选过多提示`、`/显示判定结果与是否已生效` | M1–M2、M13、M20、M25、M31、M32–M33、M35–M36、M38–M39、M45、M46–M47、M50–M51、M55–M56、M60、M64、M68、M74–M76、M81、M83、M87、M92、M96、M101–M103 |
| Know | 当前会话的展示状态：候选引用、关系引用、判定类型与理由、证据与影响范围、结果类型；不持久保存档案证据、候选状态、阈值或审计 | 分析推导 |
| 业务属性 | 无持久业务属性；候选/关系选择、判定与理由、结果展示是短期请求数据 | §5.5.2 |
| 关系 | 单向关联 `IdentityVerificationCoordinator`（M2/M33/M47/M52/M76 请求；其余返回） | 04 消息表；VOPC |
| 不能承担 | 不能判定同名是否同人、外部标识是否充分、候选规模、合并/拆分是否合法、授权是否保持不变；入口是否显示不构成权限 | BR-342；SPEC §6.5；FR14；NF03 |
| 分析机制 | 结果类型可区分（候选/争议/拒绝/越权/失败/已生效）；候选证据与材料不进入公开视图；不回显凭据与他人私人信息 | NF01；BR-IMP-01/11；FR15-S04 A3/E3 |

## 3. IdentityVerificationCoordinator（Control）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Control | §5.4.2；M2–M34、M37、M40–M45、M47–M50、M52–M55、M57–M63、M65–M67、M69–M74、M76–M80、M82–M86、M88–M91、M93–M95、M97–M101 |
| Do | 校验账号状态与治理处置权限；只读来源原值；收集/追加身份证据并剔除私人联系方式；姓名表达归一与别名登记；召回候选档案；执行同名资格守卫与保守判定守卫；生成身份/归属候选；维护候选状态版本；执行判定复核与执行（合并/保持独立/争议/补充证据/改派/排除/拆分）；写入关系状态（不写本人确认）；声明并驱动生效传播；追加审计与返回结果 | E2–E18；A1–A20 |
| 候选分析操作（`/`） | `/校验账号与治理处置权限`、`/只读来源原值`、`/追加身份证据`、`/剔除私人联系方式`、`/归一姓名表达`、`/登记别名候选`、`/召回候选档案`、`/判定同名资格`、`/判定保守判定条件`、`/生成身份候选`、`/生成归属候选`、`/限制候选规模`、`/复核判定守卫`、`/执行合并`、`/执行保持独立`、`/执行争议`、`/执行改派或排除或拆分`、`/写入关系状态`、`/声明生效与传播`、`/追加判定留痕`、`/返回结果` | M3–M31、M34、M41–M45、M47–M50、M52–M101 |
| Know | 本次处理的分析状态：`workRef`、`sourceRecordRef`、`signatureFacts`（署名原值/角色/顺序/机构/外部标识）、`requestContext`（治理管线/管理员/相关学者）、`candidateRef`、`candidateStateVersion`、`evidenceFinding`（命中/冲突/缺失）、`decisionKind`、`reason`、`impactScope`（受影响学者/成果/门户/统计）、`beforeAfter`、`propagationResult`、`outcome` | 分析推导 |
| 业务属性 | 无长期业务属性。分析级状态：`workRef`、`sourceRecordRef`、`signatureFacts`、`requestContext`、`candidateRef`、`evidenceFinding`、`decisionKind`、`impactScope`、`outcome` | §5.5.2；不指定实现存储 |
| 关系 | 与 `IdentityVerificationUI`、`User`（M34）、`ScholarAuthorization`（M78，只读）、`SourceRecord`（M3，只读）、`Scholar`（M5/M8/M14/M17/M42/M70/M93/M98）、`IdentityCandidate`（M26/M28/M41/M53/M58/M61/M65/M72）、`AttributionCandidate`（M27/M77/M85/M90）、`Authorship`（M9/M18/M22/M43/M62/M71/M84/M88/M94）、`WorkEntity`（M44/M97，只读＋声明）、`ReviewEvidence`（M48）、`AuditEvidence`（19 条留痕）的单向关联 | 04 消息表；VOPC |
| 不能承担 | 不承载档案证据（`Scholar`）、候选内容（`IdentityCandidate`/`AttributionCandidate`）、关系状态（`Authorship`）、成果字段（`WorkEntity`）、材料（`ReviewEvidence`）、留痕（`AuditEvidence`）、账号与权限（`User`）；不是采集调度器、去重引擎或实现层 Service | §5.4.1；§5.5.1 |
| 分析机制 | 见 §14：同名非证据门槛、归一仅候选、证据追加、标识作用域与冲突、保守判定与规则门槛、候选与人工核验、审核≠本人确认、状态优先级与已排除不恢复、关系唯一性与幂等、合并治理、拆分与待核验、授权不转移、影响范围与跨视图一致、材料可见性、追加式审计、结构性不扩张 | FR15-S01～S13；SPEC §6.2～§6.5；B-01～B-08 |

**待确认：** 身份判定阈值与规则批准状态（B-03）、候选规模与排序参数（B-04）、候选/争议状态集合与结案载体（B-01）、时限（B-02）、归属候选是否独立成类（B-05）。

## 4. User（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M34；E11；A1、A2 |
| Do | 提供账号标识、账号状态与“治理处置权限”事实，回答“该请求者能否执行身份/归属判定” | NF03；FR14；FR15-S05 权限规则；`AC-IMPL-NF03-01` |
| 候选分析操作（`/`） | `/校验账号状态`、`/提供治理处置权限事实` | M34；A1、A2 |
| Know | 自己的账号标识、账号状态、是否具备治理处置权限 | FR04；FR14；SRS §8 |
| 业务属性 | `accountIdentifier`：账号标识（沿用 UC-04，本 UC 不新增）；`accountStatus`：可用/停用；`governanceScope`：治理处置权限事实（等级/范围划分口径待确认） | FR04；NF03；SRS §8 |
| 关系 | 被 `IdentityVerificationCoordinator` 在 M34 读取；不建立 `User—Scholar` 关联（账号与档案分离） | 05 §2；SRS §12；BR-UC10-01 |
| 不能混淆 | 不是 `Scholar`（可无账号的学术档案）；治理处置权限≠门户对象授权（后者是 `ScholarAuthorization`）；不是审计记录中的“操作者”字段 | FR03/FR05；BR-IMP-03/05；BR-IMP-10 |
| 分析机制 | 持久性；服务端每次校验账号状态与权限；拒绝也留痕 | NF03；FR14；A1/A2 |

## 5. ScholarAuthorization（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M78；E11；A13 |
| Do | 回答“目标档案是否已有有效门户管理授权、被授权主体是谁、范围到哪里”；为“判定不转移/不撤销管理权”提供事实 | FR05；BR-349；FR15-S05 E4；FR15-S10 E4 |
| 候选分析操作（`/`） | `/提供有效授权与被授权主体`、`/拒绝转移或撤销` | M78；A13 |
| Know | 目标 `Scholar` 引用、被授权主体（→`User`）、对象级授权范围、有效性与生效时间 | FR05；`AC-IMPL-FR05-02`；SPEC ID-12 |
| 业务属性 | `targetScholarRef`、`grantedSubjectRef`、`scope`、`validity`、`grantedAt`（沿用 UC-06/UC-08，本 UC 不新增） | FR05；`AC-IMPL-FR05-02` |
| 关系 | 被协调器只读依赖（M78）；其 `0..*:1 User` 与 `→Scholar` 关系已在 UC-06/UC-08 定义（后者多重性未定，SPEC ID-12），本 UC 不重复画边 | 05 §2.3 |
| 不能混淆 | 不是认领申请（`ClaimApplication`）；不是档案字段（`Scholar`）；不是账号状态（`User`） | FR05；BR-IMP-05 |
| 分析机制 | 持久性与只读；判定/合并/拆分/纠正都不改变授权（BR-349）；冲突认领生成处理项而非改权 | BR-349；FR15-S05 E4；A13 |

## 6. SourceRecord（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M3；E2、E10、E15 |
| Do | 只读提供署名原值、机构原值、外部标识原值、身份相关原始字段、许可与到达时间；**不接受**原值改写或删除 | INV-01；§7.2；BR-IMP-04；FR15-S01 前置 |
| 候选分析操作（`/`） | `/提供署名与机构原值`、`/提供外部标识原值`、`/提供许可与到达时间`、`/拒绝原值改写` | M3；A15 |
| Know | `sourceId`、`sourceDomain`、`sourceRecordKey`、`rawValues`（含参与者署名原值与顺序）、`rightsScope`、`arrivalAt`、`dataOrigin`、可达性状态（只读参考） | §7.1/§7.2；FR16；INV-01 |
| 业务属性 | 沿用 UC-02/UC-09：`sourceId`、`sourceDomain`、`sourceRecordKey`、`rawValues`、`rightsScope`、`arrivalAt`、`dataOrigin`、`originalUrl`；本 UC 不新增字段 | §7.1；FR16；INV-01 |
| 关系 | 被 `WorkEntity` 以“出处”引用（`1` : `0..*`）；被协调器只读依赖（M3）；归属候选以 `signatureSourceRef` 属性引用 | 05 §2/§2.3 |
| 不能混淆 | 不是 `Scholar` 的证据集合（规范化证据项）；不是 `ReviewEvidence`（提交材料）；不是 `AuditEvidence`（动作留痕） | INV-01；§7.2；FR15-S08 |
| 分析机制 | 持久性与不可改写；只读消费；人工判定结论不回写原值（BR-357） | INV-01；BR-IMP-04 |

## 7. Scholar（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M5/M8/M14/M17/M42/M70/M93/M98；E3、E4、E6、E9、E11、E15、E17 |
| Do | 承载学术身份事实：稳定引用、公开状态、身份核验状态、姓名表达与别名（含来源）、机构关系及任职期、外部标识（含命名空间/可信度）、合作者、研究领域、追加式证据集合与合并链；接受证据追加、别名登记、新建、合并、拆分与 `identityState` 变化；提供候选召回所需的证据 | FR15-S01/S02/S03/S07；FR03；BR-347 |
| 候选分析操作（`/`） | `/追加证据项`、`/登记别名候选`、`/提供证据集合与合并链`、`/召回候选档案`、`/新建档案`、`/合并证据与别名`、`/拆分为两档案`、`/置身份核验状态`、`/提供公开身份与状态` | M5/M8/M14/M15/M17/M42/M70/M93/M98 |
| Know | 自己的稳定身份引用、公开状态、`identityState`、姓名表达集合（原表达／比对用形式／别名候选／状态）、机构关系及时间与精度、外部标识（体系／命名空间／取值／来源／可信度／冲突标记）、合作者集合、研究领域、证据项（类型／来源／到达时间）、`claimState`（只读）、`mergedIntoRef` | FR15-S01/S02/S03；FR03；`AC-IMPL-FR03-01`；SPEC ID-01/ID-02 |
| 业务属性 | `stableId`：档案稳定引用；`publicState`：公开状态；`identityState`：已核验/待核验/争议；`claimState`：未认领/已认领（**只读**，属 UC-05/06）；`publicName`：对外展示名；`nameExpressions[]`：原表达＋比对用形式＋别名候选＋来源＋到达时间＋`expressionState`（候选/已登记/异常）；`affiliationPeriods[]`：机构规范名/原值＋起止与精度＋来源＋到达时间＋`conflictFlag`；`externalIdentifiers[]`：`scheme`＋`namespace`＋`value`＋来源＋`confidence`＋`conflictFlag`；`coauthorRefs[]`；`researchFields[]`；`evidenceItems[]`：`evidenceKind`＋`sourceRef`＋`arrivalAt`＋`precision`；`mergedIntoRef`：合并链（只追加）；`derivedFromSplitRef`：拆分来源（只追加） | FR15-S01（证据项与来源/到达时间）；FR15-S03（合并并集与合并链）；FR15-S10 A2（拆分）；SPEC ID-01/ID-02/ID-04；BR-325/343/347 |
| 关系 | 被 `IdentityCandidate` 以“候选成员”引用（`2..*`）；被 `AttributionCandidate` 以“候选学者”引用（`1..*`）；被 `Authorship` 以“归属学者”引用（`1`）；被协调器读写（M5/M8/M14/M17/M42/M70/M93/M98） | 05 §2 |
| 不能混淆 | 不是 `User`（账号状态）/`UserProfile`（普通主页）；不是 `Authorship`（一条参与关系）；不是 `IdentityCandidate`（待核验组合）；不承载成果共享字段（`WorkEntity`） | FR03；BR-353；`AC-IMPL-FR03-04`；BR-341 |
| 分析机制 | 持久性；证据追加不覆盖（BR-325/347）；同名不作为键（ID-01/ID-03）；合并留链、拆分留源；`identityState` 与 `claimState` 分别表达（BR-350/FR15-S06） | FR15-S01/S03/S06/S10；SPEC ID-01～ID-04 |
| 不能承担 | 不写档案展示字段（UC-07）、不写 `claimState`（UC-05/06）、不删除（账号停用不删档案） | FR05/FR06；`AC-IMPL-NF03-01` 类 |

**待确认：** 已核验身份字段清单（B-02 类比，UC-07 已登记）、外部标识可信度分级（B-03）、`identityState` 的公开标签口径（UC-03 Q-06）。

## 8. IdentityCandidate（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M26/M28/M41/M53/M58/M61/M65/M72；E10、E11、E13–E15；A8–A10、A12 |
| Do | 保存“若干档案可能属于同一人”的待核验组合：成员、逐项证据命中与冲突、排序依据、候选规模提示、状态与决定依据；接受补充证据、争议、保持独立、合并四种结果 | FR15-S04 主流程 1–3；FR15-S05 主流程 3–4、A1/A2 |
| 候选分析操作（`/`） | `/登记候选成员与逐项证据`、`/提供排序依据（仅供查看）`、`/标注候选过多`、`/置待核验`、`/置争议中`、`/置已排除（保持独立）`、`/置已确认（合并）`、`/记录决定依据`、`/提供状态版本` | M26/M28/M41/M53/M58/M61/M65/M72 |
| Know | 候选组自身：成员档案引用（≥2）、逐项证据比对结果（姓名表达／机构／外部标识／合作者／领域：命中/冲突/缺失）、排序依据、候选规模提示、状态、决定依据、状态版本、生成时间 | FR15-S04；FR15-S05；SPEC §6.3 |
| 业务属性 | `candidateRef`；`memberScholarRefs[]`（≥2）；`evidenceComparison[]`：`evidenceKind`＋`finding`（命中/冲突/缺失）＋`detail`；`rankingBasis`（仅供人工查看顺序）；`candidateScaleNote`（如“候选过多，需人工筛查”）；`candidateState`：`待核验/已确认（合并）/已排除（保持独立）/争议中`；`decisionBasis`；`stateVersion`；`createdAt` | FR15-S04 主流程 1–3、A1、E1–E4；FR15-S05 主流程 3–4；B-01 |
| 关系 | `0..* → 2..* Scholar`（候选成员）；被协调器创建与推进 | 05 §2/§2.2 |
| 不能混淆 | 不是 `Scholar`（候选存在时档案仍为独立两个）；不是合并结论（“已排除（保持独立）”也是候选结论）；不是 `AttributionCandidate`（对象是档案，不是署名） | FR15-S03/S04；`AC-IMPL-FR15-01/03` |
| 分析机制 | 持久性；候选不改变公开归属结论；关闭后同一组合不再自动生成（除新证据）；候选规模截断但判定证明不丢 | FR15-S04 主流程 4、E1/E2；FR15-S05 主流程 4；BR-346 |

**待确认：** `待补充证据` 是否独立状态（B-01）；排序依据的披露方式（B-04）。

## 9. AttributionCandidate（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M27/M77/M85/M90；E10、E13、E15；B-05 |
| Do | 保存“一条署名归属未定”的待核验问题：署名原值、角色、顺序、候选学者集合、逐项证据；接受确认归属、排除两种结果，并记录依据 | `AC-IMPL-FR15-01`（成果关系进入候选）；FR15-S07 E1（对照）；FR15-S10 主流程 3 |
| 候选分析操作（`/`） | `/登记署名与候选学者`、`/登记逐项证据`、`/提供候选与版本`、`/置已确认归属`、`/置已排除`、`/记录依据与关联关系` | M27/M77/M85/M90 |
| Know | 自身：`workRef`（归属成果）、`signatureRaw`（署名原值）、`role`、`order`（含“顺序未知”）、`candidateScholarRefs[]`（≥1）、`evidenceComparison[]`、`candidateState`（`待核验/已确认归属/已排除/争议`）、`decisionBasis`、`confirmedRelationRef`（0..1）、`signatureSourceRef`、`authorizationRef`（只读）、`stateVersion` | `AC-IMPL-FR15-01`；FR15-S07 主流程 1、A1、E1；FR15-S10 主流程 3 |
| 业务属性 | `candidateRef`、`workRef`、`signatureRaw`、`role`、`order`、`candidateScholarRefs[]`、`evidenceComparison[]`、`candidateState`、`decisionBasis`、`confirmedRelationRef`（`0..1`）、`signatureSourceRef`、`authorizationRef`、`stateVersion`、`createdAt` | 同 `Know`；B-05 |
| 关系 | `0..* → 1..* Scholar`（候选学者）；`signatureSourceRef`/`confirmedRelationRef` 以属性表达（05 §2.3） | 05 §2 |
| 不能混淆 | 不是 `Authorship`（关系已指向一位学者）；不是 `IdentityCandidate`（对象是署名而非档案）；不是 `IdentityEvidence`（证据项在 `Scholar`） | BR-353；FR15-S07 E1；B-05 |
| 分析机制 | 持久性；归属未定不建立关系（不静默分配）；确认后才产生/改派关系且写 `自动关联`；排除后署名原值仍保留在来源记录 | `AC-IMPL-FR15-01`；SPEC §6.4；FR15-S09 A4 |

**待确认：** 与 `Authorship(待核验)` 的表达分工（B-05）；`order` 未知的标注口径（FR15-S07 A1 已要求显式标注）。

## 10. Authorship（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M9/M10/M18/M22/M43/M62/M71/M84/M88/M94；E5、E8、E9、E11、E15、E16；A6、A7、A9、A16 |
| Do | 承载一条“学者—成果”参与关系：角色、顺序、来源证据引用、独立状态与状态历史；接受 `自动关联/待核验/争议/已排除` 写入；提供既有状态供排除检查；被合并时迁移、被拆分时按证据恢复 | FR15-S07；FR15-S09；FR15-S10；FR15-S11 |
| 候选分析操作（`/`） | `/提供角色与顺序`、`/提供关系状态`、`/建立关系（自动关联）`、`/建立关系（待核验）`、`/置争议`、`/置已排除`、`/迁移归属`、`/恢复或置待核验`、`/提供受影响关系集合` | M9/M10/M18/M22/M43/M62/M71/M84/M88/M94 |
| Know | 自己的归属学者、归属成果、角色、顺序（或“顺序未知”）、来源证据引用、状态与状态历史、管理员审核证据引用、公开证据标记 | FR15-S07；FR15-S08；§6.4；§7.2 |
| 业务属性 | `participantDisplay`（公开身份显示名或原署名）、`role`、`order`（适用时；未知须显式标注）、`relationState`（`自动关联/本人确认/待核验/争议/已排除`）、`evidenceRefs`（来源/审核/本人确认三类分别标记）、`evidencePublic`、`stateHistory[]`（原状态/目标状态/触发事件/时间）、`attributionCandidateRef`（如由候选确认产生） | FR15-S07；FR15-S08；FR15-S09；§6.4；SPEC ID-06/ID-10/ID-11 |
| 关系 | `0..* → 1 Scholar`（归属学者）；`0..* → 1 WorkEntity`（归属成果）；被协调器读写 | 05 §2；BR-353 |
| 不能混淆 | 不是成果属性（排除不删成果）；不是合作边（FR12/IN01 派生）；管理员审核≠本人确认；不是 `Scholar` 档案 | INV-05；§6.4；FR12；§12 |
| 不能承担 | 本 UC **不写** `本人确认`（只由 UC-08 本人事件产生）；不新建第二条同对关系；不删除关系（只置 `已排除`） | SPEC ID-06/ID-10/ID-11；FR15-S11；A18 |
| 分析机制 | 状态优先级（已排除>争议>待核验>本人确认>自动关联）；已排除不被自动恢复；关系唯一性；三类证据分离；状态变化只追加历史 | §6.4；BR-356；ID-06/ID-11；`DGM-STATE-05` |

## 11. WorkEntity（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M44/M97；E11、E17 |
| Do | 只读提供目标成果引用、类型与公开状态，用于计算影响范围；接收“按生效关系更新参与展示”的声明 | INV-02/04；BR-341；FR15-S12 |
| 候选分析操作（`/`） | `/提供成果引用与类型`、`/提供公开状态`、`/接收参与展示更新声明` | M44/M97 |
| Know | 稳定成果引用、类型、公开状态与生效关系集合（只读视图） | FR02；§7.1；INV-02/04 |
| 业务属性 | 沿用 UC-02/UC-03 的只读子集：`stableId`、`workType`、`publicState`；本 UC 不新增、不改写共享字段 | §7.1；UC-02 06 §6 |
| 关系 | `1 → 0..* SourceRecord`（出处）；被 `Authorship` 以“归属成果”引用（`1`） | 05 §2；INV-02 |
| 不能混淆 | 不是参与关系（`Authorship`）；不是来源记录（`SourceRecord`）；不是版本（`WorkVersion`，属 UC-09） | INV-02/04；BR-341 |
| 分析机制 | 只读与结构性不扩张：身份合并不改变成果实体数；排除/拆分不删除成果与来源 | BR-341；FR15-S11；INV-04 |

## 12. ReviewEvidence（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M48；E12、E13、E18；A18 |
| Do | 承载相关学者或审核方提交的说明与佐证材料及可见范围（提交者＋有权审核者）；向有权审核者提供核对内容，向提交者提供摘要；不改变关系或候选状态 | FR15-S05 权限规则；BR-IMP-11；NF06；`AC-IMPL-NF06-01` |
| 候选分析操作（`/`） | `/登记材料与可见范围`、`/提供审核可见内容`、`/提供本人摘要`、`/拒绝越权读取` | M48；A18 |
| Know | 材料项与说明、提交者、提交时间、关联候选、可见范围 | FR15-S05；BR-IMP-11 |
| 业务属性 | `evidenceItems[]`/`materialRef`（材料引用）、`submittedBy`、`submittedAt`、`candidateRef`、`visibilityScope`（提交者＋有权审核者） | BR-IMP-11；NF06；`AC-IMPL-NF06-01` |
| 关系 | 由协调器创建（M48）；候选以 `evidenceRefs` 属性引用（不画实体关联） | 05 §2.3 |
| 不能混淆 | 不是 `SourceRecord`（来源原值）；不是 `AuditEvidence`（动作留痕）；不是“本人确认”证据（ID-06） | FR15-S08；SPEC ID-10；`AC-IMPL-FR15-03` |
| 分析机制 | 可见性最小化；材料不进入公开视图；提交材料不改变状态、不构成本人确认 | BR-IMP-11；NF06；A18 |

## 13. AuditEvidence（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M6/M12/M19/M24/M30/M37/M40/M49/M57/M59/M63/M67/M73/M80/M82/M86/M91/M95/M100 |
| Do | 保存可追溯留痕：证据剔除、自动关联、候选生成、拒绝/越权、材料登记、补充证据请求、争议、合并、保持独立、改派、排除、拆分、生效与传播结果；覆盖“被拒绝、未写入”的尝试 | BR-IMP-10；INV-10；FR15-S08/S12；BR-381/382 |
| 候选分析操作（`/`） | `/追加留痕`、`/提供判定依据与前后值`、`/拒绝被普通用户读取` | M6–M100；FR14 |
| Know | 自己记录的：操作者身份与类型（系统/管理员/相关学者）、动作种类、对象引用、理由或结果、前后值、影响范围、时间 | BR-IMP-10；FR15-S10 主流程 4 |
| 业务属性 | `actorIdentity`、`actorKind`（系统/管理员/相关学者）、`actionKind`（证据剔除/自动关联/候选生成/拒绝/越权/材料登记/补充证据/争议/合并/保持独立/改派/排除/拆分/生效/传播）、`targetRefs`、`reasonOrOutcome`、`beforeAfter`、`impactScope`、`occurredAt` | BR-IMP-10；INV-10；FR15-S12 主流程 4；D-13 |
| 关系 | 由协调器创建；指向对象写成属性（05 §2.3） | 05 §2；BR-IMP-10 |
| 不能混淆 | 不是 `ReviewEvidence`（材料）；不是候选状态（`IdentityCandidate`/`AttributionCandidate`）；不是关系状态（`Authorship`） | FR15-S08；SPEC ID-10；BR-IMP-11 |
| 分析机制 | 追加不覆盖；覆盖拒绝与失败；无审计不报告成功；不含凭据与材料原文；查询与保留期限属 UC-17（D-09） | BR-IMP-10；INV-10；A20 |

## 14. 关系定义汇总

| 关系 | 角色/方向 | 多重性 | 来源 | 状态 |
|---|---|---|---|---|
| UI → Coordinator | UI 请求/显示 | 不定义持久多重性 | M2/M33/M47/M52/M76 等 | 已定义（单向关联） |
| Coordinator → User | 校验账号与权限 | 不定义持久多重性 | M34 | 已定义 |
| Coordinator ⇢ ScholarAuthorization | 只读确认授权 | 不定义持久多重性 | M78 | 已定义（只读依赖） |
| Coordinator ⇢ SourceRecord | 只读读取原值 | 不定义持久多重性 | M3 | 已定义（只读依赖） |
| Coordinator → Scholar | 读写档案与证据 | 不定义持久多重性 | M5/M8/M14/M17/M42/M70/M93/M98 | 已定义 |
| Coordinator → IdentityCandidate | 创建/推进候选 | 不定义持久多重性 | M26/M28/M41/M53/M58/M61/M65/M72 | 已定义 |
| Coordinator → AttributionCandidate | 创建/推进候选 | 不定义持久多重性 | M27/M77/M85/M90 | 已定义 |
| Coordinator → Authorship | 读写关系状态 | 不定义持久多重性 | M9/M18/M22/M43/M62/M71/M84/M88/M94 | 已定义 |
| Coordinator ⇢ WorkEntity | 只读＋传播声明 | 不定义持久多重性 | M44/M97 | 已定义（只读依赖） |
| Coordinator → ReviewEvidence | 创建材料 | 不定义持久多重性 | M48 | 已定义 |
| Coordinator → AuditEvidence | 创建留痕 | 不定义持久多重性 | 19 条留痕消息 | 已定义 |
| IdentityCandidate → Scholar | 候选成员 | `0..*` : `2..*` | FR15-S04 A1；M26 | 已定义 |
| AttributionCandidate → Scholar | 候选学者 | `0..*` : `1..*` | `AC-IMPL-FR15-01`；M27 | 已定义 |
| Authorship → Scholar | 归属学者 | `0..*` : `1` | BR-353；ID-05/ID-11 | 已定义（沿用 UC-02/UC-08） |
| Authorship → WorkEntity | 归属成果 | `0..*` : `1` | BR-353；ID-05/ID-11 | 已定义（沿用 UC-02/UC-08） |
| WorkEntity → SourceRecord | 出处 | `1` : `0..*` | INV-02；FR15-S11 | 已定义（沿用 UC-02） |
| AttributionCandidate → Authorship | 确认后关系 | `0..1`（属性表达） | FR15-S10 主流程 3 | 属性表达（05 §2.3） |
| ScholarAuthorization → User / → Scholar | 被授权主体/目标档案 | `0..*:1` / 未定 | FR05；SPEC ID-12 | 已在 UC-06/UC-08 定义，本 UC 不重复画边 |

## 15. 分析机制汇总

| 机制 | 作用对象 | 业务问题 | 本 UC 可证实的约束 | 未决定/后续 |
|---|---|---|---|---|
| 同名非证据门槛 | Coordinator、IdentityCandidate、AttributionCandidate | 仅凭姓名合并或确定归属 | 姓名相同/相似/缩写/译名只召回候选；`AC-IMPL-FR15-01/03` | 姓名相似阈值（B-03） |
| 归一仅候选 | Coordinator、Scholar | 归一结果替换展示名或直接合并 | 只生成比对用形式与别名候选，展示名不变 | 译名/缩写等价声明流程（FR15-S02 A2） |
| 证据追加与来源时效 | Scholar | 证据被覆盖、无法回溯 | 逐项记来源与到达时间，历史不覆盖 | 证据失效标注流程（FR14） |
| 私人联系方式剔除 | Coordinator、AuditEvidence | 私人手机/邮箱被用作消歧证据 | 不采集、登记剔除、不展示 | 剔除登记保留期限（D-09） |
| 标识作用域与冲突 | Coordinator、Scholar | 标识被复用或跨体系误配 | 记来源/命名空间/可信度；冲突标待核验、不自动合并 | 可信度分级与充分性（B-03） |
| 保守判定门槛 | Coordinator | 未批准规则被用于自动合并 | 条件（a）∨（b）且规则已批准；否则转候选 | 规则批准状态与参数（B-03；SPEC §6.5） |
| 候选与人工核验 | IdentityCandidate、AttributionCandidate | 证据不足仍出结论或被任选 | 只生成候选、不建立确定归属；候选可关闭但不改变档案 | 状态集合与结案载体（B-01）、候选规模（B-04） |
| 审核≠本人确认 | Coordinator、Authorship | 管理员判定被写成本人确认 | 人工核验只写 `自动关联`＋独立审核证据；本 UC 无写 `本人确认` 的消息 | 与 UC-08 的入口分工（B-08） |
| 状态优先级与已排除不恢复 | Authorship | 低优先级证据覆盖高优先级状态 | 已排除>争议>待核验>本人确认>自动关联；已排除不被自动恢复 | 争议结案与恢复入口（B-01/B-02） |
| 关系唯一性与幂等 | Authorship、Coordinator | 同一对产生多条关系 | 同 `Scholar`–`WorkEntity` 只有一条逻辑关系；重复证据并入 | — |
| 合并治理 | Scholar、IdentityCandidate | 丢证据、部分合并、循环或重复条目 | 证据/别名并集、合并链保留、同成果同角色去重、失败整体回滚 | 合并后重分配细则（FR15-S10 A1 已定义单条情形） |
| 拆分与待核验 | Scholar、Authorship | 拆分后擅自分配关系 | 按证据归属；无法确定置 `待核验` | 再判定入口与时限（B-06） |
| 授权不转移 | ScholarAuthorization、Coordinator | 判定/合并顺带改管理权 | 只读确认；冲突认领生成处理项，不改授权 | 授权转移与申诉条件（SPEC ID-12；B-07） |
| 影响范围与跨视图一致 | Coordinator、WorkEntity、Scholar | 门户/统计/网络读到不同归属 | 声明同一生效关系、逐视图记录传播时间、未生效提示管理员 | 传播时限（B-02；NF05 实测 NOT RUN） |
| 材料可见性与最小化 | ReviewEvidence、Coordinator | 材料被公开或被当作确认 | 仅提交者＋有权审核者可见；材料不改变状态 | 材料最低集合（D-06 类比） |
| 追加式审计 | AuditEvidence | 判定无法追溯或被覆盖 | 依据/操作者/时间/前后值/影响范围；只追加；无审计不报成功 | 保留期限（D-09；UC-17） |
| 结构性不扩张 | 全体 | 身份流程制造采集合并成果、删除来源或建边 | 不合并/删除成果、不改共享字段、不建合作边、不做本人确认与认领审核 | 与 UC-08/09/14 的入口分工（B-08） |

## 16. 与其他 UC 的边界

- **UC-05/UC-06**：认领申请、证据审核、授权授予不在本 UC；本 UC 只读 `ScholarAuthorization` 判断“是否已认领”并保证不改动（BR-349）。
- **UC-07**：Scholar 档案展示字段的授权维护与待审变更不在本 UC；本 UC 只写身份证据、`identityState`、合并/拆分。
- **UC-08**：本人确认、补录、纠错申请与其审核不在本 UC；`本人确认` 状态只由 UC-08 的本人事件写入。
- **UC-09**：成果实体去重、清洗、版本归并不在本 UC；身份合并不改变成果实体数（BR-341）。
- **UC-14/UC-15**：合作边与论文最短路径不在本 UC；本 UC 只产出生效关系状态并声明派生结果采用同一版本。
- **UC-17**：待办队列、争议结案、认领冲突处理项与审计查询不在本 UC；本 UC 只置状态与写留痕。
- **UC-02/UC-03/UC-11**：成果详情、公开门户、统计口径的展示规则不在本 UC；本 UC 只保证“待核验/争议不展示为已确认”。
- **UC-22**：普通主页资料编辑不在本 UC；`UserProfile` 与 `Scholar` 不按姓名合并。
