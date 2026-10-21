# 69 AC → FR/BR/NF → UC → 分析模型双向追踪

**版本：** V1.3（2026-10-21；UC-04/01/02/03追踪修订）
**基线：** [`69-ac-baseline.json`](../../srs/integration/69-ac-baseline.json)
**SRS：** [`CognitaNexus-Shared-SRS-V2.2-69AC.md`](../../srs/CognitaNexus-Shared-SRS-V2.2-69AC.md)
**状态：** S02 分析追踪；所有运行状态仍为 `NOT RUN`。

> 本表由69条权威账本生成，逐条保留 ID、父项、BR、UC 与分析模型。模型是分析交付，不是已实现系统；测试状态不因追踪完整而变为 PASS。
>
> **2026-10-21 UC-04 R1：** 为UC-04补充`DGM-SEQ-05/VOPC-01`，并把`AC-IMPL-FR03-03/04`纳入UC-04反向追踪；其他UC行未改。
>
> **2026-10-21 Q08 全量追踪核对：** 69条AC前向/反向一致；UC-01～19、UC-22共20个；`AC-IMPL-NF08-01`仅归“全仓实现与测试”；未发现其他UC的正反向差异。
>
> **2026-10-21 UC-01 R2：** 为UC-01补充`DGM-SEQ-06/VOPC-02`，仅修改FR01/NF01/NF02相关行。

## 1. 汇总

| 父项 | AC数 | 主要 UC | 主要分析模型 |
|---|---:|---|---|
| FR01 | 3 | UC-01 | DGM-USECASE-04 / ACT-07 / SEQ-03 |
| FR02 | 2 | UC-02/16 | DGM-USECASE-04 / ACT-07 / SEQ-03 |
| FR03 | 4 | UC-03 | DGM-USECASE-07 / CLASS-01 |
| FR04 | 4 | UC-04 | DGM-USECASE-07 / ACT-05 / SEQ-04 |
| FR05 | 4 | UC-05/06 | DGM-USECASE-05 / ACT-01 / STATE-02 / SEQ-02 |
| FR06 | 5 | UC-07/22 | DGM-USECASE-07 / ACT-06 / SEQ-04 |
| FR07 | 3 | UC-08 | DGM-USECASE-05 / SEQ-02 / CLASS-01 |
| FR08 | 2 | UC-18 | DGM-USECASE-03 / STATE-01 / SEQ-01 |
| FR09 | 6 | UC-09 | governance-dedup / CLASS-01 |
| FR10 | 2 | UC-19 | DGM-USECASE-03 / STATE-01 / SEQ-01 |
| FR11 | 2 | UC-11/12 | DGM-USECASE-02 / CLASS-01 |
| FR12 | 5 | UC-14 | DGM-USECASE-02 / ACT-08 / CLASS-01 |
| FR13 | 2 | UC-13 | DGM-USECASE-02 |
| FR14 | 2 | UC-17 | DGM-USECASE-06 / CLASS-01 |
| FR15 | 6 | UC-10/08 | governance-relation / SEQ-02 / CLASS-01 |
| FR16 | 2 | UC-16/02 | DGM-USECASE-04 / ACT-07 |
| IN01 | 4 | UC-15 | DGM-USECASE-02 / ACT-08 / CLASS-01 |
| NF01 | 1 | UC-01/02/03 | DGM-USECASE-04 / ACT-07 |
| NF02 | 2 | UC-01/02/15 | SEQ-03 / ACT-08 |
| NF03 | 1 | UC-04/05/06/07/08/17 | SEQ-02 / SEQ-04 |
| NF04 | 2 | UC-18/19 | STATE-01 / SEQ-01 |
| NF05 | 2 | UC-08/14/15 | SEQ-02 / ACT-08 |
| NF06 | 1 | UC-03/05/06/22 | SEQ-02 / SEQ-04 |
| NF07 | 1 | UC-18/19 | STATE-01 / SEQ-01 |
| NF08 | 1 | 实现与测试 | 全仓测试（后续实现阶段） |
| **合计** | **69** |  |  |

