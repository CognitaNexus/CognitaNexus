import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { DataNature } from '@/api/types'

/**
 * 数据性质作用域。
 *
 * 为什么把它放到全局状态而不是每个页面各自传参：这是一个会改变「数字含义」的选择。同一份
 * 「收录成果数」，在 REAL 口径下是平台真实收录量，在 ALL 口径下包含演示数据，两者不能并列
 * 展示而不加区分。全局只保留一个当前口径，并在页头常驻提示，避免同一屏里出现两个口径的数字。
 *
 * 默认 REAL：放宽口径必须由人显式选择（与后端缺省规则一致）。
 */
export const useScopeStore = defineStore('scope', () => {
  const nature = ref<DataNature>('REAL')

  /** 最近一次读接口返回的口径说明，用于页面底部标注「这些数字是什么口径」。 */
  const latestNote = ref('')
  const latestRevision = ref<number | null>(null)

  const isDemo = computed(() => nature.value === 'ALL')

  function set(value: DataNature) {
    nature.value = value
  }

  function observe(scope: { dataNatureScope?: DataNature; scopeNote?: string; publishedRevision?: number }) {
    if (scope.dataNatureScope) nature.value = scope.dataNatureScope
    if (scope.scopeNote) latestNote.value = scope.scopeNote
    if (typeof scope.publishedRevision === 'number') latestRevision.value = scope.publishedRevision
  }

  return { nature, latestNote, latestRevision, isDemo, set, observe }
})
