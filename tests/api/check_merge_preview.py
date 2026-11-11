#!/usr/bin/env python3
"""合并影响预览的响应断言。

拆成独立脚本而不是在 shell 里用 sed 抠字段：预览响应的数字字段与字符串字段交错出现，
用正则取错值不会报错，只会让自检静默地验证一个别的数字。这里统一 load 成 dict 再断言。

用法：
  python check_merge_preview.py preview <preview.json>
  python check_merge_preview.py ledger  <preview.json> <ledger.json>
  python check_merge_preview.py blocked <preview-after.json>
  python check_merge_preview.py rebase  <rebase.json> <workbench-after.json> <改判前待治理总数>
  python check_merge_preview.py carrier-pair <workbench.json> <承接候选标识> <最终实体A> <最终实体B>
  python check_merge_preview.py field   <file.json> <a.b.0.c>          # 供 shell 取单个字段
退出码非 0 表示有断言失败。
"""

import json
import sys

FAILURES = 0


def check(description, expected, actual):
    global FAILURES
    ok = expected == actual
    if not ok:
        FAILURES += 1
    print("  \033[32mPASS\033[0m  %-58s %s" % (description, actual) if ok
          else "  \033[31mFAIL\033[0m  %-58s 期望 %s，实际 %s" % (description, expected, actual))


