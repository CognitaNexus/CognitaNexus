# UC-06 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-07-claim-review.puml`](diagrams/DGM-VOPC-07-claim-review.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-07-claim-review.svg) · [PNG](diagrams/generated/DGM-VOPC-07-claim-review.png)
**方法：** 教材 §5.4.4：VOPC 只来自本用例实现的顺序图对象，关系来自消息与业务约束；不是全局 BCE。

## 1. 参与类集合

| 类 | 类型 | 来自顺序图消息 | 事件流依据 |
|---|---|---|---|
| `ClaimReviewUI` | Boundary | M1、M2、M6、M10、M12、M13、M21、M22、M25、M26、M30、M31、M34、M35、M38、M39、M42、M43、M44、M45、M50、M51、M54、M55、M56、M57、M61、M62 | E1、E4、E11、E12、A1–A9 |
| `ClaimReviewCoordinator` | Control | M2–M5、M7–M9、M11、M12、M14–M17、M19–M21、M23–M25、M27–M30、M32–M34、M36–M38、M40–M42、M45–M50、M52–M54、M57–M61 | E2–E11、E12、A1–A9 |
| `User` | Entity | M3、M58（请求方） | E2、E12 |
| `ClaimApplication` | Entity | M7、M23、M28、M32、M36、M40、M48、M52、M59 | E3、E8–E11、A2、A3、A5–A8 |
| `ReviewEvidence` | Entity | M11、M46（请求方） | E4、A4 |
| `Scholar` | Entity | M14（请求方） | E5 |
| `ScholarAuthorization` | Entity | M15（请求方）、M16（应答方）、M27（创建） | E5、E8、A5、A6 |
| `AuditEvidence` | Entity | M24、M29、M33、M37、M41、M49、M53（写入）、M60（读取） | E10、E12、A9 |

“有权审核的管理员”不进入 VOPC（参与者不是系统内部类，教材 §5.4.4）；“登录申请人”只以只读结果为主体，其查询路径属 UC-05，本 VOPC 不为其建类。

## 2. 关系与来源

| 关系 | 类型 | 来自消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `ClaimReviewUI --> ClaimReviewCoordinator` | 单向关联/消息关系 | M2/M45/M57（请求）；M6/M10/M12/M25/M30/M34/M38/M42/M50/M54/M61（返回） | UI 请求；Coordinator 返回结果 | 不定义持久多重性 | 一次审核会话的协作，不是持久聚合 |
| `ClaimReviewCoordinator --> ClaimApplication` | 单向关联/读取-记录关系 | M7/M59（读取）；M23/M32/M36/M40/M48/M52（记录状态与理由） | Coordinator 读/写申请状态 | 不定义持久多重性 | 同一条关联承载读取与记录；申请不归协调器所有 |
| `ClaimReviewCoordinator --> ReviewEvidence` | 单向关联/只读关系 | M11、M46 | 按可见范围读取材料 | 不定义持久多重性 | 只读：本 UC 不新增/删除材料 |
| `ClaimReviewCoordinator --> Scholar` | 单向关联/只读确认 | M14 | 确认目标仍为已收录档案 | 不定义持久多重性 | 不改写档案字段（维护属 UC-07） |
| `ClaimReviewCoordinator ..> ScholarAuthorization` | 单向只读依赖（虚线） | M15/M16 | 读取目标当前有效授权 | 不定义持久多重性 | 虚线表示只读：无修改/撤销/转移消息 |
| `ClaimReviewCoordinator --> ScholarAuthorization` | 单向创建关系（实线） | M27 | 创建批准后的对象级管理授权 | 不定义持久多重性 | 只在“无未决冲突且授权写入成功”分支执行 |
| `ClaimReviewCoordinator --> User` | 单向关联/校验关系 | M3、M58 | 校验审核者身份、账号状态与权限 | 不定义持久多重性 | 每次审核/查询请求都校验 |
| `ClaimReviewCoordinator --> AuditEvidence` | 单向关联/写入-读取关系 | M24/M29/M33/M37/M41/M49/M53（写）；M60（读） | 保存决定留痕；读取决定审计 | 不定义持久多重性 | 对象引用（申请/目标/授权）是审计的业务属性 |
| `ClaimApplication "0..*" --> "1" User` | 业务关联 | M7/M58；FR05“登录用户…本人”；SRS §8 | 申请 → 申请人（批准后即被授权主体） | `0..*` : `1` | 沿用 UC-05 依据；一次申请恰有一个申请人 |
| `ClaimApplication "0..*" --> "1" Scholar` | 业务关联 | M7/M14；FR05“选择已有 Scholar”；AC-IMPL-FR05-03 | 申请 → 认领目标 | `0..*` : `1` | 一次申请只认领一个 Scholar；同一 Scholar 可有竞争申请 |
| `ClaimApplication --> ReviewEvidence` | 业务关联 | M11/M46 以申请为范围读取材料 | 申请 → 其提交的私有材料 | **未定** | 证据最低集合未冻结（D-06）；沿用 UC-05 §2.2 |
| `ScholarAuthorization --> Scholar` | 业务关联 | M15 以目标 Scholar 为读取键；M27 授权对象 | 授权 → 目标档案 | **未定** | 同一 Scholar 是否可有并列管理者/授权历史保留未定（SPEC ID-12；C-04） |
| `ScholarAuthorization "0..*" --> "1" User` | 业务关联 | M27“被授权=申请人”；SRS §12“User—ScholarAuth 授权记录” | 授权 → 被授权主体 | `0..*` : `1` | 一次批准产生一条授权，主体为该申请人；同一 User 可对多个 Scholar 持有授权 |

