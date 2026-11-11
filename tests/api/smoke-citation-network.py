#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""引用网络的端到端自检：来源参考文献 → 边 → 公开页面口径。

每条断言对着一条产品规则，不是「接口返回 200」：

1. **页面上的条目与库里同一口径**：公开接口给出的引用数与数据库按「可见成果」过滤后数出来的
   完全相同；只改一边（例如 SQL 不过滤可见性）会让页面多数出几条，而数字看着仍然正常；
2. **站外引用只计数、不建边**：EXTERNAL 行的标识一个都不能出现在公开明细里；
3. **被引方向独立核对**：谁引用了本篇，与被引数必须与库里的反向行一致；
4. **缺失不是 0**：没采过的成果给 outcome=NONE 并明说「未采集不等于没有引用」；
   来源查不到的给 NOT_FOUND 并明说「取不到」，两者都不能显示成「没有被引用」；
5. **覆盖率可复算**：observedWorks / publicPapers / coveragePercent 与库内计数逐项相等；
6. **重采是整体替换**：同一篇再补一次，条目数不增长（先删后插），不产生重复边；
7. **权限分离**：普通用户读不到采集统计、也不能触发补水（这会消耗来源配额）；
8. **不动红线**：响应里没有 h-index / 影响因子这类平台刻意不做的字段。

前置：整栈已启动且已补过至少一篇参考文献（bash ops/local/stack.sh + 管理端触发一次补水）。
副作用：会重新补一篇的参考文献（消耗 1 次 Crossref 请求）、创建 1 个只读测试账号。

用法：python tests/api/smoke-citation-network.py
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
ING = os.environ.get("ING_BASE", "http://127.0.0.1:8082")
ADMIN_USER = os.environ.get("ADMIN_USER", "cnadmin")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "cn-admin-Dev-2026")
PSQL = os.environ.get("PSQL_BIN", "D:/dev-tools/pgsql/bin/psql.exe")

RESULTS = []

PUBLIC_TARGET = (
    "a.visibility='PUBLIC' AND a.effective_status='EFFECTIVE' AND a.merged_into_id IS NULL"
)


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  -> " + str(detail)) if detail else ""))


def psql(sql):
    env = dict(os.environ)
    env["PGPASSWORD"] = "cn_core_app_dev"
    env["PGCLIENTENCODING"] = "UTF8"
    env["MSYS_NO_PATHCONV"] = "1"
    out = subprocess.run(
        [PSQL, "-h", "127.0.0.1", "-p", "5432", "-U", "core_app", "-d", "core_db", "-At", "-F", "\t", "-f", "-"],
        input=sql.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr.decode("utf-8", "replace")[:300])
    return [line for line in out.stdout.decode("utf-8", "replace").splitlines() if line.strip()]


def rows(sql):
    return [line.split("\t") for line in psql(sql)]


def one(sql):
    result = rows(sql)
    return result[0] if result else None


class Session:
    """带 Cookie 与 CSRF 的会话（每个脚本各自一份，避免互相依赖）。"""

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
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=600) as response:
                return response.status, json.loads(response.read().decode("utf-8") or "{}")
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

    def get(self, path):
        return self._send("GET", CORE + path)

    def post(self, path, body=None):
        return self._send("POST", CORE + path, body if body is not None else {}, self.csrf())

    def login(self, user, password):
        self.csrf()
        return self._send(
            "POST", CORE + "/api/v1/auth/login", {"username": user, "password": password}, self.csrf()
        )


def data_of(payload):
    return (payload or {}).get("data") or {}


