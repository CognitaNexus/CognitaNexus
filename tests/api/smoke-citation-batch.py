#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""批量引用导出端到端自检（多选成果 → 一份 .bib / .ris / 国标 / APA 文件）。

验证的是「这份文件能不能真的被导入文献管理软件」，而不是「接口返回 200」：

1. 条目数与选中数一致，同一文件内引用键互不相同；
2. **逐条与单条端点逐字一致**：批量入口不得另写一套渲染，否则两处文本会漂移成
   「页面上复制的」与「文件里的」两种引用；
3. 四个格式的成篇方式：BibTeX 条目之间空行、RIS 每条以 ER 结束、国标逐条编号、APA 逐段不编号；
4. 预览 JSON 里的 text 与下载到的字节逐字一致（先看再下不能是两回事）；
5. 不可公开的成果被跳过并报数，且「不存在」与「不可公开」的原因**不可区分**（不泄露存在性）；
6. 合并过的成果会重定向到幸存实体：同时选中「原记录」与「幸存记录」时只写一条，原记录记为
   `DUPLICATE_TARGET`（与「不可公开」是两个原因）；
7. 一条都导不出来时明确失败，不交空文件；缺 ids、非法格式、超过上限分别 400 / 400 / 422；
8. 重复选中去重、顺序保持读者选中顺序。

不变量：本脚本只读，不写任何数据库行。两个入口都是 GET（预览不消耗一次性 CSRF 令牌）。

