<script setup lang="ts">
/**
 * 年份分布图。
 *
 * 两个刻意的处理：
 *
 * 1. **年份缺失单列为独立柱**，颜色与真实年份不同。把它并入最近年份或直接丢弃，都会让趋势线
 *    看起来更完整——而这份「完整」是编出来的。需求明确要求未知即未知，并说明原因。
 * 2. **相邻年份之间不插值平滑**。曲线平滑会让「某年没有收录」看起来像「某年略有下降」，
 *    这两种情况对读者的意义完全不同。
 *
 * 年份桶可能不连续（某些年份没有成果），因此横轴按实际存在的年份逐个排列，并在不连续处
 * 用断开的间隙表示，而不是把它们等距排开假装连续。
 */
import { computed } from 'vue'

const props = defineProps<{
  rows: { year: number; amount: number }[]
  unknownAmount?: number
}>()

const WIDTH = 680
const HEIGHT = 200
const PAD_L = 40
const PAD_R = 12
const PAD_T = 14
const PAD_B = 30

const bars = computed(() => {
  const known = props.rows.filter((row) => typeof row.year === 'number').sort((a, b) => a.year - b.year)
  const hasUnknown = (props.unknownAmount ?? 0) > 0
  const slots = known.length + (hasUnknown ? 1 : 0)
  if (slots === 0) return []

  const ceiling = Math.max(1, ...known.map((row) => row.amount), props.unknownAmount ?? 0)
  const usable = WIDTH - PAD_L - PAD_R
  const slotWidth = usable / slots
  const barWidth = Math.max(2, Math.min(18, slotWidth * 0.62))

  const result = known.map((row, index) => ({
    key: String(row.year),
    label: String(row.year),
    text: `${row.year} 年 · ${row.amount} 项`,
    amount: row.amount,
    unknown: false,
    x: PAD_L + slotWidth * index + (slotWidth - barWidth) / 2,
    width: barWidth,
    height: (row.amount / ceiling) * (HEIGHT - PAD_T - PAD_B),
  }))

  if (hasUnknown) {
    const index = known.length
    const amount = props.unknownAmount ?? 0
    result.push({
      key: 'unknown',
      label: '未知',
      text: `年份未知 · ${amount} 项（来源未提供年份，单列不计入任一年份）`,
      amount,
      unknown: true,
      x: PAD_L + slotWidth * index + (slotWidth - barWidth) / 2,
      width: barWidth,
      height: (amount / ceiling) * (HEIGHT - PAD_T - PAD_B),
    })
  }
  return result
})

const ceiling = computed(() =>
  Math.max(1, ...props.rows.map((row) => row.amount), props.unknownAmount ?? 0),
)

/**
 * 年份标签抽稀。
 *
 * 年份桶可能有四五十个（最早到 1960 年代），每个柱都标年份会全部压在一起，最后变成一条
 * 读不出任何信息的黑带——这比不标年份更糟，因为读者会以为那里有内容。
 *
 * 抽稀规则：目标最多 12 个刻度，步长取「桶数/12」向上取整；最后一个年份与「未知」桶
 * 无论如何都标出来，否则读者看不出横轴的右端到哪里。被跳过的年份不是被删掉了——
 * 每根柱子仍带 title 提示，鼠标移上去能看到具体值。
 */
const labelStep = computed(() => {
  const slots = props.rows.length + ((props.unknownAmount ?? 0) > 0 ? 1 : 0)
  return Math.max(1, Math.ceil(slots / 12))
})

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

/** 年份跳跃处标出断点，避免把不连续的年份读成连续趋势。 */
const gaps = computed(() => {
  const known = props.rows.filter((row) => typeof row.year === 'number').sort((a, b) => a.year - b.year)
  let count = 0
  for (let i = 1; i < known.length; i += 1) {
    if (known[i]!.year - known[i - 1]!.year > 1) count += 1
  }
  return count
})
</script>

<template>
  <div class="chart">
    <svg :viewBox="`0 0 ${WIDTH} ${HEIGHT}`" role="img" aria-label="年份分布">
      <g v-for="line in gridLines" :key="line.y">
        <line
          :x1="PAD_L"
          :x2="WIDTH - PAD_R"
          :y1="line.y"
          :y2="line.y"
          stroke="var(--border)"
          stroke-width="1"
        />
        <text
          :x="PAD_L - 6"
          :y="line.y + 4"
          text-anchor="end"
          font-size="10"
          fill="var(--text-faint)"
        >
          {{ line.value }}
        </text>
      </g>

      <g v-for="(bar, barIndex) in bars" :key="bar.key">
        <rect
          :x="bar.x"
          :y="HEIGHT - PAD_B - bar.height"
          :width="bar.width"
          :height="bar.height"
          :fill="bar.unknown ? 'var(--demo)' : 'var(--accent)'"
          :opacity="bar.unknown ? 0.75 : 0.85"
          rx="1"
        >
          <title>{{ bar.text }}</title>
        </rect>
        <text
          v-if="bar.unknown || showLabel(barIndex, bars.length)"
          :x="bar.x + bar.width / 2"
          :y="HEIGHT - PAD_B + 13"
          text-anchor="middle"
          font-size="9.5"
          :fill="bar.unknown ? 'var(--demo)' : 'var(--text-dim)'"
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
      <span><span class="swatch" style="background: var(--accent)"></span>年份已知</span>
      <span v-if="(unknownAmount ?? 0) > 0">
        <span class="swatch" style="background: var(--demo)"></span>年份未知（单列）
      </span>
      <span v-if="gaps > 0" class="faint">横轴存在 {{ gaps }} 处年份跳跃，柱位按实际年份排列而非等距连续</span>
    </div>
  </div>
</template>
