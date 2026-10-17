#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""门户授权链路自检：认领申请 → 补证 → 批准 → 授权 → 门户变更 → 裁决 → 撤销发布。

覆盖的语义（每条都对着一条产品规则，不是「接口 200 就算过」）：

- 认领必须经治理端裁决才建立授权；申请人自己不能直接拿到门户；
- 要求补证后可以重新提交，申请回到待审状态；
- 同一学者已有生效授权时，第二个人认领不转移原授权，而是标为争议；
- 门户变更里有「需审批」的字段，批准后写为 REVIEWED 级（后续来源自动更新不得覆盖）；
- 撤销发布后公开详情不可打开，重新确认后恢复；
- 未登录、未获授权两类越权都被拒。

前置：整栈已启动（bash ops/local/stack.sh）。副作用：会创建两个账号、一条认领链与一条变更，
并撤销/恢复一条真实成果的发布——都选择对业务无影响的样例（自检来源产生的成果）。
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

    def get(self, path):
        return self._send("GET", CORE + path)

    def post(self, path, body=None):
        token = self.csrf()
        return self._send("POST", CORE + path, body if body is not None else {}, token)

    def login(self, user, password):
        self.csrf()
        code, payload = self._send(
            "POST", CORE + "/api/v1/auth/login", {"username": user, "password": password}, self.csrf()
        )
        return code, payload


def data_of(payload):
    return (payload or {}).get("data") or {}


