# UC-04 Boundary / Control / Entity 识别

**版本：** V1.0（2026-10-21）
**方法：** 教材§5.4.2：从用例事件流识别边界类、控制类、实体类；不从现有全局BCE反向填充。
**输入：** 本目录01规约、02事件流。

## 1. 识别原则

1. **Boundary**：与外部参与者交互、接收输入或显示结果的对象。
2. **Control**：协调一个用例内的校验、创建顺序、失败处理和结果返回；不是实现层的Controller/Service。
3. **Entity**：承载业务事实、状态和持久信息；不是数据库表。
4. 只有在该UC事件流中实际承担消息的对象才进入VOPC。
5. 参与者“访客”不是系统分析类。
6. 仅因“以后可能用到”而出现的类不进入本UC。

## 2. 候选分析类

| ID | 候选类 | 类型 | 从哪些事件发现 | 业务行为 | 为什么不能由其他候选直接承担 |
|---|---|---|---|---|---|
| C1 | `RegistrationUI` | Boundary | E1、E8、A1/A2/A3 | 接收注册输入，显示校验失败、重复结果、创建失败、成功和主页地址 | `User`/`UserProfile`是业务实体，不应承担输入/显示；`RegistrationCoordinator`是内部协调者，不直接面对访客 |
| C2 | `RegistrationCoordinator` | Control | E2–E8、A1–A3 | 校验请求、检查重复、协调User与UserProfile创建、保证整体结果、返回结果 | Boundary只负责交互，不能单独保证“User+UserProfile+稳定地址+幂等”的一致性；Entity各自只知道自己，不能独立协调跨对象创建 |
| C3 | `User` | Entity | E3、E6、A6 | 承载账号标识/账号状态；与唯一UserProfile绑定；作为本人所有权主体 | UserProfile承载的是公开主页资料和稳定地址，不是账号状态；不能用UserProfile代替账号 |
| C4 | `UserProfile` | Entity | E4、E5、E7、A4 | 承载公开主页、稳定公开地址、公开可见性、零成果空态；属于一个User | User不能承载公开主页字段；Scholar是平台采集/核验的学术身份，不是注册用户的普通主页 |

## 3. 逐类选择理由

### C1 RegistrationUI（Boundary）

- 发现于E1“提交注册信息”和E8“返回注册成功/主页地址”。
- 它还承担A1/A2/A3的失败结果显示。
- 它是外部交互边界，不持久保存User/UserProfile。
- **命名说明：** 现有全局图使用`AccountUI`；`RegistrationUI`是本UC的局部边界名。是否合并为`AccountUI`留到R5统一分析类决定（见07），本UC不声称新增全局类。

### C2 RegistrationCoordinator（Control）

- 发现于E2校验、E3–E7创建/绑定/发布、A1–A3失败处理。
- 需要跨User和UserProfile协调，且要保证“要么都成功，要么都不留下半成品”。
- 重复请求检查也需要在创建前统一处理，因此不能由某个Entity单独承担。
- **命名说明：** 全局图使用`RegCtl`；本UC使用完整分析类名`RegistrationCoordinator`，R5统一时决定保留全名还是缩写。

### C3 User（Entity）

- 发现于E3创建账号、E6本人所有权、A6账号状态守卫。
- 必要业务事实：账号标识、账号状态、与UserProfile的1:1绑定。
- 不能并入UserProfile：FR04明确User与UserProfile为两类对象，且UserProfile有公开主页语义。

### C4 UserProfile（Entity）

- 发现于E4创建唯一主页、E5稳定地址、E7公开主页/零成果空态、A4零成果。
- 必要业务事实：稳定公开地址、公开可见性、所属User、普通公开资料。
- 不能并入User：UserProfile是公开主页，User是账号；A1c要求所有注册用户有主页，且主页≠Scholar。
- 不能并入Scholar：Scholar是平台采集/核验的学者身份，可无账号；UserProfile不因同名自动成为Scholar。

## 4. 被拒绝的候选类

| 候选 | 拒绝理由 | 证据/后续 |
|---|---|---|
| `Scholar` | UC-04没有向Scholar发送消息，也没有创建/修改Scholar的要求；注册后置明确“不创建Scholar” | FR04；FR03-04；SRS §12；见08 Q-05 |
| `ScholarAuthorization` / `Auth` | 本人普通主页的所有权直接来自UserProfile，不需要对象级Scholar授权；授权只在UC-05/06批准后产生 | FR04；BR-IMP-05；SRS §12 |
| `Claim` / `ClaimApplication` | 注册不是认领；无申请、证据、审核状态 | FR04；FR05；SRS §12 |
| `Authorship` / `WorkEntity` | 注册不创建成果或参与关系；零成果是空态，不是空实体 | AC-IMPL-FR03-03；FR07-03；A1c |
| `AddressAllocator` | 稳定公开地址是UserProfile的属性/职责；需求没有独立的地址生命周期、分配策略或独立状态 | FR04；AC-IMPL-FR04-03；Q-03 |
| `Session` | 本UC没有登录/会话建立消息；会话是后续访问守卫，不是注册业务实体 | FR04仅说账号状态/受保护请求；Q-07 |
| `ReviewEvidence` / `AuditEvidence` | FR04没有要求注册必须产生独立审计证据；BR-IMP-10是否适用于注册待确认 | BR-IMP-10；Q-04 |
| `RegistrationRequest` | 注册请求是控制器的输入参数/分析上下文，不是有独立生命周期的业务实体 | 教材§5.4.2；01 |
| `UserProfileView` / `DTO` | 公开视图是UserProfile的展示投影，不需要独立业务身份 | 教材§5.4.2；本UC事件流 |

## 5. User / UserProfile / Scholar边界结论

| 边界 | 结论 | 本UC证据 |
|---|---|---|
| User ↔ UserProfile | 1:1；注册成功同时存在；UserProfile属于User | FR04；AC-IMPL-FR04-03；SRS §12 |
| UserProfile ↔ Scholar | 分离；注册不创建、不合并、不认证Scholar | FR03-04；FR04；SRS §12；A1c |
| User ↔ Scholar | 注册不建立关系；只有后续认领审核通过才建立Scholar授权/关联 | FR04；FR05；FR05-04 |
| UserProfile ↔ Authorization | 本人普通资料编辑权直接来自所有权，不需要ScholarAuthorization | FR04；BR-IMP-05 |
| 同名UserProfile与Scholar | 两个对象、两个页面对象/核验状态，不自动合并 | AC-IMPL-FR03-04 |

## 6. 识别结论

本UC的候选分析类只有4个：

- Boundary：`RegistrationUI`
- Control：`RegistrationCoordinator`
- Entity：`User`、`UserProfile`

`Scholar`、`Authorization`、`Claim`、`Authorship`、`WorkEntity`、`Session`、`AddressAllocator`、`AuditEvidence`均不进入本UC VOPC。该集合将在04的顺序图中接受消息检验；若某类没有消息，则删除或降为属性/职责。
