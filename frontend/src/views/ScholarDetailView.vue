<script setup lang="ts">
/**
 * 学者档案页（公开）。
 *
 * 认领状态单独标注：一句「已认领」意味着档案经过本人确认，与「系统自动匹配出来的档案」在
 * 可信度上不同。把两者混在一起展示会让读者以为所有档案都经过本人确认。
 *
 * 字段名以 core 的 PublicCatalogQueryService.scholarContributionViews 实际返回为准：
 * 标题字段叫 `title`（不是 `achievementTitle`），并且**不返回** role / authorOrder ——
 * 学者维度的参与视图里没有署名序。读错名字不会报错：标题会静默退化成 `ach_xxx` 标识符，
 * 读者看到一列标识符也只会以为是平台的展示风格，不会想到是字段对不上。
 */
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { accountApi, publicApi } from '@/api'
import { ApiError, serviceUrl } from '@/api/http'
import { useAsync } from '@/composables/useAsync'
import { useSessionStore } from '@/stores/session'
import AsyncBlock from '@/components/AsyncBlock.vue'
import NatureBadge from '@/components/NatureBadge.vue'
import ScholarProfileEditor from '@/components/ScholarProfileEditor.vue'
import YearTrend from '@/components/YearTrend.vue'
import CitationTrend from '@/components/CitationTrend.vue'

const route = useRoute()
const id = computed(() => String(route.params.id ?? ''))

const detail = useAsync<Record<string, unknown>>(() => publicApi.scholar(id.value))
const session = useSessionStore()

/**
 * 可编辑视图只在「已登录且有门户维护能力」时才去取。
 *
 * 判断顺序很重要：先看本地能力集合再发请求，匿名访客读档案就只多一次公开详情请求；
 * 反过来「无脑请求、失败就吞掉」的做法会让每个匿名访问者都产生一次 401/403，
 * 日志里这些噪声会盖住真正的越权尝试。
 */
const editable = ref<Record<string, unknown> | null>(null)

async function loadEditable() {
  if (!session.authenticated || !session.can('portal:maintain')) {
    return
  }
  try {
    editable.value = await accountApi.scholarProfile(id.value)
  } catch (cause) {
    // 403 表示「不是这个门户的管理者」，属于正常情况：页面照旧显示公开视图。
    if (cause instanceof ApiError && (cause.isAccessDenied || cause.isAuthRequired)) {
      editable.value = null
      return
    }
    throw cause
  }
}

interface ContributionView {
  achievementId: string
  title?: string | null
  type?: string | null
  publicationYear?: number | null
  relationState?: string | null
  qualified?: boolean
  relationNote?: string | null
}

const TYPE_LABELS: Record<string, string> = {
  PAPER: '论文',
  PATENT: '专利',
  PROJECT: '项目',
  AWARD: '获奖',
}

const RELATION_LABELS: Record<string, string> = {
  AUTO: '自动建立',
  SELF_CONFIRMED: '本人确认',
  PENDING_VERIFY: '待核验',
  DISPUTED: '存在争议',
  EXCLUDED: '已排除',
}

/** 关系是否合格。判据与后端 qualifiedForGraph 一致；后端字段缺失时回落到状态判据。 */
function isQualified(row: ContributionView): boolean {
  if (typeof row.qualified === 'boolean') return row.qualified
  return row.relationState === 'AUTO' || row.relationState === 'SELF_CONFIRMED'
}

const contributions = computed<ContributionView[]>(() => {
  const rows = detail.data.value?.contributions
  return Array.isArray(rows) ? (rows as ContributionView[]) : []
})

/** 合格关系数。计数口径必须与页头声明一致，否则「9 项成果」与列表条数对不上。 */
const qualifiedCount = computed(() => contributions.value.filter(isQualified).length)
const unqualifiedCount = computed(() => contributions.value.length - qualifiedCount.value)

/**
 * 认领状态用中文说出差别。
 *
 * 「已认领」意味着档案经过本人确认，与「系统自动匹配出来的档案」在可信度上不同；
 * 把英文枚举直接摆在页面上，读者读不出这层差别，就会默认所有档案都经过本人确认。
 */
function displayClaimStatus(status: unknown): string {
  const value = String(status ?? '')
  if (value === 'CLAIMED') return '已由本人认领'
  if (value === 'UNCLAIMED') return '尚未认领（系统自动匹配）'
  return value || '认领状态未知'
}

