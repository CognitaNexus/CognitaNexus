# UC-03 自检与待评审问题

**版本：** V1.0（2026-10-21）
**检查性质：** 静态文档/模型检查；不是产品运行测试，不是教师评分，不是人工评审签字。
**范围：** 只检查 UC-03 目录；共享追踪、跨 UC 登记表与状态文件由父 Agent 维护。

## 1. 范围与基线检查

| 检查 | 结果 | 证据 |
|---|---|---|
| 分支 | `analysis/s01-s02-69ac` | `git branch --show-current` |
| 起点 HEAD | `8878e34e4178390bf4543ffbc8bf7b2753e86845` | 本轮开始前记录 |
| 正式 SRS/SPEC/S01 | 未修改 | `git diff --name-only -- docs/srs docs/spec docs/analysis/stage-01` 为空 |
| 69 条 AC | 未新增、未删除、未改号 | 只引用既有 `AC-IMPL-FR03-01～04`、`NF01-01`、`NF06-01`，引用约束另列 |
| 其他 UC 成果 | 未重写、未生成其他 UC 文件 | 新增文件只在 `stage-02/uc-03/` |
| 共享追踪/登记表/全局图 | 未修改 | 未改 `03-AC-UC-分析模型追踪.md`、`analysis-class-registry.md`、`DGM-CLASS-01-analysis.puml`、`01-BCE分析类模型.md` |
| 其他成员未提交修改 | 未覆盖 | 工作区既有 `analytics/collection/scripts`、`.local/` 等改动保持原状，未纳入本轮 |
| 新增文件是否跟踪 | 未 `git add` | 见第 4.4 节 `git status` |

## 2. 教材步骤对应

| 教材步骤 | 本包证据 | 状态 |
|---|---|---|
| §5.4.1 p154–155 完善用例文档 | [01-UC-03用例规约](01-UC-03-use-case-spec.md) | 已完成（未决项见第 5 节） |
| §5.4.2 p155–161 识别分析类 | [03-BCE分析类识别](03-bce-identification.md) | 已完成；6 个参与类 |
| §5.4.3 p161–181 分析交互 | [04-顺序图与逐消息职责](04-sequence-messages.md)、`DGM-SEQ-08-profile` | 已完成；M1–M27 |
| §5.4.4 p181–183 构造 VOPC | [05-VOPC与关系依据](05-vopc.md)、`DGM-VOPC-04-profile` | 已完成；非全局 BCE |
| §5.5.1 p188–190 职责 | [06-分析类定义](06-class-definition.md) Do/Know/分析操作 | 已完成 |
| §5.5.2 p190–191 属性 | 同 06 业务属性表 | 已完成本 UC 可证实部分 |
| §5.5.3 p192–200 关系/多重性 | 同 06 第 8 节、05 第 2 节 | `Scholar—Authorship—WorkEntity` 有依据；读取关联不定义持久多重性 |
| §5.5.4 p200–201 分析机制 | 同 06 第 9 节 | 已定义本 UC 可证实机制 |
| §5.5.5 p201–204 统一分析类 | [07-与全局模型差异](07-global-model-diff.md) | 只提交 R5 输入，不重构全局图 |

## 3. 模型完整性检查

| 检查 | 结果 | 说明 |
|---|---|---|
| UC-03 参与者 | 访客；无次要 Actor | Scholar/成果是系统内对象，不是外部参与者 |
| 主流程编号 | 10 步 | 每步在 01/02 有来源 |
| 备选/异常 | A1–A8 | A1/A2 不可访问、A3 失败、A4 零成果、A5 同名并存、A6 未认领、A7 关系状态、A8 私有证明 |
| BCE 候选 | 6 个：PublicProfileUI/PublicProfileCoordinator/UserProfile/Scholar/Authorship/WorkEntity | 每个有事件流/消息依据 |
| 被拒绝候选 | User/ProfileUI/ProfileCtl/Claim/Auth/Evidence/Search/Session/SourceRecord/IdentityMatcher/ProfileView/EmptyState | 见 03 第 4 节 |
| 顺序图消息 | M1–M27 | 每条有发送者/接收者/职责/依据；事件/备选覆盖表见 04 第 4 节 |
| VOPC 类数 | 6 | 与顺序图对象集合一致 |
| VOPC 是否来自本 UC 顺序图 | 是 | 未使用全局 `DGM-CLASS-01` 作为 VOPC |
| UserProfile—Scholar | 无关联（明确禁止合并） | `AC-IMPL-FR03-04`；BR-IMP-03；SRS §12 |
| Scholar—WorkEntity | 不直连，必经 `Authorship` | SRS §12；FR15 |
| 零成果空态 | 正常页面 + “暂无公开成果”，非 404 | `AC-IMPL-FR03-03`；M13/M14 |
| 未认领提示 | 有 | `AC-IMPL-FR03-01`；M24/M25 |
| 待核验是否被画成已确认 | 否 | M20 保留独立状态，M23 禁止冒充 |
| 是否把 UC-22 编辑/UC-05/06 认领/UC-02 详情并入 | 否 | 只有边界说明与排除项 |
| 是否出现实现类/数据库/API/界面布局 | 否 | 只提分析职责与业务状态 |
| 是否新增/改写 AC/BR | 否 | 无新 AC；BR-UC03-01～09 由既有 SRS/AC 推导并标注来源 |

