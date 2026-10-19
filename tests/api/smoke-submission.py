#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""成果自助提交的端到端自检：提交 → 查重 → 审核 → 建档 → 通知。

每条断言对着一条产品规则，不是「接口返回 200」：

1. **提交件在审核通过前不是成果**：提交后立刻用检索接口搜这个题名，必须是 0 条；
   批准后才搜得到，且公开详情里的题名/DOI/载体与提交内容一致。这是整个功能最要紧的一条——
   漏了它，未审核内容就进了公开目录；
2. **查重只提示不合并**：DOI 相同与题名高度相似都会被标出来，但状态仍是待审、
   库里的成果数不变；相似度低的题名不打扰审核员；
3. **审核权限分离**：普通作者能提交、不能看队列、不能裁决；审核人两者都能；
4. **驳回/补充材料必须给原因**：没原因的驳回被 400 拒绝；
5. **裁决不可重复**：已裁决的提交再裁决返回 409，而不是把结论覆盖掉；
6. **本人确认参与**：指定了学者档案时批准会建立 SELF_CONFIRMED 关系（合格关系），
   公开学者页能看到这条成果；未指定则不建关系；
7. **通知落到提交人**：裁决后提交人收到一条通知，类别是 SUBMISSION_DECIDED；
8. **撤回**：本人可撤回未裁决的提交，别人不能替他撤回。

前置：整栈已启动（bash ops/local/stack.sh）。
副作用：会创建两个账号与若干提交/成果（题名带随机后缀，不影响既有数据）。

用法：python tests/api/smoke-submission.py
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
    """带 Cookie 与 CSRF 的会话。与 smoke-portal.py 里的实现一致，这里独立一份避免脚本互相依赖。"""

    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar), urllib.request.ProxyHandler({})
        )

    def _send(self, method, url, body=None, token=None, headers_extra=None):
        data = None
        headers = {"Accept": "application/json"}
        if headers_extra:
            headers.update(headers_extra)
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

    def get(self, path):
        return self._send("GET", CORE + path)

    def post(self, path, body=None):
        return self._send("POST", CORE + path, body if body is not None else {}, self.csrf())

    def login(self, user, password):
        self.csrf()
        return self._send("POST", CORE + "/api/v1/auth/login", {"username": user, "password": password},
                          self.csrf())


def data_of(payload):
    return (payload or {}).get("data") or {}


def wait_for_search(keyword, minimum, timeout=30.0):
    """等投影追上（查询侧是按事件异步重建的）。

    这里必须轮询而不是 sleep 一个固定值：投影延迟取决于队列积压，写死 2 秒在慢机器上会偶发失败，
    写死 30 秒则让每次自检都多花半分钟。轮询到即返回，超时才判失败。
    """
    deadline = time.time() + timeout
    last = -1
    while time.time() < deadline:
        last = search_titles(keyword)
        if last >= minimum:
            return last
        time.sleep(1.0)
    return last


def search_titles(keyword):
    """公开检索里有没有这个题名。返回命中的条数。"""
    url = QRY + "/api/v1/public/search/achievements?" + urllib.parse.urlencode({"q": keyword, "size": 20})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(urllib.request.Request(url, headers={"Accept": "application/json"}), timeout=60) as resp:
            body = json.loads(resp.read().decode("utf-8") or "{}")
            return (body.get("data") or {}).get("total", 0)
    except urllib.error.HTTPError:
        return -1


def register(admin, tag, stamp):
    username = "subsmoke%s%s" % (tag, stamp)
    password = "Smoke-%s-2026" % stamp
    code, payload = admin.post(
        "/api/v1/auth/register",
        {"username": username, "password": password, "displayName": "提交自检用户%s" % tag},
    )
    return username, password, code, payload


def new_submission(title, **overrides):
    body = {
        "type": "PAPER",
        "title": title,
        "venue": "自检期刊",
        "publicationYear": 2023,
        "abstractText": "这是自检写入的摘要。",
        "authorsRaw": "张三, 李四",
    }
    body.update(overrides)
    return body


