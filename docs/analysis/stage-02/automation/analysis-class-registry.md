# 跨 UC 候选分析类登记表（R5 输入）

**状态：** 仅登记，不据此修改全局分析类图。  
**来源：** UC-01、UC-04 已交付材料；后续每完成一个 UC 增量登记。

| 正式名称 | BCE | 首次发现 UC | 后续参与 UC | 主要职责 | 必要业务属性 | 已有关系 | 未定多重性 | 别名/冲突 | R5 建议 |
|---|---|---|---|---|---|---|---|---|---|
| SearchUI | Boundary | UC-01 | UC-02 | 检索输入、结果/空/失败展示 | 无持久业务属性 | → SearchCoordinator | 不定义持久多重性 | 全局同名 | 保留 |
| SearchCoordinator | Control | UC-01 | UC-02 | 范围校验、公开过滤、条件交集、稳定排序/分页、空/失败分离 | 查询上下文（单次分析状态） | → WorkEntity、→ Scholar | 不定义持久多重性 | 全局 `SearchCtl` | 统一全名 |
| WorkEntity | Entity | UC-01 | UC-02/16 | 公开有效成果查询与展示 | stableId、publicState、type、year、field、occurredInstitution、searchableText | 被 SearchCoordinator 查询 | 未定 | 全局 `Work` | 以 `Work` 为正式名 |
| Scholar | Entity | UC-01 | UC-03/05/07/10/12/14/15 | 公开学者身份/检索/档案 | stableId、publicState、searchableIdentity | 被查询；后续认领/参与 | 未定 | 不得与 UserProfile 合并 | 明确 UserProfile≠Scholar |
| RegistrationUI | Boundary | UC-04 | — | 注册输入/结果/失败 | 无持久业务属性 | → RegistrationCoordinator | 不定义持久多重性 | 全局 `AccountUI` | 统一命名 |
| RegistrationCoordinator | Control | UC-04 | — | 注册校验、User+UserProfile 创建、稳定地址、幂等/回滚 | 注册请求状态（单次） | → User、→ UserProfile | 不定义持久多重性 | 全局 `RegCtl` | 统一命名 |
| User | Entity | UC-04 | UC-05/06/22 | 账号、会话、对象授权主体 | stableId、accountState | 1:1 UserProfile；提交 Claim | 1:1（UserProfile） | 不等于 Scholar | 保留 |
| UserProfile | Entity | UC-04 | UC-03/22 | 唯一公开普通主页 | stableId/publicUrl、公开普通资料、零成果空态 | 属于 User | 1:1 | 不强制对应 Scholar | 保留 |
| ProfileUI | Boundary | UC-22 | — | 本人普通主页编辑输入/拒绝/失败/结果 | 无持久业务属性 | → ProfileCoordinator | 不定义持久多重性 | 全局同名 | 保留 |
| ProfileCoordinator | Control | UC-22 | UC-07 | 身份/归属/字段范围守卫；普通字段保存；公开投影 | 单次编辑请求状态 | → User/UserProfile/AuditEvidence | 不定义持久多重性 | 全局 `ProfileCtl` | 统一命名 |
| ClaimUI | Boundary | UC-05 | UC-06 | 认领提交/查询、缺失项、状态与失败 | 无持久业务属性 | → ClaimCoordinator | 不定义持久多重性 | 全局同名 | 保留 |
| ClaimCoordinator | Control | UC-05 | UC-06 | 证据校验、目标/账号守卫、申请创建、竞争事实读取、本人查询 | 单次申请/查询状态 | → User/Scholar/ClaimApplication/ReviewEvidence/ScholarAuthorization(只读) | 不定义持久多重性 | 全局 `ClaimCtl` | 统一命名 |
| ClaimApplication | Entity | UC-05 | UC-06 | 认领申请、状态、理由/缺失项、竞争事实 | applicationRef/applicantRef/targetScholarRef/evidenceRefs/status/statusReason/competitionFact/submittedAt | →User 0..*:1；→Scholar 0..*:1；→ReviewEvidence 未定 | 0..*:1（User/Scholar） | 全局 `Claim` | 统一为 ClaimApplication |
| ReviewEvidence | Entity | UC-05 | UC-06/17 | 申请证据、可见范围、审核材料 | evidence items/materialRef/visibilityScope | 被 ClaimApplication 引用 | 未定 | 全局 `Evidence`/`AuditEvidence` | R5 统一（D-13） |
| AuditEvidence | Entity | UC-22 | UC-17 | 受保护写入/拒绝/越权留痕 | actorIdentity/targetProfileRef/reasonOrOutcome/beforeAfterValues/occurredAt | 被 ProfileCoordinator 创建 | 未定 | 全局 `Evidence`/`ReviewEvidence` | R5 统一（D-13） |
| PublicProfileUI | Boundary | UC-03 | UC-22 | 公开主页/Scholar档案展示、空态、不可访问 | 无持久业务属性 | → PublicProfileCoordinator | 不定义持久多重性 | 全局 `PublicUI` 邻近 | R5 统一公共门户边界 |
| PublicProfileCoordinator | Control | UC-03 | UC-22 | 按稳定地址读取 UserProfile 或 Scholar；对象类型/状态区分；公开过滤 | 单次主页请求状态 | → UserProfile/Scholar/Authorship/WorkEntity | 不定义持久多重性 | 无全局同名 | R5 评估 ProfileCtl 边界 |
| WorkDetailUI | Boundary | UC-02 | — | 详情请求、详情/不可访问/失败/外链失败/处理中展示 | 无持久业务属性 | → WorkDetailCoordinator | 不定义持久多重性 | 全局 `PublicUI`/`SearchUI` 邻近 | R5 评估统一公共详情边界 |
| WorkDetailCoordinator | Control | UC-02 | — | 读取一条成果、类型字段、来源、版本证据、参与关系；公开/许可过滤；空/失败分离 | 单次详情请求状态 | → WorkEntity/SourceRecord/WorkVersion/Authorship | 不定义持久多重性 | 与 `SearchCtl` 职责边界待定 | R5 决定独立或扩展 |
| SourceRecord | Entity | UC-02 | UC-09/16/18/19 | 来源记录、许可、原值、到达时间、原站地址 | sourceId/sourceDomain/rightsScope/arrivalAt/sourceRecordKey/rawValues/dataOrigin/originalUrl | 被 WorkEntity 聚合 1:0..* | 1:0..* | 全局 `Source` | 以 `SourceRecord` 为正式名 |
| WorkVersion | Entity | UC-02 | UC-09/16/19 | 有明示证据的版本关系 | versionKind/versionEvidence/linkedSourceId/publicOk | 属于 WorkEntity 1:0..* | 1:0..* | 全局 `Version` | 统一命名 |
| Authorship | Entity | UC-02 | UC-07/08/10/14/15/16 | 学者—成果参与关系、角色/顺序/状态/证据 | participantDisplay/role/order/relationState/evidencePublic | 关联 WorkEntity 0..*:1；后续关联 Scholar | 0..*:1 | 角色顺序归属与 §7.1 有差异 | R5 确认 |
