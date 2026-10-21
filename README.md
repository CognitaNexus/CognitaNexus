# CognitaNexus

以学者为中心的学术成果聚合、共享与管理平台，覆盖论文、专利、科研项目和获奖四类成果。

## 当前阶段与阅读入口

仓库目前维护课程需求与分析文档、核心规则规格、UML 图源及评审证据，尚无应用实现或部署入口。S01 已冻结，S02 需求分析材料见文档导航；文档检查通过不代表业务验收通过。

**[文档导航](docs/README.md)** 是当前基线、模块分工、分析材料与历史来源的单一入口。

阅读顺序：需求 V2.1 → 共同 SRS V2.2（唯一 69 AC 权威基线）→ 核心 SPEC 与对齐说明 → S01 / S02 分析材料。46/59 AC 与旧 V1.1 仅用于历史追溯。各文件的实际版本和状态以正文为准，69 AC 运行验收仍未执行。

## 项目结构

```text
CognitaNexus/
├── README.md                 # 项目入口
├── AGENTS.md                 # 协作范围与交付约束
├── Makefile                  # 统一的只读文档检查入口
├── .github/workflows/        # 自动检查与临时构建
├── docs/
│   ├── README.md             # 文档单一导航
│   ├── configuration-management.md
│   ├── requirements/         # 需求基线与调研依据
│   ├── srs/                  # 共同 SRS、模块来源、编号与整合追踪
│   ├── spec/                 # 核心规则规格与对齐说明
│   ├── analysis/             # S01 / S02 分析材料
│   ├── uml/                  # 正式 PlantUML 图源；副本在各模块 generated/
│   └── reports/              # 正式评审与验证记录
├── scripts/docs/
│   ├── README.md             # 工具范围、依赖与副作用
│   ├── checks/               # 当前只读检查实现
│   ├── legacy/               # 历史重建与验证实现（可能写回历史文件）
│   └── …                     # 构建工具及旧命令兼容入口
└── .local/                   # 本地交付、临时材料；不纳入 Git
```

需求与历史文档保留稳定路径，通过导航区分效力；不按版本批量搬迁正文。各模块维护其图源，生成图与图源配对存放。已有本地 `docs/srs/incoming/` 原稿不作为受控基线或默认提交内容。

## 常用检查

在仓库根目录运行（依赖 Python 3 和 Make）：

```sh
make check-docs
```

此命令依次检查文档链接与生成物结构、SRS 范围、69 AC 基线和分析追踪；任一失败即停止，不重建或覆盖正式交付。无需 Make 时可按[工具说明](scripts/docs/README.md)运行三个 Python 检查。

Word 构建与历史重建不属于默认检查，使用前先核对工具输入版本及副作用。维护遵循 [AGENTS.md](AGENTS.md) 与[配置管理规范](docs/configuration-management.md)。
