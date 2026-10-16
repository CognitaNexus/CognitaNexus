<script setup lang="ts">
/**
 * 横向条形榜。
 *
 * 用于「按机构 / 领域 / 期刊」的排名。刻意不用饼图：这些项是多对多或可分多类的计数，
 * 饼图会被读成「占比合计 100%」，而实际上它们各自独立、合计可超过总量（领域尤其如此）。
 * 条形图不暗示合计关系。
 */
import { computed } from 'vue'

const props = defineProps<{
  items: { label: string; value: number; hint?: string }[]
  emptyText?: string
  max?: number
}>()

const ceiling = computed(() => {
  if (props.max) return props.max
  return Math.max(1, ...props.items.map((item) => item.value))
})
</script>

<template>
  <div v-if="items.length === 0" class="empty">{{ emptyText || '暂无可统计项。' }}</div>
  <div v-else>
    <div v-for="item in items" :key="item.label" style="margin-bottom: 9px">
      <div style="display: flex; justify-content: space-between; gap: 10px; font-size: 13px">
        <span style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap" :title="item.label">
          {{ item.label }}
        </span>
        <span class="mono nowrap" style="font-variant-numeric: tabular-nums">{{ item.value }}</span>
      </div>
      <div
        style="
          height: 6px;
          border-radius: 3px;
          background: var(--panel-alt);
          border: 1px solid var(--border);
          margin-top: 3px;
        "
      >
        <div
          :style="{
            width: (Math.min(item.value, ceiling) / ceiling) * 100 + '%',
            height: '100%',
            borderRadius: '3px',
            background: 'var(--accent)',
          }"
        ></div>
      </div>
      <div v-if="item.hint" class="tiny faint">{{ item.hint }}</div>
    </div>
  </div>
</template>
