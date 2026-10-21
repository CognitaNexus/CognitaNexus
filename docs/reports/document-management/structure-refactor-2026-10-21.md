# 项目结构整理记录（2026-10-21）

- 状态：工具与导航整理；不改变需求基线或评审结论。
- 授权来源：用户要求重构项目结构并提交、推送。
- 输入提交：`f1d8277`；当前业务依据为需求 V2.1、共同 SRS V2.2（69 AC）。
- 维护范围：项目入口、工具目录、默认检查命令、CI 入口与本地材料忽略规则。业务文档与图资产负责人不变。

## 调整与兼容

| 原位置 | 实现位置 / 处理 | 影响 |
| --- | --- | --- |
| `scripts/docs/check_documentation.py` | `scripts/docs/checks/check_documentation.py` | 旧路径保留转发入口 |
| `scripts/docs/verify_scoped_srs.py` | `scripts/docs/checks/verify_scoped_srs.py` | 旧路径保留转发入口 |
| `scripts/docs/verify_69_ac.py` | `scripts/docs/checks/verify_69_ac.py` | 旧路径保留转发入口 |
| `scripts/docs/build_common_srs.py` | `scripts/docs/legacy/build_common_srs.py` | 历史写回工具，旧路径兼容，默认检查不运行 |
| `scripts/docs/validate_srs.py` | `scripts/docs/legacy/validate_srs.py` | 历史写回工具，旧路径兼容，默认检查不运行 |
| 根 README | 更新到 V2.1 / V2.2 阅读链和实际目录 | 修复入口仍引用 V1.9 / V1.1 的过时说明 |
| Makefile / CI | `make check-docs` 统一三个检查 | CI 新增69 AC检查；明确原Word构建仍为历史V1.1 |
| `.gitignore` | 忽略整个 `.local/` | 保留本地文件，避免整改临时材料进入提交 |

迁移脚本仅调整仓库根路径层级，业务检查逻辑不变。旧文档和历史命令无需改写，业务文档目录、编号、追踪、正式 Word 和图资产保持原位。构建工具原路径保留，避免与工作区已有构建修改混合。

## 提交边界

任务开始时已存在 UML 修改、构建脚本修改、未跟踪课程材料和评审材料。本次保留这些内容，只暂存结构整理变更；工具 README 同文件中的既有修改单独保留在工作区。未经本次核验的原始交付不作为结构整理提交的一部分。

## 验证范围与未决项

验证新旧检查入口输出一致性、迁移实现仅根路径变化、Python语法、Markdown文件目标、Git差异及独立提交快照。实际结果见下文。

本次不运行会写回历史正文的工具，不构建Word或重渲染UML，不执行应用单元、集成或性能测试；远程CI以实际运行结果为准。69 AC运行验收仍为NOT RUN。

未改变需求语义，未发现本次结构整理引入的基线冲突。既有课程交付、人工评审与运行验收待办沿用原文档；本记录不宣布其完成。

## 实际验证结果

| 检查 | 结果 |
| --- | --- |
| 工作区 `make check-docs` | 三项静态检查通过；含本地已有材料，不代表干净检出通过 |
| 三个新旧入口、从 `/tmp` 执行新入口 | 返回0，输出逐字节一致 |
| 五个迁移实现 | 与来源提交逐字比较，仅仓库根层级 `parents[2]` → `parents[3]`；Python语法通过 |
| 项目/文档/工具导航文件目标 | 均存在；不验证远端URL或锚点 |
| 独立待提交快照：SRS范围、69 AC | 均通过 |
| 独立待提交快照：全局链接 | **失败：4处既有断链**；与整理前HEAD独立快照的错误列表完全一致，无新增错误 |
| 本次暂存差异 | `git diff --cached --check -C`通过；复制识别保留历史模板中的Markdown换行空格 |

干净检出缺少的3个文件（被4处链接引用）为：

- `docs/reports/srs/reviewed-inputs-2026-10-17.json`
- `docs/reports/srs/review-remediation-2026-10-17.md`
- `docs/srs/integration/current-acceptance-trace-2026-10-17.md`

这些文件在本地未跟踪材料中存在，但本次不将其未经审核地发布。后续由相应评审/追踪材料负责人核验并提交完整依赖，才能消除干净检出的全局链接失败。默认检查保留失败，不屏蔽缺项。全工作区差异另有原有UML空白告警，未纳入本次提交或格式化。
