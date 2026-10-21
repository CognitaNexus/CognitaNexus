# UC-18 分析序列与逐消息职责

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-SEQ-17-collection.puml`](diagrams/DGM-SEQ-17-collection.puml)
**导出：** [SVG](diagrams/generated/DGM-SEQ-17-collection.svg) · [PNG](diagrams/generated/DGM-SEQ-17-collection.png)
**方法：** 教材 §5.4.3；只表达分析对象协作，不写 Java、数据库、HTTP/API、索引、缓存、调度器、队列或重试算法。

## 1. 顺序图范围

本序列实现 UC-18“管理来源及采集任务”：主流程 E1–E19，以及 A1（未授权拒绝）、A2（来源定义不满足守卫）、A3（停止先于完成）、A4（读取/来源失败）、A5（治理交接失败）、A6（重试）、A7（超过重试上限或执行条件无效）、A8（计划输入不可确定）、A9（成功/停止后另建任务）、A10（来源相互独立）、A11（异常项不得成为公开成果）、A12（重复到达）、A13（查询只读）、A14（受保护写入五要素）分支；A10 以守卫与约束表达，A14 以受控写入消息表达，均无独立交互循环。

参与对象（共 13 个；其中 3 个为参与者，不进入 VOPC）：

- 参与者（图外或图左侧）：管理员（主要参与者）、获准数据来源（次要参与者，外部来源）、未授权请求者（次要参与者，仅触发拒绝路径）
- `AdminConsole`（Boundary）
- `SourceAdapter`（Boundary）
- `CollectionCoordinator`（Control）
- `GovernanceCoordinator`（Control，协作类，属主 UC-09/10）
- `User`（Entity，只读校验）
- `SourceDefinition`（Entity，配置）
- `CollectionTask`（Entity，状态/快照/检查点/历史）
- `SourceRecord`（Entity，到达证据）
- `GovernanceException`（Entity，待处置异常项）
- `AuditEvidence`（Entity，受控写入留痕）

> 图中不出现 `Scheduler`/`TaskQueue`/`JobRunner`/`RetryPolicy`/`Checkpoint`/`ImportBatch`/`ExceptionQueue`/`WorkEntity`/`Authorship`/`CleanRecord`/`DedupCandidate`/`IdentityCandidate`/`PendingTodo`：执行框架、治理判定、成果与待办均不属本 UC（03 §4）。

## 2. 逐消息职责表

| 消息 | 名称 | 发送者 | 接收者 | 事件步骤 | 接收者职责 | 依据/假设 |
|---|---|---|---|---|---|---|
| M1 | 打开采集管理入口（来源与任务）（E1） | 管理员 | AdminConsole | E1 | 接收入口请求，启动采集管理会话 | FR08；§8 |
| M2 | 请求采集管理入口与任务概览（已认证请求上下文） | AdminConsole | CollectionCoordinator | E1 | 启动服务端校验与入口上下文准备 | 分析推导；§8 |
| M3 | 校验账号状态（E2） | CollectionCoordinator | User | E2 | 提供账号状态（可用/停用） | §8；NF03 |
| M4 | 校验“数据来源/采集”具体管理权限（E2） | CollectionCoordinator | User | E2 | 提供“数据来源/采集”具体管理权限 | §8 权限表；FR08 |
| M5 | 权限守卫：无该具体权限不放行，不因“是管理员”默认全量（E2；A1） | CollectionCoordinator | CollectionCoordinator | E2/A1 | 判定是否放行；无具体权限不放行 | §8；FR08 执行前提 |
| M6 | 返回可访问的来源与任务入口（E2） | CollectionCoordinator | AdminConsole | E2 | 返回可访问的来源与任务入口 | E2；分析推导 |
| M7 | 显示来源与任务入口（E2） | AdminConsole | 管理员 | E2 | 显示来源与任务入口 | §8；分析推导 |
| M8 | 提交来源定义：来源身份、输入方式（API 或文件，二选一）、许可/使用范围、数据性质（真实/模拟/混合）、允许范围（E3） | 管理员 | AdminConsole | E3 | 接收来源定义输入（来源身份/输入方式/许可/性质/范围） | FR08；FR16 |
| M9 | 提交来源定义（E3） | AdminConsole | CollectionCoordinator | E3 | 启动来源定义校验 | 分析推导 |
| M10 | 来源定义守卫：已获准∧输入方式为已支持的一种∧许可与范围可声明∧数据性质可标识（E4；A2） | CollectionCoordinator | CollectionCoordinator | E4/A2 | 判定来源是否可作为启动前置 | FR08；FR16；§7.1；A2 |
| M11 | 保存来源定义（来源身份/输入方式/许可与范围/数据性质）（E5） | CollectionCoordinator | SourceDefinition | E5 | 保存来源配置事实 | FR08；FR16 |
| M12 | 返回来源定义引用与可用作启动前置的判定事实（E5） | SourceDefinition | CollectionCoordinator | E5 | 取得来源定义引用与判定事实 | FR08；分析推导 |
| M13 | 记录来源配置写入（操作者/对象/时间/前后值/理由）（E5） | CollectionCoordinator | AuditEvidence | E5 | 追加配置写入留痕（五要素） | BR-IMP-10；INV-10 |
| M14 | 返回来源定义结果；不满足守卫时拒绝保存并说明（E4–E5；A2） | CollectionCoordinator | AdminConsole | E4/E5/A2 | 返回保存结果；不满足守卫时返回拒绝原因 | E4–E5；A2 |
| M15 | 显示来源定义结果与拒绝原因，与失败/空结果可区分（E4–E5；A2） | AdminConsole | 管理员 | E4/E5/A2 | 显示结果与拒绝原因 | NF01 类比；A2 |
| M16 | 创建采集任务并请求启动：选定来源定义、声明本次计划输入范围（E6） | 管理员 | AdminConsole | E6 | 接收任务创建与启动请求（含计划输入范围） | FR08；§6.2 |
| M17 | 提交创建与启动请求（E6） | AdminConsole | CollectionCoordinator | E6 | 启动启动流程 | 分析推导 |
| M18 | 只读读取来源有效性与许可/允许范围（E7） | CollectionCoordinator | SourceDefinition | E7 | 提供来源有效性与许可/允许范围（只读） | FR08；FR16 |
| M19 | 返回来源有效性与许可/允许范围（E7） | SourceDefinition | CollectionCoordinator | E7 | 取得启动前置事实 | 分析推导 |
| M20 | 检查该来源是否已有未结束任务：成功/已停止后须另建关联任务（E7；A9） | CollectionCoordinator | CollectionTask | E7/A9 | 回答该来源既有任务状态与是否须另建关联任务 | §6.2；DGM-STATE-01；A9 |
| M21 | 返回现有任务状态与关联任务引用（E7；A9） | CollectionTask | CollectionCoordinator | E7/A9 | 取得既有任务事实 | A9；分析推导 |
| M22 | 启动守卫：来源有效∧范围可定位∧计划输入可确定∧权限有效（E8；A2/A8） | CollectionCoordinator | CollectionCoordinator | E8/A2/A8 | 判定是否允许启动（守卫） | FR08 执行前提；DGM-STATE-01；A8 |
| M23 | 保存参数快照与初始检查点，创建任务（待执行）（E9） | CollectionCoordinator | CollectionTask | E9 | 保存参数快照与初始检查点，创建待执行任务 | §6.2；DGM-STATE-01 |
| M24 | 返回任务引用、待执行状态与快照标识（E9） | CollectionTask | CollectionCoordinator | E9 | 取得任务引用、状态与快照标识 | E9 |
| M25 | 追加任务历史：创建、参数快照与启动决定（E9） | CollectionCoordinator | CollectionTask | E9 | 追加创建/快照/启动决定的任务历史 | §6.2 |
| M26 | 记录启动决定（操作者/对象/时间/前后值/理由）（E9） | CollectionCoordinator | AuditEvidence | E9 | 追加启动决定留痕 | BR-IMP-10 |
| M27 | 启动生效：待执行→运行中（E10） | CollectionTask | CollectionCoordinator | E10 | 应用启动生效（待执行→运行中） | §6.2；DGM-STATE-01 |
| M28 | 返回任务已启动与范围快照（E10） | CollectionCoordinator | AdminConsole | E10 | 返回运行中与范围快照 | E10 |
| M29 | 显示运行中、范围快照与“成功不代表人工治理完成”（E10） | AdminConsole | 管理员 | E10 | 显示运行中与“成功不代表人工治理完成” | FR08；BR-UC18-08 |
| M30 | 拒绝启动：不创建任务、无业务写入（E8；A2/A8） | CollectionCoordinator | AdminConsole | E8/A2/A8 | 返回拒绝启动与原因（无业务写入） | FR08 执行前提；A2/A8 |
| M31 | 显示拒绝原因，与失败/空结果可区分（E8；A2/A8） | AdminConsole | 管理员 | E8/A2/A8 | 显示拒绝原因 | NF01 类比；A8 |
| M32 | 读取参数快照、检查点与失败点（E11） | CollectionCoordinator | CollectionTask | E11 | 提供快照、检查点与失败点（只读） | §6.2；E11 |
| M33 | 返回快照、检查点与已完成范围（E11） | CollectionTask | CollectionCoordinator | E11 | 取得续跑所需事实 | E11 |
| M34 | 按来源定义与快照请求本批输入（API 或文件）（E11） | CollectionCoordinator | SourceAdapter | E11 | 按来源定义与快照请求本批输入（API 或文件） | FR08；D-10 |
| M35 | 请求本批记录（按许可与允许范围）（E11） | SourceAdapter | 获准数据来源 | E11 | 提供本批记录或失败反馈 | FR08；DGM-USECASE-03 |
| M36 | 返回记录、读取失败或断连反馈（E11；A4） | 获准数据来源 | SourceAdapter | E11/A4 | 转交记录或失败反馈 | A4；FR08 |
| M37 | 返回本批输入与来源证据，读取失败时返回失败反馈（E11；A4） | SourceAdapter | CollectionCoordinator | E11/A4 | 取得本批输入与来源证据或失败反馈 | E11；A4 |
| M38 | 逐条登记来源身份、到达事件、原值、数据性质、许可与到达时间（E12） | CollectionCoordinator | SourceRecord | E12 | 保存到达证据与原值（既有原值不改写；重复到达追加事件） | INV-01；FR16；A12 |
| M39 | 返回逻辑出处标识（source_id＋source_record_key）与是否重复到达（E12；A12） | SourceRecord | CollectionCoordinator | E12/A12 | 取得逻辑出处标识与重复到达标记 | FR09-06；A12 |
| M40 | 逐条移交清洗/去重/身份归属处置（E13） | CollectionCoordinator | GovernanceCoordinator | E13 | 接收逐条移交并处置（清洗/去重/身份归属规则属 UC-09/10） | FR08；FR09/FR15 边界 |
| M41 | 返回处置结果（已登记结果/待核验）或“需异常移交”与原因（E13；A5） | GovernanceCoordinator | CollectionCoordinator | E13/A5 | 取得处置结果或“需异常移交”与原因 | §6.2；A5 |
| M42 | 对无法处置的记录建立异常项：原因、对象引用、待处理状态，不设公开可见性（E14；A11） | CollectionCoordinator | GovernanceException | E14/A11 | 建立待处理异常项（无公开可见性） | AC-IMPL-FR08-01；BR-IMP-01；A11 |
| M43 | 返回异常项引用与待处理状态（E14；A11） | GovernanceException | CollectionCoordinator | E14/A11 | 取得异常项引用与待处理状态 | §6.2；A5 |
| M44 | 保存本批互斥处置计数与检查点（已登记结果/待核验/异常）（E15） | CollectionCoordinator | CollectionTask | E15 | 保存互斥处置计数与检查点 | §6.2；B-08 |
| M45 | 追加任务历史：本批尝试、处置计数与检查点（E15） | CollectionCoordinator | CollectionTask | E15 | 追加本批尝试历史（计数/检查点） | §6.2 |
| M46 | 查询任务进度（E16） | 管理员 | AdminConsole | E16 | 接收进度查询 | FR08“进度” |
| M47 | 转交进度查询（E16） | AdminConsole | CollectionCoordinator | E16 | 启动只读进度读取 | 分析推导；A13 |
| M48 | 只读读取状态、检查点与批次计数（已登记结果/待核验/异常）（E16；A13） | CollectionCoordinator | CollectionTask | E16/A13 | 提供状态、检查点与批次计数（只读） | FR08；A13 |
| M49 | 返回进度事实（E16；A13） | CollectionTask | CollectionCoordinator | E16/A13 | 取得进度事实 | A13 |
| M50 | 只读读取待处理异常数量（E16；A13） | CollectionCoordinator | GovernanceException | E16/A13 | 提供待处理异常数量（只读） | §6.2；A13 |
| M51 | 返回待处理异常数量（E16；A13） | GovernanceException | CollectionCoordinator | E16/A13 | 取得异常计数 | A13 |
| M52 | 返回进度（只读派生，不推进状态）（E16；A13） | CollectionCoordinator | AdminConsole | E16/A13 | 返回只读进度（不推进状态） | E16；BR-UC18-13 |
| M53 | 显示进度、检查点与单列的待核验/异常计数（E16；A13） | AdminConsole | 管理员 | E16/A13 | 显示进度、检查点与单列计数 | FR08；A13 |
| M54 | 读取计划输入清单与已记录计数（结果/异常）（E17） | CollectionCoordinator | CollectionTask | E17 | 提供计划输入清单与已记录计数（结果/异常） | §6.2；E17 |
| M55 | 返回计划输入清单与已记录计数（E17） | CollectionTask | CollectionCoordinator | E17 | 取得完成核对依据 | E17 |
| M56 | 核对每条异常项均已建立且为待处理（可追踪移交）（E17；A5） | CollectionCoordinator | GovernanceException | E17/A5 | 回答异常项是否均已建立且待处理 | §6.2；A5 |
| M57 | 返回异常项核对结果（E17；A5） | GovernanceException | CollectionCoordinator | E17/A5 | 取得异常移交核对结果 | A5 |
| M58 | 完成守卫：计划输入全部记录到结果或异常队列；交接失败或未记录不算完成（E17；A5） | CollectionCoordinator | CollectionCoordinator | E17/A5 | 判定是否满足完成判据 | §6.2；AC-IMPL-FR08-01 |
| M59 | 完成生效：运行中→成功（E18） | CollectionTask | CollectionCoordinator | E18 | 应用完成生效（运行中→成功） | §6.2；DGM-STATE-01 |
| M60 | 记录完成结论与计数（含待核验/异常；不写“人工治理完成”）（E18） | CollectionCoordinator | AuditEvidence | E18 | 追加完成结论与计数留痕（不写“人工治理完成”） | BR-IMP-10；FR08 |
| M61 | 返回成功＋单列的待核验与异常数量（E18） | CollectionCoordinator | AdminConsole | E18 | 返回成功与单列待核验/异常数量 | AC-IMPL-FR08-01；FR08 |
| M62 | 显示成功结论、单列计数与“不等于已公开/已核验”（E18） | AdminConsole | 管理员 | E18 | 显示成功结论、单列计数与限定措辞 | FR08 规范性要求；§6.2 |
| M63 | 请求停止任务（A3） | 管理员 | AdminConsole | A3 | 接收停止请求 | FR08“停止” |
| M64 | 转交停止请求（A3） | AdminConsole | CollectionCoordinator | A3 | 启动停止裁定（先生效） | §6.2；DGM-STATE-01 |
| M65 | 停止决定生效：先于完成则运行中→已停止，保留已完成项与未完成范围（A3） | CollectionCoordinator | CollectionTask | A3 | 应用停止决定并保留完成项与未完成范围 | §6.2；DGM-STATE-01 |
| M66 | 返回已停止与保留范围（A3） | CollectionTask | CollectionCoordinator | A3 | 取得已停止与保留范围 | A3 |
| M67 | 记录停止决定（操作者/时间/理由/前后状态）（A3） | CollectionCoordinator | AuditEvidence | A3 | 追加停止决定留痕 | BR-IMP-10 |
| M68 | 返回已停止与保留项（A3） | CollectionCoordinator | AdminConsole | A3 | 返回已停止与保留项 | A3 |
| M69 | 显示已停止、保留完成项；已停止为终态，仅可另建关联任务（A3） | AdminConsole | 管理员 | A3 | 显示已停止与后续入口 | A3 |
| M70 | 运行中→失败：保存失败点、已成功结果与恢复范围（A4） | CollectionTask | CollectionCoordinator | A4 | 记录失败状态、失败点与恢复范围 | FR08；A4 |
| M71 | 记录失败事实与失败点（不含凭据与秘密）（A4） | CollectionCoordinator | AuditEvidence | A4 | 追加失败事实与失败点（不含凭据与秘密） | BR-IMP-10；NF03 |
| M72 | 返回失败、失败点与恢复入口（A4） | CollectionCoordinator | AdminConsole | A4 | 返回失败、失败点与恢复入口 | A4；NF04-01 |
| M73 | 显示失败、失败点与“已完成项仍保留”（A4） | AdminConsole | 管理员 | A4 | 显示失败与“已完成项仍保留” | BR-IMP-04 |
| M74 | 请求重试（A6） | 管理员 | AdminConsole | A6 | 接收重试请求 | AC-IMPL-FR08-02 |
| M75 | 转交重试请求（A6） | AdminConsole | CollectionCoordinator | A6 | 启动重试守卫 | A6 |
| M76 | 只读读取失败点、检查点与已完成项（A6） | CollectionCoordinator | CollectionTask | A6 | 提供失败点、检查点与已完成项（只读） | AC-IMPL-FR08-02；A6 |
| M77 | 返回失败点、检查点与已完成项（历史成功记录保留）（A6） | CollectionTask | CollectionCoordinator | A6 | 取得续跑依据 | A6 |
| M78 | 校验账号状态与“数据来源/采集”具体管理权限（A6） | CollectionCoordinator | User | A6 | 复核账号状态与采集权限 | §8；A6 |
| M79 | 重试守卫：执行条件有效∧未超过重试上限∧不重做已完成项（A6–A7） | CollectionCoordinator | CollectionCoordinator | A6/A7 | 判定重试是否允许（上限待确认） | DGM-STATE-01；D-10；A7 |
| M80 | 失败→重试→运行中：从检查点续跑并保留历史成功记录（A6） | CollectionCoordinator | CollectionTask | A6 | 应用失败→重试→运行中，从检查点续跑 | §6.2；INV-07 |
| M81 | 返回重试中与续跑范围（A6） | CollectionTask | CollectionCoordinator | A6 | 取得续跑范围 | A6 |
| M82 | 记录重试决定（操作者/时间/理由/失败点/前后状态）（A6） | CollectionCoordinator | AuditEvidence | A6 | 追加重试决定留痕 | BR-IMP-10 |
| M83 | 返回重试已启动与续跑范围（A6） | CollectionCoordinator | AdminConsole | A6 | 返回重试中与续跑范围 | A6 |
| M84 | 显示重试中、续跑范围与失败点定位（A6） | AdminConsole | 管理员 | A6 | 显示重试中与失败点定位 | AC-IMPL-FR08-02 |
| M85 | 拒绝重试：保持失败状态并提供恢复入口（A7） | CollectionCoordinator | AdminConsole | A7 | 返回重试被拒原因 | A7 |
| M86 | 显示重试被拒原因（A7） | AdminConsole | 管理员 | A7 | 显示重试被拒 | A7 |
| M87 | 查看任务历史与失败定位（E19） | 管理员 | AdminConsole | E19 | 接收历史查询 | NF07-01 |
| M88 | 转交历史查询（E19） | AdminConsole | CollectionCoordinator | E19 | 启动只读历史读取 | A13 |
| M89 | 只读读取任务历史：尝试次数、起止、结果、失败点、检查点与计数（E19） | CollectionCoordinator | CollectionTask | E19 | 提供任务历史、检查点与失败点（只读） | §6.2；NF07-01 |
| M90 | 返回任务历史与检查点（E19） | CollectionTask | CollectionCoordinator | E19 | 取得任务历史 | E19 |
| M91 | 只读读取该任务相关受控写入留痕（E19） | CollectionCoordinator | AuditEvidence | E19 | 提供该任务相关受控写入留痕（只读） | BR-IMP-10；NF07-01 |
| M92 | 返回留痕（操作者/时间/前后值/理由）（E19） | AuditEvidence | CollectionCoordinator | E19 | 取得留痕 | E19 |
| M93 | 返回任务历史与可定位的错误项（E19） | CollectionCoordinator | AdminConsole | E19 | 返回任务历史与可定位错误项 | NF07-01 |
| M94 | 显示任务历史、失败点与关联留痕（E19） | AdminConsole | 管理员 | E19 | 显示任务历史、失败点与关联留痕 | NF07-01 |
| M95 | 未授权请求者提交采集操作（A1） | 未授权请求者 | AdminConsole | A1 | 接收未授权采集操作 | FR08 执行前提；§8 |
| M96 | 转交请求（已认证上下文） | AdminConsole | CollectionCoordinator | A1 | 启动账号与权限校验（不执行请求内容） | 分析推导；§8 |
| M97 | 校验账号状态与“数据来源/采集”具体管理权限（A1） | CollectionCoordinator | User | A1 | 提供账号状态与采集权限 | §8；NF03 |
| M98 | 拒绝：无业务变更，不返回来源定义与任务明细（A1） | CollectionCoordinator | AdminConsole | A1 | 返回拒绝（无业务变更、不返回明细） | §8；NF01 类比 |
| M99 | 显示拒绝，与失败/空结果可区分（A1） | AdminConsole | 未授权请求者 | A1 | 显示拒绝，与失败/空结果可区分 | NF01 类比；A1 |
| M100 | 记录受控拒绝与越权尝试（不含凭据）（A1） | CollectionCoordinator | AuditEvidence | A1 | 追加受控拒绝/越权留痕（不含凭据） | BR-IMP-10；NF03 |

## 3. 事件、分支与消息对照

| 事件/分支 | 消息 | 关键守卫 | 可观察结果 |
|---|---|---|---|
| E1–E2 入口与权限 | M1–M7 | 账号状态∧“数据来源/采集”具体权限（M3–M5） | 返回可访问来源与任务入口 |
| E3–E5 来源配置 | M8–M15 | 已获准∧输入方式已支持∧许可与范围可声明∧数据性质可标识（M10） | 来源定义保存或被拒绝 |
| E6–E10 创建与启动 | M16–M31 | 来源有效∧范围可定位∧计划输入可确定∧权限有效（M22） | 待执行→运行中并保存快照/检查点/历史 |
| E11–E15 读取、登记与移交 | M32–M45 | 原值不改写∧逐条登记∧逐条移交∧异常项待处理（M38–M43） | 来源记录＋处置结果＋异常项＋本批计数/检查点 |
| E16 进度 | M46–M53 | 只读（M48/M50） | 状态、检查点、单列计数 |
| E17–E18 完成判据与成功 | M54–M62 | 计划输入全部记录到结果或异常队列（M58） | 运行中→成功＋单列待核验/异常 |
| E19 任务历史与失败定位 | M87–M94 | 只读（M89/M91） | 尝试历史、失败点、关联留痕 |
| A1 未授权 | M95–M100 | 账号状态∧具体权限（M97） | 拒绝、无业务变更、受控拒绝留痕 |
| A2 来源定义不满足守卫 | M10；M14–M15；M22；M30–M31 | 获准∧输入方式∧许可/范围∧数据性质 | 拒绝保存或拒绝启动，无写入 |
| A3 停止先于完成 | M63–M69 | 停止决定先生效（M65） | 已停止＋保留完成项/未完成范围 |
| A4 读取/来源失败 | M36–M37；M70–M73 | 失败反馈（M36） | 失败＋失败点＋恢复范围（历史保留） |
| A5 治理交接失败 | M41–M43；M56–M58 | 异常项可追踪（M56） | 不算成功；运行中→失败；已登记结果保留 |
| A6 重试 | M74–M84 | 执行条件有效∧不重做已完成项（M79） | 失败→重试→运行中，续跑范围可核对 |
| A7 超过上限/条件无效 | M79；M85–M86 | 未超过重试上限（上限待确认） | 拒绝重试，保持失败并保留恢复入口 |
| A8 计划输入不可确定 | M22；M30–M31 | 计划输入可确定∧范围可定位 | 拒绝启动，不以空范围冒充成功 |
| A9 成功/停止后再次启动 | M20–M21 | 成功/已停止为终态 | 另建关联任务（新快照） |
| A10 来源相互独立 | M16–M21；M70 | 任务绑定单一来源定义 | 单来源失败不影响另一来源任务与其结果 |
| A11 异常项不得成为公开成果 | M42–M43 | 异常项无公开可见性∧本 UC 不建成果 | 异常保持待处理，不公开 |
| A12 重复到达 | M38–M39 | 逻辑出处唯一（M39） | 追加到达事件，不新增第二条逻辑出处 |
| A13 查询只读 | M46–M53；M89–M92 | 只读（M48/M50/M89/M91） | 不改状态、计数与留痕 |
| A14 受保护写入五要素 | M13；M26；M60；M67；M71；M82；M100 | 操作者/对象/时间/前后值/理由 | 留痕可核对；缺要素不记完成 |

## 4. 完整性与一致性核对

1. **每个类都有消息：** 10 个类均有至少一条接收或应答消息（M1–M100），无“只在文字里出现”的类。
2. **每个事件都有消息：** E1–E19 与 A1–A14 在 §3 均可定位到消息区间；A10/A14 以守卫与约束表达。
3. **无治理判定消息：** 对 `GovernanceCoordinator` 只有 M40 移交与 M41 回执，没有“清洗/去重/合并/身份判定”消息（FR09/FR15 不属本 UC）。
4. **无发布消息：** 对 `WorkEntity`/`Authorship`/`Scholar`/公开视图没有任何消息；异常项无公开可见性（A11）。
5. **无执行框架消息：** 不存在“建队列/投递任务/调度/重试策略”消息；检查点与批次计数只以 `CollectionTask` 属性形式出现（M44/M45）。
6. **状态推进只在 §6.2 允许的转移上：** M27（待执行→运行中）、M59（运行中→成功）、M65（运行中→已停止）、M70（运行中→失败）、M80（失败→重试→运行中）；无新增状态与转移。
7. **原值不可改写：** 对 `SourceRecord` 只有 M38 登记（新记录或追加到达事件）与 M39 回执；无“改写原值”消息（INV-01）。
8. **完成判据先于状态推进：** M54–M58 核对通过后才有 M59 的“运行中→成功”，顺序不可交换（BR-UC18-06）。
9. **重试不重做：** M76–M80 先读取失败点/检查点/已完成项，再由 M80 从检查点续跑；无“重跑全部输入”的消息（A6）。
10. **消息号连续：** M1–M100 无缺号、无重复（实际脚本输出见 08 §4.4）。

## 5. 与图的一致性

| 检查 | 方法 | 结果 |
|---|---|---|
| 消息号与名称 | 从 `.puml` 抽取 `M<n> …` 与 04 表逐行比对 | 100/100 一致（见 08 §4.4 实际输出） |
| 发送者/接收者 | 抽取箭头两端别名并映射为类名 | 100/100 一致 |
| 类的集合 | 图对象集合 vs 05 §1 表 | 10/10 一致 |
| 参与者不建模 | 图中 `管理员`、`获准数据来源`、`未授权请求者` 为 actor | 未出现在 VOPC |

> 本图与 04 表为同一交付的两个视图；若后续修改消息，必须同步修改本文与 05/06 的消息引用，并在 08 记录重渲结果。

> 状态应用说明：M27/M59/M70 是 `CollectionTask` 在守卫结果后应用状态的应答；M65/M80 是指令式停止/重试。状态迁移仍以 `DGM-STATE-01` 为准。