def public_network(achievement_id):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    url = CORE + "/api/v1/public/achievements/" + urllib.parse.quote(achievement_id) + "/citation-network"
    try:
        with opener.open(urllib.request.Request(url, headers={"Accept": "application/json"}), timeout=60) as response:
            return response.status, json.loads(response.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as err:
        text = err.read().decode("utf-8", "replace")
        try:
            return err.code, json.loads(text) if text else {}
        except ValueError:
            return err.code, {"raw": text}


def main():
    session = Session()
    status, _ = session.login(ADMIN_USER, ADMIN_PASS)
    if status != 200:
        print("管理员登录失败，无法继续")
        return 1

    # ------------------------------------------------------------------
    # 0. 前置：库里确实有数据
    # ------------------------------------------------------------------
    # 样例取「站内边最多」的那一篇：参考文献最多的那一篇往往一条站内边都没有，
    # 拿它验证明细只能得到 0 vs 0，等于什么都没验。
    observed = rows(
        "SELECT o.achievement_id, o.references_listed, coalesce(o.references_total::text,''), o.provider"
        " FROM core_citation_observation o"
        " JOIN core_citation_reference r ON r.citing_achievement_id = o.achievement_id"
        "  AND r.resolution='RESOLVED' AND r.cited_achievement_id <> r.citing_achievement_id"
        " WHERE o.outcome='OBSERVED' AND o.references_listed > 0"
        " GROUP BY o.achievement_id, o.references_listed, o.references_total, o.provider"
        " ORDER BY count(*) DESC LIMIT 1;"
    )
    if not observed:
        print("库里没有任何参考文献观测：请先在管理端触发一次补水（/governance/citations）")
        return 1
    citing_id, listed, total, provider = observed[0]
    listed = int(listed)

    # ------------------------------------------------------------------
    # 1. 公开明细与库内口径一致
    # ------------------------------------------------------------------
    status, body = public_network(citing_id)
    view = data_of(body)
    check("公开引用网络可读", status == 200 and view.get("achievementId") == citing_id, status)

    coverage = view.get("coverage") or {}
    check(
        "口径行与库内观测一致",
        coverage.get("outcome") == "OBSERVED"
        and coverage.get("referencesListed") == listed
        and coverage.get("provider") == provider,
        "listed=%s provider=%s" % (coverage.get("referencesListed"), coverage.get("provider")),
    )
    if total != "":
        check("来源声明的总数与库内一致", coverage.get("referencesTotal") == int(total), total)
    else:
        check("来源未声明总数时字段为 null（不是 0）", coverage.get("referencesTotal") is None)

    db_resolved = int(
        one(
            "SELECT count(*) FROM core_citation_reference r"
            " JOIN core_achievement a ON a.id = r.cited_achievement_id"
            " WHERE r.citing_achievement_id='%s' AND r.resolution='RESOLVED'"
            " AND r.cited_achievement_id <> r.citing_achievement_id AND %s;" % (citing_id, PUBLIC_TARGET)
        )[0]
    )
    items = (view.get("references") or {}).get("items") or []
    check("引用明细条数等于库内可见站内边", len(items) == db_resolved, "%s vs %s" % (len(items), db_resolved))
    check("明细条数与 references.count 一致", (view.get("references") or {}).get("count") == len(items))

    db_ids = {
        row[0]
        for row in rows(
            "SELECT r.cited_achievement_id FROM core_citation_reference r"
            " JOIN core_achievement a ON a.id = r.cited_achievement_id"
            " WHERE r.citing_achievement_id='%s' AND r.resolution='RESOLVED'"
            " AND r.cited_achievement_id <> r.citing_achievement_id AND %s;" % (citing_id, PUBLIC_TARGET)
        )
    }
    check("明细里的 id 全部来自库内已解析行", {item["id"] for item in items} == db_ids)

    # ------------------------------------------------------------------
    # 2. 站外引用只计数
    # ------------------------------------------------------------------
    db_external = int(
        one(
            "SELECT count(*) FROM core_citation_reference"
            " WHERE citing_achievement_id='%s' AND resolution='EXTERNAL';" % citing_id
        )[0]
    )
    check("站外引用计数与库内一致", coverage.get("external") == db_external, coverage.get("external"))
    external_keys = {
        row[0]
        for row in rows(
            "SELECT cited_value_norm FROM core_citation_reference"
            " WHERE citing_achievement_id='%s' AND resolution='EXTERNAL';" % citing_id
        )
    }
    leaked = external_keys & {str(item.get("doi") or "").lower() for item in items}
    check("站外标识不出现在公开明细里", not leaked, list(leaked)[:3])

    identified = (coverage.get("resolvedInPlatform") or 0) + (coverage.get("external") or 0) + (
        coverage.get("selfCitations") or 0
    )
    check(
        "无标识条目单列（拿到的条数 − 可建边的条数）",
        coverage.get("unidentified") == max(listed - identified, 0),
        "%s - %s" % (listed, identified),
    )

    # ------------------------------------------------------------------
    # 3. 被引方向
    # ------------------------------------------------------------------
    cited = rows(
        "SELECT r.cited_achievement_id, count(*) FROM core_citation_reference r"
        " JOIN core_achievement a ON a.id = r.cited_achievement_id"
        " WHERE r.resolution='RESOLVED' AND r.cited_achievement_id <> r.citing_achievement_id"
        " AND %s GROUP BY 1 ORDER BY 2 DESC LIMIT 1;" % PUBLIC_TARGET
    )
    if cited:
        target_id, target_count = cited[0]
        status, body = public_network(target_id)
        cited_view = data_of(body)
        cited_by = cited_view.get("citedBy") or {}
        check(
            "被引条数等于库内反向行数",
            cited_by.get("count") == int(target_count),
            "%s vs %s" % (cited_by.get("count"), target_count),
        )
        db_citers = {
            row[0]
            for row in rows(
                "SELECT r.citing_achievement_id FROM core_citation_reference r"
                " JOIN core_achievement a ON a.id = r.citing_achievement_id"
                " WHERE r.cited_achievement_id='%s' AND r.resolution='RESOLVED'"
                " AND r.citing_achievement_id <> r.cited_achievement_id AND %s;"
                % (target_id, PUBLIC_TARGET)
            )
        }
        check(
            "被引明细的引用方集合与库内一致",
            {item["id"] for item in (cited_by.get("items") or [])} == db_citers,
            "api=%s db=%s" % (len(cited_by.get("items") or []), len(db_citers)),
        )
        check(
            "被引说明写明「只统计已采集成果」",
            "已采集" in (cited_by.get("note") or ""),
            cited_by.get("note"),
        )
    else:
        check("存在被引样例（库里至少有一条站内边）", False, "没有任何站内边，无法验证被引方向")

    # ------------------------------------------------------------------
    # 4. 缺失不是 0
    # ------------------------------------------------------------------
    unobserved = one(
        "SELECT a.id FROM core_achievement a JOIN core_paper p ON p.achievement_id = a.id"
        " LEFT JOIN core_citation_observation o ON o.achievement_id = a.id"
        " WHERE o.achievement_id IS NULL AND p.doi_norm IS NOT NULL AND %s LIMIT 1;" % PUBLIC_TARGET
    )
    if unobserved:
        status, body = public_network(unobserved[0])
        view = data_of(body)
        cov = view.get("coverage") or {}
        note = (view.get("references") or {}).get("note") or ""
        check(
            "未采集：outcome=NONE 且不显示条目",
            cov.get("outcome") == "NONE" and ((view.get("references") or {}).get("count") or 0) == 0,
            cov.get("outcome"),
        )
        check("未采集：说明写明「不表示没有引用」", "不表示本篇没有引用任何文献" in note, note[:60])
    else:
        check("存在未采集样例", False, "全部可见论文都已采集，无法验证未采集口径")

    not_found = one(
        "SELECT achievement_id FROM core_citation_observation WHERE outcome='NOT_FOUND' LIMIT 1;"
    )
    if not_found:
        status, body = public_network(not_found[0])
        view = data_of(body)
        cov = view.get("coverage") or {}
        note = (view.get("references") or {}).get("note") or ""
        check("来源查不到：outcome=NOT_FOUND", cov.get("outcome") == "NOT_FOUND", cov.get("outcome"))
        check("来源查不到：说明写明「取不到」而不是「没有」", "取不到" in note, note[:60])
    else:
        print("  SKIP  库里没有 NOT_FOUND 观测，跳过该组断言")

    # 不可见成果
    hidden = one(
        "SELECT id FROM core_achievement WHERE NOT (%s) LIMIT 1;" % PUBLIC_TARGET
    )
    if hidden:
        status, _ = public_network(hidden[0])
        check("不可见成果的引用网络返回 404", status == 404, status)

    # ------------------------------------------------------------------
    # 5. 覆盖率可复算
    # ------------------------------------------------------------------
    stats_status, stats_body = session.get("/api/v1/admin/core/citations/stats")
    stats = data_of(stats_body)
    db_observed = int(one("SELECT count(*) FROM core_citation_observation WHERE outcome='OBSERVED';")[0])
    db_papers = int(
        one("SELECT count(*) FROM core_achievement a WHERE a.type='PAPER' AND %s;" % PUBLIC_TARGET)[0]
    )
    check("统计接口可读", stats_status == 200, stats_status)
    check("已采集篇数与库内一致", stats.get("observedWorks") == db_observed, stats.get("observedWorks"))
    check("可见论文数与库内一致", stats.get("publicPapers") == db_papers, stats.get("publicPapers"))
    if db_papers:
        expected = round(db_observed * 100.0 / db_papers, 2)
        check("覆盖率可复算", abs((stats.get("coveragePercent") or 0) - expected) < 0.01, expected)

    platform = (data_of(public_network(citing_id)[1]).get("platform") or {})
    check(
        "公开页的覆盖率与统计接口一致",
        platform.get("observedWorks") == stats.get("observedWorks")
        and platform.get("publicPapers") == stats.get("publicPapers"),
    )
    check("公开页写明「缺边是覆盖率问题」", "覆盖率" in (platform.get("note") or ""))

    # ------------------------------------------------------------------
    # 6. 重采是整体替换
    # ------------------------------------------------------------------
    before = int(
        one(
            "SELECT count(*) FROM core_citation_reference WHERE citing_achievement_id='%s';" % citing_id
        )[0]
    )
    doi = one(
        "SELECT doi FROM core_paper WHERE achievement_id='%s';" % citing_id
    )
    if doi and doi[0]:
        status, body = session._send(
            "POST",
            ING + "/api/v1/admin/ingestion/citations/hydrate-dois?provider=CROSSREF",
            [doi[0]],
            session.csrf(),
        )
        report = data_of(body)
        check("定向补水可执行", status == 200 and report.get("worksMatched") == 1, status)
        after = int(
            one(
                "SELECT count(*) FROM core_citation_reference WHERE citing_achievement_id='%s';" % citing_id
            )[0]
        )
        check(
            "重采不累加条目（先删后插）",
            after == before,
            "before=%s after=%s" % (before, after),
        )
    else:
        check("样例成果有 DOI 可定向补水", False)

    # ------------------------------------------------------------------
    # 7. 权限分离
    # ------------------------------------------------------------------
    stamp = str(int(time.time()))
    username = "cit%s" % stamp
    password = "Cit-%s-2026" % stamp
    register_status, _ = session.post(
        "/api/v1/auth/register",
        {
            "username": username,
            "password": password,
            "displayName": "引用网络自检只读账号",
            "email": "%s@example.com" % username,
        },
    )
    if register_status in (200, 201):
        reader = Session()
        reader.login(username, password)
        reader_status, _ = reader.get("/api/v1/admin/core/citations/stats")
        check("普通用户读不到引用网络统计", reader_status == 403, reader_status)
        hydrate_status, _ = reader._send(
            "POST", ING + "/api/v1/admin/ingestion/citations/hydrate?limit=1", {}, reader.csrf()
        )
        check("普通用户不能触发补水", hydrate_status == 403, hydrate_status)
    else:
        print("  SKIP  注册测试账号失败（%s），跳过权限断言" % register_status)

    # ------------------------------------------------------------------
    # 8. 红线：不做 h-index / 影响因子
    # ------------------------------------------------------------------
    keys = json.dumps(view, ensure_ascii=False).lower()
    check("响应不含 h-index", "h-index" not in keys and "hindex" not in keys)
    check("响应不含影响因子", "impact factor" not in keys and "impactfactor" not in keys)

    # ------------------------------------------------------------------
    failed = [name for name, ok, _ in RESULTS if not ok]
    print("\n合计 %d 项，失败 %d 项" % (len(RESULTS), len(failed)))
    if failed:
        for name in failed:
            print("  FAILED: " + name)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
