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

    def _send(self, method, url, body=None, token=None, raw=None, headers_extra=None):
        data = None
        headers = {"Accept": "application/json"}
        if raw is not None:
            data = raw
            headers.update(headers_extra or {})
        elif body is not None:
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

    def get_raw(self, path):
        """读二进制资源（头像）：返回状态、Content-Type 与字节。"""
        req = urllib.request.Request(CORE + path, headers={"Accept": "*/*"})
        try:
            with self.opener.open(req, timeout=60) as resp:
                return resp.status, resp.headers.get("Content-Type", ""), resp.read()
        except urllib.error.HTTPError as err:
            return err.code, err.headers.get("Content-Type", ""), err.read()

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

    def put(self, path, body=None):
        token = self.csrf()
        return self._send("PUT", CORE + path, body if body is not None else {}, token)

    def delete(self, path):
        token = self.csrf()
        return self._send("DELETE", CORE + path, token=token)

    def upload(self, path, filename, content, content_type):
        """multipart/form-data 上传。手写边界是因为这里不引第三方库。"""
        token = self.csrf()
        boundary = "----cn" + uuid.uuid4().hex
        body = b""
        body += ("--%s\r\n" % boundary).encode("utf-8")
        body += (
            'Content-Disposition: form-data; name="file"; filename="%s"\r\n' % filename
        ).encode("utf-8")
        body += ("Content-Type: %s\r\n\r\n" % content_type).encode("utf-8")
        body += content
        body += ("\r\n--%s--\r\n" % boundary).encode("utf-8")
        return self._send(
            "POST",
            CORE + path,
            token=token,
            raw=body,
            headers_extra={"Content-Type": "multipart/form-data; boundary=%s" % boundary},
        )

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

    # ---- 门户变更：需审批字段（机构属于身份与归属事实） ----
    # 这里刻意不用「研究简介」：它自第十批起属于本人自述、提交即生效，只有机构这类
    # 影响归属的字段才进待审通道。用错字段会让这段测试在语义变更后仍然通过（假绿）。
    new_org = "自检机构 %s" % stamp
    code, payload = first.post(
        "/api/v1/account/portal-changes",
        {"scholarId": scholar_id, "fieldName": "ORGANIZATION_NAME", "proposedValue": new_org},
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
            if row.get("scholarId") == scholar_id and row.get("fieldName") == "ORGANIZATION_NAME"
        ]
        change_id = matched[0]["id"] if matched else None
        check("回查取得变更 id", bool(change_id), change_id)

    code, payload = second.post(
        "/api/v1/account/portal-changes",
        {"scholarId": scholar_id, "fieldName": "ORGANIZATION_NAME", "proposedValue": "越权写入"},
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
        "core_db", "core", "SELECT coalesce(organization_name,'') FROM core_scholar WHERE id='%s';" % scholar_id
    )
    stored = stored_rows[0] if stored_rows else ""
    check("批准后学者机构已更新", stored == new_org, stored[:40])

    provenance = psql(
        "core_db",
        "core",
        "SELECT value_level FROM core_field_provenance "
        "WHERE entity_type='SCHOLAR' AND entity_id='%s' AND field_name='organizationName';" % scholar_id,
    )
    check("机构写为 REVIEWED 级（来源自动更新不得覆盖）",
          bool(provenance) and provenance[-1] in ("REVIEWED", "MANUAL"),
          provenance)

    code, payload = admin.post(
        "/api/v1/admin/core/portal-changes/%s/decision" % change_id,
        {"approve": True, "reason": "重复裁决"},
    )
    check("重复裁决被拒", code == 409, "%d %s" % (code, payload.get("code")))

    # 不在这里直接 UPDATE 学者机构来「模拟来源覆盖」：那样做会把已写好的 REVIEWED 值破坏掉，
    # 制造出一条与业务无关的脏数据，而且并不能验证来源写入路径。来源侧是否尊重 REVIEWED
    # 由 SourceIntakeService 的字段合并规则保证，对应的单测在 core 模块里。

    # ---- 门户资料自助维护：简介、主页、头像、代表作置顶 ----
    self_summary = "自检自助写入的研究简介 %s" % stamp
    homepage = "https://example.edu/~selfcheck-%s" % stamp
    code, payload = first.put(
        "/api/v1/account/scholars/%s/profile" % scholar_id,
        {"researchSummary": self_summary, "homepageUrl": homepage},
    )
    data = data_of(payload)
    check("本人可直接修改简介与主页", code == 200 and data.get("homepageUrl") == homepage,
          "%d %s" % (code, payload.get("message")))
    check("修改后立即回显新简介", data.get("researchSummary") == self_summary,
          str(data.get("researchSummary"))[:40])

    rows = psql("core_db", "core",
                "SELECT coalesce(research_summary,'')||'|'||coalesce(homepage_url,'') "
                "FROM core_scholar WHERE id='%s';" % scholar_id)
    check("简介与主页已落库", rows and rows[0] == self_summary + "|" + homepage, rows[:1])

    provenance = psql(
        "core_db", "core",
        "SELECT value_level FROM core_field_provenance "
        "WHERE entity_type='SCHOLAR' AND entity_id='%s' AND field_name='researchSummary';" % scholar_id)
    check("自助修改的简介写为 MANUAL 级", bool(provenance) and provenance[-1] in ("MANUAL", "REVIEWED"),
          provenance)

    code, payload = first.put(
        "/api/v1/account/scholars/%s/profile" % scholar_id,
        {"homepageUrl": "javascript:alert(1)"},
    )
    check("非 http(s) 的主页地址被拒（不让档案页变成脚本入口）", code == 400,
          "%d %s" % (code, payload.get("code")))

    code, payload = second.put(
        "/api/v1/account/scholars/%s/profile" % scholar_id,
        {"researchSummary": "越权写入"},
    )
    check("非管理者改不了别人门户的资料", code == 403, "%d %s" % (code, payload.get("code")))

    # 公开页必须能读到刚写进去的自述与主页（写成功但读不到，等于没生效）。
    code, payload = first.get("/api/v1/public/scholars/%s" % scholar_id)
    public = data_of(payload)
    check("公开档案页回显本人填写的主页",
          (public.get("profile") or {}).get("homepageUrl") == homepage,
          (public.get("profile") or {}).get("homepageUrl"))
    check("公开档案页回显本人填写的简介", public.get("researchSummary") == self_summary,
          str(public.get("researchSummary"))[:30])

    # 置顶：先拿一条该学者名下的公开成果。
    own_rows = psql(
        "core_db", "core",
        "SELECT a.id FROM core_achievement a JOIN core_contribution c ON c.achievement_id=a.id "
        "WHERE c.scholar_id='%s' AND c.relation_state IN ('AUTO','SELF_CONFIRMED') "
        "AND a.visibility='PUBLIC' AND a.effective_status='EFFECTIVE' AND a.merged_into_id IS NULL "
        "LIMIT 2;" % scholar_id)
    if own_rows:
        code, payload = first.put(
            "/api/v1/account/scholars/%s/featured" % scholar_id,
            {"achievementIds": own_rows[:2]},
        )
        featured = (data_of(payload).get("featured") or [])
        check("可置顶本人名下的公开成果",
              code == 200 and [row.get("achievementId") for row in featured] == own_rows[:2],
              "%d %s" % (code, payload.get("message")))
        check("置顶顺序与提交顺序一致（名次由数组顺序决定）",
              [row.get("achievementId") for row in featured] == own_rows[:2],
              [row.get("achievementId") for row in featured])

        code, payload = first.get("/api/v1/public/scholars/%s" % scholar_id)
        public_featured = (data_of(payload).get("featured") or {})
        check("公开页出现置顶代表作",
              [item.get("id") for item in (public_featured.get("items") or [])] == own_rows[:2],
              public_featured.get("items"))
    else:
        check("该自查学者名下没有可置顶的公开成果（跳过置顶用例）", False, scholar_id)

    foreign = psql(
        "core_db", "core",
        "SELECT a.id FROM core_achievement a WHERE a.visibility='PUBLIC' "
        "AND a.effective_status='EFFECTIVE' AND a.merged_into_id IS NULL "
        "AND NOT EXISTS (SELECT 1 FROM core_contribution c WHERE c.achievement_id=a.id "
        "AND c.scholar_id='%s') LIMIT 1;" % scholar_id)
    if foreign:
        code, payload = first.put(
            "/api/v1/account/scholars/%s/featured" % scholar_id,
            {"achievementIds": [foreign[0]]},
        )
        check("不能置顶不属于自己的成果", code == 400, "%d %s" % (code, payload.get("code")))

    code, payload = first.put(
        "/api/v1/account/scholars/%s/featured" % scholar_id,
        {"achievementIds": ["ach_1", "ach_2", "ach_3", "ach_4"]},
    )
    check("置顶超过上限被拒", code == 400, "%d %s" % (code, payload.get("code")))

    # 头像：1x1 PNG，真实字节，走真实 multipart 通道。
    png = bytes.fromhex(
        "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
        "0000000d49444154789c6360000002000100ffff03000006000557bfabd40000000049454e44ae426082")
    code, payload = first.upload(
        "/api/v1/account/scholars/%s/avatar" % scholar_id, "avatar.png", png, "image/png")
    check("上传头像成功", code == 200 and data_of(payload).get("avatarUrl"),
          "%d %s" % (code, payload.get("message")))
    avatar_url = data_of(payload).get("avatarUrl") or ""
    code, content_type, body = first.get_raw(avatar_url.split("?")[0])
    check("公开读取头像返回真实 PNG 字节",
          code == 200 and content_type.startswith("image/png") and body == png,
          "%d %s %d bytes" % (code, content_type, len(body)))

    code, payload = first.upload(
        "/api/v1/account/scholars/%s/avatar" % scholar_id, "bad.png", b"not-an-image", "image/png")
    check("非图片内容被拒（只信文件头不信 Content-Type）", code == 400,
          "%d %s" % (code, payload.get("code")))

    code, payload = first.delete("/api/v1/account/scholars/%s/avatar" % scholar_id)
    check("可移除头像", code == 200 and not data_of(payload).get("avatarUrl"),
          "%d %s" % (code, payload.get("message")))
    code, _, _ = first.get_raw("/api/v1/public/scholars/%s/avatar" % scholar_id)
    check("移除后公开头像返回 404", code == 404, code)

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
