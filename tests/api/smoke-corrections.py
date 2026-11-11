#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""公众纠错入口端到端自检（FR02 / FR16）。

验证的不是「接口 200」，而是这些产品规则：

1. 匿名可读字段清单、可提交纠错——发现写错的往往是站外读者，要求先注册等于关掉反馈渠道；
2. 提交**不改数据**：只登记一条 OPEN 的治理待办，成果字段原样不动；
3. 同一问题重复提交并入既有待办（reportCount 递增），不新增第二条——否则刷 100 次就是
   100 条待办，治理队列被淹掉；
4. 不同字段是不同问题，各自成条；
5. 不存在 / 不可见的成果一律 404，且与「不存在」不可区分（不泄露受限对象的存在性）；
6. 内容边界：说明过短、说明超长、字段取值未知都被拒（400 INVALID_ARGUMENT），且不产生待办；
7. 限流真实生效：同一来源 10 分钟内第 6 次提交返回 429；
8. 管理员待办里 correctionsOpen 与库中 OPEN 条数一致，且 anomaliesOpen 把它算进去
   （子集关系，不重复相加）；
9. 治理列表能看到报告原文与累计次数；标记已处理后 correctionsOpen 回落，重复标记返回 409。

前置：整栈已启动（bash ops/local/stack.sh）。

副作用：登记 2 条治理待办并当场标记处理完毕（治理台账里留痕，这是审计要的）。

关于重复运行：服务端限流键是来源地址，10 分钟窗口在多次运行之间是共享的，而本脚本一次运行
正好用完 5 个配额。因此在 10 分钟内重复运行会立刻收到 429——脚本会明确报「窗口未过期」
并以退出码 3 结束，不把它伪装成通过。等窗口过期后重跑即可。

脚本写入的报告原文都带 `[自检]` 前缀，启动时会先把上次中断留下、仍处 OPEN 的自检待办标记为
已处理，避免上一轮的残留让「首次提交」变成「并入既有待办」。

用法：
  python tests/api/smoke-corrections.py
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

CORE = os.environ.get("CORE_BASE", "http://127.0.0.1:8081")
ADMIN_USER = os.environ.get("ADMIN_USER", "cnadmin")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "cn-admin-Dev-2026")
PSQL = os.environ.get("PSQL_BIN", "D:/dev-tools/pgsql/bin/psql.exe")

KIND = "PUBLIC_CORRECTION_REPORT"
MARK = "[自检]"

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  -> " + str(detail)) if detail else ""))


def skip(name, why):
    RESULTS.append((name, False, "SKIP: " + why))
    print("  SKIP  " + name + "  -> " + why)


def psql(db, user, sql, sep="|"):
    env = dict(os.environ)
    env["PGPASSWORD"] = "cn_%s_app_dev" % user
    env["PGCLIENTENCODING"] = "UTF8"
    env["MSYS_NO_PATHCONV"] = "1"
    out = subprocess.run(
        [PSQL, "-h", "127.0.0.1", "-p", "5432", "-U", user + "_app", "-d", db, "-At", "-F", sep,
         "-v", "ON_ERROR_STOP=1", "-f", "-"],
        input=sql.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr.decode("utf-8", "replace")[:400])
    return [line.strip() for line in out.stdout.decode("utf-8", "replace").splitlines() if line.strip()]


class Session:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar), urllib.request.ProxyHandler({})
        )

    def _send(self, method, url, body=None, token=None):
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token:
            headers["X-XSRF-TOKEN"] = token
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=60) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
        except urllib.error.HTTPError as err:
            payload = err.read()
            try:
                return err.code, json.loads(payload.decode("utf-8")) if payload else {}
            except ValueError:
                return err.code, {"raw": payload.decode("utf-8", "replace")[:200]}

    def csrf(self):
        self._send("GET", CORE + "/api/v1/auth/csrf")
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN":
                return urllib.parse.unquote(cookie.value)
        return ""

    def get(self, path):
        return self._send("GET", CORE + path)

    def post(self, path, body=None):
        return self._send("POST", CORE + path, body if body is not None else {}, self.csrf())

    def login(self, user, password):
        return self._send(
            "POST", CORE + "/api/v1/auth/login",
            {"username": user, "password": password}, self.csrf()
        )


def data_of(payload):
    return (payload or {}).get("data") or {}


def code_of(payload):
    return (payload or {}).get("code") or ""


