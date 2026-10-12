# 项目文档导航

## 文档清单

| 阶段 | 文档 | 当前状态 |
|---|---|---|
| 需求调研 | [需求分析与可行性报告](requirements/学术成果分享平台需求分析与可行性报告.md) | 正文 V1.9，待小组评审；当前需求依据 |
| 调研依据 | [调研与可行性论证附录](requirements/学术成果分享平台调研与可行性论证附录.md) | 辅助追溯；与基线不一致时以基线为准 |
| SRS 规范 | [SRS 编写与需求建模规范](srs/CognitaNexus_SRS编写与需求建模规范_V1.0.md) | V1.0 |
| 模块 SRS | [六人模块导航](srs/modules/README.md) | Review Candidate，待联合评审 |
| **现行双组 SRS 工程候选** | **[V1.1 实施版 Markdown](srs/CognitaNexus-Shared-SRS-V1.1-Implementation-RC.md)** · [Word](srs/CognitaNexus-Shared-SRS-V1.1-Implementation-RC.docx) | Review Candidate，唯一工程建议主稿；待双组签认，未冻结 |
| 历史汇编 | [先前双组 V1.0 长版](srs/CognitaNexus-Shared-SRS-V1.0-RC.md) · [六人稿](srs/CognitaNexus-SRS-V1.0.md) | 来源/审计，不再并行作为实施规范 |
| 工程可行性与范围 | [双组差异/12项取舍/工期](reports/srs/engineering-feasibility-scope-review-2026-10-12.md) · [124+287来源决策](srs/integration/engineering-scope-trace-v1.1-rc.md) | 工程建议，待双组确认 |
| 核心 SPEC | [候选稿](spec/CognitaNexus-Core-SPEC-V1.0.md) · [追踪](spec/spec-traceability.md) · [审查报告](spec/spec-review-report.md) | Review Candidate；规则未冻结 |
| UML | [总体用例图图源](uml/usecase/overall-usecase.puml) · [采集](uml/collection/) · [认领](uml/claim/) · [治理](uml/governance/) · [统计](uml/analytics/) | `.puml` 为维护源；生成图在对应目录 |
| 评审 | [跨模块联合审查草案](reports/srs/joint-review-draft-2026-10-11.md) | 待决记录，不代表会议决议 |

当前 V1.1 Implementation RC 是工程建议的单一实施阅读入口；旧 V1.0 514 页汇编为历史追溯，不再并列作为当前需求指令。[全局编号表](srs/global-id-registry.md)及[整改验证报告](reports/srs/integration-validation-2026-10-12.md)记录整改范围。候选版和追踪矩阵不代表规则冻结或业务验收通过。

## 目录职责

- `requirements/`：保存需求基线、调研报告及依据，不混入实现方案。
- `srs/`：保存 SRS 编写规范及后续共同 SRS。
- `srs/modules/`：按负责范围维护模块 SRS；本目录中的文档须标注状态和父需求。
- `srs/review/`、`reports/srs/`：保存待决规则与联合审查材料。
- `srs/incoming/`：既有成员原稿的本地路径；当前保留以免破坏正式文档中的来源引用，默认不纳入 Git。
- `spec/`：保存核心模块 SPEC 候选稿及其追踪、审查材料。
- `uml/`：按模块保存 PlantUML 图源及 `generated/` 生成图；截图与待验证工程包不代替图源。
- `.local/submissions/`：本机原始交付暂存，已被 Git 忽略。根目录原有 PNG 与 CodeArts ZIP 归档于 `uml-reference-2026-10-12/`，未宣称已通过平台验证。
- 后续需求分析、架构、构件及测试资产按实际授权建立，不提前填充空文件。

## 维护约定

1. 需求语义及优先级以 V1.9 为准；Optional 不得成为 Must 的隐式依赖。
2. 模块文档保留稳定子需求编号、业务规则、验收条件、待决事项和追踪关系。
3. 跨模块业务规则由联合评审决定；不在个人模块中改写其他成员负责的需求。
4. SRS 描述用户可观察的行为，不规定后续实现方案。
5. 移动文档时同步检查相对链接；移动基线文件不表示修改基线内容或升级版本。
6. 文档提交使用能说明范围的提交信息；基线更新、结构整理和模块规格应能从提交记录区分。

## 本次路径整理

| 原路径 | 新路径 |
|---|---|
| `docs/学术成果分享平台需求分析与可行性报告.md` | `docs/requirements/学术成果分享平台需求分析与可行性报告.md` |
| `docs/学术成果分享平台调研与可行性论证附录.md` | `docs/requirements/学术成果分享平台调研与可行性论证附录.md` |
| `docs/CognitaNexus_SRS编写与需求建模规范_V1.0.md` | `docs/srs/CognitaNexus_SRS编写与需求建模规范_V1.0.md` |

上述三份文件仅移动，正文不因目录整理而改变。搜索与推荐模块仍位于 `docs/srs/modules/search-recommendation.md`。

## 已知引用问题

- 基线§2.1附录链接已修复为实际路径，仅修复链接，未修改基线业务语义。
- 编写规范中使用带 `_V1.9` 后缀的基线示例名称，实际文件不带该后缀，以文件正文版本为准。
- 规范内的总 SRS、其他模块及图形路径是交付模板，不代表文件已经存在。
- 此前工作区已删除的两份 PDF 已作为独立提交记录保留；当前版本以 Markdown 文档为阅读入口，历史 PDF 可从 Git 历史追溯。
