# UC-05 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-06-claim.puml`](diagrams/DGM-VOPC-06-claim.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-06-claim.svg) · [PNG](diagrams/generated/DGM-VOPC-06-claim.png)
**方法：** 教材 §5.4.4：VOPC 只来自本用例实现的顺序图对象，关系来自消息与业务约束；不是全局 BCE。

## 1. 参与类集合

| 类 | 类型 | 来自顺序图消息 | 事件流依据 |
|---|---|---|---|
| `ClaimUI` | Boundary | M1、M2、M6、M7、M8、M9、M11、M12、M15、M16、M21、M22、M23、M24、M25、M26、M29、M30、M31、M32、M33、M34、M35、M36 | E1、E8、E9、E11、A1a/A1b、A2–A8 |
| `ClaimCoordinator` | Control | M2–M6、M8、M10–M15、M17、M19–M21、M23、M26–M29、M31、M33、M35 | E2–E8、E10–E11、A1–A8 |
| `User` | Entity | M3（请求方）、M27（请求方） | E2、E10、A2 |
| `Scholar` | Entity | M4（请求方） | E3、A3 |
| `ScholarAuthorization` | Entity | M17（请求方）、M18（应答方） | E5、A5 |
| `ClaimApplication` | Entity | M13、M19（创建）；M28（读取） | E6、E8、E10、E11、A1b、A4、A8 |
| `ReviewEvidence` | Entity | M14、M20（登记） | E7、A1b、A6（读侧守卫由 ClaimApplication 归属判定继承）、E11 |

登录申请人不进入 VOPC，因为参与者不是系统内部类（教材 §5.4.4）；审核管理员不属于本 UC。

## 2. 关系与来源

| 关系 | 类型 | 来自消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `ClaimUI --> ClaimCoordinator` | 单向关联/消息关系 | M2、M6、M8、M11、M15、M21、M23；M26、M29、M31、M33、M35 | UI 提交请求；Coordinator 返回结果 | 不定义持久多重性 | UI 不是实体；按消息方向表达一次提交或查询会话的协作 |
| `ClaimCoordinator --> ClaimApplication` | 单向关联/创建-读取关系 | M13、M19（创建）；M28（读取） | Coordinator 创建申请、按归属读取申请 | 不定义持久多重性 | 同一条关联承载创建与读取；申请不归协调器所有 |
| `ClaimCoordinator ..> ScholarAuthorization` | 单向只读依赖 | M17（请求）、M18（应答） | Coordinator 只读目标当前有效授权 | 不定义持久多重性 | 虚线表示只读：本 UC 无创建/修改/撤销/转移授权的消息 |
| `ClaimApplication "0..*" --> "1" User` | 业务关联 | M3/M19/M28；FR05“登录用户…本人”；SRS §8 | 申请 → 申请人 | `0..*` : `1` | 一次申请恰有一个申请人；一个 User 可先后提交多个申请 |
| `ClaimApplication "0..*" --> "1" Scholar` | 业务关联 | M4/M19；FR05“选择已有Scholar”；AC-IMPL-FR05-03 | 申请 → 认领目标 | `0..*` : `1` | 一次申请只认领一个已收录 Scholar；同一 Scholar 可有竞争申请（AC-IMPL-FR05-03） |
| `ClaimApplication --> ReviewEvidence` | 业务关联 | M14、M20（登记时关联申请） | 申请 → 其提交的证据材料 | **未定** | 最低证据集合未冻结（D-06）；待补充申请可能已有部分材料，故不强行标 `1..*` |
| `ScholarAuthorization --> Scholar` | 业务关联 | M17 以目标 Scholar 为读取键；SRS §12“目标Scholar” | 授权 → 目标档案 | **未定** | 有效授权数量与转移条件待评审（SPEC ID-12）；本 UC 不创建授权 |

### 2.1 为什么控制/边界到实体的关联不标持久多重性

`ClaimUI`/`ClaimCoordinator` 到实体的关联按**消息方向**表达协作，不是长期对象链接。给它们标 `1..*` 会把一次用例的协调关系误解为持久聚合（例如误读为“协调器拥有若干申请”）。本 VOPC 只在有正式依据的 `ClaimApplication→User`、`ClaimApplication→Scholar` 上标 `0..*:1`。

### 2.2 为什么 `ClaimApplication→ReviewEvidence` 不标多重性

- 必要证据最低集合未冻结（B-02/D-06），无法断言“至少一份”；
- `待补充` 状态下可能只有部分材料（A1b），也可能一份都未保存（A1a）；
- 补证后的材料版本/替换规则未定义（B-04）。