def report(session, achievement_id, field, message, contact=None):
    body = {"field": field, "message": message}
    if contact:
        body["contact"] = contact
    return session.post("/api/v1/public/achievements/%s/correction-reports" % achievement_id, body)


def open_rows(achievement_id):
    """该成果下未处理的公众纠错待办（去重键、计数、字段）。

    去重键本身含 `|`，因此这里必须换用制表符作列分隔，否则键会被切成三列。
    """
    rows = psql(
        "core_db",
        "core",
        """
        SELECT detail->>'dedupeKey', detail->>'reportCount', detail->>'field'
        FROM gov_anomaly
        WHERE kind = '%s' AND status = 'OPEN'
          AND entity_id = '%s'
        ORDER BY created_at;
        """ % (KIND, achievement_id),
        sep="\t",
    )
    parsed = []
    for line in rows:
        parts = line.split("\t")
        parsed.append(
            {"dedupeKey": parts[0], "count": int(parts[1] or 0),
             "field": parts[2] if len(parts) > 2 else ""}
        )
    return parsed


def count_open(achievement_id=None):
    where = "kind = '%s' AND status = 'OPEN'" % KIND
    if achievement_id:
        where += " AND entity_id = '%s'" % achievement_id
    rows = psql("core_db", "core", "SELECT count(*) FROM gov_anomaly WHERE %s;" % where)
    return int(rows[0])


def cleanup_self_test_reports(admin):
    """把上次中断留下的「自检」待办标记为已处理。

    只认 detail.report 以 [自检] 开头的那批：人工核实中的真实报告不会被这里碰到。
    """
    rows = psql(
        "core_db",
        "core",
        """
        SELECT id FROM gov_anomaly
        WHERE kind = '%s' AND status = 'OPEN' AND detail->>'report' LIKE '%s%%';
        """ % (KIND, MARK),
    )
    for row in rows:
        admin.post("/api/v1/admin/core/governance/anomalies/%s/resolve" % row)
    if rows:
        print("已清理上次运行残留的自检待办 %d 条" % len(rows))
    return len(rows)