## 2. 逐条追踪

| # | AC ID | FR/NF | BR-IMP | 主 UC | 分析模型 | 验证场景 | 运行状态 |
|---:|---|---|---|---|---|---|---|
| 1 | `AC-IMPL-FR01-01` | FR01 | BR-IMP-01/07 | UC-01 | DGM-USECASE-04 / ACT-07 / SEQ-03 / SEQ-06 / VOPC-02 | 公开范围/组合筛选/稳定分页 | 计划验收，未执行 |
| 2 | `AC-IMPL-FR01-02` | FR01 | BR-IMP-01/07 | UC-01 | DGM-USECASE-04 / ACT-07 / SEQ-03 / SEQ-06 / VOPC-02 | 公开范围/组合筛选/稳定分页 | 计划验收，未执行 |
| 3 | `AC-IMPL-FR01-03` | FR01 | BR-IMP-01/07 | UC-01 | DGM-USECASE-04 / ACT-07 / SEQ-03 / SEQ-06 / VOPC-02 | 公开范围/组合筛选/稳定分页 | 计划验收，未执行 |
| 4 | `AC-IMPL-FR02-01` | FR02 | BR-IMP-01/02 | UC-02、UC-16 | DGM-USECASE-04 / ACT-07 / SEQ-03 | 多来源详情/外链失败 | 计划验收，未执行 |
| 5 | `AC-IMPL-FR02-02` | FR02 | BR-IMP-01/02 | UC-02、UC-16 | DGM-USECASE-04 / ACT-07 / SEQ-03 | 多来源详情/外链失败 | 计划验收，未执行 |
| 6 | `AC-IMPL-FR03-01` | FR03 | BR-IMP-01/03/11 | UC-03、UC-22 | DGM-USECASE-07 / CLASS-01 / SEQ-08 / VOPC-04 | 零成果主页/同名隔离 | 计划验收，未执行 |
| 7 | `AC-IMPL-FR03-02` | FR03 | BR-IMP-01/03/11 | UC-03、UC-22 | DGM-USECASE-07 / CLASS-01 / SEQ-08 / VOPC-04 | 零成果主页/同名隔离 | 计划验收，未执行 |
| 8 | `AC-IMPL-FR03-03` | FR03 | BR-IMP-01/03/11 | UC-03、UC-04、UC-22 | DGM-USECASE-07 / ACT-05 / SEQ-04 / SEQ-08 / SEQ-05 / VOPC-01 / VOPC-04 | 零成果主页/同名隔离 | 计划验收，未执行 |
| 9 | `AC-IMPL-FR03-04` | FR03 | BR-IMP-01/03/11 | UC-03、UC-04、UC-22 | DGM-USECASE-07 / ACT-05 / SEQ-04 / SEQ-08 / SEQ-05 / VOPC-01 / VOPC-04 | 零成果主页/同名隔离 | 计划验收，未执行 |
| 10 | `AC-IMPL-FR04-01` | FR04 | BR-IMP-05/10/11 | UC-04 | DGM-USECASE-07 / ACT-05 / SEQ-04 / SEQ-05 / VOPC-01 | 注册唯一主页/权限 | 计划验收，未执行 |
| 11 | `AC-IMPL-FR04-02` | FR04 | BR-IMP-05/10/11 | UC-04 | DGM-USECASE-07 / ACT-05 / SEQ-04 / SEQ-05 / VOPC-01 | 注册唯一主页/权限 | 计划验收，未执行 |
| 12 | `AC-IMPL-FR04-03` | FR04 | BR-IMP-05/10/11 | UC-04 | DGM-USECASE-07 / ACT-05 / SEQ-04 / SEQ-05 / VOPC-01 | 注册唯一主页/权限 | 计划验收，未执行 |
| 13 | `AC-IMPL-FR04-04` | FR04 | BR-IMP-05/10/11 | UC-04 | DGM-USECASE-07 / ACT-05 / SEQ-04 / SEQ-05 / VOPC-01 | 注册唯一主页/权限 | 计划验收，未执行 |
| 14 | `AC-IMPL-FR05-01` | FR05 | BR-IMP-03/05/10 | UC-05、UC-06 | DGM-USECASE-05 / ACT-01 / STATE-02 / SEQ-02 | 认领补证/授权/争议 | 计划验收，未执行 |
| 15 | `AC-IMPL-FR05-02` | FR05 | BR-IMP-03/05/10 | UC-05、UC-06 | DGM-USECASE-05 / ACT-01 / STATE-02 / SEQ-02 | 认领补证/授权/争议 | 计划验收，未执行 |
| 16 | `AC-IMPL-FR05-03` | FR05 | BR-IMP-03/05/10 | UC-05、UC-06 | DGM-USECASE-05 / ACT-01 / STATE-02 / SEQ-02 | 认领补证/授权/争议 | 计划验收，未执行 |
| 17 | `AC-IMPL-FR05-04` | FR05 | BR-IMP-03/05/10 | UC-05、UC-06 | DGM-USECASE-05 / ACT-01 / STATE-02 / SEQ-02 | 认领补证/授权/争议 | 计划验收，未执行 |
| 18 | `AC-IMPL-FR06-01` | FR06 | BR-IMP-05/10/11 | UC-07、UC-22 | DGM-USECASE-07 / ACT-06 / SEQ-04 | 普通主页编辑/学术审核分离 | 计划验收，未执行 |
| 19 | `AC-IMPL-FR06-02` | FR06 | BR-IMP-05/10/11 | UC-07、UC-22 | DGM-USECASE-07 / ACT-06 / SEQ-04 | 普通主页编辑/学术审核分离 | 计划验收，未执行 |
| 20 | `AC-IMPL-FR06-03` | FR06 | BR-IMP-05/10/11 | UC-07、UC-22 | DGM-USECASE-07 / ACT-06 / SEQ-04 | 普通主页编辑/学术审核分离 | 计划验收，未执行 |
| 21 | `AC-IMPL-FR06-04` | FR06 | BR-IMP-05/10/11 | UC-07、UC-22 | DGM-USECASE-07 / ACT-06 / SEQ-04 | 普通主页编辑/学术审核分离 | 计划验收，未执行 |
| 22 | `AC-IMPL-FR06-05` | FR06 | BR-IMP-05/10/11 | UC-07、UC-22 | DGM-USECASE-07 / ACT-06 / SEQ-04 | 普通主页编辑/学术审核分离 | 计划验收，未执行 |
| 23 | `AC-IMPL-FR07-01` | FR07 | BR-IMP-03/07/10 | UC-08 | DGM-USECASE-05 / SEQ-02 / CLASS-01 | 解除关系/共享成果保留 | 计划验收，未执行 |
| 24 | `AC-IMPL-FR07-02` | FR07 | BR-IMP-03/07/10 | UC-08 | DGM-USECASE-05 / SEQ-02 / CLASS-01 | 解除关系/共享成果保留 | 计划验收，未执行 |
| 25 | `AC-IMPL-FR07-03` | FR07 | BR-IMP-03/07/10 | UC-08 | DGM-USECASE-05 / SEQ-02 / CLASS-01 | 解除关系/共享成果保留 | 计划验收，未执行 |
| 26 | `AC-IMPL-FR08-01` | FR08 | BR-IMP-04 | UC-18 | DGM-USECASE-03 / STATE-01 / SEQ-01 | 批次成功与待核验 | 计划验收，未执行 |
| 27 | `AC-IMPL-FR08-02` | FR08 | BR-IMP-04 | UC-18 | DGM-USECASE-03 / STATE-01 / SEQ-01 | 批次成功与待核验 | 计划验收，未执行 |
| 28 | `AC-IMPL-FR09-01` | FR09 | BR-IMP-02/04 | UC-09 | governance-dedup / CLASS-01 | 去重/冲突/版本/幂等 | 计划验收，未执行 |
| 29 | `AC-IMPL-FR09-02` | FR09 | BR-IMP-02/04 | UC-09 | governance-dedup / CLASS-01 | 去重/冲突/版本/幂等 | 计划验收，未执行 |
| 30 | `AC-IMPL-FR09-04` | FR09 | BR-IMP-02/04 | UC-09 | governance-dedup / CLASS-01 | 去重/冲突/版本/幂等 | NOT RUN |
| 31 | `AC-IMPL-FR09-05` | FR09 | BR-IMP-02/04 | UC-09 | governance-dedup / CLASS-01 | 去重/冲突/版本/幂等 | NOT RUN |
| 32 | `AC-IMPL-FR09-06` | FR09 | BR-IMP-02/04 | UC-09 | governance-dedup / CLASS-01 | 去重/冲突/版本/幂等 | NOT RUN |
| 33 | `AC-IMPL-FR09-03` | FR09 | BR-IMP-02/04 | UC-09 | governance-dedup / CLASS-01 | 去重/冲突/版本/幂等 | 计划验收，未执行 |
| 34 | `AC-IMPL-FR10-01` | FR10 | BR-IMP-04/10 | UC-19 | DGM-USECASE-03 / STATE-01 / SEQ-01 | 增量冲突/断连恢复 | 计划验收，未执行 |
| 35 | `AC-IMPL-FR10-02` | FR10 | BR-IMP-04/10 | UC-19 | DGM-USECASE-03 / STATE-01 / SEQ-01 | 增量冲突/断连恢复 | 计划验收，未执行 |
| 36 | `AC-IMPL-FR11-01` | FR11 | BR-IMP-06/07 | UC-11、UC-12 | DGM-USECASE-02 / CLASS-01 | 去重统计/真实资格 | 计划验收，未执行 |
| 37 | `AC-IMPL-FR11-02` | FR11 | BR-IMP-06/07 | UC-11、UC-12 | DGM-USECASE-02 / CLASS-01 | 去重统计/真实资格 | 计划验收，未执行 |
| 38 | `AC-IMPL-FR12-01` | FR12 | BR-IMP-01/03/07/08 | UC-14 | DGM-USECASE-02 / ACT-08 / CLASS-01 | 四类共同成果基础边 | NOT RUN |
| 39 | `AC-IMPL-FR12-02` | FR12 | BR-IMP-01/03/07/08 | UC-14 | DGM-USECASE-02 / ACT-08 / CLASS-01 | 四类共同成果基础边 | NOT RUN |
| 40 | `AC-IMPL-FR12-03` | FR12 | BR-IMP-01/03/07/08 | UC-14 | DGM-USECASE-02 / ACT-08 / CLASS-01 | 四类共同成果基础边 | NOT RUN |
| 41 | `AC-IMPL-FR12-04` | FR12 | BR-IMP-01/03/07/08 | UC-14 | DGM-USECASE-02 / ACT-08 / CLASS-01 | 四类共同成果基础边 | NOT RUN |
| 42 | `AC-IMPL-FR12-05` | FR12 | BR-IMP-01/03/07/08 | UC-14 | DGM-USECASE-02 / ACT-08 / CLASS-01 | 四类共同成果基础边 | NOT RUN |
| 43 | `AC-IMPL-FR13-01` | FR13 | BR-IMP-06/07 | UC-13 | DGM-USECASE-02 | 关键词热点/稀疏 | 计划验收，未执行 |
| 44 | `AC-IMPL-FR13-02` | FR13 | BR-IMP-06/07 | UC-13 | DGM-USECASE-02 | 关键词热点/稀疏 | 计划验收，未执行 |
| 45 | `AC-IMPL-FR14-01` | FR14 | BR-IMP-05/10 | UC-17 | DGM-USECASE-06 / CLASS-01 | 越权拒绝/审计可查 | 计划验收，未执行 |
| 46 | `AC-IMPL-FR14-02` | FR14 | BR-IMP-05/10 | UC-17 | DGM-USECASE-06 / CLASS-01 | 越权拒绝/审计可查 | 计划验收，未执行 |
| 47 | `AC-IMPL-FR15-01` | FR15 | BR-IMP-03/10 | UC-10、UC-08 | governance-relation / SEQ-02 / CLASS-01 | 身份候选/排除不恢复 | 计划验收，未执行 |
| 48 | `AC-IMPL-FR15-02` | FR15 | BR-IMP-03/10 | UC-10、UC-08 | governance-relation / SEQ-02 / CLASS-01 | 身份候选/排除不恢复 | 计划验收，未执行 |
| 49 | `AC-IMPL-FR15-03` | FR15 | BR-IMP-03/10 | UC-10、UC-08 | governance-relation / SEQ-02 / CLASS-01 | 身份候选/排除不恢复 | NOT RUN |
| 50 | `AC-IMPL-FR15-04` | FR15 | BR-IMP-03/10 | UC-10、UC-08 | governance-relation / SEQ-02 / CLASS-01 | 身份候选/排除不恢复 | NOT RUN |
| 51 | `AC-IMPL-FR15-05` | FR15 | BR-IMP-03/10 | UC-10、UC-08 | governance-relation / SEQ-02 / CLASS-01 | 身份候选/排除不恢复 | NOT RUN |
| 52 | `AC-IMPL-FR15-06` | FR15 | BR-IMP-03/10 | UC-10、UC-08 | governance-relation / SEQ-02 / CLASS-01 | 身份候选/排除不恢复 | NOT RUN |
| 53 | `AC-IMPL-FR16-01` | FR16 | BR-IMP-02/07 | UC-16、UC-02 | DGM-USECASE-04 / ACT-07 | 四类来源与实体计数 | 计划验收，未执行 |
| 54 | `AC-IMPL-FR16-02` | FR16 | BR-IMP-02/07 | UC-16、UC-02 | DGM-USECASE-04 / ACT-07 | 四类来源与实体计数 | 计划验收，未执行 |
| 55 | `AC-IMPL-IN01-01` | IN01 | BR-IMP-08/09 | UC-15 | DGM-USECASE-02 / ACT-08 / CLASS-01 | 论文子图最短路径/规模 | 计划验收，未执行 |
| 56 | `AC-IMPL-IN01-02` | IN01 | BR-IMP-08/09 | UC-15 | DGM-USECASE-02 / ACT-08 / CLASS-01 | 论文子图最短路径/规模 | 计划验收，未执行 |
| 57 | `AC-IMPL-IN01-03` | IN01 | BR-IMP-08/09 | UC-15 | DGM-USECASE-02 / ACT-08 / CLASS-01 | 论文子图最短路径/规模 | 计划验收，未执行 |
| 58 | `AC-IMPL-IN01-04` | IN01 | BR-IMP-08/09 | UC-15 | DGM-USECASE-02 / ACT-08 / CLASS-01 | 论文子图最短路径/规模 | NOT RUN |
| 59 | `AC-IMPL-NF01-01` | NF01 | BR-IMP-01 | UC-01、UC-02、UC-03 | DGM-USECASE-04 / ACT-07 / SEQ-06 / SEQ-08 | 空/失败/无权状态 | NOT RUN |
| 60 | `AC-IMPL-NF02-01` | NF02 | BR-IMP-01/04/07 | UC-01、UC-02、UC-15 | SEQ-03 / SEQ-06 / ACT-08 | 500万/100并发负载 | NOT RUN |
| 61 | `AC-IMPL-NF02-02` | NF02 | BR-IMP-01/04/07 | UC-01、UC-02、UC-15 | SEQ-03 / SEQ-06 / ACT-08 | 500万/100并发负载 | NOT RUN |
| 62 | `AC-IMPL-NF03-01` | NF03 | BR-IMP-05/11 | UC-04、UC-05、UC-06、UC-07、UC-08、UC-17 | SEQ-02 / SEQ-04 | 越权/停用/材料 | NOT RUN |
| 63 | `AC-IMPL-NF04-01` | NF04 | BR-IMP-02/04 | UC-18、UC-19 | STATE-01 / SEQ-01 | 恢复一致/RTO/RPO | NOT RUN |
| 64 | `AC-IMPL-NF04-02` | NF04 | BR-IMP-02/04 | UC-18、UC-19 | STATE-01 / SEQ-01 | 恢复一致/RTO/RPO | NOT RUN |
| 65 | `AC-IMPL-NF05-01` | NF05 | BR-IMP-04/10 | UC-08、UC-14、UC-15 | SEQ-02 / ACT-08 | 跨视图生效版本 | NOT RUN |
| 66 | `AC-IMPL-NF05-02` | NF05 | BR-IMP-04/10 | UC-08、UC-14、UC-15 | SEQ-02 / ACT-08 | 跨视图生效版本 | NOT RUN |
| 67 | `AC-IMPL-NF06-01` | NF06 | BR-IMP-11 | UC-03、UC-05、UC-06、UC-22 | SEQ-02 / SEQ-04 | 私有证明/最小化 | NOT RUN |
| 68 | `AC-IMPL-NF07-01` | NF07 | BR-IMP-04 | UC-18、UC-19 | STATE-01 / SEQ-01 | 停用来源后历史可读 | NOT RUN |
| 69 | `AC-IMPL-NF08-01` | NF08 | BR-IMP-10 | 全仓实现与测试 | 全仓测试（后续实现阶段） | 独立覆盖率与平台证据 | NOT RUN |