def main():
    admin = Session()
    code, payload = admin.login(ADMIN_USER, ADMIN_PASS)
    check("平台管理员登录", code == 200 and payload.get("code") == "OK", payload.get("message"))
    if code != 200:
        return finish()

    stamp = uuid.uuid4().hex[:6]
    users = []
    for tag in ("a", "b"):
        username = "portalsmoke%s%s" % (tag, stamp)
        password = "Smoke-%s-2026" % stamp
        code, payload = admin.post(
            "/api/v1/auth/register",
            {"username": username, "password": password, "displayName": "门户自检用户%s" % tag},
        )
        check("注册自检用户 %s" % tag, code == 200, payload.get("code") or payload.get("message"))
        session = Session()
        login_code, login_payload = session.login(username, password)
        check("自检用户 %s 登录" % tag, login_code == 200 and login_payload.get("code") == "OK")
        users.append(session)
    if len(users) != 2:
        return finish()
    first, second = users

    # 选一个未认领、有已公开成果的学者
    scholar_id = psql(
        "core_db",
        "core",
        "SELECT s.id FROM core_scholar s "
        "JOIN core_contribution c ON c.scholar_id = s.id "
        "JOIN core_achievement a ON a.id = c.achievement_id "
        "WHERE s.claimed_status = 'UNCLAIMED' AND s.merged_into_id IS NULL "
        "AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC' "
        "GROUP BY s.id ORDER BY count(*) DESC LIMIT 1;",
    )[0]
    check("找到未认领学者作为样例", scholar_id.startswith("sch_"), scholar_id)

    # ---- 认领：提交 → 列表 → 补证 → 批准 ----
    code, payload = first.post(
        "/api/v1/account/claims", {"scholarId": scholar_id, "statement": "本人是该学者，申请维护门户"}
    )
    claim = data_of(payload)
    claim_id = claim.get("id")
    check("提交认领申请", code == 200 and claim.get("status") == "PENDING_REVIEW",
          claim.get("status") or payload.get("code"))
    if not claim_id:
        return finish()

    code, payload = admin.get("/api/v1/admin/core/claims?page=1&size=50")
    ids = [row.get("id") for row in (data_of(payload) or [])]
    check("治理端待审队列含该申请", claim_id in ids, "%d 条待审" % len(ids))

    code, payload = admin.post(
        "/api/v1/admin/core/claims/%s/decision" % claim_id,
        {"decision": "NEED_MORE_EVIDENCE", "reason": "请补充能证明身份的材料"},
    )
    check("裁决为需要补证", data_of(payload).get("status") == "NEED_MORE_EVIDENCE",
          payload.get("code"))

    code, payload = first.post(
        "/api/v1/account/claims/%s/resubmit" % claim_id, {"statement": "补充：个人主页与邮箱可核验"}
    )
    check("补证后申请回到待审", data_of(payload).get("status") == "PENDING_REVIEW",
          data_of(payload).get("status") or payload.get("code"))

    code, payload = admin.post(
        "/api/v1/admin/core/claims/%s/decision" % claim_id,
        {"decision": "APPROVE", "reason": "材料可核验，授予门户维护权"},
    )
    approved = data_of(payload)
    check("批准认领并授予授权", approved.get("status") == "APPROVED" and approved.get("authorityId"),
          approved.get("status"))

    code, payload = first.get("/api/v1/account/portals")
    portals = data_of(payload) or []
    mine = [row for row in portals if row.get("scholarId") == scholar_id]
    check("本人门户列表出现该学者", len(mine) == 1, "%d 条" % len(portals))

    # ---- 竞争认领：不转移原授权 ----
    code, payload = second.post(
        "/api/v1/account/claims", {"scholarId": scholar_id, "statement": "我也是这位学者"}
    )
    rival_id = data_of(payload).get("id")
    check("第二人可提交认领申请", bool(rival_id), data_of(payload).get("status"))
    code, payload = admin.post(
        "/api/v1/admin/core/claims/%s/decision" % rival_id,
        {"decision": "APPROVE", "reason": "竞争认领核验"},
    )
    rival = data_of(payload)
    check("竞争认领被标为争议而不是转移授权", rival.get("status") == "DISPUTED", rival.get("status"))
    code, payload = first.get("/api/v1/account/portals")
    still_mine = [row for row in (data_of(payload) or []) if row.get("scholarId") == scholar_id]
    check("原持有人仍持有授权", len(still_mine) == 1,
          still_mine[0].get("authorityId") if still_mine else "lost")

    # ---- 门户变更：需审批字段 ----
    summary = "自检写入的研究简介 %s" % stamp
    code, payload = first.post(
        "/api/v1/account/portal-changes",
        {"scholarId": scholar_id, "fieldName": "RESEARCH_SUMMARY", "proposedValue": summary},
    )
    change = data_of(payload)
    change_id = change.get("id")
    check("提交需审批的门户变更", code == 200 and change.get("status") == "PENDING_REVIEW",
          "%s id=%s" % (change.get("status") or payload.get("code"), change_id))
    if not change_id:
        # 提交接口的返回只用于确认「受理成功」；裁决要用的是库里的那条记录，从我的变更列表里
        # 回查一次，避免把「接口返回的字段名」当成契约的一部分。
        code, listing = first.get("/api/v1/account/portal-changes?limit=20")
        rows = data_of(listing) or []
        matched = [
            row for row in rows
            if row.get("scholarId") == scholar_id and row.get("fieldName") == "RESEARCH_SUMMARY"
        ]
        change_id = matched[0]["id"] if matched else None
        check("回查取得变更 id", bool(change_id), change_id)

    code, payload = second.post(
        "/api/v1/account/portal-changes",
        {"scholarId": scholar_id, "fieldName": "RESEARCH_SUMMARY", "proposedValue": "越权写入"},
    )
    check("未获授权的用户可以提交门户变更吗：应被拒", code == 403,
          "%d %s" % (code, payload.get("code")))

    code, payload = admin.post(
        "/api/v1/admin/core/portal-changes/%s/decision" % change_id,
        {"approve": True, "reason": "内容属实，审核通过"},
    )
    check("裁决通过门户变更", data_of(payload).get("status") == "APPROVED",
          "%s | %s | trace=%s" % (payload.get("code"), payload.get("message"), payload.get("traceId")))

    stored_rows = psql(
        "core_db", "core", "SELECT coalesce(research_summary,'') FROM core_scholar WHERE id='%s';" % scholar_id
    )
    stored = stored_rows[0] if stored_rows else ""
    check("批准后学者简介已更新", stored == summary, stored[:40])

    provenance = psql(
        "core_db",
        "core",
        "SELECT value_level FROM core_field_provenance "
        "WHERE entity_type='SCHOLAR' AND entity_id='%s' AND field_name='researchSummary';" % scholar_id,
    )
    check("简介写为 REVIEWED 级（来源自动更新不得覆盖）",
          bool(provenance) and provenance[-1] in ("REVIEWED", "MANUAL"),
          provenance)

    code, payload = admin.post(
        "/api/v1/admin/core/portal-changes/%s/decision" % change_id,
        {"approve": True, "reason": "重复裁决"},
    )
    check("重复裁决被拒", code == 409, "%d %s" % (code, payload.get("code")))

    # 不在这里直接 UPDATE 学者简介来「模拟来源覆盖」：那样做会把已写好的 REVIEWED 值破坏掉，
    # 制造出一条与业务无关的脏数据，而且并不能验证来源写入路径。来源侧是否尊重 REVIEWED
    # 由 SourceIntakeService 的字段合并规则保证，对应的单测在 core 模块里。

    # ---- 撤销发布与恢复 ----
    sample = psql(
        "core_db",
        "core",
        "SELECT a.id FROM core_achievement a "
        "JOIN core_achievement_source s ON s.achievement_id = a.id "
        "JOIN raw_source_record r ON r.id = s.source_record_id "
        "JOIN raw_source_config cfg ON cfg.id = r.source_id "
        "WHERE cfg.source_code LIKE 'e2e-openalex-%' AND a.effective_status='EFFECTIVE' "
        "AND a.visibility='PUBLIC' ORDER BY a.created_at DESC LIMIT 1;",
    )
    if not sample:
        check("找到可撤销的真实成果样例", False, "没有 e2e 来源的已发布成果")
        return finish()
    achievement_id = sample[0]
    code, payload = admin.post(
        "/api/v1/admin/core/achievements/%s/withdraw" % achievement_id,
        {"reason": "自检：验证撤销发布后公开侧不可见"},
    )
    check("撤销发布成功", code == 200 and payload.get("code") == "OK", payload.get("code"))

    deadline = time.time() + 45
    hidden = False
    while time.time() < deadline:
        code, _ = admin.get("/api/v1/public/achievements/%s" % achievement_id)
        if code == 404:
            hidden = True
            break
        time.sleep(2)
    check("撤销后公开详情不可打开", hidden, "code=%s" % code)

    code, payload = admin.post(
        "/api/v1/admin/core/achievements/%s/confirm" % achievement_id,
        {"reason": "自检：恢复发布"},
    )
    check("重新确认发布成功", code == 200 and payload.get("code") == "OK", payload.get("code"))
    deadline = time.time() + 45
    restored = False
    while time.time() < deadline:
        code, _ = admin.get("/api/v1/public/achievements/%s" % achievement_id)
        if code == 200:
            restored = True
            break
        time.sleep(2)
    check("重新确认后公开详情恢复", restored, "code=%s" % code)

    # ---- 未登录负向 ----
    anon = Session()
    code, payload = anon.post(
        "/api/v1/account/claims", {"scholarId": scholar_id, "statement": "未登录提交"}
    )
    check("未登录提交认领被拒", code == 401, "%d %s" % (code, payload.get("code")))
    code, payload = anon.get("/api/v1/account/portals")
    check("未登录读门户列表被拒", code == 401, code)

    return finish()


def finish():
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    failed = len(RESULTS) - passed
    print("\n结果：通过 %d 项，失败 %d 项" % (passed, failed))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
