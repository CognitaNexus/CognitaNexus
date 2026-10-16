<script setup lang="ts">
/**
 * 合作网络页。
 *
 * 三个刻意的设计：
 *
 * 1. **不提供「全图」入口**。图必须从一个学者出发并按深度展开，因为一张没有起点的全网图既
 *    无法阅读，也会把「两个人之间有路径」误读成「他们合作过」——路径与直连是两件事。
 * 2. **路径结果单独讲清「几跳」**。1 跳与 3 跳在学术含义上完全不同：前者是直接合作，后者只是
 *    通过中间人可达。界面把跳数放在最显眼处，并在多跳时明确写「不是直接合作」。
 * 3. **超限与超时如实报告**。图太大时返回的是明确的「范围过大，请先缩小」而不是一张被静默
 *    截断的图——被截断的图会让人以为「这个人只有这些合作者」。
 */
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { queryApi } from '@/api'
import type { CooperationGraph, PathResult } from '@/api/types'
import { describeError } from '@/composables/useAsync'
import { useScopeStore } from '@/stores/scope'
import CoauthorGraph from '@/components/CoauthorGraph.vue'
import NatureBadge from '@/components/NatureBadge.vue'

const route = useRoute()
const scope = useScopeStore()

const scholarId = ref(String(route.query.scholarId ?? ''))
const depth = ref(1)
const centerName = ref('')

const graph = ref<CooperationGraph | null>(null)
const graphLoading = ref(false)
const graphError = ref('')
const graphErrorCode = ref('')

const pathFrom = ref(String(route.query.scholarId ?? ''))
const pathTo = ref('')
const path = ref<PathResult | null>(null)
const pathLoading = ref(false)
const pathError = ref('')
const pathErrorCode = ref('')

/** 供选择：从检索接口取一批学者，避免手输 id。 */
const candidates = ref<{ scholarId: string; scholarName: string }[]>([])
const candidatesLoading = ref(false)

const graphTruncated = computed(
  () => graph.value !== null && (graph.value.truncated === true || graph.value.withinLimits === false),
)

async function loadCandidates() {
  candidatesLoading.value = true
  try {
    const page = await queryApi.searchScholars({ dataNature: scope.nature, size: 50 })
    candidates.value = page.items.map((item) => ({
      scholarId: item.scholarId,
      scholarName: item.scholarName,
    }))
  } catch {
    // 候选列表拿不到不影响核心功能：仍可手输 id。这里不把它显示成页面级错误。
    candidates.value = []
  } finally {
    candidatesLoading.value = false
  }
}

async function loadGraph() {
  if (!scholarId.value) {
    graphError.value = '请先指定一个起始学者。'
    graphErrorCode.value = ''
    graph.value = null
    return
  }
  graphLoading.value = true
  graphError.value = ''
  graphErrorCode.value = ''
  try {
    const data = await queryApi.graph({
      scholarId: scholarId.value,
      depth: depth.value,
      dataNature: scope.nature,
    })
    graph.value = data
    centerName.value =
      data.nodes.find((node) => node.scholarId === scholarId.value)?.scholarName ?? ''
  } catch (cause) {
    const described = describeError(cause)
    graphError.value = described.message
    graphErrorCode.value = described.code
    graph.value = null
  } finally {
    graphLoading.value = false
  }
}

async function findPath() {
  if (!pathFrom.value || !pathTo.value) {
    pathError.value = '请填写起点与终点学者标识。'
    path.value = null
    return
  }
  pathLoading.value = true
  pathError.value = ''
  pathErrorCode.value = ''
  try {
    path.value = await queryApi.shortestPath({
      from: pathFrom.value,
      to: pathTo.value,
      dataNature: scope.nature,
    })
  } catch (cause) {
    const described = describeError(cause)
    pathError.value = described.message
    pathErrorCode.value = described.code
    path.value = null
  } finally {
    pathLoading.value = false
  }
}

function useAsStart(id: string) {
  scholarId.value = id
  pathFrom.value = id
  loadGraph()
}

onMounted(async () => {
  await loadCandidates()
  if (scholarId.value) await loadGraph()
})
</script>

