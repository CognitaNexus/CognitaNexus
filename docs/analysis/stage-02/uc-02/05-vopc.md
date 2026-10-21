# UC-02 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-03-detail.puml`](diagrams/DGM-VOPC-03-detail.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-03-detail.svg) · [PNG](diagrams/generated/DGM-VOPC-03-detail.png)
**方法：** 教材 §5.4.4：VOPC 只来自本用例实现的顺序图对象；关系来自消息与业务约束；不等于全局 BCE 图。

## 1. 参与类集合

| 类 | 类型 | 来自顺序图消息 | 事件流依据 |
|---|---|---|---|
| `WorkDetailUI` | Boundary | M1/M2/M5/M6/M7/M8/M20–M23/M26/M28 | E1、E9、E10、E11、A1–A3 |
| `WorkDetailCoordinator` | Control | M2–M5/M7/M9/M11/M13/M15/M17/M19/M20 | E2–E9、A1、A3、A4、A6–A9 |
| `WorkEntity` | Entity | M3/M4/M9/M10/M11/M12 | E2、E3、E4、A1、A4、A8、A10 |
| `SourceRecord` | Entity | M13/M14 | E5、A5、A6 |
| `WorkVersion` | Entity | M15/M16 | E6、A7 |
| `Authorship` | Entity | M17/M18 | E7、A9 |

访客与原站不进入 VOPC：两者是参与者，不是系统类。

## 2. 关系与来源

| 关系 | 类型 | 消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `WorkDetailUI --> WorkDetailCoordinator` | 单向关联/消息关系 | M2、M5、M7、M20 | UI 请求、协调器返回/失败 | 不定义持久多重性 | 边界—控制交互关联，不是持久对象链接 |
| `WorkDetailCoordinator --> WorkEntity` | 单向关联/读取关系 | M3、M9、M11 | 协调器读取成果 | 不定义持久多重性 | 一次详情读取的调用关系 |
| `WorkDetailCoordinator --> SourceRecord` | 单向关联/读取关系 | M13 | 协调器读取可公开来源 | 不定义持久多重性 | 同上 |
| `WorkDetailCoordinator --> WorkVersion` | 单向关联/读取关系 | M15 | 协调器读取版本证据 | 不定义持久多重性 | 同上 |
| `WorkDetailCoordinator --> Authorship` | 单向关联/读取关系 | M17 | 协调器读取参与角色与状态 | 不定义持久多重性 | 同上 |
| `WorkEntity "1" — SourceRecord "0..*"` | 关联（来源证据） | 无直接消息；由 `AC-IMPL-FR02-01`、FR16、BR-IMP-02、INV-02 约束 | 成果聚合其来源记录 | 1 : 0..* | 一条成果可有多条来源；来源数不增加成果数 |
| `WorkEntity "1" — WorkVersion "0..*"` | 关联（明示版本证据） | FR02“须有证据才显示版本关系”；DD-08 | 成果拥有版本清单 | 1 : 0..* | 无明示证据时不建立该关系 |
| `WorkEntity "1" — Authorship "0..*"` | 关联（参与关系） | FR02 作者/参与者；FR15；SRS §7.2；INV-05 | 成果被参与关系引用 | 1 : 0..* | 解除一人关联不删除成果 |

### 2.1 为什么边界/控制到实体的关联不标多重性

M3/M9/M11/M13/M15/M17 是一次详情读取交互，不是长期对象包含关系。给 `WorkDetailCoordinator → SourceRecord` 标 `1..*` 会把“一次性读取协调”误解为“协调器持久拥有来源”。协调器只持有本次组装所需的分析状态（见 [06](06-class-definition.md) 第 3 节）。

### 2.2 为什么实体之间要标 1 : 0..*

三条实体关系不是交互关系，而是业务事实约束，且基线给出明确计数口径：

- `WorkEntity — SourceRecord`：`AC-IMPL-FR02-01` 要求“一条成果实体展示两条来源”；BR-IMP-02 要求“所有来源保留，重复来源不增加实体数”。因此必须能表达 1 : 0..*。
- `WorkEntity — WorkVersion`：FR02 只允许有明示证据的版本关系；DD-08 规定无明示关系保持独立。可 0（无版本关系）。
- `WorkEntity — Authorship`：§7.2“一个作者同一论文只能形成一条本人关系”；INV-05“解除关联不删除共享成果”。可 0（无人认领/未核验出参与关系）。

这三条关系在本 UC 中只被**读取**，其创建与维护属 UC-08/09/10/15。

### 2.3 为什么没有 `Scholar`、`Organization`、`Claim`、`Authorization`

| 排除项 | 理由 |
|---|---|
| `Scholar` | 本 UC 不从 Scholar 读取任何事实：参与角色/顺序/状态由 Authorship 提供；学者档案页面属 UC-03（是否内联入口为 Q-05） |
| `Organization` / `Team` | 承担单位、获奖团队按 §7.1 作为项目/获奖类型适用字段承载（机构事实），本 UC 无独立读取消息；是否独立成类影响 FR11/FR12，交 R5 |
| `Claim` / `ScholarAuthorization` | 认领与对象授权不参与详情读取（FR05；SRS §8；UC-05/06/07） |
| `SearchUI`/`SearchCoordinator` | 详情不检索、不筛选、不分页（UC-01） |
| `WorkDetail`/`DetailView` | 详情视图是 M20 的返回投影，不是持久实体 |

### 2.4 排除 `WorkEntity — Scholar` 直接关联

本 UC 不建立 `WorkEntity — Scholar` 关系：参与者信息经 `Authorship` 表达（角色/顺序/状态），直接连 Scholar 会绕过参与关系状态，容易把“未确认参与”显示成“已确认归属”（FR03-02、SRS §6.4、§12）。

## 3. 从顺序图到 VOPC

| 顺序图对象 | VOPC 类 | 保留/删除理由 |
|---|---|---|
| 访客 | 不保留 | 参与者，不是系统类 |
| 原站（外部来源） | 不保留 | 参与者，不是系统类；平台不与其交换业务消息 |
| WorkDetailUI | 保留 | M1/M2/M5–M8/M20–M23/M26/M28 |
| WorkDetailCoordinator | 保留 | M2–M5/M7/M9/M11/M13/M15/M17/M19/M20 |
| WorkEntity | 保留 | M3/M4/M9–M12 |
| SourceRecord | 保留 | M13/M14 |
| WorkVersion | 保留 | M15/M16 |
| Authorship | 保留 | M17/M18 |
| 其他对象 | 不保留 | 无消息；不为对称而新增 |

## 4. VOPC 完整性检查

| 检查 | 结果 |
|---|---|
| 只包含 UC-02 参与类 | 是，6 个 |
| 每个类有事件/消息依据 | 是 |
| 每条关系可追溯到消息或业务约束 | 是；实体间三条关系由 AC/BR/INV 约束支撑 |
| 不把 Actor 画成系统类 | 是；访客、原站均未进入 VOPC |
| 不用全局 BCE 冒充 VOPC | 是，独立于 `DGM-CLASS-01-analysis.puml` |
| 是否强行引入 Search/Claim/Authorization/Scholar | 否 |
| 是否引入实现技术 | 否（无数据库/API/框架/缓存） |
| 是否把来源数/版本数表达成成果数 | 否：1 : 0..* 明确区分 |

## 5. 与全局图关系

本 VOPC 是 UC-02 局部静态视图。与全局 `DGM-CLASS-01-analysis.puml` 的命名、职责与关系差异见 [07-与全局模型差异](07-global-model-diff.md)。R5 统一分析类时应以本 VOPC 为输入之一，本轮不修改全局图。
