# CognitaNexus 文档导航（单一入口）

先读需求 V1.9，再读唯一工程实施候选 SRS V1.1，最后将核心 SPEC V1.0 与 V1.1 对齐说明结合阅读。**Review Candidate / 未经两组正式签认 / 非 Frozen。** 本导航不授权进入下一阶段或变更需求。

| 类别 | 文档 | 版本 | 状态 | 用途 | 责任模块 |
| --- | --- | --- | --- | --- | --- |
| A Current | [需求主报告](requirements/学术成果分享平台需求分析与可行性报告.md) | V1.9 | 待小组评审 | 上位需求语义与范围 | 两组负责人 |
| A Current | **[共同 SRS](srs/CognitaNexus-Shared-SRS-V1.1-Implementation-RC.md)** · [Word](srs/CognitaNexus-Shared-SRS-V1.1-Implementation-RC.docx) | V1.1 | Review Candidate / 未经两组正式签认 / 非 Frozen | 唯一当前工程实施候选主稿；46 AC | 两组负责人 |
| A Current | [核心 SPEC](spec/CognitaNexus-Core-SPEC-V1.0.md) · [Word](spec/CognitaNexus-Core-SPEC-V1.0.docx) | V1.0 | Review Candidate，参数待评审 | 清洗、去重、消歧、检索规则；须配合下一项 | 01/02/03及消费模块 |
| A Current | [SPEC 工程对齐说明](spec/implementation-alignment-V1.1-RC.md) | V1.1 RC | 未签认，非 Frozen | 限定旧 SPEC 的当前工程边界 | 两组/核心规则负责人 |
| B Supporting | [模块导航](srs/modules/README.md) · [SRS 编写规范](srs/CognitaNexus_SRS编写与需求建模规范_V1.0.md) | 正文版本 / V1.0 | 原模块候选/编写规则 | 六人分工与详细来源 | 成员01～06 |
| B Supporting | [SPEC 追踪](spec/spec-traceability.md) · [采集追踪](srs/modules/collection-update-traceability.md) | 原快照 | 旧编号辅助，不代表验收 | 规则与 AC 来源 | 对应模块 |
| B Supporting | [124/287 当前处理表](srs/integration/engineering-scope-trace-v1.1-rc.md) · [JSON](srs/integration/engineering-scope-trace-v1.1-rc.json) | V1.1 RC | 父范围参考；非逐条等价 | 来源、处理、46 AC 候选落点 | 双组整合 |
| B Supporting | [全局 UC/BR/DGM](srs/global-id-registry.md) · [映射 JSON](srs/global-id-mapping.json) | V1.0 RC1 | 原六人编号与语义保留 | 旧号追踪；不覆盖 BR-IMP/AC-IMPL | 六人组 |
| B Supporting | [OvO 124 语义映射](srs/integration/ovo-semantic-map-v0.1.md) · [287 AC 原文](srs/integration/ovo-ac-lineage-v0.1.md) · [编号对照](srs/integration/subrequirement-crosswalk-2026-10-12.md) | V0.1 / 日期快照 | 早期辅助映射，非当前实施规则 | 保留来源及语义差异 | 双组整合 |
| B Supporting | [UML 图目录与阶段](uml/README.md) · [调研附录](requirements/学术成果分享平台调研与可行性论证附录.md) | 正文版本 | 建模及证据辅助 | 图源、生成物及调研 | 各模块 |
| C Review & Validation | [工程可行性与取舍](reports/srs/engineering-feasibility-scope-review-2026-10-12.md) · [双组一致性](reports/srs/cross-team-consistency-review-2026-10-12.md) | 2026-10-12 | Review Candidate，非决议签认 | 差异、范围与工期建议 | 双组联合评审 |
| C Review & Validation | [联合评审事项](reports/srs/joint-review-draft-2026-10-11.md) · [SPEC 审查](spec/spec-review-report.md) · [旧构建验证](reports/srs/integration-validation-2026-10-12.md) | 日期快照 | 历次证据；不代表本次 PASS | 待决及验证边界 | 原审查方 |
| C Review & Validation | [本次盘点和方案](reports/document-management/inventory-and-plan-2026-10-12.md) · [本次验证](reports/document-management/validation-2026-10-12.md) | 2026-10-12 | 配置管理审查 | 全部文件分类、保全和验证 | 文档管理员 |
| D Historical | [六人 SRS](srs/CognitaNexus-SRS-V1.0.md) · [Word](srs/CognitaNexus-SRS-V1.0.docx) · [双组长版](srs/CognitaNexus-Shared-SRS-V1.0-RC.md) · [Word](srs/CognitaNexus-Shared-SRS-V1.0-RC.docx) | V1.0 RC | Historical / 仅用于来源追踪，不作为当前开发基线 | 原需求/AC 全文保留 | 原编制方 |
| D Historical | [旧 Draft](srs/CognitaNexus-SRS-Draft.md) · [早期 SPEC](spec/core-rules-draft.md) · [Pi 复核](reports/srs/pi-srs-followup-review-2026-10-12.md) | 原快照 | Historical / 仅用于来源追踪，不作为当前开发基线 | 保留早期方案 | 原作者 |
| D Historical | [旧 AC 自动归并](srs/integration/shared-ac-resolution-v1.0-rc.md) · [跨组旧待决](srs/integration/decision-register-2026-10-12.md) · [六人整合决策](srs/review/integration-decisions-2026-10-12.md) | V1.0 / 日期快照 | Historical / 仅用于来源追踪，不作为当前开发基线 | 不改写旧裁决或组长确认的历史范围 | 原评审方 |
| E Tooling | [构建与检查](../scripts/docs/README.md) · [配置管理规范](configuration-management.md) | 工具/维护候选 | 无业务批准效力 | 命名、版本、生成、CI 与检查命令 | 文档维护者 |

完整逐文件索引及本机 incoming 原稿见[盘点](reports/document-management/inventory-and-plan-2026-10-12.md)。所有既有目录保持稳定；本地原稿不自动纳入 Git。历史文件正文中的“当前”指其原时间点，不赋予其当前工程效力。未取得课程任务书原文时，不凭模板推断阶段交付已完成。
