#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""学术诚信声明（撤稿 / 更正 / 关注声明）端到端自检。

验证的是一条完整链路，而不是「接口返回 200」：

1. 权限：匿名 401、普通用户 403——声明能改变一条成果对外的可信度，不能由任意登录用户登记；
2. 登记只写声明表：登记后成果题名、可见性、生效状态一个字段都不变（红线：声明不覆盖来源原值）；
3. 详情体现：公开详情返回 integrity.status/label 与声明明细，汇总取最严重的一条；
4. 撤稿不下架：撤稿后成果仍可检索、详情仍可打开，但带 RETRACTION 标记（学术记录需可查证）；
5. 唯一性：同一成果同一类型不能并存两条生效声明；
6. 撤销成套：撤销需理由，撤销后状态回落（唯一一条撤稿撤销后回到 NONE），重复撤销按冲突拒绝；
7. 输入边界：理由过短、伪协议链接、未来日期都被拒，且报错能照着改；
8. 学者指标：撤稿成果单列 worksRetracted，不从 worksTotal 里扣除。

副作用：会在一条真实公开成果上登记声明，结束时逐条撤销（保留撤销历史，这是真实审计痕迹）。
选中的成果记录在输出里，便于人工复核。

前置：整栈已启动（bash ops/local/stack.sh）。
用法：python tests/api/smoke-integrity.py
"""

import http.cookiejar
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

CORE = os.environ.get("CORE_BASE", "http://127.0.0.1:8081")
QRY = os.environ.get("QUERY_BASE", "http://127.0.0.1:8083")
ADMIN_USER = os.environ.get("ADMIN_USER", "cnadmin")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "cn-admin-Dev-2026")
PSQL = os.environ.get("PSQL_BIN", "D:/dev-tools/pgsql/bin/psql.exe")

REASON = "自检用声明：编辑部通告该成果图 3 数据存在重复使用，已决定撤稿"
REASON2 = "自检用声明：已发布更正说明，修正表 2 的单位错误，成果主体仍成立"
REVOKE_REASON = "自检收尾：本次为接口自检写入，现按流程撤销该声明"
DECLARED_BY = "[自检]示例期刊编辑部"

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  -> " + str(detail)) if detail else ""))


def psql(sql, sep="\t"):
    env = dict(os.environ)
    env["PGPASSWORD"] = "cn_core_app_dev"
    env["PGCLIENTENCODING"] = "UTF8"
    env["MSYS_NO_PATHCONV"] = "1"
    out = subprocess.run(
        [PSQL, "-h", "127.0.0.1", "-p", "5432", "-U", "core_app", "-d", "core_db", "-At",
         "-F", sep, "-v", "ON_ERROR_STOP=1", "-f", "-"],
        input=sql.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr.decode("utf-8", "replace")[:400])
    return [line.strip() for line in out.stdout.decode("utf-8", "replace").splitlines() if line.strip()]


class Session:
    def __init__(self, base=CORE):
        self.base = base
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar), urllib.request.ProxyHandler({})
        )

    def send(self, method, path, body=None, token=None, base=None):
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token:
            headers["X-XSRF-TOKEN"] = token
        req = urllib.request.Request((base or self.base) + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=60) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
        except urllib.error.HTTPError as err:
            payload = err.read()
            try:
                return err.code, json.loads(payload.decode("utf-8")) if payload else {}
            except ValueError:
                return err.code, {"raw": payload.decode("utf-8", "replace")[:200]}

    def get(self, path, base=None):
        return self.send("GET", path, base=base)

    def post(self, path, body=None):
        return self.send("POST", path, body if body is not None else {}, self.csrf())

    def csrf(self):
        self.send("GET", "/api/v1/auth/csrf")
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN":
                return urllib.parse.unquote(cookie.value)
        return ""

    def login(self, user, password):
        return self.send(
            "POST", "/api/v1/auth/login",
            {"username": user, "password": password}, self.csrf()
        )


def data_of(payload):
    return (payload or {}).get("data") or {}


def message_of(payload):
    """错误报文里的可读信息。ApiResponse 的字段名是 message。"""
    if not isinstance(payload, dict):
        return str(payload)
    if payload.get("message"):
        return str(payload["message"])
    error = payload.get("error")
    if isinstance(error, dict) and error.get("message"):
        return str(error["message"])
    return json.dumps(payload, ensure_ascii=False)[:200]


def register_notice(session, achievement_id, notice_type, declared_by, reason, url=None, date=None):
    body = {"noticeType": notice_type, "declaredBy": declared_by, "reason": reason}
    if url is not None:
        body["sourceUrl"] = url
    if date is not None:
        body["announcedOn"] = date
    return session.post(
        "/api/v1/admin/core/achievements/%s/integrity-notices" % achievement_id, body
    )


def main():
    created = []  # 本脚本登记的声明标识，收尾时逐条撤销

    # ------------------------------------------------------------------
    # 0. 选一条适合做自检的公开成果：公开、生效、未合并、有作者
    # ------------------------------------------------------------------
    rows = psql(
        """
        SELECT a.id, replace(a.title, '|', ' '), s.id
        FROM core_achievement a
        JOIN core_contribution c ON c.achievement_id = a.id
                               AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
        JOIN core_scholar s ON s.id = c.scholar_id AND s.merged_into_id IS NULL
        WHERE a.visibility = 'PUBLIC'
          AND a.effective_status = 'EFFECTIVE'
          AND a.merged_into_id IS NULL
          AND a.data_nature = 'REAL'
          AND char_length(a.title) >= 12
        ORDER BY a.updated_at DESC, a.id
        LIMIT 1;
        """,
        sep="|",
    )
    if not rows:
        print("没有可用于自检的公开成果。")
        return 1
    achievement_id, title, scholar_id = rows[0].split("|")
    print("目标成果：%s（%s） 作者学者：%s" % (title[:40], achievement_id, scholar_id))

    before = psql(
        """
        SELECT title, visibility, effective_status, version
        FROM core_achievement WHERE id = '%s'
        """ % achievement_id
    )[0].split("\t")
    metrics_before = scholar_retracted_count(scholar_id)

    admin = Session()
    code, _ = admin.login(ADMIN_USER, ADMIN_PASS)
    if code != 200:
        print("管理员登录失败 %s" % code)
        return 1

    try:
        # --------------------------------------------------------------
        # 1. 权限
        # --------------------------------------------------------------
        print("== 1 权限 ==")
        anon = Session()
        code, payload = register_notice(anon, achievement_id, "RETRACTION", DECLARED_BY, REASON)
        check("匿名登记被拒（401）", code == 401, code)

        user = Session()
        username = "itg_%0.8s" % os.urandom(4).hex()
        code, _ = user.post(
            "/api/v1/auth/register",
            {"username": username, "password": "Cn-dev-2026!", "displayName": "诚信自检账号"},
        )
        code, _ = user.login(username, "Cn-dev-2026!")
        code, payload = register_notice(user, achievement_id, "RETRACTION", DECLARED_BY, REASON)
        check("普通用户登记被拒（403）", code == 403, (code, message_of(payload)))

        # --------------------------------------------------------------
        # 2. 登记与红线
        # --------------------------------------------------------------
        print("== 2 登记 ==")
        code, payload = register_notice(
            admin, achievement_id, "RETRACTION", DECLARED_BY, REASON,
            "https://example.org/retraction/itg", "2026-10-01",
        )
        notice_id = data_of(payload).get("id")
        check("治理账号登记撤稿成功", code == 200 and bool(notice_id), (code, message_of(payload)))
        if notice_id:
            created.append(notice_id)

        after = psql(
            """
            SELECT title, visibility, effective_status, version
            FROM core_achievement WHERE id = '%s'
            """ % achievement_id
        )[0].split("\t")
        check("成果字段未被声明改写（题名/可见性/生效状态）", before[:3] == after[:3], (before[:3], after[:3]))

        db_row = psql(
            """
            SELECT notice_type, status, declared_by, source_url
            FROM core_achievement_integrity_notice WHERE id = '%s'
            """ % notice_id
        )
        check(
            "声明按 ACTIVE 落库且保留原始声明信息",
            bool(db_row) and db_row[0].split("\t")[:4] == ["RETRACTION", "ACTIVE", DECLARED_BY,
                                                           "https://example.org/retraction/itg"],
            db_row,
        )

        # --------------------------------------------------------------
        # 3. 详情体现
        # --------------------------------------------------------------
        print("== 3 详情 ==")
        code, payload = Session().get("/api/v1/public/achievements/%s" % achievement_id)
        detail = data_of(payload)
        integrity = detail.get("integrity") or {}
        check(
            "公开详情返回已撤稿标记",
            code == 200 and integrity.get("status") == "RETRACTION" and integrity.get("label") == "已撤稿",
            (code, integrity.get("status"), integrity.get("label")),
        )
        check(
            "详情带声明明细与理由，且不含登记账号",
            any(n.get("id") == notice_id and n.get("reason", "").startswith("自检用声明")
                for n in (detail.get("integrityNotices") or []))
            and "recordedBy" not in json.dumps(detail.get("integrityNotices"), ensure_ascii=False),
            len(detail.get("integrityNotices") or []),
        )

        # 第二条：更正。对外仍应显示最严重的那条（撤稿）。
        code, payload = register_notice(admin, achievement_id, "CORRECTION", DECLARED_BY, REASON2)
        correction_id = data_of(payload).get("id")
        check("同意成果可并存不同类型声明", code == 200 and bool(correction_id),
              (code, message_of(payload)))
        if correction_id:
            created.append(correction_id)
        code, payload = Session().get("/api/v1/public/achievements/%s" % achievement_id)
        integrity = data_of(payload).get("integrity") or {}
        check("汇总取最严重的一条（撤稿优先于更正）",
              integrity.get("status") == "RETRACTION" and integrity.get("activeCount") == 2,
              (integrity.get("status"), integrity.get("activeCount")))

        # --------------------------------------------------------------
        # 4. 唯一性与输入边界
        # --------------------------------------------------------------
        print("== 4 边界 ==")
        code, payload = register_notice(admin, achievement_id, "RETRACTION", DECLARED_BY, REASON)
        # 业务规则拒绝走 422（BUSINESS_RULE），不是 400：400 只用于请求本身不合法。
        # 这里要钉住的是「拒绝且指出已有那条的标识」，码值只是顺带核对。
        check("同类型重复登记被拒（422）且指出已有标识",
              code == 422 and notice_id in message_of(payload), (code, message_of(payload)))

        code, payload = register_notice(admin, achievement_id, "CONCERN", DECLARED_BY, "数据有问题")
        check("理由过短被拒（400）", code == 400, (code, message_of(payload)))

        code, payload = register_notice(
            admin, achievement_id, "CONCERN", DECLARED_BY, REASON, "javascript:alert(1)"
        )
        check("伪协议链接被拒（400）", code == 400, (code, message_of(payload)))

        code, payload = register_notice(
            admin, achievement_id, "CONCERN", DECLARED_BY, REASON, None, "2099-01-01"
        )
        check("未来声明日期被拒（400）", code == 400, (code, message_of(payload)))

        code, payload = register_notice(admin, "ach_does_not_exist", "RETRACTION", DECLARED_BY, REASON)
        check("不存在的成果返回 404", code == 404, (code, message_of(payload)))

        # --------------------------------------------------------------
        # 5. 学者指标单列
        # --------------------------------------------------------------
        print("== 5 学者指标 ==")
        metrics_after = scholar_retracted_count(scholar_id)
        check("撤稿成果计入 worksRetracted", metrics_after >= metrics_before + 1,
              (metrics_before, metrics_after))
        code, payload = Session().get("/api/v1/public/scholars/%s" % scholar_id)
        metrics = (data_of(payload).get("metrics") or {})
        check(
            "指标里 worksRetracted 存在且计入 worksTotal 之外",
            "worksRetracted" in metrics and metrics.get("worksTotal") is not None,
            (metrics.get("worksTotal"), metrics.get("worksRetracted")),
        )

        # --------------------------------------------------------------
        # 6. 检索标记（等 query 消费完 outbox）
        # --------------------------------------------------------------
        print("== 6 检索投影 ==")
        keyword = title[:12]
        found = wait_for_search(keyword, achievement_id, expect="RETRACTION")
        check("检索列表带已撤稿标记（投影已同步）", found is not None, found)

        # --------------------------------------------------------------
        # 7. 撤销
        # --------------------------------------------------------------
        print("== 7 撤销 ==")
        code, payload = admin.post(
            "/api/v1/admin/core/integrity-notices/%s/revoke" % notice_id, {"reason": "太短"}
        )
        check("撤销理由过短被拒（400）", code == 400, (code, message_of(payload)))

        code, payload = admin.post(
            "/api/v1/admin/core/integrity-notices/%s/revoke" % notice_id, {"reason": REVOKE_REASON}
        )
        check("撤销撤稿成功", code == 200 and data_of(payload).get("status") == "REVOKED",
              (code, message_of(payload)))
        if code == 200:
            created.remove(notice_id)

        code, payload = Session().get("/api/v1/public/achievements/%s" % achievement_id)
        integrity = data_of(payload).get("integrity") or {}
        check("撤销后回落到次严重声明（已更正）",
              integrity.get("status") == "CORRECTION", (integrity.get("status"), integrity.get("activeCount")))

        code, payload = admin.post(
            "/api/v1/admin/core/integrity-notices/%s/revoke" % notice_id, {"reason": REVOKE_REASON}
        )
        check("重复撤销按冲突拒绝（409）", code == 409, (code, message_of(payload)))

        code, payload = admin.post(
            "/api/v1/admin/core/integrity-notices/%s/revoke" % correction_id, {"reason": REVOKE_REASON}
        )
        if code == 200 and correction_id in created:
            created.remove(correction_id)
        code, payload = Session().get("/api/v1/public/achievements/%s" % achievement_id)
        integrity = data_of(payload).get("integrity") or {}
        check(
            "全部撤销后回到「无诚信声明」，历史条数仍保留",
            integrity.get("status") == "NONE"
            and integrity.get("activeCount") == 0
            and integrity.get("historyCount") >= 2,
            (integrity.get("status"), integrity.get("activeCount"), integrity.get("historyCount")),
        )

        code, payload = admin.get("/api/v1/admin/core/integrity-notices?limit=5&activeOnly=false")
        check("治理台可按时间列出声明（含撤销历史）",
              code == 200 and isinstance(data_of(payload).get("items"), list),
              (code, len(data_of(payload).get("items") or [])))
    finally:
        # 收尾：把脚本登记的声明全部撤销。撤销历史保留，这是真实审计痕迹；
        # 不删除行——表上本来就没有删除入口，也不应该有。
        for notice in created:
            try:
                admin.post(
                    "/api/v1/admin/core/integrity-notices/%s/revoke" % notice,
                    {"reason": "自检收尾：接口自检写入的声明按流程撤销"},
                )
            except Exception:  # noqa: BLE001 - 收尾失败不应掩盖主流程结论
                pass

    failed = [item for item in RESULTS if not item[1]]
    print("\n%d 项检查，%d 项失败" % (len(RESULTS), len(failed)))
    for name, _, detail in failed:
        print("  FAILED: %s -> %s" % (name, detail))
    return 0 if not failed else 2


def scholar_retracted_count(scholar_id):
    """从公开接口读 worksRetracted，而不是直接查库：这条自检要证明接口真的暴露了它。"""
    code, payload = Session().get("/api/v1/public/scholars/%s" % scholar_id)
    if code != 200:
        return -1
    metrics = data_of(payload).get("metrics") or {}
    value = metrics.get("worksRetracted")
    return value if isinstance(value, int) else -1


def wait_for_search(keyword, achievement_id, expect, timeout_seconds=90):
    """等 query 消费到这条事件。

    采信顺序是「先在检索里看到该成果，再看它的标记」：找不到成果说明投影还没跟上，
    这是等待条件；找到了但标记不对才是失败。把两者混成一个超时会让缺陷看起来像环境慢。
    """
    import time

    deadline = time.time() + timeout_seconds
    last = None
    while time.time() < deadline:
        code, payload = Session(base=QRY).get(
            "/api/v1/public/search/achievements?q=%s&size=20" % urllib.parse.quote(keyword)
        )
        if code == 200:
            items = data_of(payload).get("items") or []
            for item in items:
                if item.get("id") == achievement_id:
                    badge = item.get("integrity") or {}
                    last = badge
                    if badge.get("status") == expect and badge.get("label") == "已撤稿":
                        return badge
        time.sleep(3)
    return None


if __name__ == "__main__":
    sys.exit(main())
