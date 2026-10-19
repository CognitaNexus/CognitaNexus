#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""部署前补齐的八项功能的接口级自检。

覆盖：

  1. 通知中心补全（类型筛选 / 全部已读 / 移除）
  2. 系统公告（管理员发布 + 普通用户被拒 + 同标题 60 秒去重 + 发布台账）
  3. 账号数据导出（未登录被拒 + 文件内不含凭据）
  4. 注销申请（重复申请被拒 + 冷静期内不能执行 + 驳回 + 不能批自己 + 本人撤回）
  5. 收藏学者（幂等 / 状态 / 取消 / 模拟数据被拒 / 公开只给数量）
  6. 相关成果推荐（真实成果有结果 + 不存在的成果 404）
  7. BibTeX / RIS 批量导入（预览不写库 + 真实导入 + 不支持类型跳过 + 撤回还场）

第 8 项（离线种子包）不在本脚本内：它需要独立数据库实例，由 ops/verify/seed-roundtrip.sh 验收。

前置：整栈已启动（bash ops/local/stack.sh），主库有真实数据。
副作用（刻意保留，用于证明「公告不可撤回」「注销不删数据」）：
  - 发布 1 条系统公告，给当时所有启用账号各写一条通知；
  - 创建 2 个测试账号（ntf2_* / fav_*），账号不做清理——账号是审计记录的 actor，删了反而破坏追溯；
  - 导入的成果提交件会在脚本末尾自行撤回，不留待审队列。

用法：
  python tests/api/smoke-v03-features.py
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
ADMIN_USER = os.environ.get("ADMIN_USER", "cnadmin")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "cn-admin-Dev-2026")
PSQL = os.environ.get("PSQL_BIN", "D:/dev-tools/pgsql/bin/psql.exe")

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  -> " + str(detail)) if detail else ""))


def skip(name, why):
    print("  SKIP  " + name + "  -> " + why)


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
            payload = err.read()
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

    def get(self, path):
        return self._send("GET", CORE + path)

    def get_text(self, path):
        """取原始响应体（导出接口返回的就是文件本身，不是 JSON 信封）。"""
        req = urllib.request.Request(CORE + path, headers={"Accept": "application/json"})
        try:
            with self.opener.open(req, timeout=120) as resp:
                return resp.status, resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as err:
            return err.code, err.read().decode("utf-8", "replace")

    def post(self, path, body=None):
        return self._send("POST", CORE + path, body if body is not None else {}, self.csrf())

    def delete(self, path):
        return self._send("DELETE", CORE + path, None, self.csrf())

    def login(self, user, password):
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
        {"username": username, "password": password, "displayName": "功能自检 " + username},
    )
    if code != 200:
        raise RuntimeError("注册失败 %s %s" % (code, payload))
    code, _ = session.login(username, password)
    if code != 200:
        raise RuntimeError("登录失败 %s" % code)
    return username, password


def unread_count(session):
    code, payload = session.get("/api/v1/notifications/unread-count")
    return code, data_of(payload).get("unreadCount")


def notifications(session, kind=None, unread_only=False):
    path = "/api/v1/notifications?limit=100&unreadOnly=%s" % ("true" if unread_only else "false")
    if kind:
        path += "&kind=" + kind
    code, payload = session.get(path)
    return code, data_of(payload).get("items") or []


# ----------------------------------------------------------------------------
# 1. 通知中心：筛选 / 全部已读 / 移除
# ----------------------------------------------------------------------------