/** 按年份倒序，让最近的工作排在前面。年份缺失的排在末尾并单独标注。 */
const sortedContributions = computed(() =>
  [...contributions.value].sort((a, b) => {
    if (a.publicationYear == null && b.publicationYear == null) return 0
    if (a.publicationYear == null) return 1
    if (b.publicationYear == null) return -1
    return b.publicationYear - a.publicationYear
  }),
)

const yearCoverage = computed(() => {
  const years = yearRows.value.map((row) => row.year)
  if (years.length === 0) return null
  return { from: Math.min(...years), to: Math.max(...years) }
})

/**
 * 客观指标（年份分布、主要合作者），由服务端在同一份口径下算好。
 *
 * 这组数字的用处是「让人一眼看出这个档案有多少可核对的东西」，因此它必须与下面的列表同口径：
 * 都只算关系合格、成果公开生效未合并的记录。两处各算一套的结果是——页头写 12 项、列表 9 条、
 * 图表 8 根柱，读者只会得出一个结论：数据不可信。
 *
 * 页面刻意不派生任何评价性指标（h-index、影响因子、综合评分）：引用量来自单一来源且经常缺失，
 * 拿它算出的评级不是平台观察到的事实，而是平台编出来的结论。
 */
interface ScholarMetrics {
  worksTotal?: number
  worksWithYear?: number
  worksWithoutYear?: number
  byYear?: { year: number; amount: number }[]
  topCoauthors?: {
    scholarId: string
    displayName?: string | null
    organizationName?: string | null
    sharedAchievements: number
  }[]
  coauthorLimit?: number
  citations?: CitationsBlock
  basis?: string
  note?: string
}

/**
 * 引用量区块。
 *
 * 字段名与后端 `citationMetricsOf` 一一对应，其中 `worksPaperMissingCitation` 与
 * `worksNotCitable` 必须分开显示：前者是「论文但来源没给」，后者是「专利/项目/获奖本来
 * 就没有引用量这个概念」。合成一个「缺失」会让数据质量看起来比实际更糟。
 */
interface CitationYearRow {
  year: number
  works: number
  worksWithCitation: number
  citations: number
  averagePerWork?: number | null
}

interface CitationsBlock {
  worksTotal?: number
  worksWithCitation?: number
  worksMissingCitation?: number
  worksNotCitable?: number
  worksPaperMissingCitation?: number
  totalCitations?: number
  maxWorkCitations?: number
  averagePerWork?: number | null
  coveragePercent?: number | null
  byYear?: CitationYearRow[]
  sources?: { source: string; works: number }[]
  averageNote?: string
  basis?: string
  trendNote?: string
  sourceNote?: string
  metricNote?: string
}

const metrics = computed<ScholarMetrics | null>(() => {
  const raw = detail.data.value?.metrics
  return raw && typeof raw === 'object' ? (raw as ScholarMetrics) : null
})

const yearRows = computed(() => metrics.value?.byYear ?? [])
const unknownYear = computed(() => metrics.value?.worksWithoutYear ?? 0)
const coauthors = computed(() => metrics.value?.topCoauthors ?? [])
const citations = computed<CitationsBlock | null>(() => metrics.value?.citations ?? null)
const citationRows = computed(() => citations.value?.byYear ?? [])
/** 有引用量记录才画图：一条数据都没有时画一个空坐标系，看起来像「全都没被引用」。 */
const hasCitationData = computed(() => (citations.value?.worksWithCitation ?? 0) > 0)

/** 自述区块与置顶：由服务端在同一份公开口径下装配，这里只做展示。 */
interface ProfileBlock {
  homepageUrl?: string | null
  avatarUrl?: string | null
  profileUpdatedAt?: string | null
  note?: string | null
}

interface FeaturedItem {
  id: string
  title?: string | null
  type?: string | null
  typeLabel?: string | null
  publicationYear?: number | null
  venue?: string | null
  citationCount?: number | null
  citationSource?: string | null
}

interface FeaturedBlock {
  items?: FeaturedItem[]
  unavailableCount?: number
  note?: string | null
}

const profile = computed<ProfileBlock | null>(() => {
  const raw = detail.data.value?.profile
  return raw && typeof raw === 'object' ? (raw as ProfileBlock) : null
})

const featured = computed<FeaturedBlock | null>(() => {
  const raw = detail.data.value?.featured
  return raw && typeof raw === 'object' ? (raw as FeaturedBlock) : null
})

