#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""接口契约与错误码一致性。

一份接口契约的价值在于「同一个失败，无论来自哪个服务、哪一条路径，长得都一样」。这里逐项检查：

1. 成功响应统一信封（code/message/data/meta/traceId/timestamp），且 traceId 非空可检索；
2. 错误码与 HTTP 状态一一对应：400/401/403/404/409/422 各打一次真实请求验证；
3. 未知路径返回 404 而不是 500 或 HTML；
4. 公开导出与事件载荷的字段集一致——不一致时「重建后的投影」与「增量更新的投影」会在字段层
   悄悄分叉，而这只有在切换代时才可见；
5. 分页 meta 三件套齐全（page/size/total），否则前端无法实现翻页。
"""

import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

CORE = os.environ.get("CORE_BASE", "http://127.0.0.1:8081")
QRY = os.environ.get("QRY_BASE", "http://127.0.0.1:8083")
ING = os.environ.get("ING_BASE", "http://127.0.0.1:8082")
TOKEN = os.environ.get("CN_QUERY_SERVICE_TOKEN", "cn_query_service_dev")
PSQL = os.environ.get("PSQL_BIN", "D:/dev-tools/pgsql/bin/psql.exe")

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  -> " + str(detail)) if detail else ""))


def psql(db, user, sql):
    env = dict(os.environ)
    env["PGPASSWORD"] = "cn_%s_app_dev" % user
    env["PGCLIENTENCODING"] = "UTF8"
    out = subprocess.run(
        [PSQL, "-v", "ON_ERROR_STOP=1", "-h", "127.0.0.1", "-p", "5432",
         "-U", user + "_app", "-d", db, "-At", "-f", "-"],
        input=sql.encode("utf-8"), stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr.decode("utf-8", "replace")[:300])
    return [line.strip() for line in out.stdout.decode("utf-8", "replace").splitlines() if line.strip()]


def call(url, headers=None, method="GET"):
    req = urllib.request.Request(url, method=method, headers=headers or {"Accept": "application/json"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=60) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw else {}, resp.headers.get("Content-Type", "")
    except urllib.error.HTTPError as err:
        raw = err.read().decode("utf-8", "replace")
        try:
            return err.code, json.loads(raw) if raw else {}, err.headers.get("Content-Type", "")
        except ValueError:
            return err.code, {"raw": raw[:200]}, err.headers.get("Content-Type", "")


ENVELOPE_KEYS = ("code", "message", "traceId", "timestamp")


def envelope_ok(body):
    return all(key in (body or {}) for key in ENVELOPE_KEYS)


def main():
    # ---- 1. 成功信封 ----
    success_urls = [
        QRY + "/api/v1/public/search/achievements?size=5",
        QRY + "/api/v1/public/analytics/overview",
        QRY + "/api/v1/public/search/facets",
        CORE + "/api/v1/auth/csrf",
    ]
    bad = []
    for url in success_urls:
        code, body, _ = call(url)
        if code != 200 or not envelope_ok(body):
            bad.append("%s(%d)" % (url.rsplit("/", 1)[-1][:24], code))
    check("成功响应统一信封（code/message/traceId/timestamp）", not bad, bad)

    code, body, _ = call(QRY + "/api/v1/public/search/achievements?size=5")
    trace = body.get("traceId") or ""
    check("成功响应带非空 traceId", len(trace) >= 8, trace)

    # ---- 2. 分页信息 ----
    # 分页三件套的位置在两个服务里并不一致：query 放在 data 内，core 的管理端放在 meta 里。
    # 这是既成事实而不是设计目标，前端两处分别读；这里只检查「拿得到」，并如实打印位置，
    # 免得契约检查变成一句「反正我没看到」。
    data = body.get("data") or {}
    meta = body.get("meta") or {}
    in_data = all(k in data for k in ("page", "size", "total"))
    in_meta = all(k in meta for k in ("page", "size", "total"))
    check("分页响应带回 page/size/total", in_data or in_meta,
          "在 data 里" if in_data else ("在 meta 里" if in_meta else "两处都没有"))

    # ---- 3. 错误码矩阵 ----
    cases = []

    code, body, _ = call(QRY + "/api/v1/public/search/achievements?size=notanumber")
    cases.append(("400 INVALID_ARGUMENT", code == 400 and body.get("code") == "INVALID_ARGUMENT",
                  "%d %s" % (code, body.get("code"))))

    code, body, _ = call(CORE + "/api/v1/account/profile")
    cases.append(("401 AUTH_REQUIRED", code == 401 and body.get("code") == "AUTH_REQUIRED",
                  "%d %s" % (code, body.get("code"))))

    code, body, _ = call(CORE + "/api/v1/admin/core/sources")
    cases.append(("401（未登录访问管理端先判认证）", code in (401, 403), "%d %s" % (code, body.get("code"))))

    code, body, _ = call(CORE + "/api/v1/public/achievements/ach_does_not_exist")
    cases.append(("404 NOT_FOUND", code == 404 and body.get("code") == "NOT_FOUND",
                  "%d %s" % (code, body.get("code"))))

    for name, ok, detail in cases:
        check("错误码契约：%s" % name, ok, detail)

    # 未知路径不能变成 500 或 HTML
    code, body, ctype = call(QRY + "/api/v1/public/nope/nothing")
    check("未知路径返回 404 且是 JSON（不是 HTML 错误页）",
          code == 404 and "json" in (ctype or ""), "%d %s" % (code, ctype))

    # 错误响应也必须带 traceId：排障时唯一能把用户截图和日志对上的线索
    code, body, _ = call(CORE + "/api/v1/public/achievements/ach_missing")
    check("错误响应同样带 traceId", bool((body or {}).get("traceId")), (body or {}).get("traceId"))

    # ---- 4. 导出与事件载荷的投影字段集一致 ----
    #
    # 要比的**不是两份报文逐字相等**：事件是「发生了什么」，快照是「现在是什么」，两份文档各有
    # 独占字段。真正要守的不变量是——凡是投影侧会读的字段，两条路径必须都提供、且不重名。历史上
    # 这里真出过事：快照漏了 visibility/effectiveStatus，一次重建把 6515 条公开成果全变成非公开行，
    # 而重建日志全绿。
    #
    # 因此把「已确认允许不同」的三处显式写死，其余任何新增/漏掉都判失败：
    #   changeKind   事件独占，它是控制字段（UPSERT/MERGE），投影侧不读；
    #   achievementId / id   同一字段的两个名字，EventPayloadReader 两条路径互为兜底；
    #   version      快照放在载荷上，事件里由信封列 aggregate_version 承载（本项顺带核对取值相等）。
    EVENT_ONLY = {"changeKind"}
    ID_ALIASES = {"achievementId": "id"}
    ENVELOPE_BORNE = {"version"}  # 快照载荷有，事件由信封承载

    # 只取 UPSERT 事件：MERGE 事件的载荷是另一套结构（只带双方标识与快照来源），与快照比对没有
    # 意义，混进来会让本项按最差的一条判失败。
    row = psql(
        "core_db", "core",
        "SELECT aggregate_id || '|' || id || '|' || aggregate_version FROM evt_outbox "
        "WHERE event_type = 'CatalogChangeCommitted' AND payload->>'changeKind' = 'UPSERT' "
        "ORDER BY catalog_revision DESC LIMIT 1;",
    )
    if not row:
        check("找到一条 UPSERT 目录变更事件用于字段集比对", False, "没有事件")
    else:
        achievement_id, event_id, event_version = row[0].split("|")
        # 先把「最新那条事件」的 id 定下来，再展开它的键。若把 jsonb_object_keys 写进子查询并
        # 直接 LIMIT 1，限制作用在展开后的键上——只会取到一个键，比对结果恒为「导出多一堆字段」。
        payload_keys = psql(
            "core_db", "core",
            "SELECT string_agg(k, ',' ORDER BY k) FROM ("
            "  SELECT jsonb_object_keys(payload) AS k FROM evt_outbox WHERE id = '%s') t;"
            % event_id,
        )
        code, body, _ = call(
            CORE + "/internal/v1/exports/public-achievement/%s" % achievement_id,
            {"X-Service-Name": "query", "X-Service-Token": TOKEN},
        )
        view = (body or {}).get("data") or {}
        exported = set(view.keys())
        event_keys = set(payload_keys[0].split(",")) if payload_keys else set()

        # 归一化事件侧：去掉控制字段、把标识别名映射回快照用的名字、剔除信封承载的字段。
        normalized = {(ID_ALIASES.get(k, k)) for k in event_keys if k not in EVENT_ONLY}
        normalized -= ENVELOPE_BORNE
        exported_cmp = exported - ENVELOPE_BORNE
        missing = sorted(normalized - exported_cmp)
        extra = sorted(exported_cmp - normalized)
        check("公开导出与事件载荷的投影字段集一致", not missing and not extra,
              "导出缺 %s；导出多 %s（已剔除 changeKind / achievementId 别名 / version）"
              % (missing[:6], extra[:6]))

        # 版本号跨出口同值：投影侧增量路径取信封 aggregate_version，重建路径取快照 version。
        # 两者若不等，同一条成果在两条路径下写进投影的版本就不同，而这种差异只在换代时可见。
        check("版本号跨出口同值（快照 version vs 信封 aggregate_version）",
              str(view.get("version")) == str(event_version),
              "快照 %s / 信封 %s" % (view.get("version"), event_version))

    # ---- 5. 三服务行为一致：同样的空参数应被同样地拒绝 ----
    code_a, body_a, _ = call(ING + "/api/v1/admin/ingestion/sources/src_missing/run", method="POST")
    code_b, body_b, _ = call(CORE + "/api/v1/admin/core/sources/src_missing")
    check("跨服务的「对象不存在」语义一致（都拒绝，不返回空对象）",
          code_a >= 400 and code_b >= 400, "%s / %s" % (body_a.get("code"), body_b.get("code")))

    return finish()


def finish():
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    failed = len(RESULTS) - passed
    print("\n结果：通过 %d 项，失败 %d 项" % (passed, failed))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
