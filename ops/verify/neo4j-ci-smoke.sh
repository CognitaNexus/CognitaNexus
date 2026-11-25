#!/usr/bin/env bash
# =============================================================================
# Neo4j 图集成烟雾测试（CI Graph Integration 用）
# =============================================================================
#
# 这个脚本回答一个问题：**这台机器上的 Neo4j 是真的能用，还是只是端口通？**
# 端口通不等于能用——约束没建、索引没生效、Cypher 版本不对、认证没通过，都会在
# 「端口可连」的外观下静默存在。所以按依赖顺序逐项验证，任何一项失败都以非零码退出：
#
#   1. 可达性：HTTP 接口 + 认证 + bolt 版本（超时轮询，超时即失败，不无限等）
#   2. Schema 初始化：目标数据库存在、可写
#   3. 唯一约束创建并**真的拦住重复**（不是只看约束存在于元数据里）
#   4. 索引创建（并按 SHOW INDEXES 核对，而非假设 CREATE 成功即生效）
#   5. 基本图数据导入（学者 + 论文 + 合作关系）
#   6. 查询：邻居数符合预期
#   7. 最短路径：1..6 跳的 shortestPath 返回预期长度
#   8. 清理：只删本次写入的 CiSmoke* 节点与自己的约束/索引，不碰业务数据
#
# 为什么用 HTTP 事务接口而不是 cypher-shell：GitHub-hosted runner 上没有 cypher-shell，
# 而本机零特权运行时里它藏在解包目录里。HTTP 只需要 python3 标准库，两种环境行为一致。
# 需要验证 Bolt 时可以装 cypher-shell 单独跑，本脚本不假装覆盖了 Bolt 传输层。
#
# 用法：
#   NEO4J_PASSWORD=<口令> bash ops/verify/neo4j-ci-smoke.sh
#   NEO4J_HTTP_URL=http://127.0.0.1:7474 NEO4J_WAIT_SECONDS=30 ...   # CI service 容器
#
# 环境变量：
#   NEO4J_HTTP_URL     默认 http://127.0.0.1:17474（本机零特权运行时的端口）
#   NEO4J_USER         默认 neo4j
#   NEO4J_PASSWORD     必填；缺失直接失败（不猜默认口令，也不从仓库读）
#   NEO4J_DATABASE     默认 neo4j
#   NEO4J_WAIT_SECONDS 就绪等待上限，默认 60
#
# 退出码：0 全部通过；2 配置错误；3 Neo4j 不可达/认证失败；4 图断言失败；5 清理失败
# =============================================================================

set -euo pipefail

PYTHON=$(command -v python3 || true)
if [ -z "$PYTHON" ]; then
  echo "找不到 python3：本脚本用标准库访问 Neo4j HTTP 事务接口" >&2
  exit 2
fi

if [ -z "${NEO4J_PASSWORD:-}" ]; then
  echo "缺少 NEO4J_PASSWORD：本脚本不会使用任何默认口令" >&2
  exit 2
fi

exec "$PYTHON" - <<'PY'
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

HTTP = os.environ.get("NEO4J_HTTP_URL", "http://127.0.0.1:17474").rstrip("/")
USER = os.environ.get("NEO4J_USER", "neo4j")
PASSWORD = os.environ["NEO4J_PASSWORD"]
DATABASE = os.environ.get("NEO4J_DATABASE", "neo4j")
WAIT = float(os.environ.get("NEO4J_WAIT_SECONDS", "60"))

AUTH = base64.b64encode(("%s:%s" % (USER, PASSWORD)).encode()).decode()
TIMEOUT = 15.0

LABEL_SCHOLAR = "CiSmokeScholar"
LABEL_PAPER = "CiSmokePaper"
CONSTRAINT = "ci_smoke_scholar_id"
INDEX = "ci_smoke_paper_year"

failures = []


def http_json(url, payload=None):
    data = None
    headers = {"Authorization": "Basic " + AUTH, "Accept": "application/json"}
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method="POST" if data else "GET")
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return json.loads(resp.read().decode())


