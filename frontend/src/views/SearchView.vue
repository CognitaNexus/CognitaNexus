<script setup lang="ts">
/**
 * 成果检索页。
 *
 * 两个必须做对的交互细节：
 *
 * 1. **查询版本**：分页时带上第一页拿到的 queryVersion。服务端会在已发布修订前进后拒绝继续
 *    分页（QUERY_VERSION_CHANGED），这时不能装作没事继续显示，也不能把两页结果拼在一起——
 *    而是明确告诉用户「数据已更新，请重新开始」，并自动回到第一页。
 * 2. **筛选条件写进地址栏**：检索结果需要可分享、可回退。把条件放在组件内部状态里，用户
 *    刷新或分享链接就会丢掉所有条件，看到一份「不同条件的同名页面」。
 *
 * 结果列表按文献条目的排版组织（标题—作者—来源—计量），这是学术检索站的通行读法。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { queryApi } from '@/api'
import { ApiError } from '@/api/http'
import type { Facets, SearchOptions, SearchPage, TopicView } from '@/api/types'
import { useAsync } from '@/composables/useAsync'
import { useScopeStore } from '@/stores/scope'
import AsyncBlock from '@/components/AsyncBlock.vue'
import Pager from '@/components/Pager.vue'

const route = useRoute()
const router = useRouter()
const scope = useScopeStore()

const facets = useAsync<Facets>(() => queryApi.facets(scope.nature, 50))
const options = useAsync<SearchOptions>(() => queryApi.options())

const keyword = ref((route.query.q as string) ?? '')
const yearFrom = ref((route.query.yearFrom as string) ?? '')
const yearTo = ref((route.query.yearTo as string) ?? '')
const organization = ref((route.query.organization as string) ?? '')
const topic = ref((route.query.topic as string) ?? '')
const sort = ref((route.query.sort as string) ?? 'relevance')
const page = ref(Number(route.query.page ?? 1))

/** 折叠筛选条件：默认收起，让结果尽可能早地出现在首屏。 */
const filtersOpen = ref(
  Boolean(route.query.yearFrom || route.query.yearTo || route.query.organization || route.query.topic),
)

/** 第一页拿到的版本号，后续翻页沿用；条件变化时必须清空，否则会被误判为过期。 */
const pinnedVersion = ref<string | null>(null)
const versionNotice = ref('')

const loading = ref(false)
const error = ref('')
const errorCode = ref('')
const data = ref<SearchPage | null>(null)

/** 排序取值的说明。只给代号会让读者不知道 relevance 与 cited 的差别。 */
const SORT_LABELS: Record<string, string> = {
  relevance: '相关度',
  year: '年份（新→旧）',
  'year-desc': '年份（新→旧）',
  'year-asc': '年份（旧→新）',
  citations: '引用量（多→少）',
  'citation-desc': '引用量（多→少）',
  title: '标题',
}

function sortLabel(value: string): string {
  return SORT_LABELS[value] ?? value
}

function syncUrl() {
  router.replace({
    query: {
      q: keyword.value || undefined,
      yearFrom: yearFrom.value || undefined,
      yearTo: yearTo.value || undefined,
      organization: organization.value || undefined,
      topic: topic.value || undefined,
      sort: sort.value === 'relevance' ? undefined : sort.value,
      page: page.value > 1 ? String(page.value) : undefined,
    },
  })
}