def section_notifications(admin, fresh):
    print("== 1 通知中心：筛选 / 全部已读 / 移除 ==")
    title = "自检公告 %s" % uuid.uuid4().hex[:8]
    code, payload = admin.post(
        "/api/v1/admin/core/announcements",
        {"title": title, "body": "这是一条用于自检的系统公告，发布后不可撤回。", "level": "WARNING"},
    )
    data = data_of(payload)
    check("管理员发布公告成功", code in (200, 201) and data.get("recipients", 0) >= 1, (code, data.get("recipients")))

    code, count = unread_count(fresh)
    check("新账号收到公告未读", code == 200 and count == 1, (code, count))

    code, payload = fresh.get("/api/v1/notifications/kinds")
    kinds = data_of(payload).get("kinds") or []
    check("类型列表含系统公告", code == 200 and "SYSTEM_ANNOUNCEMENT" in kinds, kinds)

    code, items = notifications(fresh, kind="SYSTEM_ANNOUNCEMENT")
    check("按类型筛选只返回公告", code == 200 and len(items) == 1 and items[0]["kind"] == "SYSTEM_ANNOUNCEMENT", len(items))
    if items:
        check("公告正文复制进通知", "不可撤回" in items[0].get("body", ""), items[0].get("body", "")[:60])

    code, items = notifications(fresh, kind="CLAIM_DECIDED")
    check("筛选不匹配的类型返回空", code == 200 and len(items) == 0, len(items))

    code, payload = fresh.post("/api/v1/notifications/read-all")
    check("全部已读生效", code == 200 and data_of(payload).get("markedRead") == 1, (code, payload))
    _, remaining = unread_count(fresh)
    check("全部已读后未读为 0", remaining == 0, remaining)

    row_id = None
    code, items = notifications(fresh)
    if items:
        row_id = items[0]["id"]

    # 横向越权：另一个账号移除别人的通知，返回 0 行，且原账号列表不变。
    stranger = Session()
    register(stranger, "ntf2_stranger")
    code, payload = stranger.post("/api/v1/notifications/archive", {"ids": [row_id]})
    check("越权移除返回 0 行", code == 200 and data_of(payload).get("archived") == 0, (code, payload))
    code, items = notifications(fresh)
    check("越权移除后本方列表不变", len(items) == 1, len(items))

    code, payload = fresh.post("/api/v1/notifications/archive", {"ids": [row_id]})
    check("本人移除成功", code == 200 and data_of(payload).get("archived") == 1, (code, payload))
    check("移除后列表为空", len(notifications(fresh)[1]) == 0, "")
    code, payload = fresh.get("/api/v1/notifications/kinds")
    check("移除后该类型不再出现", "SYSTEM_ANNOUNCEMENT" not in (data_of(payload).get("kinds") or []), "")

    # 归档是「从我的列表消失」，不是删除：库里那行仍在，只是 archived_at 有值。
    rows = psql(
        "core_db",
        "core",
        "SELECT count(*) FROM core_notification WHERE id = '%s' AND archived_at IS NOT NULL;" % row_id,
    )
    check("移除只置归档位不删行", rows and rows[0] == "1", rows)

    code, payload = fresh.post("/api/v1/notifications/archive-read")
    check("清空已读不报错", code == 200, (code, payload))


# ----------------------------------------------------------------------------
# 2. 系统公告：权限与去重
# ----------------------------------------------------------------------------


def section_announcement(admin, plain_user):
    print("== 2 系统公告：权限与去重 ==")
    code, _ = plain_user.post(
        "/api/v1/admin/core/announcements", {"title": "普通用户尝试发布", "body": "应被拒绝"}
    )
    check("普通用户发布公告被拒", code in (403, 401), code)

    title = "幂等自检 %s" % uuid.uuid4().hex[:8]
    code, _ = admin.post(
        "/api/v1/admin/core/announcements", {"title": title, "body": "第一次发布", "level": "INFO"}
    )
    check("首次发布成功", code in (200, 201), code)
    code, payload = admin.post(
        "/api/v1/admin/core/announcements", {"title": title, "body": "重复点击", "level": "INFO"}
    )
    # 60 秒窗口：双击发布不能让全体账号各收两条一模一样的通知（公告无法撤回）。
    check("同标题 60 秒内被拒", code == 422, (code, payload))

    code, payload = admin.post(
        "/api/v1/admin/core/announcements", {"title": "级别自检 %s" % uuid.uuid4().hex[:8], "body": "x", "level": "URGENT"}
    )
    check("非法级别被拒", code == 400, code)

    code, payload = admin.get("/api/v1/admin/core/announcements?limit=5")
    items = data_of(payload).get("items") or []
    check("发布台账可查", code == 200 and len(items) >= 1, len(items))
    if items:
        check("台账带收件账号数", int(items[0].get("recipients") or 0) >= 1, items[0].get("recipients"))


# ----------------------------------------------------------------------------
# 3. 账号数据导出
# ----------------------------------------------------------------------------


def section_export(plain_user):
    print("== 3 账号数据导出 ==")
    anon = Session()
    code, _ = anon.get("/api/v1/account/export")
    check("未登录导出被拒", code in (401, 403), code)

    code, text = plain_user.get_text("/api/v1/account/export")
    check("登录后可导出", code == 200, code)
    if code == 200:
        try:
            payload = json.loads(text)
        except ValueError:
            payload = {}
        check("导出格式标识正确", payload.get("format") == "cognitanexus-account-export/1", payload.get("format"))
        check("导出含账号基本资料", "account" in payload and "username" in payload.get("account", {}), "")
        # 三样不能出现在「我的数据」里：凭据、会话控制位、来源报文原文。
        check("导出不含口令哈希", "passwordHash" not in text, "")
        check("导出不含会话版本", "authVersion" not in text, "")
        check("导出声明了上限", "limits" in payload and "notifications" in payload.get("limits", {}), "")
        check("导出声明了排除项", isinstance(payload.get("excluded"), list) and len(payload["excluded"]) >= 1, "")


