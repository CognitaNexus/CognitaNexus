#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""本轮补齐项的接口级自检：关键词热点（FR13）、统一待办（FR14）、成果详情来源清单（FR02）、
采集任务停止与重试（FR08）。

为什么单独一个脚本而不是塞进 smoke-core / smoke-query：
这几个端点分属三个服务、覆盖三条互不相同的要求，混进现有脚本会让人以为「跑过 smoke-core 就等于
验收了 FR14」。单独成文件，失败时也更容易指到是哪一项。

检查的重点不是「接口返回 200」，而是那些**看错也不会报错**的口径：

1. 热点：分母是窗口内全部公开论文（改了分母数字依然好看）；排序必须单调不增（否则界面看起来
   有榜、读起来是乱序）；年份窗非法必须 400 而不是静默当全时段；limit 必须被夹住。
2. 待办：`null` 与 `0` 的区别（无权限 vs 今天没事）；`todoTotal` 必须等于各项之和，不能是另一套算法。
3. 来源清单：外链要么是绝对 URL，要么字段根本不出现；`sourceCount` 必须等于列表长度。
4. 停止/重试：端点确实挂载、错误语义是 404 而不是 500。

用法：python tests/api/smoke-fr-additions.py
前置：整栈已启动（bash ops/local/stack.sh）。
"""

import http.cookiejar
import json
import os
import urllib.error
import urllib.parse
import urllib.request

CORE = os.environ.get("CORE_BASE", "http://127.0.0.1:8081")
ING = os.environ.get("ING_BASE", "http://127.0.0.1:8082")
QRY = os.environ.get("QRY_BASE", "http://127.0.0.1:8083")

ADMIN_USER = os.environ.get("CN_BOOTSTRAP_ADMIN_USERNAME", "cnadmin")
ADMIN_PASS = os.environ.get("CN_BOOTSTRAP_ADMIN_PASSWORD", "cn-admin-Dev-2026")

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok)))
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  -> " + str(detail)) if detail else ""))


def finish():
    passed = sum(1 for _, ok in RESULTS if ok)
    failed = len(RESULTS) - passed
    print("\n== 结果 ==")
    print("  通过 %d，失败 %d" % (passed, failed))
    return 0 if failed == 0 else 1


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
            text = err.read().decode("utf-8", "replace")
            try:
                return err.code, json.loads(text) if text else {}
            except ValueError:
                return err.code, {"raw": text}

    def csrf(self):
        self._send("GET", CORE + "/api/v1/auth/csrf")
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN":
                return urllib.parse.unquote(cookie.value)
        return ""

    def get(self, base, path):
        return self._send("GET", base + path)

    def post(self, base, path, body=None):
        token = self.csrf()
        return self._send("POST", base + path, body if body is not None else {}, token)

    def login(self, user, password):
        self.csrf()
        return self._send(
            "POST", CORE + "/api/v1/auth/login", {"username": user, "password": password}, self.csrf()
        )


def data_of(payload):
    return (payload or {}).get("data") or {}


def main():
    # ---------------------------------------------------------------- 1. 关键词热点
    print("\n== 1. 关键词热点（FR13，query）==")
    code, payload = Session().get(QRY, "/api/v1/public/analytics/keywords")
    check("默认请求 200", code == 200, "%d %s" % (code, payload.get("code")))
    data = data_of(payload)
    check("带回规则版本 KEYWORD_HOTSPOT_V1",
          data.get("ruleVersion") == "KEYWORD_HOTSPOT_V1", data.get("ruleVersion"))
    check("带回口径（scope / countingRules / window）",
          isinstance(data.get("scope"), dict)
          and isinstance(data.get("countingRules"), dict)
          and isinstance(data.get("window"), dict),
          list(data.keys())[:8])

    sample = data.get("sampleSize")
    check("sampleSize 是数字（占比的分母）", isinstance(sample, int) and sample >= 0, sample)
    items = data.get("items") or []
    check("items 是列表", isinstance(items, list), type(items).__name__)

    if items:
        counts = [it.get("achievements") or 0 for it in items]
        check("按论文数降序（界面上的「榜」必须真的是榜）",
              all(counts[i] >= counts[i + 1] for i in range(len(counts) - 1)), counts[:6])
        first = items[0]
        check("每项带 topicId 与 canonicalNameKnown 布尔",
              bool(first.get("topicId")) and isinstance(first.get("canonicalNameKnown"), bool),
              {k: first.get(k) for k in ("topicId", "canonicalNameKnown")})
        share = first.get("shareOfSample")
        expected = None if not sample else round(counts[0] * 100.0 / sample, 1)
        check("占比 = 该项论文数 / 窗口内论文数", share == expected, "%s vs %s" % (share, expected))
        print("         样本 %d 篇，%d 个领域有标注，首位 %s：%s%%"
              % (sample, len(items), counts[0], share))

    # 非法年份窗必须 400，不能静默当全时段
    code, payload = Session().get(
        QRY, "/api/v1/public/analytics/keywords?yearFrom=2024&yearTo=2020")
    check("起始年晚于结束年 → 400 INVALID_ARGUMENT",
          code == 400 and payload.get("code") == "INVALID_ARGUMENT",
          "%d %s" % (code, payload.get("code")))

    code, payload = Session().get(
        QRY, "/api/v1/public/analytics/keywords?yearFrom=1200")
    check("年份越界 → 400", code == 400 and payload.get("code") == "INVALID_ARGUMENT", code)

    # 年份窗收窄后样本不能变大：变大说明年份条件没生效
    code, payload = Session().get(
        QRY, "/api/v1/public/analytics/keywords?yearFrom=2023&yearTo=2025")
    narrowed = data_of(payload).get("sampleSize")
    check("年份窗收窄后样本数不增加（条件确实生效）",
          isinstance(narrowed, int) and narrowed <= (sample or 0), "%s <= %s" % (narrowed, sample))

    code, payload = Session().get(QRY, "/api/v1/public/analytics/keywords?limit=1000")
    big = data_of(payload).get("items") or []
    check("limit 被夹到 50 以内（否则这个端点等于导出接口）", len(big) <= 50, len(big))

    # ---------------------------------------------------------------- 2. 统一待办
    print("\n== 2. 统一待办（FR14，core）==")
    anon = Session()
    code, payload = anon.get(CORE, "/api/v1/admin/core/todos")
    check("未登录 → 401", code == 401, "%d %s" % (code, payload.get("code")))

    admin = Session()
    code, payload = admin.login(ADMIN_USER, ADMIN_PASS)
    check("平台管理员登录", code == 200 and payload.get("code") == "OK", payload.get("message"))
    if code == 200:
        code, payload = admin.get(CORE, "/api/v1/admin/core/todos")
        todos = data_of(payload)
        check("管理员取待办 200", code == 200, "%d %s" % (code, payload.get("code")))
        check("带回认领与门户变更计数（不是 null）",
              todos.get("claimsPending") is not None and todos.get("portalChangesPending") is not None,
              {"claims": todos.get("claimsPending"), "changes": todos.get("portalChangesPending")})

        parts = [todos.get("achievementsPending"), todos.get("dedupCandidatesOpen"),
                 todos.get("anomaliesOpen"), todos.get("claimsPending"),
                 todos.get("portalChangesPending")]
        expected_total = sum(int(p or 0) for p in parts)
        check("todoTotal = 各项之和（不是另一套算法）",
              int(todos.get("todoTotal") or 0) == expected_total,
              "%s vs %s" % (todos.get("todoTotal"), expected_total))
        check("带回口径说明（说明什么算待办、null 是什么）",
              "需要人做决定" in (todos.get("todoNote") or ""), (todos.get("todoNote") or "")[:24])
        # 采集失败与投递积压不属于待办，只用于页面上的运行状态链接
        overview_keys = set(todos.keys())
        check("同时带回运行状态所需字段（delivery 等）",
              "delivery" in overview_keys or "catalogRevision" in overview_keys,
              sorted(overview_keys)[:8])
        print("         待办合计 %s（待治理 %s / 候选 %s / 异常 %s / 认领 %s / 变更 %s）"
              % (todos.get("todoTotal"), todos.get("achievementsPending"),
                 todos.get("dedupCandidatesOpen"), todos.get("anomaliesOpen"),
                 todos.get("claimsPending"), todos.get("portalChangesPending")))

        # 普通用户没有治理能力：必须 403，而不是拿到一份空待办（0 与 null 的区别在服务层）
        normal = Session()
        sys_user = os.environ.get("CN_SMOKE_USER", "smoke_r3_user")
        sys_pass = os.environ.get("CN_SMOKE_PASS", "Smoke-Dev-2026")
        normal.csrf()
        normal._send("POST", CORE + "/api/v1/auth/register",
                     {"username": sys_user, "password": sys_pass, "displayName": "自检普通用户"},
                     normal.csrf())
        code, payload = normal.login(sys_user, sys_pass)
        if code == 200:
            code, payload = normal.get(CORE, "/api/v1/admin/core/todos")
            check("普通账号取待办 → 403（能力校验在应用层）",
                  code == 403, "%d %s" % (code, payload.get("code")))
        else:
            print("  SKIP  普通账号登录失败（%s），跳过越权检查" % payload.get("code"))

    # ---------------------------------------------------------------- 3. 详情来源清单
    print("\n== 3. 成果详情来源清单（FR02，core）==")
    code, payload = Session().get(QRY, "/api/v1/public/search/achievements?size=1")
    hits = data_of(payload).get("items") or data_of(payload).get("content") or []
    if code == 200 and hits:
        ach_id = hits[0].get("achievementId") or hits[0].get("id")
        code, payload = Session().get(CORE, "/api/v1/public/achievements/" + str(ach_id))
        detail = data_of(payload)
        check("详情 200", code == 200, code)
        check("详情带 sources 与 sourceCount",
              isinstance(detail.get("sources"), list) and isinstance(detail.get("sourceCount"), int),
              {"type": type(detail.get("sources")).__name__, "count": detail.get("sourceCount")})
        sources = detail.get("sources") or []
        check("sourceCount 等于列表长度", detail.get("sourceCount") == len(sources),
              "%s vs %s" % (detail.get("sourceCount"), len(sources)))
        bad_urls = [s.get("externalUrl") for s in sources
                    if s.get("externalUrl") is not None
                    and not str(s.get("externalUrl")).startswith(("http://", "https://"))]
        check("外链要么是绝对 URL，要么字段不出现（不拼一个会 404 的地址）", not bad_urls, bad_urls)
        blank = [s.get("externalUrl") for s in sources if s.get("externalUrl") == ""]
        check("外链不出现空串（空串会被渲染成可点但无效的链接）", not blank, blank)
        print("         成果 %s 有 %d 处来源，%d 处带原站外链"
              % (ach_id, len(sources), sum(1 for s in sources if s.get("externalUrl"))))
    else:
        check("取到至少一条公开成果用于详情检查", False, code)

    # ---------------------------------------------------------------- 4. 停止 / 重试
    print("\n== 4. 采集任务停止与重试（FR08，ingestion）==")
    admin2 = Session()
    admin2.login(ADMIN_USER, ADMIN_PASS)
    code, payload = admin2.post(ING, "/api/v1/admin/ingestion/jobs/ing_not_exist/stop")
    check("停止不存在的任务 → 404（端点已挂载且语义正确）",
          code == 404 and payload.get("code") == "NOT_FOUND", "%d %s" % (code, payload.get("code")))
    code, payload = admin2.post(ING, "/api/v1/admin/ingestion/jobs/ing_not_exist/retry")
    check("重试不存在的任务 → 404", code == 404 and payload.get("code") == "NOT_FOUND",
          "%d %s" % (code, payload.get("code")))

    return finish()


if __name__ == "__main__":
    raise SystemExit(main())
