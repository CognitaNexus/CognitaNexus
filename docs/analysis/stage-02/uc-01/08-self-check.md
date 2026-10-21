# UC-01 R2 自检与待评审问题

**版本：** V1.0（2026-10-21）
**检查性质：** 静态文档/模型检查；不是产品运行测试，不是教师评分，不是人工评审签字。
**范围：** 只检查UC-01与本轮S02状态修订。

## 1. 范围与基线检查

| 检查 | 结果 | 证据 |
|---|---|---|
| 分支 | `analysis/s01-s02-69ac` | `git branch --show-current` |
| 起点HEAD | `88c40458db256a2c43de01bcfacbf1712dad0446` | 本轮开始前记录 |
| 正式SRS/SPEC/S01 | 未修改 | `git diff --name-only -- docs/srs docs/spec docs/analysis/stage-01` 为空 |
| 69条AC | 未新增、未删除、未改号 | `verify_69_ac.py` PASS |
| 其他UC成果 | 未重写、未生成其他UC VOPC | 新增文件只在`stage-02/uc-01/`；S02只改状态/UC-01追踪 |
| 其他成员未提交修改 | 未覆盖 | 原工作区`analytics/collection/scripts`修改保持原状，未纳入本轮 |

## 2. 教材步骤对应

| 教材步骤 | 本包证据 | 状态 |
|---|---|---|
| §5.4.1 p154–155 完善用例文档 | [01-UC-01用例规约](01-UC-01-use-case-spec.md) | 已完成（未决项见第5节） |
| §5.4.2 p155–161 识别分析类 | [03-BCE分析类识别](03-bce-identification.md) | 已完成；4个参与类 |
| §5.4.3 p161–181 分析交互 | [04-顺序图与逐消息职责](04-sequence-messages.md)、`DGM-SEQ-06-search` | 已完成；M1–M23 |
| §5.4.4 p181–183 构造VOPC | [05-VOPC与关系依据](05-vopc.md)、`DGM-VOPC-02-search` | 已完成；非全局BCE |
| §5.5.1 p188–190 职责 | [06-分析类定义](06-class-definition.md) Do/Know/分析操作 | 已完成 |
| §5.5.2 p190–191 属性 | 同06业务属性表 | 已完成本UC可证实部分 |
| §5.5.3 p192–200 关系/多重性 | 同06第6节、05第2节 | 查询关联不定义持久多重性；无Work—Scholar关系 |
| §5.5.4 p200–201 分析机制 | 同06第7节 | 已定义本UC可证实机制 |
| §5.5.5 p201–204 统一分析类 | [07-与全局模型差异](07-global-model-diff.md) | 只提交R5输入，不重构全局图 |

## 3. 模型完整性检查

| 检查 | 结果 | 说明 |
|---|---|---|
| UC-01参与者 | 访客；无次要Actor | 系统不是外部Actor |
| 主流程编号 | 9步 | 每步在01/02有来源 |
| 备选/异常 | A1–A4 | A1空、A2非法、A3失败、A4学者+成果筛选 |
| BCE候选 | 4个：SearchUI/SearchCoordinator/WorkEntity/Scholar | 每个有事件/消息依据 |
| 被拒绝候选 | SearchContext/SearchResult/Organization/Field/SourceRecord/User/UserProfile/SearchIndex/QueryValidator | 见03第4节 |
| 顺序图消息 | M1–M23 | 每条有发送者/接收者/职责/依据 |
| VOPC类数 | 4 | 与顺序图对象集合一致 |
| VOPC是否含SearchContext/SearchResult | 否 | 上下文是协调器状态，结果是返回投影 |
| Work—Scholar关系 | 不建立 | 范围互斥，无消息；AC-01 |
| 成果/学者范围 | 互斥 | M7/M9分支；AC-01 |
| 空/失败 | 分离 | M15/M16 vs M11/M12 |
| 分页 | 同一上下文 | M6/M17/M19–M22；A-01待定 |
| Scholar与UserProfile | 不合并 | FR03；SRS §12；BR-IMP-03 |
| 是否创建/修改实体 | 否 | 只读 |
| 技术实现 | 未引入数据库/Java/API/框架/索引产品 | 只有分析职责 |

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
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-01/diagrams/DGM-SEQ-06-search.puml
plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-01/diagrams/DGM-SEQ-06-search.puml
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-01/diagrams/DGM-VOPC-02-search.puml
plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-01/diagrams/DGM-VOPC-02-search.puml
```

结果：

| 模型 | PUML | SVG | PNG | 目视检查 |
|---|---|---|---|---|
| 检索序列 | 有 | 有 | 有 | M1–M23、范围/失败/空/分页分支可读 |
| VOPC | 有 | 有 | 有 | 4类、两条查询关联、无Work—Scholar关系 |

SVG XML：2/2可解析。渲染成功不证明业务运行或教师评审通过。

## 5. 待评审/待确认

| 编号 | 问题 | 为什么不能自行决定 | 当前处理 |
|---|---|---|---|
| Q-01 | 允许的排序字段及其相关性/匹配层级 | SRS只说“选定字段”，未列集合 | 保留为待确认；当前只要求稳定ID破并列 |
| Q-02 | 分页大小、查询上下文有效期、同一读取版本机制 | FR01只要求保留上下文，未定义参数/机制 | 以A-01标注；不指定快照/索引实现 |
| Q-03 | 学者范围是否接受成果专属筛选 | FR01只明确“成果支持…”；未写学者筛选 | 当前按“成果筛选不适用于学者”，A4处理 |
| Q-04 | 真实/模拟数据在检索中的展示 | FR16/BR-IMP-07范围，不是FR01核心 | 不纳入本UC；UC-16/后续处理 |
| Q-05 | 检索结果是否需要总数/分页控件布局 | FR01/AC-02未要求总数 | 本UC只返回当前页+条件回显+上下文 |
| Q-06 | NF02性能超时/容量 | 500万/100并发是目标，未实测 | 仅作为失败/性能约束引用；NOT RUN |
| Q-07 | SearchContext是否需独立值对象 | 当前无独立生命周期证据 | 不建类；R5决定是否需要值对象 |
| Q-08 | 全局`SearchCtl→Scholar`关系 | 全局图未明确；本UC有M9 | 交R5统一，不直接改全局图 |

## 6. 结论

```text
UC-01 R2: STATIC COMPLETE
UML RENDER: 2/2 OK
AC BASELINE: 69 UNCHANGED
RUNTIME TEST: NOT RUN
HUMAN REVIEW: 待评审
S02 STATUS: PARTIAL
```

UC-01已按教材§5.4.1–§5.4.4和§5.5.1–§5.5.4完成一次可追溯的用例实现分析，并产出独立VOPC。Q-01/Q-02/Q-03/Q-04/Q-07/Q-08未解决前，不把UC-01标为“业务已批准/教师已验收”，也不进入UC-02。
