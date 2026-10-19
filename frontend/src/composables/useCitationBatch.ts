/**
 * 批量导出的状态装配。
 *
 * 与单条引用面板的分工相同：文本、编号、跳过判定都在服务端（`POST /api/v1/public/citations/batch`），
 * 前端只负责「选哪些」「看清楚会得到什么」「下载」。
 *
 * 这里有一个只有批量才存在的陷阱：**预览与选择会不同步**。用户勾了 3 条、点了导出、看到一份 3 条
 * 的文件，然后又勾上第 4 条——此时屏幕上那份文本已经不是将要下载的内容了。所以只要选择或格式变了，
 * 上一份预览立即作废（`invalidate`），而不是留着让人对着旧文本点下载。
 */

import { computed, ref } from 'vue'
import { publicApi } from '@/api'
import type { CitationBatch } from '@/api/types'
import {
  batchSummary,
  batchWarnings,
  pruneSelected,
  toggleSelectAll,
  toggleSelected,
} from '@/composables/citationView'
import { describeError } from '@/composables/useAsync'

/**
 * 默认格式给 BibTeX：批量导出最常见的用途是把一批文献导入 Zotero / EndNote / LaTeX，
 * 而人读格式（国标、APA）单条复制就够，一次要十条国标文本的场景少见得多。
 */
const DEFAULT_FORMAT = 'bibtex'

export function useCitationBatch() {
  const selected = ref<string[]>([])
  const format = ref(DEFAULT_FORMAT)
  const batch = ref<CitationBatch | null>(null)
  const loading = ref(false)
  const error = ref('')
  const copied = ref('')

  const count = computed(() => selected.value.length)
  const summary = computed(() => (batch.value ? batchSummary(batch.value) : ''))
  const warnings = computed(() => (batch.value ? batchWarnings(batch.value) : []))

  function isSelected(id: string): boolean {
    return selected.value.includes(id)
  }

  function toggle(id: string) {
    selected.value = toggleSelected(selected.value, id)
    invalidate()
  }

  /** 全选／取消全选当前结果页。跨页累计会让「已选」里混进看不见的条目，这里刻意不做。 */
  function selectAll(visibleIds: string[]) {
    selected.value = toggleSelectAll(selected.value, visibleIds)
    invalidate()
  }

  /** 结果集变化（换条件、翻页）后清掉已不可见的勾选。 */
  function prune(visibleIds: string[]) {
    const next = pruneSelected(selected.value, visibleIds)
    if (next.length !== selected.value.length) {
      selected.value = next
      invalidate()
    }
  }

  function clear() {
    selected.value = []
    invalidate()
  }

  function setFormat(next: string) {
    format.value = next
    invalidate()
  }

  /** 选择或格式变了：上一份预览描述的文件已经不存在了。 */
  function invalidate() {
    batch.value = null
    error.value = ''
    copied.value = ''
  }

  async function run() {
    if (selected.value.length === 0) {
      error.value = '请先勾选至少一条成果。'
      return
    }
    loading.value = true
    error.value = ''
    try {
      batch.value = await publicApi.batchCitations(selected.value, format.value)
    } catch (cause) {
      // 400（未选/格式错）、422（超过上限）、404（全部不可导出）都带服务端写好的原因，
      // 直接展示比前端猜测「可能是什么问题」准确。
      batch.value = null
      error.value = describeError(cause).message
    } finally {
      loading.value = false
    }
  }

  async function copy() {
    const text = batch.value?.text ?? ''
    if (!text) {
      copied.value = '当前没有可复制的引用文本。'
      return
    }
    try {
      await navigator.clipboard.writeText(text)
      copied.value = '已复制整份文件内容。'
    } catch {
      copied.value = '浏览器拒绝了剪贴板写入（常见于非 HTTPS 页面），请手动选中文本复制。'
    }
    window.setTimeout(() => {
      copied.value = ''
    }, 6000)
  }

  return {
    selected,
    format,
    batch,
    loading,
    error,
    copied,
    count,
    summary,
    warnings,
    isSelected,
    toggle,
    selectAll,
    prune,
    clear,
    setFormat,
    run,
    copy,
  }
}
