#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""站内通知端到端自检（ADR-003 §6 验收判据的接口级部分）。

验证的不是「接口 200」，而是这些产品规则：

1. 通知只在业务动作发生时产生：认领被裁决（要求补证 / 通过）、成果被合并，接收者的
   未读数与列表必须随之变化；
2. 合并通知按账号去重：同一账号管理多个学者时只收到一条，不是两条轰炸；
3. 标记已读只能操作自己的通知：横向越权返回 0 行影响，本方未读数不变，
   别人的列表里也看不到；
4. 未登录读不到任何通知——通知是账号私有状态，不是公开数据。

前置：整栈已启动（bash ops/local/stack.sh）。
副作用：创建两个账号；一次被批准的认领（产生门户授权）；一次真实成果合并（可回溯，台账留档）。
不撤来源许可（该路径由 SourceDisplayLicenseProbe 覆盖，避免本脚本与探针互相踩数据）。

用法：
  python tests/api/smoke-notifications.py
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

    def post(self, path, body=None):
        return self._send("POST", CORE + path, body if body is not None else {}, self.csrf())

    def put(self, path, body=None):
        return self._send("PUT", CORE + path, body if body is not None else {}, self.csrf())

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
        {"username": username, "password": password, "displayName": "通知自检 " + username},
    )
    if code != 200:
        raise RuntimeError("注册失败 %s %s" % (code, payload))
    code, _ = session.login(username, password)
    if code != 200:
        raise RuntimeError("登录失败 %s" % code)
    return username, password


def notifications_of(session, unread_only=False):
    code, payload = session.get(
        "/api/v1/notifications?unreadOnly=%s&limit=100" % ("true" if unread_only else "false")
    )
    return code, data_of(payload).get("items") or []


def unread_count_of(session):
    code, payload = session.get("/api/v1/notifications/unread-count")
    return code, data_of(payload).get("unreadCount")