# ----------------------------------------------------------------------------
# 4. 注销申请
# ----------------------------------------------------------------------------


def section_closure(admin, applicant):
    print("== 4 注销申请与冷静期 ==")
    code, payload = applicant.post("/api/v1/account/closure", {"reason": "自检：验证注销流程"})
    first = data_of(payload)
    request_id = first.get("id")
    check("提交注销申请成功", code == 200 and bool(request_id), (code, request_id))
    check("申请状态为待处理", first.get("status") == "PENDING", first.get("status"))
    check("给出冷静期结束时间", bool(first.get("effectiveAt")), first.get("effectiveAt"))

    code, payload = applicant.post("/api/v1/account/closure", {"reason": "自检：重复提交"})
    check("重复申请被拒", code == 422, (code, payload))

    code, payload = admin.get("/api/v1/admin/core/closure-requests?limit=50")
    queue = data_of(payload).get("items") or []
    check("管理端队列可见该申请", code == 200 and any(r.get("id") == request_id for r in queue), len(queue))

    code, payload = admin.post(
        "/api/v1/admin/core/closure-requests/%s/decide" % request_id,
        {"decision": "APPROVE", "note": "自检：冷静期内执行"},
    )
    # 冷静期条件写在 SQL 里：管理员的机器时钟无法绕过。
    check("冷静期内执行被拒", code == 422, (code, payload))
    code, _ = applicant.login(applicant.username, applicant.password)
    check("被拒后账号仍可登录", code == 200, code)

    code, payload = admin.post(
        "/api/v1/admin/core/closure-requests/%s/decide" % request_id,
        {"decision": "REJECT", "note": "自检：驳回以保留账号"},
    )
    check("驳回成功", code == 200 and data_of(payload).get("status") == "REJECTED", (code, payload))
    code, _ = applicant.login(applicant.username, applicant.password)
    check("驳回后账号仍可登录", code == 200, code)

    code, payload = applicant.get("/api/v1/account/closure")
    check("驳回后本人看不到待处理申请", data_of(payload).get("id") is None, data_of(payload).get("id"))

    # 本人撤回：再申请一次，然后自己撤掉，不需要任何审批。
    code, payload = applicant.post("/api/v1/account/closure", {"reason": "自检：验证本人撤回"})
    second_id = data_of(payload).get("id")
    check("再次申请成功", code == 200 and bool(second_id), (code, second_id))
    code, payload = applicant.post("/api/v1/account/closure/%s/cancel" % second_id)
    check("本人撤销成功", code == 200, (code, payload))
    code, _ = applicant.get("/api/v1/account/closure")
    check("撤销后无待处理申请", data_of(payload).get("id") is None, "")

    # 不能审批自己：管理员自己申请，再由自己批准。
    code, payload = admin.post("/api/v1/account/closure", {"reason": "自检：管理员自申请"})
    self_id = data_of(payload).get("id")
    check("管理员可提交注销申请", code == 200 and bool(self_id), (code, self_id))
    code, payload = admin.post(
        "/api/v1/admin/core/closure-requests/%s/decide" % self_id,
        {"decision": "APPROVE", "note": "自检：自己批自己"},
    )
    check("不能审批自己的注销申请", code == 422, (code, payload))
    code, payload = admin.post("/api/v1/account/closure/%s/cancel" % self_id)
    check("管理员撤回自己的申请", code == 200, (code, payload))


# ----------------------------------------------------------------------------
# 5. 收藏学者
# ----------------------------------------------------------------------------


