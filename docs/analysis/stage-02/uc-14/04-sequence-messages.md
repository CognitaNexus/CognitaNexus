# UC-14 分析序列与逐消息职责

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-SEQ-22-collaboration-network.puml`](diagrams/DGM-SEQ-22-collaboration-network.puml)
**导出：** [SVG](diagrams/generated/DGM-SEQ-22-collaboration-network.svg) · [PNG](diagrams/generated/DGM-SEQ-22-collaboration-network.png)
**方法：** 教材 §5.4.3；只表达分析对象协作，不写 Java、数据库、图存储、HTTP/API、索引、缓存、任务调度或图布局算法。

## 1. 顺序图范围

本序列实现 UC-14“查看四类共同成果合作网络”：主流程 E1–E20，以及 A1（条件非法）、A2（无合作关系）、A3（失败）、A4（未更新）、A5（缺数据类别标识）、A6（模拟/混合）、A7（时间未知）、A8（待核验关系）、A9（争议/已排除关系）、A10（仅同机构/同领域）、A11（同名/相似姓名）、A12（同成果多来源/已确认版本）、A13（两项不同共同成果）、A14（作者顺序/角色不同）、A15（学者合并）、A16（仅共同专利/项目/获奖）、A17（规模超限）、A18（越界到路径或筛选）、A19（缺角色证据/身份未定）、A20（导航目标不可用）分支。

参与对象（共 7 个；其中 1 个为参与者，不进入 VOPC）：

- 参与者（图左）：访客（主要参与者）
- `CollaborationNetworkUI`（Boundary）
- `CollaborationNetworkCoordinator`（Control）
- `WorkEntity`（Entity，只读）
- `Authorship`（Entity，只读）
- `Scholar`（Entity，只读）
- `CollaborationEdge`（Entity，派生、只读）

**不画入图的外部前置：** 成果治理方（FR09/UC-09）、身份与归属治理方（FR15/UC-08/UC-10）、来源与数据性质维护方（FR08/FR16/UC-18/UC-19）在本 UC 视图可用前已产出去重成果、生效参与关系与数据类别；本 UC 无与之交互的消息（01 §1）。UC-15/IN01 是下游用例，也不作为参与者画入。

## 2. 逐消息职责表

| 消息 | 名称 | 发送者 | 接收者 | 事件步骤 | 接收者职责 | 依据/假设 |
|---|---|---|---|---|---|---|
| M1 | 打开四类共同成果基础合作网络（默认现实视图；未指定时使用已声明默认时间范围）（E1） | 访客 | CollaborationNetworkUI | E1 | 承接网络视图请求，默认进入现实视图 | FR12 参与者；BR-IMP-07；NF01；FR12-S01（细化） |
| M2 | 请求基础网络（视图范围＋数据类别＝现实/演示＋可选时间范围＋公开只读请求上下文） | CollaborationNetworkUI | CollaborationNetworkCoordinator | E1 | 启动网络读取，转交视图范围与默认条件 | 分析推导；FR12；FR12-S01（细化） |
| M3 | 条件校验守卫：数据类别取值合法；时间范围若提供则两端含且起始≤结束；非法→拒绝并保留原有网络与可编辑条件（A1；NF01） | CollaborationNetworkCoordinator | 自身 | E2/A1 | 校验视图条件，非法不清空页面 | FR12-S01 E1（细化）；NF01；B-03 |
| M4 | 数据范围守卫：默认只纳入真实、公开、生效的合格成果；模拟/混合仅显式演示并固定标识；缺数据类别标识不计入真实（A5/A6；BR-IMP-01/07） | CollaborationNetworkCoordinator | 自身 | E3/A5/A6 | 判定纳入范围与数据类别模式 | BR-IMP-01/07；§7.1；B-04 |
| M5 | 只读读取建边候选成果：canonical成果标识、成果类型（论文/专利/项目/获奖）、公开生效状态、数据类别、已核验发生时间（E4） | CollaborationNetworkCoordinator | WorkEntity | E4 | 提供成果身份、资格字段与类型（只读） | FR12 明文；§7.1；INV-02；SPEC FR12-IN01 §1 |
| M6 | 返回成果字段与缺失标记（非公开/未生效/时间未知/数据类别未知） | WorkEntity | CollaborationNetworkCoordinator | E4 | 提供字段与缺失标记 | 分析推导；§7.1；SPEC §1 |
| M7 | 成果资格守卫：只有公开、生效、真实（默认）的成果进入建边候选；时间未知不因此排除，仅当声明时间范围时不命中并披露（A7；B-03）；未纳入按原因计数并披露；四类成果同等对待，不把基础网络裁成论文图（E5；A5/A7；AC-IMPL-FR12-01/05） | CollaborationNetworkCoordinator | 自身 | E5/A5/A7 | 排除不合格成果并分类披露 | FR12 明文；BR-IMP-01；AC-IMPL-FR12-01/05 |
| M8 | 只读读取该成果的参与关系：角色种类（论文作者/专利发明人/项目参与者/获奖个人成员）、参与者引用、角色顺序（适用时）、关系状态、公开性与角色证据引用（E6） | CollaborationNetworkCoordinator | Authorship | E6 | 提供参与角色、顺序、状态与证据引用（只读） | FR12 明文；FR15；§6.4；§11.3；SPEC §1 |
| M9 | 返回参与关系字段、关系状态与缺失标记（缺角色证据／参与者身份未定） | Authorship | CollaborationNetworkCoordinator | E6 | 提供关系字段与缺失标记 | 分析推导；FR15；SPEC §1 |
| M10 | 参与关系资格守卫：四类角色按各自证据核验；只有身份归属明确、可公开、状态为本人确认或有独立核验证据的已批准自动关联才参与建边；待核验/争议/已排除不参与强度与证据；缺角色证据不建边（E7；A8/A9/A19；BR-IMP-01/08；§6.4；§11.3） | CollaborationNetworkCoordinator | 自身 | E7/A8/A9/A19 | 判定每条关系能否参与建边 | FR12 明文；AC-IMPL-FR12-04；§6.4；§11.3；B-06 |
| M11 | 只读读取参与者学者身份：canonical_id、公开状态、合并重定向与失效标记（E8） | CollaborationNetworkCoordinator | Scholar | E8 | 提供 canonical 身份与公开/合并状态（只读） | FR12 明文；SPEC §2；BR-IMP-03 |
| M12 | 返回 canonical_id、公开状态与合并/失效标记 | Scholar | CollaborationNetworkCoordinator | E8 | 提供身份标识与状态 | 分析推导；SPEC §2；BR-530 |
| M13 | 节点资格守卫：只以公开学者为节点；合并先解析到当前 canonical_id，环或失效目标按异常处理、不随意选节点；不按同名/相似姓名/同机构/同领域合并或建边；未公开学者不成节点，相关边一并消失（E9；A10/A11/A15；BR-IMP-03；FR12 明文） | CollaborationNetworkCoordinator | 自身 | E9/A10/A11/A15 | 确定节点集合与身份解析结果 | FR12 明文；AC-IMPL-FR12-04；BR-IMP-03；SPEC §1/§2；BR-530 |
| M14 | 建边守卫：对每项合格成果，从合格参与关系取 canonical_id 集合去重，枚举不同学者的无序对，以 min/max canonical_id 作为唯一边键；自环不生成（E10；BR-IMP-08） | CollaborationNetworkCoordinator | 自身 | E10 | 枚举学者无序对并生成唯一边键 | FR12 明文；BR-IMP-08；SPEC §2 |
| M15 | 把该成果的 canonical 成果ID加入该学者对的共同成果证据集合 | CollaborationNetworkCoordinator | CollaborationEdge | E10 | 维护该边的证据集合与边身份 | FR12 明文；BR-IMP-08 |
| M16 | 确认边身份、两端 canonical_id 与去重后的证据集合规模（强度） | CollaborationEdge | CollaborationNetworkCoordinator | E10 | 提供边身份、两端与强度 | BR-IMP-08；AC-IMPL-FR12-03；SPEC §2 |
| M17 | 强度守卫：每项合格成果对同一学者对只贡献 1；同成果多来源、重复角色、已确认版本不重复加权；跨来源不重复累计；作者顺序不同不产生新边（E11；A12/A13/A14；AC-IMPL-FR12-01/02/03） | CollaborationNetworkCoordinator | 自身 | E11/A12/A13/A14 | 裁决强度去重与无向唯一 | AC-IMPL-FR12-01/02/03；BR-IMP-08；BR-IMP-02；`AC-IMPL-FR16-02` 边界 |
| M18 | 证据守卫：每条边至少一项合格共同成果证据；强度必须等于证据清单条数，不一致按缺陷处理；证据只含公开字段，待核验/争议明细不进入（E12；AC-IMPL-FR12-01/02/03；BR-IMP-01/11） | CollaborationNetworkCoordinator | 自身 | E12 | 校验可追溯性与证据一致性 | BR-IMP-08；FR12-S02/S03（细化）；BR-IMP-01/11 |
| M19 | 披露生成：数据范围（真实/演示）、时间范围、关系定义（共享至少一项合格共同成果且两端参与关系均合格）、强度含义（共同成果实体数）、被排除关系状态与未纳入计数、节点/边规模、更新时间（E13；AC-IMPL-FR12-01/02） | CollaborationNetworkCoordinator | 自身 | E13 | 组装与结果一致的关系定义与披露 | AC-IMPL-FR12-01/02 语境；BR-IMP-07；FR12-S01/S03/S05（细化） |
| M20 | 规模守卫：节点/边超过已声明展示上限→提示当前规模并引导收窄，不展示未声明子集冒充完整网络；上限未冻结前只声明实际规模（A17；模块 FR12-S06 细化；D-08 待确认） | CollaborationNetworkCoordinator | 自身 | E13/A17 | 判定展示规模与子集声明 | FR12-S06（细化）；BR-528（细化）；NF01；B-02 |
| M21 | 返回网络（节点、边、强度）＋披露块＋数据类别标识＋规模信息＋状态（已形成／无合作关系／失败／未更新／规模超限／拒绝） | CollaborationNetworkCoordinator | CollaborationNetworkUI | E14 | 返回网络、披露与状态标识 | FR12；AC-IMPL-FR12-01/02/03；NF01 |
| M22 | 显示网络、关系定义、强度含义、被排除关系状态说明与固定声明“关系强度不代表研究质量、影响力或学术水平”；不含个人敏感字段与待核验明细（E14；BR-IMP-11；NF06） | CollaborationNetworkUI | 访客 | E14 | 显示网络、披露与固定声明 | FR12-S03/S05（细化）；BR-522（细化）；BR-IMP-11；NF06 |
| M23 | 选中一条合作关系，请求其成果依据清单（E15） | 访客 | CollaborationNetworkUI | E15 | 接收证据清单请求 | FR12-S02（细化） |
| M24 | 请求该边的成果依据清单与条数 | CollaborationNetworkUI | CollaborationNetworkCoordinator | E15 | 取回该边证据清单与条数 | 分析推导；FR12-S02/S03（细化） |
| M25 | 只读读取该边的证据成果引用与强度 | CollaborationNetworkCoordinator | CollaborationEdge | E15 | 提供证据成果引用与强度（只读） | BR-IMP-08；FR12-S02（细化） |
| M26 | 返回证据成果引用（按成果 canonical_id 升序）与强度 | CollaborationEdge | CollaborationNetworkCoordinator | E15 | 提供证据引用与强度 | SPEC §2；FR12-S03（细化） |
| M27 | 只读读取证据成果的公开字段（标题、类型、发生时间、来源与归属状态）（E15） | CollaborationNetworkCoordinator | WorkEntity | E15 | 提供证据成果公开字段（只读） | FR12-S02（细化）；§7.1；BR-IMP-01 |
| M28 | 返回公开字段与缺失标记（缺失标未知，不隐藏该项） | WorkEntity | CollaborationNetworkCoordinator | E15 | 提供公开字段与缺失标记 | FR12-S02 AC（细化）；§7.1 |
| M29 | 返回成果依据清单（条数＝强度）、缺失项标注与成果详情入口 | CollaborationNetworkCoordinator | CollaborationNetworkUI | E15 | 返回清单、条数与详情入口 | FR12-S02/S03（细化）；BR-IMP-08 |
| M30 | 显示成果依据清单与条数，可点击进入成果详情（UC-02） | CollaborationNetworkUI | 访客 | E15 | 显示清单与详情入口 | FR12-S02/S09（细化）；UC-02 边界 |
| M31 | 选中一个学者节点，请求其关系强度与含义说明（E16） | 访客 | CollaborationNetworkUI | E16 | 接收节点强度请求 | FR12-S03（细化） |
| M32 | 请求该节点的相关边与强度 | CollaborationNetworkUI | CollaborationNetworkCoordinator | E16 | 取回节点相关边与强度 | 分析推导；FR12-S03（细化） |
| M33 | 只读读取该节点的相关边、强度与证据数 | CollaborationNetworkCoordinator | CollaborationEdge | E16 | 提供相关边与强度（只读） | BR-IMP-08；AC-IMPL-FR12-03 |
| M34 | 返回相关边、强度与证据数 | CollaborationEdge | CollaborationNetworkCoordinator | E16 | 提供相关边与强度 | 分析推导；BR-IMP-08 |
| M35 | 返回节点相关边强度、强度含义说明与“不代表质量”声明 | CollaborationNetworkCoordinator | CollaborationNetworkUI | E16 | 返回强度与含义说明 | FR12-S03（细化）；BR-522（细化） |
| M36 | 显示节点强度与含义说明 | CollaborationNetworkUI | 访客 | E16 | 显示强度与含义说明 | 分析推导；FR12-S03（细化） |
| M37 | 点击学者节点或证据成果，请求导航（E17） | 访客 | CollaborationNetworkUI | E17 | 接收导航请求 | FR12-S09（细化） |
| M38 | 请求目标对象的当前身份与可访问性 | CollaborationNetworkUI | CollaborationNetworkCoordinator | E17 | 判定导航目标可用性 | 分析推导；FR12-S09（细化）；BR-530 |
| M39 | 只读确认目标学者当前 canonical 身份、公开状态与合并指向 | CollaborationNetworkCoordinator | Scholar | E17 | 提供当前身份与合并指向（只读） | SPEC §2；BR-530；FR12-S09（细化） |
| M40 | 返回当前身份、公开状态与合并指向 | Scholar | CollaborationNetworkCoordinator | E17 | 提供身份与状态 | 分析推导；BR-IMP-03 |
| M41 | 返回导航结论（学者门户 UC-03／成果详情 UC-02／明确不可用原因类别） | CollaborationNetworkCoordinator | CollaborationNetworkUI | E17 | 返回导航结论与原因类别 | FR12-S09（细化）；BR-IMP-01 |
| M42 | 跳转或给出明确提示，不出现空白页或静默失败 | CollaborationNetworkUI | 访客 | E17/A20 | 执行跳转或显示明确提示 | FR12-S09 E1/E2（细化） |
| M43 | 切换数据类别（现实/演示）或调整时间范围（E18） | 访客 | CollaborationNetworkUI | E18 | 接收范围变更 | BR-IMP-07；FR12-S01 A1（细化） |
| M44 | 提交范围变更 | CollaborationNetworkUI | CollaborationNetworkCoordinator | E18 | 转交范围变更 | 分析推导 |
| M45 | 重建守卫：按新范围重建网络并同步刷新披露与数据类别标识，不保留与结果不符的旧披露（E18；BR-IMP-07） | CollaborationNetworkCoordinator | 自身 | E18 | 重建并同步刷新披露与标识 | BR-IMP-07；FR12-S01 A1/S08（细化） |
| M46 | 生效关系版本核对：与检索/门户/统计使用同一生效关系版本；落后时标注“结果可能未反映最新变更”，不展示待审核关系（E19/A4；NF05；BR-IMP-01/10） | CollaborationNetworkCoordinator | 自身 | E19/A4 | 核对版本并决定是否标注未更新 | NF05；`AC-IMPL-NF05-01/02`；BR-IMP-10；FR12-S08（细化） |
| M47 | 返回重建结果、刷新后的披露、更新时间与未更新提示 | CollaborationNetworkCoordinator | CollaborationNetworkUI | E18/E19 | 返回重建结果与时间信息 | 分析推导；NF05 |
| M48 | 显示重建结果、数据集标识与更新时间/未更新提示 | CollaborationNetworkUI | 访客 | E18/E19 | 显示结果、标识与时间提示 | BR-IMP-07；NF05 |
| M49 | 空结果与失败守卫：范围内没有合格合作关系→明确“当前数据范围内没有可展示的合作关系”并展示数据范围；读取或重建不可用→明确失败；两类不互相冒充，也不展示半成品（A2/A3；NF01） | CollaborationNetworkCoordinator | 自身 | A2/A3 | 区分无合作关系与失败 | FR12-S01 E1/E2（细化）；NF01 |
| M50 | 返回无合作关系或失败提示（可区分） | CollaborationNetworkCoordinator | CollaborationNetworkUI | A2/A3 | 返回空/失败分支 | NF01；`AC-IMPL-NF01-01` 同类 |
| M51 | 边界守卫：拒绝最短路径查询、按机构/领域筛选网络、个人综合排名与质量/影响力评价；只给可用范围与交接（路径与筛选属 UC-15/IN01，排名属 UC-11/UC-12）（E20；A18） | CollaborationNetworkCoordinator | 自身 | E20/A18 | 拒绝越界请求并给交接 | IN01-R1/R3；BR-IMP-09；AC-IMPL-FR12-05；SRS §2.1 |
| M52 | 返回拒绝结论与可用范围说明 | CollaborationNetworkCoordinator | CollaborationNetworkUI | E20/A18 | 返回拒绝与交接说明 | FR12/IN01 边界；02 §5 |
| M53 | 显示拒绝与交接说明 | CollaborationNetworkUI | 访客 | E20/A18 | 显示拒绝与可用范围 | 分析推导；02 §5 |

> `M49`–`M53` 的守卫分支复用 `M1`/`M43` 的触发路径；不另设 Actor 消息，避免把同一交互画成两次重复。

## 3. 事件、分支与消息对照

| 事件/分支 | 消息 | 关键守卫 | 可观察结果 |
|---|---|---|---|
| E1 打开视图 | M1–M2 | — | 进入默认现实视图＋默认时间范围 |
| E2/A1 条件校验 | M3 | 数据类别合法∧时间范围合法 | 条件有效或拒绝并保留原网络 |
| E3/A5/A6 数据范围 | M4 | 公开∧生效∧真实；缺标识单列；模拟/混合仅演示 | 纳入范围与数据类别模式 |
| E4 读取成果 | M5–M6 | 只读；带缺失标记 | 成果身份、资格字段与类型 |
| E5/A5/A7 成果资格 | M7 | 四类成果∧公开生效∧真实（时间未知不排除） | 建边候选成果集合与未纳入分类 |
| E6 读取参与关系 | M8–M9 | 只读；四类角色与状态可见 | 参与角色、顺序、状态与证据引用 |
| E7/A8/A9/A19 参与关系资格 | M10 | 角色证据∧身份明确∧可公开∧状态合格 | 每条关系的参与裁决 |
| E8 读取身份 | M11–M12 | 只读；canonical 身份与合并状态 | 两端学者身份 |
| E9/A10/A11/A15 节点资格 | M13 | 公开∧canonical∧合并解析∧无同名/机构/领域推断 | 节点集合与身份解析结果 |
| E10 建边 | M14–M16 | 无序对∧min/max 边键∧无自环 | 边身份与证据集合 |
| E11/A12/A13/A14 强度 | M17 | 每成果每对 1∧来源/版本/角色不放大∧无向唯一 | 强度值 |
| E12 证据 | M18 | ≥1 证据∧强度＝清单条数∧只含公开字段 | 证据一致性与可追溯性 |
| E13/A17 披露与规模 | M19–M20 | 披露项齐备∧上限判定 | 披露块与规模信息 |
| E14 展示 | M21–M22 | 不作质量结论∧无敏感字段 | 网络、关系定义、固定声明 |
| E15 边检视 | M23–M30 | 条数＝强度∧缺失标未知∧可进入详情 | 成果依据清单 |
| E16 节点检视 | M31–M36 | 强度含义可解释∧不代表质量 | 相关边强度与含义说明 |
| E17/A20 导航 | M37–M42 | 目标公开可访问∧合并解析 | 门户/详情跳转或明确提示 |
| E18 范围变更 | M43–M45、M47–M48 | 新范围合法∧披露同步刷新 | 重建结果＋刷新披露＋数据集标识 |
| E19/A4 版本核对 | M46–M48 | 同一生效关系版本∧更新时间语义 | 更新时间或未更新标注 |
| A2/A3 空与失败 | M49–M50 | 无合作关系≠失败 | 可区分提示，无半成品 |
| E20/A18 边界 | M51–M53 | 能力范围∧交接说明 | 拒绝与可用范围 |

## 4. 完整性与一致性核对

| 检查 | 结果 | 说明 |
|---|---|---|
| 消息编号 | M1–M53 连续、无缺号、无重复 | 脚本核对（08 §4.4） |
| 每条消息有发送者/接收者/事件/职责/依据 | 是 | 表 2 全列齐全 |
| 每个参与类至少收到一条消息 | 是 | UI：M1/M23/M31/M37/M43；Ctl：M2/M24/M32/M38/M44；WorkEntity：M5/M27；Authorship：M8；Scholar：M11/M39；CollaborationEdge：M15/M25/M33 |
| 是否按来源数/版本数/重复角色放大强度 | 否（有反证守卫） | M17；A12；BR-UC14-03 |
| 是否用同机构/同领域/相似姓名建边 | 否（显式排除） | M7/M10/M13；A10/A11；BR-UC14-01 |
| 基础网络是否被裁成论文图 | 否（四类同等） | M7；A16；BR-UC14-15 |
| 待核验/争议/已排除是否产生强度或证据 | 否（守卫排除） | M10；A8/A9；BR-UC14-07 |
| 是否出现最短路径、BFS、路径证据对象 | 否（拒绝并交接） | M51；A18；BR-UC14-16 |
| 是否出现按机构/领域筛选网络的消息 | 否（属 UC-15） | M51；07 §5 C-03 |
| 是否出现质量/影响力/个人排名 | 否（有拒绝与固定声明） | M22/M35/M51；BR-UC14-10 |
| 强度是否等于证据清单条数 | 是（有守卫） | M18/M25–M30；BR-UC14-05 |
| 空/失败/未更新/超限/条件非法是否分开 | 是 | M3、M20、M46–M50 |
| 披露与结果是否同步 | 是（有守卫） | M19–M22、M45–M48 |
| 是否出现持久网络结果/图快照/图版本对象 | 否 | 05 §4；C-01 |
| 是否出现写成果/参与关系/身份/合并的消息 | 否 | 只有 M5/M8/M11/M27/M39 五条只读消息 |
| 是否出现实现技术（图库、索引、缓存、任务、接口、DTO） | 否 | 03 §4；只有分析职责 |

## 5. 与图的一致性

`DGM-SEQ-22` 的 53 条消息与本表逐条对应；图中分组为“视图与范围（E1–E3）→ 成果读取与成果资格（E4–E5）→ 参与关系与角色资格（E6–E7）→ 节点身份（E8–E9）→ 建边、强度与证据（E10–E12）→ 披露与规模（E13–E14）→ 边与节点检视（E15–E16）→ 节点与成果导航（E17）→ 范围变更与一致性（E18–E19）→ 状态与边界（A2/A3/E20）”。图中不画 Actor 为系统类，不画 `Institution`、`Field`、`UserProfile`、`SourceRecord`、`WorkVersion`、`ShortestPath`、`NetworkResult`、质量评分或 DTO 的消息；A5–A19 以守卫消息与图例表达。

## 6. 与 UC-15 的消息边界

UC-15（IN01 筛选与最短路径）将以本 UC 的基础网络为输入，再取**论文子图**做按领域/机构筛选与 BFS 最短路径。两者的消息边界是：

- 本 UC 只产出“节点、无向边、强度、证据、关系定义与披露”；**不产出**“路径、跳数、图版本、论文子图”。
- 本 UC 的边**必须保留成果类型**（A16），否则 UC-15 无法区分论文边与非论文边；这是 `AC-IMPL-FR12-01/05` 在本 UC 的最小可观察要求。
- UC-15 的图版本、筛选口径与路径证据由其自身分析包定义；本 UC 只在披露中声明“该边不作为论文路径证据”，不代为实现路径规则（M51/M52）。
