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
