# UC-22 分析与现有全局分析模型差异

**版本：** V1.0（2026-10-21）
**比较对象：** [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml)、[`01-BCE分析类模型.md`](../01-BCE分析类模型.md)、合并序列 [`DGM-SEQ-04-profile.puml`](../../../uml/analysis/DGM-SEQ-04-profile.puml)、编辑活动图 [`DGM-ACTIVITY-06-edit-profile.puml`](../../../uml/profile/DGM-ACTIVITY-06-edit-profile.puml)、候选类登记表 [`analysis-class-registry.md`](../automation/analysis-class-registry.md)。
**原则：** 全局图只作比对材料，不作为本 UC 识别类的反向答案；本轮不重构全局图、不修改共享登记表。

## 1. 已存在且一致

| UC-22 类/概念 | 全局对应 | 一致点 | 证据 |
|---|---|---|---|
| `ProfileUI` | 全局 `ProfileUI`（`01` §2“编辑本人普通 UserProfile 字段”） | 都是“本人普通主页编辑”的边界；与公开浏览边界 `PublicUI`/UC-03 分离 | FR06；UC-22 |
| `ProfileCoordinator` | 全局 `ProfileCtl`（`01` §3“校验会话/所有权，区普通资料与学术事实”） | 都负责所有权校验与普通/学术字段分离 | FR06-03/04/05；UC-22 |
| `User` | 全局 `User`（账号状态/授权主体） | 都是账号、状态与对象所有权主体；不因普通编辑变成 Scholar | FR04；SRS §12 |
| `UserProfile` | 全局 `UserProfile`（稳定公开地址、普通公开资料、零成果空态） | 都是唯一公开普通主页；属性覆盖普通资料、公开投影与空态 | FR03；FR04；FR06 |
| `User 1 — 1 UserProfile` | 全局第 116 行 `User "1" -- "1" UserProfile : 唯一拥有` | 编辑期不变量：不新建、不复制第二个主页 | FR04；`AC-IMPL-FR04-03` |
| `ProfileCoordinator → UserProfile` | 全局 `ProfileCtl ..> UserProfile` | 都表达协调器向主页写入普通资料 | M4/M15/M18/M19 |
| 学术事实不写核验状态 | `01` §3 关键守卫“普通编辑不写核验状态”；`DGM-ACTIVITY-06` 的学术分支 | 一致：本 UC 只拒绝并转交 | FR06；`AC-IMPL-FR06-01/02` |
| 审计留痕 | 全局 `AuditCtl ..> Evidence`；合并序列 `Profile -> Evidence : 记录受控审计` | 都要求受保护写入留痕 | BR-IMP-10；`AC-IMPL-FR06-04` |

## 2. 需要更名/别名统一

| 本 UC 名称 | 全局名称 | 差异 | R5 建议 |
|---|---|---|---|
| `ProfileCoordinator` | `ProfileCtl` | 全名 vs 缩写 | 与 `SearchCtl`/`RegCtl`/`AuditCtl` 一并统一（登记表 D-11）；不要让同一概念在不同 UC VOPC 用两个名字 |
| `ProfileUI` | `ProfileUI` | 名称一致 | 保留；注意与 UC-03 的 `PublicProfileUI` 区分，避免被读成同一门户边界 |
| `AuditEvidence` | `Evidence`（全局图）、`ReviewEvidence`（`01` §4） | 全局一名两用：既指认领/审核证据材料，又指审计留痕 | R5 拆分命名：审核证据材料与受保护写入留痕必须可区分；建议保留 `AuditEvidence` 表示留痕，`ReviewEvidence` 专指认领/审核材料 |
| `UserProfile.ordinaryPublicFields` | 全局 `+普通公开资料()` | 全局只有操作名，没有字段级说明 | 在统一类定义中把“普通公开字段集合 + 公开白名单”写成业务属性，不写数据库字段 |

## 3. 职责差异/需要补充