因此该关系只表达“材料关联申请”的业务事实，多重性留待需求确认。

### 2.3 为什么 `ScholarAuthorization` 只画只读依赖，且不画 `User—Authorization`

- 授权由 UC-06 批准后产生（`DGM-SEQ-02`、`DGM-ACTIVITY-01`；AC-IMPL-FR05-02）；本 UC 没有任何发给授权对象的创建/写入消息；
- 本 UC 只按目标 Scholar 读取“当前是否已有有效授权”，用于识别竞争（AC-IMPL-FR05-03）；
- `User—ScholarAuthorization`（“被授予”）在本 UC 没有消息，照“关系须可追溯到消息或业务约束”的要求不画；其创建属 UC-06。

## 3. 从顺序图到 VOPC 的对应

| 顺序图对象 | VOPC 类 | 保留/删除理由 |
|---|---|---|
| 登录申请人（Actor） | 不保留 | 参与者不是系统类 |
| ClaimUI | 保留 | 接收 M1/M25，返回 M6–M12、M15/M16、M21–M24、M29–M36 |
| ClaimCoordinator | 保留 | 协调 M2–M21、M23、M26–M29、M31、M33、M35 |
| User | 保留 | 接收 M3、M27 |
| Scholar | 保留 | 接收 M4 |
| ScholarAuthorization | 保留 | 接收 M17，应答 M18（只读） |
| ClaimApplication | 保留 | 接收 M13/M19，应答 M28 |
| ReviewEvidence | 保留 | 接收 M14/M20 |
| 无其他对象 | — | 没有 UserProfile/Auth 写入/Authorship/WorkEntity/Admin 的消息 |

## 4. 排除项与理由

- `UserProfile`：本 UC 无消息；认领不创建第二个主页、不合并主页（AC-IMPL-FR05-04；SRS §12）。主页属 UC-04/UC-22。
- `Authorship`/`WorkEntity`/`WorkVersion`/`SourceRecord`：认领不确认、不创建、不修改成果与来源事实（FR05；AC-IMPL-FR15-06；SPEC ID-07）。
- `AuditEvidence`/`ReviewEvidence` 之外的留痕类：本 UC 的受保护写入由申请记录自身承载；独立审核决定留痕属 UC-06，后台审计属 UC-17（BR-IMP-10；D-13；B-05）。
- `ClaimReviewCoordinator`：审核侧控制类属 UC-06；在本 VOPC 中出现会把审核决定塞进提交/查询用例。
- `ConflictQueue`/`ControlledTodo`/`Dispute`：争议产生与处理属 UC-06/UC-17；本 UC 只在申请上记录竞争事实（AC-IMPL-FR05-03；B-06）。
- 通知对象 `Notification`/`Notice`：SRS 未定义通知渠道与内容（B-07）。
- 第二个 `Scholar` 或 `ScholarMerge`：本 UC 无创建/合并消息（FR05；SRS §12；FR15/BR-IMP-03）。
- `Session`/`CredentialContext`：已认证请求上下文是 M2/M26 的输入；基线未定义会话对象（UC-04 Q-07）。
- `ClaimView`/`StatusDTO`/`EvidenceFile`：返回投影与存储形态，不是分析类。

## 5. VOPC 完整性检查

| 检查 | 结果 |
|---|---|
| 只包含 UC-05 参与类 | 是，7 个 |
| 每个类有事件流/消息依据 | 是，见表 1/表 3 |
| 每条关系可追溯到消息或业务约束 | 是，见表 2 |
| 不把 Actor 画成系统类 | 是；审核管理员不入图 |
| 不用全局 BCE 冒充 VOPC | 是；本图独立于 `DGM-CLASS-01-analysis.puml` |
| 是否强行填多重性 | 否；只有申请→申请人、申请→目标两处有依据 |
| 是否出现授权创建/转移 | 否；仅虚线只读依赖 |
| 是否出现第二主页或成果关系 | 否 |
| 是否引入设计技术 | 否 |

## 6. 与全局图的关系

本 VOPC 是 UC-05 的局部静态视图，不是全系统类图。与 `DGM-CLASS-01-analysis.puml`、`01-BCE分析类模型.md`、合并序列 `DGM-SEQ-02-claim.puml` 的异同见[07-与全局模型差异](07-global-model-diff.md)。R5 统一分析类时应以本 VOPC 为输入之一，而不是用全局图反推本 UC 的参与类。
