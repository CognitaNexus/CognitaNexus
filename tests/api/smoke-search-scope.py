#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""检索范围（摘要 / 作者名）与输入建议的端到端自检。

这份脚本要回答的是「检索是不是真的变宽了」，而不是「接口返回了 200」：

1. **作者名命中**：q 是某位学者的姓（如 kuai），返回的成果题名里不含这个词——
   证明命中的是参与关系里的作者名，而不是题名巧合；
2. **摘要命中**：q 只在摘要里出现的词，返回里存在题名不含该词的成果，
   再拿这条 id 去 core 取详情，确认详情摘要里确实有这个词（跨服务闭合）；
3. **增量是真实增量**：用 psql 直接算「只按题名/载体/DOI」的旧口径条数，
   与接口 total 比较，新口径必须严格大于旧口径——否则说明条件没生效；
4. **建议按前缀优先**：首条建议的题名必须以关键词开头，否则「敲一半」时最想选的那条不在第一行；
5. **建议不越权**：学者建议里的「公开成果 N 篇」必须等于该学者 AUTO/SELF_CONFIRMED
   关系在公开生效成果上的条数（用 psql 算），待核验关系不能被算进来；
6. **边界**：空关键词给空列表并说明原因；limit 越界 400；大小写与首尾空白归一。

不变量：本脚本只读，不写任何数据库行。