## 4. 实际执行的验证

### 4.1 PlantUML 渲染

实际执行（仓库根目录）：

```bash
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-03/diagrams/DGM-SEQ-08-profile.puml
plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-03/diagrams/DGM-SEQ-08-profile.puml
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-03/diagrams/DGM-VOPC-04-profile.puml
plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-03/diagrams/DGM-VOPC-04-profile.puml
```

结果：

| 模型 | PUML | SVG | PNG | 目视检查 |
|---|---|---|---|---|
| 公开主页/学者档案序列 | 有 | 有 | 有 | 已查看：M1–M27、三层 alt 分支、中文与图例可读 |
| VOPC | 有 | 有 | 有 | 已查看：6 类、读取方向、`1—0..*`、`0..*—1` 多重性可读；无 UserProfile—Scholar 边 |

SVG XML：2/2 可解析，且不含 PlantUML 语法错误图像标记。`PlantUML version 1.2020.02`；渲染成功只证明语法与可读性，不证明业务运行或评审通过。

### 4.2 文档静态检查

```text
python3 scripts/docs/check_documentation.py  # 输出字段节选（word 明细略）
{
  "status": "STATIC_PASS_ONLY",
  "relative_links": 1316,
  "uml_svg_pairs": 30,
  "local_source_dependencies": [],
  "errors": [],
  "external_urls_and_anchors": "NOT_CHECKED",
  "human_approval": "NOT_VERIFIED",
  "runtime_tests": "NOT_EXECUTED"
}
EXIT=0
```

该脚本为只读检查（相对链接、Word 包结构/AC 文本、`docs/uml` 下 PUML-SVG 配对），不做运行测试；`STATIC_PASS_ONLY` 不等于业务验收通过。

### 4.3 静态文本检查

- 04 中每条消息 M1–M27 都有发送者/接收者/事件步骤/职责/依据列；
- 05 每条关系都有消息或 FR03/AC/BR/§12 依据；
- 06 属性表区分“已证实/分析推导/待确认”，未冻结字段不写成既定值；
- `uc-03` 目录只包含 UC-03 文件，没有其他 UC 的规约、序列图或 VOPC；
- 01/02/03/04/05/06/07 的每个“待确认”项都能回到 01 §11 的 Q 编号。

### 4.4 工作区状态

```text
git status --short --untracked-files=all docs/analysis/stage-02/uc-03
?? docs/analysis/stage-02/uc-03/01-UC-03-use-case-spec.md
?? docs/analysis/stage-02/uc-03/02-event-flow-trace.md
?? docs/analysis/stage-02/uc-03/03-bce-identification.md
?? docs/analysis/stage-02/uc-03/04-sequence-messages.md
?? docs/analysis/stage-02/uc-03/05-vopc.md
?? docs/analysis/stage-02/uc-03/06-class-definition.md
?? docs/analysis/stage-02/uc-03/07-global-model-diff.md
?? docs/analysis/stage-02/uc-03/08-self-check.md
?? docs/analysis/stage-02/uc-03/README.md
?? docs/analysis/stage-02/uc-03/diagrams/DGM-SEQ-08-profile.puml
?? docs/analysis/stage-02/uc-03/diagrams/DGM-VOPC-04-profile.puml
?? docs/analysis/stage-02/uc-03/diagrams/generated/DGM-SEQ-08-profile.png
?? docs/analysis/stage-02/uc-03/diagrams/generated/DGM-SEQ-08-profile.svg
?? docs/analysis/stage-02/uc-03/diagrams/generated/DGM-VOPC-04-profile.png
?? docs/analysis/stage-02/uc-03/diagrams/generated/DGM-VOPC-04-profile.svg
```

本轮未 `git add`、未提交；`docs/uml/analytics`、`docs/uml/collection` 的既有修改与 `docs/srs/integration/current-acceptance-trace-*` 的既有未跟踪文件不是本轮产生，保持原状。

### 4.5 基线校验（只证明基线未被本次改动破坏）

