# UC-04 分析类职责、属性、关系与分析机制

**版本：** V1.0（2026-10-21）
**方法：** 教材§5.5.1职责、§5.5.2属性、§5.5.3关系、§5.5.4分析机制。
**范围：** 只定义UC-04能够证实的部分；跨用例统一关系不在此擅自补齐。

## 1. 定义原则

1. “做（Do）”型职责说明对象能执行/协调什么；“知道（Know）”型职责说明对象必须知道/提供什么。
2. 属性只写业务信息，不写数据库字段、主键类型、索引或API参数。
3. 关系只写有消息或正式业务约束支持的；无依据的跨用例关系留待R5。
4. 控制类通常没有长期业务属性；其请求上下文是单次用例的分析状态。
5. 具体输入规则、幂等键、URL格式等未冻结项不伪装成类定义。

## 2. RegistrationUI（Boundary）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Boundary | §5.4.2；M1/M4–M8/M13–M17 |
| Do | 接收注册输入；提交给协调器；显示校验失败、重复结果、创建失败、成功和公开主页地址 | E1、E8、A1–A3 |
| 候选分析操作（`/`） | `/接收注册请求`、`/显示注册结果`、`/显示失败或重复结果` | M1–M8、M13–M17 |
| Know | 当前输入和当前展示状态；不持久保存User/UserProfile | 分析推导 |
| 业务属性 | 无持久业务属性；注册输入是短期请求数据，不是Entity | 教材§5.5.2（Boundary通常不需要业务属性） |
| 关系 | 单向关联`RegistrationCoordinator`（M2/M4/M7/M13/M16） | 04消息表 |
| 不能承担 | 不能决定账号唯一性、1:1绑定、地址分配或失败回滚；这些需Control协调 | FR04；AC-IMPL-FR04-03 |
| 分析机制 | 显示最小化；不得把凭据/审核材料回显到公开结果 | BR-IMP-11；AC-IMPL-FR04-04 |

## 3. RegistrationCoordinator（Control）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Control | §5.4.2；M2–M4/M6–M7/M9–M16 |
| Do | 校验注册请求；检查重复/重试；协调`User`与`UserProfile`创建；绑定稳定地址；保证整体不生效或整体成功；返回结果 | E2–E8；A1–A3 |
| 候选分析操作（`/`） | `/校验注册`、`/检查重复请求`、`/创建账号与主页`、`/使整体生效或不生效`、`/返回注册结果` | M2–M16 |
| Know | 本次请求的校验结果、重复检查结果、创建结果和返回结果；知道必须同时满足User/UserProfile/地址条件 | 分析推导 |
| 业务属性 | 无长期业务属性。分析级状态：注册请求上下文、校验结果、幂等检查结果、已创建对象引用（均为单次用例状态） | §5.5.2；不指定实现存储 |
| 关系 | 与`RegistrationUI`、`User`（M9）、`UserProfile`（M10/M11/M15）的单向关联（按消息方向） | 04消息表；VOPC |
| 不能承担 | 不承载账号状态（User的Know）；不承载公开主页数据（UserProfile的Know）；不是实现层Controller/Service | §5.4.2；§5.5.1 |
| 分析机制 | 原子性/整体生效；幂等；失败可重试；稳定地址唯一性；不向公开响应泄漏秘密 | FR04；AC-IMPL-FR04-03；BR-IMP-01/11 |

**待确认：** M6的幂等识别键与重复结果形式（Q-02）；M12“整体不生效”的实现方式（设计阶段）。

## 4. User（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M9；E3/E6/A6 |
| Do | 被创建；提供账号状态；与唯一UserProfile绑定；作为本人主页所有权的账号主体 | FR04；AC-IMPL-FR04-02/03；BR-IMP-05 |
| 候选分析操作（`/`） | `/校验账号状态`、`/绑定唯一主页` | M9；A6 |
| Know | 自己的账号标识、账号状态、绑定的UserProfile | FR04；SRS §12 |
| 业务属性 | `accountIdentifier`：账号标识（唯一性/格式规则待J02）；`accountStatus`：可用/停用；`profile`：与UserProfile的1:1引用 | FR04；AC-IMPL-FR04-02/03；SRS §12 |
| 关系 | `User 1 — 1 UserProfile`（唯一拥有）；不建立User—Scholar强制关系 | FR04；SRS §12；FR03-04 |
| 不能混淆 | User不是Scholar；注册不因昵称/简介生成Scholar；User不承载公开主页资料 | FR04；FR03-04；A1c |
| 分析机制 | 持久性；账号状态校验；对象所有权；稳定标识（具体规则Q-01/Q-03） | FR04；BR-IMP-05 |