const featuredItems = computed(() => featured.value?.items ?? [])
/**
 * 头像地址：服务端给的是服务内路径，`<img>` 不经过统一客户端，前缀必须在这里补。
 *
 * 漏补的表现不是报错，而是「200 + text/html」——开发代理没命中，dev server 把 index.html
 * 当静态资源发了回来，页面上只是一张裂图。
 */
const avatarUrl = computed(() =>
  profile.value?.avatarUrl ? serviceUrl('core', profile.value.avatarUrl) : null,
)
/** 头像占位用姓名首字：显示名可能为空，此时退回问号而不是渲染一段空白。 */
const avatarInitial = computed(() => String(detail.data.value?.displayName ?? '?').slice(0, 1).toUpperCase())

/** 可置顶的候选：只含合格关系的成果，与「公开成果」列表同一口径。 */
const featuredCandidates = computed(() =>
  contributions.value
    .filter(isQualified)
    .map((row) => ({
      achievementId: row.achievementId,
      title: row.title,
      publicationYear: row.publicationYear,
      type: row.type,
    })),
)

const editableFeaturedIds = computed(() => {
  const rows = editable.value?.featured
  if (!Array.isArray(rows)) return []
  return (rows as { achievementId: string }[]).map((row) => row.achievementId)
})

/** 编辑区里的当前头像，同样要补前缀（编辑视图与公开视图各返回一份地址）。 */
const editableAvatarUrl = computed(() => {
  const raw = editable.value?.avatarUrl
  return typeof raw === 'string' && raw ? serviceUrl('core', raw) : null
})

/**
 * 保存后重新读取：必须静默。
 *
 * <p>非静默读取会把整块内容切成「正在读取…」，编辑区随之卸载，刚显示的「已保存」提示一起消失。
 * 用户按下保存后什么都没看到，只会怀疑没保存成功。
 */
async function reload() {
  await detail.run({ silent: true })
  await loadEditable()
}

// ---------------------------------------------------------------------------
// 收藏（关注）
// ---------------------------------------------------------------------------
/**
 * 收藏是**个人列表**语义，不是订阅：收藏这位学者不会因此收到他新成果的通知。
 * 这一点写在按钮旁边，否则用户会默认以为「关注 = 有更新就提醒我」。
 *
 * 未登录时 favorited 为 null（服务端明确区分「未登录」与「登录了但没收藏」），
 * 这里据此决定按钮文案是「收藏」还是引导登录。
 */
const favorite = ref<{ favorited: boolean | null; favoriteCount: number }>({
  favorited: null,
  favoriteCount: 0,
})
const favoriteBusy = ref(false)
const favoriteNote = ref('')

async function loadFavorite() {
  try {
    if (!session.authenticated) {
      // 匿名访客只读公开计数。/account/favorites 需要会话，匿名调用会得到 401——
      // 页面本身没坏，但控制台里多出一条失败请求，看的人会以为坏了。
      favorite.value = {
        favorited: null,
        favoriteCount: (await publicApi.scholarFavoriteCount(id.value)).favoriteCount,
      }
      return
    }
    const view = await accountApi.favoriteStatus(id.value)
    favorite.value = { favorited: view.favorited, favoriteCount: view.favoriteCount }
  } catch {
    // 收藏状态读不到不影响档案本身，静默保持初始值（按钮显示「收藏」）。
  }
}

async function toggleFavorite() {
  if (favoriteBusy.value) return
  if (!session.authenticated) {
    favoriteNote.value = '登录后才能收藏学者。'
    return
  }
  favoriteBusy.value = true
  favoriteNote.value = ''
  try {
    const view = favorite.value.favorited
      ? await accountApi.unfavorite(id.value)
      : await accountApi.favorite(id.value)
    favorite.value = { favorited: view.favorited, favoriteCount: view.favoriteCount }
    favoriteNote.value = view.note ?? ''
  } catch (cause) {
    favoriteNote.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    favoriteBusy.value = false
  }
}

onMounted(async () => {
  await detail.run()
  await loadEditable()
  await loadFavorite()
})
</script>

