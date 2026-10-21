# UC-06 自检与待评审问题

**版本：** V1.0（2026-10-21）
**检查性质：** 静态文档/模型检查；不是产品运行测试，不是教师评分，不是人工评审签字。
**范围：** 只检查 UC-06 分析包及本包的渲染与文档链接结果。

## 1. 范围与基线检查

| 检查 | 结果 | 证据 |
|---|---|---|
| 分支 | `analysis/s01-s02-69ac` | `git branch --show-current` |
| 起点 HEAD | `b57f1019fc696945321a6c75be51f9828c29a994` | 本轮开始前记录 |
| 正式 SRS/SPEC/S01 | 未修改 | 本轮只新增 `docs/analysis/stage-02/uc-06/` |
| 69 条 AC | 未新增、未删除、未改号 | `python3 scripts/docs/verify_69_ac.py`（§4） |
| 全局 UML / 合并认领序列 / 状态图 / 活动图 | 未修改 | 只读比对，见 07 |
| 其他 UC 目录 | 未修改 | 未写入 `uc-01`–`uc-05`、`uc-22` |
| 共享追踪/登记文件 | 未修改 | `03-AC-UC-分析模型追踪.md`、`automation/analysis-class-registry.md`、`automation/unresolved-decisions.md` 保持原状；登记建议见 07 §6 |
| 其他成员未提交修改 | 未覆盖 | 工作区原有 `docs/uml/analytics`、`docs/uml/collection`、`scripts/docs` 修改保持原状 |

## 2. 教材步骤对应

| 教材步骤 | 本包证据 | 状态 |
|---|---|---|
| §5.4.1 p154–155 完善用例文档 | [01-UC-06用例规约](01-UC-06-use-case-spec.md) | 已完成（未决项见 §6） |
| §5.4.2 p155–161 识别分析类 | [02-事件流与需求来源](02-event-flow-trace.md)、[03-BCE分析类识别](03-bce-identification.md) | 已完成；8 个参与类 |
| §5.4.3 p161–181 分析交互 | [04-顺序图与逐消息职责](04-sequence-messages.md)、`DGM-SEQ-11-claim-review` | 已完成；M1–M62 |
| §5.4.4 p181–183 构造 VOPC | [05-VOPC与关系依据](05-vopc.md)、`DGM-VOPC-07-claim-review` | 已完成；非全局 BCE |
| §5.5.1 p188–190 职责 | [06-分析类定义](06-class-definition.md) Do/Know/候选分析操作 | 已完成 |
| §5.5.2 p190–191 属性 | 同 06 业务属性表 | 已完成本 UC 可证实部分；审核权限划分/证据集合/版本机制标待确认 |
| §5.5.3 p192–200 关系/多重性 | 同 05 §2、06 §10 | 仅申请→申请人/目标、授权→被授权主体标 `0..*:1`；其余不定义或标未定 |
| §5.5.4 p200–201 分析机制 | 同 06 §11 | 已定义 11 项机制 |
| §5.5.5 p201–204 统一分析类 | [07-与全局模型差异](07-global-model-diff.md) | 只提交 R5 输入，不重构全局图 |

## 3. 模型完整性检查