## 5. UserProfile（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M10/M11/M15；E4/E5/E7/A4 |
| Do | 被创建；绑定稳定公开地址；发布公开视图；提供零成果空态；向公开读取提供允许公开的普通资料 | FR04；FR03；AC-IMPL-FR03-03/04；A1c |
| 候选分析操作（`/`） | `/绑定稳定公开地址`、`/发布公开视图`、`/判断零成果空态` | M10/M11/M15；A4 |
| Know | 所属User；稳定公开地址；公开可见性；普通公开资料集合；是否已有公开成果（用于空态判断） | FR03；FR04；SRS §12 |
| 业务属性 | `stablePublicAddress`：稳定、可分享的公开地址；`owner`：所属User（1:1）；`publicVisibility`：公开可见性；`ordinaryPublicProfileData`：普通公开资料集合（具体字段来自FR06，本UC不初始化具体值）；空态是派生显示状态，不是伪造的成果实体 | FR04；AC-IMPL-FR04-03；FR03-03；FR06 |
| 关系 | `UserProfile 1 — 1 User`（属于/唯一）；不与Scholar强制合并；不由注册创建Authorization/Claim | FR04；SRS §12；FR03-04；A1c |
| 不能混淆 | 不是Scholar档案；不是学术认证；普通资料不能成为合作边/统计依据；零成果不等于404 | FR03-04；FR06；A1c；AC-IMPL-FR03-03 |
| 分析机制 | 持久性；稳定公开地址唯一/可分享；公开/私有字段分离；最小化；零成果空态；不因账号停用删除公开Scholar档案 | FR04-02/03/04；FR03；BR-IMP-01/11 |

**未定义的具体字段：** 昵称、头像、简介、兴趣领域等属于FR06/UC-22；本UC只确认UserProfile存在且公开，不在注册分析中臆造字段是否已填写或默认值。

## 6. 关系定义汇总

| 关系 | 角色/方向 | 多重性 | 来源 | 状态 |
|---|---|---|---|---|
| RegistrationUI → RegistrationCoordinator | 请求/显示；UI→Coordinator | 不定义持久多重性 | M2/M4/M7/M13/M16 | 已定义（单向关联） |
| RegistrationCoordinator → User | 创建/校验账号 | 不定义持久多重性 | M9 | 已定义（单向关联） |
| RegistrationCoordinator → UserProfile | 创建/绑定/发布 | 不定义持久多重性 | M10/M11/M15 | 已定义（单向关联） |
| User — UserProfile | User唯一拥有UserProfile | 1—1 | FR04；AC-IMPL-FR04-03；SRS §12 | 已证实 |
| UserProfile — Scholar | **本UC不建立** | 不定义 | FR03-04；SRS §12；A1c | 留待R5/UC-03，禁止在本UC擅定 |
| User — ScholarAuthorization | **本UC不建立** | 不定义 | FR04；BR-IMP-05；FR05 | 留待UC-05/06，禁止在本UC擅定 |
| UserProfile — Claim/Authorship | **本UC不建立** | 不定义 | FR04；FR05；FR07 | 不属于本UC |

## 7. 分析机制汇总

| 机制 | 作用对象 | 业务问题 | 本UC可证实的约束 | 未决定/后续 |
|---|---|---|---|---|
| 持久性 | User、UserProfile | 注册成功后账号和主页必须可再次读取 | User/UserProfile存在且1:1；主页可公开访问 | 存储粒度/保留期 |
| 原子性/整体生效 | RegistrationCoordinator | 不能有User无主页或有主页无User | 创建/地址任一步失败，本次结果整体不生效 | 实现事务/补偿方式 |
| 幂等 | RegistrationCoordinator/UserProfile | 重复注册不得产生第二主页 | 重试不增加UserProfile、不产生无主主页 | 幂等键、保留时长Q-02 |
| 稳定公开标识 | UserProfile | 主页地址必须可分享、稳定 | 注册成功绑定稳定地址 | 格式/分配/变更Q-03 |
| 访问控制/最小化 | RegistrationUI、UserProfile | 公开读、受保护写；秘密不公开 | 未登录写拒绝；公开响应不含凭据/材料 | 会话/字段级策略Q-05/Q-07 |
| 失败恢复 | RegistrationCoordinator、RegistrationUI | 创建/地址失败后可重试且不产生半成品 | 失败不显示成功；无无主主页；可重试 | 重试提示/限制 |

## 8. 与UC-22、UC-05/06的边界

- 本UC不定义`ProfileUI`、`ProfileCoordinator`：它们是UC-22的编辑职责，不是注册职责。
- 本UC不定义`ClaimUI`、`ClaimReviewCoordinator`、`ScholarAuthorization`：认领/授权在UC-05/06。
- `UserProfile`的普通资料编辑权来自`User`所有权；不是Scholar对象授权。该区别是本UC最重要的类边界。