### 2.1 为什么 `Coordinator → ScholarAuthorization` 同时有虚线（只读）与实线（创建）

两条消息语义不同，不能合并成一条：

- M15/M16 是**只读**读取目标当前有效授权，用于冲突判定；虚线表示“读不写”；
- M27 是**创建**批准后的新授权，只在无未决冲突且授权写入成功时执行；实线表示“确实写入”。

若只画实线，会让人误以为审核过程始终有授权写入；若只画虚线，则无法表达“批准产生授权”这一 FR05/AC-IMPL-FR05-02 的核心结果。

### 2.2 为什么 `ScholarAuthorization → Scholar` 与 `ClaimApplication → ReviewEvidence` 不标多重性

- `Auth → Scholar`：AC-IMPL-FR05-03 只规定“争议保留原授权、不自动转移”，未规定同一 Scholar 是否可有并列管理者、授权历史如何保留（SPEC ID-12 待评审）。标 `1` 或 `0..*` 都会替需求做决定（C-04）。
- `Claim → ReviewEvidence`：必要证据最低集合未冻结（D-06/C-02），待补充申请可能只有部分材料；补证后的材料版本/替换规则未定义（C-07）。沿用 UC-05 §2.2 的处理。

### 2.3 为什么控制/边界到实体的关联不标持久多重性

`ClaimReviewUI`/`ClaimReviewCoordinator` 到实体的关联按**消息方向**表达协作，不是长期对象链接。给它们标 `1..*` 会把一次审核会话的协调关系误解为持久聚合（例如误读为“协调器拥有若干授权”）。

### 2.4 审计为什么不画到被写对象的关联

`AuditEvidence` 的对象引用（申请/目标 Scholar/授权）是其**业务属性**（`targetRefs`、`beforeAfterValues`），不是独立的对象链接；这与 UC-22 VOPC 的处理一致，避免把“记录留痕”画成“实体持有审计对象”。审计的查询界面属 UC-17（FR14）。

## 3. 从顺序图到 VOPC 的对应

