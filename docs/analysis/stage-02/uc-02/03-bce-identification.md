# UC-02 Boundary / Control / Entity 识别

**版本：** V1.0（2026-10-21）
**方法：** 教材 §5.4.2：从本 UC 事件流识别边界、控制、实体类；不从全局 BCE 反向填充，也不照抄全局 `DGM-CLASS-01`。
**输入：** 本目录 [01 用例规约](01-UC-02-use-case-spec.md)、[02 事件流与来源](02-event-flow-trace.md)。

## 1. 识别原则

1. Boundary 承接访客的详情请求与外链点击，并显示详情、不可访问、执行失败、外链失败。
2. Control 协调“按稳定标识取一条成果 + 取其类型字段/来源/版本/参与关系 + 按公开生效许可过滤”。
3. Entity 只承载可核实的持久业务事实：成果实体、来源记录、版本证据、参与关系。
4. 只有在本 UC 事件/消息中实际出现的对象进入 VOPC；参与角色、来源数、版本数都是**属性或关系事实**，不另造类。
5. 检索、认领、授权、治理核验、纠错、规模核对对象不属于本 UC，不强行纳入。

## 2. 候选分析类

| ID | 候选类 | 类型 | 发现事件 | 业务行为 | 为什么不能由其他候选承担 |
|---|---|---|---|---|---|
| C1 | `WorkDetailUI` | Boundary | E1、E9、E10、A1–A3 | 接收打开详情/打开外链；显示详情字段、未知标注、数据性质与时间、来源列表、外链、不可访问/失败/外链失败 | Entity 不应处理输入与展示；控制类不直接面对访客 |
| C2 | `WorkDetailCoordinator` | Control | E2–E9、A1、A3、A4、A6–A9 | 按稳定标识取一条成果，协调类型字段/来源/版本/参与关系的读取，按公开生效许可过滤，组装详情视图，区分不可访问与执行失败 | Boundary 只会显示；四个 Entity 各自只能回答自身领域事实，无法同时完成“一条成果 + 多来源 + 版本 + 参与”的组装与公开过滤 |
| C3 | `WorkEntity` | Entity | E2、E3、E4、A1、A4、A8、A10 | 提供公开生效状态、公共字段、四类适用专属字段、数据性质与更新时间 | SourceRecord 是来源证据不是去重成果；WorkVersion 只表达版本证据；Authorship 是参与关系；都不能替代成果实体本身 |
| C4 | `SourceRecord` | Entity | E5、A5、A6 | 提供来源 ID、来源域、许可或使用范围、采集/导入时间、原站地址、数据性质 | WorkEntity 只能说明“有哪些来源”，不能保留逐条来源原值与许可；许可/时间是来源级事实（FR16；INV-01） |
| C5 | `WorkVersion` | Entity | E6、A7 | 提供有明示证据的版本关系（预印本/正式发表） | WorkEntity 不该把版本混同为主体身份；版本与来源不同：版本是同一成果内部的时间/发布形态，来源是出处 |
| C6 | `Authorship` | Entity | E7、A9 | 提供作者/参与者角色、顺序、关系状态（自动关联/本人确认/待核验/争议/已排除） | 参与关系不是成果属性：解除一人关联不删成果（INV-05）；状态独立于成果公开状态（SRS §6.4/§11.3） |

## 3. 逐类选择理由

### C1 WorkDetailUI（Boundary）

- 发现于 E1 打开详情、E9 展示详情、E10 打开外链，A1/A2/A3 显示不可访问/外链失败/执行失败。
- 只做输入承接与展示，不判断公开许可、不合并来源、不解释版本证据。
- 与全局 `PublicPortalUI`（UC-01/02/03…）职责重叠但不相等：本 UC 只需要“单条成果详情”这一个边界，见 [07-与全局模型差异](07-global-model-diff.md)。

### C2 WorkDetailCoordinator（Control）

- 发现于 E2–E9 的完整组装过程与 A1/A3 的分支判定。
- 必须同时协调四类 Entity 读取，并在返回前统一执行公开/生效/许可过滤与待审值屏蔽（BR-IMP-01/10）。
- 不让 UI 承担业务过滤，也不让 WorkEntity 承担“组装其他对象”的协调职责。
- **不复用 `SearchCoordinator`**：详情读取没有关键词、筛选、排序、分页语义；把详情职责塞进检索协调器会让 UC-01 的检索语义与 UC-02 的展示语义耦合（见 07 第 3 节）。

### C3 WorkEntity（Entity）

- 发现于 E2（公开生效状态+公共字段）、E3（类型适用字段）、E4（数据性质/更新时间）。
- 必要事实：稳定 ID、类型、标题、公开生效状态、类型发生时间、数据性质、更新时间、四类专属字段。
- 不能并入 SourceRecord：来源数不增加成果数（INV-02），且 AC-FR02-01 明确要求“一条成果实体展示两条来源”。
- 不能并入 WorkVersion：版本是有证据的发布形态，不是成果身份。

### C4 SourceRecord（Entity）

- 发现于 E5（全部可公开来源）、A5（多来源）、A6（许可不明）。
- 必要事实：来源 ID、来源域、许可或使用范围、采集/导入时间、原站地址、数据性质、原始值。
- 独立理由：FR16 明确“每条来源保留来源ID、采集/导入时间、许可或使用范围与数据性质”；INV-01 要求原始记录不被清洗改写；来源撤稿只影响相应版本/证据，不自动删除成果（SPEC §5.5）。
- 不能并入 WorkEntity：否则无法表达“同一成果两条来源、许可不同、采集时间不同”，也无法保住原始值。

