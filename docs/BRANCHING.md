# CognitaNexus 分支与 PR 规范

2026-11-11 首次将与 `main` **没有共同祖先** 的 `dev` 实现代码按保留双亲提交历史的方式引入稳定分支。禁止用 `git push --force` 来覆盖 `main`。

## 分支职责

| 分支 | 职责 | 约束 |
| --- | --- | --- |
| `main` | 稳定可回滚的工程版本 | 仅经 PR 审查后合并；禁止直接推送 |
| `dev` | 日常联调、下一版集成 | PR 合入；**当前推送触发部署流水线**，更改前核实目标机 |
| `course-docs` | 课程定稿索引、架构决策与课程交付文档 | 文档 PR 合入此分支 |
| `analysis/s01-s02-69ac` | 历史 SRS V2.2 / 69 AC 与 S01/S02 分析资料（非本次课程定稿） | 只通过评审的文档 PR 更新，不直接放入产线 |
| `feat/<issue>-<topic>` | 新功能 | 从 `dev` 拉，完成后 PR 到 `dev` |
| `fix/<issue>-<topic>` | 缺陷修复 | 从 `dev` 拉，PR 到 `dev` |
| `docs/<topic>` | 课程文档调整 | 从 `course-docs` 拉，PR 到 `course-docs` |
| `release/vX.Y.Z` | 发布候选（需要时创建） | 测试与审阅通过再合入 `main` |

代码和课程文档分别提交。课程文档使用 `docs/<topic>` 分支合入 `course-docs`。与实现直接相关的编码规范、来源许可及运行说明随代码维护。

## 实际操作

```bash
git fetch origin --prune
git switch dev
git pull --ff-only origin dev
git switch -c feat/9-bulk-ingestion
# 仅提交与任务有关的修改
git push -u origin feat/9-bulk-ingestion
# 在 GitHub 创建指向 dev 的 PR
```

PR 必须关联 Issue，写清改动文件、数据库兼容性、测试结果（含未执行项目）及回滚步骤。涉及授权/审核/去重/来源许可的 PR 需要跨模块评审。

## 合并与发布
- 禁止强推及重写共享分支历史；任何 GitHub 分支保护设置的操作须由组织管理员在 Settings 里配置。
- 建议给 `main` 添加：禁止直接推送、至少一人审查、要求 CI 成功、禁止强推/删除。
- 建议给 `dev` 添加：要求 PR、CI 检查；先把当前“push dev 即部署”拆分为可审核的发布流程，再允许频繁集成。
- 既有提交历史可通过 Git 提交追溯；不要引用已经删除的临时备份分支。
- 后续把 `main` 的发布整理提交同步回 `dev` 时，**会触发当前 dev 部署流水线**，须先处理 CD 触发规则或有意识地执行发布。

## 需求与提交权限
GitHub commit 作者并不等于具备仓库写权限。正式任务分配前先由管理员核对 `Settings → Collaborators and teams` 或组织团队权限，然后用 GitHub 用户名分配 Issue，避免凭姓名推断账号。