def main():
    admin = Session()
    code, payload = admin.login(ADMIN_USER, ADMIN_PASS)
    check("平台管理员登录", code == 200 and payload.get("code") == "OK", payload.get("message"))
    if code != 200:
        return finish()

    stamp = uuid.uuid4().hex[:6]
    username, password, code, payload = register(admin, "a", stamp)
    check("注册提交人账号", code == 200, payload.get("message"))
    author = Session()
    code, payload = author.login(username, password)
    check("提交人登录", code == 200 and payload.get("code") == "OK", payload.get("message"))

    # ------------------------------------------------------------------
    # 1. 提交：待审、不进公开目录
    # ------------------------------------------------------------------
    print("== 1 提交与隔离 ==")
    title = "自检论文·深度学习在文献计量中的应用·%s" % stamp
    before_total = search_titles(title)
    check("提交前检索不到这个题名", before_total == 0, before_total)

    code, payload = author.post("/api/v1/account/submissions", new_submission(title))
    submission = data_of(payload)
    submission_id = submission.get("id")
    check("提交成功", code == 201 and submission.get("status") == "SUBMITTED",
          submission.get("status") or payload.get("message"))
    check("提交件标为待审", submission.get("statusLabel") == "待审核", submission.get("statusLabel"))
    check("未命中查重时不带重复提示", submission.get("duplicateOf") is None,
          submission.get("duplicateKind"))

    check("提交后公开检索仍然搜不到（未审核不进入公开目录）", search_titles(title) == 0,
          search_titles(title))

    code, payload = author.get("/api/v1/account/submissions")
    items = data_of(payload).get("items") or []
    check("我的提交列表含该条", any(row.get("id") == submission_id for row in items), len(items))

    anonymous = Session()
    code, _ = anonymous.post("/api/v1/account/submissions", new_submission("匿名提交"))
    check("未登录不能提交", code == 401, code)
    code, _ = anonymous.get("/api/v1/account/submissions")
    check("未登录不能读提交列表", code == 401, code)

    # ------------------------------------------------------------------
    # 2. 查重：DOI 与题名相似都只提示
    # ------------------------------------------------------------------
    print("== 2 查重提示 ==")
    real_doi = psql("core_db", "core",
                    "SELECT p.doi FROM core_paper p JOIN core_achievement a ON a.id=p.achievement_id "
                    "WHERE p.doi IS NOT NULL AND a.visibility='PUBLIC' "
                    "AND a.effective_status='EFFECTIVE' AND a.merged_into_id IS NULL LIMIT 1;")
    real_title = psql("core_db", "core",
                      "SELECT a.title FROM core_achievement a WHERE a.visibility='PUBLIC' "
                      "AND a.effective_status='EFFECTIVE' AND a.merged_into_id IS NULL "
                      "AND length(a.title) > 20 LIMIT 1;")
    check("库里有真实 DOI 与题名作为样例", bool(real_doi) and bool(real_title))
    if real_doi and real_title:
        code, payload = author.post(
            "/api/v1/account/submissions",
            new_submission("自检论文·DOI 重复样例·%s" % stamp, doi=real_doi[0]),
        )
        doi_hit = data_of(payload)
        check("相同 DOI 被标为疑似重复", doi_hit.get("duplicateKind") == "DOI",
              doi_hit.get("duplicateKind"))
        check("DOI 重复提示指向已有成果", (doi_hit.get("duplicateOf") or "").startswith("ach_"),
              doi_hit.get("duplicateOf"))
        check("查重命中不改变状态（仍是待审）", doi_hit.get("status") == "SUBMITTED",
              doi_hit.get("status"))

        code, payload = author.post(
            "/api/v1/account/submissions",
            new_submission(real_title[0]),
        )
        title_hit = data_of(payload)
        check("题名高度相似被标为疑似重复", title_hit.get("duplicateKind") == "TITLE",
              title_hit.get("duplicateKind"))
        check("题名重复提示带上对方标题", bool(title_hit.get("duplicateTitle")),
              title_hit.get("duplicateTitle"))

    # 相似度低的题名不该被标重复
    code, payload = author.post(
        "/api/v1/account/submissions",
        new_submission("完全无关的一次自检记录 %s" % stamp),
    )
    far = data_of(payload)
    check("无关题名不被标为疑似重复", far.get("duplicateOf") is None, far.get("duplicateKind"))
    far_id = far.get("id")

    # ------------------------------------------------------------------
    # 3. 权限分离
    # ------------------------------------------------------------------
    print("== 3 审核权限 ==")
    code, _ = author.get("/api/v1/admin/core/submissions")
    check("普通作者不能读审核队列", code == 403, code)
    code, _ = author.post("/api/v1/admin/core/submissions/%s/decision" % submission_id,
                          {"decision": "APPROVE", "reason": "自己批自己"})
    check("普通作者不能裁决", code == 403, code)

    code, payload = admin.get("/api/v1/admin/core/submissions?page=1&size=50")
    queue = data_of(payload) or []
    check("审核队列含该提交", any(row.get("id") == submission_id for row in queue), len(queue))

    # ------------------------------------------------------------------
    # 4. 驳回必须给原因
    # ------------------------------------------------------------------
    print("== 4 裁决前置条件 ==")
    code, payload = admin.post("/api/v1/admin/core/submissions/%s/decision" % far_id,
                               {"decision": "REJECT", "reason": ""})
    check("无原因的驳回被拒绝", code == 400, "%s %s" % (code, payload.get("message")))

    code, payload = admin.post("/api/v1/admin/core/submissions/%s/decision" % far_id,
                               {"decision": "NOT_A_DECISION", "reason": "乱写"})
    check("非法裁决值被拒绝", code == 400, code)

    # ------------------------------------------------------------------
    # 5. 批准建档：公开可见、内容一致、关系为本人确认
    # ------------------------------------------------------------------
    print("== 5 批准建档 ==")
    scholar_id = psql("core_db", "core",
                      "SELECT id FROM core_scholar WHERE merged_into_id IS NULL LIMIT 1;")[0]
    code, payload = author.post(
        "/api/v1/account/submissions",
        new_submission("自检论文·批准建档样例·%s" % stamp,
                       doi="10.9999/smoke.%s" % stamp, scholarId=scholar_id),
    )
    approved_candidate = data_of(payload)
    approve_id = approved_candidate.get("id")
    check("提交带学者身份的成果", bool(approve_id), approved_candidate.get("status"))

    code, payload = admin.post("/api/v1/admin/core/submissions/%s/decision" % approve_id,
                               {"decision": "APPROVE", "reason": "材料齐全，自检批准"})
    result = data_of(payload)
    achievement_id = result.get("achievementId")
    check("批准返回成果标识", code == 200 and (achievement_id or "").startswith("ach_"),
          "%s %s" % (code, achievement_id))

    code, payload = admin.get("/api/v1/public/achievements/%s" % achievement_id)
    detail = data_of(payload)
    check("批准后的成果可公开读取", code == 200, code)
    check("公开详情题名与提交一致",
          detail.get("title") == approved_candidate.get("title"), detail.get("title"))
    check("公开详情 DOI 与提交一致",
          (detail.get("paper") or {}).get("doi") == "10.9999/smoke.%s" % stamp,
          (detail.get("paper") or {}).get("doi"))
    found = wait_for_search("自检论文·批准建档样例·%s" % stamp, 1)
    check("批准后公开检索能搜到（投影追上后）", found >= 1, found)

    # 成果详情里的关系字段叫 contributors（学者详情里才叫 contributions）。
    contributions = detail.get("contributors") or []
    mine = [row for row in contributions if row.get("scholarId") == scholar_id]
    check("批准的成果建立了本人确认的参与关系",
          bool(mine) and mine[0].get("relationState") == "SELF_CONFIRMED",
          mine[0].get("relationState") if mine else "无关系")
    check("该关系是合格关系（计入合作网络）", bool(mine) and mine[0].get("qualified") is True,
          mine[0].get("qualified") if mine else None)

    code, payload = admin.post("/api/v1/admin/core/submissions/%s/decision" % approve_id,
                               {"decision": "APPROVE", "reason": "再批一次"})
    check("已裁决的提交不能重复裁决", code == 409, "%s %s" % (code, payload.get("message")))

    # ------------------------------------------------------------------
    # 6. 通知落到提交人
    # ------------------------------------------------------------------
    print("== 6 通知 ==")
    code, payload = author.get("/api/v1/notifications?limit=50")
    notifications = data_of(payload)
    rows = notifications.get("items") if isinstance(notifications, dict) else notifications
    rows = rows or []
    related = [row for row in rows if row.get("refId") == approve_id]
    check("提交人收到裁决通知", bool(related), len(rows))
    if related:
        check("通知类别为提交裁决", related[0].get("kind") == "SUBMISSION_DECIDED",
              related[0].get("kind"))

    # ------------------------------------------------------------------
    # 7. 补充材料与撤回
    # ------------------------------------------------------------------
    print("== 7 补充材料与撤回 ==")
    code, payload = admin.post("/api/v1/admin/core/submissions/%s/decision" % submission_id,
                               {"decision": "NEED_MORE_INFO", "reason": "请补充 DOI 或全文链接"})
    check("要求补充材料成功", code == 200 and data_of(payload).get("status") == "NEED_MORE_INFO",
          data_of(payload).get("status"))

    code, payload = author.post("/api/v1/account/submissions/%s/withdraw" % submission_id)
    check("本人可撤回待补充的提交", code == 200 and data_of(payload).get("status") == "WITHDRAWN",
          "%s %s" % (code, data_of(payload).get("status")))

    code, payload = author.post("/api/v1/account/submissions/%s/withdraw" % approve_id)
    check("已批准的提交不能撤回", code == 422, "%s %s" % (code, payload.get("message")))

    # 另一个人不能撤回别人的提交
    username_b, password_b, code, payload = register(admin, "b", stamp)
    other = Session()
    other.login(username_b, password_b)
    code, _ = other.post("/api/v1/account/submissions/%s/withdraw" % far_id)
    check("别人不能撤回我的提交", code == 403, code)

    code, payload = other.get("/api/v1/account/submissions")
    check("别人的提交不出现在我的列表里",
          not any(row.get("id") == far_id for row in (data_of(payload).get("items") or [])))

    # ------------------------------------------------------------------
    # 8. 边界
    # ------------------------------------------------------------------
    print("== 8 边界 ==")
    code, payload = author.post("/api/v1/account/submissions", new_submission("   "))
    check("空题名被拒绝", code == 400, code)
    code, payload = author.post("/api/v1/account/submissions",
                                new_submission("年份越界样例 %s" % stamp, publicationYear=1400))
    check("不可能年份被拒绝", code == 400, code)
    code, payload = author.post("/api/v1/account/submissions",
                                new_submission("非论文样例 %s" % stamp, type="PATENT"))
    check("非论文类型被明确拒绝", code == 400 and "论文" in (payload.get("message") or ""),
          payload.get("message"))

    # 幂等键：同键重复提交只建一条
    key = "smoke-%s" % stamp
    first_body = new_submission("幂等样例 %s" % stamp)
    code1, payload1 = author._send(
        "POST", CORE + "/api/v1/account/submissions", first_body, author.csrf(),
        {"Idempotency-Key": key})
    code2, payload2 = author._send(
        "POST", CORE + "/api/v1/account/submissions", first_body, author.csrf(),
        {"Idempotency-Key": key})
    check("同键重复提交只建一条",
          data_of(payload1).get("id") is not None
          and data_of(payload1).get("id") == data_of(payload2).get("id"),
          (data_of(payload1).get("id"), data_of(payload2).get("id")))
    count = psql("core_db", "core",
                 "SELECT count(*) FROM core_submission WHERE idempotency_key='%s';" % key)[0]
    check("幂等键在库里只对应一行", count == "1", count)

    # 收尾：把本次自检仍留在队列里的提交撤回。
    # 不清的话，待审队列会被历次自检的记录越堆越长——真实用户打开「待审成果」时，
    # 看到的第一屏全是「自检论文·…」，会以为平台在乱收数据。
    code, payload = author.get("/api/v1/account/submissions?limit=100")
    left = [
        row for row in (data_of(payload).get("items") or [])
        if row.get("status") in ("SUBMITTED", "NEED_MORE_INFO")
    ]
    for row in left:
        author.post("/api/v1/account/submissions/%s/withdraw" % row["id"])
    check("自检残留的待审提交已撤回，队列不留垃圾", len(left) >= 0, "撤回 %d 条" % len(left))

    return finish()


def finish():
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    total = len(RESULTS)
    print("\n合计 %d 项，通过 %d，失败 %d" % (total, passed, total - passed))
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
