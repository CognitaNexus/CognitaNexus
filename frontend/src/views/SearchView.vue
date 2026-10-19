<script setup lang="ts">
/**
 * 检索页：成果与学者两个作用域共用一个页面。
 *
 * 三条必须做对的交互细节：
 *
 * 1. **作用域与筛选条件写进地址栏**：检索结果需要可分享、可回退。条件放在组件内部状态里，
 *    用户刷新或分享链接就会丢掉所有条件，看到一份「不同条件的同名页面」；前进／后退也会
 *    只改地址不改结果。这里以路由为唯一条件来源：用户操作先写 URL，路由变化再驱动检索。
 * 2. **请求竞态**：连续检索时慢的旧请求可能晚于新请求返回，把旧结果盖在新条件上。每次请求
 *    带一个递增序号，回来时不是最新那次就整段丢弃——列表、loading 与错误一起丢弃，不能只丢列表。
 * 3. **查询版本**：分页时带第一页拿到的 queryVersion。服务端在已发布修订前进后拒绝继续分页
 *    （QUERY_VERSION_CHANGED）。这时自动重取一次是合理的，但**只能一次**：无限重试会让
 *    用户看不到任何提示，也不知道自己已经被挪回第一页。
 *
 * 学者检索与成果检索是两项不同的能力：学者结果的字段只有姓名、公开成果数与最早年份，
 * 不足以区分同名者，因此每条都给出档案入口，让用户进档案按机构核对，而不是在列表里猜。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { queryApi } from '@/api'
import { ApiError } from '@/api/http'
import type { AchievementKind, Facets, ScholarPage, SearchOptions, SearchPage, TopicView } from '@/api/types'
import { useAsync } from '@/composables/useAsync'
import { downloadHref, warningTone } from '@/composables/citationView'
import { useCitationBatch } from '@/composables/useCitationBatch'
import { useScopeStore } from '@/stores/scope'
import AsyncBlock from '@/components/AsyncBlock.vue'
import Pager from '@/components/Pager.vue'
import SearchSuggest from '@/components/SearchSuggest.vue'

type Scope = 'achievements' | 'scholars'

const route = useRoute()
const router = useRouter()
const scope = useScopeStore()

const facets = useAsync<Facets>(() => queryApi.facets(scope.nature, 50))
const options = useAsync<SearchOptions>(() => queryApi.options())

const searchScope = ref<Scope>(route.query.scope === 'scholars' ? 'scholars' : 'achievements')
const keyword = ref(text(route.query.q))
const type = ref(text(route.query.type))
const yearFrom = ref(text(route.query.yearFrom))
const yearTo = ref(text(route.query.yearTo))
const organization = ref(text(route.query.organization))
const topic = ref(text(route.query.topic))
const sort = ref(text(route.query.sort) || 'relevance')
const page = ref(positiveInt(route.query.page))

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
const achievementData = ref<SearchPage | null>(null)
const scholarData = ref<ScholarPage | null>(null)

/** 请求序号：只有最新一次请求允许写回状态。 */
let requestSeq = 0
/** 上一次由本页写入的地址，用来区分「自己改的 URL」与「用户前进／后退／打开分享链接」。 */
let selfNavigated = ''

/**
 * 排序标签。
 *
 * <p>键必须与服务端返回的枚举逐字一致（下划线，不是连字符）：写成 `year-desc` 时下拉框里
 * 显示的是后端代号 `year_desc`，用户看到的是一串英文标识符。
 */
const SORT_LABELS: Record<string, string> = {
  relevance: '相关度',
  year_desc: '年份（新→旧）',
  year_asc: '年份（旧→新）',
  title_asc: '标题',
  citations_desc: '引用量（多→少）',
}

const TYPE_LABELS: Record<AchievementKind, string> = {
  PAPER: '论文',
  PATENT: '专利',
  PROJECT: '项目',
  AWARD: '获奖',
}

const TYPES: AchievementKind[] = ['PAPER', 'PATENT', 'PROJECT', 'AWARD']

function sortLabel(value: string): string {
  return SORT_LABELS[value] ?? value
}

function typeLabel(value: string): string {
  return TYPE_LABELS[value as AchievementKind] ?? value
}