def load(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def section(data, key):
    value = data.get(key)
    return value if isinstance(value, dict) else {}


def check_preview(path):
    body = load(path)
    data = body.get("data") or {}
    check("dryRun=true", True, data.get("dryRun"))
    check("canMerge 是布尔值", True, isinstance(data.get("canMerge"), bool))
    side_keys = ("id", "title", "type", "dataNature", "visibility", "effectiveStatus", "version",
                 "merged", "yearKnown", "publicationYearLabel")
    for side in ("survivor", "merged"):
        detail = section(data, side)
        check("%s 段落齐备" % side, True, all(key in detail for key in side_keys))
        # 年份标签必须永远在、且非空：null 字段会被全局 Jackson 配置丢掉，
        # 只留可空的 publicationYear 时，「年份未知」与「字段漏写」在报文上无法区分。
        check("%s 年份标签非空" % side, True, bool(str(detail.get("publicationYearLabel") or "").strip()))
        check("%s yearKnown 是布尔值" % side, True, isinstance(detail.get("yearKnown"), bool))
    for name in ("contributions", "sources", "topics", "identifiers", "effects"):
        check("%s 段落是对象" % name, True, isinstance(data.get(name), dict))
    for name in ("blockers", "warnings"):
        check("%s 是列表" % name, True, isinstance(data.get(name), list))

    contributions = section(data, "contributions")
    sources = section(data, "sources")
    topics = section(data, "topics")
    identifiers = section(data, "identifiers")

    check("参与关系会计等式", True,
          contributions.get("resultingRows")
          == contributions.get("survivorRows") + contributions.get("willTransfer"))
    check("参与关系分类互不重叠", True,
          contributions.get("mergedRows")
          == contributions.get("willTransfer") + contributions.get("willMergeIntoExisting"))
    check("来源归属会计等式", True,
          sources.get("resultingRows")
          == sources.get("survivorRows") + sources.get("willTransfer"))
    check("来源归属分类互不重叠", True,
          sources.get("mergedRows")
          == sources.get("willTransfer") + sources.get("willSkipDuplicate"))
    check("领域标签会计等式", True,
          topics.get("resultingRows") == topics.get("survivorRows") + topics.get("willTransfer"))
    check("稳定标识会计等式", True,
          identifiers.get("resultingCount")
          == identifiers.get("survivorCount") + identifiers.get("willTransfer"))
    check("迁移清单条数与计数一致", True,
          len(contributions.get("transferList") or []) == contributions.get("willTransfer"))
    check("重复清单条数与计数一致", True,
          len(contributions.get("duplicateList") or []) == contributions.get("willMergeIntoExisting"))
    check("每条重复关系都写明了处置后果", True,
          all(row.get("effect") for row in (contributions.get("duplicateList") or [])))
    check("拖尾备注说明这是只读预览", True, "不写入" in str(data.get("note", "")))
    check("effects 写明被合并方终态与重定向目标", True,
          contributions and section(data, "effects").get("mergedEffectiveStatus") == "WITHDRAWN"
          and section(data, "effects").get("redirectTo") == section(data, "survivor").get("id"))
    check("有阻断原因时 canMerge 必为假", True,
          not data.get("blockers") or data.get("canMerge") is False)
    # 被阻断时不能只在内部把 canMerge 置假，却仍用「会迁移 N 条」这种既成事实的语气描述后果：
    # 那样的响应读起来像「操作已经成立、只是还没做」，而真实状态是「现在做不成」。
    if data.get("blockers"):
        check("被阻断时明确声明本次合并不成立", True,
              any("不可执行" in str(item) for item in (data.get("warnings") or [])))

    # 候选的处置后果：合并会连带裁决「这一对之间」的候选（候选问的就是这两条是不是同一条，
    # 合并已经回答了）。三个数字都必须是非负整数——它们是预览里唯一说明「待治理清单会怎么变」
    # 的地方，缺一个就会让治理人员以为合并与候选无关。
    #
    # 这里不再加一条「两者相加不超过某数」的断言：能写出来的都是恒真的（加数非负），而恒真的
    # 断言只会让人以为这块被验过了。真正能独立核对这条数字的地方是自检的步骤 8——那里用
    # 待治理总数在合并前后的实际差值来对照这个数字。
    effects = section(data, "effects")
    for key in ("candidatesClosedWithMerge", "otherCandidatesOnMergedSide",
                "otherCandidatesOnSurvivorSide"):
        value = effects.get(key)
        check("effects.%s 是非负整数" % key, True, isinstance(value, int) and value >= 0)


def check_ledger(preview_path, ledger_path):
    preview = (load(preview_path).get("data") or {})
    entries = (load(ledger_path).get("data") or [])
    check("台账至少有一条记录", True, len(entries) >= 1)
    if not entries:
        return
    entry = entries[0]
    snapshot = entry.get("snapshot") or {}
    contributions = section(preview, "contributions")
    identifiers = section(preview, "identifiers")

    check("台账动作是 MERGE", "MERGE", entry.get("operation"))
    check("台账指向这一对实体", True,
          entry.get("survivorId") == section(preview, "survivor").get("id")
          and entry.get("mergedId") == section(preview, "merged").get("id"))
    check("实际插入的参与关系数 = 预览的会迁移数", contributions.get("willTransfer"),
          snapshot.get("contributionsInserted"))
    check("实际改指的稳定标识数 = 预览的会迁移数", identifiers.get("willTransfer"),
          snapshot.get("identifiersMoved"))
    check("行数差 = 预览的「合并为一条」数", contributions.get("willMergeIntoExisting"),
          (snapshot.get("contributionsRemoved") or 0) - (snapshot.get("contributionsInserted") or 0))
    check("快照保留了合并前标识", True, "identifiersBefore" in snapshot)
    check("快照保留了合并前关系归属", True, "contributionsBefore" in snapshot)
    # 连带裁决的候选数也要对得上：预览说会顺手裁决几条，台账里就要记几条。这个数字此前只在
    # 执行路径里算、不在台账里留痕，于是「合并后候选还在」这种缺陷只能靠人眼发现。
    check("实际一并裁决的候选数 = 预览的会一并裁决数",
          section(preview, "effects").get("candidatesClosedWithMerge"),
          snapshot.get("candidatesClosedWithMerge"))


def check_blocked(path):
    data = load(path).get("data") or {}
    blockers = data.get("blockers") or []
    check("已合并的实体不能再作为两侧出现：canMerge 为假", False, data.get("canMerge"))
    check("给出了阻断原因", True, len(blockers) >= 1)
    check("阻断原因说明了为何不可合并", True, any("已被合并" in str(item) for item in blockers))
    # 反向前提：合并时已经把这一对之间的候选裁决掉了，所以再预览时要显示「一条都不用裁决」。
    # 这条与步骤 8 的总数差值互相独立——一个是按实体算，一个是按全表算。
    check("这一对之间已无待治理候选", 0, section(data, "effects").get("candidatesClosedWithMerge"))


def field(path, dotted):
    """按 a.b.0.c 取值，取不到输出空串——shell 侧只做字符串比较，不做类型判断。"""
    current = load(path)
    for part in dotted.split("."):
        if isinstance(current, list):
            index = int(part)
            current = current[index] if 0 <= index < len(current) else None
        elif isinstance(current, dict):
            current = current.get(part)
        else:
            current = None
        if current is None:
            break
    if current is None:
        return ""
    if isinstance(current, bool):
        return "True" if current else "False"
    return str(current)


def same_data(left_path, right_path):
    """比较两个响应的 data 部分。

    不能直接比整份响应：统一响应包里有 traceId 与 timestamp，两个请求必然不同，
    整份比对会把「内容没变」误判成「内容变了」——这正是本次自检踩到的坑。
    """
    left = load(left_path).get("data")
    right = load(right_path).get("data")
    print("True" if left == right else "False")
    if left != right:
        print("左：%s" % json.dumps(left, ensure_ascii=False)[:400], file=sys.stderr)
        print("右：%s" % json.dumps(right, ensure_ascii=False)[:400], file=sys.stderr)


def carrier_pair(path, candidate_id, final_a, final_b):
    """在待治理清单里找到承接候选，并核对它的两端就是改判响应报出的最终实体。

    改判的全部意义就是「问题转到最终实体这一对上」。只核对响应里写了 rebasedToId 是不够的：
    那个标识可能指向一条不存在或两端不相干的候选，接口照样返回 200。因此必须回到清单里，
    把那条候选的两端读出来对一遍。

    不比较左右顺序：候选落库时按标识字典序规范左右，而最终实体是按端别报出的，两者的顺序
    本来就不保证一致；这里比的是「这一对」而不是「这一对的方向」。
    """
    rows = load(path).get("data") or []
    target = None
    for row in rows:
        if str(row.get("id")) == candidate_id:
            target = row
            break
    if target is None:
        print("False")
        print("待治理清单里找不到承接候选 %s" % candidate_id, file=sys.stderr)
        return
    actual = {str(target.get("leftAchievementId")), str(target.get("rightAchievementId"))}
    expected = {str(final_a), str(final_b)}
    print("True" if actual == expected else "False")
    if actual != expected:
        print("承接候选两端 %s，改判报出的最终实体 %s" % (sorted(actual), sorted(expected)),
              file=sys.stderr)


def check_rebase(path, workbench_path, before_total):
    """改判响应的自洽性：状态、指针、以及「待治理总数变化」与「是否新建承接候选」的对应关系。"""
    body = load(path)
    data = body.get("data") or {}
    status = data.get("status")
    check("改判落为终态（REBASED 或两端同实的 MERGED）", True, status in ("REBASED", "MERGED"))
    check("说清了最终实体是哪两条", True, bool(data.get("finalLeftId")) and bool(data.get("finalRightId")))
    existed = data.get("targetExisted")
    check("targetExisted 是布尔值", True, isinstance(existed, bool))
    after_total = int(field(workbench_path, "meta.openTotal") or before_total)
    if status == "MERGED":
        # 两端归并到同一条：这条候选的问题已由合并回答，不新建也不复用候选。
        check("两端同实时不写承接候选", True, "rebasedToId" not in data)
        check("待治理总数下降 1（原候选离开待办，没有承接候选补进来）",
              before_total - 1, after_total)
    else:
        check("REBASED 必须写明承接候选", True, bool(data.get("rebasedToId")))
        # 待治理总数的变化是一条会计等式：原候选一定离开待办（-1）；承接候选若是新建的，
        # 它同时以 OPEN 补进来（+1），两者抵消；若是复用既有候选，则不补。
        # 用工作台返回的总数核对，等于用另一条独立路径验证「到底新建了没有」——
        # 接口自报的 targetExisted 说错了，这条等式就会不等。
        check("待治理总数的变化 = 是否新建了承接候选",
              before_total - (1 if existed else 0), after_total)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    mode = argv[0]
    if mode == "preview":
        check_preview(argv[1])
    elif mode == "ledger":
        check_ledger(argv[1], argv[2])
    elif mode == "blocked":
        check_blocked(argv[1])
    elif mode == "rebase":
        check_rebase(argv[1], argv[2], int(argv[3]))
    elif mode == "carrier-pair":
        carrier_pair(argv[1], argv[2], argv[3], argv[4])
        return 0
    elif mode == "field":
        print(field(argv[1], argv[2]))
        return 0
    elif mode == "same-data":
        same_data(argv[1], argv[2])
        return 0
    else:
        print("未知模式：%s" % mode)
        return 2
    return 1 if FAILURES else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
