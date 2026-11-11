<script setup lang="ts">
/**
 * 数据性质徽标。
 *
 * 三值语义必须一眼可辨：REAL 是真实公开元数据，SIMULATED 是构造数据，MIXED 是两者混合。
 * 这三者在本平台不是「标签好看不好看」的问题——需求明确禁止模拟数据冒充真实，因此徽标使用
 * 固定色彩映射，不随主题或上下文变化。
 */
import { computed } from 'vue'

const props = defineProps<{ nature: string; compact?: boolean }>()

const label = computed(() => {
  switch (props.nature) {
    case 'REAL':
      return '真实'
    case 'SIMULATED':
      return '模拟'
    case 'MIXED':
      return '混合'
    case 'ALL':
      return '全部口径'
    default:
      return props.nature || '未知'
  }
})

const cls = computed(() => {
  const key = (props.nature || '').toLowerCase()
  return ['badge', ['real', 'simulated', 'mixed', 'all'].includes(key) ? key : 'neutral']
})

const title = computed(() => {
  switch (props.nature) {
    case 'REAL':
      return '来源为真实公开元数据'
    case 'SIMULATED':
      return '构造的模拟数据，不得作为真实收录口径引用'
    case 'MIXED':
      return '真实与模拟数据混合，需按字段分别判断'
    case 'ALL':
      return '不限数据性质的演示口径'
    default:
      return '数据性质未知'
  }
})
</script>

<template>
  <span :class="cls" :title="title">{{ compact ? nature : label }}</span>
</template>
