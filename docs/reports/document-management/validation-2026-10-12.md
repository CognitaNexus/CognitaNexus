# 文档整理验证报告（2026-10-12）

状态：配置管理静态检查与构建验证；不代表业务验收、人工联合签认或 Frozen。审计基点 `622be6e095cafa60bde8a6e3deb2785eb2796513`；工作区开始时干净，从 fetch 后最新 origin/main 创建 `codex/docs-configuration-20261012`。

## 修改范围与保全

修改四个入口（根 README、docs/README、modules/README、AGENTS），两份 CI，以及工程可行性报告的四处文件链接。新增配置管理规范、逐文件盘点与整理方案、本报告和机器检查结果、UML 导航、工具说明、只读检查脚本及临时 Word 构建脚本。

逐一将审计基点的受控文件与工作区字节比较：无文件丢失、无迁移；仅上述七份既有文件不同。需求主报告、三个 SRS Markdown、两个 SPEC 规范、全部原追踪 JSON/Markdown、模块正文、Word、PUML/SVG/PNG 均与基点逐字节一致。因此本次未改变 FR/NF/IN/O、AC、业务规则和流程，也未引入需求基线冲突。所有历史交付保留，非受控 incoming 原稿未暂存。

## 执行命令与结果

| 检查 | 执行命令/方法 | 真实结果与边界 |
| --- | --- | --- |
| Git 基点 | `git fetch origin main`；`git rev-list --left-right --count main...origin/main` | 0/0；干净工作区；从 origin/main 创建独立分支 |
| 当前 SRS/来源映射 | `python3 scripts/docs/verify_scoped_srs.py` | DOCUMENT_STATIC_PASS_ONLY：10章、16 FR、8 NF、IN01/Optional存在、46唯一AC、124唯一子需求、287唯一来源AC，全部候选目标可解析 |
| 索引/文件链接 | `python3 scripts/docs/check_documentation.py` | 最终结果见[机器检查 JSON](static-checks-2026-10-12.json)；全 docs Markdown（排除 incoming）及根入口；文件目标可访问；不检查远端 URL 和片段锚点 |
| 原追踪保全 | 基点 `git show` 与本地文件逐字节比较 | 原有 integration、CN 编号注册和 SPEC 追踪全部不变；不把父范围引用解释为 G/W/T 等价 |
| 图源与图片 | 检查脚本 XML 配对；`env -u DISPLAY plantuml -charset UTF-8 -checkonly docs/uml/*/*.puml` | 17组源/SVG配对，XML可解析；17图源语法检查返回0；未重新生成或覆盖受控图片 |
| 四份 Word 静态检查 | 检查脚本 ZIP/XML/AC比对 | 4份 ZIP/XML 可读取，源AC没有缺失；当前SRS有2媒体；非完整文本一致性或视觉通过 |
| Shell/Python | `bash -n scripts/docs/build_current_word.sh`；Python `py_compile` | 语法检查成功；临时 pycache 不提交 |
| 当前 Word 构建 | `bash scripts/docs/build_current_word.sh /tmp/cognita-word-qa-final-20261012` | 返回0；ZIP/XML、46 AC、2嵌入图通过；只写临时目录，正式Word未覆盖 |
| Word 渲染 | bundled Python 运行 documents 技能 `render_docx.py <Word> --output_dir <临时目录> --emit_pdf` | 正式V1.1 16页、临时新构建18页，PDF及全部页PNG已生成；使用捆绑 LibreOfficeDev 26.8，非系统桌面实例 |
| 人工视觉抽查 | 打开正式1/8/16页及临时1/3/18页PNG | 抽查正文可读，未见明显乱码/裁切；临时第3页图前留白较多，排版与正式副本不同；**未逐页视觉审查，不标全稿视觉PASS** |
| 差异格式 | `git diff --check` | 无空白错误；新增文件另检查内容，不以普通diff为空判定无新增 |
| CI | 系统 Python 的 PyYAML `safe_load` 解析两份工作流、检查 contents: read 与 persist-credentials: false；本地执行当前检查/构建路径 | 权限收缩为read、无push；GitHub实际运行结果须以PR检查记录为准，不预标PASS |

## 构建诊断和可复现性边界

