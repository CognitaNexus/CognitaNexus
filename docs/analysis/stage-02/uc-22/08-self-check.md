# UC-22 自检与待评审问题

**版本：** V1.0（2026-10-21）
**检查性质：** 静态文档/模型检查；不是产品运行测试，不是教师评分，不是人工评审签字。
**范围：** 只检查 UC-22 的分析包，以及本包的渲染与文档链接结果。

## 1. 范围与基线检查

| 检查 | 结果 | 证据 |
|---|---|---|
| 分支 | `analysis/s01-s02-69ac` | `git branch --show-current` |
| 正式 SRS/SPEC/S01 | 未修改 | `git status --short` 中不出现 `docs/srs`、`docs/spec`、`docs/analysis/stage-01` |
| 69 条 AC | 未新增、未删除、未改号 | `python3 scripts/docs/verify_69_ac.py` → PASS（69 AC unique；trace has all 69） |
| 全局 UML / 合并序列 / 活动图 | 未修改 | 本轮只新增 `stage-02/uc-22/` 下文件 |
| 其他 UC 目录 | 未修改 | 未写入 `uc-01`–`uc-04` |
| 共享追踪/登记文件 | 未修改 | `03-AC-UC-分析模型追踪.md`、`automation/analysis-class-registry.md` 保持原状，建议见 07 §6 |
| 其他成员未提交修改 | 未覆盖 | 工作区原有的 `analytics/collection/scripts` 等改动保持原状 |
| 图表引用 | 本包 6 个图表链接均可解析 | `check_documentation.py` 结果见 §4 |

## 2. 教材步骤对应

| 教材步骤 | 本包证据 | 状态 |
|---|---|---|
| §5.4.1 p154–155 完善用例文档 | [01-UC-22用例规约](01-UC-22-use-case-spec.md) | 已完成（未决项见 §6） |
| §5.4.2 p155–161 识别分析类 | [03-BCE分析类识别](03-bce-identification.md) | 已完成；5 个参与类 |
| §5.4.3 p161–181 分析交互 | [04-顺序图与逐消息职责](04-sequence-messages.md)、`DGM-SEQ-09-profile-edit` | 已完成；M1–M22 |
| §5.4.4 p181–183 构造 VOPC | [05-VOPC与关系依据](05-vopc.md)、`DGM-VOPC-05-profile-edit` | 已完成；非全局 BCE |
| §5.5.1 p188–190 职责 | [06-分析类定义](06-class-definition.md) Do/Know/分析操作 | 已完成 |
| §5.5.2 p190–191 属性 | 同 06，业务属性表 | 已完成本 UC 可证实部分；字段级细节标待确认 |
| §5.5.3 p192–200 关系/多重性 | 同 06 §7、05 §2 | 1:1 有依据；控制/边界到实体不定义持久多重性 |
| §5.5.4 p200–201 分析机制 | 同 06 §8 | 已定义 8 项机制 |
| §5.5.5 p201–204 统一分析类 | [07-与全局模型差异](07-global-model-diff.md) | 只提交 R5 输入，不重构全局图 |

## 3. 模型完整性检查

