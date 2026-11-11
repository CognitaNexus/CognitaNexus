# 课程定稿与工程文档

## 课程需求基线

2026-11-11 用户提供《软件系统分析与设计.zip》，明确这套材料为本次课程定稿。**使用该定稿内容作为新增代码需求和评审图的依据，不再使用历史 V1.1 工程候选或未经同步的 V2.2 草案替代。**

压缩包内有：
- 需求调研成果：需求调研报告、可行性分析附录、数据源与平台调查。
- 需求建模成果：**SRS V2.1**（69 条 AC）、SPEC、用例/活动/状态 UML 图和 CodeArts 图源。
- 需求分析成果：需求分析报告、核心业务类图、数据采集/门户认领/检索时序图、PlantUML 可编辑源及展开图。

**注意：本次公开仓库清理不等于已上传完整定稿文件。** 原 ZIP 含匿名调查、成员信息以及重复打包资料，需先筛选可公开文件，才能作为权威交付物发布到公开 GitHub。完整原件以本次上传的压缩包为准。

核对信息：
- 归档文件 SHA-256：`1402a172c9a8cd98bc61b2c03ab1bc8e0fe7a76d0dbd58755b0a1623b67c3f7d`。
- SRS DOCX SHA-256：`fb626d98fad76a4293b6e77d05792f63461e8f8e740f1ff0cf25e04ebeaf5f55`。
- SPEC DOCX SHA-256：`9adf8d4ece8b827eec3a7275bce84bde42869b0320d3f849ae20806090e32b22`。
- 需求分析报告 DOCX SHA-256：`5d5cbb6f0ef38f93292f7c630979b61cae8d9f337eef3bbb4cd68df5f3d05c46`。

## 架构决策与代码仓库入口

| 路径 | 用途 |
| --- | --- |
| [根目录 AGENTS.md](https://github.com/CognitaNexus/CognitaNexus/blob/main/AGENTS.md) | 当前软件工程流程与智能体工作约束 |
| [分支规范](https://github.com/CognitaNexus/CognitaNexus/blob/main/docs/BRANCHING.md) | Git 分支、PR 与发布流程 |
| [编码规范](https://github.com/CognitaNexus/CognitaNexus/tree/main/docs/standards) | 后端和前端编码约束（实施时核实与当前框架一致） |
| [架构决策记录](architecture/adr/) | 历史工程取舍证据；不得视为覆盖本次课程定稿的正式架构 |
| [来源许可与字段白名单](https://github.com/CognitaNexus/CognitaNexus/tree/main/docs/engineering) | 公开数据源接入时的许可和字段约束 |
| [ops](https://github.com/CognitaNexus/CognitaNexus/tree/main/ops)、[tests](https://github.com/CognitaNexus/CognitaNexus/tree/main/tests) | 当前部署、种子导入、接口和性能测试的可执行资产 |

已从 `main` 删除旧的 V0.2 架构说明、过时运维手册、旧 SRS/SPEC 对照流水账、阶段性 AI 报告/提示词和历史统计报告；这些资料仍可通过 Git 历史查阅。不删除正式运行代码、Flyway、CI 或测试脚本。

## 任务管理

[Issues](https://github.com/CognitaNexus/CognitaNexus/issues) 只跟踪实现、代码质量、数据管道、性能、部署及自动化测试。课程文档已经定稿，不再创建单独的文档编写 Issue。需求变更应在代码 Issue 中注明受影响的定稿条款和验收条件。