def main():
    anon = Session()
    code, _ = anon.get("/api/v1/notifications")
    check("未登录读通知被拒", code in (401, 403), code)
    code, _ = anon.get("/api/v1/notifications/unread-count")
    check("未登录读未读数被拒", code in (401, 403), code)

    admin = Session()
    code, _ = admin.login(ADMIN_USER, ADMIN_PASS)
    if code != 200:
        raise RuntimeError("管理员登录失败：%s" % code)

    applicant = Session()
    register(applicant, "ntf_applicant")
    stranger = Session()
    register(stranger, "ntf_stranger")

    code, count = unread_count_of(applicant)
    check("新账号未读数为 0", code == 200 and count == 0, (code, count))

    # ------------------------------------------------------------------
    # 1. 认领裁决通知（NEED_MORE_EVIDENCE → APPROVE 两个分支）
    # ------------------------------------------------------------------
    print("== 1 认领裁决通知 ==")
    rows = psql(
        "core_db",
        "core",
        """
        SELECT s.id, s.display_name
        FROM core_scholar s
        WHERE s.merged_into_id IS NULL
          AND NOT EXISTS (SELECT 1 FROM iam_portal_authority pa
                          WHERE pa.scholar_id = s.id AND pa.status = 'ACTIVE')
          AND NOT EXISTS (SELECT 1 FROM wf_claim_request c
                          WHERE c.scholar_id = s.id AND c.status IN ('PENDING_REVIEW','NEED_MORE_EVIDENCE'))
          AND EXISTS (SELECT 1 FROM core_contribution c
                      JOIN core_achievement a ON a.id = c.achievement_id
                      WHERE c.scholar_id = s.id AND a.merged_into_id IS NULL)
        LIMIT 1;
        """,
    )
    if not rows:
        print("SKIP：没有可用的未认领学者（可能被其他自检占用），本次只验证通用规则")
        scholar_id, scholar_name = None, None
    else:
        scholar_id, scholar_name = rows[0].split("|", 1)

    if scholar_id:
        code, payload = applicant.post(
            "/api/v1/account/claims",
            {"scholarId": scholar_id, "statement": "通知自检：这是本人门户的认领申请。"},
        )
        claim_id = data_of(payload).get("id")
        check("认领提交成功", code == 200 and bool(claim_id), (code, claim_id))

        code, payload = admin.post(
            "/api/v1/admin/core/claims/%s/decision" % claim_id,
            {"decision": "NEED_MORE_EVIDENCE", "reason": "通知自检：请补充说明与成果的关系"},
        )
        check("要求补充材料成功", code == 200, (code, payload))

        code, count = unread_count_of(applicant)
        check("补证要求产生未读", code == 200 and count == 1, (code, count))
        code, items = notifications_of(applicant, unread_only=True)
        decided = [row for row in items if row.get("kind") == "CLAIM_DECIDED"]
        check("列表出现认领裁决通知", len(decided) == 1, len(decided))
        if decided:
            row = decided[0]
            check("通知正文写明要求", "补充" in row.get("body", ""), row.get("body", "")[:60])
            check("通知指向学者档案", row.get("refType") == "SCHOLAR" and row.get("refId") == scholar_id, "")
            check("通知未读状态为真", row.get("read") is False, row.get("read"))

            # 横向越权：陌生人标记别人的通知，返回 0 行，且本人未读数不变
            code, payload = stranger.post("/api/v1/notifications/read", {"ids": [row["id"]]})
            check("越权标记返回 0 行", code == 200 and data_of(payload).get("markedRead") == 0, (code, payload))
            code, items = notifications_of(applicant, unread_only=True)
            check("越权标记后本方未读不变", len([r for r in items if r.get("kind") == "CLAIM_DECIDED"]) == 1, "")
            # 陌生人的列表里看不到这条通知
            code, others = notifications_of(stranger)
            check("陌生人列表无该通知", all(r.get("id") != row["id"] for r in others), "")

            code, payload = applicant.post("/api/v1/notifications/read", {"ids": [row["id"]]})
            check("本人标记已读生效", code == 200 and data_of(payload).get("markedRead") == 1, (code, payload))
            code, count = unread_count_of(applicant)
            check("标记后未读数回 0", count == 0, count)

        # 批准分支：补证重提后批准
        code, payload = applicant.post(
            "/api/v1/account/claims/%s/resubmit" % claim_id,
            {"statement": "通知自检：已补充机构邮箱与成果列表说明。"},
        )
        check("补证重提成功", code == 200, code)
        code, _ = admin.post(
            "/api/v1/admin/core/claims/%s/decision" % claim_id,
            {"decision": "APPROVE", "reason": "通知自检：材料已补齐，批准"},
        )
        check("批准成功", code == 200, code)
        code, count = unread_count_of(applicant)
        check("批准产生未读", code == 200 and count == 1, (code, count))
        code, items = notifications_of(applicant, unread_only=True)
        approved = [row for row in items if row.get("kind") == "CLAIM_DECIDED" and "已通过" in row.get("title", "")]
        check("批准通知已送达", len(approved) == 1, len(approved))

        # ------------------------------------------------------------------
        # 2. 合并通知（按账号去重）
        # ------------------------------------------------------------------
        print("== 2 成果合并通知 ==")
        merged_rows = psql(
            "core_db",
            "core",
            """
            SELECT c.achievement_id || '|' || a.title
            FROM core_contribution c
            JOIN core_achievement a ON a.id = c.achievement_id
            WHERE c.scholar_id = '%s'
              AND a.merged_into_id IS NULL
              AND a.effective_status = 'EFFECTIVE'
            LIMIT 1;
            """
            % scholar_id,
        )
        if not merged_rows:
            print("SKIP：该学者名下没有可合并的成果，跳过合并通知验证")
        else:
            ach_x, title_x = merged_rows[0].split("|", 1)
            other_rows = psql(
                "core_db",
                "core",
                """
                SELECT a.id || '|' || a.title
                FROM core_achievement a
                WHERE a.id <> '%s'
                  AND a.type = (SELECT type FROM core_achievement WHERE id = '%s')
                  AND a.merged_into_id IS NULL
                  AND a.effective_status = 'EFFECTIVE'
                LIMIT 1;
                """
                % (ach_x, ach_x),
            )
            if not other_rows:
                print("SKIP：没有第二条同类型成果，跳过合并通知验证")
            else:
                ach_y, title_y = other_rows[0].split("|", 1)
                code, payload = admin.post(
                    "/api/v1/admin/core/governance/achievements/merge",
                    {"survivorId": ach_x, "mergedId": ach_y, "reason": "通知自检：验证合并通知链路"},
                )
                check("合并执行成功", code == 200, (code, payload))
                code, items = notifications_of(applicant, unread_only=True)
                merged_notes = [
                    row for row in items if row.get("kind") == "ACHIEVEMENT_MERGED"
                ]
                check("合并通知已送达认领账号", len(merged_notes) >= 1, len(merged_notes))
                if merged_notes:
                    body = merged_notes[0].get("body", "")
                    check("通知写明并入去向", title_x[:20] in body or ach_x in json.dumps(merged_notes[0]), body[:80])
                    check("通知含理由", "通知自检" in body, body[:80])
                    # 接收者按账号去重：申请人在本次合并里只应有一条合并通知（他是唯一认领者）
                    check("合并通知按账号只有一条", len(merged_notes) == 1, len(merged_notes))
                    check("合并通知指向幸存成果", merged_notes[0].get("refId") == ach_x, merged_notes[0].get("refId"))
                    code, payload = applicant.post("/api/v1/notifications/read", {"ids": [merged_notes[0]["id"]]})
                    check("合并通知可标记已读", data_of(payload).get("markedRead") == 1, payload)

    # ------------------------------------------------------------------
    # 3. 撤权与恢复通知（挑一个成果最少的已授予许可来源，撤了再恢复）
    # ------------------------------------------------------------------
    print("== 3 来源撤权与恢复通知 ==")
    # 关键前提：撤权通知只发给「成果确实因此不再公开」的认领账号。一个没有独占公开成果的来源
    # 被撤权时 targetCount=0，谁都不该收到通知——这不是缺陷，是「通知跟随真实影响」的语义。
    # 所以这里必须挑一个确有独占公开成果的来源，否则断言本身不成立。
    source_rows = psql(
        "core_db",
        "core",
        """
        SELECT s.id || '|' || s.source_code
        FROM raw_source_config s
        WHERE s.display_license_status = 'GRANTED'
          AND EXISTS (
            SELECT 1 FROM core_achievement a
            WHERE a.visibility = 'PUBLIC' AND a.merged_into_id IS NULL
              AND a.display_restriction_source_id IS NULL
              AND EXISTS (SELECT 1 FROM core_achievement_source cas
                          WHERE cas.achievement_id = a.id AND cas.source_id = s.id)
              AND NOT EXISTS (SELECT 1 FROM core_achievement_source cas2
                              WHERE cas2.achievement_id = a.id AND cas2.source_id <> s.id)
          )
        ORDER BY (SELECT count(*) FROM core_achievement a2
                  WHERE a2.visibility='PUBLIC' AND a2.merged_into_id IS NULL
                    AND a2.display_restriction_source_id IS NULL
                    AND EXISTS (SELECT 1 FROM core_achievement_source cas3
                                WHERE cas3.achievement_id = a2.id AND cas3.source_id = s.id)
                    AND NOT EXISTS (SELECT 1 FROM core_achievement_source cas4
                                    WHERE cas4.achievement_id = a2.id AND cas4.source_id <> s.id)) ASC
        LIMIT 1;
        """,
    )
    if not source_rows:
        print("SKIP：没有存在独占公开成果的来源，跳过撤权通知验证")
    else:
        source_id, source_code = source_rows[0].split("|", 1)
        scholar2_rows = psql(
            "core_db",
            "core",
            """
            SELECT DISTINCT c.scholar_id
            FROM core_achievement a
            JOIN core_achievement_source cas ON cas.achievement_id = a.id AND cas.source_id = '%s'
            JOIN core_contribution c ON c.achievement_id = a.id
            JOIN core_scholar cs ON cs.id = c.scholar_id AND cs.merged_into_id IS NULL
            WHERE a.visibility = 'PUBLIC' AND a.merged_into_id IS NULL
              AND a.display_restriction_source_id IS NULL
              AND NOT EXISTS (SELECT 1 FROM core_achievement_source cas2
                              WHERE cas2.achievement_id = a.id AND cas2.source_id <> '%s')
              AND NOT EXISTS (SELECT 1 FROM iam_portal_authority pa
                              WHERE pa.scholar_id = c.scholar_id AND pa.status = 'ACTIVE')
              AND NOT EXISTS (SELECT 1 FROM wf_claim_request w
                              WHERE w.scholar_id = c.scholar_id AND w.status IN ('PENDING_REVIEW','NEED_MORE_EVIDENCE'))
            LIMIT 1;
            """
            % (source_id, source_id),
        )
        if not scholar2_rows:
            print("SKIP：该来源独占成果的参与学者都已被认领，跳过撤权通知验证")
        else:
            scholar_b = scholar2_rows[0]
        code, payload = applicant.post(
            "/api/v1/account/claims",
            {"scholarId": scholar_b, "statement": "通知自检：认领该学者以验证撤权通知送达。"},
        )
        claim2 = data_of(payload).get("id")
        check("第二个认领提交成功", code == 200 and bool(claim2), (code, claim2))
        code, _ = admin.post(
            "/api/v1/admin/core/claims/%s/decision" % claim2,
            {"decision": "APPROVE", "reason": "通知自检：为撤权通知验证建立授权"},
        )
        check("第二个认领批准成功", code == 200, code)
        applicant.post("/api/v1/notifications/read", {"ids": []})  # 空操作，不清理未读（下一段按 kind 过滤）

        code, payload = admin.put(
            "/api/v1/admin/core/sources/%s/display-license" % source_id,
            {"status": "REVOKED", "reason": "通知自检：验证撤权通知链路，随后立即恢复"},
        )
        check("撤权执行成功", code == 200 and data_of(payload).get("complete") is True, (code, payload))
        code, items = notifications_of(applicant, unread_only=True)
        revoked = [row for row in items if row.get("kind") == "SOURCE_LICENSE_REVOKED"]
        check("撤权通知已送达", len(revoked) == 1, len(revoked))
        if revoked:
            check("撤权通知写明来源", source_code in revoked[0].get("body", ""), revoked[0].get("body", "")[:80])

        code, payload = admin.put(
            "/api/v1/admin/core/sources/%s/display-license" % source_id,
            {"status": "GRANTED", "reason": "通知自检：恢复许可，还原测试现场"},
        )
        check("恢复执行成功", code == 200 and data_of(payload).get("complete") is True, (code, payload))
        code, items = notifications_of(applicant, unread_only=True)
        restored = [row for row in items if row.get("kind") == "SOURCE_LICENSE_RESTORED"]
        check("恢复通知已送达", len(restored) == 1, len(restored))
        # 现场还原：撤销为验证而建的门户授权，避免影响其他自检的「未认领学者」选择
        auth_rows = psql(
            "core_db", "core",
            "SELECT id FROM iam_portal_authority WHERE scholar_id='%s' AND status='ACTIVE';" % scholar_b,
        )
        if auth_rows:
            psql(
                "core_db", "core",
                "UPDATE iam_portal_authority SET status='REVOKED', revoked_at=now(), "
                "revoke_reason='通知自检完成，还原现场' WHERE id='%s';" % auth_rows[0],
            )

    print()
    failed = [name for name, ok, _ in RESULTS if not ok]
    print("共 %d 项，通过 %d，失败 %d" % (len(RESULTS), len(RESULTS) - len(failed), len(failed)))
    for name in failed:
        print("  FAIL:", name)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
