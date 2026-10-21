# UC-22 Boundary / Control / Entity 识别

**版本：** V1.0（2026-10-21）
**方法：** 教材§5.4.2：从用例事件流识别边界类、控制类、实体类；不从现有全局 BCE 反向填充。
**输入：** 本目录 01 规约、02 事件流。

## 1. 识别原则

1. **Boundary**：与外部参与者交互、接收输入或显示结果的对象。
2. **Control**：协调一个用例内的校验、写入顺序、失败处理和结果返回；不是实现层的 Controller/Service。
3. **Entity**：承载业务事实、状态和持久信息；不是数据库表。
4. 只有在该 UC 事件流中实际承担消息的对象才进入 VOPC。
5. 参与者“注册用户”不是系统分析类；他人的 UserProfile 与 Scholar 档案是被拒绝的写入目标，不是本 UC 的参与对象。
6. 仅因“以后可能用到”而出现的类不进入本 UC。

## 2. 候选分析类

| ID | 候选类 | 类型 | 从哪些事件发现 | 业务行为 | 为什么不能由其他候选直接承担 |
|---|---|---|---|---|---|
| C1 | `ProfileUI` | Boundary | E1、A1–A5、E6 | 接收本人主页编辑输入；显示保存成功、拒绝（未认证/停用）、越权结果、学术字段转交说明、保存失败 | `UserProfile`/`User` 是业务实体，不应承担输入/显示；`ProfileCoordinator` 是内部协调者，不直接面对所有者 |
| C2 | `ProfileCoordinator` | Control | E2–E6、A1–A5 | 校验身份/账号状态/对象归属；判定字段范围；协调保存与公开投影；拒绝学术事实写入；协调受控审计；返回结果 | Boundary 只负责交互，不能单独保证“本人 + 普通字段 + 私有字段不公开 + 审计留痕”的一致性；Entity 各自只知道自己，不能独立协调跨对象校验 |
| C3 | `User` | Entity | E2、A1 | 提供账号标识与账号状态；作为主页所有权的账号主体，供协调器判定“请求者是否为所有者” | UserProfile 承载公开主页资料，不承载账号状态与会话主体身份；被拒绝的“他人主页”请求也必须先确认请求者身份 |
| C4 | `UserProfile` | Entity | E2、E4、E5、A2–A7 | 承载普通公开字段与展示设置；回答归属（属于哪个 User）；生成公开投影；保持零成果空态；不接受学术事实写入 | User 不承载公开主页字段；Scholar 是平台采集/核验的学术身份档案，不是注册用户的普通主页 |
| C5 | `AuditEvidence` | Entity | E6、A1、A2 | 保存受保护写入与越权/拒绝尝试的可追溯留痕：操作者身份、被写对象、理由/结果、前后值、时间 | 审计留痕不是 User/UserProfile 的业务数据（不随主页资料公开），也不是协调器的一次性上下文；`AC-IMPL-FR06-04` 明文要求“保留受控审计”，BR-IMP-10 要求身份/理由/前后值/时间 |

## 3. 逐类选择理由

### C1 ProfileUI（Boundary）

- 发现于 E1“提交普通字段修改”与 E6“返回保存成功/公开生效结果”，并承担 A1–A5 的结果显示。
- 它是外部交互边界，不持久保存 User/UserProfile/AuditEvidence。
- **命名说明：** 全局分析类图已使用 `ProfileUI` 表示“编辑本人普通 UserProfile 字段”的边界（见 07），本 UC 沿用该名称；公开浏览的边界是 UC-03 的 `PublicProfileUI`，两者不可互换。

### C2 ProfileCoordinator（Control）

- 发现于 E2 校验、E3 字段范围判定、E4–E5 保存与公开投影、E6 审计与结果返回，以及 A1–A5 的失败/拒绝处理。
- 需要跨 `User`、`UserProfile`、`AuditEvidence` 协调，并保证“拒绝时不写入”“成功时必须留痕”。
- 字段范围判定（普通 vs 学术）需要同时用到请求内容与主页可承载的字段集合，不能由某个 Entity 单独完成。
- **命名说明：** 全局图使用缩写 `ProfileCtl`；本 UC 使用完整分析类名 `ProfileCoordinator`，R5 统一时决定保留全名还是缩写（见 07、08 B-07）。

### C3 User（Entity）

- 发现于 E2 身份与账号状态校验、A1 停用拒绝。
- 必要业务事实：账号标识、账号状态、与 UserProfile 的 1:1 绑定。
- 不能并入 UserProfile：本 UC 需要判定“请求者是否为所有者”，所有权主体是账号；账号状态也不属于公开主页资料。
- 本 UC **不新增** User 属性或状态；账号与会话语义沿用 UC-04。

### C4 UserProfile（Entity）

- 发现于 E2 归属核验、E4 保存普通字段、E5 公开投影与空态、A2–A7 的拒绝/隔离分支。
- 必要业务事实：所属 User、普通公开字段集合、展示设置、公开投影白名单、零成果空态；待审版本仅在 A5 情形下作为状态出现（B-01 待确认）。
- 不能并入 User：UserProfile 是公开主页对象，User 是账号。
- 不能并入 Scholar：Scholar 是平台采集/核验的学术身份档案，普通主页不因同名自动成为 Scholar（`AC-IMPL-FR03-04`；BR-IMP-03）。
- **不创建第二个 UserProfile**：本 UC 没有任何“新建主页/复制主页”的消息，1:1 是编辑期不变量。

### C5 AuditEvidence（Entity）

