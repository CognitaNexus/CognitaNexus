# UC-11 分析序列与逐消息职责

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-SEQ-19-statistics.puml`](diagrams/DGM-SEQ-19-statistics.puml)
**导出：** [SVG](diagrams/generated/DGM-SEQ-19-statistics.svg) · [PNG](diagrams/generated/DGM-SEQ-19-statistics.png)
**方法：** 教材 §5.4.3；只表达分析对象协作，不写 Java、数据库、HTTP/API、索引、缓存、任务调度或聚合算法。

## 1. 顺序图范围

本序列实现 UC-11“查看成果统计”：主流程 E1–E14，以及 A1（条件非法）、A2（空结果）、A3（失败）、A4（数据未更新）、A5（缺数据性质标识）、A6（模拟/混合）、A7（机构缺失）、A8（只有学者当前单位）、A9（机构别名合并）、A10（跨源/版本重复）、A11（无法映射领域）、A12（多领域）、A13（未纳入实体）、A14（样本不足）、A15（规模超限）、A16（质量/影响力/个人排名）、A17（来源数/版本数/作者数当成果数）、A18（越界到其他 UC）、A19（数据性质混用）分支。

参与对象（共 6 个；其中 1 个为参与者，不进入 VOPC）：

- 参与者（图左）：访客（主要参与者）
- `StatisticsUI`（Boundary）
- `StatisticsCoordinator`（Control）
- `WorkEntity`（Entity，只读）
- `Authorship`（Entity，只读）
- `Institution`（Entity，只读）

**不画入图的外部前置：** 口径与分类映射维护方（FR14/FR16）在视图可用前已产出生效的机构规范名/别名与领域分类映射；本 UC 无与之交互的消息（01 §1；B-02）。

## 2. 逐消息职责表

| 消息 | 名称 | 发送者 | 接收者 | 事件步骤 | 接收者职责 | 依据/假设 |
|---|---|---|---|---|---|---|
| M1 | 打开成果统计视图（默认现实视图）（E1） | 访客 | StatisticsUI | E1 | 承接统计视图请求，默认进入现实视图 | FR11；BR-IMP-07；`AC-IMPL-FR11-02`；NF01 |
| M2 | 请求默认统计（视图范围＋公开只读请求上下文） | StatisticsUI | StatisticsCoordinator | E1 | 启动统计读取，转交视图范围 | 分析推导；FR11 参与者“访客” |
| M3 | 默认视图守卫：只纳入真实、公开、生效且合格的成果实体（BR-IMP-07/01；FR11） | StatisticsCoordinator | StatisticsCoordinator | E2 | 判定纳入范围，排除模拟/混合与未公开/未生效数据 | FR11 规范性要求；BR-IMP-01/07 |
| M4 | 只读读取统计范围内去重成果实体与共享字段（类型／年度及时间语义／领域／数据性质／公开生效状态）（E2） | StatisticsCoordinator | WorkEntity | E2 | 提供去重实体与共享字段（只读） | FR11；§7.1；INV-02；FR11-S07 前置 |
| M5 | 返回去重实体集合（每实体一条；不含来源数与版本数） | WorkEntity | StatisticsCoordinator | E2 | 提供计数单位集合 | BR-IMP-02/06；`AC-IMPL-FR16-02` 边界 |
| M6 | 统计资格守卫：缺稳定标识或去重冲突未决者不计入，并披露未纳入实体数（A13） | StatisticsCoordinator | StatisticsCoordinator | A13 | 排除不可判定实体并保留披露计数 | FR11-S07 E1/E2；BR-IMP-02 |
| M7 | 去重计数守卫：跨源重复与已确认版本只计一次；不按来源数／版本数／作者数计数（A10；BR-IMP-02/06） | StatisticsCoordinator | StatisticsCoordinator | E3/A10 | 保证一个实体只贡献一次计数 | BR-IMP-02/06；`AC-IMPL-FR11-01`；FR11-S07 |
| M8 | 类型分布计数：四类之和等于同条件实体总数（E3） | StatisticsCoordinator | StatisticsCoordinator | E3 | 按成果类型分组计数，0 类保留展示 | FR11；FR11-S02；BR-IMP-06 |
| M9 | 年度分布计数：缺发生年份者归“时间未知”分组（E3） | StatisticsCoordinator | StatisticsCoordinator | E3 | 按类型对应发生时间归年，缺失单列 | FR11；FR11-S03；B-03 |
| M10 | 领域分布计数：一实体关联多领域时在各领域各计一次，合计可大于实体总数（A12） | StatisticsCoordinator | StatisticsCoordinator | E3/A12 | 按领域分组并保留重复计入口径 | BR-IMP-06；FR11-S04；B-02 |
| M11 | 生成口径声明：数据范围／时间范围／统计条件／计数口径／更新时间／数据性质（E4） | StatisticsCoordinator | StatisticsCoordinator | E4 | 组装六项同屏声明 | FR11 规范性要求；BR-514；FR11-S01 |
| M12 | 返回分布结果＋口径声明＋真实数据标识（E5） | StatisticsCoordinator | StatisticsUI | E5 | 返回结果、声明与数据性质标识 | FR11；`AC-IMPL-FR11-02`；NF06 |
| M13 | 同屏显示分布结果与口径声明（E5） | StatisticsUI | 访客 | E5 | 显示结果与声明，包含更新时间与真实数据标识 | FR11；BR-514 |
| M14 | 请求机构排名（成果类型／领域／时间窗条件）（E6） | 访客 | StatisticsUI | E6 | 提交排名条件 | FR11；FR11-S05 |
| M15 | 提交机构排名请求（条件＋公开只读请求上下文） | StatisticsUI | StatisticsCoordinator | E6 | 转交条件并启动排名读取 | 分析推导 |
| M16 | 拒绝：提示“时间范围无效／条件无效”，保留原有结果与条件（A1） | StatisticsCoordinator | StatisticsUI | A1 | 拒绝非法条件，保持原结果 | FR11-S06 E1/E3；NF01 |
| M17 | 显示条件错误提示，原结果未被清空 | StatisticsUI | 访客 | A1 | 显示条件错误并保留可编辑条件 | FR11-S06 E1；NF01 |
| M18 | 只读读取成果的发生时机构归属事实与证据引用（含缺失标记）（E8） | StatisticsCoordinator | WorkEntity | E8 | 提供有证据的成果发生时机构归属（只读） | BR-IMP-06；BR-513；SR-06；§7.1 |
| M19 | 返回发生时机构原值／证据引用／缺失标记 | WorkEntity | StatisticsCoordinator | E8 | 提供机构归属事实与缺失标记 | 分析推导；§7.1 |
| M20 | 只读读取参与者角色／顺序／发生时机构原值（不按作者数计数）（E8） | StatisticsCoordinator | Authorship | E8 | 提供参与者级机构原值（只读） | ID-05；§7.2；BR-519（细化） |
| M21 | 返回参与者级机构原值与关系状态（只读，不改写） | Authorship | StatisticsCoordinator | E8 | 提供多机构原值与关系状态 | 分析推导；ID-05 |
| M22 | 机构归属守卫：只采用有证据的成果发生时机构；不以学者当前任职单位推断；缺失或无证据→机构未知（A7/A8） | StatisticsCoordinator | StatisticsCoordinator | E8/E9 | 判定每个成果的机构归属集合，未知单列 | BR-IMP-06；BR-513；SR-06；SPEC T35 |
| M23 | 只读读取机构规范名、别名与合并映射（E9） | StatisticsCoordinator | Institution | E9 | 提供规范名、别名与合并链（只读） | FR11-S08 E2；CL-05；BR-513 |
| M24 | 返回规范名／合并后机构标识／未映射标记（A9） | Institution | StatisticsCoordinator | E9 | 提供归并结果与未映射标记 | FR11-S08 E2；CL-05 |
| M25 | 机构维度计数：同一成果同一机构计一次、不同机构各计一次；机构未知单列不进名次（E9） | StatisticsCoordinator | StatisticsCoordinator | E9 | 按机构归并结果计数并剔除未知名次 | BR-IMP-06；`AC-IMPL-FR11-01` |
| M26 | 排名生成：计数降序＋并列名次跳号＋覆盖率与“样本不足”标注（A14） | StatisticsCoordinator | StatisticsCoordinator | E10/A14 | 排序、并列与样本不足标注 | FR11-S05/S10；BR-520；B-05 |
| M27 | 返回排名＋机构未知分组＋归属／并列口径＋更新时间 | StatisticsCoordinator | StatisticsUI | E10/E11 | 返回排名与口径说明 | FR11；BR-514 |
| M28 | 显示排名与归属口径：不作质量、影响力或机构优劣结论（E10/E11） | StatisticsUI | 访客 | E11 | 显示排名与口径，附禁止质量表述的固定声明 | BR-522；FR11 |
| M29 | 调整条件（时间窗／成果类型／领域／数据性质＝真实／模拟／全部）（E12） | 访客 | StatisticsUI | E12 | 接收条件变更 | FR11-S06；FR11-S12 |
| M30 | 提交条件变更 | StatisticsUI | StatisticsCoordinator | E12 | 转交条件变更 | 分析推导 |
| M31 | 数据性质守卫：默认仅真实；模拟与混合只在显式演示模式展示并固定标识“不得用于描述真实世界”（A5/A6） | StatisticsCoordinator | StatisticsCoordinator | E13/A5/A6 | 按数据性质过滤并决定是否进入演示模式 | BR-IMP-07；`AC-IMPL-FR11-02`；FR11-S12 |
| M32 | 返回按条件重算结果并同步刷新口径声明（不保留与结果不符的旧声明） | StatisticsCoordinator | StatisticsUI | E12/E13 | 返回重算结果与刷新后的声明 | FR11-S06 A1；FR11-S01 A1 |
| M33 | 显示结果与刷新后的口径、演示模式标识（E13） | StatisticsUI | 访客 | E13 | 显示结果、声明与模拟/混合标识 | `AC-IMPL-FR11-02`；NF06 |
| M34 | 从统计条目下钻复核（E14） | 访客 | StatisticsUI | E14 | 接收下钻请求 | FR11-S11 |
| M35 | 请求该条目对应的统计条件回显（不请求统计重算） | StatisticsUI | StatisticsCoordinator | E14 | 取回条目对应条件 | 分析推导；FR11-S11 |
| M36 | 返回同一统计条件（供检索按同条件复现计数） | StatisticsCoordinator | StatisticsUI | E14 | 返回条件，不复制检索逻辑 | FR11-S11；FR01 复用 |
| M37 | 显示带条件回显的检索入口（检索执行属 UC-01） | StatisticsUI | 访客 | E14 | 显示条件回显的复核入口 | FR11-S11 |
| M38 | 命中 0 条→“当前条件没有可统计的成果”，保留条件（A2） | StatisticsCoordinator | StatisticsCoordinator | A2 | 判定空结果，与失败区分 | FR11-S10 E1；NF01 |
| M39 | 返回空结果提示（与失败可区分） | StatisticsCoordinator | StatisticsUI | A2 | 返回空结果分支 | `AC-IMPL-NF01-01` 同类 |
| M40 | 读取或计算不可用→明确失败；不展示部分结果，不编造更新时间（A3） | StatisticsCoordinator | StatisticsCoordinator | A3 | 判定失败分支，禁止部分结果 | FR11-S09 E1/E3；BR-523；NF01 |
| M41 | 返回失败提示（与空结果、越权可区分） | StatisticsCoordinator | StatisticsUI | A3 | 返回失败分支 | NF01 |
| M42 | 更新时间与生效数据版本核对：落后时标注“数据更新至…／结果可能未反映最新变更”（A4；NF05） | StatisticsCoordinator | StatisticsCoordinator | A4 | 核对同一生效版本并决定是否标注未更新 | NF05；`AC-IMPL-NF05-01/02`；BR-523；B-04 |
| M43 | 返回更新时间、未更新提示与未纳入实体数 | StatisticsCoordinator | StatisticsUI | A4 | 返回时间信息与披露计数 | FR11；BR-514；FR11-S07 |
| M44 | 领域或机构无法可靠映射→“待分类”“机构未知”分组并披露影响，不强行归类（A11） | StatisticsCoordinator | StatisticsCoordinator | A11 | 保留未映射分组，不分配 | FR11-S04-03；C-7；Q-SPEC-03 |
| M45 | 返回待分类与机构未知计数及影响说明 | StatisticsCoordinator | StatisticsUI | A11 | 返回未映射披露 | FR11-S04/S08 |
| M46 | 展示规模守卫：超过单次展示上限时给出当前规模并引导收窄，子集标注“部分结果（已声明子集）”（A15） | StatisticsCoordinator | StatisticsCoordinator | A15 | 判定规模并限制展示 | FR11-S10 E2；BR-528；B-07 |
| M47 | 返回规模提示或已声明子集标注 | StatisticsCoordinator | StatisticsUI | A15 | 返回规模分支结果 | FR11-S10；BR-528 |
| M48 | 范围守卫：拒绝质量／影响力／机构优劣／个人综合学术排名，以及以来源数／版本数／作者数当成果数的请求（A16/A17/A19） | StatisticsCoordinator | StatisticsCoordinator | A16/A17/A19 | 拒绝越界与错误单位请求 | BR-522；SRS §2.1；BR-IMP-02/06/07 |
| M49 | 返回拒绝结论与可用范围说明（热点／合作网络／最短路径属 UC-13/14/15）（A18） | StatisticsCoordinator | StatisticsUI | A18 | 返回拒绝与交接说明 | FR13/FR12/IN01 边界；B-08 |

## 3. 事件、分支与消息对照

| 事件/分支 | 消息 | 关键守卫 | 可观察结果 |
|---|---|---|---|
| E1 打开视图 | M1–M2 | — | 进入默认现实视图 |
| E2 范围与读取 | M3–M5 | 真实∧公开∧生效∧合格 | 去重实体集合与共享字段 |
| A13 未纳入实体 | M6 | 稳定标识可用∧去重冲突已决 | 未纳入实体数披露 |
| E3/A10 去重计数 | M7–M9 | 一实体一次；来源/版本/作者不放大 | 类型与年度分布 |
| A12 多领域 | M10 | 逐领域各计一次 | 领域分布＋重复计入口径 |
| E4 声明 | M11–M13 | 六项齐备且与结果一致 | 结果与声明同屏 |
| E6/E7 条件与校验 | M14–M17 | 时间范围有效∧取值合法 | 进入排名或拒绝并保留原结果 |
| E8 机构归属证据 | M18–M21 | 只读；逐参与者原值 | 机构归属证据集合 |
| E9/A7/A8 归属守卫 | M22 | 有证据的发生时机构 | 机构归属集合＋未知标记 |
| E9/A9 归并 | M23–M24 | 已生效别名/合并映射 | 规范机构标识或未映射 |
| E10/A14 计数与排名 | M25–M26 | 同机构一次/跨机构各一次；未知不进名次 | 排名＋覆盖率＋样本不足标注 |
| E11 展示 | M27–M28 | 不作质量结论 | 排名与口径 |
| E12/E13 条件与数据性质 | M29–M33 | 默认仅真实；模拟/混合仅演示并标识 | 重算结果＋刷新声明 |
| E14 下钻 | M34–M37 | 条件回显一致 | 带条件的检索入口 |
| A2 空结果 | M38–M39 | 命中 0 且非失败 | 空结果提示，条件保留 |
| A3 失败 | M40–M41 | 不可用 | 失败提示，无部分结果 |
| A4 未更新 | M42–M43 | 版本核对 | 更新时间＋可能未反映最新变更 |
| A11 无法映射 | M44–M45 | 不强行归类 | 待分类/机构未知计数 |
| A15 规模超限 | M46–M47 | 展示上限 | 规模提示／已声明子集 |
| A16–A19 范围与单位边界 | M48–M49 | 计数单位∧可用范围 | 拒绝与交接说明 |

## 4. 完整性与一致性核对

| 检查 | 结果 | 说明 |
|---|---|---|
| 消息编号 | M1–M49 连续、无缺号、无重复 | 脚本核对（08 §4.4） |
| 每条消息有发送者/接收者/事件/职责/依据 | 是 | 表 2 全列齐备 |
| 每个参与类至少收到一条消息 | 是 | UI：M1/M14/M29/M34；Ctl：M2/M15/M30/M35；WorkEntity：M4/M18；Authorship：M20；Institution：M23 |
| 是否出现按来源数/版本数/作者数计数 | 否（有反证守卫） | M5/M7/M48；BR-UC11-01 |
| 是否出现“机构排名使用学者当前任职单位” | 否（有反证守卫） | M22；A8；BR-UC11-07 |
| 是否把机构未知计入已知名次 | 否 | M25；BR-UC11-09 |
| 是否出现同一机构重复行 | 否（有归并消息） | M23/M24 |
| 是否把模拟/混合计入默认现实视图 | 否 | M3/M31；`AC-IMPL-FR11-02` |
| 是否出现质量/影响力/机构优劣/个人排名结论 | 否（有拒绝与固定声明） | M28/M48；BR-522 |
| 是否出现写成果/关系/机构/来源的消息 | 否 | 只有 M4/M18/M20/M23 四条只读消息 |
| 是否出现持久统计结果/快照/口径版本对象 | 否 | 05 §4；C-01 |
| 是否出现统计实现（缓存、任务、聚合、接口、DTO） | 否 | 03 §4；只有分析职责 |
| 空结果/失败/未更新/规模超限/条件非法是否分开 | 是 | M16、M38–M39、M40–M41、M42–M43、M46–M47 |
| 声明与结果是否始终同步 | 是（有守卫） | M11/M12、M32；BR-UC11-12 |
| 下钻是否复制检索逻辑 | 否 | M35/M36 只回显条件；检索属 UC-01 |

## 5. 与图的一致性

`DGM-SEQ-19` 的 49 条消息与本表逐条对应；图中分组为“基准视图：去重成果的类型/年度/领域分布（E1–E5）→ 机构维度：成果发生时机构归属与排名（E6–E11）→ 条件与数据性质：现实视图与演示模式（E12–E13）→ 复核入口：下钻检索（E14）→ 空结果、失败、更新时间、待分类、规模与范围守卫（A2–A4、A11、A15–A19）”。图中不画 Actor 为系统类，不画 `SourceRecord`、`WorkVersion`、`Scholar`、统计结果对象或 DTO 的消息；A5–A10、A12–A14、A16–A19 以守卫消息与图例表达。
