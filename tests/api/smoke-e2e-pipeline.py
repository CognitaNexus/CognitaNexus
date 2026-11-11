#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""全链路端到端自检：新来源 → 真实抓取 → 交接 → core 入库 → 发布 → 投影 → 检索 → 统计。

跑之前整栈要在运行（bash ops/local/stack.sh）。脚本会**真的**新建一个 OpenAlex 来源并抓取，
用冷门查询词使本轮命中的是尚未入库的记录；若本轮抓到的全部是既有稳定键（去重为 0 条暂存），
脚本会明确报「未产生新数据」并失败——不做「接口 200 就算通过」的断言。

用法：
    python tests/api/smoke-e2e-pipeline.py [--query "..."]

退出码 0 表示全部通过；非 0 表示有失败项，明细在输出里。
"""

import http.cookiejar
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

CORE = os.environ.get("CORE_BASE", "http://127.0.0.1:8081")
ING = os.environ.get("ING_BASE", "http://127.0.0.1:8082")
QRY = os.environ.get("QRY_BASE", "http://127.0.0.1:8083")
ADMIN_USER = os.environ.get("ADMIN_USER", "cnadmin")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "cn-admin-Dev-2026")

PSQL = os.environ.get("PSQL_BIN", "D:/dev-tools/pgsql/bin/psql.exe")

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  -> " + str(detail)) if detail else ""))


def psql(db, user, sql):
    env = dict(os.environ)
    env["PGPASSWORD"] = "cn_%s_app_dev" % user
    env["PGCLIENTENCODING"] = "UTF8"
    env["MSYS_NO_PATHCONV"] = "1"
    out = subprocess.run(
        [PSQL, "-h", "127.0.0.1", "-p", "5432", "-U", user + "_app", "-d", db, "-At", "-f", "-"],
        input=sql.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
    )
    if out.returncode != 0:
        raise RuntimeError("psql 失败：" + out.stderr.decode("utf-8", "replace")[:300])
    return [line.strip() for line in out.stdout.decode("utf-8", "replace").splitlines() if line.strip()]


class Api:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar),
            urllib.request.ProxyHandler({}),
        )

    def raw(self, method, url, body=None, headers=None, raw_body=None, content_type=None):
        data = None
        hdrs = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            hdrs["Content-Type"] = "application/json"
        if raw_body is not None:
            data = raw_body
            if content_type:
                hdrs["Content-Type"] = content_type
        if headers:
            hdrs.update(headers)
        req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
        try:
            with self.opener.open(req, timeout=120) as resp:
                return resp.status, resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as err:
            return err.code, err.read().decode("utf-8", "replace")

    def csrf(self):
        self.raw("GET", CORE + "/api/v1/auth/csrf")
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN":
                return urllib.parse.unquote(cookie.value)
        return ""

    def write(self, method, url, body=None):
        token = self.csrf()
        code, text = self.raw(method, url, body=body, headers={"X-XSRF-TOKEN": token})
        try:
            return code, json.loads(text) if text else {}
        except ValueError:
            return code, {"raw": text}

    def read(self, url):
        code, text = self.raw("GET", url)
        try:
            return code, json.loads(text) if text else {}
        except ValueError:
            return code, {"raw": text}


def data_of(payload):
    return (payload or {}).get("data") or {}


def main():
    query = "artificial intelligence compiler optimization"
    if "--query" in sys.argv:
        query = sys.argv[sys.argv.index("--query") + 1]

    api = Api()
    api.raw("GET", CORE + "/api/v1/auth/csrf")
    code, payload = api.write(
        "POST", CORE + "/api/v1/auth/login", {"username": ADMIN_USER, "password": ADMIN_PASS}
    )
    check("平台管理员登录", code == 200 and payload.get("code") == "OK", payload.get("message"))
    if code != 200:
        return finish()

    stamp = uuid.uuid4().hex[:8]
    source_code = "e2e-openalex-" + stamp
    allowed = [
        "kind", "title", "doi", "venue", "publicationYear", "publishedDate",
        "citationCount", "citationSource", "abstractText", "topics", "sourceCategory", "authors",
    ]

    # ---- 基线：core 成果数、公开检索总数 ----
    before_core = int(psql("core_db", "core", "SELECT count(*) FROM core_achievement;")[0])
    code, overview = api.read(QRY + "/api/v1/public/analytics/overview")
    before_public = int((data_of(overview).get("totals") or {}).get("achievements") or 0)
    print("        基线：core 成果 %d 条，公开统计 %d 条" % (before_core, before_public))
    # 用 epoch 而不是格式化时间：psql 输出的 timestamp 不带时区，再转 timestamptz 会按服务器
    # 时区解释，差 8 小时会让「本轮新增」直接查成空集。
    started_epoch = int(psql("core_db", "core", "SELECT extract(epoch from now())::bigint;")[0])
    # started_epoch 保留给排查用（打印时间点），不再用于筛成果。

    # ---- 1. 登记两端来源 ----
    code, body = api.write(
        "POST",
        CORE + "/api/v1/admin/core/sources",
        {
            "sourceCode": source_code,
            "displayName": "端到端自检来源 %s" % stamp,
            "licenseScope": "OpenAlex 公开 API，元数据 CC0（自检临时来源）",
            "baseUrl": "https://api.openalex.org/works",
            "allowedFields": allowed,
            "enabled": True,
            "licenseReviewed": True,
        },
    )
    check("登记 core 权威来源", code == 200 and body.get("code") == "OK", body.get("code"))

    code, body = api.write(
        "POST",
        ING + "/api/v1/admin/ingestion/sources",
        {
            "sourceCode": source_code,
            "displayName": "端到端自检执行副本",
            "adapterKind": "OPENALEX",
            "baseEndpoint": "https://api.openalex.org/works",
            "queryExpression": query,
            "pageSize": 20,
            "licenseReviewState": "APPROVED",
            "licenseNote": "自检临时来源",
            "rateLimitPerMinute": 60,
            "enabled": True,
        },
    )
    check("登记 ingestion 执行副本", code == 200 and body.get("code") == "OK", body.get("code"))
    source_id = data_of(body).get("id") or data_of(body).get("sourceId")
    check("取得执行副本 id", bool(source_id), source_id)
    if not source_id:
        return finish()

    code, body = api.write("POST", ING + "/api/v1/admin/ingestion/sources/%s/sync" % source_id)
    check("同步 core 权威配置", code == 200 and body.get("code") == "OK", body.get("code"))

    # ---- 2. 创建任务并真实抓取 ----
    job_key = "e2e-" + stamp
    code, body = api.write(
        "POST",
        ING + "/api/v1/admin/ingestion/jobs",
        {"sourceId": source_id, "jobKey": job_key, "reason": "全链路端到端自检"},
    )
    job_id = data_of(body).get("job", {}).get("id")
    check("创建采集任务", data_of(body).get("created") is True and bool(job_id), job_id)
    if not job_id:
        return finish()

    code, body = api.write(
        "POST", ING + "/api/v1/admin/ingestion/jobs/%s/run?maxRounds=1" % job_id
    )
    run = data_of(body)
    # run 接口不回 fetched：抓取量 = 暂存 + 稳定键重复（被拒收的另计）。用这两个字段相加，
    # 避免为了一个展示字段在服务端造一个可能对不上的数。
    fetched = int(run.get("staged") or 0) + int(run.get("duplicated") or 0)
    print("        抓取结果：抓取 %d，暂存 %s，重复 %s，已交接 %s，失败=%s"
          % (fetched, run.get("staged"), run.get("duplicated"), run.get("handedOff"), run.get("fetchFailed")))
    check("抓到真实来源记录", fetched > 0, fetched)
    check("本轮抓取未失败", run.get("fetchFailed") is not True, run.get("lastMessage"))
    check("本轮产生新的暂存记录（非全部重复）", int(run.get("staged") or 0) > 0, run.get("staged"))
    if int(run.get("staged") or 0) == 0:
        print("        本轮抓到的全部命中既有稳定键，无法验证「新数据进入公开目录」；")
        print("        这不是接口故障，但作为全链路自检没有意义，按失败处理。")
        return finish()

    # ---- 3. core 侧新成果 ----
    # 只统计「由本来源的记录产生」的成果。后台执行器同时在抓别的来源，按时间窗统计会把它们的
    # 新成果算进来（实测时间窗内混入了 500 多条），那样的数字无法证明这条链路。
    core_source_id = psql(
        "core_db", "core", "SELECT id FROM raw_source_config WHERE source_code = '%s';" % source_code
    )[0]
    by_source_sql = (
        "SELECT DISTINCT a.id FROM core_achievement a "
        "JOIN core_achievement_source s ON s.achievement_id = a.id "
        "JOIN raw_source_record r ON r.id = s.source_record_id "
        "WHERE r.source_id = '%s' AND a.type = 'PAPER' ORDER BY a.id;" % core_source_id
    )
    deadline = time.time() + 90
    new_ids = []
    while time.time() < deadline:
        new_ids = [row for row in psql("core_db", "core", by_source_sql) if row.startswith("ach_")]
        if new_ids:
            break
        time.sleep(2)
    check("本来源的记录在 core 生成了成果实体", len(new_ids) > 0, "%d 条" % len(new_ids))
    if not new_ids:
        return finish()

    state_rows = psql(
        "core_db",
        "core",
        "SELECT id || '|' || effective_status || '|' || data_nature || '|' || "
        "coalesce(publication_year::text, '') FROM core_achievement WHERE id IN (%s);"
        % ",".join("'%s'" % i for i in new_ids),
    )
    states = {}
    for row in state_rows:
        parts = row.split("|")
        states[parts[0]] = {"status": parts[1], "nature": parts[2], "year": parts[3]}
    print("        新成果：%d 条，状态分布 %s"
          % (len(states), sorted({v["status"] for v in states.values()})))
    check("新成果的数据性质为真实（REAL）",
          all(v["nature"] == "REAL" for v in states.values()),
          sorted({v["nature"] for v in states.values()}))

    # ---- 4. 发布（自动发布未覆盖的走人工确认） ----
    pending = [i for i, v in states.items() if v["status"] != "EFFECTIVE"]
    confirmed = 0
    for achievement_id in pending:
        code, body = api.write(
            "POST",
            CORE + "/api/v1/admin/core/achievements/%s/confirm" % achievement_id,
            {"reason": "全链路自检：真实来源成果，字段完整，允许进入公开目录"},
        )
        if body.get("code") == "OK":
            confirmed += 1
        else:
            print("        确认失败 %s：%s" % (achievement_id, body.get("message")))
    if pending:
        check("待审的新成果可被人工确认发布", confirmed == len(pending),
              "%d/%d" % (confirmed, len(pending)))
    else:
        check("新成果满足自动发布条件（无需人工确认）", True, "0 条待审")

    # ---- 5. 等投影消费 ----
    deadline = time.time() + 90
    projected = []
    while time.time() < deadline:
        rows = psql(
            "query_db",
            "query",
            "SELECT a.achievement_id FROM qry_achievement a "
            "JOIN qry_generation g ON g.generation = a.generation AND g.status = 'READY' "
            "WHERE a.achievement_id IN (%s);" % ",".join("'%s'" % i for i in new_ids),
        )
        projected = [row for row in rows if row.startswith("ach_")]
        if len(projected) >= len([i for i in new_ids if states[i]["status"] == "EFFECTIVE"]) and projected:
            break
        time.sleep(3)
    check("新成果已进入活动投影代", len(projected) > 0, "%d 条" % len(projected))

    # ---- 6. 公开检索命中 ----
    title = psql(
        "core_db",
        "core",
        "SELECT title FROM core_achievement WHERE id = '%s';" % projected[0],
    )[0]
    # 用整条标题作为查询：单词命中（如 "intelligence"）在万级数据里会命中几百条，目标未必进
    # 前 20——那是脚本选词不当，不是检索有问题。整句能直接考察「按标题能不能找回这一条」。
    keyword = title[:120]
    code, body = api.read(
        QRY + "/api/v1/public/search/achievements?q=%s&size=20" % urllib.parse.quote(keyword)
    )
    items = data_of(body).get("items") or []
    hit_ids = [item.get("id") for item in items]
    check("公开检索命中新成果", projected[0] in hit_ids,
          "关键词=%s，命中 %d 条" % (keyword, len(items)))
    check("检索命中项带真实数据性质",
          all(item.get("dataNature") == "REAL" for item in items if item.get("id") == projected[0]),
          "")

    # ---- 7. 公开详情字段 ----
    code, detail_body = api.read(CORE + "/api/v1/public/achievements/%s" % projected[0])
    detail = data_of(detail_body)
    check("公开详情可打开", code == 200 and detail_body.get("code") == "OK", detail_body.get("code"))
    check("详情标题与库内一致", detail.get("title") == title, detail.get("title"))
    check("详情带数据性质", detail.get("dataNature") == "REAL", detail.get("dataNature"))
    contributors = detail.get("contributors") or []
    topics = detail.get("topics") or []
    print("        详情：类型=%s，作者 %d 位，领域 %d 个"
          % (detail.get("type"), len(contributors), len(topics)))
    check("详情带参与关系（作者）", len(contributors) > 0, len(contributors))
    check("详情带领域归属", len(topics) > 0, len(topics))

    # ---- 8. 统计口径 ----
    deadline = time.time() + 60
    after_public = before_public
    while time.time() < deadline:
        code, overview = api.read(QRY + "/api/v1/public/analytics/overview")
        after_public = int((data_of(overview).get("totals") or {}).get("achievements") or 0)
        if after_public > before_public:
            break
        time.sleep(3)
    check("公开统计总数随新成果增加", after_public > before_public,
          "%d -> %d" % (before_public, after_public))

    return finish()


def finish():
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    failed = len(RESULTS) - passed
    print("\n结果：通过 %d 项，失败 %d 项" % (passed, failed))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
