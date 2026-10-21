# UC-15 分析序列与逐消息职责

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-SEQ-23-shortest-path.puml`](diagrams/DGM-SEQ-23-shortest-path.puml)
**导出：** [SVG](diagrams/generated/DGM-SEQ-23-shortest-path.svg) · [PNG](diagrams/generated/DGM-SEQ-23-shortest-path.png)
**方法：** 教材 §5.4.3；只表达分析对象协作，不写 Java、数据库、图存储、HTTP/API、索引、缓存、任务调度或 BFS 实现。

## 1. 顺序图范围

本序列实现 UC-15“筛选合作网络及查询论文最短路径”：主流程 E1–E20，以及 A1（条件非法／未给端点）、A2（筛选后无合格论文边）、A3–A5（端点不存在／未公开／已合并）、A6（端点相同）、A7（无路径）、A8/A9/A10（非论文成果、仅同机构同领域、某跳只有非论文证据）、A11（等长路径）、A12（图版本失效/落后）、A13（超规模）、A14（机构/领域未知）、A15（合并后重定）、A16/A17（多来源、k 口径）、A18（失败）、A19（超时）、A20（越界请求）分支。

参与对象（共 8 个；其中 1 个为参与者，不进入 VOPC）：

- 参与者（图左）：访客（主要参与者）
- `CollaborationNetworkUI`（Boundary；沿用上游网络视图入口）
- `CollaborationPathCoordinator`（Control）
- `Scholar`（Entity，只读；端点与节点身份）
- `WorkEntity`（Entity，只读；论文资格、筛选口径与证据公开字段）
- `Authorship`（Entity，只读；论文作者关系、状态与发生时机构证据）
- `CollaborationEdge`（Entity，派生；论文子图投影）
- `PathResult`（Entity，读时投影）

**不画入图的外部前置：** 身份与归属治理方（FR15/UC-08/UC-10）、成果治理与来源方（FR09/FR08/FR16/UC-09/UC-18/UC-19）、机构/领域分类维护方（FR14/FR16 引用项）在本 UC 可用前已产出 canonical 身份、去重公开论文、数据类别与分类映射；本 UC 无与之交互的消息（01 §1）。UC-14 是**上游用例**，其基础网络结果通过 `CollaborationEdge` 的论文投影被本 UC 读取，不另画参与者。

## 2. 逐消息职责表

| 消息 | 名称 | 发送者 | 接收者 | 事件步骤 | 接收者职责 | 依据/假设 |
|---|---|---|---|---|---|---|
| M1 | 打开“按领域/机构筛选”或“查询两名学者最短路径”（IN01 探索入口，可只做其一）（E1） | 访客 | CollaborationNetworkUI | E1 | 承接探索请求，区分筛选与路径两类触发 | IN01-R1；`DGM-USECASE-02` 的 `INCL_Filter`/`INCL_Path` 有触发 `extend`；IN01-S01/S03 细化 |
| M2 | 提交探索请求（筛选条件＝领域/机构；可选起点/终点；沿用当前披露的数据图范围） | CollaborationNetworkUI | CollaborationPathCoordinator | E1 | 转交条件与端点，启动筛选或路径查询 | 分析推导；IN01-R1；UC-14 披露 |
| M3 | 条件校验守卫：筛选条件取值合法且维度语义已声明；路径请求须给出已消歧的两名端点；非法→拒绝并保留原条件与已有结果（E2；A1） | CollaborationPathCoordinator | 自身 | E2/A1 | 校验条件，非法不清空页面 | IN01-R1；NF01；IN01-S03/S06 细化；B-01/B-12 |
| M4 | 图版本固定守卫：本次查询固定同一图版本；版本不可用或已失效→明确反馈并要求重新查询，不混用两个版本（E3；A12） | CollaborationPathCoordinator | 自身 | E3/A12 | 固定并记录图版本 | `AC-IMPL-IN01-02`；SPEC §2；NF05；B-07/B-08 |
| M5 | 数据图范围守卫：只纳入真实、公开、生效的论文＋合格论文作者关系；范围外视为不存在、不推断；模拟/混合不进真实图（E4） | CollaborationPathCoordinator | 自身 | E4 | 确定论文子图的数据范围与不可推断规则 | IN01-R1；BR-IMP-01/07；BR-524 细化；`AC-IMPL-FR12-01/05`；SPEC §1 |
| M6 | 只读读取论文范围字段：canonical 论文标识、成果类型、公开生效状态、数据类别、已核验发生时间、发生时适用领域分类与映射版本（E5） | CollaborationPathCoordinator | WorkEntity | E5 | 提供论文身份、资格字段与筛选口径字段（只读） | SPEC §1/§3；§7.1；BR-IMP-07；BR-513 细化；INV-02 |
| M7 | 返回论文字段与缺失标记（时间未知/领域未分类/数据类别未知） | WorkEntity | CollaborationPathCoordinator | E5 | 提供字段与缺失标记 | 分析推导；§7.1；SPEC §1 |
| M8 | 论文资格守卫：仅论文类型且公开、生效、真实者进入子图；时间未知不因时间被排除，仅在声明时间范围时不命中并披露（E6；A8/A14） | CollaborationPathCoordinator | 自身 | E6/A8/A14 | 判定每篇成果是否进入论文子图 | IN01-R1；`AC-IMPL-FR12-05`；SPEC §1/§4；B-04 |
| M9 | 只读读取论文参与关系：角色（论文合法作者）、参与者引用、关系状态、公开性、角色证据引用、成果发生时机构证据（E7） | CollaborationPathCoordinator | Authorship | E7 | 提供角色、状态、证据引用与发生时机构证据（只读） | FR15；§6.4；§11.3；SPEC §1/§3 |
| M10 | 返回关系字段、状态与缺失标记（缺角色证据/参与者身份未定/发生时机构未知） | Authorship | CollaborationPathCoordinator | E7 | 提供关系字段与缺失标记 | 分析推导；FR15；SPEC §1 |
| M11 | 参与关系资格守卫：仅身份明确、可公开、本人确认或具独立核验证据的已批准自动关联进入子图；待核验/争议/已排除不参与、不作证据；非论文角色不进入本子图（E8） | CollaborationPathCoordinator | 自身 | E8/A9/A10 | 判定每条关系能否进入论文子图 | IN01-R1；`AC-IMPL-FR12-04`；`AC-IMPL-FR15-04`；BR-519 细化；SPEC §1；B-05 |
| M12 | 只读读取作者学者身份：canonical_id、公开状态、合并重定向与失效标记（E9） | CollaborationPathCoordinator | Scholar | E9 | 提供 canonical 身份与公开/合并状态（只读） | IN01-R1；SPEC §2；BR-IMP-03；BR-530 |
| M13 | 返回 canonical_id、公开状态与合并/失效标记 | Scholar | CollaborationPathCoordinator | E9 | 提供身份标识与状态 | 分析推导；SPEC §2 |
| M14 | 节点与端点资格守卫：只以公开学者为节点；合并解析到当前 canonical_id，环/失效按异常；不按同名/相似姓名/同机构/同领域合并；端点须已消歧（E10；A3–A6/A15） | CollaborationPathCoordinator | 自身 | E10/A3–A6/A15 | 确定节点集合与端点身份解析结果 | IN01-R1；SPEC §2；BR-IMP-03；BR-530；IN01-S07 细化 |
| M15 | 建边守卫：每篇合格论文取合格作者 canonical_id 去重，枚举无序对，以 min/max canonical_id 为边键，自环不生成；专利/项目/获奖证据边不进入子图（E11；A8） | CollaborationPathCoordinator | 自身 | E11/A8 | 枚举学者无序对并生成论文边键 | IN01-R1；`AC-IMPL-FR12-05`；BR-IMP-08/09；SPEC §2 |
| M16 | 把论文 canonical ID 加入该学者对的论文证据集合（论文子图投影） | CollaborationPathCoordinator | CollaborationEdge | E11 | 维护论文边的证据集合与边身份 | BR-518 细化；SPEC §2/§3 |
| M17 | 确认边身份、两端 canonical_id 与去重论文证据数 k | CollaborationEdge | CollaborationPathCoordinator | E11 | 提供边身份、两端与 k | BR-518 细化；`AC-IMPL-FR12-02`；SPEC §3 |
| M18 | 证据守卫：每条边至少一篇合格共同论文；k＝去重论文数；同论文多来源与已确认版本不放大（A10/A16/A17） | CollaborationPathCoordinator | 自身 | E11 | 校验边证据非空与计数口径 | BR-518 细化；`AC-IMPL-FR12-02`；`AC-IMPL-FR16-02` 边界；SPEC §3 |
| M19 | 筛选守卫：按领域（发生时适用分类及映射版本）与机构（成果发生时机构证据）先筛论文与作者参与证据，再重建边与 k；未知机构/领域不命中；跨维度取交集；同维度多值语义未冻结时按已声明语义或拒绝（E12；A14） | CollaborationPathCoordinator | 自身 | E12/A14 | 应用筛选并重建子图 | IN01-R1；§11.5；BR-513/BR-524 细化；SPEC §3；B-01/B-02 |
| M20 | 按筛选后的论文证据重建该边证据集合与 k（无证据则边不成立） | CollaborationPathCoordinator | CollaborationEdge | E12 | 重建论文边证据集合与 k | SPEC §3；BR-UC15-12 |
| M21 | 返回重建后的边身份与 k（边消失时为 0） | CollaborationEdge | CollaborationPathCoordinator | E12 | 提供重建后的边身份与 k | 分析推导；SPEC §3 |
| M22 | 规模守卫：筛选后节点>10000 或 边>100000→明确超规模分支并披露实际规模与上限；禁止任意截断后声称全范围最短（E13；A13） | CollaborationPathCoordinator | 自身 | E13/A13 | 判定规模并发起超规模分支 | `AC-IMPL-IN01-04`；SPEC §3；BR-528 细化；B-03 |
| M23 | 只读确认起点/终点学者的当前 canonical 身份、公开状态与合并指向（E14） | CollaborationPathCoordinator | Scholar | E14 | 提供端点身份与状态（只读） | IN01-R1/R3；SPEC §2/§4；IN01-S07 细化 |
| M24 | 返回端点身份、公开状态与合并指向 | Scholar | CollaborationPathCoordinator | E14 | 提供端点身份与状态 | 分析推导；SPEC §4 |
| M25 | 端点校验守卫：两端点属于同一筛选快照且可用；不存在/未公开/已合并分别提示；端点相同按 0 跳返回；未指定端点→要求指定（E14；A3–A6） | CollaborationPathCoordinator | 自身 | E14/A3–A6 | 判定端点可用性与 0 跳情形 | IN01-R3；SPEC §4；IN01-S07 细化；C-05 |
| M26 | 只读读取当前论文子图的相邻关系与论文证据数（BFS 用） | CollaborationPathCoordinator | CollaborationEdge | E15 | 提供邻接关系与论文证据计数 | IN01-R2；SPEC §4 |
| M27 | 返回相邻关系与论文证据数 | CollaborationEdge | CollaborationPathCoordinator | E15 | 提供邻接与计数 | 分析推导；SPEC §4 |
| M28 | 计算最短路径并登记结果身份（起点、终点、图版本、筛选条件） | CollaborationPathCoordinator | PathResult | E15 | 承接路径结果的身份与内容 | IN01-R2；`AC-IMPL-IN01-01/02`；SPEC §4 |
| M29 | BFS 与等长裁决守卫：无向非加权图按边数计最短；从目标 BFS 得 dist；从起点每步取 dist 递减邻居中 canonical_id 最小者；同图版本同条件下返回同一条并说明字典序规则（E15；A7/A11） | CollaborationPathCoordinator | 自身 | E15/A7/A11 | 计算最短路径并裁决等长 | IN01-R2；`AC-IMPL-IN01-01/02`；BR-IMP-09；BR-525 细化；SPEC §4 |
| M30 | 返回节点序列、跳数（＝边数）与逐跳边引用 | PathResult | CollaborationPathCoordinator | E15 | 提供节点序列、跳数与逐跳边引用 | SPEC §4；BR-525 细化 |
| M31 | 逐跳只读读取该跳的合格共同论文证据（默认 canonical ID 最小一篇起） | CollaborationPathCoordinator | CollaborationEdge | E16 | 提供该跳论文证据与 k（只读） | IN01-R3；BR-518 细化 |
| M32 | 返回该跳论文证据与 k | CollaborationEdge | CollaborationPathCoordinator | E16 | 提供该跳证据与 k | 分析推导；BR-518 细化 |
| M33 | 只读读取路径证据论文的公开字段（标题、类型＝论文、时间、来源、归属状态）（E16） | CollaborationPathCoordinator | WorkEntity | E16 | 提供证据公开字段（只读） | IN01-S04 细化；§7.1；BR-IMP-01 |
| M34 | 返回公开字段与缺失标记（缺失标未知，不隐藏该项） | WorkEntity | CollaborationPathCoordinator | E16 | 提供公开字段与缺失标记 | IN01-S04 细化；§7.1 |
| M35 | 绑定逐跳论文证据（每跳≥1 篇）与详情入口；k 只计本范围合格共同论文，不等于 FR12 共同成果强度（E16） | CollaborationPathCoordinator | PathResult | E16 | 绑定逐跳证据并标注 k 口径 | IN01-R3；BR-518 细化；B-06 |
| M36 | 确认每跳证据非空、跳数＝边数、证据与图版本一致 | PathResult | CollaborationPathCoordinator | E16 | 返回一致性确认 | 分析推导；`AC-IMPL-IN01-01`；SPEC §4 |
| M37 | 披露生成：数据图范围（仅合格共同论文）、筛选条件与语义（领域/机构、发生时口径、映射版本）、图版本、路径长度定义（合作边数）、等长裁决规则、论文证据范围、规模与上限、不可推断规则（E17） | CollaborationPathCoordinator | 自身 | E17 | 组装与结果一致的披露 | IN01-R2/R3；IN01-S06 细化；BR-514 细化 |
| M38 | 返回筛选网络或路径结果＋逐跳证据入口＋披露＋状态（有结果/无匹配边/无路径/端点不可用/已合并/超规模/失败/超时） | CollaborationPathCoordinator | CollaborationNetworkUI | E18 | 返回结果、披露与状态 | IN01-R3；NF01 |
| M39 | 展示筛选网络或路径、逐跳论文证据入口、披露与固定说明（不推断同机构/同领域合作；路径长度与证据数不代表质量）（E18） | CollaborationNetworkUI | 访客 | E18 | 显示结果、披露与固定说明 | IN01-R3；BR-522 细化；IN01-S06 细化；NF06 |
| M40 | 点击某跳论文证据或路径中学者节点，请求导航（E19） | 访客 | CollaborationNetworkUI | E19 | 接收导航请求 | IN01-S04/S07 细化 |
| M41 | 请求目标对象的可访问性 | CollaborationNetworkUI | CollaborationPathCoordinator | E19 | 判定导航目标可用性 | 分析推导；IN01-S07 细化；BR-530 |
| M42 | 只读确认目标学者当前 canonical 身份、公开状态与合并指向 | CollaborationPathCoordinator | Scholar | E19 | 提供当前身份与合并指向（只读） | SPEC §2；BR-530；IN01-S07 细化 |
| M43 | 返回当前身份、公开状态与合并指向 | Scholar | CollaborationPathCoordinator | E19 | 提供身份与状态 | 分析推导；BR-IMP-03 |
| M44 | 返回导航结论（论文详情 UC-02／学者门户 UC-03／明确不可用原因类别） | CollaborationPathCoordinator | CollaborationNetworkUI | E19 | 返回导航结论与原因类别 | IN01-S04/S07 细化；BR-IMP-01 |
| M45 | 跳转或给出明确提示，不出现空白页或静默失败 | CollaborationNetworkUI | 访客 | E19/A5 | 执行跳转或显示明确提示 | IN01-S07 E1/E2 细化 |
| M46 | 版本一致性与未更新守卫：与检索/门户/统计/基础网络使用同一生效关系版本；图版本失效或落后→要求重新查询或标注“结果可能未反映最新变更”，不以旧版本冒充最新、不混用两个版本（E20；A12） | CollaborationPathCoordinator | 自身 | E20/A12 | 核对版本并决定是否标注未更新 | NF05；`AC-IMPL-NF05-01/02`；BR-IMP-10；BR-523 细化 |
| M47 | 返回图版本、更新时间与未更新提示 | CollaborationPathCoordinator | CollaborationNetworkUI | E20 | 返回版本与时间信息 | 分析推导；NF05 |
| M48 | 显示图版本、更新时间与未更新提示 | CollaborationNetworkUI | 访客 | E20 | 显示版本与时间提示 | NF05；`AC-IMPL-NF05-01` |
| M49 | 空与失败守卫：筛选后无合格论文边→“当前范围没有可展示的合作关系”；无路径→“当前范围无路径”并在同屏重复数据范围；读取或计算失败→明确失败；计算超出约定时限→明确超时反馈，不无限加载；四类互不冒充，不展示半成品或空图（A2/A7/A18/A19） | CollaborationPathCoordinator | 自身 | A2/A7/A18/A19 | 区分无匹配边、无路径、失败与超时 | IN01-R3；`AC-IMPL-IN01-03`；NF01；IN01-S05 细化；NF02 |
| M50 | 返回无匹配边/无路径/失败/超时提示（可区分） | CollaborationPathCoordinator | CollaborationNetworkUI | A2/A7/A18/A19 | 返回空/失败分支 | NF01；`AC-IMPL-IN01-03` |
| M51 | 越界守卫：拒绝用非论文成果补路径、跨范围推断、同机构/同领域推断、个人排名与质量评价；说明 IN01 只使用合格共同论文子图（A8/A9/A20） | CollaborationPathCoordinator | 自身 | A8/A9/A20 | 拒绝越界请求并给范围说明 | IN01-R1/R3；BR-IMP-09；SRS §2.1；BR-524 细化 |
| M52 | 返回拒绝结论与可用范围说明 | CollaborationPathCoordinator | CollaborationNetworkUI | A8/A9/A20 | 返回拒绝与范围说明 | 分析推导；02 §5 |
| M53 | 显示拒绝与范围说明 | CollaborationNetworkUI | 访客 | A8/A9/A20 | 显示拒绝与可用范围 | 分析推导；02 §5 |

> `M49`–`M53` 的守卫分支复用 `M1`/`M2` 的触发路径；不另设 Actor 消息，避免把同一交互画成两次重复。`A10`（某跳只有非论文证据）由 `M18`/`M35` 的证据守卫表达：该边本不属于论文子图，故不产生“空证据跳”。

## 3. 事件、分支与消息对照

| 事件/分支 | 消息 | 关键守卫 | 可观察结果 |
|---|---|---|---|
| E1 打开筛选/路径入口 | M1–M2 | — | 进入筛选或路径查询 |
| E2/A1 条件校验 | M3 | 筛选取值合法∧维度语义已声明∧端点已给出 | 条件有效或拒绝并保留原条件 |
| E3/A12 图版本固定 | M4、M46–M48 | 同一图版本∧版本可用 | 图版本标识或失效反馈 |
| E4 数据图范围 | M5 | 真实∧公开∧生效∧论文 | 论文子图范围与不可推断声明 |
| E5 读取论文 | M6–M7 | 只读；带缺失标记 | 论文身份、资格与筛选口径字段 |
| E6/A8/A14 论文资格 | M8 | 类型＝论文∧公开生效∧真实 | 进入子图的论文集合与未纳入原因 |
| E7 读取参与关系 | M9–M10 | 只读；角色/状态/发生时机构可见 | 论文作者关系与缺失标记 |
| E8/A9/A10 关系资格 | M11 | 角色＝论文作者∧身份明确∧可公开∧状态合格 | 每条关系的参与裁决 |
| E9 读取身份 | M12–M13 | 只读；canonical 身份与合并状态 | 作者身份 |
| E10/A3–A6/A15 节点与端点 | M14、M23–M25 | 公开∧canonical∧合并解析∧端点已消歧∧同一快照 | 节点集合与端点可用性 |
| E11/A8 论文子图建边 | M15–M18 | 无序对∧min/max 边键∧无自环∧仅论文证据 | 论文边与证据集合/k |
| E12/A14 筛选 | M19–M21 | 发生时期口径∧未知不命中∧跨维度交集∧先筛后建边 | 筛选后边集合与 k |
| E13/A13 规模 | M22 | 节点≤10000∧边≤100000 | 规模披露或超规模分支 |
| E14/A3–A6 端点校验 | M23–M25 | 同一筛选快照∧可用∧端点已消歧 | 端点可用、0 跳或分类不可用 |
| E15/A7/A11 最短路径 | M26–M30 | 边数最短∧dist 递减∧canonical_id 最小 | 节点序列、跳数与规则说明 |
| E16/A10/A16/A17 逐跳证据 | M31–M36 | 每跳≥1 篇论文∧k 口径∧同图版本 | 逐跳证据与详情入口 |
| E17 披露 | M37 | 披露项齐备且与结果一致 | 披露块 |
| E18 展示 | M38–M39 | 状态可区分∧不作质量结论 | 结果、披露与固定说明 |
| E19/A5 导航 | M40–M45 | 目标公开可访问∧合并解析 | 详情/门户跳转或明确提示 |
| E20/A12 版本一致性 | M46–M48 | 同一生效关系版本∧图版本语义 | 图版本/更新时间或未更新标注 |
| A2/A7/A18/A19 空与失败 | M49–M50 | 无匹配边≠无路径≠失败≠超时 | 可区分提示，无半成品 |
| A8/A9/A20 越界与旁证 | M51–M53 | 能力范围∧不推断∧交接说明 | 拒绝与可用范围 |

## 4. 完整性与一致性核对

| 检查 | 结果 | 说明 |
|---|---|---|
| 消息编号 | M1–M53 连续、无缺号、无重复 | 脚本核对（08 §4.4） |
| 每条消息有发送者/接收者/事件/职责/依据 | 是 | 表 2 全列齐全 |
| 每个参与类至少收到一条消息 | 是 | UI：M1/M38/M40/M47/M52；Ctl：M2/M41 等；Scholar：M12/M23/M42；WorkEntity：M6/M33；Authorship：M9；CollaborationEdge：M16/M20/M26/M31；PathResult：M28/M35 |
| 是否让专利/项目/获奖边进入路径 | 否（有守卫） | M5/M15/M18；A8；BR-UC15-01 |
| 是否用同机构/同领域/相似姓名推断路径 | 否（显式排除） | M14/M19/M51；A9；BR-UC15-16 |
| 是否把强度/ k 当作距离 | 否 | M29；BR-UC15-07；SPEC §4 |
| 等长是否只返回一条并说明规则 | 是（有守卫） | M29/M37；A11；`AC-IMPL-IN01-02` |
| 每跳是否有至少一篇可点击论文证据 | 是（有守卫） | M31–M36；IN01-R3 |
| 被排除关系是否产生边或证据 | 否 | M11；A9/A10；`AC-IMPL-FR12-04` |
| 无路径/无效端点/超规模/失败/超时是否分开 | 是 | M22/M25/M38/M49–M50；NF01 |
| 披露是否与结果同屏且含等长规则与图版本 | 是 | M37–M39；BR-UC15-08/15 |
| 是否出现跨范围补边或推断 | 否 | M5/M19/M35/M51；A7/A9/A20 |
| 是否出现持久路径结果/图快照/图状态机 | 否 | 05 §4；C-01；B-08 |
| 是否出现写成果/参与关系/身份/合并的消息 | 否 | 只有 M6/M9/M12/M23/M33/M42 六条只读请求 |
| 是否出现实现技术（图库、索引、缓存、任务、接口、DTO、BFS 代码） | 否 | 03 §4；05 §4；06 §11 |
| 是否出现质量/影响力/个人排名 | 否（固定说明＋拒绝） | M39/M51；BR-UC15-17 |

## 5. 与图的一致性

`DGM-SEQ-23` 的 53 条消息与本表逐条对应；图中分组为“入口与条件（E1–E3）→ 论文子图范围与论文资格（E4–E6）→ 参与关系资格（E7–E8）→ 节点与端点身份（E9–E10）→ 论文子图建边（E11）→ 筛选与规模（E12–E13）→ 端点校验与最短路径（E14–E16）→ 披露与展示（E17–E18）→ 导航（E19）→ 版本一致性（E20）→ 状态与边界（A2/A7/A18/A19/A20）”。图中不画 Actor 为系统类，不画 `Institution`、`Field`、`UserProfile`、`SourceRecord`、`WorkVersion`、`GraphSnapshot`、`BfsQueue`、质量评分或 DTO 的消息；A1–A20 以守卫消息与图例表达。

## 6. 与 UC-14 的消息边界

UC-14 与本 UC 共用**同一网络视图入口**，但消息与职责严格分工：

- UC-14 产出：四类共同成果基础网络的节点、无向边、强度 `weight`、证据清单与披露；**不产出**路径、跳数、k、图版本或筛选结果。
- 本 UC 产出：论文子图投影（证据只含论文、计数为 k）、筛选结果、路径节点序列与跳数、逐跳论文证据、图版本与披露。
- 两处**同名类不重复定义**：`CollaborationEdge` 在 UC-14 是四类强度的派生边，在本 UC 只使用其论文投影；本 UC 不改写 UC-14 的强度规则（BR-UC15-06；07 §4.3）。
- 本 UC 不向 UC-14 回写任何内容，也不修改基础网络的建边与披露；若基础网络缺“成果类型/证据”标注，则本 UC 无法区分论文边——该前提由 UC-14 A16/M51 保证。
- 上游的“拒绝最短路径与机构/领域筛选”消息（UC-14 M51–M53）与本 UC 的入口（M1–M2）构成完整交接：UC-14 只做拒绝与指引，本 UC 才执行筛选与路径。