前置：整栈已启动（bash ops/local/stack.sh）。
用法：python tests/api/smoke-search-scope.py
"""

import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

QUERY = os.environ.get("QUERY_BASE", "http://127.0.0.1:8083")
CORE = os.environ.get("CORE_BASE", "http://127.0.0.1:8081")
PSQL = os.environ.get("PSQL_BIN", "D:/dev-tools/pgsql/bin/psql.exe")

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  -> " + str(detail)) if detail else ""))


def get(base, path, params=None):
    url = base + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8")
        try:
            return error.code, json.loads(body)
        except ValueError:
            return error.code, {"raw": body}


def psql(sql):
    env = dict(os.environ, PGCLIENTENCODING="UTF8", PGPASSWORD="cn_query_app_dev")
    out = subprocess.run(
        [PSQL, "-h", "127.0.0.1", "-p", "5432", "-U", "query_app", "-d", "query_db", "-At", "-c", sql],
        capture_output=True,
        text=True,
        env=env,
    )
    if out.returncode != 0:
        raise RuntimeError("psql 失败: " + out.stderr[:200])
    return out.stdout.strip()


GENERATION = psql(
    "select generation from qry_generation where status='READY' order by created_at desc limit 1"
)
print("当前读取代：" + GENERATION)

BASE_WHERE = (
    "from qry_achievement a where a.generation='%s' "
    "and a.visibility='PUBLIC' and a.effective_status='EFFECTIVE'" % GENERATION
)


def count_old(keyword):
    like = keyword.lower()
    return int(
        psql(
            "select count(*) %s and (a.title_norm like '%%%s%%' "
            "or lower(coalesce(a.venue,'')) like '%%%s%%' or lower(coalesce(a.doi,''))='%s')"
            % (BASE_WHERE, like, like, like)
        )
    )


def count_new(keyword):
    like = keyword.lower()
    return int(
        psql(
            "select count(*) %s and (a.title_norm like '%%%s%%' "
            "or lower(coalesce(a.venue,'')) like '%%%s%%' or lower(coalesce(a.doi,''))='%s' "
            "or lower(coalesce(a.abstract_text,'')) like '%%%s%%' "
            "or exists (select 1 from qry_contribution c where c.generation='%s' "
            "and c.achievement_id=a.achievement_id and c.relation_state in ('AUTO','SELF_CONFIRMED') "
            "and lower(coalesce(c.scholar_name,'')) like '%%%s%%'))"
            % (BASE_WHERE, like, like, like, like, GENERATION, like)
        )
    )


def search(keyword, **extra):
    params = {"q": keyword, "size": 100}
    params.update(extra)
    status, body = get(QUERY, "/api/v1/public/search/achievements", params)
    return status, body


def suggest(keyword, **extra):
    params = {"q": keyword}
    params.update(extra)
    return get(QUERY, "/api/v1/public/search/suggest", params)


print("\n一、作者名命中")
status, body = search("kuai")
data = body.get("data", {})
check("作者名检索返回 200", status == 200, status)
check("作者名命中非空", data.get("total", 0) > 0, "total=%s" % data.get("total"))
titles = [item["title"] for item in data.get("items", [])]
check(
    "首屏命中的题名都不含 kuai（走的是作者名）",
    titles and all("kuai" not in title.lower() for title in titles),
    titles[0][:40] if titles else "无结果",
)
check(
    "作者名命中数与「旧口径」的差就是新增部分",
    data.get("total", 0) == count_new("kuai") and count_new("kuai") > count_old("kuai"),
    "新口径=%s 旧口径=%s 接口=%s" % (count_new("kuai"), count_old("kuai"), data.get("total")),
)

status_upper, _ = search("  KUAI  ")
check(
    "首尾空白与大小写归一后结果一致",
    status_upper == 200 and search("KUAI")[1]["data"]["total"] == data.get("total"),
    data.get("total"),
)

print("\n二、摘要命中")
status, body = search("transformer", sort="citations_desc")
data = body.get("data", {})
check("摘要关键词返回 200", status == 200, status)
check(
    "摘要命中数 = 新口径（严格大于旧口径）",
    data.get("total", 0) == count_new("transformer") and count_new("transformer") > count_old("transformer"),
    "新口径=%s 旧口径=%s 接口=%s" % (count_new("transformer"), count_old("transformer"), data.get("total")),
)
abstract_only = [
    item for item in data.get("items", []) if "transformer" not in item["title"].lower()
]
check("返回里存在「题名不含关键词」的成果", len(abstract_only) > 0, len(abstract_only))

if abstract_only:
    target = abstract_only[0]
    detail_status, detail_body = get(CORE, "/api/v1/public/achievements/" + target["id"])
    detail = detail_body.get("data", {})
    paper = detail.get("paper") or {}
    abstract_text = (paper.get("abstractText") or "").lower()
    check("core 详情可取到该成果", detail_status == 200, detail_status)
    check(
        "详情摘要里确实含该关键词（摘要命中不是误报）",
        "transformer" in abstract_text,
        "摘要 %d 字" % len(abstract_text),
    )
    check(
        "详情题名仍不含该关键词",
        "transformer" not in (detail.get("title") or "").lower(),
        (detail.get("title") or "")[:40],
    )

print("\n三、输入建议")
status, body = suggest("graph", limit=5)
data = body.get("data", {})
items = data.get("achievements", [])
check("建议接口返回 200", status == 200, status)
check("建议非空", len(items) > 0, len(items))
check(
    "首条建议以关键词开头（前缀优先，不是纯相似度）",
    items and items[0]["title"].lower().startswith("graph"),
    items[0]["title"][:40] if items else "无",
)
check(
    "建议项带类型与年份（够渲染一行，不必再查详情）",
    items and items[0].get("type") and "publicationYear" in items[0],
    items[0] if items else None,
)
check("建议条数受 limit 约束", len(items) <= 5, len(items))
check("建议回显真实生效的口径", data.get("dataNatureScope") == "REAL", data.get("dataNatureScope"))

status, body = suggest("   ")
data = body.get("data", {})
check(
    "空关键词返回空列表并说明原因（不退化成 LIKE '%%'）",
    status == 200 and data.get("achievements") == [] and data.get("scholars") == [] and "为空" in data.get("note", ""),
    data.get("note", "")[:40],
)

status, _ = suggest("graph", limit=0)
check("limit=0 返回 400", status == 400, status)
status, _ = suggest("graph", limit=101)
check("limit 超上限返回 400", status == 400, status)

print("\n四、学者建议只算合格关系")
status, body = suggest("kuai", limit=5)
scholars = body.get("data", {}).get("scholars", [])
check("作者名能给出学者建议", len(scholars) > 0, [s["scholarName"] for s in scholars])
if scholars:
    row = scholars[0]
    expected = int(
        psql(
            "select count(distinct c.achievement_id) from qry_contribution c "
            "join qry_achievement a on a.generation=c.generation and a.achievement_id=c.achievement_id "
            "where c.generation='%s' and c.scholar_id='%s' "
            "and c.relation_state in ('AUTO','SELF_CONFIRMED') "
            "and a.visibility='PUBLIC' and a.effective_status='EFFECTIVE'"
            % (GENERATION, row["scholarId"])
        )
    )
    check(
        "学者建议的公开成果数 = 合格关系条数",
        row["qualifiedAchievementCount"] == expected,
        "%s vs %s" % (row["qualifiedAchievementCount"], expected),
    )

    # 只算合格关系这件事要能被证伪：找一位「只有待核验关系」的学者，他的姓名不应出现在建议里。
    pending_only = psql(
        "select c.scholar_id||'|'||max(c.scholar_name) from qry_contribution c "
        "where c.generation='%s' and c.relation_state not in ('AUTO','SELF_CONFIRMED') "
        "and not exists (select 1 from qry_contribution d where d.generation='%s' "
        "and d.scholar_id=c.scholar_id and d.relation_state in ('AUTO','SELF_CONFIRMED')) "
        "group by c.scholar_id limit 1" % (GENERATION, GENERATION)
    )
    if pending_only:
        pending_id, pending_name = pending_only.split("|", 1)
        _, pending_body = suggest(pending_name.split(" ")[-1], limit=10)
        returned_ids = [item["scholarId"] for item in pending_body.get("data", {}).get("scholars", [])]
        check(
            "只有待核验关系的学者不出现在建议里",
            pending_id not in returned_ids,
            "候选 %s（%s）" % (pending_name, pending_id),
        )
    else:
        check("只有待核验关系的学者不出现在建议里", False, "库中找不到这样的学者，无法证伪")

failed = [item for item in RESULTS if not item[1]]
print("\n结果：%d/%d 通过" % (len(RESULTS) - len(failed), len(RESULTS)))
if failed:
    print("失败项：")
    for name, _, detail in failed:
        print("  - %s  %s" % (name, detail))
    sys.exit(1)
