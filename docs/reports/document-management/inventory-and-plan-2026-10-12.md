# 文档盘点与结构整理方案（2026-10-12）

审计基点：`622be6e095cafa60bde8a6e3deb2785eb2796513`（fetch 后 main 与 origin/main 差异 0/0，工作区干净）。新分支 `codex/docs-configuration-20261012`。本报告为配置管理审查，不批准或冻结业务需求。

## 整理方案与文件范围

所有既有资产保留原位置；本次不建议立即迁移任何文件，不删除文件。修改 README、docs/README、模块 README、AGENTS 的入口和维护指引；修复工程评审报告的四处文件相对链接；新增本目录报告、配置规范、UML 和工具导航、只读验证及临时构建脚本。CI 取消写权限和自动提交，历史 V1.0 仅手动构建，V1.1 在 PR/main 变动时只生成 artifact。正式 Word、SRS、SPEC、历史追踪、UML 内容不覆盖。

A 表示当前阅读链，不表示 Approved；需求 V1.9 待小组评审，V1.1 为 **Review Candidate / 未经两组正式签认 / 非 Frozen**。核心 SPEC V1.0 必须结合 V1.1 对齐说明；旧正文的 V1.0 依据字段仅为历史来源。B 中的模块及旧编号登记保留其原候选身份，不并列作为当前工程指令。C 是证据快照，D 为 **Historical / 仅用于来源追踪，不作为当前开发基线。** E 的工具版本不赋予生成结果业务效力。版本以正文为准，表中“正文/来源快照”表示未统一声明版本，不擅造版本。

## 逐文件盘点（审计基点全部受控文档与工具）