| 检查 | 结果 | 说明 |
|---|---|---|
| UC-22 参与者 | 注册用户（所有者）；无次要 Actor | 学术审核属 UC-07/08；读侧展示属 UC-03 |
| 主流程编号 | E1–E6 | 每步在 01/02 有来源 |
| 备选/异常 | A1–A7 | 均有正式来源或标注分析推导 |
| BCE 候选 | 5 个：`ProfileUI`/`ProfileCoordinator`/`User`/`UserProfile`/`AuditEvidence` | 见 03 §2 |
| 被拒绝候选 | Scholar/Auth/Claim/Authorship/WorkEntity/第二 UserProfile/Session/FieldPolicy/PendingProfileVersion/PublicProfileUI/AuditCoordinator/ProfileView/EditDraft | 见 03 §4 |
| 顺序图消息 | M1–M22 | 每条有发送者/接收者/职责/依据；覆盖核对见 04 §4 |
| VOPC 类数 | 5 | 与顺序图对象集合一致 |
| VOPC 是否含 Scholar/Auth/Claim/Work | 否 | 只含 UI/Coordinator/User/UserProfile/AuditEvidence |
| `User—UserProfile` | 1:1，编辑期不变量 | FR04；`AC-IMPL-FR04-03`；SRS §12 |
| 所有权是否误用 Scholar 授权 | 否 | 所有权来自 User；`Auth` 留给 UC-05/06 |
| 直接调用（绕过 UI）是否处理 | 是 | M3/M4/M5 服务端校验 → A2 拒绝 + 审计；SRS §8 |
| 是否创建第二主页/学术对象 | 否 | 无相应消息；A3 只拒绝转交 |
| 私有字段是否可能公开 | 否 | M18 公开投影只含允许公开字段；A7 |
| 是否出现实现类/数据库/API 设计 | 否 | 未指定事务、锁、表、接口或框架 |
| 待确认项是否被写成已确认规则 | 否 | B-01～B-07 均标待确认，并在 01/02/04/06 就近标注 |

## 4. 实际执行的验证

### 4.1 AC 与文档静态检查

```text
$ python3 scripts/docs/verify_69_ac.py
PASS: 69 AC unique; SRS text matches ledger; trace has all 69;
UC-01..19/22 present; A1c and scale markers present;
active references resolve.
GROUPS: {'FR': 54, 'IN01': 4, 'NF': 11}
```

```text
$ python3 scripts/docs/verify_scoped_srs.py
status: DOCUMENT_STATIC_PASS_ONLY, authoritative_ac: 69
performance_and_recovery: NOT_RUN, codearts: NOT_VERIFIED
```

```text
$ python3 scripts/docs/check_documentation.py
（JSON 输出字段摘录）
status: STATIC_PASS_ONLY, relative_links: 1356, uml_svg_pairs: 30, errors: []
external_urls_and_anchors: NOT_CHECKED, human_approval: NOT_VERIFIED, runtime_tests: NOT_EXECUTED
```

> 本包首次运行 `check_documentation.py` 时 `status: FAIL`，`errors` 为
> `docs/analysis/stage-02/uc-22/README.md: missing 08-self-check.md`（README 先于本文件写入）。
> 补写本文件后重跑为 `STATIC_PASS_ONLY`、`errors: []`；该 FAIL 记录保留，不隐藏。
> 基线（本轮开始前）为 `relative_links=1316 / uml_svg_pairs=30 / errors=[]`；`STATIC_PASS_ONLY` 只表示相对链接与 Word 包结构静态检查通过，不等于业务验收。

### 4.2 PlantUML 渲染

实际执行：

```bash
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-22/diagrams/DGM-SEQ-09-profile-edit.puml
plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-22/diagrams/DGM-SEQ-09-profile-edit.puml
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-22/diagrams/DGM-VOPC-05-profile-edit.puml
plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-22/diagrams/DGM-VOPC-05-profile-edit.puml
```

结果：

| 模型 | PUML | SVG | PNG | 目视检查 |
|---|---|---|---|---|
| UC-22 编辑序列 | 有 | 有 | 有 | 已查看：M1–M22、多层 `alt`/`else`、中文与图例可读；未认证/停用、非本人、学术字段、保存失败、待审版本分支完整 |
| UC-22 VOPC | 有 | 有 | 有 | 已查看：5 类、`User 1—1 UserProfile`、M2/M3/M4/M15/M18/M19/M8/M11/M20 标注可读；无 Scholar/Auth/Claim |

渲染成功只证明语法与可读性，不证明业务运行或评审通过。

### 4.3 静态文本检查

