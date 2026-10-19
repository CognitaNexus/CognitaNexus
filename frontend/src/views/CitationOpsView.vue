<script setup lang="ts">
/**
 * 引用网络采集台。
 *
 * 这个页面回答三个问题，且顺序固定：现在覆盖了多少、还差多少、为什么停。
 *
 * 为什么把「为什么停」当成一等公民：补水会消耗来源配额，而 OpenAlex 的额度是全站共享的每日
 * 额度，用尽后返回 429 并要等十几小时。如果页面只显示「已补 N 篇」，运维会以为继续点就会继续涨；
 * 实际上点多少次都停在同一个数字上，直到额度重置或改用 Crossref。因此回执里的 stoppedReason
 * 与剩余配额必须同屏显示。
 *
 * 不自动刷新、不自动重试：这一步花的是外部配额，属于需要人判断的动作。
 */
import { onMounted, ref } from 'vue'
import { adminApi } from '@/api'
import { describeError } from '@/composables/useAsync'

interface Stats {
  observedWorks: number
  notFoundWorks: number
  publicPapers: number
  coveragePercent: number | null
  pendingWorks: number
  note: string
}

interface Report {
  target: number
  worksFetched: number
  worksMatched: number
  referencesWritten: number
  resolvedEdges: number
  externalReferences: number
  notFoundWorks: number
  rounds: number
  provider: string
  creditsRemaining: number | null
  stoppedReason: string
  note: string
}

const stats = ref<Stats | null>(null)
const statsError = ref('')
const report = ref<Report | null>(null)
const message = ref('')
const busy = ref('')
const search = ref('')
const limit = ref(200)
const provider = ref('AUTO')

async function loadStats() {
  statsError.value = ''
  try {
    stats.value = await adminApi.citationStats({ search: search.value || undefined })
  } catch (cause) {
    stats.value = null
    statsError.value = describeError(cause).message
  }
}

async function hydrate() {
  busy.value = 'hydrate'
  message.value = ''
  try {
    report.value = await adminApi.hydrateCitations({
      limit: limit.value,
      search: search.value || undefined,
      provider: provider.value,
    })
    message.value = '本轮补水已结束，结论见下方回执'
    await loadStats()
  } catch (cause) {
    message.value = describeError(cause).message
  } finally {
    busy.value = ''
  }
}

async function resolve() {
  busy.value = 'resolve'
  message.value = ''
  try {
    const result = await adminApi.resolveCitations()
    message.value = `重新解析完成：扫描 ${result.keysScanned} 个标识，升级 ${result.keysResolved} 个，涉及 ${result.rowsResolved} 条引用`
    await loadStats()
  } catch (cause) {
    message.value = describeError(cause).message
  } finally {
    busy.value = ''
  }
}

onMounted(loadStats)
</script>

