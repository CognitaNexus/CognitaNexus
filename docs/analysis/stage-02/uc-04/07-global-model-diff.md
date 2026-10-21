# UC-04 分析与现有全局分析类差异

**版本：** V1.0（2026-10-21）
**比较对象：** [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml) 与 [`01-BCE分析类模型.md`](../01-BCE分析类模型.md)。
**原则：** 全局图只作比对材料，不作为本UC识别类的反向答案；本轮不重构全局图。

## 1. 已存在且一致

| UC-04类/概念 | 全局图对应 | 一致点 | 证据 |
|---|---|---|---|
| `User` | `User`（全局图第49–52行） | 都是账号/状态/所有权主体；不因注册生成Scholar或Authorship | FR04；SRS §12 |
| `UserProfile` | `UserProfile`（全局图第53–57行） | 都有唯一/稳定/可分享公开地址、普通公开资料、零成果空态 | FR03；FR04；A1c |
| User—UserProfile 1:1 | 全局图第116行 `User "1" -- "1" UserProfile` | 唯一拥有；注册成功绑定 | FR04；AC-IMPL-FR04-03 |
| `RegistrationCoordinator`职责 | 全局`RegCtl`（第23–25行） | 都负责注册、唯一主页、回滚 | FR04；UC-04 |
| `RegistrationUI`职责 | 全局`AccountUI`（第13行） | 都承接账号/注册交互 | FR04；UC-04 |
| UserProfile与Scholar分离 | 全局`Scholar`（第58–61行）独立存在 | 注册不创建Scholar，不建立强制关系 | FR03-04；SRS §12 |

## 2. 需要更名/别名统一

| 本UC名称 | 全局名称 | 差异 | R5建议 |
|---|---|---|---|
| `RegistrationUI` | `AccountUI` | 本UC是注册专用边界；全局是账号边界 | 二选一：保留`RegistrationUI`作为UC-04局部类并映射到`AccountUI`，或把全局`AccountUI`作为唯一正式名 |
| `RegistrationCoordinator` | `RegCtl` | 全名 vs 缩写 | 统一为可读的`RegistrationCoordinator`，或保留`RegCtl`但在别名表说明；不要让两个名字分别出现在不同UC VOPC |
| `UserProfile` | `UserProfile` | 名称一致 | 保留 |
| `User` | `User` | 名称一致 | 保留 |

## 3. 职责冲突/需要补充

| 项目 | 本UC证据 | 全局现状 | 差异/影响 | R5处理 |
|---|---|---|---|---|
| 注册的幂等 | FR04-03“重试不产生第二个主页” | 全局`RegCtl`只写“注册/唯一主页/回滚”；未明确幂等检查和结果 | 全局职责缺少可验证的重复请求分支 | 把“检查重复/幂等”加入`RegistrationCoordinator`职责；幂等键另立Q-02 |
| 注册的整体生效 | FR04“一次性创建”；AC-IMPL-FR04-03 | 全局`RegCtl`有“回滚”但无“不得留下无主主页”的明确约束 | 回滚不等于完整定义；需明确User/UserProfile/地址整体不生效 | 在统一类职责中写清“整体成功或整体不生效” |
| 地址分配 | FR04；AC-IMPL-FR04-03；Q-03 | 全局`UserProfile`有`稳定公开地址()`，无职责归属到协调器 | 地址必须唯一/稳定/可分享；由谁分配不清晰 | 明确`RegistrationCoordinator`协调、`UserProfile`持有；格式/分配器待后续，当前不新增类 |
| 本人所有权与Scholar授权 | FR04；BR-IMP-05 | 全局有`Auth`和`ProfileCtl ..> Scholar` | 本UC不需要Auth；不能把UserProfile所有权误画成Scholar授权 | 统一时把“UserProfile所有权”与“ScholarAuthorization”分开表达 |
| 注册审计 | FR04未要求独立审计；BR-IMP-10按受保护写入解释 | 全局`AuditCtl/Evidence`存在 | 注册是未认证账号创建，当前不要求独立审计证据 | 不加入本UC；若安全负责人扩大解释，走需求变更 |
| 公开读取边界 | FR04-04；FR03 | 全局`PublicUI/SearchCtl`等合并 | 本UC只到“主页可公开读”的后置，不展开公共读取交互 | UC-03/R5处理；本UC不引入PublicUI |

## 4. 全局图的Authorization关系问题

本UC发现：

- `01`第49行说User“被授予Auth”；
- `01`第53行说`ScholarAuthorization`是“User×Scholar”；
- 但`DGM-CLASS-01`第117–120行只有`User—Claim`、`Claim—Scholar`、`Claim ..> Auth`、`Auth—Scholar`，**没有User—Auth关联**。

对本UC的影响：

- UC-04不创建Auth，因此本UC VOPC不能替全局图补这条关系；
- 但该差异证明全局`Auth`不能表达“哪个User获得哪个Scholar的授权”，属于R5必须修正的类关系缺口；
- 本UC的边界结论是：**UserProfile所有权不需要Authorization**，不要用Auth填补注册职责。

## 5. 当前不能确定的关系

| 关系 | 为什么不能在本UC确定 | 留待 |
|---|---|---|
| UserProfile—Scholar | 注册不建立；同名不合并；是否存在“同一人”关联取决于FR05/FR15审核语义 | UC-03/UC-05/06与R5 |
| User—ScholarAuthorization | 只在认领审核通过后产生 | UC-05/06 |
| UserProfile—Claim | 认领申请关联User/Scholar，不要求把UserProfile作为申请对象 | UC-05/06 |
| UserProfile—Authorship/Work | 注册不创建学术关系/成果 | UC-07/08/14/15 |
| 注册审计证据 | 当前不要求；BR-IMP-10按受保护写入解释 | Q-04已核对；扩大解释需需求变更 |
| UserProfile公开字段集合 | 注册邮箱/联系方式默认不公开；具体字段集合未冻结 | UC-22/FR06与Q-06 |

## 6. 追踪文件差异

UC-04 R1已在`03-AC-UC-分析模型追踪.md`（V1.1）中把`AC-IMPL-FR03-03/04`补入UC-04反向追踪，并给FR03-03/04和FR04行补上`SEQ-05/VOPC-01`；其他UC行未改。此前该反向行只列FR04，属于已修正的追踪缺口。

## 7. 差异结论

UC-04分析支持以下全局模型调整建议：

1. 保留`User`、`UserProfile`、1:1；
2. 将`RegCtl`统一为`RegistrationCoordinator`或建立明确别名；
3. 将`AccountUI`/`RegistrationUI`统一命名；
4. 在`RegistrationCoordinator`职责中加入幂等/整体生效；
5. 不在UC-04引入`Scholar`、`Authorization`、`Claim`、`Authorship`；
6. R5必须单独修正全局`User—Authorization—Scholar`关系缺口。

本轮**不直接修改** `DGM-CLASS-01-analysis.puml`。
