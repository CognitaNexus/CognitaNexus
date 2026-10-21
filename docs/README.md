# CognitaNexus 文档导航（单一入口）

先读需求报告 V2.1（2026-10-20 最终基线），再读唯一 69 AC 权威 SRS V2.2，最后将核心 SPEC 与 A1c/规模对齐说明结合阅读。**S01 已冻结；69 AC 为唯一验收基线。** 文档导航不授权修改需求或跳过变更评审。

| 类别 | 文档 | 版本 | 状态 | 用途 | 责任模块 |
| --- | --- | --- | --- | --- | --- |
| A Current | [需求主报告](requirements/学术成果分享平台需求分析与可行性报告.md) | V2.1 / 2026-10-20 | S01最终基线 | 上位需求语义、A1c范围与规模 | 两组负责人 |
| A Current | **[共同 SRS V2.2](srs/CognitaNexus-Shared-SRS-V2.2-69AC.md)** | V2.2 / 2026-10-20 | **S01 FROZEN — 69 AC** | 唯一权威验收基线、A1c/规模同步 | 两组负责人 |
| A Current | [核心 SPEC](spec/CognitaNexus-Core-SPEC-V1.0.md) · [Word](spec/CognitaNexus-Core-SPEC-V1.0.docx) | V1.0 / 2026-10-20 aligned | 69 AC aligned；未实测 | 清洗、去重、消歧、检索、网络边界 | 01/02/03及消费模块 |
| A Current | [SPEC 工程对齐说明](spec/implementation-alignment-V1.1-RC.md) | V1.1 RC | 未签认，非 Frozen | 限定旧 SPEC 的当前工程边界 | 两组/核心规则负责人 |
| B Supporting | [模块导航](srs/modules/README.md) · [SRS 编写规范](srs/CognitaNexus_SRS编写与需求建模规范_V1.0.md) | 正文版本 / V1.0 | 原模块候选/编写规则 | 六人分工与详细来源 | 成员01～06 |
| B Supporting | [SPEC 追踪](spec/spec-traceability.md) · [采集追踪](srs/modules/collection-update-traceability.md) | 原快照 | 旧编号辅助，不代表验收 | 规则与 AC 来源 | 对应模块 |
| B Supporting | [124/287 历史处理表](srs/integration/engineering-scope-trace-v1.1-rc.md) · [JSON](srs/integration/engineering-scope-trace-v1.1-rc.json) | V1.1 RC | 历史来源；非当前AC等价 | 旧124子需求/287 AC来源溯源 | 双组整合 |
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

## 目录维护与工具入口

业务文档按 `requirements / srs / spec / analysis / uml / reports` 分工，沿用稳定路径。工具实现按 `scripts/docs/checks/`（当前只读检查）和 `scripts/docs/legacy/`（历史重建）分组，旧脚本路径保留兼容入口。统一检查运行 `make check-docs`；构建依赖与副作用见[工具说明](../scripts/docs/README.md)，本次目录调整与验证见[结构整理记录](reports/document-management/structure-refactor-2026-10-21.md)。

## 2026-10-20 S01/S02 课程分析入口

- [课程要求与交付追踪矩阵](analysis/课程要求与交付追踪矩阵.md)
- [S01 架构分析（已冻结）](analysis/stage-01/README.md) · [冻结记录](analysis/stage-01/09-S01冻结记录.md)
- [S02 需求分析（用例分析）](analysis/stage-02/README.md)
- [69 AC 权威基线](srs/integration/69-ac-authoritative-baseline.md) · [版本映射](srs/integration/69-ac-version-mapping.md) · [双向追踪](analysis/stage-02/03-AC-UC-分析模型追踪.md) · [机器账本](srs/integration/69-ac-baseline.json)
- [合作图SPEC](spec/FR12-IN01-合作图与最短路径规范.md)、[NF测量方案](spec/NF-验收测量方案.md)、[数据源整改](requirements/四类成果数据源调研-整改版.md)。

**69 AC为唯一权威验收基线；46/59 AC与旧V1.1 SRS仅历史参考。** 所有运行测试仍为NOT RUN，文档统一不等于系统PASS。
