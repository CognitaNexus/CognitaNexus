# UC-10 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-11-identity.puml`](diagrams/DGM-VOPC-11-identity.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-11-identity.svg) · [PNG](diagrams/generated/DGM-VOPC-11-identity.png)
**方法：** 教材 §5.4.4：VOPC 只来自本用例实现的顺序图对象，关系来自消息和业务约束；不是全局 BCE。

## 1. 参与类集合

VOPC 只包含以下 12 个类：

| 类 | 类型 | 来自顺序图对象 | 事件流依据 |
|---|---|---|---|
| `IdentityVerificationUI` | Boundary | M1、M2、M32、M33、M46、M47、M51、M52、M75、M76（接收）；M13、M20、M25、M31、M35、M36、M38、M39、M45、M50、M55、M56、M60、M64、M68、M74、M81、M83、M87、M92、M96、M101、M102、M103（返回） | E1、E8–E12、E18；A1–A3、A7–A10、A12、A14、A17 |
| `IdentityVerificationCoordinator` | Control | M2–M34、M37、M40–M45、M47–M50、M52–M55、M57–M63、M65–M67、M69–M74、M76–M80、M82–M86、M88–M91、M93–M95、M97–M101 | E2–E18；A1–A20 |
| `User` | Entity | M34（接收） | E11；A1、A2 |
| `ScholarAuthorization` | Entity | M78（接收，只读） | E11；A13 |
| `SourceRecord` | Entity | M3（接收，只读） | E2、E10、E15 |
| `Scholar` | Entity | M5、M8、M14、M17、M42、M70、M93、M98（接收）；M15（应答） | E3、E4、E6、E9、E11、E15、E17；A14 |
| `IdentityCandidate` | Entity | M26、M28、M41、M53、M58、M61、M65、M72（接收） | E10、E11、E13–E15；A8–A10、A12 |
| `AttributionCandidate` | Entity | M27、M77、M85、M90（接收） | E10、E13、E15；B-05 |
| `Authorship` | Entity | M9、M18、M22、M43、M62、M71、M84、M88、M94（接收）；M10（应答） | E5、E8、E9、E11、E15、E16；A6、A7、A9、A16 |
| `WorkEntity` | Entity | M44、M97（接收） | E11、E17 |
| `ReviewEvidence` | Entity | M48（接收） | E12、E13、E18；A18 |
| `AuditEvidence` | Entity | M6、M12、M19、M24、M30、M37、M40、M49、M57、M59、M63、M67、M73、M80、M82、M86、M91、M95、M100（接收） | E3、E5、E8–E10、E12、E15、E18；A1、A2、A7–A13、A15、A17 |

三位参与者（治理管线、管理员、相关学者）不进入 VOPC：参与者不是系统内部类（教材 §5.4.4）。其中“系统”参与者由 `IdentityVerificationCoordinator` 实现，不另设边界或控制类。

## 2. 关系与来源

