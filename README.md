# CognitaNexus

以学者为中心的学术成果聚合、共享与管理平台，覆盖论文、专利、科研项目和获奖四类成果。

## 当前阶段

项目处于**需求建模 / SRS 与核心 SPEC 初稿**阶段。当前仓库维护需求、模块规格、图源和评审材料，尚未提供应用代码或运行部署流程。

- 需求依据：V1.9 需求基线，正文状态为待小组评审。
- **当前唯一工程实施候选：双组共同 SRS V1.1 Implementation RC**；已依据 V1.9 进行工程可行性与需求边界收敛，尚待 CognitaNexus 与 OvO 双方签认，未 Frozen。此前六人稿及 514 页双组汇编均保留为历史来源，不再作为现行开发规范。
- 核心 SPEC V1.0 为 Review Candidate，需联合评审；不代表规则已冻结。
- FR01、FR02 为 Must；O01、O02 为 Optional，不作为一期基础验收的前置条件。

## 阅读入口

**[文档导航：当前规范、追踪、UML、评审与历史](docs/README.md)** 是文档单一入口。

当前阅读顺序：需求 V1.9 → 共同 SRS V1.1 Implementation RC → 核心 SPEC V1.0 + V1.1 工程对齐说明。共同 SRS 为 **Review Candidate / 未经两组正式签认 / 非 Frozen**。历史六人稿及双组 V1.0：**Historical / 仅用于来源追踪，不作为当前开发基线。**

[配置管理规范](docs/configuration-management.md) · [盘点与整理方案](docs/reports/document-management/inventory-and-plan-2026-10-12.md) · [整理验证](docs/reports/document-management/validation-2026-10-12.md)

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