首次运行 PlantUML 受 DISPLAY=:0 影响报 X11 连接错误；改为 `env -u DISPLAY` 后语法与临时图片生成通过。首次 Pandoc 转 SVG 缺少 rsvg-convert；当前脚本将两张授权图源复制到临时目录转 PNG，再由 Lua 引用，生成后校验必须嵌入两张图，避免仅成功退出却丢图。正式 SVG 不变。捆绑 Python 缺少 PyYAML，YAML检查改用系统 Python 已成功；这不涉及Word工具运行时。Pandoc 本地版本3.5，PlantUML版本及环境记录在临时构建的 tool-versions.txt；CI 的 apt 版本与本机未锁定，因此只证明可构建，不声称字节或版式完全确定。

正式 SRS/核心 SPEC 与 Markdown 的 Pandoc plain 去空白比对均不完全相等；不同格式中的表格/图片表示及版本差异仍需逐项核对，未宣称全文相同。明确发现核心 SPEC Word **缺少 Markdown 顶部 V1.1 工程对齐通知**（Word正文亦未找到V1.1），属于既有同步缺口。本次保留原件，导航强制结合 V1.1 对齐说明阅读，待负责人批准后单独生成、逐页审核并更新正式副本。正式Word与本次开始时完全相同，本次没有制造非预期覆盖差异。

历史 build_common_srs.py、validate_srs.py 不运行，以免覆盖历史交付/报告。历史 V1.0 CI 的临时重建未本地实际运行，不标PASS。全部历史Word的视觉检查、全图生成一致性、完整业务语义核对、远端URL与中文锚点验证均未执行。未运行应用单元、集成、性能测试；未验证万条真实数据、CodeArts导入、来源许可或两组签认。

## 当前候选46 AC归属

以下按当前SRS定义归属父需求；仅列登记，不新增验收或重命名旧AC。OvO旧编号映射见[当前处理表](../../srs/integration/engineering-scope-trace-v1.1-rc.md)，CN原编号见[注册表](../../srs/global-id-registry.md)和[旧六人SRS](../../srs/CognitaNexus-SRS-V1.0.md)。

| 父需求 | 当前候选验收编号 |
| --- | --- |
| FR01 | AC-IMPL-FR01-01、AC-IMPL-FR01-02、AC-IMPL-FR01-03 |
| FR02 | AC-IMPL-FR02-01、AC-IMPL-FR02-02 |
| FR03 | AC-IMPL-FR03-01、AC-IMPL-FR03-02 |
| FR04 | AC-IMPL-FR04-01、AC-IMPL-FR04-02 |
| FR05 | AC-IMPL-FR05-01、AC-IMPL-FR05-02、AC-IMPL-FR05-03 |
| FR06 | AC-IMPL-FR06-01、AC-IMPL-FR06-02 |
| FR07 | AC-IMPL-FR07-01、AC-IMPL-FR07-02 |
| FR08 | AC-IMPL-FR08-01、AC-IMPL-FR08-02 |
| FR09 | AC-IMPL-FR09-01、AC-IMPL-FR09-02、AC-IMPL-FR09-03 |
| FR10 | AC-IMPL-FR10-01、AC-IMPL-FR10-02 |
| FR11 | AC-IMPL-FR11-01、AC-IMPL-FR11-02 |
| FR12 | AC-IMPL-FR12-01、AC-IMPL-FR12-02 |
| FR13 | AC-IMPL-FR13-01、AC-IMPL-FR13-02 |
| FR14 | AC-IMPL-FR14-01、AC-IMPL-FR14-02 |
| FR15 | AC-IMPL-FR15-01、AC-IMPL-FR15-02 |
| FR16 | AC-IMPL-FR16-01、AC-IMPL-FR16-02 |
| IN01 | AC-IMPL-IN01-01、AC-IMPL-IN01-02、AC-IMPL-IN01-03 |
| NF01 | AC-IMPL-NF01-01 |
| NF02 | AC-IMPL-NF02-01 |
| NF03 | AC-IMPL-NF03-01 |
| NF04 | AC-IMPL-NF04-01 |
| NF05 | AC-IMPL-NF05-01 |
| NF06 | AC-IMPL-NF06-01 |
| NF07 | AC-IMPL-NF07-01 |
| NF08 | AC-IMPL-NF08-01 |

待决：两组签认、SPEC Word同步、旧映射的逐条语义人工核对、课程原文阶段要求核定。没有通过整理自行解决或冻结这些业务问题。
