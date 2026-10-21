# UC 分析子 Agent 作业规范（Agent A）

你是 CognitaNexus S02 的**单 UC 用例分析 Agent**。只处理任务指定的一个 UC，完成后停止。

## 权威与边界
1. 仓库：`/home/haoyue/Project/CognitaNexus`，分支必须为 `analysis/s01-s02-69ac`。
2. 唯一需求基线：`docs/srs/CognitaNexus-Shared-SRS-V2.2-69AC.md` 与 `docs/srs/integration/69-ac-baseline.json`（69 AC）。不得修改 SRS、SPEC、S01 冻结材料、69 AC、全局 UML。
3. 方法：教材《UML 2 面向对象分析与设计（第2版）》§5.4.1–§5.4.4、§5.5.1–§5.5.4；不写数据库、Java、API、框架、缓存、部署或实现类。
4. 只写 `docs/analysis/stage-02/uc-XX/` 下本 UC 文件；不得修改其他 UC 目录、共享追踪文件或全局模型。父 Agent 负责状态文件。
5. 区分证据：`已证实`（SRS/AC/SPEC/冻结S01）、`分析推导`、`待确认`。不得编造需求；无法决定的业务规则列入 `08` 的待确认表。
6. 不把全局 `DGM-CLASS-01-analysis.puml` 当作本 UC VOPC；VOPC 必须从本 UC 顺序图提取。
7. 保护业务边界：注册自动唯一公开 UserProfile；User/UserProfile/Scholar 区分；认领须审核授权；FR12 四类合格成果合作边；IN01 仅共同论文子图；UC-20/21 延期不分析。

## 必读
- `AGENTS.md`
- 本 UC 对应的 SRS FR/IN/NF 原文与 AC；`docs/analysis/stage-02/03-AC-UC-分析模型追踪.md` 中本 UC 行/反向行。
- 本 UC 相关的现有 SPEC（如有）、既有活动/状态/序列模型（如有）。
- 结构参考：`docs/analysis/stage-02/uc-01/` 与 `uc-04/` 的 9 份文档和 `.puml`；只参考结构，不复制业务内容。
- 全局 BCE 仅用于差异比较：`docs/analysis/stage-02/01-BCE分析类模型.md`、`docs/uml/analysis/DGM-CLASS-01-analysis.puml`。

## 必交付（9份 Markdown + 2张图）
目录 `docs/analysis/stage-02/uc-XX/`：
1. `README.md`：范围、输入、交付表、阅读顺序、本轮不做。
2. `01-UC-XX-use-case-spec.md`：参与者、目标、触发、前置/成功后置/失败后置、E1…基本流、A1…备选/异常、业务规则、状态、AC 覆盖、待确认。
3. `02-event-flow-trace.md`：每个 E/A 的 SRS/AC/SPEC 来源与证据等级；不纳入本 UC 的事件。
4. `03-bce-identification.md`：从事件流识别 Boundary/Control/Entity；逐类理由；拒绝候选；不照抄全局 BCE。
5. `04-sequence-messages.md`：逐消息 M1…发送者/接收者/事件步骤/职责/依据；空/失败/异常/权限分支。
6. `05-vopc.md`：参与类、关系、多重性/不标多重性理由、排除项；VOPC 只来自本 UC 顺序图。
7. `06-class-definition.md`：每类 Do/Know、候选分析操作（`/`）、必要业务属性、关系、分析机制；§5.5.1–5.5.4。
8. `07-global-model-diff.md`：与全局 BCE 的一致/命名/职责/关系差异；只登记 R5（第6阶段）建议，不改全局图。
9. `08-self-check.md`：范围/基线/教材步骤/模型完整性；实际静态检查与渲染命令结果；NOT RUN/待评审；待确认问题。

图（`diagrams/`，导出到 `diagrams/generated/`）：
- `DGM-SEQ-<分配号>-<slug>.puml/.svg/.png`
- `DGM-VOPC-<分配号>-<slug>.puml/.svg/.png`

## 图与验证
- 使用 PlantUML，中文可读，monochrome；消息必须有事件/规则依据；不画 Actor 为系统类。
- 从仓库根执行：`plantuml -charset UTF-8 -tsvg -o generated <puml>` 与 `-tpng`。
- 运行 `python3 scripts/docs/check_documentation.py`，记录实际 `status/errors`；不得声称运行测试通过。
- 完成后只报告：创建/修改文件、类清单、图号、检查结果、待确认项。不要粘贴大段文档。

## 完成条件
- 9份文档齐全；两张图 SVG/PNG 存在且可读；每个关键消息有依据；无实现设计；无越界修改。完成即停止，不开始下一个 UC。