前置：整栈已启动（bash ops/local/stack.sh）。
用法：python tests/api/smoke-citation-batch.py
"""

import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

CORE = os.environ.get("CORE_BASE", "http://127.0.0.1:8081")
PSQL = os.environ.get("PSQL_BIN", "D:/dev-tools/pgsql/bin/psql.exe")

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
    "CITE_KEY_DISAMBIGUATED",
}

MAX_BATCH = 100

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


class Anon:
    def __init__(self, base=CORE):
        self.base = base
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def send(self, method, path, body=None, accept="application/json"):
        data = None
        headers = {"Accept": accept}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=120) as resp:
                return resp.status, resp.read(), dict(resp.headers)
        except urllib.error.HTTPError as err:
            return err.code, err.read(), dict(err.headers or {})

    def json(self, method, path, body=None, accept="application/json"):
        code, raw, headers = self.send(method, path, body, accept)
        try:
            return code, json.loads(raw.decode("utf-8") or "{}"), raw, headers
        except ValueError:
            return code, {"raw": raw.decode("utf-8", "replace")[:300]}, raw, headers

    def batch(self, ids, fmt):
        query = "format=" + urllib.parse.quote(fmt)
        for one in (ids or []):
            query += "&ids=" + urllib.parse.quote(one)
        return self.json("GET", "/api/v1/public/citations/batch?" + query)

    def download(self, ids, fmt):
        query = "format=" + urllib.parse.quote(fmt)
        for one in ids:
            query += "&ids=" + urllib.parse.quote(one)
        return self.send("GET", "/api/v1/public/citations/batch/download?" + query,
                         accept="application/x-bibtex")


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


def brace_balance(text):
    return text.count("{") - text.count("}")


def main():
    # ------------------------------------------------------------------
    # 0. 选三条互不相同的公开论文成果，外加一条「存在但不可公开」的
    # ------------------------------------------------------------------
    rows = psql(
        """
        SELECT a.id, replace(a.title, '|', ' ')
        FROM core_achievement a
        JOIN core_paper p ON p.achievement_id = a.id
        WHERE a.type = 'PAPER'
          AND a.visibility = 'PUBLIC'
          AND a.effective_status = 'EFFECTIVE'
          AND a.merged_into_id IS NULL
          AND a.data_nature = 'REAL'
          AND p.doi IS NOT NULL
          AND p.venue IS NOT NULL
          AND char_length(a.title) >= 10
          AND (SELECT count(*) FROM core_contribution c
                WHERE c.achievement_id = a.id AND c.relation_state <> 'EXCLUDED') >= 1
        ORDER BY a.updated_at DESC, a.id
        LIMIT 3;
        """,
        sep="|",
    )
    if len(rows) < 3:
        print("可用的公开论文成果不足 3 条（需要 DOI、刊名与至少 1 位署名者）。")
        return 1
    picked = [line.split("|") for line in rows]
    id_a, id_b, id_c = picked[0][0], picked[1][0], picked[2][0]
    print("选中成果：%s / %s / %s" % (id_a, id_b, id_c))

    hidden = psql(
        """
        SELECT id FROM core_achievement
        WHERE merged_into_id IS NULL
          AND (visibility <> 'PUBLIC' OR effective_status <> 'EFFECTIVE')
        ORDER BY updated_at DESC, id
        LIMIT 1;
        """
    )
    hidden_id = hidden[0] if hidden else None
    print("不可公开的既有成果：%s" % (hidden_id or "（库中没有，改用不存在的 id 对照）"))

    # 合并过的成果会重定向到幸存实体：同时选中「原记录」与「幸存记录」时不能写两条相同引用。
    merged = psql(
        """
        SELECT a.id, a.merged_into_id
        FROM core_achievement a
        JOIN core_achievement t ON t.id = a.merged_into_id
        WHERE t.visibility = 'PUBLIC'
          AND t.effective_status = 'EFFECTIVE'
          AND t.merged_into_id IS NULL
        ORDER BY a.updated_at DESC, a.id
        LIMIT 1;
        """,
        sep="|",
    )
    merged_id, merged_target = (merged[0].split("|") + [""])[:2] if merged else ("", "")
    print("合并重定向样本：%s -> %s" % (merged_id or "（库中没有）", merged_target or "-"))

    anon = Anon()

    # ------------------------------------------------------------------
    # 1. 三条一次导出
    # ------------------------------------------------------------------
    print("== 1 三条批量导出 ==")
    code, payload, _, _ = anon.batch([id_a, id_b, id_c], "bibtex")
    batch = data_of(payload)
    check("批量导出返回 200", code == 200, (code, message_of(payload)))
    check("请求条数 = 导出条数 + 跳过条数",
          batch.get("requested") == len(batch.get("entries") or []) + len(batch.get("skipped") or []),
          (batch.get("requested"), len(batch.get("entries") or []), len(batch.get("skipped") or [])))
    check("三条全部写入文件", batch.get("exported") == 3, batch.get("exported"))
    check("没有跳过项", batch.get("skippedCount") == 0, batch.get("skipped"))
    keys = [entry.get("citeKey") for entry in (batch.get("entries") or [])]
    check("同一文件内引用键互不相同", len(keys) == len(set(keys)), keys)
    check("引用键只含 ASCII 安全字符",
          all(re.fullmatch(r"[a-z0-9_]+", key or "") for key in keys), keys)
    text = batch.get("text") or ""
    check("文件正文非空", bool(text.strip()), len(text))
    check("BibTeX 文件花括号平衡", brace_balance(text) == 0, brace_balance(text))
    check("文件含三条条目（三个 @ 头）", text.count("@article{") == 3, text.count("@article{"))
    check("每条键都真的写进了文件", all(("{" + key + ",") in text for key in keys), keys)
    check("引用正文不含平台出处（非书目信息不进文件）", "CognitaNexus" not in text)
    check("文件名带格式与日期",
          re.fullmatch(r"citations-bibtex-\d{4}-\d{2}-\d{2}\.bib", batch.get("fileName") or "") is not None,
          batch.get("fileName"))

    all_codes = set(codes_of(batch.get("warnings")))
    for entry in (batch.get("entries") or []):
        all_codes |= set(codes_of(entry.get("warnings")))
    check("所有告警码都在已知集合内（无拼写漂移）",
          all_codes <= KNOWN_WARNING_CODES, sorted(all_codes - KNOWN_WARNING_CODES))

    # ------------------------------------------------------------------
    # 2. 逐条与单条端点逐字一致
    # ------------------------------------------------------------------
    print("== 2 与单条端点一致 ==")
    for entry in (batch.get("entries") or []):
        code, raw, _ = anon.send(
            "GET", "/api/v1/public/achievements/%s/citation?format=bibtex" % entry.get("achievementId"),
            accept="application/x-bibtex")
        single = raw.decode("utf-8")
        check("条目 %s 与单条下载逐字一致" % entry.get("citeKey"),
              code == 200 and single == (entry.get("text") or ""),
              (code, len(single), len(entry.get("text") or "")))

    # ------------------------------------------------------------------
    # 3. 四个格式的成篇方式
    # ------------------------------------------------------------------
    print("== 3 四个格式成篇 ==")
    code, payload, _, _ = anon.batch([id_a, id_b, id_c], "gb_t_7714")
    gb = data_of(payload)
    gb_text = gb.get("text") or ""
    check("国标文件逐条编号 [1][2][3]",
          gb_text.startswith("[1] ") and "\n[2] " in gb_text and "\n[3] " in gb_text,
          gb_text[:30])
    check("国标条目本身不带编号（编号属于文件里的位置）",
          all(not (entry.get("text") or "").startswith("[1]") for entry in (gb.get("entries") or [])),
          [(entry.get("text") or "")[:8] for entry in (gb.get("entries") or [])])

    code, payload, _, _ = anon.batch([id_a, id_b, id_c], "apa")
    apa_text = data_of(payload).get("text") or ""
    check("APA 文件不编号", "[1]" not in apa_text, apa_text[:20])
    check("APA 段落之间空行", "\n\n" in apa_text, apa_text.count("\n\n"))

    code, payload, _, _ = anon.batch([id_a, id_b, id_c], "ris")
    ris_text = data_of(payload).get("text") or ""
    ris_lines = [line for line in ris_text.split("\n") if line]
    check("RIS 文件含三条 ER 结束标记", ris_text.count("ER  - ") == 3, ris_text.count("ER  - "))
    check("RIS 每行都是两空格加连字符的标签格式",
          all(re.match(r"^[A-Z][A-Z0-9]  - ", line) for line in ris_lines),
          [line[:12] for line in ris_lines if not re.match(r"^[A-Z][A-Z0-9]  - ", line)])
    check("RIS 条目之间空行分隔", "ER  - \n\nTY  - " in ris_text)

    # ------------------------------------------------------------------
    # 4. 下载与预览逐字一致
    # ------------------------------------------------------------------
    print("== 4 下载 ==")
    code, raw, headers = anon.download([id_a, id_b, id_c], "bibtex")
    body = raw.decode("utf-8")
    check("批量下载返回 200", code == 200, code)
    check("下载正文与预览 text 逐字一致", body == text, (len(body), len(text)))
    check("附件头文件名与响应字段一致",
          ('filename="%s"' % batch.get("fileName")) in (headers.get("Content-Disposition") or ""),
          headers.get("Content-Disposition"))
    check("媒体类型为 application/x-bibtex",
          "application/x-bibtex" in (headers.get("Content-Type") or ""), headers.get("Content-Type"))

    code, raw, headers = anon.download([id_a, id_b, id_c], "gb-t-7714")
    check("国标批量下载可按别名 gb-t-7714 且为 text/plain",
          code == 200 and "text/plain" in (headers.get("Content-Type") or "")
          and raw.decode("utf-8") == gb_text,
          (code, headers.get("Content-Type")))

    # ------------------------------------------------------------------
    # 5. 跳过不可导出项
    # ------------------------------------------------------------------
    print("== 5 跳过 ==")
    mixed = [id_a, hidden_id or "ach_hidden_probe", "ach_not_exists_0001"]
    code, payload, _, _ = anon.batch(mixed, "bibtex")
    part = data_of(payload)
    check("部分不可导出时仍返回 200", code == 200, (code, message_of(payload)))
    check("只写入可导出的那一条", part.get("exported") == 1, part.get("exported"))
    check("两条不可导出被记为跳过", part.get("skippedCount") == 2, part.get("skipped"))
    reasons = {(item.get("achievementId"), item.get("reason")) for item in (part.get("skipped") or [])}
    check("跳过原因统一为 NOT_AVAILABLE",
          all(reason == "NOT_AVAILABLE" for _, reason in reasons), reasons)
    messages = {item.get("message") for item in (part.get("skipped") or [])}
    check("「不存在」与「不可公开」的说明文本完全相同（不泄露存在性）",
          len(messages) == 1, messages)

    # ------------------------------------------------------------------
    # 6. 错误路径
    # ------------------------------------------------------------------
    print("== 6 错误路径 ==")
    code, payload, _, _ = anon.batch(["ach_not_exists_0001", "ach_not_exists_0002"], "bibtex")
    check("全部不可导出返回 404 而不是空文件",
          code == 404 and "都不可公开导出" in message_of(payload), (code, message_of(payload)))
    code, payload, _, _ = anon.json("GET", "/api/v1/public/citations/batch?format=bibtex&ids=")
    check("ids 为空串返回 400", code == 400 and "至少选择一条" in message_of(payload),
          (code, message_of(payload)))
    code, payload, _, _ = anon.batch([id_a], "bib")
    check("非法格式返回 400", code == 400 and "不支持的引用格式" in message_of(payload),
          (code, message_of(payload)))
    code, payload, _, _ = anon.json("GET", "/api/v1/public/citations/batch?format=bibtex")
    check("缺 ids 参数返回 400", code == 400 and "至少选择一条" in message_of(payload),
          (code, message_of(payload)))
    bulk = ["ach_bulk_%04d" % i for i in range(MAX_BATCH + 1)]
    code, payload, _, _ = anon.batch(bulk, "bibtex")
    check("超过上限返回 422 且说明上限",
          code == 422 and "一次最多导出 100 条" in message_of(payload), (code, message_of(payload)))

    # ------------------------------------------------------------------
    # 7. 去重与顺序
    # ------------------------------------------------------------------
    print("== 7 去重与顺序 ==")
    code, payload, _, _ = anon.batch([id_b, id_a, id_b], "bibtex")
    dedup = data_of(payload)
    check("重复选中只写一条", dedup.get("requested") == 2 and dedup.get("exported") == 2,
          (dedup.get("requested"), dedup.get("exported")))
    check("条目顺序等于读者选中顺序",
          [entry.get("achievementId") for entry in (dedup.get("entries") or [])] == [id_b, id_a],
          [entry.get("achievementId") for entry in (dedup.get("entries") or [])])

    # ------------------------------------------------------------------
    # 8. 合并重定向：同一实体不写两条
    # ------------------------------------------------------------------
    print("== 8 合并重定向 ==")
    if merged_id and merged_target:
        code, payload, _, _ = anon.batch([merged_id, merged_target], "bibtex")
        merged_batch = data_of(payload)
        check("同时选中原记录与幸存记录仍返回 200", code == 200, (code, message_of(payload)))
        check("只写入一条引用", merged_batch.get("exported") == 1, merged_batch.get("exported"))
        check("写入的是幸存实体",
              [entry.get("achievementId") for entry in (merged_batch.get("entries") or [])]
              == [merged_target],
              [entry.get("achievementId") for entry in (merged_batch.get("entries") or [])])
        reasons = [item.get("reason") for item in (merged_batch.get("skipped") or [])]
        check("原记录记为 DUPLICATE_TARGET（与「不可公开」区分开）",
              reasons == ["DUPLICATE_TARGET"], reasons)
        check("被记为重复的是已合并的旧记录，而不是幸存记录",
              [item.get("achievementId") for item in (merged_batch.get("skipped") or [])] == [merged_id],
              [item.get("achievementId") for item in (merged_batch.get("skipped") or [])])
        check("文件里不出现重复键",
              len({entry.get("citeKey") for entry in (merged_batch.get("entries") or [])})
              == merged_batch.get("exported"))
    else:
        print("  库中没有可用的合并重定向样本，跳过本段（不记为通过）")

    # ------------------------------------------------------------------
    # 9. 汇总
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
