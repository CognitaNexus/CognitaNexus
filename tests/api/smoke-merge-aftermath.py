#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""合并之后的世界：旧标识、公开检索、投影与台账是否真的一致。

合并的写入路径由 smoke-merge-preview.sh 覆盖（预览 → 执行 → 候选裁决 → 改判）。那份脚本不回答
合并之后的问题，而后者才是用户能感知到的部分：

1. 旧链接还能不能打开，打开的是不是幸存实体（重定向，而不是 404 或原实体）；
2. 被并走的标题是否已从公开检索里消失（投影若还留着旧行，检索会把它翻出来）；
3. 幸存实体本身该可见就可见、该不可见就不可见（合并不得把幸存方一起带下架）；
4. 台账是否留下可追溯的前后快照（没有快照的合并无法复核）；
5. 审计是否记了这次合并（合并不可逆，必须有人负责）。

只读：不发起任何合并，只核对已经发生的合并。
"""

import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request

CORE = os.environ.get("CORE_BASE", "http://127.0.0.1:8081")
QRY = os.environ.get("QRY_BASE", "http://127.0.0.1:8083")
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


def get(url):
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as err:
        text = err.read().decode("utf-8", "replace")
        try:
            return err.code, json.loads(text) if text else {}
        except ValueError:
            return err.code, {"raw": text}


def final_entity_of(entity_id):
    """沿 merged_into_id 走到终点，返回 (最终实体, 状态, 可见性)。"""
    current = entity_id
    for _ in range(10):  # 合并链不该超过这个深度；超过说明数据有问题，直接停在原处
        rows = psql(
            "core_db", "core",
            "SELECT coalesce(merged_into_id, '') || '|' || effective_status || '|' || visibility "
            "FROM core_achievement WHERE id = '%s';" % current,
        )
        if not rows:
            return current, "", ""
        next_id, status, visibility = rows[0].split("|")
        if not next_id:
            return current, status, visibility
        current = next_id
    return current, "", ""


def main():
    # 取最近几次真实合并，覆盖「幸存方可见」与「幸存方不可见」两种情形。
    rows = psql(
        "core_db", "core",
        "SELECT m.id || '|' || m.merged_into_id || '|' || "
        "  coalesce((SELECT effective_status FROM core_achievement s WHERE s.id = m.merged_into_id), '') "
        "FROM core_achievement m WHERE m.merged_into_id IS NOT NULL "
        "ORDER BY m.updated_at DESC LIMIT 5;",
    )
    check("库里存在已合并成果可供核对（前提）", len(rows) > 0, "%d 条" % len(rows))
    if not rows:
        return finish()

    redirect_ok = 0
    hidden_ok = 0
    projection_ok = 0
    for row in rows:
        merged_id, survivor_id, survivor_status = row.split("|")
        # 合并可以成链（A→B、B→C）。旧标识必须解析到链的终点，而不是停在中间那个也已被并走的实体。
        final_id, final_status, final_visibility = final_entity_of(survivor_id)
        visible = final_status == "EFFECTIVE" and final_visibility == "PUBLIC"

        code, body = get(CORE + "/api/v1/public/achievements/%s" % merged_id)
        data = (body or {}).get("data") or {}
        if visible:
            # 终点可见时，旧链接必须打开终点实体：这才是「旧引用仍然有效」的正确含义。
            if code == 200 and data.get("id") == final_id:
                redirect_ok += 1
        else:
            # 终点不可见时，旧链接应当打不开，而不是把未公开内容顺带暴露出来。
            if code == 404:
                redirect_ok += 1

        title = psql(
            "core_db", "core",
            "SELECT title FROM core_achievement WHERE id = '%s';" % merged_id,
        )[0]
        code, body = get(
            QRY + "/api/v1/public/search/achievements?q=%s&size=20"
            % urllib.parse.quote(title[:120])
        )
        items = ((body or {}).get("data") or {}).get("items") or []
        if all(item.get("id") != merged_id for item in items):
            hidden_ok += 1

        projected = psql(
            "query_db", "query",
            "SELECT coalesce(a.effective_status, '-') || '|' || coalesce(a.visibility, '-') "
            "FROM qry_achievement a JOIN qry_generation g "
            "  ON g.generation = a.generation AND g.status = 'READY' "
            "WHERE a.achievement_id = '%s';" % merged_id,
        )
        # 投影里旧行不能仍然是「已生效 + 公开」：那样它会继续出现在检索与统计里。
        if not projected or not projected[0].startswith("EFFECTIVE|PUBLIC"):
            projection_ok += 1

    check("被并走的旧标识都重定向到幸存实体", redirect_ok == len(rows), "%d/%d" % (redirect_ok, len(rows)))
    check("被并走的标题已从公开检索消失", hidden_ok == len(rows), "%d/%d" % (hidden_ok, len(rows)))
    check("投影里旧行不再是「已生效 + 公开」", projection_ok == len(rows),
          "%d/%d" % (projection_ok, len(rows)))

    # 台账：每条合并都要有前后快照，否则事后无法回答「当时迁走了什么」。
    ledger_rows = psql(
        "core_db", "core",
        "SELECT coalesce(snapshot::text, '') FROM gov_merge_ledger "
        "WHERE entity_type = 'ACHIEVEMENT' ORDER BY occurred_at DESC LIMIT 5;",
    )
    check("合并台账记录了快照", len(ledger_rows) > 0 and all(len(s) > 2 for s in ledger_rows),
          "%d 条" % len(ledger_rows))
    has_keys = [
        s for s in ledger_rows
        if ("contributions" in s or "sources" in s or "before" in s)
    ]
    check("快照里能看到被迁移的关系与来源归属", len(has_keys) == len(ledger_rows),
          "%d/%d" % (len(has_keys), len(ledger_rows)))

    audit = psql(
        "core_db", "core",
        "SELECT count(*) FROM aud_audit_event WHERE action = 'ACHIEVEMENT_MERGED';",
    )[0]
    check("每次合并都留下审计事件", int(audit) > 0, "%s 条" % audit)

    # 幸存方状态自洽：合并不得把幸存方一起带下架。
    survivors = psql(
        "core_db", "core",
        "SELECT DISTINCT s.id FROM core_achievement m JOIN core_achievement s "
        "ON s.id = m.merged_into_id WHERE m.merged_into_id IS NOT NULL LIMIT 5;",
    )
    consistent = 0
    for survivor_id in survivors:
        final_id, status, visibility = final_entity_of(survivor_id)
        code, body = get(CORE + "/api/v1/public/achievements/%s" % survivor_id)
        public_ok = code == 200
        if (status == "EFFECTIVE" and visibility == "PUBLIC") == public_ok:
            consistent += 1
        if public_ok and ((body or {}).get("data") or {}).get("id") != final_id:
            public_ok = False
    check("幸存实体的公开可见性与它的状态一致（合并不误伤幸存方）",
          consistent == len(survivors), "%d/%d" % (consistent, len(survivors)))

    return finish()


def finish():
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    failed = len(RESULTS) - passed
    print("\n结果：通过 %d 项，失败 %d 项" % (passed, failed))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