async function search(resetVersion = false) {
  if (resetVersion) pinnedVersion.value = null
  loading.value = true
  error.value = ''
  errorCode.value = ''
  versionNotice.value = ''
  try {
    const pageData = await queryApi.searchAchievements({
      q: keyword.value || undefined,
      yearFrom: yearFrom.value ? Number(yearFrom.value) : undefined,
      yearTo: yearTo.value ? Number(yearTo.value) : undefined,
      organization: organization.value || undefined,
      topic: topic.value || undefined,
      dataNature: scope.nature,
      sort: sort.value,
      page: page.value,
      size: 10,
      queryVersion: pinnedVersion.value ?? undefined,
    })
    data.value = pageData
    if (pinnedVersion.value === null) pinnedVersion.value = pageData.queryVersion
    scope.observe(pageData)
  } catch (cause) {
    if (cause instanceof ApiError && cause.isQueryVersionChanged) {
      // 版本过期不是错误，是「数据在这两页之间变了」。回到第一页重新取一份一致的快照，
      // 并把这件事说出来——静默重取会让用户以为翻页正常，实际看到的还是第一页。
      versionNotice.value =
        '本次翻页期间公开数据已更新，为避免把两个时刻的结果混在同一列表里，已回到第一页重新查询。'
      pinnedVersion.value = null
      page.value = 1
      await search(true)
      return
    }
    data.value = null
    error.value = cause instanceof Error ? cause.message : String(cause)
    errorCode.value = cause instanceof ApiError ? cause.code : ''
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  page.value = 1
  syncUrl()
  search(true)
}

function changePage(target: number) {
  page.value = target
  syncUrl()
  search(false)
}

function resetFilters() {
  keyword.value = ''
  yearFrom.value = ''
  yearTo.value = ''
  organization.value = ''
  topic.value = ''
  sort.value = 'relevance'
  page.value = 1
  filtersOpen.value = false
  syncUrl()
  search(true)
}

const yearRangeInvalid = computed(
  () => !!(yearFrom.value && yearTo.value && Number(yearFrom.value) > Number(yearTo.value)),
)

/** 已生效的筛选条件摘要，收起筛选区后仍能一眼看出结果被什么限制住了。 */
const activeFilters = computed(() => {
  const items: string[] = []
  if (yearFrom.value || yearTo.value) {
    items.push(`年份 ${yearFrom.value || '不限'}–${yearTo.value || '不限'}`)
  }
  if (organization.value) items.push(`机构：${organization.value}`)
  if (topic.value) {
    const found = facets.data.value?.topics.find((item) => item.topicId === topic.value)
    items.push(`领域：${found?.canonicalName ?? topic.value}`)
  }
  return items
})

/**
 * 结果卡片上的领域标签。
 *
 * 推断出来的领域必须在这里就能被认出不是来源给的：卡片是读者第一眼看到的地方，等到点进详情
 * 才知道「这个领域是系统猜的」，等于让读者先信了一次错的东西。来源分类不加任何修饰，
 * 加了修饰反而会让读者以为所有标签都不可靠。
 */
function topicLabel(topic: TopicView): string {
  return topic.assignmentKind === 'INFERRED'
    ? `${topic.canonicalName}（系统推断）`
    : topic.canonicalName
}

onMounted(() => {
  facets.run()
  options.run()
  search(true)
})

// 口径切换等价于换一组数据，必须回到第一页并重新绑定版本号。
watch(
  () => scope.nature,
  () => {
    facets.run()
    applyFilters()
  },
)
</script>

<template>
  <div>
    <div class="section-head" style="margin-top: 0">
      <h2 style="font-size: 19px">成果检索</h2>
      <span class="small dim">
        当前口径 <span class="mono">{{ scope.nature }}</span> · 默认只含真实公开元数据
      </span>
    </div>

    <div class="panel">
      <div class="panel-body">
        <form class="btn-row" @submit.prevent="applyFilters">
          <input
            v-model="keyword"
            type="search"
            placeholder="标题关键词、DOI 或作者"
            style="flex: 1; min-width: 260px; height: 34px"
            aria-label="检索关键词"
          />
          <select v-model="sort" style="width: auto; height: 34px" aria-label="排序方式">
            <option v-for="item in options.data.value?.sorts ?? ['relevance']" :key="item" :value="item">
              {{ sortLabel(item) }}
            </option>
          </select>
          <button class="btn primary" type="submit" :disabled="loading || yearRangeInvalid">检索</button>
          <button class="btn" type="button" @click="filtersOpen = !filtersOpen">
            {{ filtersOpen ? '收起筛选' : '筛选条件' }}
          </button>
          <button class="btn" type="button" :disabled="loading" @click="resetFilters">重置</button>
        </form>

        <div v-if="activeFilters.length" class="tiny dim" style="margin-top: 9px">
          已应用：{{ activeFilters.join('；') }}
        </div>

        <div v-if="filtersOpen" style="margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--rule)">
          <div class="grid cols-3">
            <div class="field">
              <label for="yf">起始年份</label>
              <input id="yf" v-model="yearFrom" type="number" placeholder="如 2015" />
            </div>
            <div class="field">
              <label for="yt">截止年份</label>
              <input id="yt" v-model="yearTo" type="number" placeholder="如 2025" />
            </div>
            <div class="field">
              <label for="org">机构（成果发生时）</label>
              <select id="org" v-model="organization">
                <option value="">不限</option>
                <option
                  v-for="item in facets.data.value?.organizations ?? []"
                  :key="item.name"
                  :value="item.name"
                >
                  {{ item.name }}（{{ item.achievement_count }}）
                </option>
              </select>
            </div>
            <div class="field">
              <label for="topic">领域</label>
              <select id="topic" v-model="topic">
                <option value="">不限</option>
                <option
                  v-for="item in facets.data.value?.topics ?? []"
                  :key="item.topicId"
                  :value="item.topicId"
                >
                  {{ item.canonicalName }}（{{ item.achievementCount }}）{{
                    item.systemInferred ? ` · 其中推断 ${item.systemInferred}` : ''
                  }}
                </option>
              </select>
            </div>
          </div>

          <div v-if="yearRangeInvalid" class="notice warn tiny" style="margin: 0 0 12px">
            起始年份晚于截止年份，这样查不出任何结果。请调整后再检索。
          </div>

          <button class="btn primary" :disabled="loading || yearRangeInvalid" @click="applyFilters">
            应用筛选
          </button>
        </div>
      </div>
    </div>

    <div v-if="yearRangeInvalid" class="strip warn">年份区间反向：结果必然为空。</div>
    <div v-if="versionNotice" class="notice info">{{ versionNotice }}</div>

    <AsyncBlock
      :loading="loading"
      :error="error"
      :error-code="errorCode"
      :empty="!loading && !error && (data?.items.length ?? 0) === 0"
      empty-text="没有符合条件的成果。可放宽年份或机构条件，或清空关键词。"
    >
      <template v-if="data">
        <div class="panel">
          <div class="panel-head">
            <h2>命中 {{ data.total.toLocaleString() }} 项</h2>
            <span class="tiny faint mono">
              快照 {{ data.queryVersion }} · 规则 {{ data.ruleVersion }} · 排序 {{ sortLabel(data.appliedSort) }}
            </span>
          </div>

          <article v-for="item in data.items" :key="item.id" class="result-item">
            <h3 class="result-title">
              <RouterLink :to="`/achievements/${item.id}`">{{ item.title }}</RouterLink>
            </h3>

            <div v-if="item.authors.length" class="result-authors">
              {{ item.authors.slice(0, 8).join('、') }}
              <span v-if="item.authors.length > 8" class="faint">等 {{ item.authors.length }} 位</span>
            </div>

            <div class="result-source">
              <template v-if="item.venue">{{ item.venue }}</template>
              <template v-else>来源未提供发表载体</template>
              ·
              <template v-if="item.publicationYear">{{ item.publicationYear }} 年</template>
              <template v-else><span class="faint">年份未知</span></template>
            </div>

            <div class="result-meta" style="margin-top: 5px">
              <span class="badge neutral">{{ item.type }}</span>
              <span
                v-if="item.citationCount !== null"
                class="mono"
                :title="`引用量来源：${item.citationSource ?? '未标注'}`"
              >
                被引 {{ item.citationCount.toLocaleString() }}
                <span class="faint">（{{ item.citationSource ?? '来源未标注' }}）</span>
              </span>
              <span v-else class="faint">来源未提供引用量</span>
              <span v-if="item.doi" class="mono faint">DOI {{ item.doi }}</span>
            </div>

            <div class="result-links">
              <RouterLink :to="`/achievements/${item.id}`">查看详情</RouterLink>
              <span class="faint mono">{{ item.id }}</span>
              <span v-if="item.topics.length" class="faint">
                <template v-for="(t, i) in item.topics" :key="t.topicId">
                  <RouterLink :to="`/topics/${t.topicId}`">{{ topicLabel(t) }}</RouterLink
                  ><span v-if="i < item.topics.length - 1"> · </span>
                </template>
              </span>
            </div>
          </article>

          <Pager
            :page="data.page"
            :size="data.size"
            :total="data.total"
            :max-offset="data.maxOffset"
            :disabled="loading"
            @change="changePage"
          />
        </div>

        <p class="tiny faint">
          口径说明：{{ data.scopeNote }} 深翻页上限 {{ data.maxOffset }} 条，超过时请收窄条件而不是继续翻页。
        </p>
      </template>
    </AsyncBlock>
  </div>
</template>
