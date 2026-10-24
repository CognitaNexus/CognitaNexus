<script setup lang="ts">
/**
 * 机构维度（FR11 机构排名的可浏览版本）。
 *
 * 为什么单独一页：总览里已经有「机构前 20 名」，但那一份只够做首页排行。要回答「某个大学到底有
 * 哪些成果、多少位学者」需要能搜索、能翻页、能下钻——而这三件事恰好是排行卡片做不到的。
 *
 * 三个必须写清楚的边界：
 *  1. 按来源给出的机构原值分组，同一机构的不同写法（带院系、带城市）会各自成条。这是投影的事实，
 *     不是 bug；启发式合并会把「University of California, Berkeley」并进「University of California」，
 *     比碎片化更糟；
 *  2. 计数口径与总览排行榜完全一致（同成果同机构只计一次），否则会出现「排行第 5 名点进去只有
 *     3 条」这种自相矛盾；
 *  3. 下钻用「逐字相等」匹配而不是模糊匹配——模糊匹配会让点进 A 机构看到 B 机构的成果。
 */
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { queryApi } from '@/api'
import { useScopeStore } from '@/stores/scope'
import { useAsync } from '@/composables/useAsync'
import AsyncBlock from '@/components/AsyncBlock.vue'

const scope = useScopeStore()

const keyword = ref('')
const appliedKeyword = ref('')
const page = ref(1)
const size = 20

const list = useAsync<Record<string, unknown>>(() =>
  queryApi.organizations({
    q: appliedKeyword.value || undefined,
    dataNature: scope.nature,
    page: page.value,
    size,
  }),
)

/** 选中的机构名。为空表示还没下钻。 */
const selected = ref('')
const detailPage = ref(1)
const detail = useAsync<Record<string, unknown>>(
  () =>
    selected.value
      ? queryApi.organizationDetail({
          organization: selected.value,
          dataNature: scope.nature,
          page: detailPage.value,
          size,
        })
      : Promise.resolve({}),
)

interface OrganizationItem {
  organization: string
  achievementCount: number
  scholarCount: number
}

interface AchievementItem {
  achievementId: string
  title?: string | null
  type?: string | null
  publicationYear?: number | null
  venue?: string | null
  doi?: string | null
  citationCount?: number | null
}

const items = computed<OrganizationItem[]>(
  () => (list.data.value?.items as OrganizationItem[] | undefined) ?? [],
)
const total = computed(() => Number(list.data.value?.total ?? 0))
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))

const detailItems = computed<AchievementItem[]>(
  () => (detail.data.value?.items as AchievementItem[] | undefined) ?? [],
)
const detailTotal = computed(() => Number(detail.data.value?.total ?? 0))
const detailTotalPages = computed(() => Math.max(1, Math.ceil(detailTotal.value / size)))

const scopeNote = computed(() => {
  const value = list.data.value?.scope as Record<string, unknown> | undefined
  return value ? String(value.note ?? '') : ''
})

function search() {
  appliedKeyword.value = keyword.value.trim()
  page.value = 1
  // 换了搜索词就收起下钻：否则下面那块还挂着上一个词里的机构，看起来像搜索结果的一部分。
  selected.value = ''
  list.run()
}

function open(organization: string) {
  selected.value = organization
  detailPage.value = 1
  detail.run()
}

function close() {
  selected.value = ''
}

function go(next: number) {
  const target = Math.min(Math.max(next, 1), totalPages.value)
  if (target === page.value) return
  page.value = target
  list.run()
}

function goDetail(next: number) {
  const target = Math.min(Math.max(next, 1), detailTotalPages.value)
  if (target === detailPage.value) return
  detailPage.value = target
  detail.run()
}

function typeLabel(raw: string | null | undefined) {
  switch (raw) {
    case 'PAPER':
      return '论文'
    case 'PATENT':
      return '专利'
    case 'PROJECT':
      return '项目'
    case 'AWARD':
      return '获奖'
    default:
      return raw ?? '类型未知'
  }
}

onMounted(() => list.run())
</script>