| 类别 | 文档 | 版本 | 状态/当前效力 | 用途与保留理由 | 责任模块 |
| --- | --- | --- | --- | --- | --- |
| E Tooling | [.github/workflows/build-scoped-shared-srs.yml](../../../.github/workflows/build-scoped-shared-srs.yml) | 正文/来源快照 | 工具；不提供业务效力 | build-scoped-shared-srs；保留稳定引用与来源 | 文档维护者 |
| E Tooling | [.github/workflows/build-shared-srs-word.yml](../../../.github/workflows/build-shared-srs-word.yml) | 正文/来源快照 | 工具；不提供业务效力 | build-shared-srs-word；保留稳定引用与来源 | 文档维护者 |
| B Supporting | [.gitignore](../../../.gitignore) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | .gitignore；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [AGENTS.md](../../../AGENTS.md) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | CognitaNexus 项目级 AI 协作规范；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [README.md](../../../README.md) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | CognitaNexus；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/README.md](../../README.md) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 项目文档导航；保留稳定引用与来源 | 对应模块/文档维护者 |
| C Review & Validation | [docs/reports/srs/cross-team-consistency-review-2026-10-12.md](../srs/cross-team-consistency-review-2026-10-12.md) | 正文/来源快照 | 评审快照/待决；不代表签认或本次验证 | CognitaNexus × OvO 两组 SRS 完整性与一致性审查（联合评审候选）；保留稳定引用与来源 | 联合评审/原作者 |
| C Review & Validation | [docs/reports/srs/engineering-feasibility-scope-review-2026-10-12.md](../srs/engineering-feasibility-scope-review-2026-10-12.md) | 正文/来源快照 | 评审快照/待决；不代表签认或本次验证 | CognitaNexus × OvO 软件工程可行性与需求收敛评审（2026-10-12）；保留稳定引用与来源 | 联合评审/原作者 |
| C Review & Validation | [docs/reports/srs/implementation-scope-validation-2026-10-12.json](../srs/implementation-scope-validation-2026-10-12.json) | 正文/来源快照 | 评审快照/待决；不代表签认或本次验证 | 机器可读编号/追踪/检查数据：implementation-scope-validation-2026-10-12；保留稳定引用与来源 | 联合评审/原作者 |
| C Review & Validation | [docs/reports/srs/integration-coverage.json](../srs/integration-coverage.json) | 正文/来源快照 | 评审快照/待决；不代表签认或本次验证 | 机器可读编号/追踪/检查数据：integration-coverage；保留稳定引用与来源 | 联合评审/原作者 |
| C Review & Validation | [docs/reports/srs/integration-validation-2026-10-12.md](../srs/integration-validation-2026-10-12.md) | 正文/来源快照 | 评审快照/待决；不代表签认或本次验证 | CognitaNexus SRS一致性整改与交付验证；保留稳定引用与来源 | 联合评审/原作者 |
| C Review & Validation | [docs/reports/srs/integration-validation.json](../srs/integration-validation.json) | 正文/来源快照 | 评审快照/待决；不代表签认或本次验证 | 机器可读编号/追踪/检查数据：integration-validation；保留稳定引用与来源 | 联合评审/原作者 |
| C Review & Validation | [docs/reports/srs/joint-review-draft-2026-10-11.md](../srs/joint-review-draft-2026-10-11.md) | 正文/来源快照 | 评审快照/待决；不代表签认或本次验证 | CognitaNexus 六人组 SRS 跨模块联合审查（2026-10-11 · 待决草案）；保留稳定引用与来源 | 联合评审/原作者 |
| D Historical | [docs/reports/srs/pi-srs-followup-review-2026-10-12.md](../srs/pi-srs-followup-review-2026-10-12.md) | 正文/来源快照 | Historical / 仅用于来源追踪，不作为当前开发基线 | Pi SRS 整合结果独立复核与 OvO 跨组阻断项（2026-10-12）；保留稳定引用与来源 | 原编制方 |
| B Supporting | [docs/requirements/学术成果分享平台调研与可行性论证附录.md](../../requirements/学术成果分享平台调研与可行性论证附录.md) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 学术成果分享平台调研与可行性论证附录；保留稳定引用与来源 | 对应模块/文档维护者 |
| A Current | [docs/requirements/学术成果分享平台需求分析与可行性报告.md](../../requirements/学术成果分享平台需求分析与可行性报告.md) | V1.9 | 候选依据；Word 为对应生成副本 | 软件系统分析与设计作业；保留稳定引用与来源 | 两组/责任成员 |
| A Current | [docs/spec/CognitaNexus-Core-SPEC-V1.0.docx](../../spec/CognitaNexus-Core-SPEC-V1.0.docx) | V1.0 | 候选依据；Word 为对应生成副本 | 对应 Markdown 的 Word 交付副本；原样保留；保留稳定引用与来源 | 两组/责任成员 |
| A Current | [docs/spec/CognitaNexus-Core-SPEC-V1.0.md](../../spec/CognitaNexus-Core-SPEC-V1.0.md) | V1.0 | 候选依据；Word 为对应生成副本 | CognitaNexus 核心模块软件算法规格说明书（V1.0 · Review Candidate）；保留稳定引用与来源 | 两组/责任成员 |
| D Historical | [docs/spec/core-rules-draft.md](../../spec/core-rules-draft.md) | 正文/来源快照 | Historical / 仅用于来源追踪，不作为当前开发基线 | 核心模块 SPEC 规则初稿：清洗、去重、消歧、检索排序（Draft）；保留稳定引用与来源 | 原编制方 |
| A Current | [docs/spec/implementation-alignment-V1.1-RC.md](../../spec/implementation-alignment-V1.1-RC.md) | V1.1 RC | 候选依据；Word 为对应生成副本 | CognitaNexus Core SPEC × 双组 V1.1 实施范围对齐备忘录（RC）；保留稳定引用与来源 | 两组/责任成员 |
| C Review & Validation | [docs/spec/spec-review-report.md](../../spec/spec-review-report.md) | 正文/来源快照 | 评审快照/待决；不代表签认或本次验证 | 核心 SPEC 静态审查报告（2026-10-12，Review Candidate）；保留稳定引用与来源 | 联合评审/原作者 |
| B Supporting | [docs/spec/spec-traceability.md](../../spec/spec-traceability.md) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | CognitaNexus SPEC 追踪与复核索引（Review Candidate）；保留稳定引用与来源 | 对应模块/文档维护者 |
| D Historical | [docs/srs/CognitaNexus-SRS-Draft.md](../../srs/CognitaNexus-SRS-Draft.md) | 正文/来源快照 | Historical / 仅用于来源追踪，不作为当前开发基线 | CognitaNexus SRS 历史整合入口；保留稳定引用与来源 | 原编制方 |
| D Historical | [docs/srs/CognitaNexus-SRS-V1.0.docx](../../srs/CognitaNexus-SRS-V1.0.docx) | V1.0 | Historical / 仅用于来源追踪，不作为当前开发基线 | 对应 Markdown 的 Word 交付副本；原样保留；保留稳定引用与来源 | 原编制方 |
| D Historical | [docs/srs/CognitaNexus-SRS-V1.0.md](../../srs/CognitaNexus-SRS-V1.0.md) | V1.0 | Historical / 仅用于来源追踪，不作为当前开发基线 | CognitaNexus 软件需求规格说明书；保留稳定引用与来源 | 原编制方 |
| D Historical | [docs/srs/CognitaNexus-Shared-SRS-V1.0-RC.docx](../../srs/CognitaNexus-Shared-SRS-V1.0-RC.docx) | V1.0 | Historical / 仅用于来源追踪，不作为当前开发基线 | 对应 Markdown 的 Word 交付副本；原样保留；保留稳定引用与来源 | 原编制方 |
| D Historical | [docs/srs/CognitaNexus-Shared-SRS-V1.0-RC.md](../../srs/CognitaNexus-Shared-SRS-V1.0-RC.md) | V1.0 | Historical / 仅用于来源追踪，不作为当前开发基线 | CognitaNexus × OvO 共同软件需求规格说明书（V1.0 Review Candidate）；保留稳定引用与来源 | 原编制方 |
| A Current | [docs/srs/CognitaNexus-Shared-SRS-V1.1-Implementation-RC.docx](../../srs/CognitaNexus-Shared-SRS-V1.1-Implementation-RC.docx) | V1.1 RC | 候选依据；Word 为对应生成副本 | 对应 Markdown 的 Word 交付副本；原样保留；保留稳定引用与来源 | 两组/责任成员 |
| A Current | [docs/srs/CognitaNexus-Shared-SRS-V1.1-Implementation-RC.md](../../srs/CognitaNexus-Shared-SRS-V1.1-Implementation-RC.md) | V1.1 RC | 候选依据；Word 为对应生成副本 | CognitaNexus × OvO 工程可实施共同 SRS V1.1（Review Candidate）；保留稳定引用与来源 | 两组/责任成员 |
| B Supporting | [docs/srs/CognitaNexus_SRS编写与需求建模规范_V1.0.md](../../srs/CognitaNexus_SRS编写与需求建模规范_V1.0.md) | V1.0 | 详细规范/追踪/辅助；不得覆盖当前候选 | CognitaNexus SRS 编写与需求建模规范；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/srs/global-id-mapping.json](../../srs/global-id-mapping.json) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 机器可读编号/追踪/检查数据：global-id-mapping；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/srs/global-id-registry.json](../../srs/global-id-registry.json) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 机器可读编号/追踪/检查数据：global-id-registry；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/srs/global-id-registry.md](../../srs/global-id-registry.md) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | CognitaNexus 全局UC BR DGM编号注册表；保留稳定引用与来源 | 对应模块/文档维护者 |
| D Historical | [docs/srs/integration/decision-register-2026-10-12.md](../../srs/integration/decision-register-2026-10-12.md) | 正文/来源快照 | Historical / 仅用于来源追踪，不作为当前开发基线 | CognitaNexus × OvO 共同需求待决事项（2026-10-12）；保留稳定引用与来源 | 原编制方 |
| B Supporting | [docs/srs/integration/engineering-scope-trace-v1.1-rc.json](../../srs/integration/engineering-scope-trace-v1.1-rc.json) | V1.1 RC | 详细规范/追踪/辅助；不得覆盖当前候选 | 机器可读编号/追踪/检查数据：engineering-scope-trace-v1.1-rc；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/srs/integration/engineering-scope-trace-v1.1-rc.md](../../srs/integration/engineering-scope-trace-v1.1-rc.md) | V1.1 RC | 详细规范/追踪/辅助；不得覆盖当前候选 | CognitaNexus × OvO 工程收敛版全量旧编号处理表 V1.1 RC；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/srs/integration/ovo-ac-lineage-v0.1.md](../../srs/integration/ovo-ac-lineage-v0.1.md) | V0.1 | 详细规范/追踪/辅助；不得覆盖当前候选 | OvO 287 条验收条件来源与候选落点（V0.1）；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/srs/integration/ovo-semantic-decisions-v0.1.txt](../../srs/integration/ovo-semantic-decisions-v0.1.txt) | V0.1 | 详细规范/追踪/辅助；不得覆盖当前候选 | ovo-semantic-decisions-v0.1；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/srs/integration/ovo-semantic-map-v0.1.md](../../srs/integration/ovo-semantic-map-v0.1.md) | V0.1 | 详细规范/追踪/辅助；不得覆盖当前候选 | OvO 124 条子需求跨仓库语义映射（V0.1 提议）；保留稳定引用与来源 | 对应模块/文档维护者 |
| D Historical | [docs/srs/integration/shared-ac-resolution-v1.0-rc.json](../../srs/integration/shared-ac-resolution-v1.0-rc.json) | V1.0 | Historical / 仅用于来源追踪，不作为当前开发基线 | 机器可读编号/追踪/检查数据：shared-ac-resolution-v1.0-rc；保留稳定引用与来源 | 原编制方 |
| D Historical | [docs/srs/integration/shared-ac-resolution-v1.0-rc.md](../../srs/integration/shared-ac-resolution-v1.0-rc.md) | V1.0 | Historical / 仅用于来源追踪，不作为当前开发基线 | CognitaNexus × OvO 287 条 AC 逐条语义裁决与双向来源映射；保留稳定引用与来源 | 原编制方 |
| B Supporting | [docs/srs/integration/subrequirement-crosswalk-2026-10-12.md](../../srs/integration/subrequirement-crosswalk-2026-10-12.md) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | CognitaNexus × OvO 子需求编号对照清单（评审候选）；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/srs/modules/README.md](../../srs/modules/README.md) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | CognitaNexus 六人组 SRS 模块导航与统一编写口径（Draft）；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/srs/modules/admin-audit.md](../../srs/modules/admin-audit.md) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | CognitaNexus SRS · 成员06 · FR14（管理后台与审计）；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/srs/modules/analytics-graph.md](../../srs/modules/analytics-graph.md) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | CognitaNexus 学术成果分享平台 — 软件需求规格说明书；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/srs/modules/collection-update-traceability.md](../../srs/modules/collection-update-traceability.md) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 第②成员需求追踪矩阵（SRS V1.0）；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/srs/modules/collection-update.md](../../srs/modules/collection-update.md) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | CognitaNexus 软件需求规格说明书：数据采集与更新模块；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/srs/modules/governance-dedup.md](../../srs/modules/governance-dedup.md) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | CognitaNexus SRS · 成员03 · FR09（数据治理与归属）；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/srs/modules/nonfunctional.md](../../srs/modules/nonfunctional.md) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | CognitaNexus SRS · 成员06 · NF01～NF08（非功能需求）；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/srs/modules/scholar-claim.md](../../srs/modules/scholar-claim.md) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | CognitaNexus 软件需求规格说明书：学者门户、认领与维护；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/srs/modules/scholar-disambiguation.md](../../srs/modules/scholar-disambiguation.md) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | CognitaNexus SRS · 成员03 · FR15（数据治理与归属）；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/srs/modules/search-recommendation.md](../../srs/modules/search-recommendation.md) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | CognitaNexus 搜索与推荐模块软件需求规格说明书；保留稳定引用与来源 | 对应模块/文档维护者 |
| D Historical | [docs/srs/review/integration-decisions-2026-10-12.md](../../srs/review/integration-decisions-2026-10-12.md) | 正文/来源快照 | Historical / 仅用于来源追踪，不作为当前开发基线 | CognitaNexus 联合整合决策与业务边界；保留稳定引用与来源 | 原编制方 |
| C Review & Validation | [docs/srs/review/member03-governance-rules.md](../../srs/review/member03-governance-rules.md) | 正文/来源快照 | 评审快照/待决；不代表签认或本次验证 | FR09／FR15 公共规则、术语与接口边界（合并补丁）；保留稳定引用与来源 | 联合评审/原作者 |
| C Review & Validation | [docs/srs/review/member03-governance-usecases.md](../../srs/review/member03-governance-usecases.md) | 正文/来源快照 | 评审快照/待决；不代表签认或本次验证 | FR09／FR15 关键用例规格；保留稳定引用与来源 | 联合评审/原作者 |
| B Supporting | [docs/uml/analytics/DGM-ACTIVITY-02-metrics-refresh.puml](../../uml/analytics/DGM-ACTIVITY-02-metrics-refresh.puml) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | PlantUML 业务图源：DGM-ACTIVITY-02-metrics-refresh；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/analytics/DGM-ACTIVITY-03-collab-path.puml](../../uml/analytics/DGM-ACTIVITY-03-collab-path.puml) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | PlantUML 业务图源：DGM-ACTIVITY-03-collab-path；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/analytics/DGM-ACTIVITY-04-institution-ranking.puml](../../uml/analytics/DGM-ACTIVITY-04-institution-ranking.puml) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | PlantUML 业务图源：DGM-ACTIVITY-04-institution-ranking；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/analytics/DGM-STATE-03-metrics-visibility.puml](../../uml/analytics/DGM-STATE-03-metrics-visibility.puml) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | PlantUML 业务图源：DGM-STATE-03-metrics-visibility；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/analytics/DGM-USECASE-02-analytics.puml](../../uml/analytics/DGM-USECASE-02-analytics.puml) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | PlantUML 业务图源：DGM-USECASE-02-analytics；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/analytics/generated/DGM-ACTIVITY-02-metrics-refresh.svg](../../uml/analytics/generated/DGM-ACTIVITY-02-metrics-refresh.svg) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：DGM-ACTIVITY-02-metrics-refresh；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/analytics/generated/DGM-ACTIVITY-03-collab-path.svg](../../uml/analytics/generated/DGM-ACTIVITY-03-collab-path.svg) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：DGM-ACTIVITY-03-collab-path；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/analytics/generated/DGM-ACTIVITY-04-institution-ranking.svg](../../uml/analytics/generated/DGM-ACTIVITY-04-institution-ranking.svg) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：DGM-ACTIVITY-04-institution-ranking；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/analytics/generated/DGM-STATE-03-metrics-visibility.svg](../../uml/analytics/generated/DGM-STATE-03-metrics-visibility.svg) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：DGM-STATE-03-metrics-visibility；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/analytics/generated/DGM-USECASE-02-analytics.svg](../../uml/analytics/generated/DGM-USECASE-02-analytics.svg) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：DGM-USECASE-02-analytics；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/claim/DGM-ACTIVITY-01-claim-review.puml](../../uml/claim/DGM-ACTIVITY-01-claim-review.puml) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | PlantUML 业务图源：DGM-ACTIVITY-01-claim-review；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/claim/DGM-STATE-02-claim-request.puml](../../uml/claim/DGM-STATE-02-claim-request.puml) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | PlantUML 业务图源：DGM-STATE-02-claim-request；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/claim/generated/DGM-ACTIVITY-01-claim-review.svg](../../uml/claim/generated/DGM-ACTIVITY-01-claim-review.svg) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：DGM-ACTIVITY-01-claim-review；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/claim/generated/DGM-STATE-02-claim-request.svg](../../uml/claim/generated/DGM-STATE-02-claim-request.svg) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：DGM-STATE-02-claim-request；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/collection/collection-local-usecase.puml](../../uml/collection/collection-local-usecase.puml) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | PlantUML 业务图源：collection-local-usecase；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/collection/collection-task-state.puml](../../uml/collection/collection-task-state.puml) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | PlantUML 业务图源：collection-task-state；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/collection/collection-update-activity.puml](../../uml/collection/collection-update-activity.puml) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | PlantUML 业务图源：collection-update-activity；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/collection/generated/collection-local-usecase.png](../../uml/collection/generated/collection-local-usecase.png) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：collection-local-usecase；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/collection/generated/collection-local-usecase.svg](../../uml/collection/generated/collection-local-usecase.svg) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：collection-local-usecase；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/collection/generated/collection-task-state.png](../../uml/collection/generated/collection-task-state.png) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：collection-task-state；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/collection/generated/collection-task-state.svg](../../uml/collection/generated/collection-task-state.svg) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：collection-task-state；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/collection/generated/collection-update-activity.png](../../uml/collection/generated/collection-update-activity.png) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：collection-update-activity；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/collection/generated/collection-update-activity.svg](../../uml/collection/generated/collection-update-activity.svg) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：collection-update-activity；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/governance/dedup-candidate-activity.puml](../../uml/governance/dedup-candidate-activity.puml) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | PlantUML 业务图源：dedup-candidate-activity；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/governance/generated/dedup-candidate-activity.svg](../../uml/governance/generated/dedup-candidate-activity.svg) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：dedup-candidate-activity；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/governance/generated/governance-exception-state.svg](../../uml/governance/generated/governance-exception-state.svg) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：governance-exception-state；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/governance/generated/governance-overview-activity.svg](../../uml/governance/generated/governance-overview-activity.svg) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：governance-overview-activity；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/governance/generated/identity-disambiguation-activity.svg](../../uml/governance/generated/identity-disambiguation-activity.svg) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：identity-disambiguation-activity；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/governance/generated/relation-decision-sequence.svg](../../uml/governance/generated/relation-decision-sequence.svg) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：relation-decision-sequence；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/governance/generated/scholar-achievement-relation-state.svg](../../uml/governance/generated/scholar-achievement-relation-state.svg) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：scholar-achievement-relation-state；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/governance/governance-exception-state.puml](../../uml/governance/governance-exception-state.puml) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | PlantUML 业务图源：governance-exception-state；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/governance/governance-overview-activity.puml](../../uml/governance/governance-overview-activity.puml) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | PlantUML 业务图源：governance-overview-activity；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/governance/identity-disambiguation-activity.puml](../../uml/governance/identity-disambiguation-activity.puml) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | PlantUML 业务图源：identity-disambiguation-activity；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/governance/relation-decision-sequence.puml](../../uml/governance/relation-decision-sequence.puml) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | PlantUML 业务图源：relation-decision-sequence；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/governance/scholar-achievement-relation-state.puml](../../uml/governance/scholar-achievement-relation-state.puml) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | PlantUML 业务图源：scholar-achievement-relation-state；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/usecase/generated/overall-usecase.svg](../../uml/usecase/generated/overall-usecase.svg) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | 图源生成副本：overall-usecase；保留稳定引用与来源 | 对应模块/文档维护者 |
| B Supporting | [docs/uml/usecase/overall-usecase.puml](../../uml/usecase/overall-usecase.puml) | 正文/来源快照 | 详细规范/追踪/辅助；不得覆盖当前候选 | PlantUML 业务图源：overall-usecase；保留稳定引用与来源 | 对应模块/文档维护者 |
| E Tooling | [scripts/docs/build_common_srs.py](../../../scripts/docs/build_common_srs.py) | 正文/来源快照 | 工具；不提供业务效力 | build_common_srs；保留稳定引用与来源 | 文档维护者 |
| E Tooling | [scripts/docs/build_docx.py](../../../scripts/docs/build_docx.py) | 正文/来源快照 | 工具；不提供业务效力 | build_docx；保留稳定引用与来源 | 文档维护者 |
| E Tooling | [scripts/docs/validate_srs.py](../../../scripts/docs/validate_srs.py) | 正文/来源快照 | 工具；不提供业务效力 | validate_srs；保留稳定引用与来源 | 文档维护者 |
| E Tooling | [scripts/docs/verify_scoped_srs.py](../../../scripts/docs/verify_scoped_srs.py) | 正文/来源快照 | 工具；不提供业务效力 | verify_scoped_srs；保留稳定引用与来源 | 文档维护者 |

