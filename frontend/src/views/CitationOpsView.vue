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

/**
 * 引文反向回填的状态。
 *
 * 与补水的回执分开存放：两件事方向相反（补水补观测，回填补节点），共用一个回执对象会让
 * 「上一轮补水的结果」在下一次回填之后消失，而这两组数字恰恰要放在一起看才说明问题。
 */
const backfillKind = ref('OPENALEX_ID')
const backfillLimit = ref(10)
const backfillRounds = ref(5)
const backfillResult = ref<Record<string, unknown> | null>(null)
const backfillMessage = ref('')

/**
 * 连续跑若干轮回填。
 *
 * 单轮只处理 10～20 个标识，这不是可调的性能参数而是安全上限：每收一条新成果，core 都要与全库
 * 比对标题做去重登记，单条约 0.5～1.2 秒，批一开大就超过读超时、整批回滚。所以「补得多」只能
 * 靠多跑几轮，不能靠调大批量。
 *
 * 累计口径在客户端做：每轮的回执是独立的，界面上的总数必须是这些轮的和，否则最后一轮的数字会
 * 覆盖前面几轮的成果，看起来像只补了一轮。
 */
async function backfill() {
  busy.value = 'backfill'
  backfillMessage.value = ''
  const rounds = Math.max(1, Math.min(Number(backfillRounds.value) || 1, 50))
  const total: Record<string, number> = {
    requested: 0,
    fetched: 0,
    missing: 0,
    accepted: 0,
    attached: 0,
    duplicate: 0,
    rejected: 0,
    resolvedRows: 0,
  }
  const failures: string[] = []
  let done = 0
  try {
    for (let i = 0; i < rounds; i += 1) {
      const page = (await adminApi.backfillCitations({
        kind: backfillKind.value,
        limit: backfillLimit.value,
      })) as Record<string, unknown>
      done += 1
      for (const key of Object.keys(total)) {
        total[key] += Number(page[key] ?? 0)
      }
      if (Array.isArray(page.failures)) {
        failures.push(...(page.failures as string[]))
      }
      backfillResult.value = {
        ...page,
        ...total,
        rounds: done,
        failures,
      }
      backfillMessage.value = `已跑 ${done}/${rounds} 轮，累计新收录 ${total.accepted} 篇、点亮 ${total.resolvedRows} 条引用边`
      // 候选取空说明这一类已经补完；继续请求只会拿到 0，白跑。
      if (Number(page.requested ?? 0) === 0) {
        backfillMessage.value += '；候选已取空，提前结束'
        break
      }
    }
    backfillMessage.value = `回填结束，共 ${done} 轮：累计新收录 ${total.accepted} 篇、点亮 ${total.resolvedRows} 条引用边`
    await loadStats()
  } catch (cause) {
    // 前面几轮已经改动了数据，不能因为最后一轮失败就说整体失败——累计值照常展示。
    backfillMessage.value = done > 0
      ? `跑到第 ${done} 轮时失败：${describeError(cause).message}；前 ${done} 轮的成果已生效`
      : describeError(cause).message
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

    <div class="panel">
      <div class="panel-head">
        <h2>反向回填站外被引文献</h2>
        <span class="tiny faint">把站内引了很多次、但本站还没收录的文献收进来</span>
      </div>
      <div class="panel-body">
        <p class="tiny faint" style="margin-top: 0">
          补水让「站内论文引用了谁」这件事被记录下来，但被引的那一端如果不在库内，边只能落在站外；
          反向回填补的是这一端。收一篇被引 200 次的论文，一次能点亮 200 条边，随机补摘要做不到。
          选样由 core 决定（它才知道哪些标识还没收录、各自被引几次）。
        </p>
        <div class="ops-form">
          <label>
            每轮处理
            <input v-model.number="backfillLimit" type="number" min="1" max="20" style="width: 88px" />
            个标识
          </label>
          <label>
            连续跑
            <input v-model.number="backfillRounds" type="number" min="1" max="50" style="width: 72px" />
            轮
          </label>
          <label>
            标识种类
            <select v-model="backfillKind" style="width: 200px">
              <option value="OPENALEX_ID">OpenAlex 作品号（占外部引用 84%）</option>
              <option value="DOI">DOI</option>
            </select>
          </label>
          <button class="btn primary" :disabled="busy !== ''" @click="backfill">
            {{ busy === 'backfill' ? '正在回填…' : '开始回填' }}
          </button>
        </div>
        <p class="tiny faint" style="margin-top: 6px">
          每轮上限 20 不是性能开关而是安全线：每收一条成果 core 都要与全库比对标题做去重登记，
          单条约 1 秒，批量一开大就超过读超时、整批回滚。要补得多，加轮数，不要加单批量。
        </p>
        <p v-if="backfillMessage" class="tiny" style="margin-top: 8px">{{ backfillMessage }}</p>

        <div v-if="backfillResult" style="margin-top: 12px">
          <p class="tiny faint" style="margin-bottom: 6px">
            以下为 {{ backfillResult.rounds }} 轮累计；「剩余候选」与「待解析标识」是最后一轮的实时值。
          </p>
          <div class="ops-kpi">
            <div class="kpi">
              <span class="kpi-value">
                {{ backfillResult.fetched }}/{{ backfillResult.requested }}
              </span>
              <span class="kpi-label">来源返回 / 请求的标识</span>
            </div>
            <div class="kpi">
              <span class="kpi-value">{{ backfillResult.accepted }}</span>
              <span class="kpi-label">新收录成果</span>
            </div>
            <div class="kpi">
              <span class="kpi-value">{{ backfillResult.duplicate }}</span>
              <span class="kpi-label">已存在（未重复建）</span>
            </div>
            <div class="kpi">
              <span class="kpi-value">{{ backfillResult.resolvedRows }}</span>
              <span class="kpi-label">随之点亮的引用边</span>
            </div>
            <div class="kpi">
              <span class="kpi-value">{{ backfillResult.remaining }}</span>
              <span class="kpi-label">该种类剩余候选</span>
            </div>
          </div>
          <p v-if="Number(backfillResult.resolvedRows) === 0" class="tiny warn-text" style="margin-top: 8px">
            本轮没有点亮任何引用边。新收录的成果只有在引用它的那些论文的参考文献里出现过，边才会连上；
            收进来的若是没人引用的文献，成果数会涨而引用网络不变。
          </p>
          <p class="tiny muted" style="margin-top: 6px">
            还有 {{ backfillResult.unresolvedRemaining }} 个「节点已进站、边仍是外部」的标识等待解析。
            回填每轮都会顺带解析一批，这个数降到 0 才算把能连的边都连上了。
          </p>
          <p v-if="Number(backfillResult.missing) > 0" class="tiny warn-text" style="margin-top: 8px">
            其中 {{ backfillResult.missing }} 个标识来源查不到（作品已删除、转为私有或标识失效）。
            这不是失败，但也不能算作「补上了」——它们仍会留在候选里，下一轮会被再次请求。
          </p>
          <p
            v-if="Array.isArray(backfillResult.failures) && backfillResult.failures.length > 0"
            class="tiny warn-text"
            style="margin-top: 6px"
          >
            有 {{ backfillResult.failures.length }} 批取数失败：
            {{ (backfillResult.failures as string[]).join('；') }}
          </p>
        </div>
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