| 关系 | 类型 | 来自消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `IdentityVerificationUI --> IdentityVerificationCoordinator` | 单向关联/消息关系 | M2/M33/M47/M52/M76 请求；M13/M20/M25/M31/M45/M50/M55/M60/M64/M68/M74/M81/M83/M87/M92/M96/M101 返回 | UI 请求；Coordinator 返回结果 | 不定义持久多重性 | UI 无持久业务属性；按消息方向表达一次用例会话的协作 |
| `Coordinator --> User` | 单向关联/使用关系 | M34 | 索取账号标识、账号状态与治理处置权限事实 | 不定义持久多重性 | 协调器不是账号容器；不新建账号属性 |
| `Coordinator ..> ScholarAuthorization` | 单向**只读**依赖 | M78 | 只读确认既有授权与被授权主体 | 不定义持久多重性 | 虚线表示只读：不创建/转移/撤销授权（BR-349；SPEC ID-12） |
| `Coordinator ..> SourceRecord` | 单向**只读**依赖 | M3 | 读取署名/机构/标识原值、许可与到达时间 | 不定义持久多重性 | 虚线表示只读：不写、不删、不改原值（INV-01） |
| `Coordinator --> Scholar` | 单向关联/读-写关系 | M5/M8 追加证据与别名；M14 召回；M17 新建；M42 读取；M70 合并；M93 拆分；M98 门户投影 | 读写身份档案与证据集合 | 不定义持久多重性 | 不写 `claimState`（UC-05/06）、不写档案展示字段（UC-07） |
| `Coordinator --> IdentityCandidate` | 单向关联/创建-推进关系 | M26 生成；M28 规模限制；M41/M53 读取；M58 补充证据；M61 争议；M65 保持独立；M72 已合并 | 创建候选、读取、写入候选状态 | 不定义持久多重性 | 候选≠合并；状态集合待确认（B-01） |
| `Coordinator --> AttributionCandidate` | 单向关联/创建-推进关系 | M27 生成；M77 读取；M85 已确认归属；M90 已排除 | 创建归属候选、读取、写入状态 | 不定义持久多重性 | 归属未定≠关系；B-05 |
| `Coordinator --> Authorship` | 单向关联/读-写关系 | M9/M43 读取；M18 待核验；M22/M84 自动关联；M62 争议；M71 迁移；M88 已排除；M94 恢复/待核验 | 读取关系；写入状态并追加证据 | 不定义持久多重性 | **不写** `本人确认`（ID-06/ID-10）；不新建第二条同对关系（ID-11） |
| `Coordinator ..> WorkEntity` | 单向**只读＋传播声明**依赖 | M44 只读读取；M97 参与展示更新声明 | 读取成果类型与公开状态；声明按生效关系更新参与展示 | 不定义持久多重性 | 虚线表示不写共享字段、不新建、不删除、不合并成果（BR-341） |
| `Coordinator --> ReviewEvidence` | 单向关联/创建-读取关系 | M48 | 保存材料与可见范围 | 不定义持久多重性 | 材料不进入公开视图；不构成本人确认 |
| `Coordinator --> AuditEvidence` | 单向关联/创建关系 | M12/M19/M24/M30/M37/M40/M49/M57/M59/M63/M67/M73/M80/M82/M86/M91/M95/M100 | 记录判定、拒绝、越权、争议、合并、拆分、生效与传播留痕 | 不定义持久多重性 | 审计指向的对象是属性，不另画实体关联（§2.3） |
| `IdentityCandidate "0..*" --> "2..*" Scholar` | 业务关联 | FR15-S04 主流程 1、A1；`AC-IMPL-FR15-01/03`；M26 | 候选成员（档案组合） | `0..*` : `2..*` | 一个档案可参与多个候选组合；一个身份候选至少涉及两个档案 |
| `AttributionCandidate "0..*" --> "1..*" Scholar` | 业务关联 | `AC-IMPL-FR15-01`；FR15-S07 E1；M27/M77 | 候选学者 | `0..*` : `1..*` | 一条署名至少有一个候选学者；一位学者可出现在多条署名候选中 |
| `Authorship "0..*" --> "1" Scholar` | 业务关联 | BR-353；FR15-S07；SPEC ID-05/ID-11；M9/M84/M88 | 归属学者 | `0..*` : `1` | 一位学者可有多条关系；一条关系只归一位学者（沿用 UC-08 §2.2） |
| `Authorship "0..*" --> "1" WorkEntity` | 业务关联 | BR-353；ID-05/ID-11；M43/M97 | 归属成果 | `0..*` : `1` | 一项成果可有多条参与关系；一条关系只属于一个成果（沿用 UC-02/UC-08） |
| `WorkEntity "1" --> "0..*" SourceRecord` | 业务关联 | INV-02；§7.2；M3/M89 | 出处 | `1` : `0..*` | 成果可有多条来源、可暂无（治理异常态）；排除/拆分都不删除来源（FR15-S11） |

### 2.1 为什么控制/边界到实体的关联不标持久多重性

`IdentityVerificationUI`/`IdentityVerificationCoordinator` 到实体的关联按**消息方向**表达协作，不是长期业务对象链接。为它们标 `1..*` 会把一次用例的协调关系误解为持久聚合关系。因此本 VOPC 只对业务事实之间的关联给出多重性：`IdentityCandidate→Scholar`、`AttributionCandidate→Scholar`、`Authorship→Scholar`、`Authorship→WorkEntity`、`WorkEntity→SourceRecord` 五条。

### 2.2 为什么身份候选标 `2..*`、归属候选标 `1..*`

- `FR15-S04 主流程 1`“生成身份候选，记录涉及的档案与全部比对证据”、`A1`“候选涉及两个以上档案——系统生成一个候选组”，说明身份候选的成员是**至少两个**档案；单个档案谈不上“是否同一人”。
- `AC-IMPL-FR15-01`“成果关系进入候选”与 `FR15-S07 E1` 只覆盖“无候选档案→新建＋待核验”，说明归属候选出现在“有候选但无法唯一确定”时，至少列出 **1** 个候选学者（唯一候选但证据冲突也落入此路径，见 A4）。
- 两条边都是**组合事实**（候选由档案组成），不是“候选等同于档案”：候选关闭后档案仍存在。

