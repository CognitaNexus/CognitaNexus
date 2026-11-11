#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""成果引用导出端到端自检（BibTeX / RIS / GB-T 7714 / APA）。

验证的是「引用能不能被真的拿去用」，而不是「接口返回 200」：

1. 四种格式一次给全，默认格式由服务端给出，引用键只含 ASCII 安全字符；
2. 每种格式都带上题名与作者，且各自的语法骨架正确（@article 花括号平衡、RIS 逐行标签
   且以 ER 结尾、国标带文献类型标识、APA 带 (年份) 与 DOI 链接）；
3. 下载入口逐字节等于 JSON 里的文本，附件头、媒体类型、文件名扩展名都对得上；
4. 错误路径也是 JSON：非法格式、缺格式、不存在的成果——错误报文不因 Accept 声明而变形；
5. 撤稿前后：登记撤稿声明后引用端点仍然返回 200（用户引用过的文献不能因为撤稿就打不开），
   但文本与告警都带上撤稿事实；撤销后标记回落。

不变量：本脚本不改任何成果字段，只在一条真实成果上临时登记一条撤稿声明，结束时撤销。

前置：整栈已启动（bash ops/local/stack.sh）。
用法：python tests/api/smoke-citations.py
"""

import http.cookiejar
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

CORE = os.environ.get("CORE_BASE", "http://127.0.0.1:8081")
ADMIN_USER = os.environ.get("ADMIN_USER", "cnadmin")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "cn-admin-Dev-2026")
PSQL = os.environ.get("PSQL_BIN", "D:/dev-tools/pgsql/bin/psql.exe")

REASON = "自检用声明：编辑部通告该成果存在数据重复使用，已决定撤稿"
REVOKE_REASON = "自检收尾：本次为引用导出自检写入，现按流程撤销该声明"
DECLARED_BY = "[自检]示例期刊编辑部"

KNOWN_WARNING_CODES = {
    "YEAR_UNKNOWN",
    "AUTHOR_ORDER_UNKNOWN",
    "NO_AUTHOR",
    "AUTHOR_PENDING_VERIFY",
    "EXCLUDED_DROPPED",
    "AUTHOR_NAME_UNRESOLVED",
    "VENUE_MISSING",
    "RETRACTED",
    "CORRECTED",
    "CONCERNED",
    "DATA_NATURE_NOT_REAL",
    "NAME_SPLIT_NOT_APPLIED",
}

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

    def send(self, method, path, body=None, token=None, accept="application/json"):
        data = None
        headers = {"Accept": accept}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token:
            headers["X-XSRF-TOKEN"] = token
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=60) as resp:
                raw = resp.read()
                return resp.status, raw, dict(resp.headers)
        except urllib.error.HTTPError as err:
            return err.code, err.read(), dict(err.headers or {})

    def json(self, method, path, body=None, token=None, accept="application/json"):
        code, raw, headers = self.send(method, path, body, token, accept)
        try:
            return code, json.loads(raw.decode("utf-8") or "{}"), raw, headers
        except ValueError:
            return code, {"raw": raw.decode("utf-8", "replace")[:300]}, raw, headers

    def post(self, path, body=None):
        return self.json("POST", path, body if body is not None else {}, self.csrf())

    def csrf(self):
        self.send("GET", "/api/v1/auth/csrf")
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN":
                return urllib.parse.unquote(cookie.value)
        return ""

    def login(self, user, password):
        code, payload, _, _ = self.json(
            "POST", "/api/v1/auth/login", {"username": user, "password": password}, self.csrf()
        )
        return code, payload


def data_of(payload):
    return (payload or {}).get("data") or {}


def message_of(payload):
    if not isinstance(payload, dict):
        return str(payload)
    if payload.get("message"):
        return str(payload["message"])
    return json.dumps(payload, ensure_ascii=False)[:200]


def codes_of(warnings):
    return [w.get("code") for w in (warnings or [])]


def formats_of(bundle):
    return {item.get("code"): item for item in (bundle.get("formats") or [])}


def brace_balance(text):
    return text.count("{") - text.count("}")


def main():
    created = []

    # ------------------------------------------------------------------
    # 0. 选一条论文型公开成果：有 DOI、有 >=2 位已确认作者、有刊名
    # ------------------------------------------------------------------
    rows = psql(
        """
        SELECT a.id,
               replace(a.title, '|', ' '),
               coalesce(p.doi, ''),
               coalesce(p.venue, ''),
               (SELECT count(*) FROM core_contribution c
                 WHERE c.achievement_id = a.id
                   AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
                   AND c.author_order IS NOT NULL),
               (SELECT count(*) FROM core_contribution c
                 WHERE c.achievement_id = a.id
                   AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')),
               coalesce(a.publication_year::text, '')
        FROM core_achievement a
        JOIN core_paper p ON p.achievement_id = a.id
        WHERE a.type = 'PAPER'
          AND a.visibility = 'PUBLIC'
          AND a.effective_status = 'EFFECTIVE'
          AND a.merged_into_id IS NULL
          AND a.data_nature = 'REAL'
          AND p.doi IS NOT NULL
          AND p.venue IS NOT NULL
          AND char_length(a.title) >= 12
          AND (SELECT count(*) FROM core_contribution c
                WHERE c.achievement_id = a.id
                  AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')) >= 2
        ORDER BY a.updated_at DESC, a.id
        LIMIT 1;
        """,
        sep="|",
    )
    if not rows:
        print("没有可用于自检的公开论文成果（需要 DOI、刊名与至少 2 位已确认作者）。")
        return 1
    achievement_id, title, doi, venue, ordered_count, author_count, year = rows[0].split("|")
    print("目标成果：%s（%s） DOI=%s 作者=%s 有序作者=%s" % (
        title[:36], achievement_id, doi, author_count, ordered_count))

    author_names = psql(
        """
        SELECT s.display_name
        FROM core_contribution c
        JOIN core_scholar s ON s.id = c.scholar_id
        WHERE c.achievement_id = '%s'
          AND c.relation_state <> 'EXCLUDED'
        ORDER BY c.author_order NULLS LAST, c.created_at, s.id;
        """ % achievement_id
    )
    first_author = author_names[0] if author_names else ""

    anon = Session()

    # ------------------------------------------------------------------
    # 1. 四种格式一次给全
    # ------------------------------------------------------------------
    print("== 1 全部格式 ==")
    code, payload, _, _ = anon.json("GET", "/api/v1/public/achievements/%s/citations" % achievement_id)
    bundle = data_of(payload)
    check("引用汇总返回 200", code == 200, (code, message_of(payload)))
    formats = formats_of(bundle)
    check(
        "四种格式齐全且顺序固定",
        list(formats.keys()) == ["bibtex", "ris", "gb_t_7714", "apa"],
        list(formats.keys()),
    )
    check("默认格式为国标", bundle.get("defaultFormat") == "gb_t_7714", bundle.get("defaultFormat"))
    check(
        "出处与生成时刻作为独立字段给出",
        bool(bundle.get("provenance")) and bool(re.fullmatch(r"\d{4}-\d{2}-\d{2}", bundle.get("exportedOn") or "")),
        (bundle.get("provenance"), bundle.get("exportedOn")),
    )
    check(
        "引用键只含 ASCII 安全字符",
        bool(re.fullmatch(r"[a-z0-9_]+", bundle.get("citeKey") or "")),
        bundle.get("citeKey"),
    )
    all_warning_codes = set(codes_of(bundle.get("warnings")))
    for item in (bundle.get("formats") or []):
        all_warning_codes |= set(codes_of(item.get("warnings")))
    check(
        "所有告警码都在已知集合内（无拼写漂移）",
        all_warning_codes <= KNOWN_WARNING_CODES,
        sorted(all_warning_codes - KNOWN_WARNING_CODES),
    )

    texts = {code_: (formats.get(code_) or {}).get("text") or "" for code_ in formats}
    for code_ in formats:
        check("格式 %s 文本非空" % code_, bool(texts[code_].strip()), len(texts[code_]))
    check(
        "引用正文不含平台出处与生成时刻（非书目信息不进参考文献表）",
        not any("CognitaNexus" in text or "导出于" in text for text in texts.values()),
        [code_ for code_, text in texts.items() if "CognitaNexus" in text or "导出于" in text],
    )

    # ------------------------------------------------------------------
    # 2. 各格式的语法骨架
    # ------------------------------------------------------------------
    print("== 2 格式骨架 ==")
    bib = texts["bibtex"]
    check("BibTeX 用 @article 承载论文", bib.startswith("@article{"), bib[:20])
    check("BibTeX 花括号平衡", brace_balance(bib) == 0, brace_balance(bib))
    check("BibTeX 含 author/title/journal/year/doi 五个字段",
          all(("  %s = {" % f) in bib for f in ("author", "title", "journal", "year", "doi")),
          [f for f in ("author", "title", "journal", "year", "doi") if ("  %s = {" % f) not in bib])
    check("BibTeX 多作者以 and 连接", " and " in bib or author_count == "1", bib.split("\n")[1][:60])

    ris = texts["ris"]
    ris_lines = [line for line in ris.split("\n") if line]
    check("RIS 首行为 TY  - JOUR", ris_lines and ris_lines[0].startswith("TY  - JOUR"), ris_lines[:1])
    check("RIS 末行为 ER  - ", ris_lines and ris_lines[-1].startswith("ER  - "), ris_lines[-1:])
    check("RIS 每行都是两空格加连字符的标签格式",
          all(re.match(r"^[A-Z][A-Z0-9]  - ", line) for line in ris_lines),
          [line[:12] for line in ris_lines if not re.match(r"^[A-Z][A-Z0-9]  - ", line)])
    check("RIS 含 AU / TI / PY 三个必备标签",
          all(any(line.startswith(tag) for line in ris_lines) for tag in ("AU  - ", "TI  - ", "PY  - ")))

    gb = texts["gb_t_7714"]
    check("国标文本以第一作者开头", gb.startswith(first_author + (", " if author_count != "1" else ". ")),
          (gb[:24], first_author))
    check("国标带期刊类型标识 [J]", "[J]." in gb, gb[:60])
    check("国标含 DOI 与刊名", doi in gb and venue in gb, gb[-70:])
    check("国标以句点结束", gb.endswith("."), gb[-10:])

    apa = texts["apa"]
    check("APA 以 (年份) 开头作者段", ("(%s)." % year) in apa if year else True, apa[:60])
    check("APA 输出可点击的 DOI 链接", ("https://doi.org/" + doi) in apa, apa[-60:])

    # ------------------------------------------------------------------
    # 3. 下载入口
    # ------------------------------------------------------------------
    print("== 3 下载 ==")
    code, raw, headers = anon.send("GET", "/api/v1/public/achievements/%s/citation?format=bibtex"
                                   % achievement_id, accept="application/x-bibtex")
    body = raw.decode("utf-8")
    check("下载返回 200", code == 200, code)
    check("下载正文与 JSON 文本逐字一致", body == bib, (len(body), len(bib)))
    check("附件头文件名用引用键",
          ('filename="%s.bib"' % bundle.get("citeKey")) in (headers.get("Content-Disposition") or ""),
          headers.get("Content-Disposition"))
    check("媒体类型为 application/x-bibtex",
          "application/x-bibtex" in (headers.get("Content-Type") or ""), headers.get("Content-Type"))

    code, raw, headers = anon.send("GET", "/api/v1/public/achievements/%s/citation?format=gb-t-7714"
                                   % achievement_id, accept="text/plain")
    check("国标可按别名 gb-t-7714 下载且为 text/plain",
          code == 200 and "text/plain" in (headers.get("Content-Type") or "")
          and raw.decode("utf-8") == gb,
          (code, headers.get("Content-Type")))

    # ------------------------------------------------------------------
    # 4. 错误路径也是 JSON（错误报文不随 Accept 变形）
    # ------------------------------------------------------------------
    print("== 4 错误路径 ==")
    code, payload, _, _ = anon.json("GET", "/api/v1/public/achievements/%s/citation?format=bib"
                                    % achievement_id, accept="text/plain")
    check("非法格式被拒为 400 且报文仍是 JSON",
          code == 400 and isinstance(payload, dict) and "不支持的引用格式" in message_of(payload),
          (code, message_of(payload)))
    code, payload, _, _ = anon.json("GET", "/api/v1/public/achievements/%s/citation" % achievement_id)
    check("缺 format 被拒为 400", code == 400 and "引用格式不能为空" in message_of(payload),
          (code, message_of(payload)))
    code, payload, _, _ = anon.json("GET", "/api/v1/public/achievements/ach_not_exists/citations")
    check("不存在的成果返回 404 且不泄露存在性以外的信息",
          code == 404 and "成果不存在" in message_of(payload), (code, message_of(payload)))

    # ------------------------------------------------------------------
    # 5. 撤稿前后
    # ------------------------------------------------------------------
    print("== 5 撤稿前后 ==")
    admin = Session()
    code, _ = admin.login(ADMIN_USER, ADMIN_PASS)
    if code != 200:
        print("管理员登录失败 %s，跳过撤稿段落" % code)
        return 1
    try:
        code, payload, _, _ = admin.post(
            "/api/v1/admin/core/achievements/%s/integrity-notices" % achievement_id,
            {"noticeType": "RETRACTION", "declaredBy": DECLARED_BY, "reason": REASON},
        )
        notice_id = data_of(payload).get("id")
        check("登记撤稿声明成功（自检夹具）", code == 200 and bool(notice_id),
              (code, message_of(payload)))
        if notice_id:
            created.append(notice_id)

        code, payload, _, _ = anon.json(
            "GET", "/api/v1/public/achievements/%s/citations" % achievement_id)
        after = data_of(payload)
        after_formats = formats_of(after)
        check("撤稿后引用端点仍可访问（撤稿不下架）", code == 200, code)
        check("撤稿进入元数据告警", "RETRACTED" in codes_of(after.get("warnings")),
              codes_of(after.get("warnings")))
        check("撤稿进入国标文本",
              "撤稿" in ((after_formats.get("gb_t_7714") or {}).get("text") or ""),
              ((after_formats.get("gb_t_7714") or {}).get("text") or "")[-60:])
        check("撤稿进入 BibTeX note 字段",
              "note = {" in ((after_formats.get("bibtex") or {}).get("text") or ""),
              ((after_formats.get("bibtex") or {}).get("text") or "")[-80:])
        check("成果题名未被声明改动", title in ((after_formats.get("bibtex") or {}).get("text") or ""))

        # 指纹：撤销之后必须回落，否则「撤销」等于没做
        code, payload, _, _ = admin.post(
            "/api/v1/admin/core/integrity-notices/%s/revoke" % notice_id,
            {"reason": REVOKE_REASON},
        )
        check("撤销声明成功", code == 200, (code, message_of(payload)))
        if notice_id in created:
            created.remove(notice_id)

        code, payload, _, _ = anon.json(
            "GET", "/api/v1/public/achievements/%s/citations" % achievement_id)
        reverted = data_of(payload)
        check("撤销后撤稿告警回落",
              "RETRACTED" not in codes_of(reverted.get("warnings")),
              codes_of(reverted.get("warnings")))
        check("撤销后引用键不变（引用键不随声明漂移）",
              reverted.get("citeKey") == bundle.get("citeKey"),
              (reverted.get("citeKey"), bundle.get("citeKey")))
    finally:
        for notice in created:
            code, payload, _, _ = admin.post(
                "/api/v1/admin/core/integrity-notices/%s/revoke" % notice,
                {"reason": REVOKE_REASON},
            )
            print("  收尾撤销 %s -> %s" % (notice, code))

    # ------------------------------------------------------------------
    # 6. 汇总
    # ------------------------------------------------------------------
    failed = [name for name, ok, _ in RESULTS if not ok]
    print("\n%d 项检查：%d 通过，%d 失败" % (len(RESULTS), len(RESULTS) - len(failed), len(failed)))
    if failed:
        for name in failed:
            print("  FAILED: " + name)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