| 项目 | 本 UC 证据 | 全局现状 | 差异/影响 | R5 处理 |
|---|---|---|---|---|
| 拒绝结果与三态区分 | `AC-IMPL-FR06-02/04`；NF01 | `ProfileUI` 只写“编辑本人普通 UserProfile 字段” | 全局边界缺“显示越权/停用拒绝/保存失败/学术转交结果”的职责，容易被实现为“隐藏入口即授权” | 在 `ProfileUI` 职责中补拒绝与失败结果展示（非授权判定） |
| 归属校验依赖 `User` | M3；BR-IMP-05；SRS §8 | 全局 `ProfileCtl` 没有到 `User` 的依赖 | 所有权判定的账号主体在全局图中缺失，`ProfileCtl ..> Scholar` 却存在 | R5 在 `ProfileCtl` 职责中加入“校验请求者账号状态与所有权”，并评估补 `ProfileCtl → User` |
| 受保护写入的留痕归属 | M8/M11/M20；`AC-IMPL-FR06-04` | 留痕在 `AuditCtl ..> Evidence`，`ProfileCtl` 无留痕职责 | 若按全局图实现，UC-22 的越权/停用拒绝可能没有留痕，直接违反 AC-IMPL-FR06-04 | R5 决定：`ProfileCoordinator` 直接写审计留痕，或委托 `AuditCtl`；无论哪种，FR06 写入必须有明确留痕归属 |
| 拒绝也留痕 | `AC-IMPL-FR06-04`；BR-IMP-10 | 全局 `Evidence` 属性为“操作者/理由/前后值” | 缺“对象引用、结果、时间”，无法表达“被拒绝的写入尝试” | `Evidence` 建议补 `targetRef`、`outcome`、`occurredAt`；与 NF03 的“审计不含秘密”一并写入职责 |
| 待审版本语义 | A5；FR06“待审版本不覆盖公开版” | 全局 `UserProfile` 无待审版本/公开版区分；`ProfileCtl` 无相关职责 | 若普通字段被策略要求审核（B-01），全局模型无法表达“待审不覆盖公开版” | 在 R5 明确：普通编辑是否产生待审版本；若需要，`UserProfile` 需要“公开版/待审版本”区分，但不建复杂状态机 |
| 会话对象 | M2“已认证请求上下文”；B-06 | `01` 写“校验会话/所有权”，全局无 `Session` 类 | 会话是隐藏依赖，可能导致实现自行发明授权对象 | R5/需求确认会话是否成为分析对象；在此之前只作为请求上下文 |
| 字段策略 | M12；B-02 | 全局无字段清单/白名单表达 | 实现者容易自选字段集合，导致私有字段泄漏或超范围写入 | R5 以业务属性表达，不当成独立策略对象；字段级清单待需求确认 |

## 4. 关系差异

### 4.1 `ProfileCtl ..> Scholar`（全局图第 107 行）

- 本 UC 顺序图中**没有**任何消息发送给 `Scholar`：普通编辑不写学术身份/机构/归属/来源事实（FR06；`AC-IMPL-FR06-01/02`）。
- 合并序列 `DGM-SEQ-04-profile.puml` 画有 `Profile -> Scholar : 转对象授权与学术审核`。若该消息被读作“普通编辑协调器向 Scholar 写入或发起 Scholar 变更”，则越过 FR06 边界（Scholar 的写属 UC-07，认领申请属 UC-05）。
- R5 建议：把该依赖改为“**拒绝/转交**”语义（不写入、不改核验状态），或把 Scholar 维护依赖从 `ProfileCtl` 移到 UC-07 的协调器；不能保留可被读成“普通编辑可写 Scholar”的箭头。

### 4.2 `ProfileCtl → User`（全局缺失）

- 本 UC 的授权判定必须先确认请求者账号状态与所有权（M3/M4/M5；BR-IMP-05；SRS §8）。
- 全局只通过 `User "1" -- "1" UserProfile` 表达结构关系，没有表达“编辑协调器需要账号主体”。R5 评估补充该依赖或在职责中说明所有权来源。

### 4.3 审计与实体的关系

- 全局只有 `AuditCtl ..> Evidence`，`Evidence ..> Claim/Authorship`，没有“留痕指向被写对象”的表达。
- 本 UC 把对象指向写成 `AuditEvidence.targetProfileRef` 属性，不新增实体关联（05 §2.2），避免与全局 `Evidence` 关系冲突。R5 再统一留痕对象引用的表达方式。

### 4.4 `UserProfile — Scholar`

