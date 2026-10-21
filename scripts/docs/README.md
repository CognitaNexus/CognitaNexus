# 文档工具与 CI

## 目录与默认入口（2026-10-21）

| 目录 / 入口 | 职责 |
| --- | --- |
| [`checks/`](checks/) | 当前只读检查实现：链接/生成物结构、SRS 范围、69 AC 与分析追踪 |
| [`legacy/`](legacy/) | 六人 V1.0 历史重建与验证；会写回历史正文/报告，按授权使用 |
| `scripts/docs/*.py` 同名旧入口 | 转发至对应实现，保留已有命令与历史引用 |
| `build_docx.py`、`build_current_word.sh` | 保留原路径的 Word 构建工具；后者实际输入为历史 V1.1 |

推荐从仓库根目录运行 `make check-docs`。无需 Make 时执行：

```sh
python3 scripts/docs/checks/check_documentation.py
python3 scripts/docs/checks/verify_scoped_srs.py
python3 scripts/docs/checks/verify_69_ac.py
```

三个旧路径命令继续有效。当前检查以 V2.2 / 69 AC 为准，不运行 `legacy/`。构建工具仍需核对输入文档版本，旧文件名中的 `current` 不代表当前基线。

## 工具依赖与副作用

从仓库根目录运行，输出目录必须使用新的临时目录。Markdown 和图源的正式副本由评审维护，不在验证过程中覆盖。

| 工具 | 适用范围 | 副作用与命令 |
| --- | --- | --- |
| `verify_scoped_srs.py` | 当前 V2.2 / 69 AC | `python3 scripts/docs/verify_scoped_srs.py`；只读；验证16 FR、8 NF、69 AC与124/287历史父范围来源 |
| `check_documentation.py` | 全部受控 Markdown、UML及对应Word（排除 incoming） | `python3 scripts/docs/check_documentation.py`；只读 JSON；链接文件、图配对/XML、Word ZIP/XML/AC；不验证锚点、远程链接、完整文本或视觉语义 |
| `build_current_word.sh` | Historical V1.1 Word | `bash scripts/docs/build_current_word.sh /tmp/cognita-word-qa`；新目录；Pandoc + 临时图源转 PNG + 固定 Lua 图片尺寸（规避本地缺少 rsvg-convert）；依赖 pandoc 与 plantuml，不覆盖正式交付 |
| `build_docx.py` | 旧 python-docx 转换器，也接受指定 Markdown | 依赖 python-docx/Pillow/PlantUML/中文字体；会覆盖显式输出路径；排版和 Pandoc 不同，不能替代当前转换链 |
| `build_common_srs.py` | Historical 六人 V1.0 | **会覆盖历史六人正文及关联登记输出**；仅在获准重建历史交付时使用，不纳入当前检查 |
| `validate_srs.py` | Historical 六人 V1.0 验证 | **会覆盖历史 integration-validation.json**，并检查PlantUML；不能用其结果代表当前 V2.2 / 69 AC；本次保留不运行 |

[当前检查与历史 V1.1 构建 CI](../../.github/workflows/build-scoped-shared-srs.yml) 在 PR/main 相关文件变更或手动运行时执行69 AC只读检查、历史V1.1临时 Word 构建及 PDF 渲染，产物上传 artifact，不提交或推送。
[历史构建 CI](../../.github/workflows/build-shared-srs-word.yml) 仅手动重建 V1.0 到临时目录，不覆盖受控 Word。均 contents: read，checkout 不保留凭据。

Word 渲染需 LibreOffice、Poppler 和 Noto CJK 字体。UML 源文件使用 `plantuml -charset UTF-8 -tsvg -o generated <已授权图源>`；本次仅检查，不重写图。需要复现时记录 Git SHA、工具版本和字体，DOCX 字节受 ZIP 元数据影响；可重建不等于相同哈希。CI 是否执行成功以实际运行记录为准。
