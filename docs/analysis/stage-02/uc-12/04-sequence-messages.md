# UC-12 分析序列与逐消息职责

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-SEQ-20-institution-rank.puml`](diagrams/DGM-SEQ-20-institution-rank.puml)
**导出：** [SVG](diagrams/generated/DGM-SEQ-20-institution-rank.svg) · [PNG](diagrams/generated/DGM-SEQ-20-institution-rank.png)
**方法：** 教材 §5.4.3；只表达分析对象协作，不写 Java、数据库、HTTP/API、索引、缓存、任务调度或聚合算法。

## 1. 顺序图范围

本序列实现 UC-12“查看机构排名”：主流程 E1–E14，以及 A1（条件非法）、A2（空结果）、A3（失败）、A4（数据未更新）、A5（缺数据性质标识）、A6（模拟/混合）、A7（缺稳定标识或去重冲突未决）、A8（跨源/版本重复）、A9（机构归属缺失）、A10（只有作者当前单位）、A11（机构别名合并与未映射）、A12（一成果多机构）、A13（机构类别维度未冻结）、A14（计数并列）、A15（占比/分母与样本不足）、A16（规模超限）、A17（质量/影响力/机构优劣）、A18（错误计数单位与个人维度）、A19（越界到其他视图或要求未知并入名次）分支。

参与对象（共 6 个；其中 1 个为参与者，不进入 VOPC）：

- 参与者（图左）：访客（主要参与者）
- `InstitutionRankingUI`（Boundary）
- `InstitutionRankingCoordinator`（Control）
- `WorkEntity`（Entity，只读）
- `Institution`（Entity，只读）
- `Authorship`（Entity，只读）

**不画入图的外部前置：** 机构规范名/别名与分类映射维护方（FR14/FR16）在视图可用前已产出生效的机构规范名、别名、合并链与领域分类映射；本 UC 无与之交互的消息（01 §1；B-01/B-02）。

## 2. 逐消息职责表

| 消息 | 名称 | 发送者 | 接收者 | 事件步骤 | 接收者职责 | 依据/假设 |
|---|---|---|---|---|---|---|
| M1 | 打开机构排名视图（默认现实视图）（E1） | 访客 | InstitutionRankingUI | E1 | 承接机构排名视图请求，默认进入现实视图 | FR11；BR-IMP-07；`AC-IMPL-FR11-02`；NF01；FR11-S05 触发条件 |
| M2 | 请求默认机构排名（视图范围＋默认筛选条件＋公开只读请求上下文） | InstitutionRankingUI | InstitutionRankingCoordinator | E1 | 启动排名读取，转交视图范围与默认条件 | 分析推导；FR11 参与者“访客”；FR11-S06 |
| M3 | 视图范围守卫：只纳入真实、公开、生效且合格的去重成果实体（BR-IMP-01/07；FR11） | InstitutionRankingCoordinator | InstitutionRankingCoordinator | E2 | 判定纳入范围，排除模拟/混合与未公开/未生效数据 | FR11 规范性要求；BR-IMP-01/07 |
| M4 | 只读读取排名范围内去重成果实体与共享字段（类型／领域／发生时间／数据性质／公开生效状态）（E2） | InstitutionRankingCoordinator | WorkEntity | E2 | 提供去重实体与共享字段（只读） | FR11；§7.1；INV-02；FR11-S05 输入 |
| M5 | 返回去重实体集合与共享字段（每实体一条；不含来源数／版本数／参与关系数） | WorkEntity | InstitutionRankingCoordinator | E2 | 提供计数单位集合 | BR-IMP-02/06；`AC-IMPL-FR16-02` 边界 |
| M6 | 统计资格与去重计数守卫：缺稳定标识或去重冲突未决者不计入并披露未纳入数（A7）；跨源重复与已确认版本只计一次（A8；BR-IMP-02/06）（E3） | InstitutionRankingCoordinator | InstitutionRankingCoordinator | E3 | 排除不可判定实体并保证一个实体只贡献一次计数 | BR-IMP-02/06；`AC-IMPL-FR11-01`；FR11-S07（细化） |
| M7 | 只读读取每项成果的发生时机构归属事实与证据引用（含缺失／无证据标记）（E4） | InstitutionRankingCoordinator | WorkEntity | E4 | 提供有证据的成果发生时机构归属与缺失标记（只读） | BR-IMP-06；BR-513；SR-06；§7.1；FR11-S05 输入 |
| M8 | 返回发生时机构原值／规范引用／证据引用／缺失标记 | WorkEntity | InstitutionRankingCoordinator | E4 | 提供机构归属事实与缺失标记 | 分析推导；§7.1；ID-05 |
| M9 | 只读读取参与者级署名机构原值、角色与顺序（仅作归属证据，不按作者数计数）（E4） | InstitutionRankingCoordinator | Authorship | E4 | 提供参与者级机构原值（只读） | ID-05；§7.2；BR-IMP-06 |
| M10 | 返回参与者机构原值与关系状态（只读，不改写） | Authorship | InstitutionRankingCoordinator | E4 | 提供多机构原值与关系状态 | 分析推导；ID-05；§6.4 |
| M11 | 机构归属守卫：只采用有证据的成果发生时机构；缺失或无证据→机构未知；不以作者当前任职单位推断（A9/A10；BR-IMP-06；BR-513；SR-06）（E5） | InstitutionRankingCoordinator | InstitutionRankingCoordinator | E5 | 判定每项成果的机构归属集合，未知单列 | BR-IMP-06；BR-513；SR-06；SPEC T35 |
| M12 | 只读读取机构规范名、别名、合并链与未映射标记（E6） | InstitutionRankingCoordinator | Institution | E6 | 提供规范名、别名、合并链与未映射标记（只读） | FR11-S08 E2；CL-05；FR11-S05 前置 2 |
| M13 | 返回规范机构标识／规范名／未映射标记 | Institution | InstitutionRankingCoordinator | E6 | 提供归并结果与未映射标记 | FR11-S08 E2；CL-05；B-01 |
| M14 | 机构归并守卫：按已生效规范名／别名把同一机构归并为一行；未映射保留原值并披露（A11；未映射是否进入名次待确认） | InstitutionRankingCoordinator | InstitutionRankingCoordinator | E6 | 归并同一机构的多种写法，保留未映射披露 | FR11-S08 E2；CL-05；B-01 |
| M15 | 机构维度计数：同一成果同一规范机构计一次、同一成果涉及不同机构各计一次；机构未知单列不进名次（A12；BR-IMP-06；AC-IMPL-FR11-01）（E7） | InstitutionRankingCoordinator | InstitutionRankingCoordinator | E7 | 按规范机构计数并剔除未知的名次资格 | BR-IMP-06；`AC-IMPL-FR11-01`；FR11-S08 |
| M16 | 名次生成：计数降序；计数相同者名次相同、下一名次按并列数跳号；并列内次序不得按质量或名气（A14；并列规则待确认）（E8） | InstitutionRankingCoordinator | InstitutionRankingCoordinator | E8 | 排序、并列与名次跳号 | FR11-S05 步骤 4/A1（细化）；BR-522；B-03 |
| M17 | 覆盖率与占比：按声明分母口径给出覆盖率，并对低于约定阈值的机构标注“样本不足”（A15；分母与阈值待确认）（E9） | InstitutionRankingCoordinator | InstitutionRankingCoordinator | E9 | 计算覆盖率/占比并标注样本不足 | FR11-S05 步骤 3（细化）；FR11-S10；BR-520；B-04/B-05 |
| M18 | 生成口径声明：筛选条件／时间范围／计数单位（去重成果实体）／机构归属规则／别名归并规则／机构未知处理／并列规则／分母与覆盖率／数据性质／更新时间（E10） | InstitutionRankingCoordinator | InstitutionRankingCoordinator | E10 | 组装与结果一致的口径声明 | FR11 规范性要求；BR-514；FR11-S01；B-07 |
| M19 | 返回排名表、机构未知分组、未映射披露、口径声明与数据性质标识 | InstitutionRankingCoordinator | InstitutionRankingUI | E11 | 返回结果、声明与数据性质标识 | FR11；`AC-IMPL-FR11-02`；NF06 |
| M20 | 同屏展示排名与声明，并固定说明“按计数排序，不表示机构质量、影响力或优劣”（E11；BR-522） | InstitutionRankingUI | 访客 | E11 | 显示排名、机构未知分组与声明，附非质量评价说明 | FR11；BR-514/522 |
| M21 | 调整筛选条件（成果类型／研究领域／时间窗／数据性质＝现实或演示）（E12） | 访客 | InstitutionRankingUI | E12 | 接收条件变更 | FR11-S06；FR11-S12 |
| M22 | 提交条件变更 | InstitutionRankingUI | InstitutionRankingCoordinator | E12 | 转交条件变更 | 分析推导 |
| M23 | 条件校验守卫：时间两端含、取值在允许集合内；非法→拒绝并保留原结果与可编辑条件（A1；NF01） | InstitutionRankingCoordinator | InstitutionRankingCoordinator | E12/A1 | 校验条件合法性，非法不清空页面 | FR11-S06 E1/E3；NF01 |
| M24 | 数据性质守卫：默认只纳入真实数据；缺标识归“数据性质未知”并披露；模拟与混合只在显式演示模式展示并固定标识“不得用于描述真实世界”（A5/A6；BR-IMP-07；AC-IMPL-FR11-02） | InstitutionRankingCoordinator | InstitutionRankingCoordinator | E13/A5/A6 | 按数据性质过滤并决定是否进入演示模式 | BR-IMP-07；`AC-IMPL-FR11-02`；FR11-S12 |
| M25 | 返回按新条件重算的排名、刷新后的声明与数据性质标识 | InstitutionRankingCoordinator | InstitutionRankingUI | E12/E13 | 返回重算结果与刷新后的声明 | FR11-S06 A1；FR11-S01 A1（细化） |
| M26 | 显示重算结果与刷新后的声明（E13） | InstitutionRankingUI | 访客 | E13 | 显示结果、声明与模拟/混合标识 | `AC-IMPL-FR11-02`；NF06 |
| M27 | 请求解释某一机构行的计数口径（E14） | 访客 | InstitutionRankingUI | E14 | 接收口径解释请求 | FR11-S05 步骤 4 |
| M28 | 请求该机构行的统计条件与口径回显 | InstitutionRankingUI | InstitutionRankingCoordinator | E14 | 取回该行的条件与口径 | 分析推导；FR11-S05 步骤 5 |
| M29 | 返回同一筛选条件、机构规范标识与归属／分母口径（不含个人敏感数据） | InstitutionRankingCoordinator | InstitutionRankingUI | E14 | 返回条件、机构标识与口径，不复制检索逻辑 | FR11-S11（细化）；BR-IMP-11 |
| M30 | 显示口径说明与“不表示机构优劣”的固定声明 | InstitutionRankingUI | 访客 | E14 | 显示口径说明与固定声明 | BR-522；FR11-S05 输出 |
| M31 | 点击机构行下钻复核（E14） | 访客 | InstitutionRankingUI | E14 | 接收下钻请求 | FR11-S05 步骤 5 |
| M32 | 请求下钻所需的筛选条件与机构标识（不请求排名重算） | InstitutionRankingUI | InstitutionRankingCoordinator | E14 | 取回条件与机构标识 | 分析推导；FR11-S11 |
| M33 | 返回同一筛选条件与机构规范标识（检索执行属 UC-01） | InstitutionRankingCoordinator | InstitutionRankingUI | E14 | 返回条件与标识，不执行检索 | FR11-S05 步骤 5；FR01 复用 |
| M34 | 显示带条件回显的检索入口 | InstitutionRankingUI | 访客 | E14 | 显示带条件的复核入口 | FR11-S11 |
| M35 | 命中 0 条→提示“当前条件下没有可排名的机构”，保留条件（A2；NF01） | InstitutionRankingCoordinator | InstitutionRankingCoordinator | A2 | 判定空结果，与失败区分 | FR11-S10 E1；NF01 |
| M36 | 返回空结果提示（与失败可区分） | InstitutionRankingCoordinator | InstitutionRankingUI | A2 | 返回空结果分支 | `AC-IMPL-NF01-01` 同类 |
| M37 | 排名读取或计算不可用→明确失败；不展示部分结果、不以过期结果冒充最新、不编造更新时间（A3；NF01） | InstitutionRankingCoordinator | InstitutionRankingCoordinator | A3 | 判定失败分支，禁止部分结果 | FR11-S09 E1/E3；BR-523；NF01 |
| M38 | 返回失败提示（与空结果可区分） | InstitutionRankingCoordinator | InstitutionRankingUI | A3 | 返回失败分支 | NF01 |
| M39 | 更新时间与生效数据版本核对：落后时标注“数据更新至…／结果可能未反映最新变更”（A4；NF05） | InstitutionRankingCoordinator | InstitutionRankingCoordinator | A4 | 核对同一生效版本并决定是否标注未更新 | NF05；`AC-IMPL-NF05-01/02`；BR-523；B-07 |
| M40 | 返回更新时间、未更新提示与未纳入／未映射披露数 | InstitutionRankingCoordinator | InstitutionRankingUI | A4 | 返回时间信息与披露计数 | FR11；BR-514；FR11-S07 |
| M41 | 机构类别维度守卫：机构类别分类体系未冻结→不提供按类别分组或筛选，并在声明中说明（A13；待确认） | InstitutionRankingCoordinator | InstitutionRankingCoordinator | A13 | 判定类别维度可用性，不构造类别体系 | FR11-S05；Q-SPEC-03；B-02 |
| M42 | 返回类别维度不可用说明 | InstitutionRankingCoordinator | InstitutionRankingUI | A13 | 返回类别维度不可用分支 | B-02 |
| M43 | 展示规模守卫：超过单次展示上限时给出当前规模并引导收窄；子集标注“部分结果（已声明子集）”（A16；上限待确认） | InstitutionRankingCoordinator | InstitutionRankingCoordinator | A16 | 判定规模并限制展示 | FR11-S10 E2；BR-528；B-06 |
| M44 | 返回规模提示或已声明子集标注 | InstitutionRankingCoordinator | InstitutionRankingUI | A16 | 返回规模分支结果 | FR11-S10；BR-528 |
| M45 | 范围守卫：拒绝质量／影响力／机构优劣排名，拒绝以来源数／版本数／参与关系数／作者数当机构计数，拒绝把机构未知并入已知名次（A17/A18/A19；BR-522；BR-IMP-02/06） | InstitutionRankingCoordinator | InstitutionRankingCoordinator | A17/A18/A19 | 拒绝越界、错误单位与错误名次请求 | BR-522；SRS §2.1；BR-IMP-02/06；FR16-02 边界 |
| M46 | 返回拒绝结论与可用范围说明（分布统计属 UC-11、领域热点属 UC-13、合作网络与路径属 UC-14/15） | InstitutionRankingCoordinator | InstitutionRankingUI | A19 | 返回拒绝与交接说明 | FR11/FR13/FR12/IN01 边界；B-10 |

## 3. 事件、分支与消息对照

| 事件/分支 | 消息 | 关键守卫 | 可观察结果 |
|---|---|---|---|
| E1 打开视图 | M1–M2 | — | 进入默认现实视图 |
| E2 范围与读取 | M3–M5 | 真实∧公开∧生效∧合格 | 去重实体集合与共享字段 |
| E3/A7/A8 资格与去重 | M6 | 稳定标识可用∧去重冲突已决∧一实体一次 | 未纳入数披露、只计一次 |
| E4 机构归属证据 | M7–M10 | 只读；逐参与者原值 | 机构归属证据集合 |
| E5/A9/A10 归属守卫 | M11 | 有证据的发生时机构 | 机构归属集合＋未知标记 |
| E6/A11 归并 | M12–M14 | 已生效别名/合并映射 | 规范机构标识或未映射披露 |
| E7/A12 机构计数 | M15 | 同机构一次/跨机构各一次；未知不进名次 | 各机构计数＋未知分组 |
| E8/A14 名次 | M16 | 降序∧同计数同名次∧下一名次跳号 | 名次与并列说明 |
| E9/A15 覆盖率与占比 | M17 | 声明分母∧样本不足标注 | 覆盖率/占比＋样本不足标注 |
| E10 声明 | M18 | 声明项齐备且与结果一致 | 口径声明 |
| E11 展示 | M19–M20 | 不作质量结论 | 排名、未知分组、声明 |
| E12/A1 条件校验与重算 | M21–M25 | 时间两端含∧取值合法 | 重算结果＋刷新声明或拒绝保留原结果 |
| E13/A5/A6 数据性质 | M24–M26 | 默认仅真实；模拟/混合仅演示并标识 | 重算结果＋数据性质标识 |
| E14 口径解释与下钻 | M27–M34 | 条件与机构标识回显一致 | 口径说明＋带条件的检索入口 |
| A2 空结果 | M35–M36 | 命中 0 且非失败 | 空结果提示，条件保留 |
| A3 失败 | M37–M38 | 不可用 | 失败提示，无部分结果 |
| A4 未更新 | M39–M40 | 版本核对 | 更新时间＋可能未反映最新变更 |
| A13 类别维度 | M41–M42 | 分类体系未冻结 | 类别维度不可用说明 |
| A16 规模超限 | M43–M44 | 展示上限 | 规模提示／已声明子集 |
| A17–A19 范围与单位边界 | M45–M46 | 计数单位∧可用范围∧未知不进名次 | 拒绝与交接说明 |

## 4. 完整性与一致性核对

| 检查 | 结果 | 说明 |
|---|---|---|
| 消息编号 | M1–M46 连续、无缺号、无重复 | 脚本核对（08 §4.4） |
| 每条消息有发送者/接收者/事件/职责/依据 | 是 | 表 2 全列齐备 |
| 每个参与类至少收到一条消息 | 是 | UI：M1/M21/M27/M31；Ctl：M2/M22/M28/M32；WorkEntity：M4/M7；Institution：M12；Authorship：M9 |
| 是否按来源数/版本数/关系数/作者数计数 | 否（有反证守卫） | M5/M6/M45；BR-UC12-01 |
| 是否出现“用作者当前任职单位推断机构归属” | 否（有反证守卫） | M11；A10；BR-UC12-05 |
| 是否把机构未知计入已知名次 | 否 | M15/M45；BR-UC12-07 |
| 是否出现同一机构重复行 | 否（有归并消息） | M12–M14 |
| 是否把模拟/混合计入默认现实视图 | 否 | M3/M24；`AC-IMPL-FR11-02` |
| 是否出现质量/影响力/机构优劣/个人排名结论 | 否（有拒绝与固定声明） | M20/M30/M45；BR-522 |
| 是否出现机构类别分组/筛选 | 否（显式不提供） | M41/M42；A13 |
| 是否出现写成果/关系/机构/来源的消息 | 否 | 只有 M4/M7/M9/M12 四条只读消息 |
| 是否出现持久排名结果/快照/口径版本对象 | 否 | 05 §4；C-01 |
| 是否出现排名实现（缓存、任务、聚合、接口、DTO） | 否 | 03 §4；只有分析职责 |
| 空结果/失败/未更新/规模超限/条件非法/类别不可用是否分开 | 是 | M23、M35–M38、M39–M40、M41–M42、M43–M44 |
| 声明与结果是否始终同步 | 是（有守卫） | M18/M19、M25；BR-UC12-11 |
| 下钻是否复制检索逻辑 | 否 | M32/M33 只回显条件与机构标识；检索属 UC-01 |

## 5. 与图的一致性

`DGM-SEQ-20` 的 46 条消息与本表逐条对应；图中分组为“打开视图与排名范围判定（E1–E3）→ 成果发生时机构归属：证据、机构未知与规范名归并（E4–E6）→ 机构维度计数、并列与名次（E7–E10）→ 条件变更与数据性质：现实视图与演示模式（E12–E13）→ 口径解释与下钻复核（E14）→ 空结果、失败、版本、类别、规模与范围守卫（A2–A4、A13、A16–A19）”。图中不画 Actor 为系统类，不画 `SourceRecord`、`WorkVersion`、`Scholar`、排名结果对象、机构类别对象或 DTO 的消息；A5–A12、A14–A15、A17–A18 以守卫消息与图例表达。
