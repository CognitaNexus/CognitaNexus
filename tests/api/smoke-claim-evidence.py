#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""认领材料闭环自检：材料元信息可见 → 受保护下载 → 补证重提 → 终结后拒绝。

要点不是「接口 200」，而是下面几条产品规则：

1. 申请人要能看到自己这份申请有哪些材料（类型、大小、上传时刻），否则「补证」无从下手；
2. 材料本体只能经鉴权代理下载，列表响应里不得出现文件路径；非本人且无审核能力者拿不到；
3. 补证后重新提交必须保留原 claimId，材料不丢，状态回到待审；
4. 已经有结论的申请不能再追加材料或重提——服务端判断，不靠前端隐藏按钮。

前置：整栈已启动（bash ops/local/stack.sh）。
副作用：创建两个账号与一条认领申请（最终被判为驳回），并留一份测试材料。
不产生门户授权（结尾用驳回而不是通过）。

用法：
  python tests/api/smoke-claim-evidence.py
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

# 最小的合法 PDF：只用来验证「字节原样往返」，不是可渲染文档。
PDF_BYTES = (
    b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
    b"2 0 obj<</Type/Pages/Kids[3 0 R]/Count 0>>endobj\n"
    b"trailer<</Root 1 0 R>>\n%%EOF\n"
)


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
        input=sql.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
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

    def _send(self, method, url, body=None, token=None, raw=False):
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
                payload = resp.read()
                return resp.status, payload if raw else json.loads(payload.decode("utf-8") or "{}")
        except urllib.error.HTTPError as err:
            payload = err.read()
            if raw:
                return err.code, payload
            try:
                return err.code, json.loads(payload.decode("utf-8")) if payload else {}
            except ValueError:
                return err.code, {"raw": payload.decode("utf-8", "replace")[:200]}

    def csrf(self):
        self._send("GET", CORE + "/api/v1/auth/csrf")
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN":
                return urllib.parse.unquote(cookie.value)
        return ""

    def get(self, path, raw=False):
        return self._send("GET", CORE + path, raw=raw)

    def post(self, path, body=None):
        return self._send("POST", CORE + path, body if body is not None else {}, self.csrf())

    def upload(self, path, filename, content, content_type):
        boundary = "----cn" + uuid.uuid4().hex
        head = (
            "--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
            "Content-Type: %s\r\n\r\n" % (boundary, filename, content_type)
        ).encode("utf-8")
        tail = ("\r\n--%s--\r\n" % boundary).encode("utf-8")
        body = head + content + tail
        headers = {
            "Content-Type": "multipart/form-data; boundary=" + boundary,
            "X-XSRF-TOKEN": self.csrf(),
        }
        req = urllib.request.Request(CORE + path, data=body, headers=headers, method="POST")
        try:
            with self.opener.open(req, timeout=60) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
        except urllib.error.HTTPError as err:
            text = err.read().decode("utf-8", "replace")
            try:
                return err.code, json.loads(text) if text else {}
            except ValueError:
                return err.code, {"raw": text[:200]}

    def login(self, user, password):
        self.csrf()
        return self._send(
            "POST", CORE + "/api/v1/auth/login", {"username": user, "password": password}, self.csrf()
        )


def data_of(payload):
    return (payload or {}).get("data") or {}


def register(session, prefix):
    username = "%s_%s" % (prefix, uuid.uuid4().hex[:8])
    password = "Cn-dev-2026!"
    code, payload = session.post(
        "/api/v1/auth/register",
        {"username": username, "password": password, "displayName": "材料自检 " + username},
    )
    if code != 200:
        raise RuntimeError("注册失败 %s %s" % (code, payload))
    code, payload = session.login(username, password)
    if code != 200:
        raise RuntimeError("登录失败 %s %s" % (code, payload))
    return username, password


def pick_unclaimed_scholar():
    """选一个当前没有生效门户授权的学者，避免认领被判为争议，影响本脚本要验的路径。"""
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(QRY + "/api/v1/public/search/scholars?size=30", timeout=30) as resp:
        page = json.loads(resp.read().decode("utf-8"))
    for item in data_of(page).get("items", []):
        scholar_id = item["scholarId"]
        rows = psql(
            "core_db",
            "core",
            "SELECT count(*) FROM iam_portal_authority WHERE scholar_id='%s' AND status='ACTIVE';\n"
            % scholar_id,
        )
        if rows and rows[0] == "0":
            return scholar_id, item["scholarName"]
    raise RuntimeError("没有找到未认领的学者，无法验证认领路径")