## 3. 反向 UC → AC

| UC | 覆盖 AC |
|---|---|
| UC-01 | `AC-IMPL-FR01-01`、`AC-IMPL-FR01-02`、`AC-IMPL-FR01-03`、`AC-IMPL-NF01-01`、`AC-IMPL-NF02-01`、`AC-IMPL-NF02-02` |
| UC-02 | `AC-IMPL-FR02-01`、`AC-IMPL-FR02-02`、`AC-IMPL-FR16-01`、`AC-IMPL-FR16-02`、`AC-IMPL-NF01-01`、`AC-IMPL-NF02-01`、`AC-IMPL-NF02-02` |
| UC-03 | `AC-IMPL-FR03-01`、`AC-IMPL-FR03-02`、`AC-IMPL-FR03-03`、`AC-IMPL-FR03-04`、`AC-IMPL-NF01-01`、`AC-IMPL-NF06-01` |
| UC-04 | `AC-IMPL-FR04-01`、`AC-IMPL-FR04-02`、`AC-IMPL-FR04-03`、`AC-IMPL-FR04-04`、`AC-IMPL-FR03-03`、`AC-IMPL-FR03-04`、`AC-IMPL-NF03-01` |
| UC-05 | `AC-IMPL-FR05-01`、`AC-IMPL-FR05-02`、`AC-IMPL-FR05-03`、`AC-IMPL-FR05-04`、`AC-IMPL-NF03-01`、`AC-IMPL-NF06-01` |
| UC-06 | `AC-IMPL-FR05-01`、`AC-IMPL-FR05-02`、`AC-IMPL-FR05-03`、`AC-IMPL-FR05-04`、`AC-IMPL-NF03-01`、`AC-IMPL-NF06-01` |
| UC-07 | `AC-IMPL-FR06-01`、`AC-IMPL-FR06-02`、`AC-IMPL-FR06-03`、`AC-IMPL-FR06-04`、`AC-IMPL-FR06-05`、`AC-IMPL-NF03-01` |
| UC-08 | `AC-IMPL-FR07-01`、`AC-IMPL-FR07-02`、`AC-IMPL-FR07-03`、`AC-IMPL-FR15-01`、`AC-IMPL-FR15-02`、`AC-IMPL-FR15-03`、`AC-IMPL-FR15-04`、`AC-IMPL-FR15-05`、`AC-IMPL-FR15-06`、`AC-IMPL-NF03-01`、`AC-IMPL-NF05-01`、`AC-IMPL-NF05-02` |
| UC-09 | `AC-IMPL-FR09-01`、`AC-IMPL-FR09-02`、`AC-IMPL-FR09-04`、`AC-IMPL-FR09-05`、`AC-IMPL-FR09-06`、`AC-IMPL-FR09-03` |
| UC-10 | `AC-IMPL-FR15-01`、`AC-IMPL-FR15-02`、`AC-IMPL-FR15-03`、`AC-IMPL-FR15-04`、`AC-IMPL-FR15-05`、`AC-IMPL-FR15-06` |
| UC-11 | `AC-IMPL-FR11-01`、`AC-IMPL-FR11-02` |
| UC-12 | `AC-IMPL-FR11-01`、`AC-IMPL-FR11-02` |
| UC-13 | `AC-IMPL-FR13-01`、`AC-IMPL-FR13-02` |
| UC-14 | `AC-IMPL-FR12-01`、`AC-IMPL-FR12-02`、`AC-IMPL-FR12-03`、`AC-IMPL-FR12-04`、`AC-IMPL-FR12-05`、`AC-IMPL-NF05-01`、`AC-IMPL-NF05-02` |
| UC-15 | `AC-IMPL-IN01-01`、`AC-IMPL-IN01-02`、`AC-IMPL-IN01-03`、`AC-IMPL-IN01-04`、`AC-IMPL-NF02-01`、`AC-IMPL-NF02-02`、`AC-IMPL-NF05-01`、`AC-IMPL-NF05-02` |
| UC-16 | `AC-IMPL-FR02-01`、`AC-IMPL-FR02-02`、`AC-IMPL-FR16-01`、`AC-IMPL-FR16-02` |
| UC-17 | `AC-IMPL-FR14-01`、`AC-IMPL-FR14-02`、`AC-IMPL-NF03-01` |
| UC-18 | `AC-IMPL-FR08-01`、`AC-IMPL-FR08-02`、`AC-IMPL-NF04-01`、`AC-IMPL-NF04-02`、`AC-IMPL-NF07-01` |
| UC-19 | `AC-IMPL-FR10-01`、`AC-IMPL-FR10-02`、`AC-IMPL-NF04-01`、`AC-IMPL-NF04-02`、`AC-IMPL-NF07-01` |
| UC-22 | `AC-IMPL-FR03-01`、`AC-IMPL-FR03-02`、`AC-IMPL-FR03-03`、`AC-IMPL-FR03-04`、`AC-IMPL-FR06-01`、`AC-IMPL-FR06-02`、`AC-IMPL-FR06-03`、`AC-IMPL-FR06-04`、`AC-IMPL-FR06-05`、`AC-IMPL-NF06-01` |
| 全仓实现与测试 | `AC-IMPL-NF08-01` |

## 4. 追踪完整性规则

- 69 个 ID 每个必须且只能出现一次于第2节；按 ID 唯一计数。
- 每条 AC 至少有一个 UC 或明确的“实现与测试”归属；NF08 只在后续实现阶段验证，不伪装成业务用例。
- SRS V2.2 的定义文本必须与 JSON 账本逐条一致；任何不一致、缺失或新增第70条由 `scripts/docs/verify_69_ac.py` 拒绝。
- 图/规约中出现的 UC-01～19、UC-22 必须有规约；UC-20/21 仅延期追踪。
- 运行状态只允许 `NOT RUN` / `计划验收，未执行`；S02不运行产品测试。
