# UC-03 分析序列与逐消息职责

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-SEQ-08-profile.puml`](diagrams/DGM-SEQ-08-profile.puml)
**导出：** [SVG](diagrams/generated/DGM-SEQ-08-profile.svg) · [PNG](diagrams/generated/DGM-SEQ-08-profile.png)
**方法：** 教材 §5.4.3；只表达分析对象的协作，不写数据库、Java、API、框架、缓存或部署设计。

## 1. 顺序图范围

本序列只实现 UC-03“查看公开个人主页/学者档案”：

- 按稳定标识解析**一个**目标对象，判定其为 `UserProfile` 还是 `Scholar` 档案，不按姓名匹配；
- 校验目标对象当前公开且可访问；
- `UserProfile` 分支：读取允许公开的普通资料，判断是否有可公开成果；零成果显示“暂无公开成果”空态，不返回 404；
- `Scholar` 分支：读取公开身份与认领/核验状态（未认领给提示），读取公开且生效的参与关系与成果展示字段；
- 统一执行公开/生效/许可过滤：待核验/争议/已排除关系不显示为已确认合作或本人确认，私有证明不进入公开视图；
- 空态、不可访问、执行失败三态分离；
- 只读，不发出任何写操作。

检索（UC-01）、成果详情与来源（UC-02）、本人主页编辑（UC-22）、认领与审核（UC-05/06）、学者档案维护（UC-07）、身份核验与合并（UC-10/15）不进入本序列。

参与对象：

- `PublicProfileUI`（Boundary）
- `PublicProfileCoordinator`（Control）
- `UserProfile`（Entity）
- `Scholar`（Entity）
- `Authorship`（Entity）
- `WorkEntity`（Entity）

## 2. 逐消息职责表

| 消息 | 名称 | 发送者 | 接收者 | 事件步骤 | 接收者职责 | 依据/假设 |
|---|---|---|---|---|---|---|
| M1 | 打开稳定主页/学者档案地址（携带稳定标识） | 访客 | PublicProfileUI | E1 | 承接查看请求，携带稳定标识 | FR03；`AC-IMPL-FR03-03/04` |
| M2 | 请求公开视图（稳定标识） | PublicProfileUI | PublicProfileCoordinator | E1 | 启动公开视图协调 | FR03；分析推导 |
| M3 | 解析稳定标识并判定对象类型（UserProfile/Scholar），不按姓名匹配 | PublicProfileCoordinator | PublicProfileCoordinator | E2 | 只按稳定标识定位单个对象；同名不作为解析依据、不触发合并 | `AC-IMPL-FR03-04`；BR-IMP-03；SPEC ID-01/SR-12 |
| M4 | 校验目标对象当前公开且可访问 | PublicProfileCoordinator | PublicProfileCoordinator | E3 | 依据对象公开状态判定可访问性；不读取账号状态（Q-09） | BR-IMP-01；FR03 |
| M5 | 执行失败（与空态、不可访问区分） | PublicProfileCoordinator | PublicProfileUI | A3 | 把读取失败/超时与空态、不可访问分开 | `AC-IMPL-NF01-01`；NF01 |
| M6 | 显示执行失败，不显示“暂无公开成果” | PublicProfileUI | 访客 | A3 | 显示可区分的失败状态 | `AC-IMPL-NF01-01`；分析推导 |
| M7 | 不可访问，不返回任何资料字段 | PublicProfileCoordinator | PublicProfileUI | A1、A2 | 不展示资料/成果/关系，不泄露私有字段 | BR-IMP-01；`AC-IMPL-NF01-01` |
| M8 | 显示不可访问，与空态/失败区分 | PublicProfileUI | 访客 | A1、A2 | 显示不可访问状态；对外粒度待 Q-05 | `AC-IMPL-NF01-01`；Q-05 |
| M9 | 读取允许公开的普通资料 | PublicProfileCoordinator | UserProfile | E4 | 只返回用户允许展示的普通字段 | FR03；FR04-04；BR-IMP-11；字段集合 Q-01 |
| M10 | 公开普通资料（仅允许展示字段） | UserProfile | PublicProfileCoordinator | E4、A8 | 返回公开普通资料；凭据/联系方式等未允许公开字段不外传 | FR03；BR-IMP-11；`AC-IMPL-NF06-01`；Q-01 |
| M11 | 判断是否存在可公开学术成果 | PublicProfileCoordinator | UserProfile | E5 | 回答“有可公开成果投影”或“无可公开成果（零成果）” | `AC-IMPL-FR03-03`；FR03；来源口径 Q-03 |
| M12 | 无可公开成果（零成果）/ 有公开成果投影 | UserProfile | PublicProfileCoordinator | E5、A4 | 零成果返回空态依据，不用虚假成果填充 | `AC-IMPL-FR03-03`；Q-02/Q-03 |
| M13 | 公开主页视图 + “暂无公开成果”空态 | PublicProfileCoordinator | PublicProfileUI | A4 | 组装正常主页视图，空态不是错误 | `AC-IMPL-FR03-03`；`AC-IMPL-NF01-01` |
| M14 | 正常打开主页并显示空态（非 404） | PublicProfileUI | 访客 | A4 | 显示主页与空态文案 | `AC-IMPL-FR03-03`；Q-02 |
| M15 | 公开主页视图 + 成果展示列表（不含详情） | PublicProfileCoordinator | PublicProfileUI | E9 | 只组装公开展示字段，不展开详情 | FR03；`AC-IMPL-FR03-03`；UC-02 |
| M16 | 显示主页与成果列表；详情跳转属 UC-02 | PublicProfileUI | 访客 | E9 | 展示列表与跳转入口（不在此打开详情） | FR03；UC-02 |
| M17 | 读取公开身份信息与认领/核验状态 | PublicProfileCoordinator | Scholar | E6 | 返回公开身份（名称/别名）与状态标签 | `AC-IMPL-FR03-01`；FR03；标签口径 Q-06 |
| M18 | 公开身份 + 未认领/已认领 + 核验状态 | Scholar | PublicProfileCoordinator | E6、A6 | 提供未认领提示依据；未认领不等于不可浏览 | `AC-IMPL-FR03-01`；SRS §12；Q-06 |
| M19 | 读取当前公开且生效的参与关系与关系状态 | PublicProfileCoordinator | Authorship | E7 | 返回角色、顺序、关系状态、可公开证据范围 | FR03；BR-IMP-01；FR15 |
| M20 | 参与关系（角色/顺序/关系状态）/ 无生效关系 | Authorship | PublicProfileCoordinator | E7、A7 | 逐条返回关系与独立状态；不得把状态压成“已确认” | SPEC §1 公共状态；FR15；`AC-IMPL-FR03-02` |
| M21 | 读取关系对应成果的公开展示字段（不展开详情） | PublicProfileCoordinator | WorkEntity | E7 | 按成果稳定标识返回标题/类型/年份等公开展示字段 | FR03；SRS §7.1；UC-02 |
| M22 | 公开展示字段 / 不公开·已失效 | WorkEntity | PublicProfileCoordinator | E7、A2 | 不公开/失效成果返回不可用信号，不伪造字段 | BR-IMP-01；SRS §7.1 |
| M23 | 公开/生效/许可过滤；不显示为已确认合作或本人确认；屏蔽私有证明；不按姓名补并同名对象 | PublicProfileCoordinator | PublicProfileCoordinator | E8 | 统一公开最小化与关系状态守卫；不合并同名对象、不补造学者身份 | `AC-IMPL-FR03-02/04`；BR-IMP-01/03/11；`AC-IMPL-NF06-01` |
| M24 | 学者档案视图 + 未认领提示 + 已生效成果 | PublicProfileCoordinator | PublicProfileUI | A6 | 组装未认领档案视图 | `AC-IMPL-FR03-01`；FR03 |
| M25 | 显示学者档案、未认领提示与生效成果 | PublicProfileUI | 访客 | A6 | 显示未认领提示与生效成果，不标成已认证 | `AC-IMPL-FR03-01`；BR-IMP-03 |
| M26 | 学者档案视图 + 认领/核验状态 + 已生效成果 | PublicProfileCoordinator | PublicProfileUI | E9 | 组装已认领档案视图与状态标签 | FR03；`AC-IMPL-FR03-01`；Q-06 |
| M27 | 显示学者档案与生效成果 | PublicProfileUI | 访客 | E9、E10 | 展示档案、状态标签与生效成果；无编辑/认领/审核入口 | FR03；FR04-04；BR-IMP-05 |

## 3. 关键协作说明

### 3.1 一个请求只呈现一个对象（核心）

- M1/M2 携带的是**稳定标识**，M3 据此判定目标类型，不按姓名检索、不把同名对象作为候选（`AC-IMPL-FR03-04`；BR-IMP-03；SPEC ID-01/SR-12）。
- M9–M16 与 M17–M27 是两个互斥分支：同一请求不会同时呈现 UserProfile 与 Scholar 档案。
- 同名并存时，访客分别打开两个链接；两个页面各自显示对象类型与核验状态，M23 明确禁止补并同名对象、禁止把 `UserProfile` 呈现为已认证学者。

### 3.2 三态分离：空态 / 不可访问 / 执行失败

| 状态 | 消息 | 语义 |
|---|---|---|
| 执行失败 | M5/M6 | 读取依赖失败或超时；不显示空态，不显示“暂无公开成果” |
| 不可访问 | M7/M8 | 标识无法定位对象，或对象当前不公开/已失效；不展示任何字段 |
| 零成果空态 | M13/M14 | 对象公开、读取成功，但没有可公开学术成果；正常页面，非 404 |

三者不共用消息与文案（`AC-IMPL-NF01-01`；`AC-IMPL-FR03-03`）。M5 的失败守卫覆盖 M9–M21 的任一次实体读取失败或超时，不只覆盖 M3/M4；M23 之后不再新增读取。

### 3.3 零成果不是错误

M11/M12 只回答“是否有可公开成果”。零成果时 M13/M14 组装**正常主页**与“暂无公开成果”空态：

- 不返回 404，不只显示空白页（`AC-IMPL-FR03-03`）；
- 不用虚假/推测成果填充；
- 空态文案是否统一覆盖四类成果、部分类型为零如何展示属 Q-02；
- `UserProfile` 成果区的数据来源口径（是否经 FR05 批准关联的 Scholar 生效成果）属 Q-03，本序列不建立 `UserProfile—WorkEntity` 关系。

### 3.4 未认领 Scholar 仍可公开

M17/M18 同时返回公开身份与认领/核验状态；M23 只做公开过滤，不因未认领而隐藏档案；M24/M25 在同一次展示中给出未认领提示（`AC-IMPL-FR03-01`；SRS §12）。本序列不读取认领申请、证据或授权记录（那些属 UC-05/06），也不显示审核材料。

### 3.5 关系状态不得冒充确认

- M19/M20 读取 `Authorship` 的角色、顺序与**独立关系状态**（自动关联/本人确认/待核验/争议/已排除）。
- M23 保证：待核验/争议/已排除关系不出现在“已确认合作”“本人确认”的展示位置；争议/未生效关系不进入公开列表；管理员审核事件不等于本人确认（SPEC ID-06；FR15；`AC-IMPL-FR03-02`）。
- 待核验关系是否以“待核验”标签公开、还是完全不进入公开列表，正式基线未统一（Q-04）；本序列实现为“绝不显示为已确认”。
- M21/M22 只取成果的**公开展示字段**，不展开详情、来源、版本与外链（UC-02）；成果不公开/已失效时该条关系不进入公开列表。

### 3.6 公开最小化

M10 只交出允许公开的普通字段；M23 统一屏蔽凭据、审核材料、私有证明材料原文与未允许公开字段（BR-IMP-11；`AC-IMPL-NF06-01`；FR04-04）。前端隐藏不代替服务端过滤。

### 3.7 只读边界

M1–M27 中没有任何创建、修改、删除业务实体的消息：不写 `UserProfile`、不写 `Scholar` 状态、不改 `Authorship` 关系状态、不改 `WorkEntity`。编辑（UC-22）、认领/审核（UC-05/06）、档案维护（UC-07）、确认纠错（UC-08）、身份核验（UC-10）不在本序列。

## 4. 事件/备选覆盖核对

| 事件/备选 | 是否需要独立消息 | 对应消息 | 结论 |
|---|---|---|---|
| E1 打开链接 | 是 | M1/M2 | 已覆盖 |
| E2 解析对象类型、不按姓名 | 是 | M3 | 已覆盖 |
| E3 校验公开可访问 | 是 | M4（分支出口 M7/M8） | 已覆盖 |
| E4 UserProfile 公开普通资料 | 是 | M9/M10 | 已覆盖 |
| E5 零成果空态 | 是 | M11–M14 | 已覆盖 |
| E6 Scholar 身份与认领/核验状态 | 是 | M17/M18 | 已覆盖 |
| E7 Scholar 生效关系与成果字段 | 是 | M19–M22 | 已覆盖 |
| E8 统一公开/状态过滤 | 是 | M23 | 已覆盖 |
| E9 返回单一对象视图 | 是 | M15、M26 | 已覆盖 |
| E10 只读展示、无写入口 | 否（由 M27 与只读约束表达） | M27；§3.7 | 已覆盖，不需要写消息 |
| A1 无法定位对象 | 是 | M7/M8 | 已覆盖 |
| A2 对象不公开/已失效 | 是 | M7/M8（对象层）、M22（成果层） | 已覆盖，两个层级分别处理 |
| A3 读取失败/超时 | 是 | M5/M6 | 已覆盖 |
| A4 UserProfile 零成果 | 是 | M13/M14 | 已覆盖 |
| A5 同名 UserProfile 与 Scholar 并存 | 否 | M3（按稳定标识解析、不合并）+ M23（不补并）+ M1（分别打开链接） | 无需单独消息，靠解析与过滤保证 |
| A6 Scholar 未认领 | 是 | M24/M25 | 已覆盖 |
| A7 待核验/争议/已排除关系 | 是 | M20（返回独立状态）+ M23（不冒充、不公开） | 已覆盖 |
| A8 私有证明/未允许公开字段 | 否（由过滤保证） | M10、M23 | 无需单独消息 |

覆盖结论：未发现需要新增消息的遗漏；A5/A8 由解析规则与统一过滤表达，不额外引入合并器或证据类。

## 5. 消息完整性检查

| 检查 | 结果 |
|---|---|
| 每条消息有发送者/接收者 | 是，M1–M27 |
| 接收者有明确职责 | 是 |
| 每条消息可追溯到事件步骤 | 是：E1–E10、A1–A8（覆盖表见第 4 节） |
| 是否出现实现技术 | 否（无数据库/API/框架/缓存） |
| 是否把 Actor 画成系统类 | 否；访客是参与者，Scholar/成果是系统内实体 |
| 空态/不可访问/失败是否混用 | 否：M13/M14（空态）、M7/M8（不可访问）、M5/M6（失败）分离 |
| 是否把待核验关系画成已确认 | 否：M20 保留独立状态，M23 禁止冒充 |
| 是否把同名对象画成合并 | 否：M3 按稳定标识解析，M23 禁止补并 |
| 是否出现写/删除消息 | 否 |
