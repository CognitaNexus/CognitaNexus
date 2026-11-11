<script setup lang="ts">
/**
 * 引用网络面板：本篇引用了谁、谁引用了本篇。
 *
 * 这个面板只做一件事——把「边」和「覆盖率」一起摆出来，让读者不会把「本站 0 条被引」读成
 * 「全世界没人引用」。因此三块内容缺一不可：
 *
 *   1. 口径行：这批边来自哪个来源、什么时候取的、采没采到；
 *   2. 两个方向：本篇引用（出边）与被引（入边），各自只列**平台内可见**的成果；
 *   3. 覆盖率：平台共采了多少篇、占全部可见论文多少——这是读者判断「0 条」有没有意义的唯一依据。
 *
 * 三种「没有」必须分开说：没采过（NONE）、来源查不到这一篇（NOT_FOUND）、采到了但确实没有
 * 可解析的引用（OBSERVED + 0）。混成一句「暂无引用」就是把未知说成了结论。
 */
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import type { CitationNetworkView, CitedWorkView } from '@/api/types'

const props = defineProps<{ network: CitationNetworkView }>()

const coverage = computed(() => props.network.coverage)
const references = computed(() => props.network.references)
const citedBy = computed(() => props.network.citedBy)
const platform = computed(() => props.network.platform)

/** 口径行。三种状态各给一句话，不共用「暂无数据」这种把三种情况抹平的文案。 */
const statusLine = computed(() => {
  const c = coverage.value
  if (c.outcome === 'OBSERVED') {
    const parts: string[] = [`来源 ${c.provider ?? '未知'}`]
    if (c.fetchedAt) parts.push(`取于 ${String(c.fetchedAt).slice(0, 10)}`)
    if (c.referencesTotal != null) parts.push(`来源声明 ${c.referencesTotal} 篇参考文献`)
    else parts.push(`来源未声明参考文献总数`)
    return parts.join('，')
  }
  if (c.outcome === 'NOT_FOUND') {
    return '来源按该成果的标识查不到这一篇，取不到它的参考文献列表'
  }
  return '尚未采集本篇的参考文献列表'
})

const isObserved = computed(() => coverage.value.outcome === 'OBSERVED')

/** 明细里的年份：精确到年才显示年份，否则明说「年份未知」。 */
function yearText(work: CitedWorkView): string {
  if (work.yearKnown && work.publicationYear != null) return `${work.publicationYear} 年`
  if (work.publicationYear != null) return `${work.publicationYear} 年（年份待核）`
  return '年份未知'
}

function detailLink(work: CitedWorkView): string {
  return `/achievements/${work.id}`
}
</script>

<template>
  <div class="panel">
    <div class="panel-head">
      <h2>引用网络</h2>
      <span class="tiny faint">{{ statusLine }}</span>
    </div>
    <div class="panel-body">
      <div class="net-kpi">
        <div class="kpi">
          <span class="kpi-value">{{ coverage.referencesListed ?? 0 }}</span>
          <span class="kpi-label">来源给出的参考文献条目</span>
        </div>
        <div class="kpi">
          <span class="kpi-value">{{ references.count }}</span>
          <span class="kpi-label">其中收录于本平台</span>
        </div>
        <div class="kpi">
          <span class="kpi-value">{{ citedBy.count }}</span>
          <span class="kpi-label">本平台内引用本篇</span>
        </div>
        <div class="kpi">
          <span class="kpi-value">{{ platform.observedWorks }}</span>
          <span class="kpi-label">全平台已采集参考文献的成果</span>
        </div>
      </div>

      <p class="tiny faint" style="margin: 10px 0 0">
        平台共有 {{ platform.publicPapers }} 篇可见论文，其中
        <strong>{{ platform.observedWorks }}</strong> 篇已采集参考文献
        <template v-if="platform.coveragePercent != null">
          （覆盖 {{ platform.coveragePercent }}%）
        </template>
        ，只有这部分成果之间才可能出现可显示的引用边。
      </p>
      <p class="tiny faint" style="margin: 6px 0 0">{{ platform.note }}</p>

      <div class="net-columns">
        <section>
          <h3>本篇引用（{{ references.count }}）</h3>
          <p v-if="!references.items.length" class="tiny dim">{{ references.note }}</p>
          <ul v-else class="net-list">
            <li v-for="work in references.items" :key="work.id">
              <RouterLink :to="detailLink(work)">{{ work.title }}</RouterLink>
              <span class="tiny faint">
                · {{ work.typeLabel }} · {{ yearText(work) }}
                <template v-if="work.venue"> · {{ work.venue }}</template>
              </span>
            </li>
          </ul>
          <p v-if="references.items.length" class="tiny faint">{{ references.note }}</p>
        </section>

        <section>
          <h3>本平台内被引（{{ citedBy.count }}）</h3>
          <p v-if="!citedBy.items.length" class="tiny dim">
            本平台已采集参考文献的成果里，没有一篇引用了本篇。
          </p>
          <ul v-else class="net-list">
            <li v-for="work in citedBy.items" :key="work.id">
              <RouterLink :to="detailLink(work)">{{ work.title }}</RouterLink>
              <span class="tiny faint">
                · {{ work.typeLabel }} · {{ yearText(work) }}
                <template v-if="work.venue"> · {{ work.venue }}</template>
              </span>
            </li>
          </ul>
          <p class="tiny faint">{{ citedBy.note }}</p>
        </section>
      </div>

      <p v-if="isObserved && coverage.listingNote" class="tiny faint" style="margin-top: 10px">
        {{ coverage.listingNote }}<template v-if="(coverage.unidentified ?? 0) > 0">
          ；其中 {{ coverage.unidentified }} 条没有可识别标识（只有作者与题名等），无法建边</template
        >。
      </p>
      <p v-if="(coverage.selfCitations ?? 0) > 0" class="tiny faint">
        另有 {{ coverage.selfCitations }} 条自引（本篇引用自己），不计入引用关系。
      </p>
      <p v-if="network.reportedCitationCountNote" class="tiny faint" style="margin-top: 6px">
        {{ network.reportedCitationCountNote }}
      </p>
    </div>
  </div>
</template>

<style scoped>
.net-kpi {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 10px;
}
.kpi {
  border: 1px solid var(--line, #e3e6ea);
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.kpi-value {
  font-size: 22px;
  font-weight: 600;
}
.kpi-label {
  font-size: 12px;
  opacity: 0.75;
}
.net-columns {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 16px;
  margin-top: 16px;
}
.net-columns h3 {
  font-size: 14px;
  margin: 0 0 8px;
}
.net-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.net-list li {
  font-size: 13px;
  line-height: 1.5;
}
</style>
