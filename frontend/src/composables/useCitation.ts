/**
 * 引用面板的状态装配。
 *
 * 四种格式的渲染与告警判定都在服务端（`GET /api/v1/public/achievements/{id}/citations`），
 * 前端不再拼引用文本。理由与四个被修掉的具体缺陷写在 `citationView.ts` 的文件头。
 *
 * 这里只做四件事，每一件失败得都不显眼，因此都单独处理：
 *
 *  1. **独立加载**。引用是详情页的附加信息，它失败不能让整页降级成错误页——读者来是为了看成果，
 *     不是为了复制引用。因此错误只在引用面板内显示，页面其余部分照常。
 *  2. **选中项跟随服务端默认值**。默认格式由服务端给，前端不写死「国标」，否则服务端改默认时
 *     界面会与 API 说法不一致。
 *  3. **复制失败要说人话**。非 HTTPS 页面上 `navigator.clipboard` 不存在或不放行，此时不能
 *     静默无反应，否则用户会以为复制成功了。
 *  4. **下载用服务端给的文件名**。文件名由引用键派生，前端自己拼一个就会和 .bib 里的 key 对不上。
 */

import { computed, ref, type Ref } from 'vue'
import { publicApi } from '@/api'
import type { CitationBundle } from '@/api/types'
import { activeFormat, mergeWarnings } from '@/composables/citationView'
import { describeError } from '@/composables/useAsync'

export function useCitation(achievementId: Ref<string>) {
  const bundle = ref<CitationBundle | null>(null)
  const loading = ref(false)
  const error = ref('')
  const selectedCode = ref('')
  const copied = ref('')

  async function load() {
    loading.value = true
    error.value = ''
    try {
      const data = await publicApi.citations(achievementId.value)
      bundle.value = data
      selectedCode.value = data.defaultFormat
    } catch (cause) {
      bundle.value = null
      error.value = describeError(cause).message
    } finally {
      loading.value = false
    }
  }

  const active = computed(() => activeFormat(bundle.value, selectedCode.value))

  /** 元数据告警 + 当前格式的告警。切换格式时格式层面的告警随之变化。 */
  const warnings = computed(() =>
    mergeWarnings(bundle.value?.warnings ?? [], active.value?.warnings ?? []),
  )

  /**
   * 用法提示。
   *
   * 机读格式提示「导入」，人读格式提示「粘贴」——两种用法混在一句话里会让人找不到入口。
   * 「需要补齐缺失项」只在**确实有告警**时出现，且指向下方：告警清单渲染在引用文本之后，
   * 写「上方」会让人往面板顶部找一条不存在的提示。
   */
  const usageHint = computed(() => {
    if (!active.value) return ''
    if (active.value.machineReadable) {
      return '该格式供文献管理软件读取（Zotero / EndNote / LaTeX），建议下载文件后导入'
    }
    return warnings.value.length
      ? '该格式用于粘贴进论文参考文献表；下方列出的缺失项需按原文补全后再使用'
      : '该格式用于粘贴进论文参考文献表'
  })

  async function copy() {
    const text = active.value?.text ?? ''
    if (!text) {
      copied.value = '当前没有可复制的引用文本。'
      return
    }
    try {
      await navigator.clipboard.writeText(text)
      copied.value = '已复制到剪贴板。'
    } catch {
      copied.value = '浏览器拒绝了剪贴板写入（常见于非 HTTPS 页面），请手动选中上面的文本复制。'
    }
    window.setTimeout(() => {
      copied.value = ''
    }, 6000)
  }

  return { bundle, loading, error, selectedCode, copied, active, warnings, usageHint, load, copy }
}
