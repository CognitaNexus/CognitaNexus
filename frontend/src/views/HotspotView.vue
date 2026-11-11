<script setup lang="ts">
/**
 * 关键词热点（FR13）。
 *
 * 这一页刻意只做三件事：给年份窗、给数据口径、列排名。理由：
 *  1. 热点榜最容易被误读成「学术质量排名」或「趋势预测」。需求明确不做趋势预测、不做质量评价，
 *     所以页面上不画预测线、不标「上升/下降」，只给窗口内的计数与占比；
 *  2. 领域是多对多，占比之和会超过 100%。这句话必须出现在数字旁边，而不是折叠在「口径说明」里；
 *  3. 样本不足时不给「参考排名」——那种排名会被截图带走，然后在别的场合被当成结论。
 */
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { queryApi } from '@/api'
import { useScopeStore } from '@/stores/scope'
import { useAsync } from '@/composables/useAsync'
import AsyncBlock from '@/components/AsyncBlock.vue'

const scope = useScopeStore()

/** 年份输入用字符串保存：空串表示「不限」，比 null 更好绑到 input 上。 */
const yearFrom = ref('')
const yearTo = ref('')
const yearError = ref('')

const limit = 20

const hotspots = useAsync<Record<string, unknown>>(() =>
  queryApi.keywordHotspots({
    dataNature: scope.nature,
    yearFrom: yearFrom.value ? Number(yearFrom.value) : null,
    yearTo: yearTo.value ? Number(yearTo.value) : null,
    limit,
  }),
)

interface HotspotItem {
  topicId: string
  canonicalName?: string | null
  canonicalNameKnown?: boolean
  achievements: number
  sourceProvided: number
  systemInferred: number
  firstYear?: number | null
  lastYear?: number | null
  shareOfSample?: number | null
}

const items = computed<HotspotItem[]>(
  () => (hotspots.data.value?.items as HotspotItem[] | undefined) ?? [],
)
const sampleSize = computed(() => Number(hotspots.data.value?.sampleSize ?? 0))
// 「有标注的领域总数」与「本页显示几行」分开显示：只显示行数时，样本 8701 篇却只有 2 行的画面
// 会被读成「热点只有两个」，而实际是这两个领域占了绝大多数论文。
const topicCount = computed(() => Number(hotspots.data.value?.topicsWithKeywords ?? 0))
const sparse = computed(() => hotspots.data.value?.sparse === true)
const note = computed(() => String(hotspots.data.value?.note ?? ''))
const windowLabel = computed(() => {
  const w = hotspots.data.value?.window as { label?: string } | undefined
  return w?.label ?? '全时段'
})
const rules = computed(() => {
  const raw = hotspots.data.value?.countingRules as Record<string, string> | undefined
  return raw ? Object.entries(raw) : []
})

function apply() {
  yearError.value = ''
  const from = yearFrom.value ? Number(yearFrom.value) : null
  const to = yearTo.value ? Number(yearTo.value) : null
  if (from !== null && to !== null && from > to) {
    yearError.value = '起始年份不能晚于结束年份。'
    return
  }
  hotspots.run()
}

function reset() {
  yearFrom.value = ''
  yearTo.value = ''
  yearError.value = ''
  hotspots.run()
}

function share(item: HotspotItem): string {
  if (item.shareOfSample === null || item.shareOfSample === undefined) return '—'
  return `${item.shareOfSample}%`
}

function span(item: HotspotItem): string {
  if (!item.firstYear && !item.lastYear) return '年份未提供'
  if (item.firstYear === item.lastYear) return String(item.firstYear)
  return `${item.firstYear ?? '?'}–${item.lastYear ?? '?'}`
}

onMounted(hotspots.run)
</script>

