<script setup lang="ts">
/**
 * 合作网络图（纯 SVG，无第三方图形库）。
 *
 * 布局选择：**按度数的确定性圆形布局**，而不是力导向收敛。
 *
 *   - 力导向每次结果都不同，同一个人刷新两次看到两张不一样的图，会怀疑数据变了；
 *   - 圆形布局的读法是确定的：外圈是一度合作者，越靠内侧度数越高（合作越广）；角度按 id 排序
 *     固定，同一份数据永远画出同一张图。
 *
 * 图上还承担一个语义职责：**只画合格关系**。待核验、争议、已排除的关系不建边——这一点由后端
 * 保证，前端在提示里再写一次，因为「网络图上两个人连着线」在读者眼里就是「他们确实合作过」，
 * 这个印象一旦形成，事后解释成本极高。
 */
import { computed, ref } from 'vue'
import type { GraphEdge, GraphNode } from '@/api/types'

const props = defineProps<{
  nodes: GraphNode[]
  edges: GraphEdge[]
  centerId?: string
  /** 高亮路径上的节点 id 序列（最短路径视图用）。 */
  highlightPath?: string[]
  edgeRule?: string
  height?: number
}>()

const WIDTH = 680
const height = computed(() => props.height ?? 460)

const hovered = ref<string>('')

const layout = computed(() => {
  const nodes = [...props.nodes]
  if (nodes.length === 0) return { points: new Map<string, { x: number; y: number; degree: number }>(), lines: [] }

  const degree = new Map<string, number>()
  for (const node of nodes) degree.set(node.scholarId, 0)
  for (const edge of props.edges) {
    degree.set(edge.source, (degree.get(edge.source) ?? 0) + 1)
    degree.set(edge.target, (degree.get(edge.target) ?? 0) + 1)
  }

  const cx = WIDTH / 2
  const cy = height.value / 2
  const maxDegree = Math.max(1, ...degree.values())
  const outer = Math.min(cx, cy) - 46

  // 中心学者放正中央；其余按 id 排序后均匀分布在外圈，度数高者半径略小。
  const others = nodes
    .filter((node) => node.scholarId !== props.centerId)
    .sort((a, b) => a.scholarId.localeCompare(b.scholarId))

  const points = new Map<string, { x: number; y: number; degree: number }>()
  if (props.centerId && nodes.some((node) => node.scholarId === props.centerId)) {
    points.set(props.centerId, { x: cx, y: cy, degree: degree.get(props.centerId) ?? 0 })
  }

  others.forEach((node, index) => {
    const angle = (index / Math.max(1, others.length)) * Math.PI * 2 - Math.PI / 2
    const d = degree.get(node.scholarId) ?? 0
    // 度数越高越靠内：半径在 [0.62, 1.0] × outer 之间
    const radius = outer * (1 - 0.38 * (d / maxDegree))
    points.set(node.scholarId, {
      x: cx + Math.cos(angle) * radius,
      y: cy + Math.sin(angle) * radius,
      degree: d,
    })
  })

  const highlight = new Set(props.highlightPath ?? [])
  const lines = props.edges
    .map((edge) => {
      const from = points.get(edge.source)
      const to = points.get(edge.target)
      if (!from || !to) return null
      const onPath =
        highlight.size > 1 && isPathEdge(edge.source, edge.target, props.highlightPath ?? [])
      return { edge, from, to, onPath }
    })
    .filter((line): line is NonNullable<typeof line> => line !== null)

  return { points, lines }
})

function isPathEdge(source: string, target: string, path: string[]): boolean {
  for (let i = 1; i < path.length; i += 1) {
    const a = path[i - 1]!
    const b = path[i]!
    if ((a === source && b === target) || (a === target && b === source)) return true
  }
  return false
}

const maxStrength = computed(() => Math.max(1, ...props.edges.map((edge) => edge.strength)))

function radiusOf(node: GraphNode): number {
  const point = layout.value.points.get(node.scholarId)
  const degree = point?.degree ?? 0
  const base = 4 + Math.min(8, degree * 1.1)
  return node.scholarId === props.centerId ? base + 3 : base
}

const pathSet = computed(() => new Set(props.highlightPath ?? []))
</script>

<template>
  <div>
    <div class="graph-wrap">
      <svg :viewBox="`0 0 ${WIDTH} ${height}`" role="img" aria-label="合作网络图">
        <!-- 先画边，再画点：否则半透明边会盖在节点文字上，读起来像脏点。 -->
        <g v-for="(line, index) in layout.lines" :key="index">
          <line
            :x1="line.from.x"
            :y1="line.from.y"
            :x2="line.to.x"
            :y2="line.to.y"
            :stroke="line.onPath ? 'var(--accent)' : 'var(--border-strong)'"
            :stroke-width="line.onPath ? 2.4 : 0.6 + (line.edge.strength / maxStrength) * 1.8"
            :opacity="highlightPath && highlightPath.length > 1 ? (line.onPath ? 0.95 : 0.18) : 0.55"
          >
            <title>
              {{ line.edge.source }} ↔ {{ line.edge.target }} · 共同合格成果 {{ line.edge.strength }} 篇
            </title>
          </line>
        </g>

        <g v-for="node in nodes" :key="node.scholarId">
          <circle
            :cx="layout.points.get(node.scholarId)?.x ?? 0"
            :cy="layout.points.get(node.scholarId)?.y ?? 0"
            :r="radiusOf(node)"
            :fill="
              pathSet.has(node.scholarId)
                ? 'var(--accent)'
                : node.scholarId === centerId
                  ? 'var(--ok)'
                  : 'var(--text-faint)'
            "
            :opacity="hovered && hovered !== node.scholarId ? 0.55 : 1"
            @mouseenter="hovered = node.scholarId"
            @mouseleave="hovered = ''"
          >
            <title>
              {{ node.scholarName }}（{{ node.scholarId }}）· 合格成果 {{ node.qualifiedAchievementCount }} 项
              · 合作度 {{ layout.points.get(node.scholarId)?.degree ?? 0 }}
            </title>
          </circle>
          <text
            v-if="node.scholarId === centerId || pathSet.has(node.scholarId) || hovered === node.scholarId"
            :x="(layout.points.get(node.scholarId)?.x ?? 0) + radiusOf(node) + 3"
            :y="(layout.points.get(node.scholarId)?.y ?? 0) + 3"
            font-size="10"
            fill="var(--text)"
          >
            {{ node.scholarName }}
          </text>
        </g>
      </svg>
    </div>

    <div class="legend" style="margin-top: 8px">
      <span><span class="swatch" style="background: var(--ok)"></span>查询中心</span>
      <span><span class="swatch" style="background: var(--text-faint)"></span>合作者</span>
      <span v-if="highlightPath && highlightPath.length > 1">
        <span class="swatch" style="background: var(--accent)"></span>最短路径
      </span>
      <span class="faint">节点大小与合作度相关，连线粗细为共同合格成果数</span>
    </div>

    <p v-if="edgeRule" class="tiny faint" style="margin: 8px 0 0">
      建边规则：{{ edgeRule }}
    </p>
  </div>
</template>