## 非受控本地原稿（不加入 Git）

以下逐文件列出 incoming 本机可见交付；全部归属 D Historical（原始来源，不作为当前开发基线），版本以文件正文为准，责任人为原成员，保留原始内容便于追溯。不重新导入、覆盖或移动。缺少本地材料的新克隆不能据此宣称正式文档丢失。

- `docs/srs/incoming/member04/FR03-FR07-scholar-original.md`：docs/srs/modules/FR03-FR07-scholar.md。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/交组长说明-交付内容与待决项.docx`：对应 Markdown 的 Word 交付副本；原样保留。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/交组长说明-交付内容与待决项.md`：交组长说明：交付内容与待决项。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/CognitaNexus需求建模评审汇报_模块05.pptx`：CognitaNexus需求建模评审汇报_模块05。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/CognitaNexus需求规格说明书_模块05_FR11-FR13-IN01.docx`：对应 Markdown 的 Word 交付副本；原样保留。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/CognitaNexus需求规格说明书_模块05_FR11-FR13-IN01.md`：CognitaNexus 学术成果分享平台 — 软件需求规格说明书。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/脚本/渲染全部图.sh`：渲染全部图。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/脚本/渲染全部图.py`：渲染全部图。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/脚本/生成Word文档.py`：生成Word文档。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/DGM-ACTIVITY-02-metrics-refresh.puml`：PlantUML 业务图源：DGM-ACTIVITY-02-metrics-refresh。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/DGM-STATE-01-collection-task.puml`：PlantUML 业务图源：DGM-STATE-01-collection-task。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/DGM-ACTIVITY-01-claim-review.puml`：PlantUML 业务图源：DGM-ACTIVITY-01-claim-review。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/DGM-USECASE-02-analytics.puml`：PlantUML 业务图源：DGM-USECASE-02-analytics。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/DGM-STATE-03-metrics-visibility.puml`：PlantUML 业务图源：DGM-STATE-03-metrics-visibility。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/DGM-STATE-02-claim-request.puml`：PlantUML 业务图源：DGM-STATE-02-claim-request。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/DGM-ACTIVITY-03-collab-path.puml`：PlantUML 业务图源：DGM-ACTIVITY-03-collab-path。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/DGM-ACTIVITY-04-institution-ranking.puml`：PlantUML 业务图源：DGM-ACTIVITY-04-institution-ranking。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/DGM-USECASE-01-system.puml`：PlantUML 业务图源：DGM-USECASE-01-system。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/渲染输出/DGM-ACTIVITY-02-metrics-refresh.png`：图源生成副本：DGM-ACTIVITY-02-metrics-refresh。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/渲染输出/DGM-ACTIVITY-01-claim-review.png`：图源生成副本：DGM-ACTIVITY-01-claim-review。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/渲染输出/DGM-ACTIVITY-02-metrics-refresh.svg`：图源生成副本：DGM-ACTIVITY-02-metrics-refresh。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/渲染输出/DGM-USECASE-02-analytics.png`：图源生成副本：DGM-USECASE-02-analytics。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/渲染输出/DGM-USECASE-02-analytics.svg`：图源生成副本：DGM-USECASE-02-analytics。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/渲染输出/DGM-ACTIVITY-03-collab-path.png`：图源生成副本：DGM-ACTIVITY-03-collab-path。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/渲染输出/DGM-STATE-02-claim-request.svg`：图源生成副本：DGM-STATE-02-claim-request。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/渲染输出/DGM-ACTIVITY-04-institution-ranking.png`：图源生成副本：DGM-ACTIVITY-04-institution-ranking。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/渲染输出/DGM-ACTIVITY-01-claim-review.svg`：图源生成副本：DGM-ACTIVITY-01-claim-review。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/渲染输出/DGM-STATE-01-collection-task.png`：图源生成副本：DGM-STATE-01-collection-task。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/渲染输出/DGM-STATE-02-claim-request.png`：图源生成副本：DGM-STATE-02-claim-request。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/渲染输出/DGM-STATE-03-metrics-visibility.svg`：图源生成副本：DGM-STATE-03-metrics-visibility。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/渲染输出/DGM-USECASE-01-system.png`：图源生成副本：DGM-USECASE-01-system。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/渲染输出/DGM-ACTIVITY-04-institution-ranking.svg`：图源生成副本：DGM-ACTIVITY-04-institution-ranking。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/渲染输出/DGM-STATE-03-metrics-visibility.png`：图源生成副本：DGM-STATE-03-metrics-visibility。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/渲染输出/DGM-USECASE-01-system.svg`：图源生成副本：DGM-USECASE-01-system。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/渲染输出/DGM-ACTIVITY-03-collab-path.svg`：图源生成副本：DGM-ACTIVITY-03-collab-path。
- `docs/srs/incoming/member05/需求规格说明书交付_成员05_统计分析专家网络与创新/图/渲染输出/DGM-STATE-01-collection-task.svg`：图源生成副本：DGM-STATE-01-collection-task。

