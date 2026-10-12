# CognitaNexus

以学者为中心的学术成果聚合、共享与管理平台，覆盖论文、专利、科研项目和获奖四类成果。

## 当前阶段

项目处于**需求建模 / SRS 与核心 SPEC 初稿**阶段。当前仓库维护需求、模块规格、图源和评审材料，尚未提供应用代码或运行部署流程。

- 需求依据：V1.9 需求基线，正文状态为待小组评审。
- **当前唯一工程实施候选：双组共同 SRS V1.1 Implementation RC**；已依据 V1.9 进行工程可行性与需求边界收敛，尚待 CognitaNexus 与 OvO 双方签认，未 Frozen。此前六人稿及 514 页双组汇编均保留为历史来源，不再作为现行开发规范。
- 核心 SPEC V1.0 为 Review Candidate，需联合评审；不代表规则已冻结。
- FR01、FR02 为 Must；O01、O02 为 Optional，不作为一期基础验收的前置条件。

## 阅读入口

1. [需求基线 V1.9](docs/requirements/学术成果分享平台需求分析与可行性报告.md)
2. [调研与可行性论证附录](docs/requirements/学术成果分享平台调研与可行性论证附录.md)
3. [SRS 编写与需求建模规范 V1.0](docs/srs/CognitaNexus_SRS编写与需求建模规范_V1.0.md)
4. [六人模块 SRS 导航](docs/srs/modules/README.md)
5. **[工程可实施双组共同 SRS V1.1 RC](docs/srs/CognitaNexus-Shared-SRS-V1.1-Implementation-RC.md)** · [对应 Word](docs/srs/CognitaNexus-Shared-SRS-V1.1-Implementation-RC.docx)；[工程可行性评审与12项取舍](docs/reports/srs/engineering-feasibility-scope-review-2026-10-12.md)
6. [核心 SPEC 候选稿](docs/spec/CognitaNexus-Core-SPEC-V1.0.md)
7. **[OvO 124 条子需求/287 条 AC 逐条收敛来源表](docs/srs/integration/engineering-scope-trace-v1.1-rc.md)** · [历史自动归并 V1.0](docs/srs/integration/shared-ac-resolution-v1.0-rc.md)（仅审计，不作为现行实施）
8. [文档目录与维护约定](docs/README.md)

## 项目结构

```text
CognitaNexus/
├── README.md
├── .gitignore
├── AGENTS.md
├── .local/submissions/   # 本地原始交付与待验证导出；不纳入 Git
└── docs/
    ├── README.md
    ├── requirements/       # 需求基线与调研依据
    ├── srs/                # 编写规范、共同SRS候选、模块 SRS 与评审输入
    │   ├── modules/
    │   ├── review/
    │   └── incoming/       # 历史成员原稿，当前为本地未跟踪材料
    ├── spec/               # 核心 SPEC 候选稿与追踪
    ├── uml/                # PlantUML 图源与生成图
    └── reports/            # 评审材料
```

根目录原有 PNG 和待验证 CodeArts ZIP 已归入 `.local/submissions/uml-reference-2026-10-12/`，仅作本机参考；正式 UML 以 `docs/uml/` 下的 `.puml` 为维护源。`docs/srs/incoming/` 中既有成员原稿暂保留原路径，避免破坏现有引用，也不作为默认提交内容。后续分析、设计及实现资产在获准进入对应阶段后再增加。