def main():
    anon = Session()

    # ------------------------------------------------------------------
    # 0. 准备：可公开的成果 + 管理员会话 + 清理残留
    # ------------------------------------------------------------------
    rows = psql(
        "core_db",
        "core",
        """
        SELECT id, title FROM core_achievement
        WHERE visibility = 'PUBLIC' AND effective_status = 'EFFECTIVE' AND merged_into_id IS NULL
        ORDER BY id LIMIT 1;
        """,
        sep="\t",
    )
    if not rows:
        print("没有可用的公开成果，无法自检。")
        return 1
    achievement_id = rows[0].split("\t")[0]
    print("被纠错成果：%s" % achievement_id)

    admin = Session()
    code, _ = admin.login(ADMIN_USER, ADMIN_PASS)
    if code != 200:
        raise RuntimeError("管理员登录失败：%s" % code)

    cleanup_self_test_reports(admin)

    # ------------------------------------------------------------------
    # 1. 字段清单（匿名可读）
    # ------------------------------------------------------------------
    print("== 1 字段清单 ==")
    code, payload = anon.get("/api/v1/public/correction-fields")
    fields = data_of(payload) if isinstance(data_of(payload), list) else []
    check("匿名可读纠错字段清单", code == 200 and len(fields) >= 5, (code, len(fields)))
    check(
        "清单含稳定取值与中文标签",
        any(row.get("code") == "TITLE" and row.get("label") for row in fields),
        fields[:2],
    )

    # 记录提交前的成果字段，用于证明「纠错不改数据」
    before = psql(
        "core_db",
        "core",
        "SELECT title || '§' || coalesce(publication_year::text, 'null') || '§' || version::text"
        " FROM core_achievement WHERE id = '%s';" % achievement_id,
    )[0]

    # ------------------------------------------------------------------
    # 2. 不存在的成果：404（这一步同时探明限流窗口是否已满）
    # ------------------------------------------------------------------
    print("== 2 存在性不泄露 ==")
    code, payload = report(anon, "ach_does_not_exist", "TITLE", MARK + "这条记录看起来根本不存在")
    if code == 429:
        print(
            "\n限流窗口未过期：同一来源 10 分钟内已提交 5 次。"
            "本脚本一次运行会用完整个配额，请等待窗口过期后重跑（不影响已通过的部分）。"
        )
        return 3
    check(
        "不存在的成果返回 404",
        code == 404 and code_of(payload) == "NOT_FOUND",
        (code, code_of(payload)),
    )

    # ------------------------------------------------------------------
    # 3. 首次提交：登记一条待办，不修改成果
    # ------------------------------------------------------------------
    print("== 3 首次提交 ==")
    first_message = MARK + "这篇记录的标题少了一个副标题，来源页面上是完整的"
    code, payload = report(anon, achievement_id, "TITLE", first_message)
    body = data_of(payload)
    check(
        "首次提交被受理",
        code == 200 and body.get("accepted") is True and body.get("reportCount") == 1,
        (code, body.get("reportCount")),
    )
    check("回执带字段中文标签", body.get("fieldLabel") == "标题", body.get("fieldLabel"))
    check("首次提交 merged=false", body.get("merged") is False, body.get("merged"))
    check("回执不泄露治理积压（无异常总数）", "anomaliesOpen" not in body and "total" not in body,
          sorted(body.keys()))

    rows = open_rows(achievement_id)
    check("库中恰好新增 1 条 OPEN 待办", len(rows) == 1, rows)
    check("待办计数为 1 且字段为 TITLE",
          bool(rows) and rows[0]["count"] == 1 and rows[0]["field"] == "TITLE", rows)

    after = psql(
        "core_db",
        "core",
        "SELECT title || '§' || coalesce(publication_year::text, 'null') || '§' || version::text"
        " FROM core_achievement WHERE id = '%s';" % achievement_id,
    )[0]
    check("纠错未修改成果任何字段（标题/年份/版本不变）", before == after, (before, after))

    # ------------------------------------------------------------------
    # 4. 同一问题重复提交：并入既有待办
    # ------------------------------------------------------------------
    print("== 4 重复提交并入 ==")
    code, payload = report(anon, achievement_id, "TITLE", first_message)
    body = data_of(payload)
    check(
        "立即重复提交并入既有待办",
        code == 200 and body.get("merged") is True,
        (code, body.get("merged")),
    )
    # 一分钟内的同来源同内容视为一次提交的误触/重试：并入待办但不累加计数。
    # 计数是要拿去判断「有多少人认为这里错了」的，把连点两下算成两个人会直接把它变成假数字。
    check(
        "1 分钟内同来源重复不累加计数",
        body.get("reportCount") == 1,
        body.get("reportCount"),
    )
    rows = open_rows(achievement_id)
    check("仍未新增第二条待办", len(rows) == 1, rows)

    # 间隔超过抑制窗口后重复提交，才应累加计数。这一步必须等真实时间过去：
    # 把窗口改成可注入的配置只为让测试跑得快，会让「真实库里到底怎么计数」永远没人验证。
    print("   等待 61 秒以越过抑制窗口（同来源 1 分钟内重复被视为误触）…")
    time.sleep(61)
    # 这一次刻意带首尾空白：归一后与首条等价，既验证「空白差异仍并入同一条」，
    # 也验证「超过窗口才累加」。两件事一次请求，避免多消耗一份来源级配额。
    code, payload = report(anon, achievement_id, "TITLE", "  " + first_message + "  ")
    body = data_of(payload)
    check(
        "超过抑制窗口后重复提交计数递增到 2",
        code == 200 and body.get("merged") is True and body.get("reportCount") == 2,
        (code, body.get("merged"), body.get("reportCount")),
    )
    rows = open_rows(achievement_id)
    check("累计 2 次仍只有一条待办（首尾空白不同也归一）",
          len(rows) == 1 and rows[0]["count"] == 2, rows)

    # ------------------------------------------------------------------
    # 5. 不同字段是不同问题
    # ------------------------------------------------------------------
    print("== 5 不同字段各自成条 ==")
    code, payload = report(
        anon, achievement_id, "YEAR", MARK + "发表年份应为 2023 而不是 2024，来源页面可核对"
    )
    body = data_of(payload)
    check(
        "不同字段新建待办且计数为 1",
        code == 200 and body.get("merged") is False and body.get("reportCount") == 1,
        (code, body.get("merged"), body.get("reportCount")),
    )
    rows = open_rows(achievement_id)
    check("该成果下共 2 条 OPEN 待办", len(rows) == 2, rows)

    # ------------------------------------------------------------------
    # 6. 内容边界（都在限流与存在性检查之前被拒，因此不占配额、不产生待办）
    # ------------------------------------------------------------------
    print("== 6 内容边界 ==")
    code, payload = report(anon, achievement_id, "TITLE", "错了")
    check(
        "说明过短被拒（400 INVALID_ARGUMENT）",
        code == 400 and code_of(payload) == "INVALID_ARGUMENT",
        (code, code_of(payload)),
    )
    code, payload = report(anon, achievement_id, "TITEL", MARK + "这条记录的标题写错了")
    check(
        "未知字段取值被拒",
        code == 400 and code_of(payload) == "INVALID_ARGUMENT",
        (code, code_of(payload)),
    )
    code, payload = report(anon, achievement_id, "TITLE", MARK + "x" * 1100)
    check(
        "说明超长被拒",
        code == 400 and code_of(payload) == "INVALID_ARGUMENT",
        (code, code_of(payload)),
    )
    check("内容非法的请求不产生待办", len(open_rows(achievement_id)) == 2, open_rows(achievement_id))

    # ------------------------------------------------------------------
    # 7. 限流：本运行已用 5 个配额，下一个提交必须被拒
    # ------------------------------------------------------------------
    print("== 7 限流 ==")
    code, payload = report(anon, "ach_rate_probe", "TITLE", MARK + "限流探针：确认窗口边界")
    check(
        "同一来源第 6 次提交被拒（429）",
        code == 429 and code_of(payload) == "RATE_LIMITED",
        (code, code_of(payload)),
    )
    check("被拒的请求不产生待办", count_open("ach_rate_probe") == 0, count_open("ach_rate_probe"))

    # ------------------------------------------------------------------
    # 8. 管理员侧：待办计数与治理列表
    # ------------------------------------------------------------------
    print("== 8 管理员待办与治理列表 ==")
    code, payload = admin.get("/api/v1/admin/core/todos")
    todos = data_of(payload)
    db_open = count_open()
    check(
        "待办里 correctionsOpen 与库中 OPEN 数一致",
        code == 200 and todos.get("correctionsOpen") == db_open,
        (todos.get("correctionsOpen"), db_open),
    )
    check(
        "correctionsOpen 是 anomaliesOpen 的子集（不重复相加）",
        isinstance(todos.get("anomaliesOpen"), int)
        and todos["anomaliesOpen"] >= (todos.get("correctionsOpen") or 0),
        (todos.get("anomaliesOpen"), todos.get("correctionsOpen")),
    )

    code, payload = admin.get("/api/v1/admin/core/governance/anomalies?page=1&size=100")
    items = data_of(payload)
    mine = [row for row in items if row.get("kind") == KIND]
    check("治理列表能列出公众纠错待办", len(mine) > 0, len(mine))
    check("列出时带中文标签", bool(mine) and mine[0].get("kindLabel") == "公众纠错报告",
          mine[0].get("kindLabel") if mine else None)
    detail = (mine[0].get("detail") or {}) if mine else {}
    check("列表带报告原文与累计次数",
          bool(detail.get("report")) and detail.get("reportCount"), detail)
    check("摘要含字段标签与原文摘录",
          bool(mine) and str(mine[0].get("summary") or "").startswith("公众纠错："),
          mine[0].get("summary") if mine else None)
    check("摘要不外泄提交来源指纹以外的东西",
          "submitterFingerprint" in detail and not str(detail.get("submitterFingerprint")).count("."),
          detail.get("submitterFingerprint"))

    # ------------------------------------------------------------------
    # 9. 处置闭环
    # ------------------------------------------------------------------
    print("== 9 处置闭环 ==")
    if mine:
        target_id = mine[0].get("id")
        code, _ = admin.post("/api/v1/admin/core/governance/anomalies/%s/resolve" % target_id)
        db_after = count_open()
        check("标记已处理后 OPEN 数减少 1", code == 200 and db_after == db_open - 1,
              (code, db_open, db_after))
        code, _ = admin.post("/api/v1/admin/core/governance/anomalies/%s/resolve" % target_id)
        check("重复标记已处理被拒（409）", code == 409, code)
    else:
        skip("标记已处理", "治理列表中没有公众纠错待办")

    cleanup_self_test_reports(admin)
    left = count_open(achievement_id)
    check("本次产生的待办已全部关闭（不删除，台账可查）", left == 0, left)

    # ------------------------------------------------------------------
    failed = [name for name, ok, detail in RESULTS if not ok]
    print("\n%s" % ("全部通过：%d 项" % len(RESULTS) if not failed
                    else "失败 %d 项：%s" % (len(failed), failed)))
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
