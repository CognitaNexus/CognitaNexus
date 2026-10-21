# UC-04 分析序列与逐消息职责

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-SEQ-05-register.puml`](diagrams/DGM-SEQ-05-register.puml)
**导出：** [SVG](diagrams/generated/DGM-SEQ-05-register.svg) · [PNG](diagrams/generated/DGM-SEQ-05-register.png)
**方法：** 教材§5.4.3；只表达分析对象协作，不写Java、数据库、HTTP/API或消息中间件。

## 1. 顺序图范围

本序列只实现UC-04的注册主流程，以及A1校验失败、A2重复请求、A3创建失败分支。公开主页浏览的完整交互属于UC-03；本人编辑属于UC-22；Scholar认领属于UC-05/06。它们只作为本UC的后置条件或权限守卫出现，不进入本序列。

参与对象：

- `RegistrationUI`（Boundary）
- `RegistrationCoordinator`（Control）
- `User`（Entity）
- `UserProfile`（Entity）

## 2. 逐消息职责表

| 消息 | 名称 | 发送者 | 接收者 | 事件步骤 | 接收者职责 | 依据/假设 |
|---|---|---|---|---|---|---|
| M1 | 提交注册信息 | 访客 | RegistrationUI | E1 | 接收注册输入并准备提交 | FR04；UC-04 |
| M2 | 请求注册 | RegistrationUI | RegistrationCoordinator | E1 | 启动注册用例协调 | FR04；分析推导 |
| M3 | 校验注册信息与账号资格 | RegistrationCoordinator | RegistrationCoordinator | E2 | 校验输入和可继续条件；不合格转A1 | FR04成功条件；具体规则Q-01 |
| M4 | 拒绝注册并说明原因 | RegistrationCoordinator | RegistrationUI | A1 | 返回可区分的失败原因，不创建实体 | FR04；分析推导 |
| M5 | 显示校验失败 | RegistrationUI | 访客 | A1 | 显示校验失败，不展示成功主页 | FR04；分析推导 |
| M6 | 检查重复请求/既有主页 | RegistrationCoordinator | RegistrationCoordinator | A2 | 在创建前识别重复/重试；不创建第二主页 | FR04-03；**业务幂等已确认，识别键A-01未冻结** |
| M7 | 不创建第二个主页；返回已有结果 | RegistrationCoordinator | RegistrationUI | A2 | 维持幂等结果；返回形式待定 | FR04-03；Q-02 |
| M8 | 显示已有账号/主页结果 | RegistrationUI | 访客 | A2 | 告知已有结果，不将重复请求标成新注册 | FR04-03；分析推导 |
| M9 | 创建User | RegistrationCoordinator | User | E3 | 创建账号对象，记录账号标识/状态 | FR04；SRS §12 |
| M10 | 创建与该User绑定的唯一UserProfile | RegistrationCoordinator | UserProfile | E4 | 创建与User绑定的唯一主页 | FR04；AC-IMPL-FR04-03 |
| M11 | 绑定唯一、稳定、可分享的公开地址 | RegistrationCoordinator | UserProfile | E5 | 为UserProfile绑定唯一、稳定、可分享地址 | FR04；AC-IMPL-FR04-03；格式Q-03 |
| M12 | 使本次业务创建整体不生效 | RegistrationCoordinator | RegistrationCoordinator | A3 | 保证不留下无主主页/半完成账号 | FR04“一次性创建”；AC-IMPL-FR04-03 |
| M13 | 注册失败；无账号/无主页 | RegistrationCoordinator | RegistrationUI | A3 | 返回失败，不返回虚假成功 | FR04；分析推导 |
| M14 | 显示失败与重试入口 | RegistrationUI | 访客 | A3 | 显示可重试失败，不显示空态冒充成功 | FR04；分析推导 |
| M15 | 设置公开可见性与零成果空态 | RegistrationCoordinator | UserProfile | E7 | 发布公开主页；零成果显示空态 | FR03-03；A1c；SRS §12 |
| M16 | 返回注册成功与公开主页地址 | RegistrationCoordinator | RegistrationUI | E8 | 返回账号引用、公开地址和成功结果 | FR04；AC-IMPL-FR04-03 |
| M17 | 显示成功与主页地址 | RegistrationUI | 访客 | E8 | 展示成功结果和可分享地址 | FR04；分析推导 |

## 3. 关键协作说明

### 3.1 注册账号与创建主页的一致性

M9–M11是同一个注册成功结果的分析级组合：

- M9创建User；
- M10创建唯一UserProfile；
- M11绑定唯一、稳定、可分享地址；
- 任一步失败，M12要求本次业务创建整体不生效，不能出现“有User无主页”或“有无主主页”；
- 成功才进入M15/M16，公开主页和成功结果不能先于完整性成立。

此处只定义业务一致性，不指定数据库事务、补偿算法、锁或消息。具体实现属于后续设计。

### 3.2 重复注册

M6在M9之前检查重复/重试（业务幂等已确认；识别键/保留时长待Q-02）。若已有结果：

- 不执行M9–M11；
- 不创建第二个UserProfile；
- M7/M8只返回已有结果，具体是“同一页面/错误提示/幂等响应”尚未冻结（Q-02）。

M6没有单独引入`IdempotencyStore`或`RegistrationRequest`类，因为正式基线没有要求该对象的独立生命周期；识别键和存放方式是后续设计/统一分析类问题（A-01）。

### 3.3 创建/地址分配失败

M10或M11失败时：

- M12使本次业务创建整体不生效；
- M13/M14返回失败，不显示“暂无公开成果”；
- 既有其他公开主页、Scholar档案、授权不受影响（FR04-02的后置边界）。

### 3.4 零成果与Scholar边界

- M15只设置UserProfile公开可见性和零成果空态，不创建WorkEntity或Authorship。
- 本序列没有向Scholar发送消息，也没有创建ScholarAuthorization/Claim；注册成功不产生Scholar关系。
- 同名Scholar的区分由UC-03/FR03-04验证，本序列不把Scholar拉进来充当参与者。

### 3.5 公开读取与受保护写

本序列在M17结束于注册成功显示。以下后置条件由后续UC/权限守卫验证，不在本序列展开：

- 公开读取主页（UC-03；FR04-04）；
- 本人普通资料编辑（UC-22；FR06）；
- 未登录/停用账号的受保护写拒绝（A6/A7；FR04-02/04）。


## 3.6 E6/E9/A4–A6 与顺序图覆盖核对

| 事件 | 是否需要独立对象交互 | 对应消息/说明 | 结论 |
|---|---|---|---|
| E6 授予本人普通资料编辑权 | 否 | 所有权由M10/M11建立User 1:1 UserProfile时自然成立；M16返回成功结果。没有独立的`Authorization`消息。 | 不需要授权实体；不创建ScholarAuthorization |
| E9 公开读/受保护写守卫 | 否 | M17结束于注册成功；公开读取属于UC-03，受保护写属于UC-22/权限守卫。 | 本UC只保留后置条件，不扩张交互 |
| A4 零成果空态 | 是，已覆盖 | M15设置公开可见性与零成果空态；不创建WorkEntity/Authorship。 | 已覆盖 |
| A5 同名Scholar | 否 | 本UC没有Scholar消息；同名区分由UC-03/FR03-04验证。 | 不把Scholar加入VOPC，不合并 |
| A6 停用/退出后受保护请求 | 否 | 注册流程内无停用/退出消息；该条件是后续访问守卫，拒绝但不删除已有公开Scholar档案。 | 后置守卫，不新增注册消息 |

覆盖结论：E6/E9/A5/A6无需独立消息，原因分别是所有权随1:1建立、跨UC守卫、非参与对象、后续访问守卫；A4已经由M15覆盖。未发现需要修改顺序图的遗漏。

## 4. 消息完整性检查

| 检查 | 结果 |
|---|---|
| 每条消息是否有发送者/接收者 | 是，M1–M17 |
| 接收者是否有职责描述 | 是 |
| 是否对应事件流步骤 | 是；M1–M5/E1–E2/A1，M6–M8/A2，M9–M17/E3–E8/A3 |
| 是否出现实现技术 | 否 |
| 是否出现参与者作为系统类 | 否；访客只在图外作为Actor |
| 是否有无依据的新业务规则 | 否；规则来源/假设见表 |
