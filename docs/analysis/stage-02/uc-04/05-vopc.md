# UC-04 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-01-register.puml`](diagrams/DGM-VOPC-01-register.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-01-register.svg) · [PNG](diagrams/generated/DGM-VOPC-01-register.png)
**方法：** 教材§5.4.4：VOPC只来自本用例实现的顺序图对象，关系来自消息和业务约束；不是全局BCE。

## 1. 参与类集合

VOPC只包含以下4个类：

| 类 | 类型 | 来自顺序图对象 | 事件流依据 |
|---|---|---|---|
| `RegistrationUI` | Boundary | M1、M2、M4–M8、M13–M17 | E1、E8、A1–A3 |
| `RegistrationCoordinator` | Control | M2–M4、M6–M7、M9–M16 | E2–E8、A1–A3 |
| `User` | Entity | M9 | E3、E6、A6 |
| `UserProfile` | Entity | M10、M11、M15 | E4、E5、E7、A4 |

访客不进入VOPC，因为参与者不是系统内部类（教材§5.4.4）。

## 2. 关系与来源

| 关系 | 类型 | 来自消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `RegistrationUI --> RegistrationCoordinator` | 单向关联/消息关系 | M2、M4、M7、M13、M16 | UI请求/结果显示；Coordinator为控制端 | 不定义持久多重性 | UI不是实体；这是按消息方向的单向关联，不是持久对象链接 |
| `RegistrationCoordinator --> User` | 单向关联/创建-使用关系 | M9 | Coordinator创建/校验账号 | 不定义持久多重性 | 单向关联针对一次注册协调，不代表Coordinator持久拥有User |
| `RegistrationCoordinator --> UserProfile` | 单向关联/创建-使用关系 | M10、M11、M15 | Coordinator创建/绑定/发布主页 | 不定义持久多重性 | 同上；不把协调器做成主页容器 |
| `User "1" -- "1" UserProfile` | 业务关联 | FR04；AC-IMPL-FR04-03；SRS §12“User 1:1 UserProfile” | User“唯一拥有”UserProfile | 1—1 | 成功注册后恰好一个UserProfile；失败不得留下单边对象 |

### 2.1 为什么控制/边界向实体的单向关联不标持久多重性

`RegistrationUI`/`RegistrationCoordinator`到实体的单向关联按**消息/创建方向**表达，不是长期业务对象链接。为它们强行标`1..*`会把一次用例的协调关系误解为持久聚合关系；因此本VOPC明确不定义其持久多重性。真正需要业务多重性的只有`User—UserProfile`，其两端均为`1`。

### 2.2 为什么没有 `Scholar`、`Auth`、`Claim`、`Authorship`

- `Scholar`：本UC没有任何消息发送给Scholar；注册后置明确“不创建Scholar”，同名Scholar属于UC-03的展示边界（FR03-04），不是UC-04参与对象。
- `ScholarAuthorization`/`Auth`：本人普通主页的编辑权来自UserProfile所有权（FR04/BR-IMP-05），不是Scholar对象授权；授权只在UC-05/06批准后产生。
- `Claim`：注册不是认领，没有申请、证据、审核状态。
- `Authorship`/`WorkEntity`：注册不创建成果/参与关系；零成果是主页显示空态（AC-IMPL-FR03-03），不是空实体。
- `ReviewEvidence`/`AuditEvidence`：FR04没有把注册定义为必须产生独立审计证据；是否适用BR-IMP-10待Q-04。
- `AddressAllocator`：稳定地址是UserProfile的属性/职责；没有独立的地址生命周期或分配策略证据，不能凭空造类。
- `Session`：本UC没有登录/会话建立消息；访问守卫在04 §3.5作为后置边界说明。

## 3. 从顺序图到VOPC的对应

| 顺序图对象 | VOPC类 | 保留/删除理由 |
|---|---|---|
| 访客（Actor） | 不保留 | 不是系统类 |
| RegistrationUI | 保留 | 接收M1、返回M4/M7/M13/M16 |
| RegistrationCoordinator | 保留 | 协调M3/M6/M9–M12/M15 |
| User | 保留 | 接收M9 |
| UserProfile | 保留 | 接收M10/M11/M15 |
| 无其他对象 | — | 没有Scholar/Auth/Claim等消息 |

## 4. VOPC完整性检查

| 检查 | 结果 |
|---|---|
| 只包含UC-04参与类 | 是，4个 |
| 每个类有事件流/消息依据 | 是，见表1/表2 |
| 每条关系可追溯到消息或业务约束 | 是，见表2 |
| 不把Actor画成系统类 | 是 |
| 不用全局BCE冒充VOPC | 是；本图独立于`DGM-CLASS-01` |
| 关系多重性是否有依据 | 只有`User—UserProfile 1:1`有正式依据；控制/边界向实体的关联不定义持久多重性，已说明 |
| 是否引入设计技术 | 否 |

## 5. 与全局图的关系

本VOPC是UC-04的局部静态视图，不是全系统类图。与`DGM-CLASS-01-analysis.puml`的异同、命名合并建议见[07-与全局模型差异](07-global-model-diff.md)。R5统一分析类时应以本VOPC为输入之一，而不是用全局图反推本UC的参与类。