```text
python3 scripts/docs/verify_69_ac.py
PASS: 69 AC unique; SRS text matches ledger; trace has all 69;
UC-01..19/22 present; A1c and scale markers present;
active references resolve.
GROUPS: {'FR': 54, 'IN01': 4, 'NF': 11}
EXIT_69=0

python3 scripts/docs/verify_scoped_srs.py
"status": "DOCUMENT_STATIC_PASS_ONLY", "authoritative_ac": 69,
"application_test": "NOT_EXECUTED", "performance_and_recovery": "NOT_RUN"
EXIT_SCOPED=0
```

两个脚本均为静态检查；`NOT_EXECUTED/NOT_RUN` 保持原样，不能据此声称测试已通过。

## 5. 待确认清单

| 编号 | 问题 | 为什么不能自行决定 | 本包当前处理 |
|---|---|---|---|
| Q-01 | 公开普通字段集合与默认可见性（昵称/头像/简介/兴趣领域） | FR03 只说“用户允许展示的信息”；FR06/UC-22 未冻结字段级可见性；注册邮箱/联系方式默认不公开已确认 | 属性写为“允许公开的普通资料”，不列具体字段；不臆造默认值 |
| Q-02 | 空态文案适用范围（四类全零 vs 部分为零分区） | `AC-IMPL-FR03-03` 只要求零成果非 404 且可见“暂无公开成果” | 只承诺零成果空态与文案；分区展示待确认 |
| Q-03 | UserProfile 主页成果区的数据来源口径 | SRS §12 禁止由普通资料推导学术身份/合作边，但未规定认领后主页是否列出该 Scholar 成果 | 不建立 `UserProfile—WorkEntity/Authorship` 关系；只做“是否有可公开成果”与空态 |
| Q-04 | 待核验关系是否以标签公开，还是完全不进入公开列表 | `AC-IMPL-FR03-02` 只禁止冒充已确认；BR-IMP-01 只排除待审/争议/敏感证据 | 保证“绝不显示为已确认合作/本人确认”；展示与否待确认 |
| Q-05 | 不可访问的对外粒度（不存在/未公开/已失效）与是否 404 | 基线只要求与空态、执行失败可区分 | 只承诺可区分且不泄露字段 |
| Q-06 | Scholar 认领状态与身份核验状态的展示标签口径 | SPEC 身份/关系状态枚举到门户标签的映射需联合评审 | 属性保留 `claimState`/`identityState`，标签口径待 R5 |
| Q-07 | 登录用户查看本人主页的视图差异 | 属 UC-22 与所有权校验 | 按同一公开视图分析，不定义差异 |
| Q-08 | “用户允许展示”的授权与撤销机制 | 受保护写属 UC-22/FR06 | 只读其结果，不定义机制 |
| Q-09 | 账号停用后 UserProfile 是否仍公开可读 | `AC-IMPL-FR04-02` 只保证不删除学者公开档案 | 不把 `User` 纳入序列，不决定该规则 |

## 6. 待父 Agent/后续阶段处理

| 事项 | 说明 |
|---|---|
| 追踪文件 | `03-AC-UC-分析模型追踪.md` 的 UC-03 前向行（`AC-IMPL-FR03-01～04`、`NF01-01`、`NF06-01`）、FR03/NF01/NF06 汇总行与 UC-03 反向行需补 `DGM-SEQ-08/VOPC-04`；本 Agent 未改共享文件 |
| 跨 UC 类登记 | `analysis-class-registry.md` 需登记 `PublicProfileUI`、`PublicProfileCoordinator`，并为 `UserProfile/Scholar/Authorship/WorkEntity` 的“后续参与 UC”补 UC-03（建议见 07 第 6 节） |
| 状态文件 | `automation/progress.json` 的 UC-03 状态由父 Agent 更新 |
| 全局模型 | 07 的 R5 建议（公开只读边界/协调者拆分、未认领提示、零成果三态、关系状态过滤）留待第 6 阶段统一分析类 |
| 人工评审 | 本包未取得人工评审签字；Q-01～Q-09 待需求负责人/评审确认 |

## 7. 结论

```text
UC-03: STATIC COMPLETE (check_documentation errors=0)
UML RENDER: 2/2 OK (SVG/PNG)
AC BASELINE: 69 UNCHANGED (no AC added or reworded)
RUNTIME TEST: NOT RUN
HUMAN REVIEW: 待评审
SCOPE: 只新增 docs/analysis/stage-02/uc-03/
```

UC-03 已按教材 §5.4.1–§5.4.4 和 §5.5.1–§5.5.4 完成一次可追溯的用例实现分析，并产出了独立 VOPC。零成果空态、未认领提示、同名不合并、关系状态不冒充与公开最小化均有正式来源；公开字段集合、空态文案范围、成果区来源口径、待核验展示口径、不可访问粒度、状态标签口径与停用账号可读性仍待确认，未写成已批准需求。

在评审人确认前，不把 UC-03 标为“业务已批准/教师已验收”。