### C5 WorkVersion（Entity）

- 发现于 E6（明示版本关系）、A7（无证据）。
- 独立理由：FR02 要求“预印本/正式发表须有证据才显示版本关系”；DD-08 规定无明示关系保持独立。版本证据与出处不同，不能都塞进 SourceRecord。
- 不能并入 WorkEntity：版本数是“同一成果的版本清单”，与成果身份是 1:0..*，不是同一层概念。

### C6 Authorship（Entity）

- 发现于 E7（角色/顺序/状态）、A9（未确认状态）。
- 独立理由：SRS §7.2“一个作者同一论文只能形成一条本人关系”；INV-05“解除关联不删除共享成果”；§6.4 与 §11.3 规定参与关系统一状态，且“管理员批准不等于本人确认”。
- 不能并入 WorkEntity：参与者角色/顺序/状态是关系事实；也不能并入 Scholar——本 UC 只读取参与关系的公开表达，不读取学者档案（见第 4 节拒绝项与 Q-05）。

## 4. 被拒绝的候选类

| 候选 | 拒绝理由 | 依据/后续 |
|---|---|---|
| `SearchUI` / `SearchCoordinator` | 详情不检索、不筛选、不分页；强行复用会把检索语义带入只读详情 | UC-01；07 第 3 节 |
| `Claim` / `ClaimApplication` / `ScholarAuthorization` | 认领与对象授权不参与详情读取；访客对公开详情只有读权限 | FR05；SRS §8；UC-05/06/07 |
| `Scholar` | 本 UC 只读取参与关系的公开角色/顺序/状态，不向 Scholar 发消息；是否内联学者门户入口属 UC-03 边界（Q-05） | FR03；UC-03；无消息不建类 |
| `Organization` / `Team` | 承担单位、获奖团队在 §7.1 中作为项目/获奖的类型适用字段（机构事实）承载，本 UC 无独立查询行为；是否独立成类影响 FR11/FR12，交 R5 | SRS §7.1；UC-11/UC-14；07 第 4 节 |
| `WorkDetail` / `DetailView` / `DetailDTO` | 详情视图是协调器返回给边界的投影，不是持久业务实体 | 教材 §5.4.2；分析推导 |
| `FieldDictionary` / `TypeSchema` | 四类字段是 WorkEntity 的类型适用属性，没有独立生命周期或对外行为 | SRS §7.1；分析推导 |
| `Rights` / `PublicPermission` | 许可是来源级属性（SourceRecord）加控制器的过滤规则，无独立业务身份 | FR16；BR-IMP-01 |
| `DataOrigin` | 真实/模拟/混合是互斥标识属性，不是实体 | BR-IMP-07；SRS §7.1 |
| `SourceAdapter` / `ExternalSiteGateway` | 平台不代理原站、不抓取全文；外链只跳转，本 UC 与原站无业务消息 | FR02；SRS §2.1；UC-18/19 |
| `VersionComparison` | 版本比较不是 FR02 要求，只读取“是否有明示版本关系” | FR02；分析推导 |
| `ViewLog` / `BehaviorTrace` | O02 延期；详情不采集行为画像 | SRS §5.2；BR-IMP-12 |
| `RedactionPolicy` / `Masking` | 字段级遮蔽由控制器过滤规则承担，无独立状态证据；私有证明不公开是规则而非类 | BR-IMP-11；NF06 |

## 5. 边界结论

| 边界 | 结论 | 证据 |
|---|---|---|
| WorkEntity vs SourceRecord | 成果是去重实体；来源是逐条出处证据；来源数不增加成果数 | `AC-IMPL-FR02-01`、`AC-IMPL-FR16-02`；BR-IMP-02；INV-02 |
| WorkEntity vs WorkVersion | 版本只在有明示证据时关联，属于成果内部发布形态 | FR02；DD-08 |
| Authorship vs WorkEntity | 参与关系独立带状态；解除关联不删成果 | SRS §6.4；INV-05；FR15 |
| Authorship vs 合作边 | 详情只显示参与角色；共同成果合作边是 FR12/IN01 的派生视图 | FR12；IN01；本 UC 不建边 |
| 许可边界 | 只有可公开许可的来源进入公开详情；许可不明不扩大公开范围 | BR-IMP-01；SPEC §3 |
| 公开边界 | 只展示当前公开且生效的成果与允许的关系；待审新值不展示 | FR02 执行前提；SRS §6.4；BR-IMP-10 |
| 全文边界 | 平台只提供原站外链，不托管、不代理全文 | FR02；SRS §2.1 |
| 外链失效边界 | 外链失效不是成果失效：不删除、不隐藏、元数据仍可读 | `AC-IMPL-FR02-02`；BR-IMP-04 |
| 写边界 | 详情只读；确认/补录/纠错/授权不在本 UC | FR02；UC-08；UC-05/06 |
| 检索边界 | 不引入 Search 边界/控制 | UC-01；任务边界 |

## 6. 识别结论

本 UC 的参与分析类为 6 个：

- Boundary：`WorkDetailUI`
- Control：`WorkDetailCoordinator`
- Entity：`WorkEntity`、`SourceRecord`、`WorkVersion`、`Authorship`

不引入 `SearchUI`/`SearchCoordinator`、`Claim`/`ScholarAuthorization`、`Scholar`、`Organization`、详情 DTO、字段字典、许可策略、`SourceAdapter`、行为日志等类；这些要么属于其他 UC，要么是属性/投影/实现设计。
