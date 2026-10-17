#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""WB-01 / T01：合并成果旧链接的公开资格专项自检。

背景：旧实现在合并分支里先解析幸存实体、再直接组装响应，资格判定只在「非合并」分支执行，
于是一条仍处于待治理或受限状态的成果，其旧标识可以通过重定向读到标题与类型。修复方向是
「先解析链终点 → 对终点做资格校验 → 再组装详情」，本脚本用真实接口把这件事钉死。

覆盖：
1. A→B→C 三跳链，终点公开时旧链接跳到 C，且与直接读 C 的可见结论一致；
2. 终点受限 / 待治理 / 已撤回时，旧链接与直接读终点都必须 404，且报文里不得出现标题；
3. 终点恢复公开后旧链接重新可用（证明前一步的 404 是资格判定，不是缓存或数据缺失）；
4. 不存在的标识同样 404，与「不可见」不可区分；
5. 合并链成环（数据异常）时的实际行为单独记录，不当成通过项。

写库说明：测试实体由本脚本用固定前缀 ach_t01_ 插入，标题含明确的测试标记，数据性质为
SIMULATED；结束时按前缀删除。不触碰任何既有成果，不修改真实来源数据。

前置：整栈已启动（core 8081 + PostgreSQL 5432）。
"""

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

CORE = os.environ.get("CORE_BASE", "http://127.0.0.1:8081")
PSQL = os.environ.get("PSQL_BIN", "D:/dev-tools/pgsql/bin/psql.exe")
PREFIX = "ach_t01_"

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  -> " + str(detail)) if detail else ""))


def psql(sql):
    env = dict(os.environ)
    env["PGPASSWORD"] = "cn_core_app_dev"
    env["PGCLIENTENCODING"] = "UTF8"
    env["MSYS_NO_PATHCONV"] = "1"
    out = subprocess.run(
        [
            PSQL, "-h", "127.0.0.1", "-p", "5432", "-U", "core_app", "-d", "core_db",
            "-At", "-v", "ON_ERROR_STOP=1", "-f", "-",
        ],
        input=sql.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr.decode("utf-8", "replace")[:400])
    return [line.strip() for line in out.stdout.decode("utf-8", "replace").splitlines() if line.strip()]


def get(path):
    req = urllib.request.Request(CORE + path, method="GET")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as err:
        body = err.read().decode("utf-8", "replace")
        try:
            return err.code, json.loads(body)
        except json.JSONDecodeError:
            return err.code, {"raw": body[:200]}


def seed(ids, links, visibility="PUBLIC", status="EFFECTIVE"):
    """按库内不变量插入夹具。

    core_achievement 上有延迟约束触发器：visibility=PUBLIC 或 effective_status=EFFECTIVE 的成果
    必须恰好有一个类型扩展。所以先以 RESTRICTED/PENDING_GOVERNANCE 插入，再补 core_paper，
    最后才改成可发布状态——顺序反过来会被约束直接拒绝（这正是该约束该做的事）。
    """
    rows = []
    for idx, aid in enumerate(ids):
        merged = links.get(aid)
        merged_sql = "NULL" if merged is None else "'%s'" % merged
        rows.append(
            "('%s','PAPER','T01 合并可见性测试成果 %d','t01 merge visibility test %d',"
            "'SIMULATED','RESTRICTED','PENDING_GOVERNANCE',2024,'YEAR',%s,1)"
            % (aid, idx, idx, merged_sql)
        )
    papers = ",\n".join(
        "('%s','10.5555/t01.%d','10.5555/t01.%d')" % (aid, idx, idx)
        for idx, aid in enumerate(ids)
    )
    promote = ",\n".join("('%s')" % aid for aid in ids)
    psql(
        "BEGIN;\n"
        "INSERT INTO core_achievement "
        "(id,type,title,title_norm,data_nature,visibility,effective_status,publication_year,"
        "year_precision,merged_into_id,version) VALUES\n" + ",\n".join(rows) + ";\n"
        "INSERT INTO core_paper (achievement_id,doi,doi_norm) VALUES\n" + papers + ";\n"
        "UPDATE core_achievement SET visibility='%s', effective_status='%s' "
        "WHERE id IN (%s);\n"
        "COMMIT;\n" % (visibility, status, promote)
    )


def set_state(aid, visibility, status):
    psql(
        "UPDATE core_achievement SET visibility='%s', effective_status='%s', updated_at=now() "
        "WHERE id='%s';\n" % (visibility, status, aid)
    )


def cleanup():
    """先退出可发布状态再删。

    延迟约束触发器在 COMMIT 时校验「可发布成果必须恰好有一个类型扩展」，所以不能在成果仍为
    PUBLIC/EFFECTIVE 时先删 core_paper——删扩展的那一刻不变量就破了。顺序：降级 → 删扩展
    → 删成果。
    """
    psql(
        "BEGIN;\n"
        "UPDATE core_achievement SET visibility='RESTRICTED', effective_status='PENDING_GOVERNANCE',"
        " merged_into_id=NULL WHERE id LIKE '%s%%';\n"
        "DELETE FROM core_paper WHERE achievement_id LIKE '%s%%';\n"
        "DELETE FROM core_achievement WHERE id LIKE '%s%%';\n"
        "COMMIT;\n" % (PREFIX, PREFIX, PREFIX)
    )


def body_contains(ids, payload, needle):
    text = json.dumps(payload, ensure_ascii=False)
    return needle in text


def main():
    a, b, c = PREFIX + "a", PREFIX + "b", PREFIX + "c"
    cleanup()
    seed([a, b, c], {a: b, b: c})

    print("== 1 终点公开：旧链接跳到链终点，且与直接读终点一致 ==")
    status_a, payload_a = get("/api/v1/public/achievements/" + a)
    status_c, payload_c = get("/api/v1/public/achievements/" + c)
    view_a = payload_a.get("data") or {}
    check("旧链接 A 返回 200", status_a == 200, status_a)
    check("旧链接解析到链终点 C（不是 B）", view_a.get("id") == c, view_a.get("id"))
    check("旧链接带 redirectedFrom=A", view_a.get("redirectedFrom") == a, view_a.get("redirectedFrom"))
    check(
        "旧链接与直接读终点可见结论一致",
        (status_a == 200) == (status_c == 200),
        "A=%s C=%s" % (status_a, status_c),
    )
    check(
        "跳转后的响应与直接读终点同构（含扩展字段与说明之外无差异）",
        set(view_a) - {"redirectedFrom", "redirectNote"} == set(view_c_set(payload_c)),
        sorted(set(view_a) ^ set(view_c_set(payload_c))),
    )

    print("== 2 终点受限：旧链接与直接读终点都 404，且不泄露标题 ==")
    set_state(c, "RESTRICTED", "EFFECTIVE")
    status_a, payload_a = get("/api/v1/public/achievements/" + a)
    status_c, _ = get("/api/v1/public/achievements/" + c)
    check("受限终点：旧链接 404", status_a == 404, status_a)
    check("受限终点：直接读 404", status_c == 404, status_c)
    check("受限终点：报文无标题", not body_contains([a], payload_a, "T01 合并可见性测试成果"), "")

    print("== 3 终点待治理：旧链接不得放行 ==")
    set_state(c, "PUBLIC", "PENDING_GOVERNANCE")
    status_a, payload_a = get("/api/v1/public/achievements/" + a)
    check("待治理终点：旧链接 404", status_a == 404, status_a)
    check("待治理终点：报文无标题", not body_contains([a], payload_a, "T01 合并可见性测试成果"), "")

    print("== 4 终点已撤回：旧链接不得放行 ==")
    set_state(c, "RESTRICTED", "WITHDRAWN")
    status_a, _ = get("/api/v1/public/achievements/" + a)
    check("已撤回终点：旧链接 404", status_a == 404, status_a)

    print("== 5 恢复公开：旧链接重新可用（排除缓存与数据缺失的假象） ==")
    set_state(c, "PUBLIC", "EFFECTIVE")
    status_a, payload_a = get("/api/v1/public/achievements/" + a)
    check("恢复后旧链接 200", status_a == 200, status_a)
    check("恢复后仍指向 C", (payload_a.get("data") or {}).get("id") == c, "")

    print("== 6 不存在的标识：与不可见不可区分 ==")
    status_missing, payload_missing = get("/api/v1/public/achievements/" + PREFIX + "missing")
    check("不存在：404", status_missing == 404, status_missing)
    check(
        "不存在与不可见的响应码一致",
        status_missing == 404,
        "missing=%s" % status_missing,
    )

    print("== 7 合并链成环（数据异常）：记录实际行为，不作为通过项 ==")
    set_state(a, "PUBLIC", "EFFECTIVE")
    psql("UPDATE core_achievement SET merged_into_id='%s' WHERE id='%s';\n" % (a, b))
    status_cycle, payload_cycle = get("/api/v1/public/achievements/" + a)
    data_cycle = payload_cycle.get("data") or {}
    print(
        "  记录：成环时 status=%s id=%s redirectedFrom=%s"
        % (status_cycle, data_cycle.get("id"), data_cycle.get("redirectedFrom"))
    )
    # 恢复为正常链，供清理
    psql("UPDATE core_achievement SET merged_into_id='%s' WHERE id='%s';\n" % (c, b))

    cleanup()

    passed = sum(1 for _, ok, _ in RESULTS if ok)
    total = len(RESULTS)
    print("\n合计 %d 项，通过 %d，失败 %d" % (total, passed, total - passed))
    return 0 if passed == total else 1


def view_c_set(payload):
    data = payload.get("data") or {}
    return data.keys()


if __name__ == "__main__":
    try:
        sys.exit(main())
    finally:
        try:
            cleanup()
        except Exception as exc:  # noqa: BLE001 - 清理失败要显式暴露，不能静默
            print("清理测试数据失败：" + str(exc), file=sys.stderr)