### 2.3 为什么若干指向写成属性而不是实体关联

| 事实 | 表达方式 | 理由 |
|---|---|---|
| 归属候选的署名原值来源 | `AttributionCandidate.signatureSourceRef` | 顺序图中没有 `AttributionCandidate → SourceRecord` 的消息；署名原值由 M3 一次读出后写入候选（与 UC-08 `CorrectionRequest.evidenceRefs` 同构） |
| 归属候选确认后建立/改派的关系 | `AttributionCandidate.confirmedRelationRef`（`0..1`） | 候选被排除时可无关系；画成实体关联会与 `Authorship→Scholar` 的归属方向重复表达同一事实 |
| 归属候选的只读授权引用 | `AttributionCandidate.authorizationRef` | M78 只读确认；不改授权，不需要实体关联 |
| 候选的评审材料 | `evidenceRefs`（属性） | 材料由协调器在 M48/M41 直接寻址；候选与材料之间无消息（与 UC-07/UC-08 一致） |
| 审计指向的对象 | `AuditEvidence.targetRefs`＋前后值 | 审计必须覆盖被拒绝、未写入的对象；本 UC 无 `AuditEvidence → Scholar/Authorship` 消息（BR-IMP-10） |
| `ScholarAuthorization` 的业务关联 | 由 UC-06/UC-08 定义（`0..*:1 User`；`→Scholar` 多重性未定，SPEC ID-12） | 本 UC 只读，重复画边会暗示本 UC 参与授权的建立/变更 |
| `Scholar` 的合并链 | `mergedIntoRef`＋`AuditEvidence` | 合并是追加式历史，不画自关联（与 UC-09 §2.3 的合并链同一取舍；BR-347/382） |

## 3. 从顺序图到 VOPC 的对应

| 顺序图对象 | VOPC 类 | 保留/删除理由 |
|---|---|---|
| 治理管线（Actor） | 不保留 | 参与者不是系统类；只提供 M1 输入 |
| 管理员（Actor） | 不保留 | 参与者不是系统类 |
| 相关学者（Actor） | 不保留 | 参与者不是系统类；只提交 M46 材料、接收 M103 说明 |
| IdentityVerificationUI | 保留 | 接收 M1/M2/M32/M33/M46/M47/M51/M52/M75/M76，返回 M13/M20/M25/M31/M35/M36/M38/M39/M45/M50/M55/M56/M60/M64/M68/M74/M81/M83/M87/M92/M96/M101/M102/M103 |
| IdentityVerificationCoordinator | 保留 | 协调 M3–M12、M14–M31、M34–M45、M47–M101 全部守卫、判定、写入与留痕 |
| User | 保留 | 接收 M34 |
| ScholarAuthorization | 保留 | 接收 M78（只读） |
| SourceRecord | 保留 | 接收 M3（只读） |
| Scholar | 保留 | 接收 M5/M8/M14/M17/M42/M70/M93/M98，应答 M15 |
| IdentityCandidate | 保留 | 接收 M26/M28/M41/M53/M58/M61/M65/M72 |
| AttributionCandidate | 保留 | 接收 M27/M77/M85/M90 |
| Authorship | 保留 | 接收 M9/M18/M22/M43/M62/M71/M84/M88/M94，应答 M10 |
| WorkEntity | 保留 | 接收 M44/M97 |
| ReviewEvidence | 保留 | 接收 M48 |
| AuditEvidence | 保留 | 接收 M6/M12/M19/M24/M30/M37/M40/M49/M57/M59/M63/M67/M73/M80/M82/M86/M91/M95/M100 |
| 无其他对象 | — | 没有 `UserProfile`/`ClaimApplication`/`ScholarChangeRequest`/`CorrectionRequest`/`WorkVersion`/`DedupCandidate`/`CollaborationEdge`/`PendingTodo`/`Session` 的消息 |

## 4. 排除项与理由

