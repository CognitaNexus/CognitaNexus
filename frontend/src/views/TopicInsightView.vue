<script setup lang="ts">
/**
 * 领域洞察页。
 *
 * 回答两问：「这个领域里是谁在做、发在哪里」（机构 / 学者 / 期刊榜），以及「什么时候集中」
 * （年度趋势）。这些聚合以领域为主语，总览页的榜单回答不了。
 *
 * 页面必须让读者分清两类数字的信任度：来源给出的分类与系统按词表推断的分类在页头分开标注，
 * 排名与趋势不区分（因为一条成果只要归属成立就参与统计，归属来源影响的是信任度不是口径）。
 */
import { computed, onMounted, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { queryApi } from '@/api'
import type { TopicInsight } from '@/api/types'
import { useAsync } from '@/composables/useAsync'
import { useScopeStore } from '@/stores/scope'
import AsyncBlock from '@/components/AsyncBlock.vue'
import BarList from '@/components/BarList.vue'
import YearTrend from '@/components/YearTrend.vue'

const route = useRoute()
const scope = useScopeStore()

const topicId = computed(() => String(route.params.id ?? ''))

const insight = useAsync<TopicInsight>(() => queryApi.topicInsight(topicId.value, scope.nature))

onMounted(() => insight.run())
// 领域标识或口径变化都等价于换一页数据，必须重新读取。
watch([topicId, () => scope.nature], () => insight.run())

const data = computed(() => insight.data.value)

/** 404 与加载失败要分开呈现：前者是确定答案，后者是尚未取到。 */
const notFound = computed(() => insight.errorCode.value === 'NOT_FOUND')

const inferenceNote = computed(() => {
  const totals = data.value?.totals
  if (!totals) return ''
  const parts: string[] = []
  if (totals.sourceProvided > 0) parts.push(`来源分类 ${totals.sourceProvided} 条`)
  if (totals.systemInferred > 0) parts.push(`系统推断 ${totals.systemInferred} 条`)
  return parts.join('，')
})

const citationRulesNote = computed(() => {
  const rows = data.value?.representativeAchievements ?? []
  if (rows.every((row) => !row.citationCountKnown)) {
    return '该领域下没有任何成果带引用量。这不表示这些成果没有引用，只表示来源记录里没有这个字段。'
  }
  return '引用量按来源分别给出，不同来源的口径不同，不能横向比较；同一年份内比较的通常是同一来源的记录。'
})
</script>

<template>
  <div>
    <div v-if="notFound" class="empty" style="margin: 24px 0">
      该领域在当前数据口径下没有可公开的成果。它可能不存在，也可能相关成果尚未公开——这两种情况
      平台都只能如实回答「看不到」，不会猜测原因。
    </div>

    <AsyncBlock
      v-else
      :loading="insight.loading.value"
      :error="insight.error.value"
      :error-code="insight.errorCode.value"
      :empty="insight.data.value === null && !insight.loading.value && !insight.error.value"
      empty-text="该领域暂无统计数据。"
    >
      <template v-if="data">
        <h1 style="margin-bottom: 4px; font-size: 26px">
          {{ data.topic.canonicalNameKnown ? data.topic.canonicalName : '（领域名称待补）' }}
        </h1>
        <div class="result-meta" style="margin-bottom: 16px">
          <span class="tiny faint mono">标识 {{ data.topic.topicId }}</span>
          <span v-if="inferenceNote" class="tiny faint">{{ inferenceNote }}</span>
          <span class="tiny faint">{{ data.scope.note }}</span>
        </div>

        <div class="grid cols-4" style="margin-bottom: 20px">
          <div class="metric">
            <div class="metric-label">公开生效成果</div>
            <div class="metric-value">{{ data.totals.achievements.toLocaleString() }}</div>
          </div>
          <div class="metric">
            <div class="metric-label">参与学者（关系合格）</div>
            <div class="metric-value">{{ data.totals.scholarsWithQualifiedRelation.toLocaleString() }}</div>
          </div>
          <div class="metric">
            <div class="metric-label">来源分类</div>
            <div class="metric-value">{{ data.totals.sourceProvided.toLocaleString() }}</div>
          </div>
          <div class="metric">
            <div class="metric-label">系统推断</div>
            <div class="metric-value">{{ data.totals.systemInferred.toLocaleString() }}</div>
          </div>
        </div>

        <div class="panel" style="margin-bottom: 16px">
          <div class="panel-head">
            <h2>年度趋势</h2>
            <span class="tiny faint">按成果发表年份计数；年份未知单列，不并入任何年份</span>
          </div>
          <div class="panel-body">
            <YearTrend
              :rows="data.byYear"
              :unknown-amount="data.achievementsWithUnknownYear"
            />
          </div>
        </div>

        <div class="grid cols-3">
          <div class="panel">
            <div class="panel-head">
              <h2>机构排名</h2>
              <span class="tiny faint">只统计该领域成果</span>
            </div>
            <div class="panel-body">
              <BarList
                :items="
                  data.topOrganizations.map((row) => ({
                    label: row.key,
                    value: row.achievements,
                  }))
                "
                empty-text="该领域下没有可归属机构的成果。"
              />
              <p v-if="data.totals.achievementsWithUnknownOrganization > 0" class="tiny faint" style="margin: 10px 0 0">
                另有 {{ data.totals.achievementsWithUnknownOrganization }} 条成果机构未知，不参与排名。
              </p>
            </div>
          </div>

          <div class="panel">
            <div class="panel-head">
              <h2>学者排名</h2>
              <span class="tiny faint">待核验 / 争议 / 已排除关系不计入</span>
            </div>
            <div class="panel-body">
              <div v-if="data.topScholars.length === 0" class="empty">
                该领域下没有关系合格的学者贡献。
              </div>
              <div v-else>
                <div
                  v-for="(row, index) in data.topScholars"
                  :key="row.scholarId"
                  style="display: flex; gap: 8px; align-items: baseline; margin-bottom: 7px; font-size: 13px"
                >
                  <span class="mono faint tiny" style="width: 22px">{{ index + 1 }}</span>
                  <RouterLink
                    :to="`/scholars/${row.scholarId}`"
                    style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap"
                  >
                    {{ row.nameKnown ? row.name : row.scholarId }}
                  </RouterLink>
                  <span class="mono nowrap faint" style="margin-left: auto">{{ row.achievements }}</span>
                </div>
              </div>
            </div>
          </div>

          <div class="panel">
            <div class="panel-head">
              <h2>发表载体</h2>
              <span class="tiny faint">按领域内成果数计，不是期刊总发文量</span>
            </div>
            <div class="panel-body">
              <BarList
                :items="
                  data.topVenues.map((row) => ({
                    label: row.key,
                    value: row.achievements,
                  }))
                "
                empty-text="该领域下没有带发表载体的成果。"
              />
            </div>
          </div>
        </div>

        <div class="panel" style="margin-bottom: 16px">
          <div class="panel-head">
            <h2>代表成果</h2>
            <span class="tiny faint">按发表年份降序，同年再按引用量降序</span>
          </div>
          <div class="panel-body">
            <div v-if="data.representativeAchievements.length === 0" class="empty">
              该领域下没有可展示的成果。
            </div>
            <div v-else>
              <div
                v-for="row in data.representativeAchievements"
                :key="row.achievementId"
                class="result-item"
                style="padding: 8px 0; border-bottom: 1px solid var(--border)"
              >
                <RouterLink :to="`/achievements/${row.achievementId}`" class="result-title">
                  {{ row.title }}
                </RouterLink>
                <div class="result-meta">
                  <span v-if="row.publicationYearKnown">{{ row.publicationYear }} 年</span>
                  <span v-else class="faint">年份未知</span>
                  <span v-if="row.citationCountKnown">
                    被引 {{ row.citationCount }}
                    <span v-if="row.citationSourceKnown" class="faint">（{{ row.citationSource }}）</span>
                  </span>
                  <span v-else class="faint">来源未提供引用量</span>
                </div>
              </div>
            </div>
            <p class="tiny faint" style="margin: 10px 0 0">{{ citationRulesNote }}</p>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <h2>口径说明</h2>
            <span class="tiny faint mono">规则版本 {{ data.ruleVersion }} · 已发布修订 {{ data.publishedRevision }}</span>
          </div>
          <div class="panel-body">
            <div v-for="(text, key) in data.countingRules" :key="key" style="margin-bottom: 6px">
              <span class="mono tiny faint">{{ key }}</span>
              <span class="tiny" style="margin-left: 8px">{{ text }}</span>
            </div>
          </div>
        </div>
      </template>
    </AsyncBlock>
  </div>
</template>
