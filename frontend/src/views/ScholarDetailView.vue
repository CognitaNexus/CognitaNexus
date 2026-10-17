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
import { computed, onMounted } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { publicApi } from '@/api'
import { useAsync } from '@/composables/useAsync'
import AsyncBlock from '@/components/AsyncBlock.vue'
import NatureBadge from '@/components/NatureBadge.vue'

const route = useRoute()
const id = computed(() => String(route.params.id ?? ''))

const detail = useAsync<Record<string, unknown>>(() => publicApi.scholar(id.value))

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
  const years = contributions.value
    .map((row) => row.publicationYear)
    .filter((year): year is number => typeof year === 'number')
  if (years.length === 0) return null
  return { from: Math.min(...years), to: Math.max(...years) }
})

onMounted(detail.run)
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
          <span class="tiny faint mono">标识 {{ id }}</span>
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
            </div>
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
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <h2>公开成果（{{ contributions.length }}）</h2>
            <span class="small dim">
              <template v-if="yearCoverage">
                年份跨度 {{ yearCoverage.from }}–{{ yearCoverage.to }} ·
              </template>
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