- `UserProfile` / 第二个公开主页：本 UC 无普通主页消息；`UserProfile` 与 `Scholar` 不按姓名合并（FR03；`AC-IMPL-FR03-04`；UC-22/UC-03）。
- `ClaimApplication` / `ClaimCoordinator` / `ClaimReviewCoordinator`：认领申请与审核属 UC-05/UC-06；本 UC 只读授权（FR05；`AC-IMPL-FR05-02/04`）。
- `ScholarChangeRequest`：UC-07 的档案字段待审变更；本 UC 不写展示字段（FR06 vs FR15）。
- `CorrectionRequest`：UC-08 的成果关系纠错/补录待审对象；本 UC 的判定对象是身份候选、归属候选与关系状态（FR07 vs FR15；B-08）。
- `WorkVersion` / `DedupCandidate` / `CleanRecord` / `MergeDecision`：版本、去重与清洗属 FR09/UC-09；身份合并不改变成果实体（BR-341）。
- `CollaborationEdge` / `GraphCoordinator`：建边、强度与论文子图属 FR12/IN01（UC-14/UC-15）；本 UC 只产出生效关系状态。
- `DisputeCase` / `PendingTodo` / `ClaimConflictItem`：争议结案、待办与认领冲突处理项的载体属 FR14/UC-17；本 UC 只置“争议”并记录需生成处理项（A13；B-01）。
- `Notification` / `SlaTimer`：通知渠道与时限未定义（B-02）。
- `Session` / `CredentialContext`：已认证请求上下文是 M2/M33/M47/M52/M76 的输入；基线未定义会话对象（B-09）。
- `ReviewerRole` / `PermissionMatrix` / `ApprovalChain`：基线只区分“是否具备对应治理处置权限”（FR14；NF03）。
- `AliasMap` / `NameNormalizer` / `SimilarityScorer` / `ThresholdPolicy` / `ExternalIdAuthority`：归一是协调器内的分析操作，阈值与充分性未冻结（SPEC §6.5；B-03/B-04），不能建模为已批准实体。
- 独立 `IdentityEvidence` / `AffiliationPeriod` / `ExternalIdentifier`：由 `Scholar` 的追加式证据集合与结构化属性表达；无独立消息寻址（05 §2.3 同类取舍；FR15-S01）。
- 独立 `MergeRecord` / `SplitRecord`：合并链与拆分历史由 `mergedIntoRef`＋`AuditEvidence` 表达（BR-347/382）。
- `EvidenceFile` / `Attachment`：材料存储形态属实现设计。
- 独立 `EffectiveVersion`：生效状态以 `Authorship.relationState` 与 `Scholar.identityState` 表达（§6.4；NF05）。
- `AuditCoordinator`（全局 `AuditCtl`）：审计查询与集中治理属 UC-17；本 UC 只需写入留痕。
- 展示投影/DTO（`CandidateView`/`EvidenceDTO`）：交互态，不是业务事实。

## 5. VOPC 完整性检查

| 检查 | 结果 |
|---|---|
| 只包含 UC-10 参与类 | 是，12 个 |
| 每个类有事件流/消息依据 | 是，见表 1/表 3 |
| 每条关系可追溯到消息或业务约束 | 是，见表 2 |
| 不把 Actor 画成系统类 | 是；三位参与者不进入 VOPC |
| 不用全局 BCE 冒充 VOPC | 是；本图独立于 `DGM-CLASS-01-analysis.puml` |
| 关系多重性是否有依据 | 五条业务关联有依据（§2.2）；控制/边界到实体不定义持久多重性（§2.1） |
| 是否把 `Scholar` 与 `User`/`UserProfile` 混为一类 | 否：档案、账号、普通主页三者分离（BR-UC10-01） |
| 是否把 `IdentityCandidate` 与 `AttributionCandidate` 混为一类 | 否：前者成员是档案（是否同一人），后者对象是署名（属于谁） |
| 是否把候选与合并结论混为一谈 | 否：`IdentityCandidate` 状态含“已排除（保持独立）”，候选关闭后档案仍独立 |
| 是否把 `Authorship` 与 `WorkEntity` 混为一类 | 否：成果共享实体与单条参与关系分离；关系状态独立 |
| 是否把 `SourceRecord`、`ReviewEvidence`、`AuditEvidence` 混为一类 | 否：来源原值／审核材料／动作留痕三分离（§2.3） |
| 是否出现写 `本人确认`、合并成果、删除来源 | 否：`Ctl-->Authorship` 只写自动关联/待核验/争议/已排除；`Ctl..>WorkEntity` 只读＋声明；无删除边 |
| 是否引入设计技术（表/接口/队列/索引） | 否 |

## 6. 与全局图的关系

本 VOPC 是 UC-10 的局部静态视图，不是全系统类图。与 `DGM-CLASS-01-analysis.puml` 的异同、命名合并建议见[07-与全局模型差异](07-global-model-diff.md)。R5 统一分析类时应以本 VOPC 为输入之一，而不是用全局图反推本 UC 的参与类。