<template>
  <div>
    <div style="display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap">
      <h1>机构</h1>
      <button class="btn small" @click="list.run()">刷新</button>
    </div>

    <div class="notice info">
      按来源给出的机构<strong>原值</strong>分组：同一机构的不同写法（含院系、含城市）会各自成条，这里不做
      启发式合并。计数与总览排行榜同一口径——同一成果在同一机构下只计一次，跨机构各计一次。
      <span v-if="scopeNote"> {{ scopeNote }}</span>
    </div>

    <div class="panel">
      <div class="panel-body">
        <div style="display: flex; gap: 8px; flex-wrap: wrap; align-items: center">
          <input
            v-model="keyword"
            placeholder="按机构名搜索（大小写不敏感，支持片段）"
            style="min-width: 260px"
            @keyup.enter="search"
          />
          <button class="btn small primary" @click="search">搜索</button>
          <span class="tiny faint">共 {{ total }} 个机构</span>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-body tight">
        <AsyncBlock
          :loading="list.loading.value"
          :error="list.error.value"
          :error-code="list.errorCode.value"
          :empty="items.length === 0"
          empty-text="没有匹配的机构。换一个搜索词，或确认当前数据口径下是否有机构归属。"
        >
          <table class="data">
            <thead>
              <tr>
                <th>机构</th>
                <th style="width: 110px">成果数</th>
                <th style="width: 110px">学者数</th>
                <th style="width: 90px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in items" :key="row.organization">
                <td class="tiny">{{ row.organization }}</td>
                <td>{{ row.achievementCount }}</td>
                <td>{{ row.scholarCount }}</td>
                <td>
                  <button class="btn small" @click="open(row.organization)">看成果</button>
                </td>
              </tr>
            </tbody>
          </table>
          <div
            v-if="totalPages > 1"
            style="display: flex; gap: 8px; align-items: center; justify-content: center; margin-top: 12px"
          >
            <button class="btn small" :disabled="page <= 1" @click="go(page - 1)">上一页</button>
            <span class="tiny faint">第 {{ page }} / {{ totalPages }} 页</span>
            <button class="btn small" :disabled="page >= totalPages" @click="go(page + 1)">下一页</button>
          </div>
        </AsyncBlock>
      </div>
    </div>

    <div v-if="selected" class="panel">
      <div class="panel-body">
        <div style="display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap">
          <h2 style="margin: 0; font-size: 16px">机构成果：{{ selected }}</h2>
          <button class="btn small" @click="close">收起</button>
        </div>
        <div class="tiny faint" style="margin-top: 6px">
          共 {{ detailTotal }} 条。按年份与引用量排序；这里只列成果本身，学者归属请看学者页。
        </div>
      </div>
      <div class="panel-body tight">
        <AsyncBlock
          :loading="detail.loading.value"
          :error="detail.error.value"
          :error-code="detail.errorCode.value"
          :empty="detailItems.length === 0"
          empty-text="该机构当前口径下没有公开成果。"
        >
          <table class="data">
            <thead>
              <tr>
                <th>成果</th>
                <th style="width: 80px">类型</th>
                <th style="width: 80px">年份</th>
                <th style="width: 90px">引用</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in detailItems" :key="row.achievementId">
                <td>
                  <RouterLink :to="`/achievements/${row.achievementId}`">
                    {{ row.title ?? row.achievementId }}
                  </RouterLink>
                  <div class="tiny faint">{{ row.venue ?? '来源未给出' }}</div>
                </td>
                <td class="tiny">{{ typeLabel(row.type) }}</td>
                <td class="tiny">{{ row.publicationYear ?? '年份未知' }}</td>
                <td class="tiny">{{ row.citationCount ?? '未采集' }}</td>
              </tr>
            </tbody>
          </table>
          <div
            v-if="detailTotalPages > 1"
            style="display: flex; gap: 8px; align-items: center; justify-content: center; margin-top: 12px"
          >
            <button class="btn small" :disabled="detailPage <= 1" @click="goDetail(detailPage - 1)">
              上一页
            </button>
            <span class="tiny faint">第 {{ detailPage }} / {{ detailTotalPages }} 页</span>
            <button
              class="btn small"
              :disabled="detailPage >= detailTotalPages"
              @click="goDetail(detailPage + 1)"
            >
              下一页
            </button>
          </div>
        </AsyncBlock>
      </div>
    </div>
  </div>
</template>
