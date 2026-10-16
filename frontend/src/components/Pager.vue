<script setup lang="ts">
/**
 * 分页条。
 *
 * 翻页是「换一页条件」而不是「同一份结果往下滚」：服务端按查询版本拒绝跨快照分页，因此
 * 翻页时沿用同一组条件与版本号；版本一旦过期，父组件会收到 QUERY_VERSION_CHANGED 并回到首页。
 */
const props = defineProps<{
  page: number
  size: number
  total: number
  maxOffset?: number
  disabled?: boolean
}>()

const emit = defineEmits<{ (e: 'change', page: number): void }>()

function go(target: number) {
  if (target < 1 || props.disabled) return
  const offset = (target - 1) * props.size
  if (props.maxOffset && offset > props.maxOffset) return
  emit('change', target)
}
</script>

<template>
  <div class="pager">
    <button class="btn small" :disabled="page <= 1 || disabled" @click="go(page - 1)">上一页</button>
    <span class="page-info">
      第 {{ page }} 页 · 每页 {{ size }} 条 · 共 {{ total }} 条
    </span>
    <button
      class="btn small"
      :disabled="disabled || page * size >= total || (!!maxOffset && page * size > maxOffset)"
      @click="go(page + 1)"
    >
      下一页
    </button>
    <span v-if="maxOffset" class="tiny faint">深翻页上限 {{ maxOffset }} 条</span>
  </div>
</template>
