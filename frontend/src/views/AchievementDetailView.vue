<script setup lang="ts">
/**
 * 成果详情页（公开）。
 *
 * 关键展示约定：**缺失的字段不显示为 0 或空字符串，而是明说「来源未提供」**。
 * 「引用 0」与「来源没说引用」在网上是完全不同的两句话，前者是结论，后者是未知。
 * 同理年份缺失单独写「年份未知」，不补一个默认年份。
 */
import { computed, onMounted } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { publicApi } from '@/api'
import type { PublicAchievementDetail } from '@/api/types'
import { useAsync } from '@/composables/useAsync'
import AsyncBlock from '@/components/AsyncBlock.vue'
import NatureBadge from '@/components/NatureBadge.vue'

const route = useRoute()
const id = computed(() => String(route.params.id ?? ''))

const detail = useAsync<PublicAchievementDetail & Record<string, unknown>>(() =>
  publicApi.achievement(id.value),
)

interface PaperView {
  doi?: string | null
  venue?: string | null
  publishedDate?: string | null
  preprintStatus?: string | null
  citationCount?: number | null
  citationSource?: string | null
  abstractText?: string | null
  citationNote?: string
}

const paper = computed<PaperView | null>(() => {
  const raw = detail.data.value?.paper
  if (!raw || typeof raw !== 'object') return null
  return raw as unknown as PaperView
})

const redirectedFrom = computed(() => detail.data.value?.redirectedFrom as string | undefined)

/**
 * 作者行。
 *
 * 只列关系合格的参与（后端已过滤），按署名序排列，不补全缺失的姓名——
 * 用标识占位而不是省略，是为了让「这位作者的身份还没确认」这件事可见。
 */
const authorLine = computed(() => {
  const rows = [...(detail.data.value?.contributions ?? [])].sort(
    (a, b) => (a.authorOrder ?? 0) - (b.authorOrder ?? 0),
  )
  return rows.map((row) => row.scholarName ?? `（待确认 ${row.scholarId}）`)
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
      empty-text="该成果不存在，或当前不具备公开资格。"
    >
      <template v-if="detail.data.value">
        <div v-if="redirectedFrom" class="notice info">
          该成果已被合并到另一个实体（原标识 <span class="mono">{{ redirectedFrom }}</span>），
          本页展示的是合并后的权威记录。旧链接不会指向错误对象，但两者不是两条记录。
        </div>

        <h1 style="margin-bottom: 8px; font-size: 26px; line-height: 1.35">
          {{ detail.data.value.title }}
        </h1>

        <div v-if="authorLine.length" class="result-authors" style="font-size: 13.5px">
          {{ authorLine.join('、') }}
        </div>

        <div class="result-source" style="margin-bottom: 12px">
          <template v-if="paper?.venue">{{ paper.venue }}</template>
          <template v-else>来源未提供发表载体</template>
          ·
          <template v-if="detail.data.value.publicationYear">
            {{ detail.data.value.publicationYear }} 年
          </template>
          <template v-else>年份未知（来源未提供）</template>
        </div>

        <div class="result-meta" style="margin-bottom: 16px">
          <NatureBadge :nature="detail.data.value.dataNature" />
          <span class="badge neutral">{{ detail.data.value.type }}</span>
          <span class="badge ok">{{ detail.data.value.effectiveStatus }}</span>
          <span class="badge ok">{{ detail.data.value.visibility }}</span>
          <span class="tiny faint mono">标识 {{ detail.data.value.id }} · 版本 v{{ detail.data.value.version }}</span>
        </div>

        <div class="grid cols-2">
          <div class="panel">
            <div class="panel-head"><h2>成果信息</h2></div>
            <div class="panel-body">
              <dl class="dl">
                <dt>标识</dt>
                <dd class="mono">{{ detail.data.value.id }}</dd>
                <template v-if="paper">
                  <dt>DOI</dt>
                  <dd>
                    <span v-if="paper.doi" class="mono">{{ paper.doi }}</span>
                    <span v-else class="faint">来源未提供</span>
                  </dd>
                  <dt>发表载体</dt>
                  <dd>
                    <span v-if="paper.venue">{{ paper.venue }}</span>
                    <span v-else class="faint">来源未提供</span>
                  </dd>
                  <dt>发表日期</dt>
                  <dd>
                    <span v-if="paper.publishedDate" class="mono">{{ paper.publishedDate }}</span>
                    <span v-else class="faint">
                      来源未提供完整日期（只有年份时不补 1 月 1 日）
                    </span>
                  </dd>
                  <dt>引用量</dt>
                  <dd>
                    <template v-if="paper.citationCount !== null && paper.citationCount !== undefined">
                      {{ paper.citationCount }}
                      <span class="badge neutral">{{ paper.citationSource ?? '来源未标注' }}</span>
                      <div class="tiny faint">{{ paper.citationNote }}</div>
                    </template>
                    <span v-else class="faint">来源未提供引用量</span>
                  </dd>
                </template>
              </dl>
            </div>
          </div>

          <div class="panel">
            <div class="panel-head">
              <h2>参与关系</h2>
              <span class="tiny faint">仅展示关系合格的参与，待核验与已排除不计入</span>
            </div>
            <div class="panel-body">
              <div
                v-if="(detail.data.value.contributions ?? []).length === 0"
                class="empty"
              >
                该成果暂无合格参与关系记录。
              </div>
              <div v-else class="scroll-x">
                <table class="data">
                  <thead>
                    <tr>
                      <th class="num">序</th>
                      <th>学者</th>
                      <th>角色</th>
                      <th>关系状态</th>
                      <th>成果发生时机构</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="row in detail.data.value.contributions" :key="row.scholarId + row.authorOrder">
                      <td class="num">{{ row.authorOrder }}</td>
                      <td>
                        <RouterLink :to="`/scholars/${row.scholarId}`">
                          {{ row.scholarName ?? row.scholarId }}
                        </RouterLink>
                      </td>
                      <td class="small">{{ row.role }}</td>
                      <td>
                        <span
                          class="badge"
                          :class="row.relationState === 'AUTO' || row.relationState === 'SELF_CONFIRMED' ? 'ok' : 'warn'"
                        >
                          {{ row.relationState }}
                        </span>
                      </td>
                      <td class="small">
                        <template v-if="row.organizations && row.organizations.length">
                          {{ row.organizations.join('；') }}
                        </template>
                        <span v-else class="faint">未知（不折算为学者现职机构）</span>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <h2>摘要</h2>
            <span class="tiny faint">来源原文，未经改写</span>
          </div>
          <div class="panel-body">
            <p v-if="paper?.abstractText" style="margin: 0; white-space: pre-wrap">
              {{ paper.abstractText }}
            </p>
            <div v-else class="empty">来源未提供摘要。</div>
          </div>
        </div>

        <div v-if="(detail.data.value.topics ?? []).length" class="panel">
          <div class="panel-head"><h2>领域标注</h2></div>
          <div class="panel-body btn-row">
            <span v-for="topic in detail.data.value.topics" :key="topic.topicId" class="badge neutral">
              {{ topic.canonicalName }}
            </span>
          </div>
        </div>
      </template>
    </AsyncBlock>
  </div>
</template>
