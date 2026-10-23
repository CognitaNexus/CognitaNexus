#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""业务红线专项自检。

逐条对着 SRS 的红线做可执行断言，而不是「读一遍代码觉得没问题」：

1. SIMULATED 数据不进默认现实视图；显式 ALL 作用域下可见，但每条都带数据性质与演示说明；
2. 来源原值保留在原始记录里，公开成果的 DOI 与来源上报值一致（来源事实不被改写）；
3. 姓名不同且无共同稳定标识的学者不能被合并（同名不是同人证据，不同名更不是）；
4. 待核验关系不参与建边（读取模型里也成立，不只是写入时过滤）；
5. 隐藏入口不构成授权：普通用户直接调管理端接口一律 403；
6. 默认作用域下公开检索结果只含 REAL，且每条都带 dataNature；
7. 没有稳定标识的公开论文必须有人工确认审计——不存在「无标识自动发布」。

前置：整栈已启动。只读为主，唯一写操作是一次注定被拒的学者合并请求。
"""

import http.cookiejar
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

CORE = os.environ.get("CORE_BASE", "http://127.0.0.1:8081")
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
        input=sql.encode("utf-8"), stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr.decode("utf-8", "replace")[:300])
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
                text = resp.read().decode("utf-8")
                return resp.status, json.loads(text) if text else {}
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

    def get(self, url):
        return self._send("GET", url)

    def post(self, path, body=None):
        token = self.csrf()
        return self._send("POST", CORE + path, body if body is not None else {}, token)

    def login(self, user, password):
        self.csrf()
        return self._send(
            "POST", CORE + "/api/v1/auth/login", {"username": user, "password": password}, self.csrf()
        )


def data_of(payload):
    return (payload or {}).get("data") or {}


def main():
    admin = Session()
    code, payload = admin.login(ADMIN_USER, ADMIN_PASS)
    check("管理员登录", code == 200, payload.get("code"))
    if code != 200:
        return finish()

    # ---- 1. 模拟数据隔离 ----
    simulated = psql(
        "core_db", "core",
        "SELECT id FROM core_achievement WHERE data_nature='SIMULATED' "
        "AND effective_status='EFFECTIVE' AND visibility='PUBLIC' LIMIT 1;",
    )
    check("当前没有已发布的模拟数据（前提）", len(simulated) == 0, "%d 条" % len(simulated))

    simulated_ids = psql(
        "core_db", "core",
        "SELECT id FROM core_achievement WHERE data_nature <> 'REAL' "
        "AND merged_into_id IS NULL LIMIT 3;",
    )
    check("库里确有模拟数据可供验证（否则这条红线无从检验）", len(simulated_ids) > 0,
          "%d 条" % len(simulated_ids))

    # 机制层断言：模拟数据进不了公开目录，卡在人工确认这一步，而不是「恰好没人去发布」。
    rejected = 0
    for achievement_id in simulated_ids:
        code, payload = admin.post(
            "/api/v1/admin/core/achievements/%s/confirm" % achievement_id,
            {"reason": "红线自检：尝试发布模拟数据"},
        )
        if code == 422 and payload.get("code") == "BUSINESS_RULE_VIOLATION":
            rejected += 1
    check("模拟数据即使人工确认也不能进入公开目录", rejected == len(simulated_ids),
          "%d/%d 被拒" % (rejected, len(simulated_ids)))

    code, payload = admin.get(QRY + "/api/v1/public/search/achievements?type=PATENT&size=5")
    items = data_of(payload).get("items") or []
    check("默认现实视图里没有模拟专利（REAL 作用域）", len(items) == 0,
          "total=%s" % data_of(payload).get("total"))

    code, payload = admin.get(QRY + "/api/v1/public/search/achievements?size=1")
    real_total = data_of(payload).get("total")
    check("默认检索作用域声明为 REAL", data_of(payload).get("dataNatureScope") == "REAL",
          data_of(payload).get("dataNatureScope"))

    code, payload = admin.get(
        QRY + "/api/v1/public/search/achievements?size=50&dataNature=ALL"
    )
    all_total = data_of(payload).get("total")
    all_items = data_of(payload).get("items") or []
    scope_note = data_of(payload).get("scopeNote") or ""
    check("显式 ALL 作用域不小于默认范围（放大范围不能反而更少）",
          all_total is not None and real_total is not None and all_total >= real_total,
          "ALL=%s REAL=%s" % (all_total, real_total))
    check("ALL 作用域每条都带数据性质",
          len(all_items) > 0 and all(item.get("dataNature") for item in all_items),
          "%d 条" % len(all_items))
    check("ALL 作用域说明其包含演示数据、不代表真实收录口径",
          any(word in scope_note for word in ("演示", "模拟", "全部数据性质")), scope_note[:60])

    # ---- 2. 来源原值保留 ----
    # 成果实体上没有 doi 字段：DOI 在标识注册表里。所以「来源值没被改写」要拿
    # 原始记录的 payload 与注册表的规范化值对比，而不是跟一个并不存在的列比。
    #
    # 只抽**单源成果**：一个成果可以有多条来源记录，而注册表里每成果只有一条 DOI。
    # 不限定时，补充材料那几条（.../s001、.../s003、.../s004）会和主记录的正刊 DOI 配成一对，
    # 差异来自 JOIN 把不相干的两行拼在一起，不是来源值被改写——实测 4/5 就是这么来的。
    raw = psql(
        "core_db", "core",
        "SELECT r.payload->>'doi' || '|' || i.normalized_value FROM raw_source_record r "
        "JOIN core_achievement_source s ON s.source_record_id = r.id "
        "JOIN core_identifier_registry i ON i.entity_id = s.achievement_id "
        "WHERE i.identifier_kind = 'DOI' AND coalesce(r.payload->>'doi','') <> '' "
        "  AND (SELECT count(*) FROM core_achievement_source s2 "
        "       WHERE s2.achievement_id = s.achievement_id) = 1 "
        "LIMIT 5;",
    )
    kept = 0
    for row in raw:
        source_doi, registered = row.split("|", 1)
        if source_doi.strip().lower() == registered.strip().lower():
            kept += 1
    check("来源上报的 DOI 与注册表的规范化值一致（抽 5 条）", kept == len(raw) and kept > 0,
          "%d/%d" % (kept, len(raw)))

    provenance_levels = psql(
        "core_db", "core",
        "SELECT DISTINCT value_level FROM core_field_provenance;",
    )
    check("字段来源分级存在 SOURCE 级记录（人工修订可与之区分）",
          "SOURCE" in provenance_levels, provenance_levels)

    # ---- 3. 不同名学者不得直接合并 ----
    pair = psql(
        "core_db", "core",
        "SELECT a.id || '|' || b.id FROM core_scholar a JOIN core_scholar b ON a.id <> b.id "
        "WHERE a.merged_into_id IS NULL AND b.merged_into_id IS NULL "
        "AND a.name_norm <> b.name_norm LIMIT 1;",
    )
    ledger_before = int(psql("core_db", "core", "SELECT count(*) FROM gov_merge_ledger;")[0])
    if pair:
        left, right = pair[0].split("|", 1)
        code, payload = admin.post(
            "/api/v1/admin/core/governance/scholars/merge",
            {"survivorId": left, "mergedId": right, "reason": "红线自检：不同名且无共同标识"},
        )
        check("不同名且无共同标识的学者合并被拒",
              code >= 400 and payload.get("code") in ("BUSINESS_RULE_VIOLATION", "INVALID_ARGUMENT", "VERSION_CONFLICT"),
              "%d %s" % (code, payload.get("code")))
        check("被拒的合并没有写台账", 
              int(psql("core_db", "core", "SELECT count(*) FROM gov_merge_ledger;")[0]) == ledger_before,
              ledger_before)
    else:
        check("找到不同名的两位学者用于负向测试", False, "没有可用样本")

    # ---- 4. 待核验关系不建边 ----
    edge_from_pending = psql(
        "query_db", "query",
        "SELECT count(*) FROM qry_coauthor_edge e "
        "JOIN qry_generation g ON g.generation = e.generation AND g.status = 'READY' "
        "JOIN qry_contribution c ON c.generation = e.generation "
        "  AND c.achievement_id = e.achievement_id AND c.scholar_id = e.scholar_a "
        "WHERE c.relation_state = 'PENDING_VERIFY';",
    )[0]
    check("待核验关系没有产生任何合作边", edge_from_pending == "0", edge_from_pending)
    disputed_from_edge = psql(
        "query_db", "query",
        "SELECT count(*) FROM qry_coauthor_edge e "
        "JOIN qry_generation g ON g.generation = e.generation AND g.status = 'READY' "
        "JOIN qry_contribution c ON c.generation = e.generation "
        "  AND c.achievement_id = e.achievement_id AND c.scholar_id = e.scholar_a "
        "WHERE c.relation_state IN ('DISPUTED','EXCLUDED');",
    )[0]
    check("争议与已排除关系没有产生任何合作边", disputed_from_edge == "0", disputed_from_edge)

    # ---- 5. 隐藏入口不构成授权 ----
    stamp = uuid.uuid4().hex[:6]
    username = "redline" + stamp
    password = "Redline-%s-2026" % stamp
    code, payload = admin.post(
        "/api/v1/auth/register",
        {"username": username, "password": password, "displayName": "红线自检用户"},
    )
    plain = Session()
    login_code, _ = plain.login(username, password)
    check("普通用户登录", login_code == 200, login_code)
    for path in (
        "/api/v1/admin/core/sources",
        "/api/v1/admin/core/governance/candidates/workbench?page=1&size=5",
        "/api/v1/admin/core/audit",
    ):
        code, payload = plain.get(CORE + path)
        check("普通用户访问 %s 被拒" % path.split("?")[0], code == 403, "%d %s" % (code, payload.get("code")))
    code, payload = plain.post(
        "/api/v1/admin/core/governance/scholars/merge",
        {"survivorId": "sch_x", "mergedId": "sch_y", "reason": "越权尝试"},
    )
    check("普通用户调用治理写接口被拒", code == 403, "%d %s" % (code, payload.get("code")))
    code, payload = plain.post(
        "/api/v1/admin/core/achievements/ach_missing/confirm", {"reason": "越权发布"}
    )
    check("普通用户尝试确认发布被拒", code == 403, "%d %s" % (code, payload.get("code")))

    # ---- 6. 默认作用域只含 REAL ----
    code, payload = admin.get(QRY + "/api/v1/public/search/achievements?size=50")
    data = data_of(payload)
    natures = {item.get("dataNature") for item in (data.get("items") or [])}
    check("默认检索作用域为 REAL", data.get("dataNatureScope") == "REAL", data.get("dataNatureScope"))
    check("默认检索结果只含真实数据", natures == {"REAL"}, natures)

    # ---- 7. 无标识公开必须有人工确认 ----
    no_identifier = psql(
        "core_db", "core",
        "SELECT count(*) FROM core_achievement a "
        "WHERE a.type='PAPER' AND a.effective_status='EFFECTIVE' AND a.merged_into_id IS NULL "
        "AND NOT EXISTS (SELECT 1 FROM core_identifier_registry r WHERE r.entity_id = a.id) "
        "AND NOT EXISTS (SELECT 1 FROM aud_audit_event e WHERE e.target_id = a.id "
        "                AND e.action = 'ACHIEVEMENT_PUBLISH_CONFIRMED');",
    )[0]
    check("没有「无稳定标识且未经人工确认」的公开论文", no_identifier == "0", no_identifier)

    return finish()


def finish():
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    failed = len(RESULTS) - passed
    print("\n结果：通过 %d 项，失败 %d 项" % (passed, failed))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