/** 从路由参数还原条件。空字符串表示「不限」，与 URL 里没有该参数等价。 */
function applyRoute() {
  searchScope.value = route.query.scope === 'scholars' ? 'scholars' : 'achievements'
  keyword.value = text(route.query.q)
  type.value = text(route.query.type)
  yearFrom.value = text(route.query.yearFrom)
  yearTo.value = text(route.query.yearTo)
  organization.value = text(route.query.organization)
  topic.value = text(route.query.topic)
  sort.value = text(route.query.sort) || 'relevance'
  page.value = positiveInt(route.query.page)
}

function buildQuery(): Record<string, string> {
  const query: Record<string, string> = {}
  if (keyword.value) query.q = keyword.value
  if (searchScope.value === 'scholars') query.scope = 'scholars'
  // 学者检索没有类型、年份、机构、领域这些条件；把它们写进 URL 会让人以为条件生效了。
  if (searchScope.value === 'achievements') {
    if (type.value) query.type = type.value
    if (yearFrom.value) query.yearFrom = yearFrom.value
    if (yearTo.value) query.yearTo = yearTo.value
    if (organization.value) query.organization = organization.value
    if (topic.value) query.topic = topic.value
    if (sort.value !== 'relevance') query.sort = sort.value
  }
  if (page.value > 1) query.page = String(page.value)
  return query
}

async function syncUrl() {
  const target = router.resolve({ path: '/search', query: buildQuery() }).fullPath
  if (target === route.fullPath) return
  selfNavigated = target
  await router.replace({ path: '/search', query: buildQuery() })
}

// ---------------------------------------------------------------------------
// 批量引用导出：勾选本页若干成果 → 一份可直接导入文献管理软件的文件。
//
// 勾选只在当前结果页内有效：跨页累计会让「已选 5 条」里有几条根本看不见，用户点导出时无法在
// 同屏核对将要得到什么。因此每次检索/翻页后都会把已不可见的勾选剪掉。
// ---------------------------------------------------------------------------
const bulk = useCitationBatch()

const FORMAT_OPTIONS = [
  { code: 'bibtex', label: 'BibTeX' },
  { code: 'ris', label: 'RIS' },
  { code: 'gb_t_7714', label: 'GB/T 7714' },
  { code: 'apa', label: 'APA' },
]

const visibleIds = computed(() => (achievementData.value?.items ?? []).map((item) => item.id))

// ---------------------------------------------------------------------------
// 导出 CSV：按当前条件与口径拉文件并存为下载。与在线检索共用同一套资格条件，
// 导出的每一行都能在检索页翻到（上限内）；超限的错误原因由服务端给出并直接展示。
// ---------------------------------------------------------------------------
const exporting = ref(false)
const exportError = ref('')

async function exportCsv() {
  if (!achievementData.value || exporting.value) return
  exporting.value = true
  exportError.value = ''
  try {
    const envelope = await queryApi.exportAchievementsCsv({
      q: keyword.value || undefined,
      type: (type.value || undefined) as AchievementKind | undefined,
      yearFrom: yearFrom.value ? Number(yearFrom.value) : undefined,
      yearTo: yearTo.value ? Number(yearTo.value) : undefined,
      organization: organization.value || undefined,
      topic: topic.value || undefined,
      dataNature: scope.nature,
      sort: sort.value === 'relevance' ? undefined : sort.value,
    })
    const url = URL.createObjectURL(envelope.data)
    const link = document.createElement('a')
    link.href = url
    link.download = `achievements-${new Date().toISOString().slice(0, 10)}.csv`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } catch (cause) {
    exportError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    exporting.value = false
  }
}

/**
 * 执行检索。
 *
 * <p>notice 由调用方决定是否清空：版本过期时本方法会被自己再调一次，若在这里清空提示，
 * 那句「数据已更新，已重新查询」会在用户看到之前就被抹掉。
 */