## 发现与待决

- 当前工程 SRS 为 16 FR、8 NF、IN01、Optional O01/O02，46 AC；OvO 124/287 的映射为父范围参考，不能推断逐条语义等价或已经签认。
- 两个既有 CI 监听旧工作分支、contents: write、自动 git push；本次收缩为只读 artifact，不新增 main 写入能力。
- build_common_srs.py 会覆盖历史六人 SRS 及历史 integration-coverage.json，validate_srs.py 会覆盖历史验证 JSON；保留工具但不得用于当前常规检查。
- build_docx.py 为 python-docx 简化转换；既有 V1.1 CI 使用 Pandoc 和图片尺寸 Lua 过滤器，不能将不同转换器结果视为逐字节可复现。
- 历史 Markdown 与 Word 可能不同步；原件保留，本次不重生成覆盖。具体结构/AC 检查与渲染结果见验证报告。
- UML 业务语义与当前候选的完整一致性、两组签认、课程原文阶段要求仍需人工核定；不凭图存在宣称业务验收。

## 本次新增受控文档与工具

| 文件 | 建议归属与效力 | 用途/保留理由 |
| --- | --- | --- |
| [docs/configuration-management.md](../../configuration-management.md) | E Tooling / 维护约定候选 | 提供导航、管理依据或本次证据；不具有需求批准效力 |
| [docs/uml/README.md](../../uml/README.md) | B Supporting / 图导航 | 提供导航、管理依据或本次证据；不具有需求批准效力 |
| [scripts/docs/README.md](../../../scripts/docs/README.md) | E Tooling / 维护约定候选 | 新目录构建/只读检查，不覆盖正式交付 |
| [scripts/docs/build_current_word.sh](../../../scripts/docs/build_current_word.sh) | E Tooling / 维护约定候选 | 新目录构建/只读检查，不覆盖正式交付 |
| [scripts/docs/check_documentation.py](../../../scripts/docs/check_documentation.py) | E Tooling / 维护约定候选 | 新目录构建/只读检查，不覆盖正式交付 |
| [docs/reports/document-management/inventory-and-plan-2026-10-12.md](inventory-and-plan-2026-10-12.md) | C Review & Validation / 本次配置管理快照 | 提供导航、管理依据或本次证据；不具有需求批准效力 |
| [docs/reports/document-management/validation-2026-10-12.md](validation-2026-10-12.md) | C Review & Validation / 本次配置管理快照 | 提供导航、管理依据或本次证据；不具有需求批准效力 |
| [docs/reports/document-management/static-checks-2026-10-12.json](static-checks-2026-10-12.json) | C Review & Validation / 本次配置管理快照 | 提供导航、管理依据或本次证据；不具有需求批准效力 |