def section_favorite(plain_user, anon):
    print("== 5 收藏学者 ==")
    rows = psql(
        "core_db",
        "core",
        """
        SELECT s.id FROM core_scholar s
        WHERE s.merged_into_id IS NULL AND s.data_nature = 'REAL'
        ORDER BY s.id LIMIT 1;
        """,
    )
    if not rows:
        skip("收藏学者", "库中没有可用学者")
        return
    scholar_id = rows[0]

    code, payload = anon.get("/api/v1/public/scholars/%s/favorite-count" % scholar_id)
    check("未登录可读公开收藏数", code == 200, code)
    check("公开侧只给数量", "scholarId" in data_of(payload) and "favorited" not in data_of(payload), data_of(payload))

    code, payload = anon.post("/api/v1/account/favorites/%s" % scholar_id)
    check("未登录收藏被拒", code in (401, 403), code)

    code, payload = plain_user.get("/api/v1/account/favorites/%s" % scholar_id)
    check("初始未收藏", data_of(payload).get("favorited") is False, (code, data_of(payload)))

    code, payload = plain_user.post("/api/v1/account/favorites/%s" % scholar_id)
    check("收藏成功", code == 200 and data_of(payload).get("favorited") is True, (code, payload))
    first_count = data_of(payload).get("favoriteCount")

    code, payload = plain_user.post("/api/v1/account/favorites/%s" % scholar_id)
    check("重复收藏幂等且说明原因", code == 200 and "此前已收藏" in (data_of(payload).get("note") or ""), (code, data_of(payload).get("note")))
    check("重复收藏不增加计数", data_of(payload).get("favoriteCount") == first_count, (first_count, data_of(payload).get("favoriteCount")))

    code, payload = plain_user.get("/api/v1/account/favorites?limit=100")
    data = data_of(payload)
    items = (data.get("items") if isinstance(data, dict) else data) or []
    check("收藏列表含该学者", any(r.get("scholarId") == scholar_id for r in items), len(items))

    code, payload = plain_user.delete("/api/v1/account/favorites/%s" % scholar_id)
    check("取消收藏成功", code == 200 and data_of(payload).get("favorited") is False, (code, payload))
    code, payload = plain_user.get("/api/v1/account/favorites/%s" % scholar_id)
    check("取消后状态为未收藏", data_of(payload).get("favorited") is False, "")

    # 模拟数据不该被收藏：收藏数会被当作真实的社会证明展示出去。
    sim = psql(
        "core_db",
        "core",
        "SELECT id FROM core_scholar WHERE data_nature = 'SIMULATED' AND merged_into_id IS NULL LIMIT 1;",
    )
    if not sim:
        skip("模拟数据不可收藏", "库中没有 SIMULATED 学者")
    else:
        code, payload = plain_user.post("/api/v1/account/favorites/%s" % sim[0])
        check("模拟数据学者不可收藏", code == 422, (code, payload))


# ----------------------------------------------------------------------------
# 6. 相关成果推荐
# ----------------------------------------------------------------------------


def section_related(anon):
    print("== 6 相关成果推荐 ==")
    rows = psql(
        "core_db",
        "core",
        """
        SELECT a.id || '|' || p.citation_count
        FROM core_achievement a
        JOIN core_paper p ON p.achievement_id = a.id
        WHERE a.visibility = 'PUBLIC' AND a.effective_status = 'EFFECTIVE'
          AND a.merged_into_id IS NULL AND a.data_nature = 'REAL'
          AND p.citation_count > 50 AND a.type = 'PAPER'
        ORDER BY p.citation_count DESC
        LIMIT 1;
        """,
    )
    if not rows:
        skip("相关成果推荐", "库中没有高被引公开成果")
    else:
        achievement_id, citations = rows[0].split("|")
        code, payload = anon.get("/api/v1/public/achievements/%s/related?limit=5" % achievement_id)
        data = data_of(payload)
        check("公开成果可读相关推荐", code == 200, code)
        check("返回结果不超过 limit", len(data.get("items") or []) <= 5, len(data.get("items") or []))
        check("给出打分方法说明", bool(data.get("method")), "")
        items = data.get("items") or []
        if items:
            check("每条都带理由", all(r.get("reasons") for r in items), len(items))
            check("每条分不低于门槛 6", all(int(r.get("score") or 0) >= 6 for r in items), [r.get("score") for r in items])
            check("结果不包含自身", all(r.get("achievementId") != achievement_id for r in items), "")
        else:
            check("空结果必须说明原因", bool(data.get("emptyReason")), data.get("emptyReason"))
        check("说明里写明抽样口径", "抽样" in (data.get("note") or ""), (data.get("note") or "")[:60])

    code, payload = anon.get("/api/v1/public/achievements/ach_not_exists_0000/related")
    check("不存在的成果返回 404", code == 404, (code, payload))


# ----------------------------------------------------------------------------
# 7. BibTeX / RIS 批量导入
# ----------------------------------------------------------------------------

BIBTEX_SAMPLE = r"""
@article{selftest2026a,
  author = {Zhang, Wei and Li, Na},
  title = {A Self-Check Paper on {Nested} Braces},
  journal = {Journal of Self Checks},
  year = {2026},
  volume = {1},
  doi = {10.9999/selftest.2026.a},
  abstract = {This entry exists to prove that nested braces in titles survive parsing.}
}

@patent{selftest2026p,
  author = {Zhang, Wei},
  title = {A Patent Which Self-Service Import Does Not Support},
  year = {2026}
}
"""

