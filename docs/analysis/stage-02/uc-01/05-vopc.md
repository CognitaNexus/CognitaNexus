# UC-01 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-02-search.puml`](diagrams/DGM-VOPC-02-search.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-02-search.svg) · [PNG](diagrams/generated/DGM-VOPC-02-search.png)
**方法：** 教材§5.4.4：VOPC只来自本用例实现的顺序图对象，关系来自消息和业务约束；不是全局BCE。

## 1. 参与类集合

| 类 | 类型 | 来自顺序图消息 | 事件流依据 |
|---|---|---|---|
| `SearchUI` | Boundary | M1/M2/M4/M5/M11/M12/M15–M23 | E1、E8、E9、A1–A3 |
| `SearchCoordinator` | Control | M2–M4/M6–M23 | E2–E9、A1–A4 |
| `WorkEntity` | Entity | M7/M8 | E4、E6、E7 |
| `Scholar` | Entity | M9/M10 | E5、E6、E7 |

访客不进入VOPC，因为参与者不是系统类。

## 2. 关系与来源

| 关系 | 类型 | 消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `SearchUI --> SearchCoordinator` | 单向关联/消息关系 | M2、M4、M11、M15、M17、M20、M22 | UI请求/显示，Coordinator控制 | 不定义持久多重性 | 边界与控制器的交互关联，不是持久对象链接 |
| `SearchCoordinator --> WorkEntity` | 单向关联/查询关系 | M7 | Coordinator查询成果 | 不定义持久多重性 | 查询关联；不代表Coordinator持久拥有成果 |
| `SearchCoordinator --> Scholar` | 单向关联/查询关系 | M9 | Coordinator查询学者 | 不定义持久多重性 | 同上 |
| `WorkEntity — Scholar` | **不建立** | 无消息；范围互斥 | — | — | AC-01要求成果/学者不混排；本UC不需要关联两类实体 |

### 2.1 为什么控制/边界到实体的关联不标多重性

M7/M9是一次查询交互，不是长期对象包含关系；强行标`1..*`会把查询协调误解为持久聚合。`SearchCoordinator`只组合本次查询上下文（分析状态），不持久拥有Work/Scholar。

### 2.2 为什么没有 `SearchContext`、`SearchResult`

- `SearchContext`：FR01要求“切页保留查询上下文”，但没有要求独立业务身份、生命周期或对外行为；本UC作为协调器的单次分析状态，不造类。
- `SearchResult`：结果页是控制器返回的投影，不是业务实体；不进入VOPC。
- 若后续设计需要查询上下文值对象，应作为设计/统一分析类问题处理，不能反向改写本UC需求。

### 2.3 为什么没有 Work—Scholar 关系

`WorkEntity`和`Scholar`是范围互斥的两种查询结果：

- 成果范围不返回学者（AC-01）；
- 学者范围不返回成果；
- 查询交互没有在两者间传递消息；
- 成果—学者合作关系属于FR12/IN01，不是UC-01检索结果关系。

## 3. 从顺序图到VOPC

| 顺序图对象 | VOPC类 | 保留/删除理由 |
|---|---|---|
| 访客 | 不保留 | 不是系统类 |
| SearchUI | 保留 | M1/M2/M4/M5/M11/M12/M15–M23 |
| SearchCoordinator | 保留 | M2–M4/M6–M23 |
| WorkEntity | 保留 | M7/M8 |
| Scholar | 保留 | M9/M10 |
| 其他对象 | 不保留 | 无消息；不为了对称而新增 |

## 4. VOPC完整性检查

| 检查 | 结果 |
|---|---|
| 只包含UC-01参与类 | 是，4个 |
| 每个类有事件/消息依据 | 是 |
| 每条关系可追溯到消息或业务约束 | 是 |
| 不把Actor画成系统类 | 是 |
| 不用全局BCE冒充VOPC | 是，独立于`DGM-CLASS-01` |
| 是否强行建立Work—Scholar关系 | 否；范围互斥，无消息 |
| 是否引入设计技术 | 否 |

## 5. 与全局图关系

本VOPC是UC-01局部静态视图。与`DGM-CLASS-01-analysis.puml`的命名、职责差异见[07-与全局模型差异](07-global-model-diff.md)。R5统一分析类时应以本VOPC为输入之一。
