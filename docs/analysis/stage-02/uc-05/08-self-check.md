# UC-05 自检与待评审问题

**版本：** V1.0（2026-10-21）
**检查性质：** 静态文档/模型检查；不是产品运行测试，不是教师评分，不是人工评审签字。
**范围：** 只检查 UC-05 分析包及本包的渲染与文档链接结果。

## 1. 范围与基线检查

| 检查 | 结果 | 证据 |
|---|---|---|
| 分支 | `analysis/s01-s02-69ac` | `git branch --show-current` |
| 起点 HEAD | `4209cf00f26d0b9912e2821ef906bf87c00e3c54` | 本轮开始前记录 |
| 正式 SRS/SPEC/S01 | 未修改 | 本轮只新增 `docs/analysis/stage-02/uc-05/` |
| 69 条 AC | 未新增、未删除、未改号 | `python3 scripts/docs/verify_69_ac.py`（§4） |
| 全局 UML / 合并认领序列 / 状态图 / 活动图 | 未修改 | 只读比对，见 07 |
| 其他 UC 目录 | 未修改 | 未写入 `uc-01`–`uc-04`、`uc-22` |
| 共享追踪/登记文件 | 未修改 | `03-AC-UC-分析模型追踪.md`、`automation/analysis-class-registry.md`、`automation/unresolved-decisions.md` 保持原状；登记建议见 07 §6 |
| 其他成员未提交修改 | 未覆盖 | 工作区原有 `docs/uml/analytics`、`docs/uml/collection`、`scripts/docs` 修改保持原状 |

## 2. 教材步骤对应

| 教材步骤 | 本包证据 | 状态 |
|---|---|---|
| §5.4.1 p154–155 完善用例文档 | [01-UC-05用例规约](01-UC-05-use-case-spec.md) | 已完成（未决项见 §6） |
| §5.4.2 p155–161 识别分析类 | [02-事件流与需求来源](02-event-flow-trace.md)、[03-BCE分析类识别](03-bce-identification.md) | 已完成；7 个参与类 |
| §5.4.3 p161–181 分析交互 | [04-顺序图与逐消息职责](04-sequence-messages.md)、`DGM-SEQ-10-claim` | 已完成；M1–M36 |
| §5.4.4 p181–183 构造 VOPC | [05-VOPC与关系依据](05-vopc.md)、`DGM-VOPC-06-claim` | 已完成；非全局 BCE |
| §5.5.1 p188–190 职责 | [06-分析类定义](06-class-definition.md) Do/Know/候选分析操作 | 已完成 |
| §5.5.2 p190–191 属性 | 同 06 业务属性表 | 已完成本 UC 可证实部分；证据集合/状态理由细节标待确认 |
| §5.5.3 p192–200 关系/多重性 | 同 05 §2、06 §9 | 仅申请→申请人、申请→目标标 `0..*:1`；其余不定义或标未定 |
| §5.5.4 p200–201 分析机制 | 同 06 §10 | 已定义 11 项机制 |
| §5.5.5 p201–204 统一分析类 | [07-与全局模型差异](07-global-model-diff.md) | 只提交 R5 输入，不重构全局图 |

## 3. 模型完整性检查

| 检查 | 结果 | 说明 |
|---|---|---|
| UC-05 参与者 | 注册用户（登录用户）；无次要 Actor | 审核管理员属 UC-06 |
| 主流程编号 | E1–E11 | 每步在 01/02 有来源 |
| 备选/异常 | A1a/A1b、A2–A8 | 均有正式来源或标注分析推导 |
| BCE 候选 | 7 个：`ClaimUI`/`ClaimCoordinator`/`User`/`Scholar`/`ClaimApplication`/`ReviewEvidence`/`ScholarAuthorization` | 见 03 §2 |
| 被拒绝候选 | UserProfile、Authorship、WorkEntity、AuditEvidence、ClaimReviewCoordinator、ConflictQueue、Notification、EvidenceFile、ClaimRequest、ClaimView、Session、Reviewer、ScholarMerge | 见 03 §4 |
| 顺序图消息 | M1–M36 | 每条有发送者/接收者/职责/依据；覆盖核对见 04 §4 |
| VOPC 类数 | 7 | 与顺序图对象集合一致 |
| VOPC 是否含 UserProfile/Authorship/Work | 否 | 只含 UI/Coordinator/User/Scholar/Claim/Evidence/Auth |
| UserProfile 是否被创建/合并 | 否 | 无 UserProfile 消息；AC-IMPL-FR05-04 |
| ScholarAuthorization 是否被创建/修改/转移 | 否 | M17/M18 只读；虚线依赖 |
| 是否创建 Scholar | 否 | 只引用已收录档案；A3 拒绝不可认领目标 |
| 是否自动确认 Authorship | 否 | 无 Authorship/WorkEntity 消息；SPEC ID-07 |
| 状态归属 | 提交侧只产生待审核（及 A1b 待补充） | 通过/驳回/争议只读；产生属 UC-06 |
| 缺必要证据 | 两种基线允许的处置都保留 | A1a 拒绝 / A1b 待补充；B-01 |
| 证据可见性 | 私有，仅本人与有权审核者 | M14/M20/M29/M35；AC-IMPL-NF06-01 |
| 拒绝/失败/空/无权 | 分开表达 | A2/A3/A6 vs A4 vs A7 vs A8 |
| 是否含审核决定 | 否 | 明确排除；04 §3.4 |
| 技术实现 | 未引入数据库/Java/API/框架/上传实现 | 只有分析职责 |

## 4. 实际执行的验证