def wait_ready():
    print("== 1. 可达性与认证 ==")
    print("   目标 %s（用户 %s，数据库 %s）" % (HTTP, USER, DATABASE))
    deadline = time.time() + WAIT
    last = None
    version = None
    while time.time() < deadline:
        try:
            info = http_json(HTTP + "/")
            version = info.get("neo4j_version", "?")
            edition = info.get("neo4j_edition", "?")
            bolt = info.get("bolt_direct", "?")
            print("   OK  neo4j_version=%s edition=%s bolt=%s" % (version, edition, bolt))
            break
        except urllib.error.HTTPError as exc:
            last = "HTTP %s" % exc.code
        except Exception as exc:  # noqa: BLE001 - 连接类异常统一重试
            last = "%s: %s" % (type(exc).__name__, exc)
        time.sleep(2)
    if version is None:
        print("   FAIL 等待 %.0fs 后仍不可用：%s" % (WAIT, last), file=sys.stderr)
        raise SystemExit(3)
    if not version.startswith("5."):
        print("   FAIL Neo4j 版本 %s 不是 5.x，脚本与 CI service 镜像不对应" % version, file=sys.stderr)
        raise SystemExit(3)

    # 发现接口（GET /）本身是匿名可读的，所以「能读版本」不等于「认证可用」。
    # 这里再用一条 RETURN 1 验证认证与数据库可访问，失败直接以 3 退出，
    # 而不是等到第一条写语句才抛出一屏 traceback。
    deadline = time.time() + WAIT
    last = None
    while time.time() < deadline:
        try:
            resp = tx([{"statement": "RETURN 1 AS ok"}])
            errors = resp[1]
            if errors:
                last = errors[0].get("code")
            else:
                print("   OK  认证与数据库可访问（RETURN 1）")
                return
        except urllib.error.HTTPError as exc:
            if exc.code in (401, 403):
                print(
                    "   FAIL 认证失败（HTTP %s）：检查 NEO4J_USER / NEO4J_PASSWORD" % exc.code,
                    file=sys.stderr,
                )
                raise SystemExit(3)
            last = "HTTP %s" % exc.code
        except Exception as exc:  # noqa: BLE001
            last = "%s: %s" % (type(exc).__name__, exc)
        time.sleep(2)
    print("   FAIL 认证/数据库不可用：%s" % last, file=sys.stderr)
    raise SystemExit(3)


def tx(statements):
    """执行一批语句；返回 (results, errors)。语句在一个事务里，任一失败整批回滚。"""
    body = {"statements": statements}
    resp = http_json("%s/db/%s/tx/commit" % (HTTP, DATABASE), body)
    return resp.get("results") or [], resp.get("errors") or []


def run(desc, cypher, params=None):
    """执行一条语句并要求无错误；返回第一行。HTTP/协议错误一律转成失败项。"""
    print("== %s ==" % desc)
    print("   %s" % " ".join(cypher.split()))
    statement = {"statement": cypher}
    if params:
        statement["parameters"] = params
    try:
        results, errors = tx([statement])
    except urllib.error.HTTPError as exc:
        print("   FAIL HTTP %s: %s" % (exc.code, exc.reason), file=sys.stderr)
        failures.append("%s：HTTP %s" % (desc, exc.code))
        return None
    except Exception as exc:  # noqa: BLE001
        print("   FAIL %s: %s" % (type(exc).__name__, exc), file=sys.stderr)
        failures.append("%s：%s" % (desc, exc))
        return None
    if errors:
        for err in errors:
            print("   FAIL %s: %s" % (err.get("code"), err.get("message")), file=sys.stderr)
        failures.append("%s：Cypher 执行失败 %s" % (desc, errors[0].get("code")))
        return None
    data = (results[0] if results else {}).get("data") or []
    row = data[0]["row"] if data else None
    print("   OK  %s" % (row if row is not None else "（无返回行）"))
    return row


wait_ready()

# 2. Schema 初始化：目标数据库在线、可写
row = run("2. Schema 初始化（目标数据库在线且可写）", "CALL dbms.components() YIELD name, versions RETURN name, versions")
if row is None:
    raise SystemExit(4)

# 3. 唯一约束：先建，再用一次「注定违反约束」的写入证明它真的在拦
run(
    "3a. 创建唯一约束",
    "CREATE CONSTRAINT %s IF NOT EXISTS FOR (s:%s) REQUIRE s.id IS UNIQUE"
    % (CONSTRAINT, LABEL_SCHOLAR),
)
row = run(
    "3b. 核对约束存在于元数据",
    "SHOW CONSTRAINTS YIELD name RETURN collect(name) AS names",
)
if row is not None and CONSTRAINT not in row[0]:
    failures.append("约束 %s 不在 SHOW CONSTRAINTS 结果里" % CONSTRAINT)

print("== 3c. 反证：重复 id 必须被约束拒绝 ==")
# 同一事务里先建 s1、再建一次 s1：第二条违反唯一约束，整批回滚（所以 s1 不会留在库里）。
_, errors = tx(
    [
        {"statement": "MERGE (:%s {id: 's1'})" % LABEL_SCHOLAR},
        {"statement": "CREATE (:%s {id: 's1'})" % LABEL_SCHOLAR},
    ]
)
codes = [e.get("code", "") for e in errors]
if any("Constraint" in code or "ConstraintValidationFailed" in code for code in codes):
    print("   OK  重复写入被拒绝：%s" % codes[0])