- 全局已有 `Scholar -- Authorship -- Work` 与 `User — Claim — Scholar` 路径，但没有 `UserProfile — Scholar` 边；本 UC 同样**不建立**该边（同名不合并、普通资料不等于学者身份）。一致，无需变更。

## 5. 与合并图/活动图的一致性

| 全局资产 | 与本 UC 的一致点 | 需注意的差异 |
|---|---|---|
| [`DGM-SEQ-04-profile.puml`](../../../uml/analysis/DGM-SEQ-04-profile.puml)（UC-04+UC-22 合并序列） | 越权/停用拒绝、普通字段保存、受控审计、普通编辑不改变学术核验状态 | ① 画了 `Profile -> Scholar` 消息（见 4.1）；② 合并图不含“待审不覆盖公开版”“私有字段不进入公开投影”“零成果空态不变”的显式步骤；③ 没有区分“未认证/停用”与“非本人”两类拒绝，本 UC 的 M6–M8 与 M9–M11 分开处理 |
| [`DGM-ACTIVITY-06-edit-profile.puml`](../../../uml/profile/DGM-ACTIVITY-06-edit-profile.puml) | 会话/账号/所有权校验 → 字段分类 → 学术转交 → 保存与发布 → 访客读取允许公开字段 | 活动图把“访客读取公开字段”画在同一活动内；本 UC 顺序图不含读侧交互（读侧属 UC-03），此处仅是不画，不是冲突 |
| [`01-BCE分析类模型.md`](../01-BCE分析类模型.md) §5.1 | “UC-22 只改本人普通字段；Claim 批准不另建主页” | 一致；本 UC 增加“拒绝也留痕”和“字段范围判定”两项可验证职责 |

## 6. 追踪文件与登记表（只提建议，不改文件）

| 文件 | 现状 | 建议（由父 Agent 决定） |
|---|---|---|
| [`03-AC-UC-分析模型追踪.md`](../03-AC-UC-分析模型追踪.md) | FR06 各行“分析模型”列只列 `DGM-USECASE-07 / ACT-06 / SEQ-04`；UC-22 反向行未含 `FR07-03`、`NF03-01` | 增补 `SEQ-09 / VOPC-05`；评估把 `AC-IMPL-FR07-03`、`AC-IMPL-NF03-01` 作为 UC-22 的补充证据登记（本 UC 只提供证据，不主张改主 UC） |
| [`analysis-class-registry.md`](../automation/analysis-class-registry.md) | 有 `PublicProfileUI`/`PublicProfileCoordinator` 行，但无 `ProfileUI`/`ProfileCoordinator`/`AuditEvidence` 行 | 增量登记三个类：首次发现 UC-22、别名冲突（`ProfileCtl`、`Evidence`/`ReviewEvidence`）、未定多重性（控制/边界到实体不定义） |
| 全局 `DGM-CLASS-01-analysis.puml` | `ProfileCtl ..> Scholar`、无 `ProfileCtl → User`、`Evidence` 属性缺对象/结果/时间 | 按第 3–4 节建议在 R5 统一分析类时处理；本 UC 不直接修改全局图 |

## 7. 差异结论

UC-22 分析支持以下全局模型调整建议：

1. 保留 `ProfileUI`、`ProfileCoordinator`、`User`、`UserProfile` 与 `User—UserProfile 1:1`；
2. 让 `ProfileCoordinator`（`ProfileCtl`）职责包含“服务端每次校验账号状态与所有权”“字段范围判定”“普通字段保存与公开投影”“待审不覆盖公开版”；
3. 把 `ProfileCtl ..> Scholar` 改为“拒绝/转交”语义，避免暗示普通编辑可写学术档案；
4. 明确 FR06 受保护写入（含拒绝/越权）的审计留痕归属，并补齐留痕的对象/结果/时间信息；
5. 拆分 `Evidence`/`ReviewEvidence`/`AuditEvidence` 的命名，避免“审核材料”与“审计留痕”混用；
6. 会话是否成为分析对象、普通字段清单与公共资料策略口径，仍需需求确认（B-01/B-02/B-04/B-06）。

本轮**不直接修改** `DGM-CLASS-01-analysis.puml`、`DGM-SEQ-04-profile.puml`、`DGM-ACTIVITY-06-edit-profile.puml` 与共享追踪/登记文件。
