<script setup lang="ts">
/**
 * 学者档案页（公开）。
 *
 * 认领状态单独标注：一句「已认领」意味着档案经过本人确认，与「系统自动匹配出来的档案」在
 * 可信度上不同。把两者混在一起展示会让读者以为所有档案都经过本人确认。
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
  achievementTitle?: string
  role?: string
  authorOrder?: number
  relationState?: string
  publicationYear?: number | null
}

const contributions = computed<ContributionView[]>(() => {
  const rows = detail.data.value?.contributions
  return Array.isArray(rows) ? (rows as ContributionView[]) : []
})

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
                {{ row.achievementTitle ?? row.achievementId }}
              </RouterLink>
            </h3>
            <div class="result-source">
              <template v-if="row.publicationYear">{{ row.publicationYear }} 年</template>
              <template v-else><span class="faint">年份未知</span></template>
              <template v-if="row.role"> · {{ row.role }}</template>
              <template v-if="row.authorOrder"> · 第 {{ row.authorOrder }} 作者</template>
            </div>
            <div class="result-links">
              <span class="badge" :class="row.relationState === 'AUTO' || row.relationState === 'SELF_CONFIRMED' ? 'ok' : 'warn'">
                {{ row.relationState }}
              </span>
              <span class="faint mono">{{ row.achievementId }}</span>
            </div>
          </article>
        </div>
      </template>
    </AsyncBlock>
  </div>
</template>
