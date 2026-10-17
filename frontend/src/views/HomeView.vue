<script setup lang="ts">
/**
 * 总览页。
 *
 * 页面组织按「读者会怎么问」而不是按接口返回字段顺序：先给一个检索入口（这是学术站的主要动作），
 * 再看规模（多少成果、多少学者），再看结构（年份、机构、领域、期刊），最后看引用——引用放最后
 * 是因为它只覆盖部分来源，放在最上面会被误读成平台的核心指标。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { queryApi } from '@/api'
import type { AnalyticsOverview } from '@/api/types'
import { useAsync } from '@/composables/useAsync'
import { useScopeStore } from '@/stores/scope'
import AsyncBlock from '@/components/AsyncBlock.vue'
import BarList from '@/components/BarList.vue'
import NatureBadge from '@/components/NatureBadge.vue'
import YearTrend from '@/components/YearTrend.vue'

const router = useRouter()
const scope = useScopeStore()

const heroKeyword = ref('')
const heroYearFrom = ref('')

const overview = useAsync<AnalyticsOverview>(() => queryApi.analyticsOverview(scope.nature))

const totals = computed(() => overview.data.value?.totals)

const unknownShare = computed(() => {
  const data = overview.data.value
  if (!data) return null
  const unknown = data.totals.achievementsWithUnknownOrganization
  if (data.totals.achievements === 0) return null
  return {
    unknown,
    percent: Math.round((unknown / data.totals.achievements) * 1000) / 10,
  }
})

const citationNote = computed(() => {
  const rows = overview.data.value?.citationsBySource ?? []
  if (rows.length === 0) {
    return '当前口径下没有任何来源提供引用量。这不表示这些成果没有引用，只表示来源记录里没有这个字段。'
  }
  return '引用量按来源分别展示，不跨来源相加，也不提供「总引用」。不同来源的统计覆盖范围不同，相加得到的数字没有可解释的含义。'
})

/** 年份跨度：让读者对「这些数字覆盖了多长时间」有个概念。 */
const yearSpan = computed(() => {
  const rows = (overview.data.value?.byYear ?? []).filter((row) => typeof row.year === 'number')
  if (rows.length === 0) return null
  const years = rows.map((row) => row.year).sort((a, b) => a - b)
  return { from: years[0]!, to: years[years.length - 1]!, buckets: years.length }
})

/** 成果类型的中文名。后端在详情接口里给了 typeLabel，但总览的 byType 只给枚举，这里补一份映射。 */
const TYPE_LABELS: Record<string, string> = {
  PAPER: '论文',
  PATENT: '专利',
  PROJECT: '项目',
  AWARD: '获奖',
}

function typeLabel(key: string): string {
  return TYPE_LABELS[key] ?? key
}

/** 只有一类成果时补一句说明：否则读者会以为其余三类被漏掉了。 */
const onlyOneType = computed(() => (overview.data.value?.byType.length ?? 0) === 1)

function heroSearch() {
  router.push({
    name: 'search',
    query: {
      q: heroKeyword.value.trim() || undefined,
      yearFrom: heroYearFrom.value || undefined,
    },
  })
}

async function load() {
  await overview.run()
  if (overview.data.value) {
    // 把后端声明的口径回写到全局：页面上的数字与页头提示必须来自同一处判断。
    scope.observe({
      dataNatureScope: overview.data.value.scope.dataNature,
      scopeNote: overview.data.value.scope.note,
      publishedRevision: overview.data.value.publishedRevision,
    })
  }
}

onMounted(load)
watch(() => scope.nature, load)
</script>