<template>
  <div>
    <AsyncBlock
      :loading="detail.loading.value"
      :error="detail.error.value"
      :error-code="detail.errorCode.value"
      :empty="detail.data.value === null && !detail.loading.value && !detail.error.value"
      empty-text="该学者档案不存在或尚不可见。"
    >
      <template v-if="detail.data.value">
        <div style="display: flex; gap: 16px; align-items: flex-start">
          <img
            v-if="avatarUrl"
            :src="avatarUrl"
            :alt="`${detail.data.value.displayName} 的头像`"
            class="scholar-avatar"
          />
          <span v-else class="scholar-avatar placeholder" aria-hidden="true">{{ avatarInitial }}</span>

          <div style="min-width: 0">
            <h1 style="margin-bottom: 4px; font-size: 26px">{{ detail.data.value.displayName }}</h1>
            <div class="result-meta" style="margin-bottom: 16px">
              <NatureBadge :nature="String(detail.data.value.dataNature)" />
              <span
                class="badge"
                :class="String(detail.data.value.claimedStatus) === 'CLAIMED' ? 'ok' : 'neutral'"
              >
                {{ displayClaimStatus(detail.data.value.claimedStatus) }}
              </span>
              <span v-if="detail.data.value.organizationName">
                {{ detail.data.value.organizationName }}
              </span>
              <span v-else class="faint">机构未知</span>
              <a
                v-if="profile?.homepageUrl"
                :href="profile.homepageUrl"
                target="_blank"
                rel="noopener noreferrer nofollow"
                class="tiny"
              >个人主页</a>
              <span class="tiny faint mono">标识 {{ id }}</span>
            </div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-body">
            <div class="btn-row">
              <RouterLink class="btn small" :to="`/portal?scholarId=${encodeURIComponent(id)}`">
                {{ String(detail.data.value.claimedStatus) === 'CLAIMED' ? '对这个门户提交认领申请' : '申请管理这个门户' }}
              </RouterLink>
              <RouterLink class="btn small" :to="`/network?scholarId=${encodeURIComponent(id)}`">
                查看合作网络
              </RouterLink>
              <button
                class="btn small"
                type="button"
                :disabled="favoriteBusy"
                @click="toggleFavorite"
              >
                {{ favorite.favorited ? '取消收藏' : '收藏' }}
                <span v-if="favorite.favoriteCount > 0" class="tiny faint">
                  · {{ favorite.favoriteCount }}
                </span>
              </button>
            </div>
            <p v-if="favoriteNote" class="tiny faint" style="margin: 8px 0 0">{{ favoriteNote }}</p>
            <p class="tiny faint" style="margin: 10px 0 0">
              <template v-if="String(detail.data.value.claimedStatus) === 'CLAIMED'">
                该门户已有生效的管理人。提交申请不等于转移授权：已有管理人不会被一次申请自动替换，
                审核会按争议流程处理。
              </template>
              <template v-else>
                认领只授予这个门户的管理权，不会把你名下的同名成果自动改为本人确认；
                审核会核对材料与说明，结果以服务端返回为准。
              </template>
            </p>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head"><h2>研究简介</h2></div>
          <div class="panel-body">
            <p v-if="detail.data.value.researchSummary" style="margin: 0; white-space: pre-wrap">
              {{ detail.data.value.researchSummary }}
            </p>
            <div v-else class="empty">尚未填写研究简介。</div>
            <p v-if="profile?.note" class="tiny faint" style="margin: 10px 0 0">{{ profile.note }}</p>
          </div>
        </div>

        <div v-if="featuredItems.length || featured?.unavailableCount" class="panel">
          <div class="panel-head">
            <h2>代表作置顶</h2>
            <span class="tiny faint">
              由本人指定，不是平台推荐
              <template v-if="featured?.unavailableCount">
                · 另有 {{ featured.unavailableCount }} 条已不可公开被隐藏
              </template>
            </span>
          </div>
          <div class="panel-body">
            <ol class="featured-list">
              <li v-for="item in featuredItems" :key="item.id">
                <RouterLink :to="`/achievements/${item.id}`">{{ item.title }}</RouterLink>
                <span class="tiny faint">
                  <template v-if="item.publicationYear"> · {{ item.publicationYear }}</template>
                  <template v-if="item.typeLabel"> · {{ item.typeLabel }}</template>
                  <template v-if="item.venue"> · {{ item.venue }}</template>
                  <template v-if="item.citationCount != null">
                    · 引用 {{ item.citationCount }}（{{ item.citationSource || '来源未注明' }}）
                  </template>
                </span>
              </li>
            </ol>
            <p v-if="featured?.note" class="tiny faint" style="margin: 10px 0 0">{{ featured.note }}</p>
          </div>
        </div>

        <ScholarProfileEditor
          v-if="editable"
          :scholar-id="id"
          :display-name="String(detail.data.value.displayName ?? '')"
          :research-summary="detail.data.value.researchSummary as string | null"
          :homepage-url="(editable.homepageUrl as string | null) ?? null"
          :avatar-url="editableAvatarUrl"
          :profile-updated-at="(editable.profileUpdatedAt as string | null) ?? null"
          :featured-ids="editableFeaturedIds"
          :max-featured="Number(editable.maxFeatured ?? 3)"
          :candidates="featuredCandidates"
          @saved="reload"
        />

        <div v-if="metrics" class="grid cols-2">
          <div class="panel">
            <div class="panel-head">
              <h2>年度分布</h2>
              <span class="tiny faint">
                可核对成果 {{ metrics.worksTotal }} 项<template v-if="unknownYear">
                  ，其中年份未知 {{ unknownYear }} 项单列</template
                >
              </span>
            </div>
            <div class="panel-body">
              <YearTrend
                v-if="yearRows.length || unknownYear"
                :rows="yearRows"
                :unknown-amount="unknownYear"
              />
              <div v-else class="empty">暂无可统计的成果：该学者尚无可核对的公开记录。</div>
              <p class="tiny faint" style="margin: 10px 0 0">{{ metrics.basis }}</p>
            </div>
          </div>

          <div class="panel">
            <div class="panel-head">
              <h2>主要合作者</h2>
              <span class="tiny faint">
                按共同成果数排序，最多 {{ metrics.coauthorLimit ?? 10 }} 位
              </span>
            </div>
            <div class="panel-body">
              <div v-if="coauthors.length === 0" class="empty">
                暂无可确认的共著记录：共著要求两端的参与关系都已确认或自动建立。
              </div>
              <table v-else class="data">
                <thead>
                  <tr>
                    <th>学者</th>
                    <th>机构</th>
                    <th class="num">共同成果</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in coauthors" :key="row.scholarId">
                    <td>
                      <RouterLink :to="`/scholars/${row.scholarId}`">
                        {{ row.displayName || '（姓名未确认）' }}
                      </RouterLink>
                      <div v-if="!row.displayName" class="tiny faint mono">{{ row.scholarId }}</div>
                    </td>
                    <td class="small">
                      <template v-if="row.organizationName">{{ row.organizationName }}</template>
                      <span v-else class="faint">未知</span>
                    </td>
                    <td class="num">{{ row.sharedAchievements }}</td>
                  </tr>
                </tbody>
              </table>
              <p class="tiny faint" style="margin: 10px 0 0">{{ metrics.note }}</p>
            </div>
          </div>
        </div>

        <div v-if="citations" class="panel">
          <div class="panel-head">
            <h2>引用量</h2>
            <span class="tiny faint">
              <template v-if="citations.worksWithCitation">
                {{ citations.worksWithCitation }} / {{ citations.worksTotal }} 项成果有引用量记录（{{
                  citations.coveragePercent ?? '—'
                }}%）
              </template>
              <template v-else>暂无引用量记录</template>
            </span>
          </div>
          <div class="panel-body">
            <template v-if="hasCitationData">
              <div class="citation-kpi">
                <div>
                  <div class="kpi-value">{{ citations.totalCitations }}</div>
                  <div class="tiny faint">累计引用（各篇取单一来源值后相加）</div>
                </div>
                <div>
                  <div class="kpi-value">{{ citations.averagePerWork ?? '—' }}</div>
                  <div class="tiny faint">{{ citations.averageNote || '篇均引用（只除以有数据的成果）' }}</div>
                </div>
                <div>
                  <div class="kpi-value">{{ citations.maxWorkCitations ?? '—' }}</div>
                  <div class="tiny faint">单篇最高（看分布是否偏斜）</div>
                </div>
                <div>
                  <div class="kpi-value">{{ citations.worksPaperMissingCitation ?? 0 }}</div>
                  <div class="tiny faint">论文但来源未提供引用量</div>
                </div>
              </div>

              <CitationTrend :rows="citationRows" />

              <p v-if="citations.sources?.length" class="tiny faint" style="margin: 10px 0 0">
                来源分布：
                <template v-for="(row, index) in citations.sources" :key="row.source">
                  <template v-if="index"> · </template>{{ row.source }} {{ row.works }} 篇
                </template>
                <template v-if="citations.worksNotCitable">
                  · 另有 {{ citations.worksNotCitable }} 条非论文成果（专利/项目/获奖）类型上不计引用量
                </template>
              </p>
            </template>
            <div v-else class="empty">
              该学者的成果暂无可用的引用量记录：来源未提供，平台不做估算、也不按 0 计入。
            </div>

            <p class="tiny faint" style="margin: 10px 0 0">{{ citations.basis }}</p>
            <p class="tiny faint" style="margin: 6px 0 0">{{ citations.trendNote }}</p>
            <p class="tiny faint" style="margin: 6px 0 0">{{ citations.sourceNote }}</p>
            <p class="tiny faint" style="margin: 6px 0 0">{{ citations.metricNote }}</p>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <h2>公开成果（{{ qualifiedCount }}）</h2>
            <span class="small dim">
              <template v-if="yearCoverage">
                年份跨度 {{ yearCoverage.from }}–{{ yearCoverage.to }} ·
              </template>
              <template v-if="unqualifiedCount">另有 {{ unqualifiedCount }} 条待核验关系在下方单列 · </template>
              <RouterLink :to="`/network?scholarId=${id}`">查看合作网络</RouterLink>
            </span>
          </div>

          <div v-if="unqualifiedCount" class="notice warn" style="margin: 12px">
            下列 {{ contributions.length }} 条中，有 {{ unqualifiedCount }} 条参与关系处于待核验、争议或已排除状态。
            它们<strong>不计入</strong>合作网络建边，也不计入平台的成果计数；此处列出的目的是让读者看到
            「有关系但尚未确认」，而不是把它们当作确定的参与。
          </div>

          <div v-if="contributions.length === 0" class="empty">
            该学者暂无公开生效的成果记录。这可能表示尚未收录，也可能表示已有关系仍在核验中。
          </div>
          <article
            v-for="row in sortedContributions"
            :key="row.achievementId"
            class="result-item"
          >
            <h3 class="result-title">
              <RouterLink :to="`/achievements/${row.achievementId}`">
                {{ row.title || '（来源未提供标题）' }}
              </RouterLink>
            </h3>
            <div class="result-source">
              <template v-if="row.publicationYear">{{ row.publicationYear }} 年</template>
              <template v-else><span class="faint">年份未知</span></template>
              <template v-if="row.type"> · {{ TYPE_LABELS[row.type] ?? row.type }}</template>
            </div>
            <div class="result-links">
              <span class="badge" :class="isQualified(row) ? 'ok' : 'warn'">
                {{ RELATION_LABELS[row.relationState ?? ''] ?? row.relationState }}
              </span>
              <!--
                关系说明只给非合格行：合格行十有八九是同一句「自动建立」，列表场景里
                逐行重复同一句等于没有信息；而非合格行（待核验/争议/已排除）的说明
                正是读者需要停下来读的那部分。
              -->
              <span v-if="!isQualified(row)" class="tiny faint">{{ row.relationNote }}</span>
              <span class="faint mono">{{ row.achievementId }}</span>
            </div>
          </article>
        </div>
      </template>
    </AsyncBlock>
  </div>
</template>

<style scoped>
/* 头像与姓名同一行：独立一行会让页头多出一个只有 64px 高的空白带。 */
.scholar-avatar {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  object-fit: cover;
  border: 1px solid var(--rule);
  flex: 0 0 auto;
  margin-top: 4px;
}

/* 未上传头像时的占位：用姓名首字而不是通用剪影，读者能一眼确认「这是谁」，
   同时也不会误以为平台替他生成了头像。 */
.scholar-avatar.placeholder {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--accent-soft);
  color: var(--accent);
  font-family: var(--serif, Georgia, 'Songti SC', serif);
  font-size: 26px;
}

.featured-list {
  margin: 0;
  padding-left: 20px;
  line-height: 1.9;
}

.featured-list a {
  font-family: var(--serif, Georgia, 'Songti SC', serif);
}

/* 四个数字横排：竖排会把「累计/篇均/最高/缺失」读成四个互不相关的段落。 */
.citation-kpi {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.citation-kpi .kpi-value {
  font-family: var(--serif, Georgia, 'Songti SC', serif);
  font-size: 24px;
  line-height: 1.2;
}

@media (max-width: 720px) {
  .citation-kpi {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
