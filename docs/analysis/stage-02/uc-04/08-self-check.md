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
| 顺序图消息 | M1–M17 | 每条有发送者/接收者/职责/依据；E6/E9/A4–A6覆盖核对见04 §3.6 |
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

```text
python3 scripts/docs/check_documentation.py
STATIC_PASS_ONLY links=1245 svg=30 errors=0
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

## 5. 初始待确认清单（最终状态见第7节）

> 下表是R1初次提交时的问题清单；评审后的落实状态以第7节为准。

| 编号 | 问题 | 为什么不能自行决定 | 建议处理 |
|---|---|---|---|
| Q-01 | 注册最小字段、合法格式、错误反馈 | 正式基线只规定注册成功/失败语义；J02未冻结 | 参考现有SRS；未明确部分不新增强制字段，具体规则待需求确认 |
| Q-02 | 重复注册的幂等识别键、保留时长、重复响应形式 | 业务幂等已确认；FR04-03未定义键、时长和返回形式 | A-01：识别键/保留时长未冻结；M6只表达业务幂等，不指定键或存储 |
| Q-03 | 主页地址唯一/稳定/可分享的格式、分配/冲突/变更策略 | 评审已确认唯一/稳定/可分享；未规定格式 | 格式/分配后续确定；当前作为UserProfile属性 |
| Q-04 | 注册是否必须产生独立审计证据 | BR-IMP-10说“受保护写入”需留痕；注册是未认证账号创建 | 已核对：当前不要求独立注册审计证据；若扩大解释需需求变更 |
| Q-05 | `RegistrationUI`是否应统一为`AccountUI` | 现有全局图用`AccountUI`；本UC用局部名便于追踪 | R5统一分析类时决定；不因此声称新增全局类 |
| Q-06 | UserProfile公开字段集合 | 默认不得公开注册邮箱/联系方式等；具体字段集合未冻结 | 默认非公开已落实；具体字段集合单独确认，本UC不臆造默认值 |
| Q-07 | 注册成功是否在同一次交互建立会话 | 评审明确暂不确定，不得作为既定需求 | 当前M17只返回地址；不建立会话，后续再确认 |
| Q-08 | 其他UC追踪核查 | 需自行核对正反向一致性 | 已完成69条前向/反向核对，20个UC一致；见03与第7节 |
| Q-09 | UserProfile是否需要独立状态机 | 本UC只证明公开可见/零成果，没有草稿/发布/删除规则 | 已确认不建立复杂状态机；若后续FR06需要，再单独分析 |
| Q-10 | User—Authorization—Scholar关系缺口 | 本UC不使用Auth，不能在UC-04 VOPC中补 | R5必须修正全局模型；不能把本UC当授权分析 |

## 6. 结论

```text
UC-04 R1: STATIC COMPLETE (docs check errors=0)
UML RENDER: 2/2 OK
AC BASELINE: 69 UNCHANGED
RUNTIME TEST: NOT RUN
HUMAN REVIEW: 待评审
S02 STATUS: PARTIAL
```

UC-04已按教材§5.4.1–§5.4.4和§5.5.1–§5.5.4完成一次可追溯的用例实现分析，并产出了独立VOPC。已按独立审查F1–F10做文档/图最小修订；Q-01/Q-02/Q-03/Q-06/Q-07/Q-10仍待确认或后续阶段处理。在评审人确认前，不把UC-04标为“业务已批准/教师已验收”。

## 7. 人工评审决议落实（2026-10-21）

| 问题 | 评审决议 | 本包落实 | 状态 |
|---|---|---|---|
| Q-01 注册字段/校验 | 参考现有SRS；未明确部分继续登记，不擅自增加强制字段 | 01前置条件、02 E2/A1、08仍保留具体规则待定；未新增字段 | 部分解决；具体字段待需求确认 |
| Q-02 幂等 | 确认业务幂等；识别键和保留时长暂不冻结 | 01 A2、02 A2、04 M6/M7、06机制均写“业务幂等已确认，键/时长待Q-02” | 业务要求已解决；实现参数待定 |
| Q-03 主页地址 | 必须唯一、稳定、可分享；格式后续确定 | 01目标/后置/主流程/规则、02 E5、04 M11、06属性/机制已统一为“唯一、稳定、可分享” | 已解决；格式待后续 |
| Q-04 注册审计 | 先核对BR-IMP-10适用范围，再决定是否独立证据 | BR-IMP-10按“受保护写入”解释；注册是未认证账号创建，当前不纳入独立审计证据；03/05/07/08同步 | 已按当前基线解决；扩大解释需需求变更 |
| Q-05 UI命名 | 留第6阶段统一分析类命名 | 保持`RegistrationUI`局部名，07记录`AccountUI`映射，R5处理 | 已登记，待R5 |
| Q-06 公开字段 | 默认不公开注册邮箱、联系方式等；字段集合单独确认 | 01后置/A7/规则、02 A7、06 UserProfile属性均写默认不公开；具体集合仍Q-06 | 默认策略已解决；字段集合待确认 |
| Q-07 会话 | 暂不确定，不得作为既定需求 | 01范围、02 E8、03 Session拒绝、04 M17均不建立会话；08标记待确认 | 已避免擅自假设；待确认 |
| Q-08 其他UC追踪 | 由本分析自行核查 | 已执行69条前向/反向核对：20个UC一致，NF08仅实现测试，无其他UC差异；见03说明 | 已完成 |
| Q-09 UserProfile状态机 | 无明确业务状态要求时不必建立复杂状态机 | 01只列账号状态/公开可见性；未新增UserProfile状态图；06说明无复杂生命周期 | 已解决 |
| Q-10 全局Auth关系 | 留第6阶段统一分析类修复 | 07记录User—Authorization—Scholar缺口；本UC VOPC不引入Auth | 已登记，待R5 |

## 8. 评审后最终检查（2026-10-21）

| 检查 | 结果 |
|---|---|
| 事件流→消息 | M1–M17逐条有事件步骤；M11已更新为“唯一/稳定/可分享” |
| 消息→VOPC | 4个VOPC类均出现在序列；关系来自M2/M4/M7/M9/M10/M11/M13/M15/M16及1:1业务约束 |
| 控制类职责 | Coordinator只做校验、重复检查、创建协调、整体生效和结果返回；不承载User/UserProfile属性 |
| E6/E9/A4–A6覆盖 | 见04 §3.6；E6/E9/A5/A6无独立消息，A4由M15覆盖 | 不遗漏、不扩张 |
| 技术实现 | 未引入Java/DB/API/框架/中间件；仅保留分析级原子性/幂等/持久性描述 |
| 69 AC | 未新增、未删除、未改号；`verify_69_ac.py` PASS |
| 其他UC | 未改分析成果；仅做03追踪核对，未发现差异 |
| 渲染 | 两个PUML重新渲染；SVG XML 2/2可解析；PNG已目视 |

**评审后状态：** UC-04 分析可提交复核；Q-01具体字段、Q-02键/时长、Q-03格式、Q-06字段集合、Q-07会话、Q-10 R5仍待确认或后续阶段处理。正式“通过”仍需评审人确认。
