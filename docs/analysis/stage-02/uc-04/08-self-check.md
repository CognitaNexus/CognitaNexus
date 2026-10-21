# UC-04 R1 自检与待评审问题

**版本：** V1.0（2026-10-21）
**检查性质：** 静态文档/模型检查；不是产品运行测试，不是教师评分，不是人工评审签字。
**范围：** 只检查UC-04与本轮S02状态修订。

## 1. 范围与基线检查

| 检查 | 结果 | 证据 |
|---|---|---|
| 分支 | `analysis/s01-s02-69ac` | `git branch --show-current` |
| 起点HEAD | `cafe74b355e332b1036dd03f583d43419e4ae61e` | 本轮开始前记录 |
| 正式SRS/SPEC/S01 | 未修改 | `git diff --name-only -- docs/srs docs/spec docs/analysis/stage-01` 为空 |
| 69条AC | 未新增、未删除、未改号 | `verify_69_ac.py` PASS |
| 其他UC成果 | 未重写、未生成其他UC VOPC | 新增文件只在`stage-02/uc-04/`；S02只改状态说明 |
| 其他成员未提交修改 | 未覆盖 | 原工作区`analytics/collection/scripts`修改保持原状，未纳入本轮 |

## 2. 教材步骤对应

| 教材步骤 | 本包证据 | 状态 |
|---|---|---|
| §5.4.1 p154–155 完善用例文档 | [01-UC-04用例规约](01-UC-04-use-case-spec.md) | 已完成（未决项见第5节） |
| §5.4.2 p155–161 识别分析类 | [03-BCE分析类识别](03-bce-identification.md) | 已完成；4个参与类 |
| §5.4.3 p161–181 分析交互 | [04-顺序图与逐消息职责](04-sequence-messages.md)、`DGM-SEQ-05-register` | 已完成 |
| §5.4.4 p181–183 构造VOPC | [05-VOPC与关系依据](05-vopc.md)、`DGM-VOPC-01-register` | 已完成；非全局BCE |
| §5.5.1 p188–190 职责 | [06-分析类定义](06-class-definition.md) Do/Know/分析操作 | 已完成 |
| §5.5.2 p190–191 属性 | 同06，业务属性表 | 已完成本UC可证实部分 |
| §5.5.3 p192–200 关系/多重性 | 同06第6节、05第2节 | 1:1有依据；控制/边界到实体的单向关联不定义持久多重性 |
| §5.5.4 p200–201 分析机制 | 同06第7节 | 已定义本UC可证实机制 |
| §5.5.5 p201–204 统一分析类 | [07-与全局模型差异](07-global-model-diff.md) | 只提交R5输入，不重构全局图 |

## 3. 模型完整性检查

| 检查 | 结果 | 说明 |
|---|---|---|
| UC-04参与者 | 访客；无次要Actor | 系统不作为外部Actor |
| 主流程编号 | 9步 | 每步在01/02有来源 |
| 备选/异常 | A1–A7 | 均有正式来源或标注分析推导 |
| BCE候选 | 4个：UI/Coordinator/User/UserProfile | 每个有事件流/消息依据 |
| 被拒绝候选 | Scholar/Auth/Claim/Authorship/Work/AddressAllocator/Session/Evidence | 见03第4节 |
| 顺序图消息 | M1–M17 | 每条有发送者/接收者/职责/依据 |
| VOPC类数 | 4 | 与顺序图对象集合一致 |
| VOPC是否含Scholar/Auth/Claim | 否 | 只含UI/Coordinator/User/UserProfile |
| User—UserProfile | 1:1 | FR04；AC-IMPL-FR04-03；SRS §12 |
| 普通主页所有权是否误用ScholarAuth | 否 | 所有权来自UserProfile；Auth留给UC-05/06 |
| 是否创建Work/Authorship | 否 | 零成果只显示空态 |
| 是否出现实现类/数据库/API设计 | 否 | 文档只提分析职责；未指定技术 |
| 是否把M6/M12假设写成已确认规则 | 否 | M6幂等键=Q-02；M12实现方式=设计阶段 |

## 4. 实际执行的验证

### 4.1 AC与文档

