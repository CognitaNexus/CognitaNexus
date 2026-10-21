# UC-19 分析序列与逐消息职责

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-SEQ-18-source-update.puml`](diagrams/DGM-SEQ-18-source-update.puml)
**导出：** [SVG](diagrams/generated/DGM-SEQ-18-source-update.svg) · [PNG](diagrams/generated/DGM-SEQ-18-source-update.png)
**方法：** 教材 §5.4.3；只表达分析对象协作，不写 Java、数据库、HTTP/API、索引、缓存、调度器、队列或重试算法。

## 1. 顺序图范围

本序列实现 UC-19“管理来源更新”：主流程 E1–E17，以及 A1（无上次基线/游标）、A2（删除/撤稿报告）、A3（无版本信息）、A4（断连/读取失败）、A5（与人工审核字段冲突）、A6（更新期间人工值变化）、A7（复跑/重叠窗口幂等）、A8（来源身份/记录键不可确认）、A9（要求物理删除）、A10（无证据建版本）、A11（未授权）、A12（失效直接下架/覆盖）、A13（查询夹带写入）、A14（受保护写入缺五要素）分支；A13 以只读消息表达，A14 以受控写入消息与约束表达，均无独立循环。

参与对象（共 16 个；其中 3 个为参与者，不进入 VOPC）：

- 参与者（图外或图左/右侧）：管理员（主要参与者）、获准数据来源（次要参与者，外部来源）、未授权请求者（次要参与者，仅触发拒绝路径）
- `AdminConsole`（Boundary）
- `SourceAdapter`（Boundary）
- `CollectionCoordinator`（Control）
- `GovernanceCoordinator`（Control，协作类，属主 UC-09/10）
- `User`（Entity，只读校验）
- `SourceDefinition`（Entity，配置，只读）
- `CollectionTask`（Entity，状态/快照/安全检查点/历史，复用 UC-18 六状态）
- `SourceRecord`（Entity，到达证据：原值/观察版本/检查时间）
- `IncrementalChangeSet`（Entity，本轮比较结果）
- `WorkEntity`（Entity，只读：生效值/已人工审核字段/公开状态）
- `WorkVersion`（Entity，只读：版本关系与明示证据）
- `GovernanceException`（Entity，待治理待办）
- `AuditEvidence`（Entity，受控写入留痕）

> 图中不出现 `Scheduler`/`TaskQueue`/`JobRunner`/`RetryPolicy`/`Checkpoint`/`IncrementalWindow`/`ChangeCursor`/`ChangeItem`/`FieldDiff`/`DedupCoordinator`/`CleanRecord`/`DedupCandidate`/`IdentityCandidate`/`Scholar`/`Authorship`/`PublishAction`/`PendingTodo`：执行与比较框架、治理判定、发布与待办投影均不属本 UC（03 §4）。

## 2. 逐消息职责表

| 消息 | 名称 | 发送者 | 接收者 | 事件步骤 | 接收者职责 | 依据/假设 |
|---|---|---|---|---|---|---|
| M1 | 打开来源更新入口（增量同步）（E1） | 管理员 | AdminConsole | E1 | 接收入口请求，启动来源更新会话 | FR10；§8；`DGM-USECASE-03` |
| M2 | 请求更新入口与上次更新概览（已认证上下文） | AdminConsole | CollectionCoordinator | E1 | 准备服务端校验与入口上下文（可读取上次更新概览的事实引用） | 分析推导；§8 |
| M3 | 校验账号状态（E2） | CollectionCoordinator | User | E2 | 提供账号状态（可用/停用） | §8；NF03 |
| M4 | 校验“数据来源/采集”具体管理权限（E2） | CollectionCoordinator | User | E2 | 提供“数据来源/采集”具体管理权限 | §8 权限表；FR10 执行前提 |
| M5 | 权限守卫：无该具体权限不放行（E2；A11） | CollectionCoordinator | CollectionCoordinator | E2/A11 | 判定是否放行；不因“是管理员”默认全量 | §8；NF03；A11 |
| M6 | 返回可访问的已核验来源域与更新入口（E2） | CollectionCoordinator | AdminConsole | E2 | 返回可访问来源域与入口 | E2；分析推导 |
| M7 | 显示已核验来源域与更新入口（E2） | AdminConsole | 管理员 | E2 | 显示来源域与更新入口 | §8；分析推导 |
| M8 | 选择已核验来源域与更新范围，请求来源更新（E3） | 管理员 | AdminConsole | E3 | 接收来源域与更新范围输入 | FR10“在已核验来源域”；02-用例模型 UC-19 |
| M9 | 提交来源更新请求（E3） | AdminConsole | CollectionCoordinator | E3 | 启动更新前置校验 | 分析推导 |
| M10 | 只读读取来源已获准状态、来源域与许可/允许范围（E3） | CollectionCoordinator | SourceDefinition | E3 | 提供已获准状态、来源域、许可/允许范围（只读） | FR10；FR08；FR16；INV-09 |
| M11 | 返回来源有效性、来源域与允许范围（E3） | SourceDefinition | CollectionCoordinator | E3 | 取得更新前置事实 | 分析推导 |
| M12 | 只读读取上次安全检查点、上次检查时间与任务状态（E4） | CollectionCoordinator | CollectionTask | E4 | 提供检查点、上次检查时间与当前状态（只读） | §6.2；`DGM-STATE-01` |
| M13 | 返回检查点、上次检查时间与任务状态（E4） | CollectionTask | CollectionCoordinator | E4 | 取得基线事实 | 分析推导 |
| M14 | 更新前置守卫：来源已获准∧来源域已核验∧范围可定位∧基线可确定∧权限有效（E3–E4；A1） | CollectionCoordinator | CollectionCoordinator | E3–E4/A1 | 判定本轮更新是否允许；无基线转 A1 | FR10；FR08；`DGM-STATE-01` |
| M15 | 确认基线可用与更新范围（E4） | CollectionCoordinator | AdminConsole | E4 | 返回可用基线与范围 | E4；分析推导 |
| M16 | 披露“无法与上次比较”并要求指定回溯范围（A1） | CollectionCoordinator | AdminConsole | A1 | 返回局限说明与回溯范围要求 | FR10；A1 |
| M17 | 指定回溯范围（A1） | 管理员 | AdminConsole | A1 | 接收回溯范围输入 | A1；B-03 |
| M18 | 提交回溯范围（A1） | AdminConsole | CollectionCoordinator | A1 | 转交回溯范围 | 分析推导 |
| M19 | 以回溯范围为基线并标注局限，不把“无法比较”当“未变”（A1） | CollectionCoordinator | CollectionCoordinator | A1 | 设定基线并标注局限 | A1；BR-UC19-03 |
| M20 | 建立本轮变更集：来源域、更新范围、基线引用、检查开始时间（E5） | CollectionCoordinator | IncrementalChangeSet | E5 | 建立本轮比较结果载体并记录检查开始时间 | FR10“保留检查与生效时间”；分析推导 |
| M21 | 返回变更集引用与检查时间（E5） | IncrementalChangeSet | CollectionCoordinator | E5 | 取得变更集引用与检查时间 | 分析推导 |
| M22 | 按来源定义与基线请求本轮记录、变更报告与删除/撤稿报告（E5） | CollectionCoordinator | SourceAdapter | E5 | 按来源定义与基线发起本轮读取 | FR10；FR08；B-03 |
| M23 | 请求本轮记录与来源侧变更/删除报告（E5） | SourceAdapter | 获准数据来源 | E5 | 由外部来源提供记录、变更报告、删除/撤稿报告或失败反馈 | FR10；`DGM-USECASE-03` |
| M24 | 返回记录/变更报告/删除或撤稿报告，或失败反馈（E5；A4） | 获准数据来源 | SourceAdapter | E5/A4 | 转交记录、报告或失败反馈 | A2/A4；FR10 |
| M25 | 返回本轮输入与来源证据，读取失败时返回失败反馈（E5；A4） | SourceAdapter | CollectionCoordinator | E5/A4 | 取得本轮输入与来源证据或失败反馈 | E5；A4 |
| M26 | 按来源ID＋来源内记录键定位来源记录，读取上次观察版本与既有原值（E6） | CollectionCoordinator | SourceRecord | E6 | 按逻辑出处定位并返回上次观察版本与原值（只读） | FR10；INV-01；DD-01 类比 |
| M27 | 返回逻辑出处、上次观察版本、原值与既有到达事件（E6） | SourceRecord | CollectionCoordinator | E6 | 取得比较基线 | 分析推导 |
| M28 | 身份与出处守卫：来源身份与记录键可确认；不得凭标题相似判同版本（E6；A8） | CollectionCoordinator | CollectionCoordinator | E6/A8 | 判定是否可比较；不可确认转 A8 | FR10；A8；DD-07 类比 |
| M29 | 分类：本次观察版本对比上次版本→新增/未变/变更/失效；无版本信息不判“未变”（E7；A3） | CollectionCoordinator | CollectionCoordinator | E7/A3 | 产出唯一分类；无版本信息时不判未变 | FR10；A3；BR-UC19-03 |
| M30 | 建立异常项：来源身份或记录键不可确认，待处理（A8） | CollectionCoordinator | GovernanceException | A8 | 建立待处理异常项（保留原始内容引用） | FR10；A8 |
| M31 | 返回异常项引用（A8） | GovernanceException | CollectionCoordinator | A8 | 取得异常项引用与待处理状态 | A8 |
| M32 | 登记新的到达事件与原值（来源证据、到达时间、观察版本）（E8） | CollectionCoordinator | SourceRecord | E8 | 保存新的到达事件与原值（既有原值不改写） | INV-01；FR16；CL-10 |
| M33 | 返回逻辑出处与首次到达标记（E8） | SourceRecord | CollectionCoordinator | E8 | 取得逻辑出处与首次到达标记 | FR09-06 |
| M34 | 交治理处置（清洗/去重/身份归属），不自行合并或判定（E8） | CollectionCoordinator | GovernanceCoordinator | E8 | 接收逐条移交并处置（规则属 UC-09/10） | FR08；FR09/FR15 边界 |
| M35 | 返回处置结果（已登记结果/待核验）或需异常移交（E8） | GovernanceCoordinator | CollectionCoordinator | E8 | 取得处置结果或需异常移交 | §6.2；A5 类比 |
| M36 | 只更新检查时间与本次观察版本，不新增到达事件与业务变更（E9） | CollectionCoordinator | SourceRecord | E9 | 更新检查时间与观察版本 | FR10“未变”；`collection-update-activity` |
| M37 | 返回未变确认与检查时间（E9） | SourceRecord | CollectionCoordinator | E9 | 取得未变确认与检查时间 | E9 |
| M38 | 保存变更前后差异：追加新的到达事件与原值，保留先前原值（E10） | CollectionCoordinator | SourceRecord | E10 | 追加变更观察的到达事件与原值，保留先前原值 | FR10；INV-01；CL-11 |
| M39 | 返回变更证据引用与新旧原值（E10） | SourceRecord | CollectionCoordinator | E10 | 取得差异证据 | 分析推导 |
| M40 | 只读读取当前生效值、已人工审核字段与公开生效状态（E10；A5） | CollectionCoordinator | WorkEntity | E10/A5 | 提供生效值、已人工审核字段、公开状态（只读） | INV-06；CL-09；`AC-IMPL-FR10-01` |
| M41 | 返回生效值、已人工审核字段与公开状态（E10；A5） | WorkEntity | CollectionCoordinator | E10/A5 | 取得冲突判定依据 | 分析推导 |
| M42 | 只读读取既有版本关系与来源明示版本证据（E13；A10） | CollectionCoordinator | WorkVersion | E13/A10 | 提供既有版本关系与明示证据（只读） | DD-08；`AC-IMPL-FR09-05` |
| M43 | 返回既有版本关系与明示证据（E13；A10） | WorkVersion | CollectionCoordinator | E13/A10 | 取得版本证据事实 | 分析推导 |
| M44 | 冲突守卫：已人工审核值优先，来源新值不得覆盖（A5） | CollectionCoordinator | CollectionCoordinator | A5 | 判定冲突并决定不覆盖 | `AC-IMPL-FR10-01`；INV-06；CL-09 |
| M45 | 建立治理待办：待核验字段冲突（字段/人工审核值/来源新值/双方证据/待处理）（A5） | CollectionCoordinator | GovernanceException | A5 | 建立待核验字段冲突待办（无公开可见性） | `AC-IMPL-FR10-01`；CL-09；BR-IMP-01 |
| M46 | 返回待办引用与待处理状态（A5） | GovernanceException | CollectionCoordinator | A5 | 取得待办引用与状态 | A5 |
| M47 | 记录该条为“冲突待核验”，生效值保持人工审核值（A5） | CollectionCoordinator | IncrementalChangeSet | A5 | 记录逐条分类与待办引用 | `AC-IMPL-FR10-01`；FR10 |
| M48 | 记录冲突待办与“未覆盖人工审核值”（操作者/对象/时间/前后值/理由）（A5） | CollectionCoordinator | AuditEvidence | A5 | 追加受控写入留痕（五要素） | BR-IMP-10；INV-10；A14 |
| M49 | 生效前重检：已人工审核值自本次读取后未变化（A6） | CollectionCoordinator | WorkEntity | A6 | 提供重检所需的最新人工审核值/生效值（只读） | FR10“并发冲突进入治理待办”；CL-09 |
| M50 | 返回重检结果（A6） | WorkEntity | CollectionCoordinator | A6 | 取得重检结果 | A6 |
| M51 | 交治理按字段规则生效，不覆盖已人工审核值（E12） | CollectionCoordinator | GovernanceCoordinator | E12 | 按属主规则执行字段级生效（生效前已重检） | CL-09；FR09/FR15 边界 |
| M52 | 返回生效结果（已按规则生效/需人工确认，处理项由治理保留）（E12） | GovernanceCoordinator | CollectionCoordinator | E12 | 取得生效结果 | 分析推导；B-09 类比 |
| M53 | 记录生效时间、生效范围与该条分类（仅“已按规则生效”记录生效时间）（E12） | CollectionCoordinator | IncrementalChangeSet | E12 | 记录生效时间与生效范围 | FR10“保留检查与生效时间”；NF05 声明层 |
| M54 | 记录生效（操作者/对象/时间/前后值/理由；不改变公开资格）（E12） | CollectionCoordinator | AuditEvidence | E12 | 追加生效留痕 | BR-IMP-10；INV-10；A14 |
| M55 | 建立并发冲突治理待办并放弃本次覆盖（A6） | CollectionCoordinator | GovernanceException | A6 | 建立并发冲突待办（无公开可见性） | FR10；CL-09 |
| M56 | 返回待办引用（A6） | GovernanceException | CollectionCoordinator | A6 | 取得待办引用 | A6 |
| M57 | 记录该条为“并发冲突待处理”（A6） | CollectionCoordinator | IncrementalChangeSet | A6 | 记录逐条分类与待办引用 | A6 |
| M58 | 记录放弃覆盖（操作者/对象/时间/前后值/理由）（A6） | CollectionCoordinator | AuditEvidence | A6 | 追加放弃覆盖留痕 | BR-IMP-10；A14 |
| M59 | 记录明示版本证据并把版本关系交治理（E13） | CollectionCoordinator | IncrementalChangeSet | E13 | 记录版本证据引用与分类 | DD-08；FR16 |
| M60 | 交治理按版本规则处理；无明示证据不得建立版本关系（E13；A10） | CollectionCoordinator | GovernanceCoordinator | E13/A10 | 按属主版本规则处理版本关系 | DD-08；`AC-IMPL-FR09-05` |
| M61 | 返回版本处理结果（E13；A10） | GovernanceCoordinator | CollectionCoordinator | E13/A10 | 取得版本处理结果 | 分析推导 |
| M62 | 版本守卫：保持独立并标待核验，不建立版本关系（A10） | CollectionCoordinator | CollectionCoordinator | A10 | 判定无证据时保持独立并转待核验 | DD-08；SPEC §5.5；A10 |
| M63 | 记录失效事件（撤稿与删除报告分别记录），保留原值与既有到达事件（E14；A2） | CollectionCoordinator | SourceRecord | E14/A2 | 登记失效事件（两类分别记录），不改写原值 | CL-12；`AC-IMPL-FR10-02` |
| M64 | 返回失效事件引用（E14） | SourceRecord | CollectionCoordinator | E14 | 取得失效事件引用 | 分析推导 |
| M65 | 只读确认既有成果与出处仍存在且不被删除（E14；A9） | CollectionCoordinator | WorkEntity | E14/A9 | 回答成果与出处存在事实（只读） | CL-12；BR-IMP-04；A9 |
| M66 | 返回成果与出处存在事实（E14） | WorkEntity | CollectionCoordinator | E14 | 取得存在事实（用于“不删除”的可观察确认） | 分析推导 |
| M67 | 建立待核验待办（失效原因/对象引用/待处理），不下架不删除（E14；A12） | CollectionCoordinator | GovernanceException | E14/A12 | 建立失效待核验待办（无公开可见性） | CL-12；`AC-IMPL-FR10-02`；A12 |
| M68 | 返回待办引用（E14） | GovernanceException | CollectionCoordinator | E14 | 取得待办引用 | E14 |
| M69 | 记录失效事件与“未删除既有记录”（操作者/对象/时间/前后值/理由）（E14） | CollectionCoordinator | AuditEvidence | E14 | 追加失效登记留痕 | BR-IMP-10；CL-12；A14 |
| M70 | 保存逐条分类（新增/未变/变更/冲突/失效/异常，含来源报告更新时间）与互斥计数、检查时间（E15） | CollectionCoordinator | IncrementalChangeSet | E15 | 保存逐条分类、来源报告更新时间、互斥计数与检查时间 | FR10；B-08 |
| M71 | 返回变更集计数与检查时间（E15） | IncrementalChangeSet | CollectionCoordinator | E15 | 取得计数与检查时间 | 分析推导 |
| M72 | 幂等核对：同一逻辑出处同一观察版本不重复建立变更、不新增第二条逻辑出处（A7） | CollectionCoordinator | IncrementalChangeSet | A7 | 核对复跑/重叠窗口是否已处理同一版本 | INV-07；FR09-06；CL-10 |
| M73 | 返回幂等核对结果（A7） | IncrementalChangeSet | CollectionCoordinator | A7 | 取得幂等核对结果 | A7 |
| M74 | 保存安全检查点并追加任务历史（E15） | CollectionCoordinator | CollectionTask | E15 | 保存检查点并追加任务历史 | §6.2；`DGM-STATE-01` |
| M75 | 返回检查点与任务历史引用（E15） | CollectionTask | CollectionCoordinator | E15 | 取得检查点与历史引用 | E15 |
| M76 | 读取本轮分类结果、待办引用与未完成项（E16） | CollectionCoordinator | IncrementalChangeSet | E16 | 提供分类结果、待办引用与未完成项（只读） | §6.2 完成判据（UC-19 对应） |
| M77 | 返回分类结果、待办引用与未完成项（E16） | IncrementalChangeSet | CollectionCoordinator | E16 | 取得完成核对依据 | E16 |
| M78 | 完成守卫：每条输入已分类且记录到结果或可追踪待办；无未记录项（E16） | CollectionCoordinator | CollectionCoordinator | E16 | 判定是否满足完成判据 | §6.2；BR-UC19-13 |
| M79 | 完成生效：运行中→成功（E16） | CollectionTask | CollectionCoordinator | E16 | 应用完成生效 | §6.2；`DGM-STATE-01` |
| M80 | 记录完成结论与分类计数（含冲突/失效/异常；不写“人工治理完成/已发布”）（E16） | CollectionCoordinator | AuditEvidence | E16 | 追加完成结论与计数留痕（措辞受限） | BR-IMP-10；FR08；BR-UC19-14 |
| M81 | 返回成功＋单列的未变/变更/冲突/失效/异常数量（E16） | CollectionCoordinator | AdminConsole | E16 | 返回成功与单列计数 | FR10；FR08 措辞约束 |
| M82 | 显示成功结论、单列计数与“不等于治理完成、不等于已发布”（E16） | AdminConsole | 管理员 | E16 | 显示成功结论与限定措辞 | §6.2；BR-UC19-14 |
| M83 | 运行中→失败：保存失败点、已分类结果与恢复范围（A4） | CollectionTask | CollectionCoordinator | A4 | 记录失败状态、失败点与恢复范围 | §6.2；A4 |
| M84 | 保存失败状态与未完成项，保留已分类结果与既有原记录（A4） | CollectionCoordinator | IncrementalChangeSet | A4 | 记录失败状态、未完成项与已分类结果 | `AC-IMPL-FR10-02`；NF04-01 |
| M85 | 记录失败事实与失败点（不含凭据与秘密）（A4） | CollectionCoordinator | AuditEvidence | A4 | 追加失败事实与失败点留痕 | BR-IMP-10；NF03；NF07-01 |
| M86 | 返回失败、失败点与恢复入口（A4） | CollectionCoordinator | AdminConsole | A4 | 返回失败、失败点与恢复入口 | A4；NF04-01 |
| M87 | 显示失败、“已收录数据与老记录仍可查询”与恢复入口（A4） | AdminConsole | 管理员 | A4 | 显示失败与历史保留事实 | `AC-IMPL-FR10-02`；BR-IMP-04 |
| M88 | 查看更新汇总与轨迹（E17） | 管理员 | AdminConsole | E17 | 接收汇总与轨迹查询 | NF07-01；02-用例模型 UC-19 |
| M89 | 转交汇总查询（E17） | AdminConsole | CollectionCoordinator | E17 | 启动只读汇总读取 | 分析推导；A13 |
| M90 | 只读读取检查时间、来源更新时间、生效时间、分类与前后差异（E17；A13） | CollectionCoordinator | IncrementalChangeSet | E17/A13 | 提供三类时间、分类与差异（只读） | FR10；CL-07；A13 |
| M91 | 返回汇总、三类时间与前后差异（E17；A13） | IncrementalChangeSet | CollectionCoordinator | E17/A13 | 取得汇总与差异 | A13 |
| M92 | 只读读取字段冲突与失效待办状态（E17；A13） | CollectionCoordinator | GovernanceException | E17/A13 | 提供待办状态与待处理数量（只读） | FR10；A13 |
| M93 | 返回待办状态与待处理数量（E17） | GovernanceException | CollectionCoordinator | E17 | 取得待办状态 | A13 |
| M94 | 只读读取相关受控写入留痕（E17；A13） | CollectionCoordinator | AuditEvidence | E17/A13 | 提供相关留痕（只读） | BR-IMP-10；NF07-01 |
| M95 | 返回留痕（操作者/时间/前后值/理由）（E17） | AuditEvidence | CollectionCoordinator | E17 | 取得留痕 | E17 |
| M96 | 返回更新汇总、轨迹与待办（只读，不推进状态）（E17；A13） | CollectionCoordinator | AdminConsole | E17/A13 | 返回只读汇总（不推进状态） | E17；A13 |
| M97 | 显示检查/生效时间分离、前后差异、待办与失败点（E17） | AdminConsole | 管理员 | E17 | 显示汇总、轨迹与待办 | FR10；NF07-01 |
| M98 | 请求按删除报告删除既有成果，或将失效记录直接下架（A9/A12） | 管理员 | AdminConsole | A9/A12 | 接收越界请求 | A9/A12 |
| M99 | 转交越界请求（A9/A12） | AdminConsole | CollectionCoordinator | A9/A12 | 启动越界判定 | 分析推导 |
| M100 | 拒绝：不物理删除，不因一次失效事件下架或覆盖公开值（A9/A12） | CollectionCoordinator | AdminConsole | A9/A12 | 返回拒绝（无业务写入） | CL-12；BR-IMP-04；BR-IMP-01 |
| M101 | 显示拒绝原因与“失效只登记事件并转待核验”（A9/A12） | AdminConsole | 管理员 | A9/A12 | 显示拒绝与正确处置入口 | A9/A12 |
| M102 | 请求在无版本证据时建立版本关系（A10） | 管理员 | AdminConsole | A10 | 接收无证据建版本请求 | A10 |
| M103 | 转交请求（A10） | AdminConsole | CollectionCoordinator | A10 | 启动版本证据守卫 | 分析推导 |
| M104 | 拒绝：无明示版本证据不建立版本关系（A10） | CollectionCoordinator | AdminConsole | A10 | 返回拒绝（无业务写入） | DD-08；SPEC §5.5 |
| M105 | 显示拒绝与“保持独立并待核验”（A10） | AdminConsole | 管理员 | A10 | 显示拒绝与待核验入口 | A10 |
| M106 | 提交来源更新或变更处置操作（A11） | 未授权请求者 | AdminConsole | A11 | 接收未授权操作 | FR10 执行前提；§8 |
| M107 | 转交请求（已认证上下文） | AdminConsole | CollectionCoordinator | A11 | 启动账号与权限校验（不执行请求内容） | 分析推导；§8 |
| M108 | 校验账号状态与“数据来源/采集”具体管理权限（A11） | CollectionCoordinator | User | A11 | 提供账号状态与采集权限 | §8；NF03 |
| M109 | 拒绝：无业务变更，不返回来源记录与变更明细（A11） | CollectionCoordinator | AdminConsole | A11 | 返回拒绝（无业务变更、不返回明细） | §8；NF01 类比 |
| M110 | 显示拒绝，与失败/无变更可区分（A11） | AdminConsole | 未授权请求者 | A11 | 显示拒绝 | NF01 类比；A11 |
| M111 | 记录受控拒绝与越权尝试（不含凭据）（A11） | CollectionCoordinator | AuditEvidence | A11 | 追加受控拒绝/越权留痕 | BR-IMP-10；NF03；A14 |

## 3. 事件、分支与消息对照

| 事件/分支 | 消息 | 关键守卫 | 可观察结果 |
|---|---|---|---|
| E1–E2 入口与权限 | M1–M7 | 账号状态∧“数据来源/采集”具体权限（M3–M5） | 返回可访问来源域与入口 |
| E3–E4 来源域与基线 | M8–M19 | 已获准∧来源域已核验∧范围可定位∧基线可确定（M14） | 确认基线或披露局限并采用回溯范围 |
| E5 变更集与本轮输入 | M20–M25 | 按来源定义与基线读取；失败反馈可区分（M24/M25） | 变更集建立并取得本轮输入/报告 |
| E6–E7 定位与分类 | M26–M29 | 来源身份与记录键可确认∧不得凭标题相似（M28） | 每条唯一分类：新增/未变/变更/失效 |
| E8 新增 | M32–M35 | 原值不改写∧逐条移交治理（M32/M34） | 到达事件＋治理处置结果 |
| E9 未变 | M36–M37 | 不造变更∧不新增到达事件（M36） | 只更新检查时间与观察版本 |
| E10–E13 变更、冲突、生效与版本 | M38–M62 | 人工审核值优先（M44）∧生效前重检（M49）∧版本须有明示证据（M62） | 冲突待办／生效时间／版本证据记录 |
| E14 失效 | M63–M69 | 不删除∧不下架∧两类失效分别记录（M63） | 失效事件＋存在确认＋待核验待办 |
| E15 检查点与幂等 | M70–M75 | 互斥计数∧复跑幂等（M72） | 计数、检查时间、检查点与任务历史 |
| E16 完成 | M76–M82 | 每条输入已分类且记录到结果或可追踪待办（M78） | 运行中→成功＋单列计数＋限定措辞 |
| E17 汇总与轨迹 | M88–M97 | 只读（M90/M92/M94） | 三类时间、前后差异、待办与失败点 |
| A1 无基线 | M16–M19 | 基线可确定∧标注局限 | 回溯范围作为基线，不当作未变 |
| A2 删除/撤稿 | M24；M63–M69 | 两类事件分别记录∧不删除 | 失效事件＋待核验待办 |
| A3 无版本信息 | M29 | 无版本信息不判“未变” | 按内容比较/待核验（B-02） |
| A4 断连/失败 | M24–M25；M83–M87 | 失败反馈（M24） | 失败＋失败点＋恢复范围（历史保留） |
| A5 人工审核字段冲突 | M44–M48 | 人工审核值优先（M44） | 审核值保留＋待核验字段冲突待办 |
| A6 并发变化 | M49–M58 | 生效前重检（M49） | 放弃覆盖＋并发冲突待办 |
| A7 复跑幂等 | M72–M73 | 同一逻辑出处同一观察版本（M72） | 不重复变更、不新增第二条逻辑出处 |
| A8 身份不可确认 | M28；M30–M31 | 来源身份与记录键可确认（M28） | 异常项待处理，不凭标题判同版本 |
| A9 要求物理删除 | M65–M66；M98–M101 | 不删除（M65/M100） | 拒绝＋既有成果与出处仍在 |
| A10 无证据建版本 | M42–M43；M59–M62；M102–M105 | 明示版本证据（M62/M104） | 拒绝＋保持独立待核验 |
| A11 未授权 | M3–M5；M106–M111 | 账号状态∧具体权限（M108） | 拒绝、无业务变更、受控拒绝留痕 |
| A12 失效下架/覆盖 | M67–M68；M98–M101 | 失效不改变公开资格（M100） | 拒绝＋转待核验 |
| A13 查询只读 | M90–M96 | 只读（M90/M92/M94） | 不改状态、计数、时间与留痕 |
| A14 受保护写入五要素 | M48；M54；M58；M69；M80；M85；M111 | 操作者/对象/时间/前后值/理由 | 留痕可核对；缺要素不记完成 |

## 4. 完整性与一致性核对

1. **每个类都有消息：** 13 个类均有至少一条接收或应答消息（M1–M111），无“只在文字里出现”的类。
2. **每个事件都有消息：** E1–E17 与 A1–A14 在 §3 均可定位到消息区间；A13 以只读消息表达，A14 以受控写入消息与约束表达。
3. **无治理判定消息：** 对 `GovernanceCoordinator` 只有 M34/M51/M60 移交与 M35/M52/M61 回执，没有“清洗/去重/合并/字段优先级/身份判定/版本归并”消息（FR09/FR15 不属本 UC）。
4. **无成果写入与发布消息：** 对 `WorkEntity` 只有 M40/M41（读生效值与审核值）、M49/M50（生效前重检）、M65/M66（存在确认）；对 `WorkVersion` 只有 M42/M43 只读；无“写字段/建版本/发布/下架”消息（BR-UC19-15）。
5. **无执行与比较框架消息：** 不存在“建队列/投递任务/调度/重试策略/开窗”消息；检查点、观察版本与逐条分类以 `CollectionTask`、`SourceRecord`、`IncrementalChangeSet` 属性形式出现（M36/M70/M74）。
6. **状态推进只在 §6.2 允许的转移上：** M79（运行中→成功）、M83（运行中→失败）；无新增状态与转移，停止入口沿用 `DGM-STATE-01`。
7. **原值不可改写：** 对 `SourceRecord` 只有 M32（新增到达事件）、M36（检查时间与观察版本）、M38（变更观察的新到达事件）、M63（失效事件）；无“改写既有原值”消息（INV-01）。
8. **人工审核值不被覆盖：** M44 守卫后只建待办（M45）并记录分类（M47），无写入 `WorkEntity` 的消息；无冲突分支先重检（M49/M50），通过后由 M51 交治理执行，再记录生效时间（M53）。
9. **完成判据先于状态推进：** M76–M78 核对通过后才有 M79 的“运行中→成功”，顺序不可交换（BR-UC19-13）。
10. **失败不删历史：** A4 只写失败状态、失败点与恢复范围（M83–M85），无删除消息；失效只登记事件并建待办（M63–M69）。
11. **消息号连续：** M1–M111 无缺号、无重复（实际脚本输出见 08 §4.3）。

## 5. 与图的一致性

| 检查 | 方法 | 结果 |
|---|---|---|
| 消息号与名称 | 从 `.puml` 抽取 `M<n> …` 与 04 表逐行比对 | 111/111 一致（见 08 §4.3 实际输出） |
| 发送者/接收者 | 抽取箭头两端别名并映射为类名 | 111/111 一致 |
| 类的集合 | 图对象集合 vs 05 §1 表 | 13/13 一致 |
| 参与者不建模 | 图中 `管理员`、`获准数据来源`、`未授权请求者` 为 actor | 未出现在 VOPC |

> 本图与 04 表为同一交付的两个视图；若后续修改消息，必须同步修改本文与 05/06 的消息引用，并在 08 记录重渲结果。

> 状态应用说明：M79 是 `CollectionTask` 在守卫结果后应用状态的应答；M83 是失败事实的回报，两者都以 `DGM-STATE-01` 为准，不新增迁移。