<template>
  <div>
    <h1>合作网络</h1>

    <div class="panel">
      <div class="panel-head">
        <h2>以学者为中心展开</h2>
        <span class="small dim">口径 <NatureBadge :nature="scope.nature" /></span>
      </div>
      <div class="panel-body">
        <div class="grid cols-3">
          <div class="field" style="grid-column: span 2">
            <label for="sid">起始学者</label>
            <input
              id="sid"
              v-model="scholarId"
              type="text"
              placeholder="学者标识，例如 sch_..."
              @keyup.enter="loadGraph"
            />
          </div>
          <div class="field">
            <label for="depth">展开深度</label>
            <select id="depth" v-model.number="depth">
              <option :value="1">1 跳（直接合作者）</option>
              <option :value="2">2 跳</option>
            </select>
          </div>
        </div>

        <div class="btn-row" style="margin-bottom: 12px">
          <button class="btn primary" :disabled="graphLoading" @click="loadGraph">加载网络</button>
          <span class="small dim">
            <template v-if="candidatesLoading">正在读取学者候选…</template>
            <template v-else-if="candidates.length">
              或从下列学者中选：
              <button
                v-for="item in candidates.slice(0, 8)"
                :key="item.scholarId"
                class="btn small"
                style="margin-left: 6px"
                @click="useAsStart(item.scholarId)"
              >
                {{ item.scholarName }}
              </button>
            </template>
          </span>
        </div>

        <p class="tiny faint">
          不提供「全平台网络图」：没有起点的全网图既无法阅读，又容易把「两点之间存在路径」
          误读成「他们合作过」。图只表达直接合作关系。
        </p>
      </div>
    </div>

    <div v-if="graphErrorCode === 'GRAPH_SCOPE_TOO_LARGE'" class="notice warn">
      <strong>该子图超出可展示范围。</strong>
      {{ graphError }}
      这次的返回是明确的「太大」而不是一张被截断的图——被截断的图会让人误以为「这个人只有这些合作者」。
      请改用更小深度、加领域或机构筛选，或换一个合作度较低的起点。
    </div>
    <div v-else-if="graphErrorCode === 'GRAPH_TIMEOUT'" class="notice warn">
      <strong>图计算超出时间预算。</strong>{{ graphError }}
    </div>
    <div v-else-if="graphError" class="notice danger">
      <strong>读取失败：</strong>{{ graphError }}
      <div class="tiny mono" style="margin-top: 4px">错误码 {{ graphErrorCode }}</div>
    </div>

    <div v-if="graphLoading" class="panel">
      <div class="loading"><span class="spinner"></span> 正在计算子图…</div>
    </div>

    <template v-if="graph && !graphLoading">
      <div v-if="graphTruncated" class="notice warn">
        该子图已被限制在展示上限内，<strong>图上不是全部合作者</strong>。请缩小深度或收窄筛选条件后重新查看。
      </div>

      <div v-if="graph.nodes.length === 0" class="panel">
        <div class="empty">
          没有找到该学者的合作网络。可能原因：他当前没有公开生效的成果、参与关系未达合格状态
          （待核验 / 争议 / 已排除不建边），或档案尚未认领。
        </div>
      </div>

      <div v-else class="panel">
        <div class="panel-head">
          <h2>
            子图：{{ graph.nodes.length }} 位学者 · {{ graph.edges.length }} 条合作关系
            <span v-if="centerName" class="dim" style="font-weight: 400">（{{ centerName }}）</span>
          </h2>
          <span class="small dim mono">
            {{ graph.graphVersion }} · 已发布修订 {{ graph.publishedRevision }}
          </span>
        </div>
        <div class="panel-body">
          <CoauthorGraph
            :nodes="graph.nodes"
            :edges="graph.edges"
            :center-id="scholarId"
            :edge-rule="graph.edgeRule"
          />
        </div>
      </div>
    </template>

    <div class="panel">
      <div class="panel-head">
        <h2>两人之间是否可达</h2>
        <span class="tiny faint">路径与直连是两件事，结果会标明跳数</span>
      </div>
      <div class="panel-body">
        <div class="grid cols-2">
          <div class="field">
            <label for="pf">起点学者</label>
            <input id="pf" v-model="pathFrom" type="text" placeholder="sch_..." />
          </div>
          <div class="field">
            <label for="pt">终点学者</label>
            <input id="pt" v-model="pathTo" type="text" placeholder="sch_..." />
          </div>
        </div>
        <div class="btn-row">
          <button class="btn primary" :disabled="pathLoading" @click="findPath">查找最短路径</button>
          <button
            v-if="graph"
            class="btn"
            :disabled="graph.nodes.length < 2"
            @click="pathTo = graph.nodes.find((n) => n.scholarId !== scholarId)?.scholarId ?? ''"
          >
            用当前图中的一位合作者填入终点
          </button>
        </div>

        <div v-if="pathError" class="notice danger" style="margin-top: 14px; margin-bottom: 0">
          <strong>查询失败：</strong>{{ pathError }}
        </div>

        <div v-else-if="path" style="margin-top: 14px">
          <div v-if="!path.reachable" class="notice warn" style="margin-bottom: 0">
            在给定筛选范围内两点<strong>不连通</strong>。这表示没有一条由合格合作关系串起来的路径，
            不表示他们一定没合作过——未达合格状态的关系不建边。
            <div class="tiny faint" style="margin-top: 4px">{{ path.note }}</div>
          </div>
          <div v-else>
            <div v-if="path.hops === 0" class="notice info" style="margin-bottom: 10px">
              起点与终点是同一人。
            </div>
            <div v-else-if="path.hops === 1" class="notice info" style="margin-bottom: 10px">
              <strong>1 跳：直接合作关系。</strong>两学者有至少一篇共同合格成果。
            </div>
            <div v-else class="notice warn" style="margin-bottom: 10px">
              <strong>{{ path.hops }} 跳：通过中间人可达，不是直接合作。</strong>
              把这种关系读成「他们合作过」是常见的误读，因此这里显式区分。
            </div>

            <ol class="small" style="margin: 0; padding-left: 20px">
              <li v-for="(nodeId, index) in path.path" :key="nodeId">
                <RouterLink :to="`/scholars/${nodeId}`">
                  {{ path.pathNodes.find((node) => node.scholarId === nodeId)?.scholarName ?? nodeId }}
                </RouterLink>
                <span v-if="index < path.path.length - 1" class="faint"> → </span>
              </li>
            </ol>

            <div v-if="graph && path.path.length > 1" style="margin-top: 14px">
              <CoauthorGraph
                :nodes="graph.nodes"
                :edges="graph.edges"
                :center-id="pathFrom"
                :highlight-path="path.path"
                :edge-rule="graph.edgeRule"
                :height="380"
              />
            </div>

            <p class="tiny faint" style="margin-top: 10px">{{ path.pathRule }}</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
