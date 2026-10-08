# CognitaNexus

以学者为中心的学术成果聚合、共享与管理平台，覆盖论文、专利、科研项目和获奖四类成果。

## 当前阶段

项目处于**需求建模 / SRS** 阶段。当前仓库维护需求与规格文档，尚未提供应用代码或运行部署流程。

- 需求依据：V1.9 需求基线，正文状态为待小组评审。
- 搜索与推荐模块：SRS V0.1 Draft，待两组联合评审。
- FR01、FR02 为 Must；O01、O02 为 Optional，不作为一期基础验收的前置条件。

## 阅读入口

1. [需求基线 V1.9](docs/requirements/学术成果分享平台需求分析与可行性报告.md)
2. [调研与可行性论证附录](docs/requirements/学术成果分享平台调研与可行性论证附录.md)
3. [SRS 编写与需求建模规范 V1.0](docs/srs/CognitaNexus_SRS编写与需求建模规范_V1.0.md)
4. [搜索与推荐模块 SRS](docs/srs/modules/search-recommendation.md)
5. [文档目录与维护约定](docs/README.md)

## 项目结构

```text
CognitaNexus/
├── README.md
├── .gitignore
└── docs/
    ├── README.md
    ├── requirements/       # 需求基线与调研依据
    └── srs/
        ├── CognitaNexus_SRS编写与需求建模规范_V1.0.md
        └── modules/
            └── search-recommendation.md
```

后续分析、设计及实现资产在进入对应阶段后再增加；当前不预建技术框架、应用目录或空模块。