| 检查 | 结果 | 说明 |
|---|---|---|
| UC-06 参与者 | 主要：有权审核的管理员；次要（只读）：申请人（读取路径属 UC-05） | 不引入角色等级 |
| 主流程编号 | E1–E12 | 每步在 01/02 有来源 |
| 备选/异常 | A1–A9 | 均有正式来源或标注分析推导 |
| BCE 候选 | 8 个：`ClaimReviewUI`/`ClaimReviewCoordinator`/`User`/`ClaimApplication`/`ReviewEvidence`/`Scholar`/`ScholarAuthorization`/`AuditEvidence` | 见 03 §2 |
| 被拒绝候选 | UserProfile、Authorship、WorkEntity/WorkVersion/SourceRecord、ClaimCoordinator(提交侧)、ConflictQueue/ControlledTodo/DisputeCase、ClaimDecision、Notification/SlaTimer、ReviewerRole/PermissionMatrix/ApprovalChain、ScholarMerge、EvidenceFile、ReviewTask、ReviewContext/DecisionView、Session、Admin、第二个 Scholar | 见 03 §4 |
| 顺序图消息 | M1–M62 | 每条有发送者/接收者/职责/依据；覆盖核对见 04 §4 |
| VOPC 类数 | 8 | 与顺序图对象集合一致 |
| VOPC 是否含 UserProfile/Authorship/Work | 否 | 只含审核 UI/Coordinator/User/Claim/Evidence/Scholar/Auth/Audit |
| UserProfile 是否被创建/合并 | 否 | 无 UserProfile 消息；AC-IMPL-FR05-04 |
| Scholar 是否被创建/改写 | 否 | M14 只读确认；A3 级风险见 07 §3 |
| ScholarAuthorization 是否被转移/撤销 | 否 | M15/M16 只读；M27 仅在无冲突批准时创建 |
| 是否自动确认 Authorship | 否 | 无 Authorship/WorkEntity 消息；M34/BR-UC06-07 |
| 状态迁移归属 | 本 UC 只产生 `待审核→待补充/通过/驳回/争议` 与 `待补充→待审核`；其余保持原状态 | 见 01 §9 |
| 冲突是否可被批准 | 否 | A5：不得批准，记录争议；M20 守卫 |
| 理由是否必填 | 是 | A8/M21–M22；`DGM-STATE-02` |
| 决定是否留痕 | 是，每条改变状态的路径都有审计消息 | M24/M29/M33/M37/M41/M49/M53 |
| 证据可见性 | 私有，仅本人与有权审核者 | M11/M46/M12/M61；AC-IMPL-NF06-01 |
| 拒绝/失败/不变状态 | 分开表达 | A1/A2/A8（拒绝）、A6（失败）、A9（注记） |
| 是否含 UC-05 提交或本人查询 | 否 | 提交与本人查询只作输入/后置条件引用，未重画 |
| 是否含 UC-07/UC-08 行为 | 否 | 档案字段维护、Authorship 确认明确排除 |
| 是否自造审核等级/SLA/通知/申诉 | 否 | C-01/C-03/C-05/C-11 |
| 技术实现 | 未引入数据库/Java/API/框架/队列 | 只有分析职责 |

## 4. 实际执行的验证

### 4.1 PlantUML 渲染

实际执行（仓库根目录）：

```bash
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-06/diagrams/DGM-SEQ-11-claim-review.puml
PLANTUML_LIMIT_SIZE=8192 plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-06/diagrams/DGM-SEQ-11-claim-review.puml
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-06/diagrams/DGM-VOPC-07-claim-review.puml
plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-06/diagrams/DGM-VOPC-07-claim-review.puml
```

> 序列图 PNG 首次导出后检查发现高度被截断（默认 PlantUML 图像上限 4096 px，实际 SVG 高 4667），故以 `PLANTUML_LIMIT_SIZE=8192` 重渲染，得到完整 3399×4666 图（含 M61/M62、A9 注记与 legend）。SVG 尺寸不受该限制影响。

结果：

| 模型 | PUML | SVG | PNG | 目视检查 |
|---|---|---|---|---|
| 审核决定序列 | 有 | 有（无 Syntax Error） | 有（3399×4666） | M1–M62 可读；A1/A2/A8、批准（A5/A6/成功）、驳回、要求补证、补证复核、决定查询各分支与中文标签正常；A9 注记与 legend 完整 |
| VOPC | 有 | 有（无 Syntax Error） | 有（2728×1683） | 8 个类；`Coordinator→Auth` 只读虚线（M15）+ 创建实线（M27）可区分；申请→申请人/目标与授权→被授权主体标 `0..*:1`；无 UserProfile/Authorship |

渲染成功不证明业务运行或教师评审通过；PNG/SVG 由 PlantUML 生成，未手工修改。

### 4.2 文档检查（本包新增后）

```text
$ python3 scripts/docs/check_documentation.py
{
  "status": "STATIC_PASS_ONLY",
  "relative_links": 1442,
  "uml_svg_pairs": 30,
  "errors": [],
  "runtime_tests": "NOT_EXECUTED",
  "human_approval": "NOT_VERIFIED"
}
```

> 本包 `08-self-check.md` 尚未创建时（已有 README 与 01–07 共 8 个 Markdown）出现过 1 次中间态 `FAIL`：`README.md` 与 `06-class-definition.md` 链接到不存在的 `08-self-check.md`，`errors` 为 `docs/analysis/stage-02/uc-06/06-class-definition.md: missing 08-self-check.md` 与同形的 README 行（`relative_links: 1435`）。补写 08（共 9 个 Markdown）后重跑为 `STATIC_PASS_ONLY`、`errors: []`、`relative_links: 1442`（上方为最终态）。`uml_svg_pairs: 30` 只统计 `docs/uml/` 下源图；本包 `docs/analysis/stage-02/uc-06/diagrams/` 的 SVG/PNG 由 §4.1 的渲染命令与链接检查共同覆盖。

### 4.3 69 AC 与范围化 SRS

```text
$ python3 scripts/docs/verify_69_ac.py
PASS: 69 AC unique; SRS text matches ledger; trace has all 69;
UC-01..19/22 present; A1c and scale markers present;
active references resolve.
GROUPS: {'FR': 54, 'IN01': 4, 'NF': 11}
```