RIS_SAMPLE = """TY  - JOUR
AU  - Zhang, Wei
AU  - Li, Na
TI  - A Self-Check Paper in RIS Format
JO  - Journal of Self Checks
PY  - 2026
DO  - 10.9999/selftest.2026.b
ER  -
"""


def section_import(plain_user):
    print("== 7 BibTeX / RIS 批量导入 ==")
    anon = Session()
    code, _ = anon.post(
        "/api/v1/account/submissions/import", {"format": "AUTO", "content": BIBTEX_SAMPLE, "dryRun": True}
    )
    check("未登录导入被拒", code in (401, 403), code)

    code, payload = plain_user.post(
        "/api/v1/account/submissions/import",
        {"format": "AUTO", "content": BIBTEX_SAMPLE, "dryRun": True},
    )
    data = data_of(payload)
    check("BibTeX 识别为 BIBTEX", code == 200 and data.get("format") == "BIBTEX", (code, data.get("format")))
    check("解析出 2 条", data.get("parsed") == 2, data.get("parsed"))
    check("预览不写库", data.get("created") == 0 and data.get("dryRun") is True, (data.get("created"), data.get("dryRun")))
    items = data.get("items") or []
    preview = [r for r in items if r.get("status") == "PREVIEW"]
    skipped = [r for r in items if r.get("status") == "SKIPPED"]
    check("论文行是预览状态", len(preview) == 1, len(preview))
    check("专利行被跳过", len(skipped) == 1, len(skipped))
    if preview:
        # 嵌套花括号如果被正则截断，标题会静默残缺——这正是要断言的地方。
        # 保护大小写的花括号在纯文本里没有意义，解析时被剥掉，但字母一个都不能少。
        check(
            "嵌套花括号的标题完整",
            preview[0].get("title") == "A Self-Check Paper on Nested Braces",
            preview[0].get("title"),
        )

    code, payload = plain_user.post(
        "/api/v1/account/submissions/import",
        {"format": "RIS", "content": RIS_SAMPLE, "dryRun": True},
    )
    data = data_of(payload)
    check("RIS 解析出 1 条", code == 200 and data.get("parsed") == 1, (code, data.get("parsed")))
    ris_items = [r for r in (data.get("items") or []) if r.get("status") == "PREVIEW"]
    check("RIS 标题正确", bool(ris_items) and ris_items[0].get("title") == "A Self-Check Paper in RIS Format", ris_items)

    code, payload = plain_user.post(
        "/api/v1/account/submissions/import",
        {"format": "AUTO", "content": "这不是任何文献格式", "dryRun": True},
    )
    data = data_of(payload)
    check("无法识别的文本给出 0 条与说明", code == 200 and data.get("parsed") == 0 and bool(data.get("note")), (code, data.get("parsed")))

    # 真实导入：不指定 scholarId（成果提交不强制门户归属），导入后立刻撤回，不留待审队列。
    code, payload = plain_user.post(
        "/api/v1/account/submissions/import",
        {"format": "BIBTEX", "content": BIBTEX_SAMPLE, "dryRun": False},
    )
    data = data_of(payload)
    check("真实导入成功", code == 200 and data.get("created") == 1, (code, data.get("created"), data.get("failed")))
    created = [r for r in (data.get("items") or []) if r.get("status") in ("CREATED", "DUPLICATE_SUSPECTED")]
    if created:
        submission_id = created[0].get("submissionId")
        code, payload = plain_user.post("/api/v1/account/submissions/%s/withdraw" % submission_id)
        check("导入的提交件可撤回", code == 200, (code, payload))
    else:
        check("导入结果包含提交件标识", False, data.get("items"))


def main():
    anon = Session()
    code, _ = anon.get("/api/v1/notifications/kinds")
    check("未登录读通知类型被拒", code in (401, 403), code)

    admin = Session()
    code, _ = admin.login(ADMIN_USER, ADMIN_PASS)
    if code != 200:
        raise RuntimeError("管理员登录失败：%s" % code)

    fresh = Session()
    fresh.username, fresh.password = register(fresh, "ntf2_fresh")

    section_notifications(admin, fresh)
    section_announcement(admin, fresh)
    section_export(fresh)
    section_closure(admin, fresh)
    section_favorite(fresh, anon)
    section_related(anon)
    section_import(fresh)

    print()
    failed = [name for name, ok, _ in RESULTS if not ok]
    print("共 %d 项，通过 %d，失败 %d" % (len(RESULTS), len(RESULTS) - len(failed), len(failed)))
    for name in failed:
        print("  FAIL:", name)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
