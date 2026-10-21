# UC-17 分析序列与逐消息职责

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-SEQ-16-admin-audit.puml`](diagrams/DGM-SEQ-16-admin-audit.puml)
**导出：** [SVG](diagrams/generated/DGM-SEQ-16-admin-audit.svg) · [PNG](diagrams/generated/DGM-SEQ-16-admin-audit.png)
**方法：** 教材 §5.4.3；只表达分析对象协作，不写 Java、数据库、HTTP/API、索引、缓存、消息中间件或查询算法。

## 1. 顺序图范围

本序列实现 UC-17“后台待办与审计”：主流程 E1–E18，以及 A1（未认证/停用）、A2（普通用户越权）、A3（缺类别权限）、A4（审计范围裁剪）、A5（证明材料原件）、A6（留痕不完整）、A7（空结果）、A8（查询失败）、A9（审计只读）、A10（状态已变）、A11（请求含处置动作）、A12（保留期限未冻结）、A13（同一对象多条留痕）、A14（敏感字段遮蔽）分支；A12 与 A13 以守卫/返回约束表达，无独立交互循环。

参与对象（共 13 个；其中 2 个为参与者，不进入 VOPC）：

- 参与者（图外或图左侧）：管理员（主要参与者）、普通用户／未授权请求者（次要参与者，仅触发拒绝路径）
- `AdminConsole`（Boundary）
- `AuditCoordinator`（Control）
- `User`（Entity，只读校验）
- `CollectionTask`（Entity，只读）
- `DedupCandidate`（Entity，只读）
- `IdentityCandidate`（Entity，只读）
- `ClaimApplication`（Entity，只读）
- `ScholarAuthorization`（Entity，只读）
- `Authorship`（Entity，只读）
- `AuditEvidence`（Entity）
- `ReviewEvidence`（Entity，只读，遮蔽）

> 图中不出现 `PendingTodo`/`WorkflowEngine`/`ApprovalChain`/`Assignee`/`SlaTimer`/`CorrectionRequest`/`ScholarChangeRequest`/`SourceRecord`/`WorkEntity`：待办不是实体、不建工作流、不执行属主 UC 的处置、不读取变更申请内容（B-07）。

## 2. 逐消息职责表

| 消息 | 名称 | 发送者 | 接收者 | 事件步骤 | 接收者职责 | 依据/假设 |
|---|---|---|---|---|---|---|
| M1 | 打开统一后台入口（采集/异常治理/学者核验/认领审核/成果变更/账号·分类·来源维护）（E1） | 管理员 | AdminConsole | E1 | 接收入口请求，发起后台入口会话 | FR14 统一入口；§3.2；`DGM-USECASE-06` |
| M2 | 请求后台入口与待办视图（已认证请求上下文） | AdminConsole | AuditCoordinator | E1/E2 | 启动服务端校验与待办聚合 | 分析推导；§8“服务端执行” |
| M3 | 校验账号状态（E2） | AuditCoordinator | User | E2 | 提供账号状态（可用/停用） | NF03；`AC-IMPL-NF03-01`；`AC-IMPL-FR04-02` |
| M4 | 校验具体管理权限与可见类别：非通用超级管理员（E2） | AuditCoordinator | User | E2 | 提供类别级/范围级管理权限事实 | FR14“不同管理员仍遵守对应权限”；§8；B-01 |
| M5 | 类别权限守卫：缺少该类别具体权限的项不列出、不因“是管理员”放行（A3） | AuditCoordinator | AuditCoordinator | A3 | 判定类别可见性；拒绝越权类别 | FR14；§8；B-01 |
| M6 | 按权限裁剪可访问类别与范围：不显示不等于授权（E3） | AuditCoordinator | AuditCoordinator | E3 | 裁剪类别与范围 | 分析推导；§8 |
| M7 | 返回可访问类别与范围（E3） | AuditCoordinator | AdminConsole | E3 | 返回入口类别与范围 | E3；M5/M6 |
| M8 | 显示后台入口与范围说明（E3） | AdminConsole | 管理员 | E3 | 显示入口与范围（未显示≠已授权） | §8；分析推导 |
| M9 | 只读读取采集/增量任务待处理事实（失败/异常移交/待核验计数）（E4） | AuditCoordinator | CollectionTask | E4 | 提供任务状态、失败点、待核验计数 | FR08；FR10；§6.2；`AC-IMPL-FR08-01` |
| M10 | 返回任务状态与待核验计数：批次成功不等于治理全部通过（E4） | CollectionTask | AuditCoordinator | E4 | 返回只读任务事实 | FR08 规范性要求；§6.2 |
| M11 | 只读读取异常治理待处理项（成果候选/字段异常状态与计数）（E5） | AuditCoordinator | DedupCandidate | E5 | 提供治理待处理项状态与计数 | FR09；UC-09；`AC-IMPL-FR09-02` |
| M12 | 返回异常治理待处理项（E5） | DedupCandidate | AuditCoordinator | E5 | 返回只读候选/异常事实 | FR09；分析推导 |
| M13 | 只读读取学者核验待处理项（身份/归属候选与争议状态、计数）（E6） | AuditCoordinator | IdentityCandidate | E6 | 提供身份/归属候选与争议状态 | FR15；UC-10；`AC-IMPL-FR15-01` |
| M14 | 返回学者核验待处理项（E6） | IdentityCandidate | AuditCoordinator | E6 | 返回只读候选事实 | FR15；分析推导 |
| M15 | 只读读取认领审核待处理项（待审核/待补充/争议、计数）（E7） | AuditCoordinator | ClaimApplication | E7 | 提供申请状态与计数 | FR05；§6.3；`AC-IMPL-FR05-01` |
| M16 | 返回认领审核待处理项（E7） | ClaimApplication | AuditCoordinator | E7 | 返回只读申请状态 | §6.3；分析推导 |
| M17 | 只读读取有效授权与竞争认领争议入口事实（E8） | AuditCoordinator | ScholarAuthorization | E8 | 提供有效授权、被授权主体与争议入口事实 | `AC-IMPL-FR05-03`；BR-IMP-05；UC-06 |
| M18 | 返回授权与争议事实：原授权不转移，处置属 UC-06（E8） | ScholarAuthorization | AuditCoordinator | E8 | 返回只读授权事实 | `AC-IMPL-FR05-03`；§6.3；BR-IMP-05 |
| M19 | 只读读取成果变更/归属待处理项（待核验/争议状态）（E9） | AuditCoordinator | Authorship | E9 | 提供关系待核验/争议状态 | FR07/FR15；§6.4 |
| M20 | 返回关系待处理项（E9） | Authorship | AuditCoordinator | E9 | 返回只读关系状态 | §6.4；分析推导 |
| M21 | 汇总待办视图：类别计数＋属主 UC＋只读/导航性质；稳定顺序（E10） | AuditCoordinator | AuditCoordinator | E10 | 生成只读聚合结果与稳定顺序 | 分析推导；FR14；B-06 |
| M22 | 以属主对象最新状态为准：已被他人处置的项只显示当前状态（A10） | AuditCoordinator | AuditCoordinator | A10 | 不覆盖他人处置，不缓存旧结论 | FR15-S05 E1 类比；分析推导 |
| M23 | 返回待办视图与计数：不产生、不推进任何业务状态（E10） | AuditCoordinator | AdminConsole | E10 | 返回只读视图 | BR-UC17-04/05 |
| M24 | 显示待办视图并标注“只读聚合，处置在对应 UC”（E10） | AdminConsole | 管理员 | E10 | 显示类别/计数/属主 UC/引用 | FR14；BR-UC17-05 |
| M25 | 选择待办项，请求进入处置入口（E11） | 管理员 | AdminConsole | E11 | 接收导航选择 | FR14 统一入口 |
| M26 | 导航目标组装：属主 UC＋对象引用＋只读上下文，不携带处置动作（E11） | AdminConsole | AdminConsole | E11 | 组装导航目标，剔除处置动作 | 分析推导；A11 |
| M27 | 交接到属主 UC 处置入口：本 UC 不执行决定（E11） | AdminConsole | 管理员 | E11 | 显示属主 UC 入口 | FR14；BR-UC17-05 |
| M28 | 拒绝在后台入口执行处置动作：只提供导航（A11） | AdminConsole | 管理员 | A11 | 拒绝执行处置，提示属主 UC | FR14“不做通用工作流引擎”；BR-UC17-05 |
| M29 | 提交审计查询条件（对象/操作者/时间范围/事件类型/结果）（E12） | 管理员 | AdminConsole | E12 | 接收查询条件 | `AC-IMPL-FR14-02`；BR-IMP-10 |
| M30 | 转交审计查询（条件＋已认证上下文）（E12） | AdminConsole | AuditCoordinator | E12 | 启动审计查询 | 分析推导 |
| M31 | 校验审计查询权限与可查范围：具体管理权限（E13） | AuditCoordinator | User | E13 | 提供“可查审计”权限与范围事实 | NF03；§8；B-01 |
| M32 | 范围裁剪：仅返回授权范围内对象/类别并显式声明裁剪（A4） | AuditCoordinator | AuditCoordinator | A4 | 裁剪范围并标记裁剪 | 分析推导；§8；B-01 |
| M33 | 保留期限与遮蔽口径未冻结（D-09）：不擅自删除、匿名化或全量返回（A12） | AuditCoordinator | AuditCoordinator | A12 | 声明未冻结，不做未授权处置 | D-09；§7.2 |
| M34 | 只读读取审计留痕（事件类型/操作者/对象/时间/前后值/理由/结果）（E14） | AuditCoordinator | AuditEvidence | E14 | 提供匹配留痕（只读） | `AC-IMPL-FR14-02`；BR-IMP-10；INV-10 |
| M35 | 按时间排序返回留痕集合：保留追加链，不合并为一条（E14） | AuditEvidence | AuditCoordinator | E14 | 返回追加链与前后值 | INV-10；BR-382；A13 |
| M36 | 五要素完整性核对：操作者/对象/时间/前后值/理由（E15） | AuditCoordinator | AuditCoordinator | E15 | 逐条核对关键事件要素 | FR14；BR-IMP-10 |
| M37 | 缺任一要素即标“留痕不完整”异常项：不补写、不推断（A6） | AuditCoordinator | AuditCoordinator | A6 | 标记异常项，不修复留痕 | FR14；INV-10；BR-382 |
| M38 | 只读读取证明材料引用与可见范围：不返回原件（E16） | AuditCoordinator | ReviewEvidence | E16 | 提供材料引用与可见范围（只读） | `AC-IMPL-FR14-02`；BR-IMP-11；NF06 |
| M39 | 返回材料引用与可见范围（E16） | ReviewEvidence | AuditCoordinator | E16 | 返回引用与范围，不返回内容 | NF06；`AC-IMPL-NF06-01` |
| M40 | 证明材料原件可见性守卫：普通用户与无材料权限者不可见（A5） | AuditCoordinator | AuditCoordinator | A5 | 判定原件可见性并遮蔽 | `AC-IMPL-FR14-02`；`AC-IMPL-NF06-01`；BR-IMP-11 |
| M41 | 敏感字段遮蔽：凭据/私人联系方式/非公开科研材料不进查询结果与安全审计（E17） | AuditCoordinator | AuditCoordinator | E17 | 遮蔽敏感字段 | NF03；BR-IMP-11；INV-08 |
| M42 | 遮蔽结果说明：返回中不提供敏感字段明文（A14） | AuditCoordinator | AuditCoordinator | A14 | 标注已遮蔽，不返回明文 | NF03；B-04 |
| M43 | 审计只读守卫：查询结果不得用于修改或删除留痕与业务状态（A9） | AuditCoordinator | AuditCoordinator | A9 | 拒绝任何写回 | INV-10；BR-IMP-10 |
| M44 | 返回无匹配（空结果），与执行失败可区分（A7） | AuditCoordinator | AdminConsole | A7 | 返回空结果类型 | NF01 类比；BR-UC17-12 |
| M45 | 返回查询失败：不返回部分结果冒充完整（A8） | AuditCoordinator | AdminConsole | A8 | 返回失败类型 | NF01 类比；BR-UC17-12 |
| M46 | 返回审计结果（五要素＋材料引用与可见范围＋异常项＋裁剪声明）（E18） | AuditCoordinator | AdminConsole | E18 | 返回完整结果与异常项 | `AC-IMPL-FR14-02`；BR-IMP-10 |
| M47 | 记录受控查询访问留痕：只读查询本身留痕，不写业务状态（E18） | AuditCoordinator | AuditEvidence | E18 | 追加查询访问留痕（不含秘密） | BR-IMP-10；B-05 |
| M48 | 显示审计结果、异常项、裁剪声明与空/失败区分（E18） | AdminConsole | 管理员 | E18 | 显示结果与异常 | E18；BR-UC17-12 |
| M49 | 普通登录用户直接访问管理操作或提交处置（A2） | 普通用户／未授权请求者 | AdminConsole | A2 | 接收越权请求 | **`AC-IMPL-FR14-01`**；§8 |
| M50 | 转交请求（已认证上下文） | AdminConsole | AuditCoordinator | A2 | 启动校验（不执行请求内容） | 分析推导 |
| M51 | 校验账号状态与管理员角色（A1；A2） | AuditCoordinator | User | A1/A2 | 提供账号状态与是否具备管理权限 | NF03；`AC-IMPL-NF03-01`；§8 |
| M52 | 拒绝：无任何业务写入（A1） | AuditCoordinator | AdminConsole | A1 | 拒绝，不返回待办/审计 | NF03；`AC-IMPL-FR04-02` |
| M53 | 显示停用/未认证拒绝（A1） | AdminConsole | 普通用户／未授权请求者 | A1 | 显示拒绝（可区分） | NF01 类比；BR-UC17-12 |
| M54 | 记录受控拒绝审计，不含凭据（A1） | AuditCoordinator | AuditEvidence | A1 | 追加受控拒绝留痕 | BR-IMP-10；NF03 |
| M55 | 拒绝：无管理权限，不产生业务变更（A2） | AuditCoordinator | AdminConsole | A2 | 拒绝越权，无写入 | **`AC-IMPL-FR14-01`**；NF03 |
| M56 | 显示拒绝，与越权/失败/空结果可区分（A2） | AdminConsole | 普通用户／未授权请求者 | A2 | 显示越权结论 | `AC-IMPL-FR14-01`；BR-UC17-12 |
| M57 | 记录受控越权尝试（A2） | AuditCoordinator | AuditEvidence | A2 | 追加越权留痕 | BR-IMP-10；NF03 |

## 3. 事件、分支与消息对照

| 事件/分支 | 消息 | 关键守卫 | 可观察结果 |
|---|---|---|---|
| E1–E3 入口与权限 | M1–M8 | 账号状态∧类别具体权限（M3/M4/M5） | 返回本人有权类别与范围 |
| E4 采集任务待办 | M9–M10 | 只读 | 任务失败/异常移交/待核验计数 |
| E5 异常治理待办 | M11–M12 | 只读 | 候选/异常待处理计数 |
| E6 学者核验待办 | M13–M14 | 只读 | 身份/归属候选与争议计数 |
| E7 认领审核待办 | M15–M16 | 只读 | 待审核/待补充/争议计数 |
| E8 授权与争议入口 | M17–M18 | 只读 | 有效授权事实、原授权不转移 |
| E9 成果变更待办 | M19–M20 | 只读 | 待核验/争议关系计数 |
| E10 待办视图 | M21–M24 | 只读派生＋稳定顺序 | 类别计数＋属主 UC＋对象引用 |
| E11 导航交接 | M25–M27 | 不携带处置动作 | 进入属主 UC 入口 |
| E12–E13 查询与权限 | M29–M31 | 可查审计权限∧范围 | 进入留痕读取或被拒 |
| E14–E15 读取与核对 | M34–M37 | 只读∧五要素核对 | 留痕集合＋留痕不完整异常项 |
| E16 材料可见性 | M38–M40 | 原件仅有权审核者 | 引用＋可见范围 |
| E17 敏感遮蔽 | M41–M42 | 凭据/私人/非公开材料 | 遮蔽后返回 |
| E18 返回与留痕 | M43–M48 | 只读∧空/失败可区分 | 审计结果＋查询访问留痕 |
| A1 停用/未认证 | M51–M54 | 账号状态 | 拒绝、无写入、拒绝留痕 |
| A2 普通用户越权 | M49–M51、M55–M57 | 管理员角色∧管理权限 | 拒绝、无业务变更、越权留痕 |
| A3 缺类别权限 | M5 | 类别具体权限 | 不列出/拒绝 |
| A4 审计范围超权 | M32 | 范围授权 | 裁剪＋显式声明 |
| A5 证明原件 | M38–M40 | 可见范围 | 不返回原件 |
| A6 留痕缺要素 | M36–M37 | 五要素 | 异常项，不补写 |
| A7 空结果 | M44 | 无匹配 | 空结果类型 |
| A8 查询失败 | M45 | 读取失败 | 失败类型 |
| A9 审计只读 | M43 | 无写回 | 拒绝修改 |
| A10 状态已变 | M22 | 属主对象最新状态 | 显示当前状态 |
| A11 含处置动作 | M26–M28 | 入口不执行决定 | 只导航 |
| A12 保留期限未冻结 | M33 | D-09 | 不做未授权处置 |
| A13 多条留痕 | M35 | 追加链 | 全部返回、不合并 |
| A14 敏感字段 | M41–M42 | 遮蔽清单未冻结（B-04） | 遮蔽并说明 |

## 4. 完整性与一致性核对

1. **每个类都有消息：** 11 个类均有至少一条接收或应答消息（M1–M57），无“只在文字里出现”的类。
2. **每个事件都有消息：** E1–E18 与 A1–A14 在 §3 均可定位到消息区间；A12/A13 以守卫与返回约束表达。
3. **无写消息到属主对象：** 对 `CollectionTask`/`DedupCandidate`/`IdentityCandidate`/`ClaimApplication`/`ScholarAuthorization`/`Authorship` 只有 M9/M11/M13/M15/M17/M19 读消息与其应答（M10/M12/M14/M16/M18/M20），**没有任何写入或状态推进消息**（BR-UC17-04/05）。
4. **无通用工作流消息：** 不存在“建待办/指派/受理/办结/超期提醒”消息；不存在对 `PendingTodo`/`WorkflowEngine` 的寻址（03 §4）。
5. **审计为只读＋受控追加：** `AuditEvidence` 只有 M34 读与 M47/M54/M57 三条“受控访问/拒绝/越权”追加；无修改/删除消息（M43 守卫）。
6. **材料不外泄：** 对 `ReviewEvidence` 只有 M38 读取引用与可见范围，M39 应答不含原件；M40 守卫原件可见性（A5）。
7. **权限分支分离：** 未认证/停用（A1）、普通用户（A2）、管理员缺类别权限（A3）分别有独立守卫与结果，不合并为单一“拒绝”。
8. **消息号连续：** M1–M57 无缺号、无重复（§5 脚本核对）。

## 5. 与图的一致性

| 检查 | 方法 | 结果 |
|---|---|---|
| 消息号与名称 | 从 `.puml` 抽取 `M<n> ...` 与 04 表逐行比对 | 57/57 一致（见 08 §4.4 实际输出） |
| 发送者/接收者 | 抽取箭头两端别名并映射为类名 | 57/57 一致 |
| 类的集合 | 图对象集合 vs 05 §1 表 | 11/11 一致 |
| 参与者不建模 | 图中 `管理员`、`普通用户／未授权请求者` 为 actor | 未出现在 VOPC |

> 本图与 04 表为同一交付的两个视图；若后续修改消息，必须同步修改本文与 05/06 的消息引用，并在 08 记录重渲结果。