<template>
  <div>
    <section class="hero">
      <h1>检索学术成果与学者</h1>
      <p class="hero-lede">
        本平台收录的来源记录均为公开元数据，按来源分别标注，不做跨来源的合并统计。
        年份、机构、引用量等字段缺失时如实标注为未知，不以默认值填补。
      </p>

      <form class="hero-search" @submit.prevent="heroSearch">
        <input
          v-model="heroKeyword"
          type="search"
          aria-label="成果检索关键词"
          placeholder="输入题名关键词、发表载体或 DOI"
        />
        <button type="submit">检索成果</button>
        <RouterLink class="btn" to="/search?scope=scholars">按姓名找学者</RouterLink>
      </form>

      <div class="hero-modes">
        <span>
          按年份下限
          <input
            v-model="heroYearFrom"
            type="number"
            placeholder="如 2018"
            style="width: 92px; height: 26px; font-size: 12.5px; display: inline-block"
          />
        </span>
        <span>·</span>
        <RouterLink to="/search">高级检索与筛选</RouterLink>
        <span>·</span>
        <RouterLink to="/network">合作网络查询</RouterLink>
      </div>
    </section>

    <div class="section-head">
      <h2>收录概览</h2>
      <span class="small dim">
        口径 <NatureBadge :nature="scope.nature" />
        <span v-if="overview.data.value" class="mono">
          · 数据截止到已发布修订 {{ overview.data.value.publishedRevision }}
        </span>
      </span>
    </div>

    <AsyncBlock
      :loading="overview.loading.value"
      :error="overview.error.value"
      :error-code="overview.errorCode.value"
      :empty="overview.data.value === null"
      empty-text="当前口径下没有可统计的公开成果。"
    >
      <template v-if="overview.data.value && totals">
        <div class="grid cols-4" style="margin-bottom: 18px">
          <div class="metric">
            <div class="metric-label">公开成果</div>
            <div class="metric-value">{{ totals.achievements.toLocaleString() }}</div>
            <div class="metric-sub">
              去重后的成果实体数
              <template v-if="yearSpan">
                · 覆盖 {{ yearSpan.from }}–{{ yearSpan.to }} 年共 {{ yearSpan.buckets }} 个年份
              </template>
            </div>
          </div>
          <div class="metric">
            <div class="metric-label">有合格关系的学者</div>
            <div class="metric-value">{{ totals.scholarsWithQualifiedRelation.toLocaleString() }}</div>
            <div class="metric-sub">仅计自动匹配与本人自证关系</div>
          </div>
          <div class="metric">
            <div class="metric-label">参与关系</div>
            <div class="metric-value">{{ totals.contributions.toLocaleString() }}</div>
            <div class="metric-sub">成果 × 学者，不做个人维度合计</div>
          </div>
          <div class="metric">
            <div class="metric-label">合作者对</div>
            <div class="metric-value">{{ totals.coauthorPairs.toLocaleString() }}</div>
            <div class="metric-sub">仅共同合格成果建边，边不重复计数</div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <h2>年份分布</h2>
            <span class="small dim">
              <template v-if="overview.data.value.achievementsWithUnknownYear">
                年份未知 {{ overview.data.value.achievementsWithUnknownYear }} 项，单列不计入任一年份
              </template>
            </span>
          </div>
          <div class="panel-body">
            <YearTrend
              :rows="overview.data.value.byYear"
              :unknown-amount="overview.data.value.achievementsWithUnknownYear"
            />
          </div>
        </div>

        <div class="section-head">
          <h2>结构分布</h2>
          <span class="tiny faint">各维度相互独立，合计不做交叉</span>
        </div>

        <div class="grid cols-2">
          <div class="panel">
            <div class="panel-head"><h2>机构分布</h2></div>
            <div class="panel-body">
              <p v-if="unknownShare && unknownShare.percent > 0" class="notice warn tiny">
                有 {{ unknownShare.unknown.toLocaleString() }} 项（{{ unknownShare.percent }}%）成果没有可归属的
                「成果发生时机构」。它们<strong>不</strong>计入下面的排名，也不被折算到任何机构的当前名称下——
                缺失就单列为未知，排名只反映已知归属。
              </p>
              <BarList
                :items="
                  overview.data.value.topOrganizations.map((row) => ({
                    label: row.key,
                    value: row.achievements,
                  }))
                "
                empty-text="当前口径下没有可归属机构的成果。"
              />
            </div>
          </div>

          <div class="panel">
            <div class="panel-head"><h2>期刊 / 会议分布</h2></div>
            <div class="panel-body">
              <BarList
                :items="
                  overview.data.value.topVenues.map((row) => ({
                    label: row.key,
                    value: row.achievements,
                  }))
                "
                empty-text="当前口径下没有带发表载体的成果。"
              />
            </div>
          </div>

          <div class="panel">
            <div class="panel-head">
              <h2>领域分布</h2>
              <span class="tiny faint">领域为多对多，合计可超过成果总数；点击进入领域洞察</span>
            </div>
            <div class="panel-body">
              <BarList
                :items="
                  overview.data.value.topTopics.map((row) => ({
                    // 名称可能缺失（投影时来源未给），此时退回显示标识而不是 undefined。
                    label: row.canonicalNameKnown ? (row.canonicalName ?? row.topicId) : row.topicId,
                    value: row.achievements,
                    hint: row.systemInferred > 0 ? `其中系统推断 ${row.systemInferred} 条` : undefined,
                    to: `/topics/${row.topicId}`,
                  }))
                "
                empty-text="当前口径下没有带领域标注的成果。"
              />
            </div>
          </div>

          <div class="panel">
            <div class="panel-head">
              <h2>成果类型构成</h2>
              <span class="tiny faint">每条成果只归一类，合计等于成果总数</span>
            </div>
            <div class="panel-body">
              <BarList
                :items="
                  overview.data.value.byType.map((row) => ({
                    label: typeLabel(row.key),
                    value: row.achievements,
                  }))
                "
                empty-text="当前口径下没有可归类的成果。"
              />
              <p v-if="onlyOneType" class="tiny faint" style="margin: 10px 0 0">
                当前口径下只有一类成果。平台的四类收录口径（论文 / 专利 / 项目 / 获奖）在数据模型上
                都受支持，但这里显示的是<strong>实际已收录</strong>的构成——不存在的类别不补零占位，
                以免看起来像「收录过但数量为 0」。
              </p>
            </div>
          </div>

          <div class="panel">
            <div class="panel-head"><h2>数据性质构成</h2></div>
            <div class="panel-body">
              <BarList
                :items="
                  overview.data.value.byNature.map((row) => ({
                    label: row.key,
                    value: row.achievements,
                  }))
                "
              />
              <p class="tiny faint" style="margin: 10px 0 0">
                统计范围：{{ overview.data.value.scope.visibility }} /
                {{ overview.data.value.scope.effectiveStatus }} · {{ overview.data.value.scope.timeWindow }}
              </p>
            </div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <h2>引用量（按来源）</h2>
            <span class="tiny faint">计数规则 {{ overview.data.value.ruleVersion }}</span>
          </div>
          <div class="panel-body">
            <div v-if="overview.data.value.citationsBySource.length === 0" class="empty">
              当前口径下没有任何来源提供引用量数据。
            </div>
            <div v-else class="scroll-x">
              <table class="data">
                <thead>
                  <tr>
                    <th>来源</th>
                    <th class="num">有引用量的成果数</th>
                    <th class="num">引用量合计</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in overview.data.value.citationsBySource" :key="row.citationSource">
                    <td>{{ row.citationSource }}</td>
                    <td class="num">{{ row.achievementsWithCitation.toLocaleString() }}</td>
                    <td class="num">{{ row.citationSum.toLocaleString() }}</td>
                  </tr>
                  <tr>
                    <td class="dim">合计</td>
                    <td class="num dim">不提供</td>
                    <td class="num dim">不提供</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p class="small dim" style="margin: 10px 0 0">{{ citationNote }}</p>
          </div>
        </div>

        <div class="section-head"><h2>可从哪些入口进入</h2></div>

        <div class="entry-grid">
          <div class="entry">
            <h3><RouterLink to="/search">成果检索</RouterLink></h3>
            <p>按关键词、年份、机构、领域组合筛选，结果按查询版本锁定，跨页不会混入不同时刻的数据。</p>
          </div>
          <div class="entry">
            <h3><RouterLink to="/network">合作网络</RouterLink></h3>
            <p>沿合格合作关系展开子图，并支持两位学者之间的最短路径查询。待核验与已排除关系不参与建边。</p>
          </div>
          <div class="entry">
            <h3><RouterLink to="/ops">一致性与投递</RouterLink></h3>
            <p>公开视图的水位、修订连续性与投递台账。用来判断「现在这份结果是什么时刻的」。</p>
          </div>
          <div class="entry">
            <h3><RouterLink to="/portal">我的门户</RouterLink></h3>
            <p>学者本人可提交认领申请与档案修订申请。修订申请不会立即生效，需经审核并全程留痕。</p>
          </div>
        </div>

        <div class="panel" style="margin-top: 18px">
          <div class="panel-head"><h2>计数规则</h2></div>
          <div class="panel-body">
            <dl class="dl">
              <template v-for="(rule, key) in overview.data.value.countingRules" :key="key">
                <dt class="mono">{{ key }}</dt>
                <dd>{{ rule }}</dd>
              </template>
            </dl>
            <p class="small dim" style="margin: 12px 0 0">{{ overview.data.value.scope.note }}</p>
          </div>
        </div>
      </template>
    </AsyncBlock>
  </div>
</template>
