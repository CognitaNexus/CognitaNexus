#!/usr/bin/env bash
# =============================================================================
# 安全与配置门禁（CI 的 Security job 用）
# =============================================================================
#
# 这个脚本检查四类问题。它们的共同点是：**不会让编译失败，也不会让测试变红**，
# 只会在某天变成一次事故 —— 所以必须有一处显式的检查。
#
#   A. 凭据：私钥、云厂商 access key、平台 token、硬编码口令
#      - 扫描 git 跟踪的全部文件（= 提交内容），并扫描最近若干提交的 diff
#      - 排除「开发默认值 / 占位符 / 环境变量读取 / 测试口令」这几类噪音，
#        否则扫描结果里 31 条全是 `os.environ.get("CN_ADMIN_PASSWORD")`，
#        真正的问题会被淹掉（本项目 2026-11-25 实测：宽规则 31 命中、有效 0 条）
#
#   B. 工作流权限：顶层 permissions 必须存在且只读；禁止 pull_request_target；
#      actions 必须钉版本；services 镜像必须钉 tag；run 里不得把 secrets 打进日志
#
#   C. CI/CD 解耦：带 SSH / 部署凭据的工作流**只能**用 workflow_dispatch 触发。
#      这条是把「push dev 就自动上生产」钉死成不可回归的约束。
#
#   D. 构建配置：pom 里不得出现 http:// 仓库、SNAPSHOT/LATEST/版本区间依赖；
#      .gitignore 必须覆盖 .env / 私钥；frontend/.npmrc 不得再有 Windows 缓存路径
#
# 用法：bash ops/verify/security-scan.sh  （在仓库根目录执行；CI 里已 cd 进去）
# 退出码：0 通过；1 发现问题；2 环境不满足（缺 python3 / PyYAML）
# =============================================================================

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

PYTHON=$(command -v python3 || true)
if [ -z "$PYTHON" ]; then
  echo "找不到 python3：安全扫描依赖它解析 YAML 与 pom" >&2
  exit 2
fi

exec "$PYTHON" - <<'PY'
import glob
import io
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

try:
    import yaml
except ImportError:
    print("缺少 PyYAML：python3 -c 'import yaml' 失败。请先 pip install pyyaml", file=sys.stderr)
    raise SystemExit(2)

ROOT = os.getcwd()
findings = []
notes = []


def fail(rule, where, detail):
    findings.append("[%s] %s：%s" % (rule, where, detail))