<template>
  <div>
    <h1>关键词热点</h1>
    <p class="small faint" style="margin-top: 0">
      在指定年份窗与数据口径下，按已登记领域统计公开论文的出现次数。这不是学术质量评价，也不做趋势预测。
    </p>

    <div class="panel">
      <div class="panel-head">
        <h2>口径</h2>
        <span class="tiny faint">数据口径由页头统一切换，本页跟随</span>
      </div>
      <div class="panel-body">
        <div class="field-row" style="display: flex; gap: 12px; flex-wrap: wrap; align-items: flex-end">
          <div class="field" style="width: 160px">
            <label for="hs-from">起始年份（含）</label>
            <input id="hs-from" v-model="yearFrom" type="number" placeholder="不限" />
          </div>
          <div class="field" style="width: 160px">
            <label for="hs-to">结束年份（含）</label>
            <input id="hs-to" v-model="yearTo" type="number" placeholder="不限" />
          </div>
          <button class="btn primary" :disabled="hotspots.loading.value" @click="apply">统计热点</button>
          <button class="btn" :disabled="hotspots.loading.value" @click="reset">清空条件</button>
        </div>
        <div v-if="yearError" class="notice warn" style="margin-top: 10px">{{ yearError }}</div>
        <p class="tiny faint" style="margin: 10px 0 0">
          当前窗口：{{ windowLabel }} · 数据口径 {{ scope.nature }} ·
          样本 {{ sampleSize.toLocaleString() }} 篇公开论文 ·
          有领域标注的领域 {{ topicCount.toLocaleString() }} 个（本页显示 {{ items.length }} 个）
        </p>
      </div>
    </div>

    <div v-if="sparse && !hotspots.loading.value" class="notice warn" style="margin-bottom: 12px">
      {{ note }}
    </div>
    <div v-else-if="note" class="notice info" style="margin-bottom: 12px">{{ note }}</div>

    <AsyncBlock
      :loading="hotspots.loading.value"
      :error="hotspots.error.value"
      :error-code="hotspots.errorCode.value"
      :empty="!hotspots.loading.value && items.length === 0"
      empty-text="当前窗口内没有可排序的领域。这可能表示窗口内没有带领域标注的公开论文，而不是没有论文。"
    >
      <div class="panel">
        <div class="panel-head">
          <h2>活跃领域 Top {{ items.length }}</h2>
          <span class="tiny faint">计数降序；计数相同时按规范词升序</span>
        </div>
        <div class="panel-body tight">
          <div class="scroll-x">
            <table class="data">
              <thead>
                <tr>
                  <th class="num">#</th>
                  <th>领域</th>
                  <th class="num">论文数</th>
                  <th class="num">占样本</th>
                  <th class="num">来源标注</th>
                  <th class="num">系统推断</th>
                  <th>年份跨度</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(item, index) in items" :key="item.topicId">
                  <td class="num">{{ index + 1 }}</td>
                  <td>
                    <RouterLink :to="`/topics/${item.topicId}`">
                      {{ item.canonicalNameKnown === false ? '（名称未提供）' : item.canonicalName }}
                    </RouterLink>
                    <div class="tiny faint mono">{{ item.topicId }}</div>
                  </td>
                  <td class="num">{{ item.achievements.toLocaleString() }}</td>
                  <td class="num">{{ share(item) }}</td>
                  <td class="num">{{ item.sourceProvided.toLocaleString() }}</td>
                  <td class="num">{{ item.systemInferred.toLocaleString() }}</td>
                  <td class="tiny">{{ span(item) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="tiny faint" style="margin: 10px 0 0">
            「占样本」的分母是窗口内全部公开论文，而一篇论文可属于多个领域，因此各行相加可以超过 100%。
            这不是数据错误，是多对多归属的正常结果。
          </p>
        </div>
      </div>

      <div class="panel">
        <div class="panel-head"><h2>口径说明</h2></div>
        <div class="panel-body">
          <dl class="dl">
            <template v-for="[key, value] in rules" :key="key">
              <dt class="mono tiny">{{ key }}</dt>
              <dd class="small">{{ value }}</dd>
            </template>
          </dl>
          <p class="tiny faint" style="margin: 10px 0 0">
            规则版本 {{ String(hotspots.data.value?.ruleVersion ?? '') }} ·
            数据版本 {{ String(hotspots.data.value?.publishedRevision ?? '') }} ·
            生成于 {{ String(hotspots.data.value?.generatedAt ?? '') }}
          </p>
        </div>
      </div>
    </AsyncBlock>
  </div>
</template>