- `04` 中每条消息 M1–M22 都有发送者/接收者/事件步骤/职责/依据列；
- `05` 每条关系都有消息或 FR04/`AC-IMPL-FR04-03` 依据，未定义持久多重性的关系已说明理由；
- `06` 属性表区分本 UC 可证实部分与待确认部分（`pendingVersion`、字段清单、白名单）；
- `uc-22` 目录只包含 UC-22 文件；没有其他 UC 的规约或 VOPC；
- 文档内链接均指向存在的文件（`check_documentation.py` errors 为空）。

## 5. 未执行项（NOT RUN）

| 项目 | 状态 | 原因 |
|---|---|---|
| 产品运行测试 / 越权接口实测 | NOT RUN | 分析阶段不实现、不运行系统 |
| 69 AC 的实际验收判定 | NOT RUN | AC 计划验收；本包只做静态模型 |
| 人工/教师评审 | 待评审 | 需评审人确认 |
| 真实权限拦截验证（直接调用） | NOT RUN | 顺序图给出分析级守卫，未实现 |
| 性能/并发相关验证 | NOT RUN | 与 UC-22 无直接指标 |

## 6. 待确认问题

| 编号 | 问题 | 为什么不能自行决定 | 本包处理 |
|---|---|---|---|
| B-01 | 公共资料策略口径：普通字段是否一律即时生效、是否存在需审核的普通字段、待审版本是否适用普通编辑 | SRS 只写“按公共资料策略公开生效”，策略未冻结 | 顺序图保留审核分支（M19）并标待确认；不预设结论 |
| B-02 | 普通字段完整清单、格式校验规则、字段级公开白名单 | FR06 只列举昵称/头像/简介/兴趣领域/展示设置；字段级可见性与 SPEC Q-SPEC-06（A06）待决 | 06 属性表按列举写字段，白名单标待确认；不新增字段 |
| B-03 | 同一请求同时含普通字段与学术事实时的处置（整体拒绝 / 仅写普通字段） | 基线未规定 | A3 只规定“学术事实不写入”，混合处置标待确认 |
| B-04 | 展示设置范围（是否含字段级可见性开关、兴趣是否可隐藏） | FR06 未细化 | 作为属性 `displaySettings`，范围标待确认 |
| B-05 | 受控审计的保留期限与查询权限 | 属 FR14/UC-17；已登记 D-09 | 本 UC 只要求留痕存在且不含秘密 |
| B-06 | 会话/凭据是否成为独立分析对象 | UC-04 Q-07 未定；基线未定义会话对象 | 作为 M2 的“已认证请求上下文”，不建模 |
| B-07 | 命名统一：`ProfileCtl`/`ProfileCoordinator`、`Evidence`/`ReviewEvidence`/`AuditEvidence` | 全局图已有缩写名与一名两用 | 07 提 R5 建议，不改全局图 |
| B-08 | 追踪表是否把 `SEQ-09/VOPC-05` 与 FR07-03/NF03-01 的补充证据登记到 UC-22 行 | 共享追踪文件由父 Agent 维护 | 07 §6 提建议，本包不改 |

## 7. 结论

```text
UC-22 R1: STATIC COMPLETE (check_documentation status=STATIC_PASS_ONLY, errors=0)
UML RENDER: 2/2 OK (SVG+PNG)
AC BASELINE: 69 UNCHANGED (verify_69_ac PASS)
RUNTIME TEST: NOT RUN
HUMAN REVIEW: 待评审
S02 STATUS: PARTIAL（总状态与共享追踪由父 Agent 维护）
```

UC-22 已按教材 §5.4.1–§5.4.4 与 §5.5.1–§5.5.4 完成一次可追溯的用例实现分析，并产出独立于全局 BCE 的 VOPC。B-01/B-02/B-03/B-04/B-06 仍需需求确认，B-05/B-07/B-08 属后续阶段或父 Agent/R5 处理；在评审人确认前，不把 UC-22 标为“业务已批准/教师已验收”。