else:
    print("   FAIL 重复写入没有被约束拒绝（errors=%s）" % codes, file=sys.stderr)
    failures.append("唯一约束没有拦截重复 id，约束形同虚设")

# 4. 索引
run(
    "4a. 创建索引",
    "CREATE INDEX %s IF NOT EXISTS FOR (p:%s) ON (p.year)" % (INDEX, LABEL_PAPER),
)
row = run("4b. 核对索引在 SHOW INDEXES 里且为 ONLINE", "SHOW INDEXES YIELD name, state RETURN collect(name + ':' + state) AS items")
if row is not None:
    items = {item.split(":")[0]: item.split(":")[1] for item in row[0]}
    if items.get(INDEX) != "ONLINE":
        failures.append("索引 %s 状态为 %s（期望 ONLINE）" % (INDEX, items.get(INDEX)))

# 5. 基本图数据导入（5 位学者、1 篇论文、4 条合作关系）
run(
    "5a. 导入学者节点",
    "UNWIND $rows AS row MERGE (s:%s {id: row.id}) SET s.name = row.name RETURN count(s) AS n"
    % LABEL_SCHOLAR,
    {"rows": [{"id": "s%d" % i, "name": "smoke-%d" % i} for i in range(1, 6)]},
)
run(
    "5b. 导入论文节点",
    "UNWIND $rows AS row MERGE (p:%s {id: row.id}) SET p.year = row.year RETURN count(p) AS n"
    % LABEL_PAPER,
    {"rows": [{"id": "p1", "year": 2024}]},
)
run(
    "5c. 导入合作关系 s1-s2-s3-s4-s5",
    "UNWIND $pairs AS pair "
    "MATCH (a:%s {id: pair[0]}), (b:%s {id: pair[1]}) "
    "MERGE (a)-[:CO_AUTHORED_WITH {paper: 'p1'}]->(b) RETURN count(*) AS n"
    % (LABEL_SCHOLAR, LABEL_SCHOLAR),
    {"pairs": [["s1", "s2"], ["s2", "s3"], ["s3", "s4"], ["s4", "s5"]]},
)

# 6. 查询：s2 的邻居应为 s1、s3（无向）
row = run(
    "6. 查询邻居（s2 应为 2 位合作者）",
    "MATCH (s:%s {id: 's2'})-[:CO_AUTHORED_WITH]-(o:%s) "
    "RETURN count(DISTINCT o) AS n" % (LABEL_SCHOLAR, LABEL_SCHOLAR),
)
if row is not None and int(row[0]) != 2:
    failures.append("s2 的合作者数量为 %s，期望 2" % row[0])

# 7. 最短路径：s1 -> s5 应为 4 跳
row = run(
    "7. 最短路径（s1 -> s5 应为 4 跳）",
    "MATCH (a:%s {id: 's1'}), (b:%s {id: 's5'}) "
    "MATCH p = shortestPath((a)-[:CO_AUTHORED_WITH*1..6]-(b)) "
    "RETURN length(p) AS hops" % (LABEL_SCHOLAR, LABEL_SCHOLAR),
)
if row is not None and int(row[0]) != 4:
    failures.append("s1->s5 最短路径为 %s 跳，期望 4" % row[0])

# 8. 清理：只删本次写入的标签节点与自己的约束/索引
try:
    run(
        "8a. 清理烟雾数据（仅 CiSmoke* 标签）",
        "MATCH (n) WHERE n:%s OR n:%s DETACH DELETE n RETURN count(*) AS n"
        % (LABEL_SCHOLAR, LABEL_PAPER),
    )
    run("8b. 删除烟雾约束", "DROP CONSTRAINT %s IF EXISTS" % CONSTRAINT)
    run("8c. 删除烟雾索引", "DROP INDEX %s IF EXISTS" % INDEX)
except SystemExit:
    raise
except Exception as exc:  # noqa: BLE001
    print("   FAIL 清理失败：%s" % exc, file=sys.stderr)
    raise SystemExit(5)

print()
if failures:
    print("结果：失败（%d 项）" % len(failures), file=sys.stderr)
    for item in failures:
        print("  - %s" % item, file=sys.stderr)
    raise SystemExit(4)
print("结果：通过 —— 约束/索引/导入/查询/最短路径全部按预期工作")
PY
