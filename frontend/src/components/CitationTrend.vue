<script setup lang="ts">
/**
 * 按发表年份的引用量分布。
 *
 * 这个图最容易被读错，因此在实现上做了三件事来「防止误导」：
 *
 * 1. **标题与提示都用「按发表年份」，不用「引用趋势」。** 平台只有一个当前累计值，
 *    根本没有「某年新增多少引用」的历史。把它画成曲线后叫「趋势」，读者一定会读成
 *    「引用逐年增长」——那不是数据说的话，是我们替数据说的话。
 * 2. **柱子高度按引用量，但 tooltip 必须同时给出该年成果数与有数据条数。** 一个高柱子
 *    可能来自「一篇被引很多」，也可能来自「很多篇各被引一点」，这两种情况的含义完全不同；
 *    只给一个总数，读者只能猜。
 * 3. **零引用量的年份照常画出来**（高度为 0 的柱子，而不是不画）。不画等于把「有成果但
 *    没被引用」从图里抹掉，剩下的年份看起来会更突出。
 */
import { computed } from 'vue'

interface CitationYearRow {
  year: number
  works: number
  worksWithCitation: number
  citations: number
  averagePerWork?: number | null
}

const props = defineProps<{ rows: CitationYearRow[] }>()

const WIDTH = 680
const HEIGHT = 200
const PAD_L = 44
const PAD_R = 12
const PAD_T = 14
const PAD_B = 30

const known = computed(() =>
  props.rows.filter((row) => typeof row.year === 'number').sort((a, b) => a.year - b.year),
)

const ceiling = computed(() => Math.max(1, ...known.value.map((row) => row.citations)))

const bars = computed(() => {
  const rows = known.value
  if (rows.length === 0) return []
  const usable = WIDTH - PAD_L - PAD_R
  const slotWidth = usable / rows.length
  const barWidth = Math.max(2, Math.min(18, slotWidth * 0.62))
  return rows.map((row, index) => ({
    key: String(row.year),
    label: String(row.year),
    text:
      `${row.year} 年发表 ${row.works} 项，其中 ${row.worksWithCitation} 项有引用量记录，` +
      `合计 ${row.citations} 次` +
      (row.averagePerWork == null ? '（该年无可算篇均的记录）' : `，篇均 ${row.averagePerWork} 次`),
    citations: row.citations,
    x: PAD_L + slotWidth * index + (slotWidth - barWidth) / 2,
    width: barWidth,
    // 有成果但引用量为 0 时保留 1px 的底座：完全不画会让该年份在图里消失。
    height: Math.max(1, (row.citations / ceiling.value) * (HEIGHT - PAD_T - PAD_B)),
  }))
})

/** 年份标签抽稀：四十多个年份全标会连成一条黑带，比不标更糟。 */
const labelStep = computed(() => Math.max(1, Math.ceil(known.value.length / 12)))

function showLabel(index: number, total: number): boolean {
  if (index % labelStep.value === 0) return true
  return index === total - 1
}

const gridLines = computed(() => {
  const max = ceiling.value
  const steps = 3
  return Array.from({ length: steps + 1 }, (_, index) => {
    const value = Math.round((max / steps) * index)
    const y = HEIGHT - PAD_B - (value / max) * (HEIGHT - PAD_T - PAD_B)
    return { y, value }
  })
})
</script>

<template>
  <!-- class 里带 citation-trend：页面上还有一个年份分布图同样用 .chart，
       只按 .chart 取柱子的断言会把两个图的柱子一起数进去。 -->
  <div class="chart citation-trend">
    <svg :viewBox="`0 0 ${WIDTH} ${HEIGHT}`" role="img" aria-label="按发表年份的引用量分布">
      <g v-for="line in gridLines" :key="line.y">
        <line
          :x1="PAD_L"
          :x2="WIDTH - PAD_R"
          :y1="line.y"
          :y2="line.y"
          stroke="var(--border)"
          stroke-width="1"
        />
        <text :x="PAD_L - 6" :y="line.y + 4" text-anchor="end" font-size="10" fill="var(--text-faint)">
          {{ line.value }}
        </text>
      </g>

      <g v-for="(bar, index) in bars" :key="bar.key">
        <rect
          :x="bar.x"
          :y="HEIGHT - PAD_B - bar.height"
          :width="bar.width"
          :height="bar.height"
          fill="var(--accent)"
          opacity="0.85"
          rx="1"
        >
          <title>{{ bar.text }}</title>
        </rect>
        <text
          v-if="showLabel(index, bars.length)"
          :x="bar.x + bar.width / 2"
          :y="HEIGHT - PAD_B + 13"
          text-anchor="middle"
          font-size="9.5"
          fill="var(--text-dim)"
        >
          {{ bar.label }}
        </text>
      </g>

      <line
        :x1="PAD_L"
        :x2="WIDTH - PAD_R"
        :y1="HEIGHT - PAD_B"
        :y2="HEIGHT - PAD_B"
        stroke="var(--border-strong)"
        stroke-width="1"
      />
    </svg>

    <div class="legend" style="margin-top: 8px">
      <span><span class="swatch" style="background: var(--accent)"></span>该年发表成果的当前累计引用量</span>
      <span class="faint">鼠标移到柱上可看该年成果数与篇均</span>
    </div>
  </div>
</template>
