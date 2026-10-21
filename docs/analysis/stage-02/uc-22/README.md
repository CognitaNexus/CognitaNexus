# UC-22 用例实现分析（编辑本人普通个人主页）

**版本：** V1.0（2026-10-21）
**输入基线：** `S01 FROZEN — 69 AC BASELINE`；[SRS V2.2（69 AC）](../../../srs/CognitaNexus-Shared-SRS-V2.2-69AC.md) FR03/FR04/FR06/FR07/NF03/NF06、BR-IMP-01/03/05/10/11、§7.2、§8、§12；[核心 SPEC V1.0](../../../spec/CognitaNexus-Core-SPEC-V1.0.md) §3 公共契约与 INV-08/INV-10、Q-SPEC-06（A06 字段公开）；教材第 5 章 §5.4–§5.5。
**范围：** 只分析 UC-22“编辑本人普通个人主页”。不分析 UC-03 公开主页浏览、UC-04 注册、UC-05/06 认领与审核、UC-07 已授权 Scholar 档案维护、UC-08 成果确认/纠错；不重构全局类图。
**状态：** UC-22 分析完成（STATIC COMPLETE，UML 2/2 渲染通过）；S02 总体状态与共享追踪文件由父 Agent 维护。

## 1. 交付内容

| 文件 | 内容 |
|---|---|
| [01-UC-22用例规约](01-UC-22-use-case-spec.md) | 参与者、目标、触发、前置/成功/失败后置、E1–E6 基本流、A1–A7 备选/异常、业务规则、状态、AC 覆盖、待确认 |
| [02-事件流与需求来源](02-event-flow-trace.md) | E1–E6/A1–A7 的逐项来源与证据等级；不纳入本 UC 的事件 |
| [03-BCE分析类识别](03-bce-identification.md) | 5 个候选类、逐类理由、拒绝候选、User / UserProfile / Scholar 三方边界 |
| [04-顺序图与逐消息职责](04-sequence-messages.md) | M1–M22 的发送者/接收者/事件步骤/职责/依据；越权/停用/字段越界/保存失败/待审版本分支 |
| [05-VOPC与关系依据](05-vopc.md) | 5 个参与类、关系与多重性理由、排除项；VOPC 只来自本 UC 顺序图 |
| [06-分析类定义](06-class-definition.md) | 每类 Do/Know、候选分析操作、业务属性、关系、分析机制（§5.5.1–§5.5.4） |
| [07-与全局模型差异](07-global-model-diff.md) | 与 `DGM-CLASS-01-analysis.puml` 的一致/命名/职责/关系差异与 R5 建议 |
| [08-自检与待评审](08-self-check.md) | 范围与基线检查、教材步骤对应、模型完整性、实际渲染/文档检查结果、B-01～B-08 |

## 2. UML

| 模型 | 源 | SVG | PNG |
|---|---|---|---|
| UC-22 本人主页编辑分析序列（22 条消息） | [`diagrams/DGM-SEQ-09-profile-edit.puml`](diagrams/DGM-SEQ-09-profile-edit.puml) | [`generated/DGM-SEQ-09-profile-edit.svg`](diagrams/generated/DGM-SEQ-09-profile-edit.svg) | [`generated/DGM-SEQ-09-profile-edit.png`](diagrams/generated/DGM-SEQ-09-profile-edit.png) |
| UC-22 VOPC（5 个参与类） | [`diagrams/DGM-VOPC-05-profile-edit.puml`](diagrams/DGM-VOPC-05-profile-edit.puml) | [`generated/DGM-VOPC-05-profile-edit.svg`](diagrams/generated/DGM-VOPC-05-profile-edit.svg) | [`generated/DGM-VOPC-05-profile-edit.png`](diagrams/generated/DGM-VOPC-05-profile-edit.png) |

## 3. 阅读顺序

先读 01 确认业务边界（谁可以写、可以写什么字段），再读 02 核对每个事件的证据；03 给出 5 个参与类与拒绝候选；04 只描述交互，05 只从 04 提取 VOPC；06 定义类的职责/属性/关系/机制；07 做全局比较并提出 R5 建议；08 列出实际执行结果与未决项。

## 4. 本轮的四个核心结论

1. **所有权来自 User，不是 Scholar 授权**：普通 UserProfile 由所属 User 直接编辑，服务端每次校验身份、账号状态与对象归属；前端隐藏入口不能代替拒绝（BR-IMP-05；`AC-IMPL-FR06-04`；SRS §8）。
2. **普通字段无需学术审核**：昵称/头像/简介/兴趣领域/展示设置由本人保存后按公共资料策略公开生效，不要求已认领 Scholar（`AC-IMPL-FR06-03`；FR06）。
3. **学术事实不在本 UC 写入**：重要已核验学术身份、机构、归属、来源事实由 Scholar 对象授权与审核流程办理；本 UC 只拒绝/转交，不写核验状态，也不产生待审版本覆盖公开版（FR06；`AC-IMPL-FR06-01/02`）。
4. **公开面最小化且结构不扩张**：公开投影只含明确允许公开的字段，私有联系方式、认证材料与凭据不进入（`AC-IMPL-FR06-05`；BR-IMP-11；INV-08）；编辑不创建第二个 UserProfile、不创建 Scholar/Claim，零成果仍显示空态（`AC-IMPL-FR07-03`、`AC-IMPL-FR03-03/04`）。

## 5. 本轮明确不做

- 不修改 SRS、SPEC、69 AC、S01 冻结材料、全局 UML 与其他 UC 目录；
- 不修改 `docs/analysis/stage-02/03-AC-UC-分析模型追踪.md` 等共享追踪文件，也不改 `automation/analysis-class-registry.md`（登记建议见 07）；
- 不把 `DGM-CLASS-01-analysis.puml` 当作本 UC VOPC；
- 不引入 Java/数据库/API/框架/部署设计；不把 `DGM-SEQ-04-profile.puml` 的合并序列当成本 UC 实现；
- 不把分析假设写成已批准业务规则：字段级清单、公共资料策略与待审版本口径均标 `待确认`（B-01/B-02/B-04）。