def main():
    admin = Session()
    code, _ = admin.login(ADMIN_USER, ADMIN_PASS)
    if code != 200:
        raise RuntimeError("管理员登录失败：%s" % code)

    applicant = Session()
    register(applicant, "ev_applicant")
    other = Session()
    register(other, "ev_other")

    scholar_id, scholar_name = pick_unclaimed_scholar()
    print("== 目标学者：%s（%s）==" % (scholar_name, scholar_id))

    print("== 1 提交认领 ==")
    code, payload = applicant.post(
        "/api/v1/account/claims",
        {"scholarId": scholar_id, "statement": "材料闭环自检：这是本人门户，附机构邮箱说明。"},
    )
    claim = data_of(payload)
    claim_id = claim.get("id")
    check("认领提交成功", code == 200 and bool(claim_id), code)
    check("新申请为待审核", claim.get("status") == "PENDING_REVIEW", claim.get("status"))
    check("新申请无材料且可追加", claim.get("evidence") == [] and claim.get("canAttachEvidence") is True, "")

    print("== 2 上传材料 ==")
    code, payload = applicant.upload(
        "/api/v1/account/claims/%s/evidence" % claim_id, "evidence.pdf", PDF_BYTES, "application/pdf"
    )
    uploaded = data_of(payload)
    check("上传成功", code == 200 and bool(uploaded.get("id")), code)
    check("响应不含文件路径", "fileRef" not in json.dumps(payload), "")
    evidence_id = uploaded.get("id")

    print("== 3 申请人读取材料元信息 ==")
    code, payload = applicant.get("/api/v1/account/claims")
    mine = [row for row in data_of(payload) if row.get("id") == claim_id] if code == 200 else []
    view = mine[0] if mine else {}
    evidences = view.get("evidence") or []
    check("列表能取到该申请", bool(mine), code)
    check("材料数为 1", len(evidences) == 1, len(evidences))
    if evidences:
        first = evidences[0]
        check(
            "材料元信息字段齐全",
            first.get("contentType") == "application/pdf"
            and first.get("sizeBytes") == len(PDF_BYTES)
            and bool(first.get("createdAt"))
            and bool(first.get("downloadUrl")),
            first.get("contentType"),
        )
        check("元信息里没有磁盘路径", "/tmp" not in json.dumps(first) and "fileRef" not in json.dumps(first), "")
    check("待审申请 canResubmit=false", view.get("canResubmit") is False, view.get("canResubmit"))

    print("== 4 下载鉴权 ==")
    code, body = applicant.get("/api/v1/account/claims/evidence/%s" % evidence_id, raw=True)
    check("申请人可下载且字节一致", code == 200 and body == PDF_BYTES, code)
    code, _ = other.get("/api/v1/account/claims/evidence/%s" % evidence_id, raw=True)
    check("其他普通账号不可下载", code in (403, 404), code)
    anonymous = Session()
    code, _ = anonymous.get("/api/v1/account/claims/evidence/%s" % evidence_id, raw=True)
    check("匿名不可下载", code in (401, 403, 404), code)

    print("== 5 审核端能看到材料 ==")
    code, payload = admin.get("/api/v1/admin/core/claims?page=1&size=50")
    rows = data_of(payload) if code == 200 else []
    row = next((item for item in rows if item.get("id") == claim_id), None)
    check("审核队列包含该申请", row is not None, code)
    if row is not None:
        check("审核端材料数为 1", len(row.get("evidence") or []) == 1, len(row.get("evidence") or []))

    print("== 6 要求补证 → 申请人重提 ==")
    code, payload = admin.post(
        "/api/v1/admin/core/claims/%s/decision" % claim_id,
        {"decision": "NEED_MORE_EVIDENCE", "reason": "自检：请补充机构邮箱域名或主页的在职证明。"},
    )
    check("裁决为需补证", code == 200, code)
    code, payload = applicant.get("/api/v1/account/claims")
    view = next((item for item in data_of(payload) if item.get("id") == claim_id), {})
    check("申请人看到 canResubmit=true", view.get("canResubmit") is True, view.get("canResubmit"))
    check("申请人看到审核意见", "邮箱" in str(view.get("decisionReason")), view.get("decisionReason"))
    check("补证后材料仍在", len(view.get("evidence") or []) == 1, len(view.get("evidence") or []))

    code, payload = applicant.post(
        "/api/v1/account/claims/%s/resubmit" % claim_id,
        {"statement": "已补充机构邮箱域名说明与主页链接，请复核。"},
    )
    after = data_of(payload)
    check("重提成功", code == 200, code)
    check("claimId 不变", after.get("id") == claim_id, after.get("id"))
    check("状态回到待审核", after.get("status") == "PENDING_REVIEW", after.get("status"))

    print("== 7 终结后不能再追加或重提 ==")
    code, _ = admin.post(
        "/api/v1/admin/core/claims/%s/decision" % claim_id,
        {"decision": "REJECT", "reason": "自检：材料不足以证明本人控制该身份。"},
    )
    check("裁决为驳回", code == 200, code)
    code, payload = applicant.get("/api/v1/account/claims")
    view = next((item for item in data_of(payload) if item.get("id") == claim_id), {})
    check("终结后 canResubmit=false", view.get("canResubmit") is False, view.get("canResubmit"))
    check("终结后 canAttachEvidence=false", view.get("canAttachEvidence") is False, view.get("canAttachEvidence"))
    code, payload = applicant.post(
        "/api/v1/account/claims/%s/resubmit" % claim_id, {"statement": "再次提交应当被拒绝"}
    )
    check("终结后重提被拒", code in (409, 400), "%s %s" % (code, (payload or {}).get("code")))
    code, payload = applicant.upload(
        "/api/v1/account/claims/%s/evidence" % claim_id, "more.pdf", PDF_BYTES, "application/pdf"
    )
    check("终结后追加材料被拒", code in (409, 400), "%s %s" % (code, (payload or {}).get("code")))

    passed = sum(1 for _, ok, _ in RESULTS if ok)
    total = len(RESULTS)
    print("\n合计 %d 项，通过 %d，失败 %d" % (total, passed, total - passed))
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