async function search(resetVersion = false) {
  if (resetVersion) pinnedVersion.value = null
  const mine = ++requestSeq
  loading.value = true
  error.value = ''
  errorCode.value = ''

  const scholarMode = searchScope.value === 'scholars'
  try {
    if (scholarMode) {
      const pageData = await queryApi.searchScholars({
        q: keyword.value || undefined,
        dataNature: scope.nature,
        page: page.value,
        size: 10,
      })
      if (mine !== requestSeq) return
      scholarData.value = pageData
      achievementData.value = null
      scope.observe(pageData)
      pinnedVersion.value = pageData.queryVersion
    } else {
      const pageData = await queryApi.searchAchievements({
        q: keyword.value || undefined,
        type: (type.value || undefined) as AchievementKind | undefined,
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
      if (mine !== requestSeq) return
      achievementData.value = pageData
      scholarData.value = null
      // 结果集变了：屏幕上已经没有的成果不能再算「已选」。
      bulk.prune(pageData.items.map((item) => item.id))
      if (pinnedVersion.value === null) pinnedVersion.value = pageData.queryVersion
      scope.observe(pageData)
    }
    if (mine !== requestSeq) return
    loading.value = false
  } catch (cause) {
    // 旧请求的错误同样要丢：它描述的是用户已经放弃的那次查询。
    if (mine !== requestSeq) return
    if (cause instanceof ApiError && cause.isQueryVersionChanged && !scholarMode) {
      if (versionNotice.value === '') {
        // 第一次遇到版本变化：回到第一页重取一份一致快照，并把这件事说出来。
        versionNotice.value =
          '本次翻页期间公开数据已更新。为避免把两个时刻的结果混在同一列表里，已回到第一页重新查询一次。'
        pinnedVersion.value = null
        page.value = 1
        loading.value = false
        await syncUrl()
        await search(true)
        return
      }
      versionNotice.value =
        '公开数据仍在更新，自动重试一次后仍未取到一致快照。请稍后手动刷新，或收窄条件重新检索。'
      achievementData.value = null
      error.value = '数据正在更新，本次请求未取到一致快照。'
      errorCode.value = cause.code
      loading.value = false
      return
    }
    achievementData.value = null
    scholarData.value = null
    error.value = cause instanceof Error ? cause.message : String(cause)
    errorCode.value = cause instanceof ApiError ? cause.code : ''
    loading.value = false
  }
}

function applyFilters() {
  page.value = 1
  versionNotice.value = ''
  syncUrl().then(() => search(true))
}

/**
 * 选中一条建议：成果直接进详情，学者直接进档案。
 *
 * 检索结果页存在的意义是「筛选与比较」，不是「确认某一条」。用户已经在建议里认出目标，
 * 再把他送到列表里挑一次，只会多一次点击。
 */
function onPickSuggestion(payload: { kind: 'achievement' | 'scholar'; id: string }) {
  if (payload.kind === 'scholar') {
    router.push({ name: 'scholar', params: { id: payload.id } })
    return
  }
  router.push({ name: 'achievement', params: { id: payload.id } })
}

function changePage(target: number) {
  page.value = target
  versionNotice.value = ''
  syncUrl().then(() => search(false))
}

function switchScope(next: Scope) {
  if (searchScope.value === next) return
  searchScope.value = next
  page.value = 1
  versionNotice.value = ''
  error.value = ''
  syncUrl().then(() => search(true))
}

function resetFilters() {
  keyword.value = ''
  type.value = ''
  yearFrom.value = ''
  yearTo.value = ''
  organization.value = ''
  topic.value = ''
  sort.value = 'relevance'
  page.value = 1
  filtersOpen.value = false
  versionNotice.value = ''
  syncUrl().then(() => search(true))
}

const yearRangeInvalid = computed(
  () => !!(yearFrom.value && yearTo.value && Number(yearFrom.value) > Number(yearTo.value)),
)

/** 已生效的筛选条件摘要，收起筛选区后仍能一眼看出结果被什么限制住了。 */
const activeFilters = computed(() => {
  const items: string[] = []
  if (type.value) items.push(`类型：${typeLabel(type.value)}`)
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

const isEmpty = computed(() => {
  if (loading.value || error.value) return false
  if (searchScope.value === 'scholars') return (scholarData.value?.items.length ?? 0) === 0
  return (achievementData.value?.items.length ?? 0) === 0
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

function text(value: unknown): string {
  return typeof value === 'string' ? value : Array.isArray(value) && typeof value[0] === 'string' ? value[0] : ''
}

function positiveInt(value: unknown): number {
  const parsed = Number(text(value) || 1)
  return Number.isFinite(parsed) && parsed >= 1 ? Math.floor(parsed) : 1
}

/** 格式下拉的切换。用显式方法而不是 v-model 直连 ref：切换格式必须作废上一份预览。 */
function onBatchFormatChange(event: Event) {
  const target = event.target as HTMLSelectElement
  bulk.setFormat(target.value)
}

onMounted(() => {
  facets.run()
  options.run()
  search(true)
})

// 路由变化才驱动检索：用户点前进／后退、粘贴分享链接、或从导航栏进入带条件的检索页，
// 都以地址栏为准。自己写入的地址由 selfNavigated 识别，避免同一次操作发两次请求。
watch(
  () => route.fullPath,
  (fullPath) => {
    if (!fullPath.startsWith('/search')) return
    if (fullPath === selfNavigated) return
    applyRoute()
    pinnedVersion.value = null
    versionNotice.value = ''
    search(true)
  },
)

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
      <h2 style="font-size: 19px">检索</h2>
      <span class="small dim">
        当前口径 <span class="mono">{{ scope.nature }}</span> · 默认只含真实公开元数据
      </span>
    </div>

    <div class="panel">
      <div class="panel-body">
        <div class="btn-row" style="margin-bottom: 12px">
          <button
            class="btn"
            :class="{ primary: searchScope === 'achievements' }"
            type="button"
            @click="switchScope('achievements')"
          >
            成果
          </button>
          <button
            class="btn"
            :class="{ primary: searchScope === 'scholars' }"
            type="button"
            @click="switchScope('scholars')"
          >
            学者
          </button>
          <span class="tiny faint" style="align-self: center">
            {{
              searchScope === 'achievements'
                ? '按题名、作者、摘要、载体或 DOI 查找成果'
                : '按姓名查找学者；同名者请进档案按机构核对，系统不替你做身份合并'
            }}
          </span>
        </div>

        <form class="btn-row" @submit.prevent="applyFilters">
          <SearchSuggest
            v-model="keyword"
            hide-button
            :data-nature="scope.nature"
            :placeholder="
              searchScope === 'achievements' ? '题名、作者、摘要、载体或 DOI' : '学者姓名'
            "
            style="flex: 1; min-width: 260px"
            aria-label="检索关键词"
            @submit="applyFilters()"
            @pick="onPickSuggestion"
          />
          <select
            v-if="searchScope === 'achievements'"
            v-model="sort"
            style="width: auto; height: 34px"
            aria-label="排序方式"
          >
            <option v-for="item in options.data.value?.sorts ?? ['relevance']" :key="item" :value="item">
              {{ sortLabel(item) }}
            </option>
          </select>
          <button class="btn primary" type="submit" :disabled="loading || yearRangeInvalid">检索</button>
          <button
            v-if="searchScope === 'achievements'"
            class="btn"
            type="button"
            @click="filtersOpen = !filtersOpen"
          >
            {{ filtersOpen ? '收起筛选' : '筛选条件' }}
          </button>
          <button class="btn" type="button" :disabled="loading" @click="resetFilters">重置</button>
        </form>

        <div v-if="activeFilters.length" class="tiny dim" style="margin-top: 9px">
          已应用：{{ activeFilters.join('；') }}
        </div>

        <div
          v-if="filtersOpen && searchScope === 'achievements'"
          style="margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--rule)"
        >
          <div class="grid cols-3">
            <div class="field">
              <label for="tp">成果类型</label>
              <select id="tp" v-model="type">
                <option value="">不限</option>
                <option v-for="item in TYPES" :key="item" :value="item">{{ typeLabel(item) }}</option>
              </select>
            </div>
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

    <div v-if="yearRangeInvalid && searchScope === 'achievements'" class="strip warn">
      年份区间反向：结果必然为空。
    </div>
    <div v-if="versionNotice" class="notice info">{{ versionNotice }}</div>

    <AsyncBlock
      :loading="loading"
      :error="error"
      :error-code="errorCode"
      :empty="isEmpty"
      :empty-text="
        searchScope === 'scholars'
          ? '没有匹配的学者。可尝试更短的姓名片段，或换用成果检索按题名查找。'
          : '没有符合条件的成果。可放宽类型、年份或机构条件，或清空关键词。'
      "
    >
      <template v-if="searchScope === 'achievements' && achievementData">
        <div class="panel">
          <div class="panel-head">
            <h2>命中 {{ achievementData.total.toLocaleString() }} 项成果</h2>
            <span class="tiny faint mono">
              快照 {{ achievementData.queryVersion }} · 规则 {{ achievementData.ruleVersion }} · 排序
              {{ sortLabel(achievementData.appliedSort) }}
              <button
                class="btn small"
                style="margin-left: 10px"
                :disabled="exporting"
                title="按当前条件与口径导出全部命中（上限 5000 条）"
                @click="exportCsv"
              >
                {{ exporting ? '正在导出…' : '导出 CSV' }}
              </button>
              <span v-if="exportError" class="tiny" style="color: var(--danger, #b00); margin-left: 8px">{{
                exportError
              }}</span>
            </span>

            <!-- 批量引用导出：勾选在本页内有效，计数就写在按钮旁边，避免「选了但不知道选了几条」。 -->
            <div class="btn-row no-print" style="margin-left: auto">
              <span class="tiny dim" style="align-self: center">已选 {{ bulk.count.value }} 条</span>
              <button
                class="btn small"
                type="button"
                :disabled="!visibleIds.length"
                @click="bulk.selectAll(visibleIds)"
              >
                {{
                  visibleIds.length && bulk.count.value === visibleIds.length ? '取消全选' : '全选本页'
                }}
              </button>
              <button
                class="btn small"
                type="button"
                :disabled="!bulk.count.value"
                @click="bulk.clear()"
              >
                清空
              </button>
              <select
                style="width: auto; height: 28px"
                aria-label="批量导出的引用格式"
                :value="bulk.format.value"
                @change="onBatchFormatChange"
              >
                <option v-for="item in FORMAT_OPTIONS" :key="item.code" :value="item.code">
                  {{ item.label }}
                </option>
              </select>
              <button
                class="btn small primary"
                type="button"
                :disabled="!bulk.count.value || bulk.loading.value"
                title="把勾选的成果拼成一份文献库文件（一次最多 100 条）"
                @click="bulk.run()"
              >
                {{ bulk.loading.value ? '正在生成…' : '导出引用' }}
              </button>
            </div>
          </div>

          <article v-for="item in achievementData.items" :key="item.id" class="result-item">
            <h3 class="result-title">
              <label class="pick no-print" :title="`勾选「${item.title}」用于批量导出引用`">
                <input
                  type="checkbox"
                  :checked="bulk.isSelected(item.id)"
                  :aria-label="`选择「${item.title}」用于批量导出引用`"
                  @change="bulk.toggle(item.id)"
                />
              </label>
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
              <span class="badge neutral">{{ typeLabel(item.type) }}</span>
              <!-- 诚信标记：撤稿必须一眼可见，否则读者会先把它当成一条正常成果。
                   用可选链读取，是因为「服务端还没升级」与「确实没有声明」都不该让整个列表抛错。 -->
              <span
                v-if="item.integrity?.noticed"
                :class="item.integrity.status === 'RETRACTION' ? 'badge danger' : 'badge warn'"
                :title="item.integrity.label"
              >
                {{ item.integrity.label }}
              </span>
              <!-- 必须同时挡 null 与 undefined：服务端 Jackson 配了 non_null，null 字段直接从
                   报文里消失，前端拿到的是 undefined。原来只写 !== null，于是「无关键词浏览」
                   命中的项目类成果（本来就没有引用量）会让这里执行 undefined.toLocaleString()，
                   渲染抛错、整页停在「正在读取…」。带关键词检索时首条恰好是有引用量的论文，
                   所以这个崩溃只在默认入口出现，翻页与截图都发现不了。 -->
              <span
                v-if="item.citationCount !== null && item.citationCount !== undefined"
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
            :page="achievementData.page"
            :size="achievementData.size"
            :total="achievementData.total"
            :max-offset="achievementData.maxOffset"
            :disabled="loading"
            @change="changePage"
          />
        </div>

        <!-- 批量导出的结果面板。刻意与结果列表同屏：读者要能一边看文本一边核对自己勾了哪几条。
             预览作废逻辑在 useCitationBatch 里：选择或格式一变，这份文本就不再是「将要下载的内容」。 -->
        <div v-if="bulk.error.value || bulk.batch.value" class="panel no-print">
          <div class="panel-head">
            <h2>批量引用导出</h2>
            <span class="tiny faint">{{ bulk.summary.value }}</span>
          </div>
          <div class="panel-body">
            <p v-if="bulk.error.value" class="notice warn tiny" style="margin: 0">
              {{ bulk.error.value }}
            </p>
            <template v-else-if="bulk.batch.value">
              <div class="btn-row">
                <a
                  class="btn primary"
                  :href="downloadHref(bulk.batch.value.downloadUrl)"
                  :download="bulk.batch.value.fileName"
                >
                  下载 {{ bulk.batch.value.fileName }}
                </a>
                <button class="btn" type="button" @click="bulk.copy()">复制全文</button>
                <span class="tiny faint" style="align-self: center">
                  {{ bulk.batch.value.formatLabel }} ·
                  {{ bulk.batch.value.entries.length }} 条 · 共
                  {{ bulk.batch.value.text.length }} 字符
                </span>
              </div>

              <p class="tiny dim" style="margin: 10px 0 0">
                {{
                  bulk.batch.value.machineReadable
                    ? '该格式供文献管理软件读取（Zotero / EndNote / LaTeX），建议下载文件后导入；下载内容与下面这段逐字一致'
                    : '该格式用于粘贴进参考文献表，逐条已按规范编号；下载内容与下面这段逐字一致'
                }}
              </p>

              <pre class="pre" style="margin-top: 10px" aria-live="polite">{{
                bulk.batch.value.text
              }}</pre>

              <p v-if="bulk.copied.value" class="tiny" style="margin: 6px 0 0">
                {{ bulk.copied.value }}
              </p>

              <ul v-if="bulk.warnings.value.length" class="warn-list">
                <li
                  v-for="warning in bulk.warnings.value"
                  :key="warning.code"
                  :class="`warn-item ${warningTone(warning.code)}`"
                >
                  {{ warning.message }}
                </li>
              </ul>

              <!-- 被跳过的条目必须列出来：少给一条而不说，读者会以为是自己少点了。 -->
              <ul v-if="bulk.batch.value.skipped.length" class="warn-list">
                <li
                  v-for="item in bulk.batch.value.skipped"
                  :key="item.achievementId"
                  class="warn-item warn"
                >
                  {{ item.message }}（{{ item.achievementId }}）
                </li>
              </ul>

              <p class="tiny faint" style="margin: 10px 0 0">
                文件由 {{ bulk.batch.value.provenance }} 于
                {{ bulk.batch.value.exportedOn }} 依已登记元数据生成。
              </p>
              <p class="tiny faint" style="margin: 6px 0 0">{{ bulk.batch.value.note }}</p>
            </template>
          </div>
        </div>

        <p class="tiny faint">
          口径说明：{{ achievementData.scopeNote }} 深翻页上限 {{ achievementData.maxOffset }} 条，超过时请收窄条件而不是继续翻页。
        </p>
      </template>

      <template v-else-if="searchScope === 'scholars' && scholarData">
        <div class="panel">
          <div class="panel-head">
            <h2>命中 {{ scholarData.total.toLocaleString() }} 位学者</h2>
            <span class="tiny faint mono">
              快照 {{ scholarData.queryVersion }} · 固定按公开成果数降序（服务端当前只支持这一种学者排序）
            </span>
          </div>

          <article v-for="item in scholarData.items" :key="item.scholarId" class="result-item">
            <h3 class="result-title">
              <RouterLink :to="`/scholars/${item.scholarId}`">{{ item.scholarName }}</RouterLink>
            </h3>

            <div class="result-source">
              公开成果 {{ item.qualifiedAchievementCount }} 项
              <template v-if="item.firstYear"> · 最早 {{ item.firstYear }} 年</template>
              <template v-else> · <span class="faint">最早年份未知</span></template>
            </div>

            <div class="result-links">
              <RouterLink :to="`/scholars/${item.scholarId}`">打开档案核对机构</RouterLink>
              <RouterLink :to="`/portal?scholarId=${encodeURIComponent(item.scholarId)}`">
                申请管理该门户
              </RouterLink>
              <span class="faint mono">{{ item.scholarId }}</span>
            </div>
          </article>

          <Pager
            :page="scholarData.page"
            :size="scholarData.size"
            :total="scholarData.total"
            :max-offset="options.data.value?.maxOffset ?? 10000"
            :disabled="loading"
            @change="changePage"
          />
        </div>

        <p class="tiny faint">{{ scholarData.countingNote }}</p>
      </template>
    </AsyncBlock>
  </div>
</template>