def git(*args):
    # 历史 diff 里夹着二进制（种子/图片），按 utf-8 严格解码会抛异常；
    # 统一用 replace，扫描只关心能读成文本的部分。
    return subprocess.run(
        ["git", *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=180,
    )


def tracked_files():
    out = git("ls-files", "-z").stdout
    return [f for f in out.split("\0") if f]


files = tracked_files()
if not files:
    print("git ls-files 返回空：不在仓库根目录或缺 git", file=sys.stderr)
    raise SystemExit(2)

# ---------------------------------------------------------------------------
# A. 凭据扫描
# ---------------------------------------------------------------------------
print("== A. 凭据扫描（%d 个被跟踪文件）==" % len(files))

SENSITIVE_NAMES = re.compile(
    r"(^|/)(\.env(\.(?!example$)[\w.-]+)?|id_rsa|id_ed25519|.*\.(pem|p12|pfx|jks|keystore))$",
    re.IGNORECASE,
)
for f in files:
    if SENSITIVE_NAMES.search(f):
        fail("凭据", f, "被 git 跟踪的凭据/环境文件（应在 .gitignore 内，绝不能提交）")

HIGH_CONFIDENCE = [
    (
        "私钥",
        re.compile(
            r"-----BEGIN [A-Z ]*PRIVATE KEY-----[ \t]*\r?\n[A-Za-z0-9+/=]{20,}"
        ),
    ),
    ("AWS Access Key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("GitHub token", re.compile(r"\b(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{22,})\b")),
    ("Slack token", re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}\b")),
    ("Google API key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("OpenAI/Anthropic key", re.compile(r"\bsk-[A-Za-z0-9]{24,}\b")),
]

# 形如 password = "..." / secret: ... 的赋值。噪音过滤的原则：
# 只保留「看起来就是一个写死的秘密」的取值；凡是变量、环境变量、函数调用、
# 格式化模板、开发/测试默认值，都不是本次要抓的东西（它们本来就该出现在仓库里）。
ASSIGNMENT = re.compile(
    r"(?i)\b(password|passwd|pwd|secret|api[_-]?key|apikey|access[_-]?token|"
    r"auth[_-]?token|private[_-]?key|service[_-]?token)\b\s*[:=]\s*[\"']?([^\s\"'#,;]{12,})"
)
# 取值必须「长得像凭据」：纯 ASCII、够长、含数字。中文说明文字（例如
# 「api_key= 会被上游当成无效密钥」）因此不会命中。
VALUE_SHAPE = re.compile(r"^[A-Za-z0-9_\-+.=@!:$/]{12,}$")
VALUE_NOISE = re.compile(
    r"(?i)(\$|%|\{|\}|\(|\)|\.get\(|os\.environ|getenv|process\.env|"
    r"dev|local|test|smoke|redline|example|sample|dummy|placeholder|change|changeme|"
    r"temp|temporary|mock|fake|demo|password|secret|token|none|null|todo)"
)


def scan_text(path, text, origin):
    for name, pattern in HIGH_CONFIDENCE:
        for m in pattern.finditer(text):
            line = text[: m.start()].count("\n") + 1
            fail("凭据", "%s:%d (%s)" % (origin, line, path), "疑似 %s 明文" % name)
    for m in ASSIGNMENT.finditer(text):
        value = m.group(2).strip()
        if not VALUE_SHAPE.match(value) or not re.search(r"[0-9]", value):
            continue
        if VALUE_NOISE.search(value):
            continue
        if value.startswith(("http://", "https://", "$", "<", "[")):
            continue
        line = text[: m.start()].count("\n") + 1
        fail(
            "凭据",
            "%s:%d (%s)" % (origin, line, path),
            "疑似硬编码口令/token：%s = %s***" % (m.group(1), value[:4]),
        )


for f in files:
    try:
        if os.path.getsize(f) > 2 * 1024 * 1024:
            continue  # 大文件（种子/压缩包）不在本检查范围
        text = io.open(f, encoding="utf-8", errors="ignore").read()
    except OSError:
        continue
    scan_text(f, text, "工作区")

# 提交历史：最近 30 个提交的 diff。只看高置信模式（私钥块/云 key/平台 token），
# 不做宽泛的口令启发式 —— 历史里的开发默认值很多，宽规则只会制造噪音。
history = git("log", "-n", "30", "--format=commit:%H", "-p", "--unified=0", "--text").stdout
for name, pattern in HIGH_CONFIDENCE:
    for m in pattern.finditer(history):
        line = history[: m.start()].count("\n") + 1
        fail("凭据", "提交历史:第 %d 行" % line, "最近 30 个提交的 diff 里出现疑似 %s 明文" % name)

# ---------------------------------------------------------------------------
# B/C. 工作流检查
# ---------------------------------------------------------------------------
print("== B/C. 工作流权限、触发条件与 actions 钉版本 ==")

WORKFLOWS = sorted(glob.glob(os.path.join(ROOT, ".github/workflows/*.yml")))
if not WORKFLOWS:
    fail("工作流", ".github/workflows", "没有任何工作流文件")

ACTION_PIN = re.compile(r"^\s*(?:-\s*)?uses:\s*([^\s#]+)")
SHA = re.compile(r"^[0-9a-f]{40}$")
VERSION_TAG = re.compile(r"^v\d+(\.\d+)*$")
SECRET_IN_LOG = re.compile(r"\$\{\{\s*secrets\.")


def triggers(doc):
    # YAML 1.1 会把裸 on 解析成 True
    on = doc.get("on", doc.get(True))
    if on is None:
        return []
    if isinstance(on, str):
        return [on]
    if isinstance(on, list):
        return [str(x) for x in on]
    if isinstance(on, dict):
        return [str(k) for k in on]
    return []


for wf in WORKFLOWS:
    name = os.path.relpath(wf, ROOT)
    raw = io.open(wf, encoding="utf-8").read()
    try:
        doc = yaml.safe_load(raw)
    except yaml.YAMLError as exc:
        fail("工作流", name, "YAML 解析失败：%s" % exc)
        continue
    if not isinstance(doc, dict):
        fail("工作流", name, "顶层不是映射")
        continue

    # B1 顶层权限最小化
    perms = doc.get("permissions")
    if perms is None:
        fail("工作流权限", name, "缺少顶层 permissions（默认继承仓库设置，可能过宽）")
    elif isinstance(perms, str):
        if perms not in ("read-all",):
            fail("工作流权限", name, "顶层 permissions=%s 不是只读" % perms)
    elif isinstance(perms, dict):
        for key, value in perms.items():
            if str(value).startswith("write"):
                fail("工作流权限", name, "permissions.%s=%s 是写权限，CI 不需要" % (key, value))
    else:
        fail("工作流权限", name, "permissions 结构无法识别")

    # B2 pull_request_target：本仓库没有需要它的场景，且它会带来 secret 暴露风险
    on_keys = triggers(doc)
    if "pull_request_target" in on_keys:
        fail("工作流权限", name, "使用了 pull_request_target（会以 base 仓库权限运行 PR 代码）")

    # B3 actions 必须钉版本（tag 或 SHA），不能是分支名
    for line in raw.splitlines():
        m = ACTION_PIN.match(line)
        if not m:
            continue
        ref = m.group(1)
        if ref.startswith("./"):
            continue
        if "@" not in ref:
            fail("actions 版本", name, "uses: %s 没有版本" % ref)
            continue
        version = ref.rsplit("@", 1)[1]
        if not (VERSION_TAG.match(version) or SHA.match(version)):
            fail(
                "actions 版本",
                name,
                "uses: %s 钉在 %s 上（分支/tag 可被移动，应钉 vX 或完整 SHA）" % (ref, version),
            )

    # B4 run 里不得把 secrets 打进日志
    for lineno, line in enumerate(raw.splitlines(), 1):
        if SECRET_IN_LOG.search(line) and re.search(r"\b(echo|printf|cat|tee)\b", line):
            fail("日志泄露", "%s:%d" % (name, lineno), "把 secrets 上下文直接打进日志")

    # B5 service 容器镜像必须钉 tag（latest 会让 CI 随上游漂移）
    for service, spec in (doc.get("jobs") or {}).items():
        for svc_name, svc in ((spec or {}).get("services") or {}).items():
            image = str((svc or {}).get("image", ""))
            if image and (":" not in image.rsplit("/", 1)[-1] or image.endswith(":latest")):
                fail(
                    "镜像版本",
                    "%s/%s/%s" % (name, service, svc_name),
                    "镜像 %s 未钉版本 tag" % image,
                )

    # C CI/CD 解耦：带 SSH / 部署凭据的工作流只能手动触发
    deployish = any(
        token in raw for token in ("CN_SSH_KEY", "CN_SSH_HOST", "ssh -F", "ssh-keygen")
    )
    if deployish:
        bad = [t for t in on_keys if t in ("push", "pull_request", "schedule")]
        if bad:
            fail(
                "CI/CD 解耦",
                name,
                "含部署凭据却仍由 %s 自动触发 —— push/pr 不得触发 SSH 部署" % ", ".join(bad)
            )

# ---------------------------------------------------------------------------
# D. 构建配置
# ---------------------------------------------------------------------------
print("== D. 构建配置（pom / .gitignore / .npmrc）==")

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
POMS = sorted(glob.glob(os.path.join(ROOT, "**/pom.xml"), recursive=True))
BAD_VERSION = re.compile(r"(?i)^(\[|\(|.*SNAPSHOT$|LATEST$|RELEASE$)")
for pom in POMS:
    if "/target/" in pom or "/node_modules/" in pom:
        continue
    rel = os.path.relpath(pom, ROOT)
    try:
        root = ET.parse(pom).getroot()
    except ET.ParseError as exc:
        fail("构建配置", rel, "pom 解析失败：%s" % exc)
        continue
    for repo in root.findall(".//m:repositories/m:repository", NS):
        url = (repo.findtext("m:url", "", NS) or "").strip()
        if url.startswith("http://"):
            fail("构建配置", rel, "依赖仓库使用明文 http：%s" % url)
    for dep in root.findall(".//m:dependencies/m:dependency", NS):
        version = (dep.findtext("m:version", "", NS) or "").strip()
        artifact = (dep.findtext("m:artifactId", "", NS) or "").strip()
        if version and BAD_VERSION.match(version) and not version.startswith("${"):
            fail(
                "构建配置",
                rel,
                "依赖 %s 的版本为 %s（SNAPSHOT/LATEST/区间都不可复现）" % (artifact, version),
            )

gitignore = io.open(os.path.join(ROOT, ".gitignore"), encoding="utf-8").read()
for needed in (".env", "*.pem", "*.key", "secrets/"):
    if needed not in gitignore:
        fail("构建配置", ".gitignore", "未忽略 %s（凭据误提交的第一道防线）" % needed)

npmrc = os.path.join(ROOT, "frontend", ".npmrc")
if os.path.exists(npmrc):
    for lineno, line in enumerate(io.open(npmrc, encoding="utf-8").read().splitlines(), 1):
        if re.match(r"^\s*cache\s*=\s*[A-Za-z]:[\\/]", line):
            fail(
                "构建配置",
                "frontend/.npmrc:%d" % lineno,
                "硬编码的 Windows 缓存路径 %s（Linux 下会生成 frontend/D:/ 脏目录）"
                % line.strip(),
            )

# ---------------------------------------------------------------------------
print()
if findings:
    print("结果：失败（%d 项）" % len(findings), file=sys.stderr)
    for item in findings:
        print("  - %s" % item, file=sys.stderr)
    raise SystemExit(1)
print("结果：通过 —— 无明文凭据、工作流权限只读、部署工作流仅手动触发、构建配置无不可复现项")
PY