<template>
  <div>
    <div>
      <h1 style="margin-bottom: 4px">引用网络采集</h1>
      <p class="tiny faint" style="margin-top: 0">
        成果之间的引用边来自来源返回的参考文献列表。未采集的成果既不显示它引用了谁，也不会出现在
        别人的被引列表里——缺边是覆盖率问题，不是「这些成果之间没有引用」。
      </p>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h2>当前覆盖</h2>
        <span class="tiny faint">数字来自 core 的引用网络统计</span>
      </div>
      <div class="panel-body">
        <p v-if="statsError" class="tiny warn-text">读取失败：{{ statsError }}</p>
        <template v-else-if="stats">
          <div class="ops-kpi">
            <div class="kpi">
              <span class="kpi-value">{{ stats.observedWorks }}</span>
              <span class="kpi-label">已采集参考文献的成果</span>
            </div>
            <div class="kpi">
              <span class="kpi-value">{{ stats.pendingWorks }}</span>
              <span class="kpi-label">待补成果</span>
            </div>
            <div class="kpi">
              <span class="kpi-value">{{ stats.notFoundWorks }}</span>
              <span class="kpi-label">来源查不到（不再重试）</span>
            </div>
            <div class="kpi">
              <span class="kpi-value">{{ stats.publicPapers }}</span>
              <span class="kpi-label">可见论文总数</span>
            </div>
          </div>
          <p class="tiny faint" style="margin-top: 10px">
            覆盖率
            <strong>{{ stats.coveragePercent == null ? '不可计算' : stats.coveragePercent + '%' }}</strong>
            <template v-if="stats.coveragePercent == null">（没有可见论文，分母为 0 时不给 0%）</template>
            。{{ stats.note }}
          </p>
        </template>
        <p v-else class="tiny faint">正在读取…</p>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h2>补一轮参考文献</h2>
        <span class="tiny faint">
          OpenAlex 有每日共享额度（一次请求可覆盖 25 篇）；额度不可用时自动改用 Crossref（免费，逐篇）
        </span>
      </div>
      <div class="panel-body">
        <div class="ops-form">
          <label>
            本次最多补
            <input v-model.number="limit" type="number" min="1" max="1000" style="width: 96px" />
            篇
          </label>
          <label>
            题名包含
            <input
              v-model="search"
              placeholder="可留空；填词可集中补一个子领域"
              style="width: 240px"
            />
          </label>
          <label>
            来源
            <select v-model="provider" style="width: 220px">
              <option value="AUTO">自动（先 OpenAlex 后 Crossref）</option>
              <option value="CROSSREF">只用 Crossref</option>
              <option value="OPENALEX">只用 OpenAlex</option>
            </select>
          </label>
          <button class="btn primary" :disabled="busy !== ''" @click="hydrate">
            {{ busy === 'hydrate' ? '正在补水…' : '开始补水' }}
          </button>
          <button class="btn" :disabled="busy !== ''" @click="resolve">
            {{ busy === 'resolve' ? '正在解析…' : '重新解析外部引用' }}
          </button>
        </div>
        <p class="tiny faint" style="margin-top: 8px">
          「重新解析」不访问外网、不消耗配额：它只是拿标识注册表把此前解析不到的引用再对一遍，
          用于新成果入库后把旧引用升级为站内边。
        </p>
        <p v-if="message" class="tiny" style="margin-top: 8px">{{ message }}</p>
      </div>
    </div>

    <div v-if="report" class="panel">
      <div class="panel-head">
        <h2>本轮回执</h2>
        <span class="tiny faint">来源 {{ report.provider }}，共 {{ report.rounds }} 轮</span>
      </div>
      <div class="panel-body">
        <div class="ops-kpi">
          <div class="kpi">
            <span class="kpi-value">{{ report.worksFetched }}/{{ report.target }}</span>
            <span class="kpi-label">取到的成果数 / 目标</span>
          </div>
          <div class="kpi">
            <span class="kpi-value">{{ report.referencesWritten }}</span>
            <span class="kpi-label">写入的引用条目</span>
          </div>
          <div class="kpi">
            <span class="kpi-value">{{ report.resolvedEdges }}</span>
            <span class="kpi-label">其中站内边</span>
          </div>
          <div class="kpi">
            <span class="kpi-value">{{ report.externalReferences }}</span>
            <span class="kpi-label">平台外文献（只计数）</span>
          </div>
        </div>
        <p class="tiny" style="margin-top: 10px">
          <strong>停止原因：</strong>{{ report.stoppedReason }}
          <template v-if="report.creditsRemaining != null">
            ；来源剩余配额 {{ report.creditsRemaining }}
          </template>
        </p>
        <p v-if="report.notFoundWorks" class="tiny faint">
          其中 {{ report.notFoundWorks }} 篇来源查不到，已记为 NOT_FOUND，不会再进入待补清单。
        </p>
        <p class="tiny faint">{{ report.note }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ops-kpi {
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
.ops-form {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
}
.ops-form label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
}
</style>
