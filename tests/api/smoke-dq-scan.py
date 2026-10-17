#!/usr/bin/env python3
"""数据质量巡检自检。

验证的是「巡检能重复运行且结论自洽」，而不是「库里有几条坏数据」——后者随数据变化，
把它写死成断言只会在下次采集之后误报。

检查项：
  1. 巡检需要登录（未登录 401）。
  2. 首次巡检返回 7 类检测项，每类都带标签、粒度与命中数。
  3. 立即再跑一次：不新增待办（created=0），只刷新（refreshed>0）。
  4. 异常列表里每条的 summary 都是可读的一句话，不是 "[object Object]"、也不是空串。
  5. 巡检报告里的命中数与异常列表 detail 里的计数一致（同一份事实，两个出口）。
  6. 人工处置口径：异常列表仍只列 OPEN；标记已处理后该条从列表消失（用一条自建异常验证不现实，
     因此只断言「未处理」>0 时不出现 RESOLVED 状态行）。

用法：python tests/api/smoke-dq-scan.py
环境变量：CN_CORE（默认 http://127.0.0.1:8081）、CN_USER、CN_PASS。
"""

import http.cookiejar
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

CORE = os.environ.get("CN_CORE", "http://127.0.0.1:8081")
USER = os.environ.get("CN_USER", "cnadmin")
PASSWORD = os.environ.get("CN_PASS", "cn-admin-Dev-2026")

failures = []
checks = 0


def check(name, condition, detail=""):
    global checks
    checks += 1
    if condition:
        print(f"  ok   {name}")
    else:
        failures.append(f"{name}: {detail}")
        print(f"  FAIL {name} {detail}")


class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar), urllib.request.ProxyHandler({})
        )
        self.token = ""

    def csrf(self):
        self.opener.open(f"{CORE}/api/v1/auth/csrf", timeout=10).read()
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN":
                self.token = urllib.parse.unquote(cookie.value)

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(CORE + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if self.token:
            req.add_header("X-XSRF-TOKEN", self.token)
        try:
            with self.opener.open(req, timeout=30) as resp:
                return resp.status, json.loads(resp.read().decode())
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read().decode() or "{}")

    def write(self, method, path, body=None):
        self.csrf()
        return self.call(method, path, body)

anonymous = Client()
status, _ = anonymous.call("GET", "/api/v1/admin/core/governance/anomalies")
check("未登录访问治理异常返回 401", status == 401, f"实际 {status}")

client = Client()
status, payload = client.write("POST", "/api/v1/auth/login", {"username": USER, "password": PASSWORD})
check("管理员登录成功", status == 200, f"实际 {status} {payload.get('code')}")

status, first = client.write("POST", "/api/v1/admin/core/governance/anomalies/scan")
check("巡检调用成功", status == 200 and first.get("code") == "OK", f"实际 {status} {first.get('code')}")
report = first.get("data", {})
findings = report.get("findings", [])
check("巡检返回 7 类检测项", len(findings) == 7, f"实际 {len(findings)}")
check(
    "每类检测项都带中文标签与粒度",
    all(f.get("label") and f.get("granularity") in ("RECORD", "AGGREGATE") for f in findings),
    json.dumps(findings, ensure_ascii=False)[:200],
)
check("报告带规则版本", bool(report.get("ruleVersion")), json.dumps(report, ensure_ascii=False)[:160])
check(
    "报告带年份口径上下界",
    isinstance(report.get("yearBounds"), dict) and "minYear" in report["yearBounds"],
    json.dumps(report.get("yearBounds")),
)

status, second = client.write("POST", "/api/v1/admin/core/governance/anomalies/scan")
again = second.get("data", {})
check("重复巡检验证：成功", status == 200 and second.get("code") == "OK", f"实际 {status}")
check("重复巡检不新增待办", again.get("created") == 0, f"created={again.get('created')}")
check(
    "重复巡检刷新既有待办",
    (again.get("refreshed") or 0) > 0,
    f"refreshed={again.get('refreshed')}（若首轮无任何命中则该值为 0，属正常）",
)

status, listing = client.call("GET", "/api/v1/admin/core/governance/anomalies?page=1&size=50")
rows = listing.get("data", []) if status == 200 else []
check("异常列表可读", status == 200 and isinstance(rows, list), f"实际 {status}")
check("列表非空（巡检已登记待办）", len(rows) > 0, f"实际 {len(rows)}")
check(
    "每条异常都有一句话说明",
    all(isinstance(row.get("summary"), str) and row["summary"].strip() for row in rows),
    json.dumps(rows[:1], ensure_ascii=False)[:200],
)
check(
    "说明不是把结构体直接渲染出来的占位文本",
    all("[object Object]" not in row.get("summary", "") for row in rows),
    "存在 [object Object]",
)
check(
    "每条异常都带中文种类标签",
    all(row.get("kindLabel") for row in rows),
    json.dumps([row.get("kind") for row in rows], ensure_ascii=False),
)
check(
    "列表只含未处理异常",
    all(row.get("status") == "OPEN" for row in rows),
    json.dumps([row.get("status") for row in rows], ensure_ascii=False),
)

# 同一份事实的两个出口：报告里的命中数 vs 异常 detail 里的计数。
reported = {f["kind"]: f["detected"] for f in findings}
for row in rows:
    kind = row.get("kind")
    detail = row.get("detail") or {}
    if kind in reported and isinstance(detail.get("count"), int) and reported[kind] > 0:
        check(
            f"{kind} 报告命中数与异常详情计数一致",
            detail["count"] == reported[kind],
            f"报告 {reported[kind]} / 详情 {detail['count']}",
        )

print()
print(f"共 {checks} 项，失败 {len(failures)} 项")
if failures:
    for item in failures:
        print(" - " + item)
    sys.exit(1)
print("数据质量巡检自检通过")