```text
$ python3 scripts/docs/verify_scoped_srs.py
{
  "status": "DOCUMENT_STATIC_PASS_ONLY",
  "chapters": 13,
  "functional_requirements": 16,
  "nonfunctional_requirements": 8,
  "authoritative_ac": 69,
  ...
  "human_joint_approval": "NOT_VERIFIED",
  "application_test": "NOT_EXECUTED",
  "performance_and_recovery": "NOT_RUN"
}
```

## 5. NOT RUN / 未验证

| 项目 | 状态 | 说明 |
|---|---|---|
| AC-IMPL-FR05-01～04、NF03-01、NF06-01 | NOT RUN | 需求层 AC 未执行；本包只做静态分析 |
| 运行时行为、并发决定覆盖、权限渗透 | NOT RUN | 无实现代码 |
| 性能/容量（NF02） | NOT RUN | 与 UC-06 无直接指标 |
| 通知/SLA | NOT RUN | 需求未定义（C-03/C-11） |
| 人工评审/教师评分 | NOT VERIFIED | 自动静态检查不等于签认 |

## 6. 待评审/待确认

| 编号 | 问题 | 为什么不能自行决定 | 当前处理 |
|---|---|---|---|
| C-01 | 审核认领权限的具体划分（是否按机构/对象/申请类型；是否存在审核员等级） | SRS §8 只写“审核权限”，FR14 只要求“遵守对应权限”；定义角色层级属需求变更 | 只用“有权审核的管理员”一个参与者；`User.reviewScope` 标待确认 |
| C-02 | 必要证据的最低集合与“可核验”判定口径 | FR05 只写“可核验证据”；沿用 UC-05 B-02/D-06 | 只做齐备性判定；缺失项进入 A3/A4 |
| C-03 | 审核 SLA/超时兜底 | D-06 未冻结 | 不写 SLA、不建计时类 |
| C-04 | 同一 Scholar 是否允许并列多个管理者/授权历史如何保留 | SPEC ID-12 待评审 | 只在无未决冲突时创建；`Auth→Scholar` 不标多重性 |
| C-05 | 争议“受控待办”载体、结案程序、授权解封/转移、申诉 | SPEC ID-12 待评审；待办入口属 FR14/UC-17；UC-05 B-06 | 只记录争议并保留原授权；`争议→…` 结案边不展开 |
| C-06 | `待补充` 申请能否被直接驳回/记争议 | `DGM-STATE-02` 只定义 `待补充→待审核` | 不新增边；先经 A4 放行再决定；登记为状态模型差异 |
| C-07 | 补证重提的校验口径与“放行”归属 | `DGM-STATE-02` 只写“本人重提[校验通过]”；UC-05 B-04 | 只表达“复核齐备后放行”，不定义阈值 |
| C-08 | 审计类命名与拆分（`ReviewEvidence`/`AuditEvidence`/`Evidence`） | D-13、UC-05 B-05 未统一 | 本 UC 用 `AuditEvidence` 表达决定留痕；R5 统一 |
| C-09 | 控制类/Boundary 命名与拆分（`ClaimCtl`/`ClaimCoordinator`/`ClaimReviewCoordinator`/`ClaimUI`/`ClaimReviewUI`） | 全局图与 `01-BCE` 各用一名，且合并提交/审核两侧；B-09/D-11 | 本 UC 用审核侧两名并登记 R5 |
| C-10 | 状态名口径：FR05 `通过/驳回` vs SPEC §3/`DGM-STATE-02` `已通过/已驳回` | 跨文档枚举统一，不能在单个 UC 内改名 | 按 FR05 表述，注明同义（UC-05 B-11） |
| C-11 | 决定/补证的通知渠道与内容 | SRS 未定义 | 只保证“可查询”（UC-05 B-07） |
| C-12 | 授权失败后的重试/再提交程序；审计失败时决定是否回滚 | `DGM-STATE-02` 只写保持原状态；回滚属实现阶段 | A6/A9 只约束“保持原状态、不虚报、无审计不报生效” |

## 7. 结论

```text
UC-06: STATIC COMPLETE
UML RENDER: 2/2 OK (SVG+PNG)
AC BASELINE: 69 UNCHANGED
RUNTIME TEST: NOT RUN
HUMAN REVIEW: 待评审
S02 STATUS: PARTIAL
```

UC-06 已按教材 §5.4.1–§5.4.4、§5.5.1–§5.5.4 完成一次可追溯的用例实现分析，并产出独立 VOPC。C-01～C-12 未解决前，不把 UC-06 标为“业务已批准/教师已验收”；提交与本人查询仍由 UC-05 分析，本包不代替。