- 发现于 E6 成功写入留痕、A1 停用/未认证拒绝留痕、A2 越权尝试留痕。
- 必要业务事实：操作者身份、被写对象引用、理由/结果、前后值、时间；不含凭据与秘密（NF03）。
- 不能由 UserProfile 承担：审计不对外公开，且必须覆盖“拒绝、未发生写入”的情形，而 UserProfile 只在成功时被写。
- 不能由 ProfileCoordinator 承担：协调器的请求上下文是一次用例状态，审计要求跨请求可追溯（INV-10）。
- **命名说明：** 全局图使用 `Evidence`、全局 BCE 表使用 `ReviewEvidence`；本 UC 需要把“审计留痕”与“认领证据材料”分开，故用 `AuditEvidence`，命名冲突登记为 R5 事项（见 07、08 B-07）。

## 4. 被拒绝的候选类

| 候选 | 拒绝理由 | 证据/后续 |
|---|---|---|
| `Scholar` | 本 UC 没有向 Scholar 发送消息：重要学术身份/机构/归属/来源事实不在本 UC 写入，只在 A3 被拒绝并转交 UC-07/UC-08 的对象授权与审核流程 | FR06；`AC-IMPL-FR06-01/02`；见 07 §4 |
| `ScholarAuthorization` / `Auth` | 普通主页编辑权直接来自 User 对 UserProfile 的所有权，不来自 Scholar 对象授权；本 UC 既不创建也不读取 Auth | BR-IMP-05；FR04；FR05 |
| `Claim` / `ClaimApplication` | 本 UC 不是认领；不产生申请、证据或审核状态 | FR05；FR06 |
| `Authorship` / `WorkEntity` | 普通资料编辑不创建成果或参与关系；零成果是空态而非空实体 | `AC-IMPL-FR07-03`；`AC-IMPL-FR03-03`；FR07 |
| 第二个 `UserProfile` / `ProfileCopy` | 用户只有一个主页；本 UC 无新建/复制消息，第二主页被明确禁止 | FR04；`AC-IMPL-FR04-03`；BR-UC22-05 |
| `Session` / `CredentialContext` | “已认证请求上下文”是本 UC 的输入而非业务实体；基线未定义会话对象生命周期（UC-04 Q-07 仍待确认）。凭据本身按 NF03 从不进入分析与公开响应 | FR04-02；SRS §8；NF03；B-06 |
| `FieldPolicy` / `FieldCatalogue` | 字段范围判定的知识分别落在请求（协调器上下文）与主页可承载字段集合（UserProfile）上；基线未定义独立的字段策略对象，公共资料策略本身未冻结 | FR06；B-01/B-02 |
| `PendingProfileVersion` | “待审版本”只在 A5 情形出现，且基线未定义 UserProfile 版本对象的独立生命周期；本分析把它作为 UserProfile 的状态属性并标待确认 | FR06；`AC-IMPL-FR06-01`；B-01 |
| `PublicProfileUI` / `PublicPortalUI` | 公开读取与展示属 UC-03；本 UC 只保证写侧公开投影，不承担读侧视图 | UC-03；`AC-IMPL-FR06-05` 读侧 |
| `AuditCoordinator`（全局 `AuditCtl`） | 本 UC 只有一次受保护写入的留痕需求，由 `ProfileCoordinator` 协调写入 `AuditEvidence`；审计查询与集中治理属 UC-17 | BR-IMP-10；FR14；见 07 §4 |
| `ProfileView` / `DTO` | 公开投影是 UserProfile 的可导出业务信息（见 06 属性表），不需要独立业务身份 | 教材§5.4.2 |
| `EditDraft` / `FormState` | 未保存的表单草稿是交互态，不是业务事实；基线未定义草稿语义 | 教材§5.5.2；01 前置条件 |

## 5. User / UserProfile / Scholar 三方边界结论

| 边界 | 结论 | 本 UC 证据 |
|---|---|---|
| User → UserProfile | 所有权来源：User 是唯一拥有者；编辑授权来自所有权，不需要 Scholar 授权 | BR-IMP-05；FR04；`AC-IMPL-FR06-04` |
| User ↔ UserProfile 多重性 | 1:1，编辑期不变量：不新建、不复制第二个主页，地址不变 | FR04；`AC-IMPL-FR04-03` |
| UserProfile ↔ Scholar | 分离：编辑普通资料不改变 Scholar 档案、不合并同名、不产生“已认证学者” | FR06；`AC-IMPL-FR03-04`；BR-IMP-03；SRS §12 |
| UserProfile 可写内容 | 仅普通公开字段与展示设置；重要学术身份/机构/归属/来源事实不在可写范围 | FR06；BR-UC22-03 |
| 他人 UserProfile | 不是本 UC 参与对象；对它的写请求被服务端拒绝并留痕 | `AC-IMPL-FR06-04`；BR-IMP-05 |
| 同名其他主页与 Scholar | 两个对象、两个状态，编辑其中一方不改变另一方 | `AC-IMPL-FR03-04` |
| UserProfile ↔ AuditEvidence | 审计留痕指向被写对象，但不随主页数据公开；审计不含秘密 | BR-IMP-10；NF03；INV-10 |

## 6. 识别结论

本 UC 的候选分析类共 5 个：

- Boundary：`ProfileUI`
- Control：`ProfileCoordinator`
- Entity：`User`、`UserProfile`、`AuditEvidence`

`Scholar`、`ScholarAuthorization`、`Claim`、`Authorship`、`WorkEntity`、第二个 `UserProfile`、`Session`、`FieldPolicy`、`PendingProfileVersion`、`PublicProfileUI`、`AuditCoordinator`、`ProfileView`、`EditDraft` 均不进入本 UC VOPC。该集合将在 04 的顺序图中接受消息检验；若某类没有消息，则删除或降为属性/职责。