```text
python3 scripts/docs/verify_69_ac.py
PASS: 69 AC unique; SRS text matches ledger; trace has all 69;
UC-01..19/22 present; A1c and scale markers present;
active references resolve.
GROUPS: {'FR': 54, 'IN01': 4, 'NF': 11}
```

```text
python3 scripts/docs/verify_scoped_srs.py
DOCUMENT_STATIC_PASS_ONLY 69
```

### 4.2 PlantUML渲染

实际执行：

```bash
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-04/diagrams/DGM-SEQ-05-register.puml
plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-04/diagrams/DGM-SEQ-05-register.puml
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-04/diagrams/DGM-VOPC-01-register.puml
plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-04/diagrams/DGM-VOPC-01-register.puml
```

结果：

| 模型 | PUML | SVG | PNG | 目视检查 |
|---|---|---|---|---|
| 注册序列 | 有 | 有 | 有 | 已查看：M1–M17、alt分支、图例可读 |
| VOPC | 有 | 有 | 有 | 已查看：4类、1:1、单向关联可读 |

SVG XML：2/2可解析。渲染成功只证明语法和可读性，不证明业务运行/教师评审通过。

### 4.3 静态文本检查

- `04`中每条消息M1–M17都有发送者/接收者/职责/依据列；
- `05`每条关系都有消息或FR04/AC/§12依据；
- `06`属性表区分“已证实/分析推导/待确认”；
- `uc-04`目录只包含UC-04文件；没有其他UC的VOPC或规约。

## 5. 待人工决议/待确认

| 编号 | 问题 | 为什么不能自行决定 | 建议处理 |
|---|---|---|---|
| Q-01 | 注册最小字段、合法格式、错误反馈 | 正式基线只规定注册成功/失败语义；J02未冻结 | 由需求负责人确认字段与校验规则；当前只按“不满足规则则拒绝”分析 |
| Q-02 | 重复注册的幂等识别键、保留时长、重复响应形式 | FR04-03只要求不产生第二主页，未定义键和返回形式 | 设计/SPEC阶段决定；本UC只保留M6分析假设A-01 |
| Q-03 | 稳定公开地址格式、分配/冲突/变更策略 | FR04/AC-FR04-03只要求稳定可分享，未规定格式 | 需求/SPEC负责人确认；当前作为UserProfile属性 |
| Q-04 | 注册是否必须产生独立审计证据 | BR-IMP-10说“受保护写入”需留痕，但FR04未明确注册是否属于该范围 | 与审计/安全负责人确认；当前不把Evidence放入VOPC |
| Q-05 | `RegistrationUI`是否应统一为`AccountUI` | 现有全局图用`AccountUI`；本UC用局部名便于追踪 | R5统一分析类时决定；不因此声称新增全局类 |
| Q-06 | UserProfile公开字段默认集合 | FR06规定可编辑字段；注册时是否有默认值未冻结 | UC-22/FR06分析时确认；本UC不臆造默认值 |
| Q-07 | 注册成功是否在同一次交互建立会话 | 本UC只处理账号/主页；登录/会话独立 | 账号操作/UC-04增量分析时确认；当前M17只返回地址 |
| Q-08 | 全局追踪反向UC-04缺少FR03-03/04 | `03`原反向表与`02`UC-04不一致 | 已在本轮把FR03-03/04补入UC-04反向追踪；其他UC行未改，R5再全量核查 |
| Q-09 | UserProfile是否需要独立状态机 | 本UC只证明公开可见/零成果，没有草稿/发布/删除规则 | 不画复杂状态机；若后续FR06需要，再单独分析 |
| Q-10 | User—Authorization—Scholar关系缺口 | 本UC不使用Auth，不能在UC-04 VOPC中补 | R5必须修正全局模型；不能把本UC当授权分析 |

## 6. 结论

```text
UC-04 R1: STATIC COMPLETE
UML RENDER: 2/2 OK
AC BASELINE: 69 UNCHANGED
RUNTIME TEST: NOT RUN
HUMAN REVIEW: 待评审
S02 STATUS: PARTIAL
```

UC-04已按教材§5.4.1–§5.4.4和§5.5.1–§5.5.4完成一次可追溯的用例实现分析，并产出了独立VOPC。第5节问题未解决前，不把UC-04标为“业务已批准/教师已验收”，也不进入R2。