### 4.1 AC 与文档

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
}
```

```text
$ python3 scripts/docs/check_documentation.py
{
  "status": "STATIC_PASS_ONLY",
  "relative_links": 1397,
  "uml_svg_pairs": 30,
  "errors": [],
  "runtime_tests": "NOT_EXECUTED",
  "human_approval": "NOT_VERIFIED"
}
```

`check_documentation.py` 为静态链接/文档检查，`runtime_tests: NOT_EXECUTED`、`human_approval: NOT_VERIFIED`。

### 4.2 PlantUML 渲染

实际执行（仓库根目录）：

```bash
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-05/diagrams/DGM-SEQ-10-claim.puml
plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-05/diagrams/DGM-SEQ-10-claim.puml
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-05/diagrams/DGM-VOPC-06-claim.puml
plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-05/diagrams/DGM-VOPC-06-claim.puml
```

结果：

| 模型 | PUML | SVG | PNG | 目视检查 |
|---|---|---|---|---|
| 提交/查询序列 | 有 | 有（无 Syntax Error） | 有 | M1–M36 可读；A1a/A1b、A2–A4、A6–A8 分支与中文标签正常 |
| VOPC | 有 | 有（无 Syntax Error） | 有 | 7 类；只读虚线到 `ScholarAuthorization`；申请→申请人/目标标 `0..*:1`；无 UserProfile/Authorship |

渲染成功不证明业务运行或教师评审通过；PNG/SVG 由 PlantUML 生成，未手工修改。

## 5. NOT RUN / 未验证

| 项目 | 状态 | 说明 |
|---|---|---|
| AC-IMPL-FR05-01～04、NF03-01、NF06-01 | NOT RUN | 需求层 AC 未执行；本包只做静态分析 |
| 运行时行为、并发、权限渗透 | NOT RUN | 无实现代码，不做运行测试 |
| 性能/容量（NF02） | NOT RUN | 与 UC-05 无直接指标，沿用既有 NOT RUN |
| 通知/SLA | NOT RUN | 需求未定义（B-07/B-08） |
| 人工评审/教师评分 | NOT VERIFIED | 自动静态检查不等于签认 |

## 6. 待评审/待确认

| 编号 | 问题 | 为什么不能自行决定 | 当前处理 |
|---|---|---|---|
| B-01 | 缺必要证据时“直接拒绝”还是“标待补充”（AC-IMPL-FR05-01 允许两者） | 需求并列两种处置，未指定策略 | A1a/A1b 都画并标待确认；对应 D-06 |
| B-02 | 必要证据的最低集合与“可核验”判定标准（材料类型、是否必须含外部稳定标识） | FR05 只写“可核验证据” | 只要求齐备性校验；列出缺失项；D-06 |
| B-03 | 同一用户对同一 Scholar 重复提交、撤回、申诉的处理 | FR05/AC 未定义 | 不建模、不虚构幂等规则 |
| B-04 | 补证后的材料版本与重提放行口径（`待补充→待审核`） | `DGM-STATE-02` 只写“本人重提[校验通过]”，校验内容未定 | 归 UC-06 放行；本包只标待确认 |
| B-05 | 提交/查询是否另需独立审计留痕类 | BR-IMP-10 要求受保护写入留痕，但类的划分未定；`Evidence`/`ReviewEvidence`/`AuditEvidence` 命名冲突（D-13） | 本 UC 用申请记录承载；登记 D-13 |
| B-06 | 竞争认领的受控待办载体与标记方式 | AC-IMPL-FR05-03 只说“进入受控待办”，载体属治理/后台（UC-06/UC-17）；授权转移与申诉程序待评审 | 只记录竞争事实；SPEC ID-12 |
| B-07 | 待补充/决定的通知渠道与内容 | SRS 全文未出现通知要求 | 只保证“可查询”，不写通知机制 |
| B-08 | 审核 SLA | 未冻结（D-06） | 不写 SLA |
| B-09 | 控制类命名与拆分（`ClaimCtl` / `ClaimCoordinator` / `ClaimReviewCoordinator`） | 全局图与 `01-BCE` 各用一名且合并提交/审核两侧 | 本 UC 用 `ClaimCoordinator` 并登记 R5/D-11 |
| B-10 | 状态图是否增加“提交时缺证→待补充”的边 | 与 B-01 绑定；本 UC 不改状态图 | 交 R5/需求确认（07 §5） |
| B-11 | 状态名口径：FR05 `通过/驳回` vs SPEC §3 与 `DGM-STATE-02` `已通过/已驳回` | 属于跨文档枚举统一，不能在单个 UC 内单方面改号/改名 | 本包按 FR05 表述用 `通过/驳回`，并在 05/06 注明同义；R5 统一 |
| B-12 | SRS §11.3 列 `待提交` 草稿态，但 SPEC §3/`DGM-STATE-02` 无该状态，UC-05 不产生 | 状态模型/SPEC 与 SRS 枚举差异，不能由本 UC 补写 | 交 R5/状态模型统一；本 UC 不把 `待提交` 写入状态集 |

## 7. 结论

```text
UC-05: STATIC COMPLETE
UML RENDER: 2/2 OK (SVG+PNG)
AC BASELINE: 69 UNCHANGED
RUNTIME TEST: NOT RUN
HUMAN REVIEW: 待评审
S02 STATUS: PARTIAL
```

UC-05 已按教材 §5.4.1–§5.4.4、§5.5.1–§5.5.4 完成一次可追溯的用例实现分析，并产出独立 VOPC。B-01～B-12 未解决前，不把 UC-05 标为“业务已批准/教师已验收”；审核决定、授权生效与争议处理仍由 UC-06 分析，本包不代替。
