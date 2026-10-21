# UC-09 分析序列与逐消息职责

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-SEQ-14-dedup.puml`](diagrams/DGM-SEQ-14-dedup.puml)
**导出：** [SVG](diagrams/generated/DGM-SEQ-14-dedup.svg) · [PNG](diagrams/generated/DGM-SEQ-14-dedup.png)
**方法：** 教材 §5.4.3；只表达分析对象协作，不写 Java、数据库、HTTP/API、检索索引或消息中间件。

## 1. 顺序图范围

本序列实现 UC-09 的“核验和纠正成果数据”：主流程 E1–E17，以及 A1（异常记录）、A2（重复到达/批次重跑）、A3（标识冲突待核验）、A4（辖区未知标识）、A5（仅标题相似的候选）、A6（无标识多证据一致）、A7（候选过多）、A8（日期非法/歧义/可疑）、A9（与已确认值冲突）、A10（疑似版本无证据）、A11（循环合并/中断）、A12（映射不可用）、A13（留痕失败）、A16（无处置权限）分支；A14（姓名/归属问题转 UC-10）与 A15（来源撤回/删除/停用不级联删除）为分析级约束与范围边界，无独立消息。

参与对象（共 11 个）：

- 参与者（图外，不画成系统类）：来源采集/增量任务（UC-18/19）、治理管理员
- `DedupGovernanceUI`（Boundary）
- `DedupCoordinator`（Control）
- `User`（Entity）
- `SourceRecord`（Entity）
- `CleanRecord`（Entity）
- `DedupCandidate`（Entity）
- `WorkEntity`（Entity）
- `WorkVersion`（Entity）
- `AuditEvidence`（Entity）

## 2. 逐消息职责表

| 消息 | 名称 | 发送者 | 接收者 | 事件步骤 | 接收者职责 | 依据/假设 |
|---|---|---|---|---|---|---|
| M1 | 交付已获准接入的来源记录批次（批次标识/到达事件/来源记录引用）（E1） | 来源采集/增量任务 | DedupGovernanceUI | E1 | 接收批次并进入治理入口 | §6.1 管线；FR08/FR10（前置，属 UC-18/19） |
| M2 | 提交治理批次（批次引用＋来源记录引用＋已认证系统/账号上下文） | DedupGovernanceUI | DedupCoordinator | E1 | 启动批次治理，转交批次内容 | 分析推导；FR09 参与者 |
| M3 | 只读读取来源原值/来源身份/类型线索/许可/数据性质/到达时间（E2） | DedupCoordinator | SourceRecord | E2 | 提供原始证据供校验与规范化（只读） | INV-01；§7.1；FR16；CL-11 |
| M4 | 校验来源身份与成果类型与名称（空白规范化后为空则异常）（E2） | DedupCoordinator | DedupCoordinator | E2 | 判定“可治理/异常”，空白规范化后为空即异常 | CL-01；SPEC §4.1 前置；FR09-S01 |
| M5 | 登记异常待处理（原因/缺项；原值完整保留） | DedupCoordinator | CleanRecord | A1 | 建立异常态规范记录并保留原始内容 | CL-01；SPEC §4.4；FR09-S11 主流程 1 |
| M6 | 记录异常入队与原因 | DedupCoordinator | AuditEvidence | A1 | 记录异常产生的原因与时间 | BR-IMP-10；FR09-S11 BR-381 |
| M7 | 该记录异常待处理，不形成有效公开实体 | DedupCoordinator | DedupGovernanceUI | A1 | 返回异常结论而非成功 | CL-01；FR08“异常不得直接成为已公开成果” |
| M8 | 显示异常与缺项 | DedupGovernanceUI | 治理管理员 | A1 | 显示异常原因与缺项，提示可修正后重新判定 | FR09-S11；NF01（可区分）；分析推导 |
| M9 | 建立规范化记录（逐字段原值＋规范值＋规则版本；原值不覆盖）（E3） | DedupCoordinator | CleanRecord | E3 | 保存可回放的清洗产物 | INV-01；CL-02/CL-11；SPEC §4.1 |
| M10 | 按类型校验最小字段集：论文/专利/项目/获奖的名称、类型、来源身份（E4） | DedupCoordinator | DedupCoordinator | E4 | 按成果类型判定治理最小输入是否齐备 | SPEC §4.1“必填边界”；CL-08；§7.1 |
| M11 | 记录最小字段结果：齐备或缺项标"未知"，区分空白/缺失/确认为无，不造值（E13） | DedupCoordinator | CleanRecord | E13 | 逐字段保存缺失语义，不造值 | CL-08；§7.1；`AC-IMPL-FR09-04` |
| M12 | 规范化时间事件（事件语义/数值/精度/原字符串；仅年份不补1月1日；采集时间不冒充发生时间）（E5） | DedupCoordinator | CleanRecord | E5 | 保存时间语义与精度，拒绝默认填充 | CL-06/CL-07；`AC-IMPL-FR09-04` |
| M13 | 规范化稳定标识（DOI按解析前缀与大小写无关；专利申请/公开/授权号按辖区＋体系；项目号限授予机构；未知辖区保留原值标待核验）（E6） | DedupCoordinator | CleanRecord | E6 | 按作用域生成标识比对键并记录作用域 | CL-03/CL-04；`AC-IMPL-FR09-05`；B-03 |
| M14 | 标异常待处理或待核验，不写生效发生日，保留原字符串 | DedupCoordinator | CleanRecord | A8 | 记录非法/歧义/可疑时间，不产生生效发生日 | CL-06；SPEC §4.4 |
| M15 | 返回日期异常/待核验（不静默补值） | DedupCoordinator | DedupGovernanceUI | A8 | 返回时间判定结论 | 分析推导；`AC-IMPL-FR09-04` |
| M16 | 保留未映射/待分类值，本次部分成功 | DedupCoordinator | CleanRecord | A12 | 记录“未映射”而不强行归类 | CL-05；FR09-S04 A2；BR-323 |
| M17 | 提示部分成功与可重试映射 | DedupCoordinator | DedupGovernanceUI | A12 | 返回部分成功结论与重试提示 | 分析推导；FR09-S04 |
| M18 | 置规范化记录治理状态：有效／异常待处理／待核验（E3） | DedupCoordinator | CleanRecord | E3 | 承载治理状态分类标签 | SPEC §3 公共状态（治理）；FR09-S11 |
| M19 | 按 source_id ＋ source_record_key 定位同一逻辑出处（E7） | DedupCoordinator | SourceRecord | E7 | 回答“该逻辑出处是否已存在” | DD-01；CL-10；FR09-S10 |
| M20 | 追加重复到达事件（不改写原值、不新增有效出处） | DedupCoordinator | SourceRecord | A2 | 记录重复到达，不改写 `rawValues` | CL-10；INV-01；FR09-S10 |
| M21 | 核对：不新增实体、不新增有效出处；到达事件计数可增加 | DedupCoordinator | DedupCoordinator | A2 | 幂等核对（两个 `distinct` 计数） | DD-01/DD-11；`AC-IMPL-FR09-03/06` |
| M22 | 记录重复到达与幂等结论 | DedupCoordinator | AuditEvidence | A2 | 留痕重复到达与结论 | BR-IMP-10；FR09-S12 |
| M23 | 该记录已处理（distinct实体数、distinct有效出处数均不变） | DedupCoordinator | DedupGovernanceUI | A2 | 返回已处理结论 | `AC-IMPL-FR09-03`；分析推导 |
| M24 | 按（标识体系，辖区/命名空间，规范键）精确查找同类型实体，跨类型不自动合并（E8） | DedupCoordinator | WorkEntity | E8 | 按标识索引返回同类型命中集合 | DD-02/DD-03；BR-IMP-02；`AC-IMPL-FR09-01` |
| M25 | 命中数量/实体类型/标识争议状态（0／唯一／多个／类型不一致） | WorkEntity | DedupCoordinator | E8 | 返回命中数量与可判定所需事实 | 分析推导；FR09-S06 E1–E3 |
| M26 | 证据相容判定：同类型∧无矛盾证据∧无争议标记 | DedupCoordinator | DedupCoordinator | E8 | 判定是否满足自动关联的唯一条件 | DD-03；BR-IMP-02；B-03 |
| M27 | 关联出处（source_id，source_record_key），实体数不变 | DedupCoordinator | WorkEntity | E8 | 建立出关联，实体数＋0 | `AC-IMPL-FR09-01`；BR-302；DD-11 |
| M28 | 字段合并：已人工确认值优先呈现；新来源证据保留为冲突项，不静默覆盖（E12） | DedupCoordinator | WorkEntity | E12 | 按优先级合并字段并登记冲突项 | CL-09；BR-IMP-04；FR09-S05 E1/E4；`AC-IMPL-FR09-04` |
| M29 | 记录自动关联依据（标识类型与规范值） | DedupCoordinator | AuditEvidence | E8 | 保存自动关联依据 | FR09-S12 A2；BR-310 |
| M30 | 命中既有实体（实体数＋0、有效出处＋1） | DedupCoordinator | DedupGovernanceUI | E8 | 返回命中结论与计数效果 | `AC-IMPL-FR09-01`；DD-11 |
| M31 | 建立候选待核验（列出全部候选与命中标识/不一致原因，不任选其一）（E10） | DedupCoordinator | DedupCandidate | E10 | 保存候选清单、原因与匹配状态 | DD-02/DD-04；FR09-S06 E1–E3 |
| M32 | 记录待核验生成（原因/候选数量） | DedupCoordinator | AuditEvidence | E10 | 留痕待核验的生成 | BR-IMP-10；FR09-S11 |
| M33 | 该记录待核验（不自动合并） | DedupCoordinator | DedupGovernanceUI | E10 | 返回待核验结论 | `AC-IMPL-FR09-02`（反向）；FR09-S07 |
| M34 | 进入候选生成：同类型＋标题比对键＋年份窗召回（召回不赋予合并资格）（E9） | DedupCoordinator | DedupCoordinator | E9 | 判定无稳定标识路径并执行召回 | DD-05/DD-06/DD-07；B-03/B-04 |
| M35 | 生成候选与逐项证据（标题/年份/参与者/成果类型：命中或冲突） | DedupCoordinator | DedupCandidate | E9 | 保存候选与逐项证据清单 | DD-05/DD-07；FR09-S07 主流程 2 |
| M36 | 标记候选待核验（标题相似本身不构成合并依据） | DedupCoordinator | DedupCandidate | A5 | 记录“标题相似/年份冲突/参与者冲突” | `AC-IMPL-FR09-02`；BR-IMP-02；BR-305 |
| M37 | 候选待核验（不自动合并） | DedupCoordinator | DedupGovernanceUI | A5 | 返回候选结论 | `AC-IMPL-FR09-02`；FR09-S07 |
| M38 | 仍为候选待核验；无稳定标识的自动合并未获批准，不可执行 | DedupCoordinator | DedupCandidate | A6 | 拒绝历史“四证据自动合并”路径 | DD-06；SPEC §5.3；Q-SPEC-01；D-07 |
| M39 | 候选待核验（须人工确认，阈值待确认） | DedupCoordinator | DedupGovernanceUI | A6 | 声明阈值未冻结，不做自动合并 | DD-06；B-03 |
| M40 | 限制候选清单长度并标注"候选过多，需人工筛查" | DedupCoordinator | DedupCandidate | A7 | 记录候选规模与截断标注 | DD-12；FR09-S07 E1；B-04 |
| M41 | 候选过多提示（不默认选第一个） | DedupCoordinator | DedupGovernanceUI | A7 | 返回候选规模与筛查提示 | DD-12；分析推导 |
| M42 | 新建成果实体（依据＝无标识新建；类型/名称/标识按规范值） | DedupCoordinator | WorkEntity | E9（无候选） | 建立实体并记录新建依据 | DD-05/DD-07 A1；FR09-S07 A1 |
| M43 | 关联首个出处（source_id，source_record_key） | DedupCoordinator | WorkEntity | E9（无候选） | 建立首个出处 | `AC-IMPL-FR09-01`（同类）；BR-302 |
| M44 | 记录新建依据与出处 | DedupCoordinator | AuditEvidence | E9（无候选） | 留痕新建依据 | FR09-S12；BR-310 |
| M45 | 新建实体（实体数＋1） | DedupCoordinator | DedupGovernanceUI | E9（无候选） | 返回新建结论 | DD-11；`AC-IMPL-FR09-06`（计数口径） |
| M46 | 归入同一成果实体的版本清单（版本种类/明示证据/关联来源）（E11） | DedupCoordinator | WorkVersion | E11 | 只在明示版本指向时建立版本关系 | DD-08；`AC-IMPL-FR09-05` |
| M47 | 记录版本归并（实体数不增） | DedupCoordinator | AuditEvidence | E11 | 留痕版本归并 | FR09-S12；DD-08；`AC-IMPL-FR16-02` |
| M48 | 保持两个独立实体并标疑似版本关联（待核验） | DedupCoordinator | DedupCandidate | A10 | 记录疑似关联而不合并 | DD-08；B-05 |
| M49 | 批次核对：distinct(生效WorkEntity canonical_id)／distinct(source_id,source_record_key)／到达事件数／异常数／待核验数分别计数（E14） | DedupCoordinator | DedupCoordinator | E14 | 输出批次计数不变量 | DD-11；BR-IMP-02/06；`AC-IMPL-FR09-03/06` |
| M50 | 返回批次结论（命中/新建/候选/异常计数；"批次处理完成"不等于"治理全部通过"） | DedupCoordinator | DedupGovernanceUI | E14 | 组合计数与结论返回 | §6.2；§11.4；FR08 |
| M51 | 显示批次结论与待核验计数（异常不得直接成为已公开成果） | DedupGovernanceUI | 来源采集/增量任务 | E14 | 向交付方与管理员披露批次结果 | FR08；§6.2；分析推导 |
| M52 | 打开处理项（候选/异常/字段冲突）并查看证据 | 治理管理员 | DedupGovernanceUI | E15 | 接收处理项选择 | FR09-S11 主流程 2 |
| M53 | 请求处理项详情（处理项引用＋管理员上下文） | DedupGovernanceUI | DedupCoordinator | E15 | 转交请求并按权限裁决 | FR14；NF03；分析推导 |
| M54 | 校验管理员身份、账号状态与治理处置权限（A16） | DedupCoordinator | User | E15 | 提供账号状态与处置权限事实 | NF03；FR14；`AC-IMPL-NF03-01` |
| M55 | 拒绝：无治理处置权限，处理项与公开值不变 | DedupCoordinator | DedupGovernanceUI | A16 | 返回拒绝结论 | FR09-S09/S11 权限规则；`AC-IMPL-NF03-01` |
| M56 | 显示拒绝（与"已处置/失败"区分） | DedupGovernanceUI | 治理管理员 | A16 | 显示拒绝原因，不复用失败文案 | 分析推导；NF01 |
| M57 | 记录拒绝/越权尝试 | DedupCoordinator | AuditEvidence | A16 | 记录拒绝的对象与结论 | BR-IMP-10；INV-10 |
| M58 | 读取候选实体清单、逐项证据、候选规模与状态版本 | DedupCoordinator | DedupCandidate | E15 | 提供待核验内容与版本 | FR09-S07/S11；DD-12 |
| M59 | 读取异常原因、字段冲突项与原始内容 | DedupCoordinator | CleanRecord | E15 | 提供异常/冲突内容与原因 | FR09-S11；CL-09 |
| M60 | 返回处理项内容（候选证据/异常原因/冲突字段与双方来源） | DedupCoordinator | DedupGovernanceUI | E15 | 组合处理项内容 | 分析推导；FR09-S11 |
| M61 | 显示处理项与可执行处置动作 | DedupGovernanceUI | 治理管理员 | E15 | 显示证据与可选动作 | FR09-S11 主流程 3 |
| M62 | 提交处置动作与理由 | 治理管理员 | DedupGovernanceUI | E16 | 接收动作与理由 | FR09-S09/S11/S12；FR14 |
| M63 | 提交处置（处理项引用＋动作＋理由＋状态版本） | DedupGovernanceUI | DedupCoordinator | E16 | 转交处置内容与版本 | FR09-S11 E2（并发守卫）；分析推导 |
| M64 | 置确定不同并保留理由与证据 | DedupCoordinator | DedupCandidate | E16（确认不同） | 关闭候选并保留证据 | FR09-S07 A3 类比；DD-07；FR09-S11 |
| M65 | 记录确定不同依据 | DedupCoordinator | AuditEvidence | E16（确认不同） | 留痕排除依据 | FR09-S12；BR-381 |
| M66 | 结果：确定不同（不合并，实体数不变） | DedupCoordinator | DedupGovernanceUI | E16（确认不同） | 返回结果 | FR09-S11；DD-11 |
| M67 | 新建成果实体（依据＝人工判定；原署名与来源保留） | DedupCoordinator | WorkEntity | E16（确认新实体） | 建立实体并记录人工依据 | FR09-S11 A3 |
| M68 | 置已处理（确认新实体） | DedupCoordinator | DedupCandidate | E16（确认新实体） | 关闭候选并记录判定 | FR09-S11 A3 |
| M69 | 记录人工新建依据 | DedupCoordinator | AuditEvidence | E16（确认新实体） | 留痕人工判定 | FR09-S12 A2 类比；BR-381 |
| M70 | 结果：新建实体（实体数＋1） | DedupCoordinator | DedupGovernanceUI | E16（确认新实体） | 返回结果 | DD-11；FR09-S11 AC-02 类比 |
| M71 | 合并守卫：类型一致∧无循环合并∧状态版本未变∧目标实体非待核验/争议（A11） | DedupCoordinator | DedupCoordinator | E16（合并） | 四项守卫的合取；不满足转 A11/拒绝 | DD-02/DD-09；FR09-S09 E2；FR09-S11 E1/E2 |
| M72 | 拒绝循环合并或整体回滚，不留部分合并状态 | DedupCoordinator | DedupGovernanceUI | A11 | 返回拒绝/回滚结论 | FR09-S09 E2/E3；DD-09 |
| M73 | 记录拒绝与回滚 | DedupCoordinator | AuditEvidence | A11 | 留痕失败原因并允许重试 | FR09-S12 E1；FR09-S11 E1 |
| M74 | 合并到目标实体（迁移全部唯一出处与版本；字段按优先级合并） | DedupCoordinator | WorkEntity | E16（合并） | 迁移出处/版本并合并字段 | DD-09；FR09-S09 主流程 2–3；BR-302 |
| M75 | 目标实体记录合并链；来源实体标"已合并"并重定向旧标识（旧ID可溯源） | DedupCoordinator | WorkEntity | E16（合并） | 记录合并链与重定向 | FR09-S09 主流程 4–5；BR-310；DD-09 |
| M76 | 字段完整性核对：来源更完整值用于补充；冲突入同一可累积处理项 | DedupCoordinator | WorkEntity | E16（合并） | 合并后字段完整性核对 | FR09-S09 E4；FR09-S05 E4；CL-09 |
| M77 | 置已处理（合并） | DedupCoordinator | DedupCandidate | E16（合并） | 关闭候选并记录结论 | FR09-S11；DD-09 |
| M78 | 记录人工合并（依据/操作者/前后出处数与实体归属） | DedupCoordinator | AuditEvidence | E16（合并） | 留痕合并前后值 | FR09-S12；BR-381；`AC-IMPL-FR16-02` |
| M79 | 合并生效（全部出处保留、实体数减少、旧标识可定位） | DedupCoordinator | DedupGovernanceUI | E16（合并） | 返回合并结论 | FR09-S09 AC-01/AC-02；DD-09/DD-11 |
| M80 | 拆分：重建来源映射与实体归属（不删除任何出处与版本） | DedupCoordinator | WorkEntity | E16（拆分） | 拆分实体并重建映射 | DD-10；FR09-S12 A3；FR09-S11 |
| M81 | 更新拆分后实体标识与重定向，原合并链保留 | DedupCoordinator | WorkEntity | E16（拆分） | 更新标识与重定向 | DD-09/DD-10；BR-310 |
| M82 | 置已处理（拆分） | DedupCoordinator | DedupCandidate | E16（拆分） | 关闭处理项并记录拆分 | FR09-S11；DD-10 |
| M83 | 记录拆分（依据/操作者/前后归属；撤销另写新记录，不覆盖历史） | DedupCoordinator | AuditEvidence | E16（拆分） | 追加拆分/撤销记录 | FR09-S12 A3/E3；BR-382 |
| M84 | 拆分生效（历史合并记录与审计保留） | DedupCoordinator | DedupGovernanceUI | E16（拆分） | 返回拆分结论 | FR09-S12 AC-02；DD-10 |
| M85 | 写入人工确认值（原已确认值保留在证据链；来源原值不改写）（A9） | DedupCoordinator | WorkEntity | E16（字段确认）；A9 | 写入人工确认的生效值 | CL-09；BR-IMP-04；`AC-IMPL-FR09-04`；FR09-S05 权限规则 |
| M86 | 关闭该字段冲突项（记录确认者/时间/依据） | DedupCoordinator | CleanRecord | E16（字段确认） | 关闭冲突项并保留证据 | CL-09；FR09-S05 E1 |
| M87 | 记录字段确认（前后值/双方来源） | DedupCoordinator | AuditEvidence | E16（字段确认） | 留痕字段确认 | FR09-S12；BR-381 |
| M88 | 字段确认生效（此后来源更新不得静默覆盖该值） | DedupCoordinator | DedupGovernanceUI | E16（字段确认） | 返回确认结论 | BR-IMP-04；`AC-IMPL-FR09-04` |
| M89 | 修正规范值（原值与规则版本保留，记录修正者与理由） | DedupCoordinator | CleanRecord | E16（修正字段）；A1 | 更新规范值供重新判定 | FR09-S11 主流程 3；CL-11；INV-01 |
| M90 | 重新进入标识匹配与候选判定（不新建并列处理项） | DedupCoordinator | DedupCoordinator | E16（修正字段） | 回到匹配判定，复用同一处理项 | FR09-S11；FR09-S05 E4（同一处理项） |
| M91 | 记录字段修正（前后值/操作者/理由） | DedupCoordinator | AuditEvidence | E16（修正字段） | 留痕修正 | FR09-S12 |
| M92 | 置已处理（忽略，理由保留；不进入公开检索） | DedupCoordinator | CleanRecord | E16（忽略） | 关闭处理项且不公开 | FR09-S11 A1 |
| M93 | 记录忽略依据 | DedupCoordinator | AuditEvidence | E16（忽略） | 留痕忽略理由 | FR09-S11；BR-381 |
| M94 | 结果：已忽略（不公开） | DedupCoordinator | DedupGovernanceUI | E16（忽略） | 返回忽略结论 | FR09-S11 A1；分析推导 |
| M95 | 留痕写入失败：不报告成功，可重试，不留"已生效但无记录"（A13） | DedupCoordinator | DedupGovernanceUI | A13；E17 | 返回失败而非成功 | FR09-S12 E1；BR-IMP-10；INV-10 |
| M96 | 显示失败与重试 | DedupGovernanceUI | 治理管理员 | A13；E17 | 显示失败并可重试 | 分析推导；NF01 |

## 3. 分支与守卫小结

| 分支 | 消息 | 守卫/条件 | 业务结果 |
|---|---|---|---|
| A1 异常记录 | M5–M8 | 来源身份/类型/名称齐备 | 异常待处理、不公开、可修正重判（M89–M91） |
| A8 时间异常 | M14–M15 | 日期合法∧顺序无歧义∧年份可信 | 异常/待核验、不写生效发生日 |
| A12 映射不可用 | M16–M17 | 字典/分类可用 | 部分成功、未映射保留 |
| A2 重复到达 | M19–M23 | 同一 `source_id＋source_record_key` 已存在 | 只追加到达事件、两计数不增 |
| E8 自动关联 | M24–M30 | 同类型∧标识唯一命中∧证据相容 | 出处＋1、实体数＋0 |
| E10 标识冲突 | M31–M33 | 命中数＝1∧类型一致∧无争议 | 否则待核验、列全部候选 |
| E9 无标识候选 | M34–M41 | 存在候选实体 | 只生成候选＋逐项证据，不合并 |
| E9 无候选 | M42–M45 | 候选集为空 | 新建实体、实体数＋1 |
| E11 版本归并 | M46–M47 | 存在来源明示版本指向 | 版本清单＋1、实体数不增 |
| A10 疑似版本 | M48 | 无明示版本证据 | 保持独立、标疑似待核验 |
| E14 批次结论 | M49–M51 | — | 输出各计数并声明“批次完成≠治理通过” |
| A16 无处置权限 | M54–M57 | 账号可用∧具备治理处置权限 | 拒绝、状态不变、留痕 |
| E16 确认不同 | M64–M66 | 有处置权限∧版本未变 | 候选关闭、不合并 |
| E16 确认新实体 | M67–M70 | 同上 | 新建实体、人工判定依据 |
| E16 合并 | M71–M79 | 类型一致∧无循环∧版本未变∧目标可用 | 出处/版本全保留、实体数减少、旧ID可溯源 |
| A11 合并异常 | M72–M73 | 守卫通过∧写入完整 | 拒绝或整体回滚、可重试 |
| E16 拆分 | M80–M84 | 有处置权限∧版本未变 | 重建映射、历史合并记录保留 |
| E16 字段确认（A9） | M85–M88 | 冲突项存在∧有处置权限 | 写入人工确认值、关闭冲突项、此后不被静默覆盖 |
| E16 修正字段 | M89–M91 | 字段可修正 | 更新规范值并重新判定，不新建并列处理项 |
| E16 忽略 | M92–M94 | 有处置权限 | 已处理、不进入公开检索 |
| A13 留痕失败 | M95–M96 | 留痕写入成功 | 不报告成功、可重试 |

## 4. 完整性与一致性核对

| 检查 | 结果 | 说明 |
|---|---|---|
| 消息编号 | M1–M96 连续、无缺号、无重复 | 脚本核对（08 §4.4） |
| 每条消息有发送者/接收者/事件/职责/依据 | 是 | 表 2 全列齐备 |
| 每个参与类至少收到一条消息 | 是 | UI：M2/M53（及返回）；Ctl：M2；User：M54；SourceRecord：M3/M19/M20；CleanRecord：M5/M9/M11/M12/M13/M14/M16/M18/M59/M86/M89/M92；DedupCandidate：M31/M35/M36/M38/M40/M48/M58/M64/M68/M77/M82；WorkEntity：M24/M27/M28/M42/M43/M67/M74/M75/M76/M80/M81/M85；WorkVersion：M46；AuditEvidence：M6/M22/M29/M32/M44/M47/M57/M65/M69/M73/M78/M83/M87/M91/M93 |
| 是否出现无稳定标识的自动合并消息 | 否 | M34–M41 只生成/标记候选；M38 明确拒绝历史提案 |
| 是否出现“仅标题相似即合并” | 否 | M36 反向标记；A5 |
| 是否出现未知辖区标识作为稳定键 | 否 | M13 保留原值标待核验；后续走无标识路径（M34） |
| 是否出现“重复到达增加实体或有效出处” | 否 | M21/M23 反向核对；`distinct` 两计数不变 |
| 是否出现版本归并增加实体数 | 否 | M46/M47 明确实体数不增 |
| 是否给缺失字段造默认值 | 否 | M11 标未知并区分空白/缺失/确认为无 |
| 是否给仅年份补月日 | 否 | M12 明确不补 1 月 1 日 |
| 是否覆盖已人工确认值 | 否 | M28 保留冲突项；M85 仅由管理员确认后写入 |
| 是否出现改写来源原值/删除来源 | 否 | M3/M19 只读；M20 只追加到达事件；无删除消息 |
| 是否出现删除成果实体的消息 | 否 | 合并只标“已合并”并重定向（M75）；拆分重建映射（M80） |
| 是否出现学者身份判定/关系写状态 | 否 | 无 `Scholar`/`Authorship` 消息；A14 为边界 |
| 是否出现采集任务/重试/增量识别消息 | 否 | 只有 M1 批次交付（外部输入）与 M51 结论回显 |
| 是否出现发布/下架/投稿 | 否 | 无对应消息；03 §4 拒绝该候选 |
| 是否把异常、待核验、拒绝、失败、已处置分开表达 | 是 | A1/A8（异常）、A3/A5/A6/A7/A10（待核验）、A16（拒绝）、A11/A13（失败/回滚）、E16（已处置） |
| 是否引入实现技术 | 否 | 只有分析职责与业务消息 |

## 5. 与图的一致性

`DGM-SEQ-14` 的 96 条消息与本表逐条对应；图中分组为“批次清洗（M1–M18）→ 幂等与匹配/候选/版本（M19–M48）→ 批次结论（M49–M51）→ 管理员核验与处置（M52–M94）→ 留痕失败（M95–M96）”。图中不画 Actor 为系统类，不画 `CollectionTask`、`Scholar`、`Authorship`、`CorrectionRequest`、`PendingTodo`、`CollaborationEdge` 消息。
