# UC-10 分析序列与逐消息职责

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-SEQ-15-identity.puml`](diagrams/DGM-SEQ-15-identity.puml)
**导出：** [SVG](diagrams/generated/DGM-SEQ-15-identity.svg) · [PNG](diagrams/generated/DGM-SEQ-15-identity.png)
**方法：** 教材 §5.4.3；只表达分析对象协作，不写 Java、数据库、HTTP/API、索引、消息中间件或匹配算法。

## 1. 顺序图范围

本序列实现 UC-10“核验学者身份与归属”：主流程 E1–E18，以及 A1（未认证/停用）、A2（无治理处置权限）、A3（仅同名）、A4（标识一致但证据冲突）、A5（标识冲突/复用）、A6（合并后同角色重复）、A7（已排除不恢复）、A8（请求补充证据）、A9（标记争议）、A10（重复判定/版本已变）、A12（候选过多）、A13（涉及已认领门户）、A14（姓名表达异常）、A15（私人联系方式）、A16（拆分后无法确定）、A17（双管理员冲突）分支；A11（失败整体回滚）、A18（提交材料不构成本人确认）、A19（范围外请求）、A20（留痕失败不得报成功）为守卫、顺序约束或范围边界，在图中以守卫消息与图例表达，无独立消息。

参与对象（共 15 个；其中 3 个为参与者，不进入 VOPC）：

- 参与者（图外或图左侧）：治理管线（UC-09/18/19，触发方）、管理员（主要参与者）、相关学者（次要参与者）
- `IdentityVerificationUI`（Boundary）
- `IdentityVerificationCoordinator`（Control）
- `User`（Entity，只读校验）
- `ScholarAuthorization`（Entity，只读）
- `SourceRecord`（Entity，只读）
- `Scholar`（Entity）
- `IdentityCandidate`（Entity）
- `AttributionCandidate`（Entity）
- `Authorship`（Entity）
- `WorkEntity`（Entity，只读）
- `ReviewEvidence`（Entity）
- `AuditEvidence`（Entity）

## 2. 逐消息职责表

| 消息 | 名称 | 发送者 | 接收者 | 事件步骤 | 接收者职责 | 依据/假设 |
|---|---|---|---|---|---|---|
| M1 | 交付已去重成果与参与者署名事实（成果引用＋署名原值/顺序/角色＋机构/外部标识原值＋来源引用）（E1） | 治理管线 | IdentityVerificationUI | E1 | 接收待归属事实，进入身份／归属处理入口 | FR15-S01 触发；FR15-S07 前置；§6.1（前置属 UC-09/18/19） |
| M2 | 请求学者身份与归属处理（成果引用＋来源记录引用＋署名事实＋已认证上下文） | IdentityVerificationUI | IdentityVerificationCoordinator | E1 | 启动处理，转交成果引用与署名事实 | 分析推导；FR15 参与者“系统” |
| M3 | 只读读取署名/机构/外部标识原值、许可与到达时间（E2） | IdentityVerificationCoordinator | SourceRecord | E2 | 提供原始证据与许可范围（只读） | INV-01；§7.2；CL-11；BR-IMP-04 |
| M4 | 校验来源许可与公开范围：原值不得改写（E2） | IdentityVerificationCoordinator | IdentityVerificationCoordinator | E2 | 判定来源可用，声明不改写原值 | INV-01/INV-06；CL-11 |
| M5 | 追加身份证据项（姓名表达/机构及任职期/外部标识[来源/命名空间/可信度]/合作者/领域），逐项记来源与到达时间（E3） | IdentityVerificationCoordinator | Scholar | E3 | 保存可追溯的追加式证据，历史不覆盖 | FR15-S01 主流程 1–3；BR-325；ID-01/ID-02 |
| M6 | 登记私人联系方式剔除：不采集为消歧证据（A15） | IdentityVerificationCoordinator | AuditEvidence | A15 | 留痕剔除，保证私人联系方式不进证据集合 | FR15-S01 E1；BR-343/371；BR-IMP-11；NF06 |
| M7 | 姓名表达归一为比对用形式：对外展示姓名不变（E4） | IdentityVerificationCoordinator | IdentityVerificationCoordinator | E4 | 生成比对用形式，不替换展示名 | FR15-S02 主流程 1–2；BR-345；ID-01 |
| M8 | 登记别名候选并保留原表达与来源；无法归一者标待核验（E4；A14） | IdentityVerificationCoordinator | Scholar | E4/A14 | 登记别名候选或待核验标记 | FR15-S02 主流程 3–4、A1–A3、E4；ID-04 |
| M9 | 查询该署名×成果的既有关系状态（含已排除/争议）（E5） | IdentityVerificationCoordinator | Authorship | E5 | 回答是否已有关系及状态 | `AC-IMPL-FR15-04`；§6.4；FR15-S09 E1 |
| M10 | 返回既有关系状态与既有证据引用 | Authorship | IdentityVerificationCoordinator | E5 | 提供判定所需既有事实 | 分析推导 |
| M11 | 已排除关系不自动恢复：保持已排除（A7） | IdentityVerificationCoordinator | IdentityVerificationCoordinator | A7 | 拒绝恢复，保持最高优先级状态 | `AC-IMPL-FR15-04`；BR-356；SRS §11.3 |
| M12 | 记录被拒绝的状态变更尝试（来源/时间/原状态）（A7） | IdentityVerificationCoordinator | AuditEvidence | A7 | 留痕非法状态变更尝试 | FR15-S09 E1；BR-381 |
| M13 | 返回“保持已排除，未恢复为自动关联”（A7） | IdentityVerificationCoordinator | IdentityVerificationUI | A7 | 返回保持结论，不显示成功变更 | 分析推导；NF01 |
| M14 | 按外部标识→姓名比对用形式→机构/合作者召回候选档案（E6） | IdentityVerificationCoordinator | Scholar | E6 | 召回候选档案集合（召回≠判定） | FR15-S03 前置 2；FR15-S04 触发 |
| M15 | 返回候选档案集合（零/唯一/多个）与各档案证据项 | Scholar | IdentityVerificationCoordinator | E6 | 提供候选与逐项证据 | 分析推导 |
| M16 | 同名资格守卫：姓名相同或相似本身不构成同人证据（A3） | IdentityVerificationCoordinator | IdentityVerificationCoordinator | A3 | 拒绝以姓名作为判定依据 | BR-342/345；ID-01/ID-03；`AC-IMPL-FR15-01/03` |
| M17 | 建立新学者档案并保留原署名（E9） | IdentityVerificationCoordinator | Scholar | E9 | 新建档案，原署名保留 | FR15-S07 E1（按 FR15-S04 允许的无候选新建） |
| M18 | 建立一条关系（角色/顺序/来源证据），状态=待核验（E9） | IdentityVerificationCoordinator | Authorship | E9 | 建立待核验关系（归属已定、证据不足） | FR15-S07 E1；BR-352 |
| M19 | 记录“无档案新建＋待核验”依据（E9） | IdentityVerificationCoordinator | AuditEvidence | E9 | 留痕新建与状态依据 | BR-IMP-10 |
| M20 | 返回“该署名待核验：已建立关系，未定位既有档案” | IdentityVerificationCoordinator | IdentityVerificationUI | E9 | 返回待核验结论 | `AC-IMPL-FR03-02` |
| M21 | 保守判定守卫：条件（a）同类型外部标识完全一致，或（b）机构一致∧合作者∧领域支持；规则未批准不得执行（E7） | IdentityVerificationCoordinator | IdentityVerificationCoordinator | E7 | 判定是否满足已批准判定规则；不满足转候选 | FR15-S03 主流程 2；ID-02；§6.3；§6.5；B-03 |
| M22 | 建立/更新一条关系：状态=自动关联，附证据引用（E8） | IdentityVerificationCoordinator | Authorship | E8 | 写入关系与自动关联状态 | FR15-S07 主流程 3；FR15-S09 A1；§6.4 |
| M23 | 幂等归并：同一学者×成果的重复证据归入同一逻辑关系（ID-11） | IdentityVerificationCoordinator | IdentityVerificationCoordinator | E8 | 防止同一对产生第二条关系 | SPEC ID-11；FR15-S03 E3 |
| M24 | 记录自动关联依据（标识类型/规范值/证据清单/执行者类型=系统）（E8） | IdentityVerificationCoordinator | AuditEvidence | E8 | 留痕自动关联依据 | FR15-S12 A2；ID-10 |
| M25 | 返回“已建立自动关联关系”及依据 | IdentityVerificationCoordinator | IdentityVerificationUI | E8 | 返回自动关联结论 | 分析推导 |
| M26 | 生成身份候选（成员档案≥2＋逐项命中与冲突＋排序依据仅供查看＋候选规模）（E10） | IdentityVerificationCoordinator | IdentityCandidate | E10 | 保存候选组与逐项证据，候选≠合并 | FR15-S04 主流程 1–2、A1 |
| M27 | 生成归属候选（署名原值/顺序/角色＋候选学者集合＋逐项证据）（E10） | IdentityVerificationCoordinator | AttributionCandidate | E10 | 保存归属未定的候选 | `AC-IMPL-FR15-01`；FR15-S07 E1；B-05 |
| M28 | 候选过多时限制规模并标注“候选过多，需人工筛查”（A12） | IdentityVerificationCoordinator | IdentityCandidate | A12 | 记录规模与截断标注，不默认选一 | FR15-S04 E1；B-04 |
| M29 | 不建立确定关系、不静默分配：原署名保留（A3/A4/A5） | IdentityVerificationCoordinator | IdentityVerificationCoordinator | E10 | 拒绝任选候选，保持"未定" | BR-342/346；`AC-IMPL-FR15-01` |
| M30 | 记录候选生成（原因/候选数量/证据清单）（E10） | IdentityVerificationCoordinator | AuditEvidence | E10 | 留痕候选生成 | BR-IMP-10 |
| M31 | 返回“该署名待人工核验：公开归属结论不变” | IdentityVerificationCoordinator | IdentityVerificationUI | E10 | 返回待核验结论 | FR15-S04 主流程 4；`AC-IMPL-FR03-02` |
| M32 | 打开身份候选/归属争议处理项，查看成员与逐项证据（E11） | 管理员 | IdentityVerificationUI | E11 | 提交候选引用，请求证据与影响范围 | FR15-S05 主流程 1 |
| M33 | 请求候选详情与影响范围（候选引用＋已认证请求上下文） | IdentityVerificationUI | IdentityVerificationCoordinator | E11 | 启动核验读取 | 分析推导 |
| M34 | 校验账号状态与治理处置权限（E11） | IdentityVerificationCoordinator | User | E11 | 提供账号状态与治理处置权限事实 | NF03；FR14；`AC-IMPL-NF03-01`；§8 |
| M35 | 拒绝：无任何业务写入（A1） | IdentityVerificationCoordinator | IdentityVerificationUI | A1 | 拒绝，不返回候选证据 | NF03；SRS §8 |
| M36 | 显示拒绝，与越权/失败可区分（A1） | IdentityVerificationUI | 管理员 | A1 | 显示拒绝原因 | NF01；分析推导 |
| M37 | 记录受控拒绝审计，不含凭据（A1） | IdentityVerificationCoordinator | AuditEvidence | A1 | 留痕受控拒绝 | BR-IMP-10；INV-10 |
| M38 | 拒绝越权：候选与关系状态不变（A2） | IdentityVerificationCoordinator | IdentityVerificationUI | A2 | 拒绝越权读取/判定 | FR14；NF03；FR15-S05 权限规则 |
| M39 | 显示越权结果（A2） | IdentityVerificationUI | 管理员 | A2 | 显示越权结论 | 分析推导 |
| M40 | 记录受控越权尝试（A2） | IdentityVerificationCoordinator | AuditEvidence | A2 | 留痕越权尝试 | BR-IMP-10 |
| M41 | 读取候选成员/逐项命中与冲突/排序依据/状态版本（E11） | IdentityVerificationCoordinator | IdentityCandidate | E11 | 提供候选内容与状态版本 | FR15-S05 主流程 1、E1 |
| M42 | 读取候选档案的姓名表达/机构任职期/外部标识/合作者/领域证据与合并链（E11） | IdentityVerificationCoordinator | Scholar | E11 | 提供档案证据与合并链 | FR15-S01；FR15-S03 E1；BR-347 |
| M43 | 读取受影响关系集合（归属/角色/顺序/状态）（E11） | IdentityVerificationCoordinator | Authorship | E11 | 提供受影响关系清单 | FR15-S10 主流程 1 |
| M44 | 只读读取受影响成果（类型/公开状态）以计算影响范围（E11） | IdentityVerificationCoordinator | WorkEntity | E11 | 提供成果类型与公开状态（只读） | INV-02/04；BR-341 |
| M45 | 返回候选证据、影响范围（门户/成果/统计/网络）与可判定动作 | IdentityVerificationCoordinator | IdentityVerificationUI | E11 | 返回核验视图数据 | FR15-S05/S10 主流程 1 |
| M46 | 提交本人相关说明与佐证材料（提交者＋候选引用）（E12） | 相关学者 | IdentityVerificationUI | E12 | 提供说明与材料 | FR15-S05 权限规则；FR15-S04 A3 |
| M47 | 登记说明与材料（提交者＋候选引用）（E12） | IdentityVerificationUI | IdentityVerificationCoordinator | E12 | 接收材料并转登记 | 分析推导 |
| M48 | 登记材料与可见范围：提交者＋有权审核者（E12） | IdentityVerificationCoordinator | ReviewEvidence | E12 | 保存材料与可见范围 | BR-IMP-11；NF06；`AC-IMPL-NF06-01` |
| M49 | 记录材料登记：不改关系状态、不构成本人确认（A18） | IdentityVerificationCoordinator | AuditEvidence | A18 | 留痕材料登记并声明性质 | `AC-IMPL-FR15-03`；ID-06/ID-10 |
| M50 | 返回“材料已登记，等待有权审核者判定”（E12） | IdentityVerificationCoordinator | IdentityVerificationUI | E12 | 返回登记结论 | 分析推导 |
| M51 | 提交判定（确认同一人/确认非同一人/标记争议/请求补充证据）与理由（E13） | 管理员 | IdentityVerificationUI | E13 | 提交判定与理由 | FR15-S05 主流程 2 |
| M52 | 提交判定（候选引用＋判定类型＋理由＋已认证上下文）（E13） | IdentityVerificationUI | IdentityVerificationCoordinator | E13 | 转交判定内容 | 分析推导 |
| M53 | 读取状态版本与既有判定（A10） | IdentityVerificationCoordinator | IdentityCandidate | A10 | 提供状态版本与既有判定 | FR15-S05 E1 |
| M54 | 复核守卫：权限有效∧理由完整∧仍待核验∧版本未变（A10） | IdentityVerificationCoordinator | IdentityVerificationCoordinator | E14 | 判定判定是否可执行 | FR15-S05 E1/E3；BR-382；INV-10 |
| M55 | 拒绝并返回当前状态与既有判定（A10） | IdentityVerificationCoordinator | IdentityVerificationUI | A10 | 拒绝覆盖已有判定 | FR15-S05 E1 |
| M56 | 显示“状态已变化”与当前状态（A10） | IdentityVerificationUI | 管理员 | A10 | 显示当前状态 | NF01；分析推导 |
| M57 | 记录被拒绝的重复判定 | IdentityVerificationCoordinator | AuditEvidence | A10 | 留痕被拒绝的判定 | BR-IMP-10；BR-382 |
| M58 | 保持待核验并记录补充说明与请求（A8） | IdentityVerificationCoordinator | IdentityCandidate | A8 | 保持待核验，登记请求 | FR15-S05 A1 |
| M59 | 记录补充证据请求（A8） | IdentityVerificationCoordinator | AuditEvidence | A8 | 留痕补充证据请求 | BR-IMP-10 |
| M60 | 返回“候选仍待核验：新证据到达时更新”（A8） | IdentityVerificationCoordinator | IdentityVerificationUI | A8 | 返回仍待核验 | FR15-S05 A1；FR15-S04 E4 |
| M61 | 候选状态=争议中（A9） | IdentityVerificationCoordinator | IdentityCandidate | A9 | 置争议状态 | FR15-S05 A2 |
| M62 | 相关关系状态=争议：保留双方证据（A9） | IdentityVerificationCoordinator | Authorship | A9 | 置关系争议并保留证据 | BR-348；§6.4 |
| M63 | 记录争议登记：提出方/证据/时间；争议期间禁止自动合并与自动关联（A9） | IdentityVerificationCoordinator | AuditEvidence | A9 | 留痕争议与禁止自动变更 | FR15-S05 A2；BR-348 |
| M64 | 返回“已标记争议：公开显示争议提示，不进入统计与合作边”（A9） | IdentityVerificationCoordinator | IdentityVerificationUI | A9 | 返回争议结论 | `AC-IMPL-FR12-04`；`AC-IMPL-FR03-02` |
| M65 | 候选状态=已排除（保持独立）并记录排除依据（E15） | IdentityVerificationCoordinator | IdentityCandidate | E15 | 置已排除并记依据 | FR15-S05 主流程 4 |
| M66 | 关闭该档案组合：后续不再自动生成同一组合（除出现新证据）（E15） | IdentityVerificationCoordinator | IdentityVerificationCoordinator | E15 | 关闭组合并约束后续生成 | FR15-S05 主流程 4、E2 |
| M67 | 记录排除判定（依据/操作者/时间）（E15） | IdentityVerificationCoordinator | AuditEvidence | E15 | 留痕排除判定 | BR-381 |
| M68 | 返回“两档案保持独立，候选已关闭”（E15） | IdentityVerificationCoordinator | IdentityVerificationUI | E15 | 返回保持独立结论 | 分析推导 |
| M69 | 合并守卫：证据相容∧无部分合并∧写入完整，失败整体回滚（A11） | IdentityVerificationCoordinator | IdentityVerificationCoordinator | E15 | 判定合并是否可完整执行 | FR15-S03 E4；FR15-S05 E3；BR-381 |
| M70 | 合并档案：姓名表达/机构证据/外部标识/合作者证据取并集，被合并档案保留合并链（E15） | IdentityVerificationCoordinator | Scholar | E15 | 执行合并，证据并集与合并链 | FR15-S03 主流程 3；BR-347 |
| M71 | 迁移关系并消除同一成果同一角色的重复条目，保留双方证据（A6） | IdentityVerificationCoordinator | Authorship | A6 | 迁移关系并去重，保留证据 | FR15-S03 E3；SPEC ID-11 |
| M72 | 候选状态=已确认（合并）（E15） | IdentityVerificationCoordinator | IdentityCandidate | E15 | 置已确认 | FR15-S05 主流程 3 |
| M73 | 记录合并判定（依据/操作者/时间/关键变化）（E15） | IdentityVerificationCoordinator | AuditEvidence | E15 | 留痕合并判定 | BR-381/382 |
| M74 | 返回“已合并为一个档案：别名与关系数量符合预期”（E15） | IdentityVerificationCoordinator | IdentityVerificationUI | E15 | 返回合并结果 | 分析推导 |
| M75 | 打开归属候选并提交处置（确认归属/改派/排除关系/拆分档案）与理由（E13） | 管理员 | IdentityVerificationUI | E13 | 提交归属处置与理由 | FR15-S10 主流程 1–2 |
| M76 | 提交归属处置（归属候选引用＋关系引用＋处置类型＋理由＋已认证上下文）（E13） | IdentityVerificationUI | IdentityVerificationCoordinator | E13 | 转交处置内容 | 分析推导 |
| M77 | 读取署名原值/顺序/角色/候选学者/逐项证据/状态版本（E13） | IdentityVerificationCoordinator | AttributionCandidate | E13 | 提供归属候选内容与版本 | FR15-S10 主流程 1；`AC-IMPL-FR15-01` |
| M78 | 只读确认既有授权与被授权主体：不转移、不撤销（A13） | IdentityVerificationCoordinator | ScholarAuthorization | A13 | 回答是否已认领与被授权主体（只读） | BR-349；FR15-S05 E4；ID-12 |
| M79 | 处置守卫：权限∧理由∧版本未变∧依据齐备∧不写本人确认；失败整体回滚（E14；A11） | IdentityVerificationCoordinator | IdentityVerificationCoordinator | E14 | 判定处置可执行性与写入边界 | ID-06/ID-10；FR15-S10 E1/E2；INV-10 |
| M80 | 记录冲突与双方依据，转更高权限确认（A17） | IdentityVerificationCoordinator | AuditEvidence | A17 | 留痕冲突与双方依据 | FR15-S10 E2 |
| M81 | 返回“不覆盖既有判定：待更高权限确认”（A17） | IdentityVerificationCoordinator | IdentityVerificationUI | A17 | 返回不覆盖结论 | FR15-S10 E2 |
| M82 | 记录“管理权不变、需生成认领冲突处理项”，队列属 FR14/UC-17（A13） | IdentityVerificationCoordinator | AuditEvidence | A13 | 留痕授权不变与冲突处理项需求 | BR-349；FR14 |
| M83 | 返回“管理权不转移/不撤销，冲突处理项转后台待办”（A13） | IdentityVerificationCoordinator | IdentityVerificationUI | A13 | 返回授权不变结论 | BR-349 |
| M84 | 建立/更新关系：归属=确认学者，状态=自动关联，附独立管理员审核证据（E16） | IdentityVerificationCoordinator | Authorship | E16 | 写入归属与自动关联状态（不写本人确认） | `AC-IMPL-FR15-02/03`；§6.4 `ON_ADMIN_REVIEW`；ID-06 |
| M85 | 候选状态=已确认归属并记录依据（E15） | IdentityVerificationCoordinator | AttributionCandidate | E15 | 置已确认归属 | FR15-S10 主流程 3 |
| M86 | 记录归属确认（前后归属/依据/操作者/时间）（E18） | IdentityVerificationCoordinator | AuditEvidence | E18 | 留痕前后归属 | BR-IMP-10；BR-357 |
| M87 | 返回“归属已确认：门户与统计采用生效关系”（E15） | IdentityVerificationCoordinator | IdentityVerificationUI | E15 | 返回归属确认结论 | `AC-IMPL-FR15-02` |
| M88 | 仅本关系状态=已排除（E16） | IdentityVerificationCoordinator | Authorship | E16 | 只终止本条关系 | FR15-S11；BR-359；ID-09 |
| M89 | 核对：成果实体/全部来源/其他合法关系保留；已排除不得被自动恢复（BR-UC10-11） | IdentityVerificationCoordinator | IdentityVerificationCoordinator | E15 | 核对不删除与不恢复约束 | FR15-S11；`AC-IMPL-FR15-04`；BR-356 |
| M90 | 候选状态=已排除并记录依据（E15） | IdentityVerificationCoordinator | AttributionCandidate | E15 | 置候选已排除 | FR15-S10 主流程 3 |
| M91 | 记录排除（前后状态/依据/操作者/时间）（E18） | IdentityVerificationCoordinator | AuditEvidence | E18 | 留痕排除前后状态 | BR-IMP-10 |
| M92 | 返回“仅本关系排除：成果与其他合法关系保留”（E15） | IdentityVerificationCoordinator | IdentityVerificationUI | E15 | 返回排除范围结论 | FR15-S11 |
| M93 | 拆分为两个档案：别名与证据按来源分别归属，保留合并链（E15） | IdentityVerificationCoordinator | Scholar | E15 | 执行拆分并保留合并链 | FR15-S10 主流程 3、A2；BR-347 |
| M94 | 按证据恢复各自关系；无法确定的关系置待核验，不擅自分配（A16） | IdentityVerificationCoordinator | Authorship | A16 | 恢复关系或置待核验 | FR15-S10 A2；BR-358 |
| M95 | 记录拆分（依据/操作者/时间/无法确定项）（E18） | IdentityVerificationCoordinator | AuditEvidence | E18 | 留痕拆分与不确定项 | BR-381 |
| M96 | 返回“拆分完成：无法确定归属的关系为待核验”（E15） | IdentityVerificationCoordinator | IdentityVerificationUI | E15 | 返回拆分与待核验项 | FR15-S10 A2 |
| M97 | 请求按生效关系更新参与展示：不改共享字段、不删除来源（E17） | IdentityVerificationCoordinator | WorkEntity | E17 | 接收参与展示更新请求（不写共享字段） | BR-341；INV-02/INV-04；FR15-S11 |
| M98 | 请求更新相关学者门户的归属状态与公开投影（E17） | IdentityVerificationCoordinator | Scholar | E17 | 更新门户归属/待核验状态投影，不含档案展示字段 | FR15-S12；NF05 |
| M99 | 声明统计/FR12 基础网络/IN01 论文路径按同一生效关系重算，并逐视图记录传播时间（E17） | IdentityVerificationCoordinator | IdentityVerificationCoordinator | E17 | 声明同版本与传播记录 | `AC-IMPL-NF05-01/02`；FR15-S12；BR-360 |
| M100 | 记录生效时刻、影响范围与传播结果；未生效时提示管理员（E18） | IdentityVerificationCoordinator | AuditEvidence | E18 | 留痕生效与传播结果 | BR-IMP-10；`AC-IMPL-FR15-05` |
| M101 | 返回判定结论、影响范围与“是否已生效”（E18） | IdentityVerificationCoordinator | IdentityVerificationUI | E18 | 返回结果与生效状态 | FR15-S12 |
| M102 | 显示结论、影响范围与未生效提示（如有）（E18） | IdentityVerificationUI | 管理员 | E18 | 显示结论与未生效提示 | FR15-S12 权限规则 |
| M103 | 显示本人门户状态说明：不暴露他人私人信息与材料原件（E18） | IdentityVerificationUI | 相关学者 | E18 | 显示本人可读的状态说明 | FR15-S04 A3/E3；BR-IMP-11；NF06 |

## 3. 事件、分支与消息对照

| 事件/分支 | 消息 | 关键守卫 | 可观察结果 |
|---|---|---|---|
| E1 交付与启动 | M1–M2 | — | 进入身份／归属处理 |
| E2 只读取证 | M3–M4 | 来源许可可用∧原值不改写 | 原值只读确认 |
| E3 证据追加 | M5 | 逐项来源与到达时间 | 证据集合＋n，历史不变 |
| A15 私人联系方式 | M6 | 不采集 | 剔除登记，证据集合不含该字段 |
| E4/A14 姓名归一 | M7–M8 | 归一后非空 | 比对用形式／别名候选／待核验 |
| E5/A7 既有状态 | M9–M13 | 状态≠已排除 | 已排除保持并留痕 |
| E6 召回 | M14–M15 | 按标识→姓名→机构/合作者 | 候选集合（零/唯一/多个） |
| A3 仅同名 | M16 | 姓名不构成证据 | 只能进入候选路径 |
| E7 保守判定 | M21 | （a）∨（b）∧规则已批准 | 允许定位唯一档案，否则转候选 |
| E8 自动关联 | M22–M25 | 幂等：同一对一条关系 | 关系状态=自动关联 |
| E9 无档案新建 | M17–M20 | 证据不足且无候选 | 新档案＋关系待核验 |
| E10 候选 | M26–M31 | 不建立确定关系 | 身份候选／归属候选，公开结论不变 |
| A12 候选过多 | M28 | 规模上限（B-04） | 截断标注，不默认选一 |
| E11 打开核验 | M32–M34、M41–M45 | 账号可用∧治理处置权限 | 证据＋影响范围可见 |
| A1 未认证/停用 | M35–M37 | 账号可用 | 拒绝且无写入 |
| A2 无处置权限 | M38–M40 | 具备治理处置权限 | 拒绝越权 |
| E12/A18 材料登记 | M46–M50 | 提交者已授权（BR-IMP-05） | 材料登记，不改状态、非本人确认 |
| E13 提交判定 | M51–M52 | 判定类型合法∧理由必填 | 进入复核 |
| E14/A10 复核 | M53–M57 | 版本未变∧仍待核验 | 执行或拒绝覆盖 |
| A8 补充证据 | M58–M60 | — | 仍待核验 |
| A9 争议 | M61–M64 | — | 候选/关系=争议，禁止自动变更 |
| E15 确认非同一人 | M65–M68 | 有处置权限∧版本未变 | 保持独立、组合关闭 |
| E15/A6/A11 合并 | M69–M74 | 证据相容∧无部分合并 | 一个档案、证据并集、关系不重复 |
| E13 归属处置 | M75–M77 | — | 归属候选内容可见 |
| A13 已认领门户 | M78、M82–M83 | 授权只读 | 管理权不变、冲突处理项转待办 |
| E14/A17 冲突判定 | M79–M81 | 无并发冲突 | 不覆盖、转更高权限 |
| E16 确认归属 | M84–M87 | 不写本人确认 | 关系=自动关联＋独立审核证据 |
| E16 排除 | M88–M92 | 仅本关系 | 已排除，成果/来源/他人关系保留 |
| E15/A16 拆分 | M93–M96 | 证据可分辨 | 两档案恢复，无法确定置待核验 |
| E17 传播 | M97–M99 | 生效关系唯一 | 门户/统计/网络同版本 |
| E18 留痕与结果 | M86、M91、M95、M100–M103 | 留痕成功才报成功 | 结论＋未生效提示＋分角色可见 |

## 4. 完整性与一致性核对

| 检查 | 结果 | 说明 |
|---|---|---|
| 消息编号 | M1–M103 连续、无缺号、无重复 | 脚本核对（08 §4.4） |
| 每条消息有发送者/接收者/事件/职责/依据 | 是 | 表 2 全列齐备 |
| 每个参与类至少收到一条消息 | 是 | UI：M1/M2/M32/M33/M46/M47/M51/M52/M75/M76；Ctl：M2；User：M34；ScholarAuthorization：M78；SourceRecord：M3；Scholar：M5/M8/M14/M17/M42/M70/M93/M98；IdentityCandidate：M26/M28/M41/M53/M58/M61/M65/M72；AttributionCandidate：M27/M77/M85/M90；Authorship：M9/M18/M22/M43/M62/M71/M84/M88/M94；WorkEntity：M44/M97；ReviewEvidence：M48；AuditEvidence：M6/M12/M19/M24/M30/M37/M40/M49/M57/M59/M63/M67/M73/M80/M82/M86/M91/M95/M100 |
| 是否出现“仅姓名相同即合并” | 否（有反证守卫） | M16 拒绝；M21 需（a)/(b) 且规则已批准；A3 |
| 是否出现“证据不足仍建立确定归属” | 否 | M29 明确不建立确定关系；A3/A4/A5 |
| 是否出现“合并档案时丢失证据/别名” | 否 | M70 证据并集、保留合并链；M71 保留双方证据 |
| 是否出现“合并后重复关系” | 否 | M71；M23 幂等归并 |
| 是否出现“已排除被自动恢复” | 否（有反证消息） | M11–M13；M89 |
| 是否出现写 `本人确认` 的消息 | 否 | M84 只写 `自动关联`＋独立审核证据；M49 声明材料不构成本人确认；M79 守卫“不写本人确认” |
| 是否出现“管理员审批即本人确认” | 否 | 同上；`AC-IMPL-FR15-03/06` |
| 是否出现“一次判定扩散到其余 N 条关系” | 否 | M84/M88 只作用于本条关系；无批量状态写入消息 |
| 是否出现删除成果/来源/他人关系 | 否 | M88/M89 明确保留；无删除消息 |
| 是否出现成果去重/字段清洗/版本归并 | 否 | 无 `WorkVersion`/`DedupCandidate`/`CleanRecord` 消息；M97 不改共享字段；BR-341 |
| 是否出现认领审核/授权写入 | 否 | M78 只读；03 §4 拒绝 `ClaimApplication`/`Auth` 写消息 |
| 是否出现普通主页/UserProfile 消息 | 否 | 03 §4 拒绝 `UserProfile` |
| 是否出现本人确认/补录/纠错申请 | 否 | 属 UC-08；A19 边界 |
| 是否出现合作边/最短路径计算 | 否 | M99 只声明派生结果采用同一生效关系；建边属 UC-14/UC-15 |
| 是否出现采集/重试/增量识别 | 否 | 只有 M1 交付（外部输入） |
| 是否出现具体阈值/权重/时限数值 | 否 | 全部标 `待确认`（B-02/B-03/B-04）；图例声明 |
| 是否把拒绝、越权、失败、待核验、争议、已生效分开表达 | 是 | M35/M38（拒绝）、M55（版本冲突）、M31/M60（待核验）、M61–M64（争议）、M84–M96（生效）、M101（是否已生效） |
| 是否引入实现技术 | 否 | 只有分析职责与业务消息 |

## 5. 与图的一致性

`DGM-SEQ-15` 的 103 条消息与本表逐条对应；图中分组为“署名归属与证据（M1–M31）→ 管理员核验身份候选（M32–M74）→ 归属候选判定与纠正（M75–M96）→ 生效传播与结果（M97–M103）”。图中不画 Actor 为系统类，不画 `UserProfile`、`ClaimApplication`、`ScholarChangeRequest`、`CorrectionRequest`、`WorkVersion`、`DedupCandidate`、`CollaborationEdge`、`PendingTodo` 消息；`A19`（认领/档案维护/主页编辑/去重/本人确认）与 `A20`（留痕失败）以图例表达为范围边界与顺序约束。
