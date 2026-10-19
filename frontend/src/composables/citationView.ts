/**
 * 引用面板的纯函数工具（不依赖 Vue，可直接在 node:test 里跑）。
 *
 * 为什么把「引用文本」从前端挪到服务端：这个文件里原来有四种格式的渲染实现，它有一段看起来
 * 很有道理的历史——「平台自己拼，不依赖后端」。但它同时带来了四个只有线上才会暴露的问题：
 *
 *  1. `%` 未转义。BibTeX 的 `%` 是注释起始符，题名里出现一个 `%`，其后的内容在编译时被整段吃掉。
 *  2. 无作者时 `note` 字段被写两次（一次占位、一次出处），同一个 entry 里重复键。
 *  3. BibTeX key 取「第一作者名的最后一个词」。中文名取到的是「三」，英文名
 *     「Zhang San」取到的是「San」——有一半的作者会得到错误的 key，且两人可能撞成同一个。
 *  4. 作者列表只取「关系已确认」的学者，于是把待核验的署名者从引用里直接删掉了：署名是元数据
 *     事实，治理状态回答的是「这位学者是否被正确匹配」，两件事不能互相覆盖。
 *
 * 现在的分工是：文本与告警由服务端一次给全（服务端能看到被排除的参与关系、缺失的刊名、
 * 撤稿声明），前端只负责展示、复制、下载。这个文件只剩下「展示层要做的三件小事」。
 */

import type {
  CitationBatch,
  CitationBundle,
  CitationFormatEntry,
  CitationWarning,
} from '@/api/types'

/** 后端路径的统一前缀。前端所有 API 都经由 vite 代理走同源，直连会丢掉会话 Cookie。 */
const SERVICE_PREFIX = '/core'

/**
 * 把服务端给的下载地址转成浏览器可用的 href。
 *
 * 只接受以 `/` 开头的站内路径：服务端返回的是相对路径，若将来有人把它改成绝对地址，
 * 也不该让页面凭一个后端字段就能跳到任意外部站点。
 */
export function downloadHref(downloadUrl: string): string {
  if (!downloadUrl || !downloadUrl.startsWith('/')) return ''
  return downloadUrl.startsWith(`${SERVICE_PREFIX}/`) ? downloadUrl : SERVICE_PREFIX + downloadUrl
}

export type WarningTone = 'danger' | 'warn' | 'info'

/**
 * 告警严重度。撤稿用 danger（红），更正／关注与「不能当正式引用依据」用 warn，
 * 其余「信息不全」用 info——把所有告警都染成红色，等于告诉读者「都别信」，
 * 而年份缺失和撤稿根本不是一回事。
 */
export function warningTone(code: string): WarningTone {
  if (code === 'RETRACTED') return 'danger'
  if (code === 'CORRECTED' || code === 'CONCERNED' || code === 'DATA_NATURE_NOT_REAL') return 'warn'
  return 'info'
}

/**
 * 当前选中的格式。
 *
 * 三级回退：指定 code → 服务端默认格式 → 第一个。不返回 null 的兜底只在「服务端给了一个空
 * 格式列表」时发生，那时返回 null 让界面显示「暂无可用格式」，而不是渲染一个空文本框。
 */
export function activeFormat(
  bundle: CitationBundle | null,
  code: string,
): CitationFormatEntry | null {
  if (!bundle || !bundle.formats || bundle.formats.length === 0) return null
  return (
    bundle.formats.find((entry) => entry.code === code) ??
    bundle.formats.find((entry) => entry.code === bundle.defaultFormat) ??
    bundle.formats[0] ??
    null
  )
}

/**
 * 合并元数据告警与当前格式的告警，按 code 去重并保持先出现的顺序。
 *
 * 去重是必要的：格式层面的告警（如 APA 不拆姓名）与元数据层面的告警在语义上不重叠，但若同一条
 * 告警将来两个来源都给出，界面上出现两遍同样的话会让人以为遇到了两个不同的问题。
 */
export function mergeWarnings(
  globalWarnings: CitationWarning[],
  formatWarnings: CitationWarning[],
): CitationWarning[] {
  const seen = new Set<string>()
  const merged: CitationWarning[] = []
  for (const warning of [...globalWarnings, ...formatWarnings]) {
    if (!warning || seen.has(warning.code)) continue
    seen.add(warning.code)
    merged.push(warning)
  }
  return merged
}

/** 引用键的展示文案：BibTeX/RIS 导入时用户要能对上号，缺省时说明「未生成」而不是空着。 */
export function citeKeyLabel(citeKey: string): string {
  return citeKey ? citeKey : '未生成引用键'
}

/* ------------------------------- 批量导出 ------------------------------- */

/**
 * 勾选／取消一条。
 *
 * 返回新数组而不是就地 splice：勾选状态要驱动「已选 N 条」与按钮可用性，就地修改后 Vue 只在
 * 某些操作下才能感知变化，表现为计数不更新、按钮该亮没亮——这类不同步看起来像界面卡住。
 */
export function toggleSelected(selected: readonly string[], id: string): string[] {
  return selected.includes(id) ? selected.filter((one) => one !== id) : [...selected, id]
}

/**
 * 结果集变化后只保留仍在当前结果里的勾选。
 *
 * 勾选必须跟着结果集走：换关键词或翻页之后，屏幕上已经没有那条成果了，若勾选还留着，
 * 「已选 3 条」里有两条是看不见的，用户点导出会得到一份自己没在同屏核对过的文件。
 */
export function pruneSelected(
  selected: readonly string[],
  visibleIds: readonly string[],
): string[] {
  const visible = new Set(visibleIds)
  return selected.filter((id) => visible.has(id))
}

/** 全选／取消全选：已全选时再点应当是清空，而不是把同一批 id 再追加一遍。 */
export function toggleSelectAll(
  selected: readonly string[],
  visibleIds: readonly string[],
): string[] {
  const allSelected =
    visibleIds.length > 0 && visibleIds.every((id) => selected.includes(id))
  return allSelected ? [] : [...visibleIds]
}

/**
 * 批量导出的一句话结论。
 *
 * 必须把「少给了几条」说出来：用户选了 5 条、拿到 4 条时，沉默会让他以为是自己少点了，
 * 而真正的原因是那条成果此刻不可公开。
 */
export function batchSummary(batch: CitationBatch): string {
  const skipped = batch.skippedCount ?? 0
  if (skipped <= 0) {
    return `已导出 ${batch.exported} 条引用`
  }
  return `已导出 ${batch.exported} 条引用，${skipped} 条不可公开已跳过（共选中 ${batch.requested} 条）`
}

/**
 * 文件级告警 + 各条目的告警，按 code 去重。
 *
 * 批量文件里同一个缺失项会出现很多次（十条里有八条缺年份），逐条列出会让告警区变成一堵墙，
 * 反而没人看；按 code 合并后只留一句，条目级的细节仍在 entries 里可查。
 */
export function batchWarnings(batch: CitationBatch): CitationWarning[] {
  const all: CitationWarning[] = [...(batch.warnings ?? [])]
  for (const entry of batch.entries ?? []) {
    all.push(...(entry.warnings ?? []))
  }
  return mergeWarnings(all, [])
}