| 顺序图对象 | VOPC 类 | 保留/删除理由 |
|---|---|---|
| 有权审核的管理员（Actor） | 不保留 | 参与者不是系统类 |
| ClaimReviewUI | 保留 | 接收 M1/M18/M44/M56，返回 M6/M10/M12/M13/M21/M22/M25/M26/M30/M31/M34/M35/M38/M39/M42/M43/M50/M51/M54/M55/M61/M62 |
| ClaimReviewCoordinator | 保留 | 协调 M2–M61 的校验、守卫、写入与返回 |
| User | 保留 | 接收 M3、M58 |
| ClaimApplication | 保留 | 接收 M7/M23/M28/M32/M36/M40/M48/M52，应答 M59 |
| ReviewEvidence | 保留 | 接收 M11、M46 |
| Scholar | 保留 | 接收 M14 |
| ScholarAuthorization | 保留 | 接收 M15（只读）、M27（创建），应答 M16 |
| AuditEvidence | 保留 | 接收 M24/M29/M33/M37/M41/M49/M53，应答 M60 |
| 无其他对象 | — | 没有 UserProfile/Authorship/Work/ConflictQueue/通知/角色层级的消息 |

## 4. 排除项与理由

- `UserProfile`：本 UC 无消息；批准只新增 Scholar 授权，不建第二个主页、不合并主页（AC-IMPL-FR05-04；BR-IMP-05；SRS §12）。
- `Authorship`/`WorkEntity`/`WorkVersion`/`SourceRecord`：批准不确认、不创建、不修改成果与来源事实（FR05；AC-IMPL-FR15-03/06；SPEC ID-07）。
- `ClaimUI`/`ClaimCoordinator`（UC-05 的提交/查询侧）：本 UC 不重画提交与本人查询；两个控制类是否合并留待 R5（C-09、07 §2）。
- `ConflictQueue`/`ControlledTodo`/`DisputeCase`：争议“受控待办”的载体属 FR14/UC-17，未冻结；本 VOPC 以申请状态＋审计表达（C-05）。
- `ClaimDecision`/`DecisionRecord`：决定事实已由 `ClaimApplication`（状态/理由）与 `AuditEvidence`（操作者/前后值/理由）承载，独立类重复同一事实。
- `ReviewerRole`/`PermissionMatrix`/`ApprovalChain`：基线未定义审核等级/审批链，不得自造（C-01）。
- `Notification`/`SlaTimer`：SRS 未定义通知与 SLA（C-03/C-11）。
- 第二个 `Scholar`/`ScholarMerge`：本 UC 无创建/合并消息（FR05；SRS §12；FR15/BR-IMP-03）。
- `Session`/`CredentialContext`：已认证请求上下文是 M2 的输入；基线未定义会话对象（UC-04 Q-07）。
- `DecisionView`/`AuditSummary`/`EvidenceFile`：返回投影与存储形态，不是分析类。

## 5. VOPC 完整性检查

| 检查 | 结果 |
|---|---|
| 只包含 UC-06 参与类 | 是，8 个 |
| 每个类有事件流/消息依据 | 是，见 §1/§3 |
| 每条关系可追溯到消息或业务约束 | 是，见 §2 |
| 不把 Actor 画成系统类 | 是；审核管理员不入图，申请人不在本序列 |
| 不用全局 BCE 冒充 VOPC | 是；本图独立于 `DGM-CLASS-01-analysis.puml` |
| 是否强行填多重性 | 否；只有申请→申请人/目标、授权→被授权主体三处有依据 |
| 是否出现“改/撤销/转移授权” | 否；只有只读依赖（虚线）与无冲突创建（实线） |
| 是否出现第二主页或成果关系 | 否 |
| 是否出现审核角色层级/SLA/通知类 | 否 |
| 是否引入设计技术 | 否 |

## 6. 与全局图的关系

本 VOPC 是 UC-06 的局部静态视图，不是全系统类图。与 `DGM-CLASS-01-analysis.puml`、`01-BCE分析类模型.md`、合并序列 `DGM-SEQ-02-claim.puml` 的异同见[07-与全局模型差异](07-global-model-diff.md)。R5 统一分析类时应以本 VOPC 为输入之一，而不是用全局图反推本 UC 的参与类。
