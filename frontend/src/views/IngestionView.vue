<script setup lang="ts">
/**
 * 采集管理台。
 *
 * 这一页最重要的不是「能启动采集」，而是把采集过程里容易被掩盖的三件事摆出来：
 *
 *  1. 能力边界：适配器类型里哪些真正实现了。把未实现的种类显示成可选，会让人以为
 *     「选了没反应」是自己操作错了。
 *  2. 许可状态：来源的 licenseReviewState 与 licenseApproved 分开展示。许可未复核的来源
 *     即使配置完整也不应启动，界面不能把它显示成「正常可用」。
 *  3. 交接计数：抓取量、暂存量、已交接量、被拒量分别展示，不做「已采集 N 条」这样的合并口径。
 *     四个数字之间的差值才是真正需要看的——差在哪一步，问题就出在哪一步。
 */
import { onMounted, reactive, ref } from 'vue'
import { adminApi, ingestionApi } from '@/api'
import { describeError } from '@/composables/useAsync'
import AsyncBlock from '@/components/AsyncBlock.vue'

type Row = Record<string, unknown>

function str(value: unknown): string {
  return value === null || value === undefined ? '' : String(value)
}

function num(value: unknown, fallback = 0): number {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

function section() {
  return reactive({ items: [] as Row[], loading: false, error: '', errorCode: '' })
}

const capabilities = ref<Row | null>(null)
const capabilityError = ref('')
const sources = reactive({ items: [] as Row[], loading: false, error: '', errorCode: '' })
const jobs = reactive({ items: [] as Row[], loading: false, error: '', errorCode: '' })
/** 核心服务里的权威来源配置。与下面的运行副本并列展示，才能看出「同步」到底同步了什么。 */
const authoritative = section()
const allowedFieldsDraft = reactive<Record<string, string>>({})

const jobDetail = ref<Row | null>(null)
const jobDetailError = ref('')
const jobDetailNote = ref('')
const selectedJobId = ref('')

const sourceReasons = reactive<Record<string, string>>({})

/**
 * 业务更新周期的草稿：按来源标识存。空串表示「不设定」，不是 0——
 * 0 会被库级 CHECK 拒绝，而空串代表这个人还没决定，两者不能混。
 */
const planDrafts = reactive<
  Record<string, { newRecord: string; fieldRefresh: string; note: string }>
>({})

function planDraftOf(row: Row) {
  const key = String(row.id)
  if (!planDrafts[key]) {
    planDrafts[key] = { newRecord: '', fieldRefresh: '', note: '' }
  }
  return planDrafts[key]
}

/** 分钟转成「人话」：1440 分钟写成 1 天，避免运维在 10080 这种数字上心算。 */
function minutesLabel(value: unknown) {
  const minutes = Number(value)
  if (!Number.isFinite(minutes) || minutes <= 0) {
    return '未设定'
  }
  if (minutes % 1440 === 0) return `${minutes / 1440} 天`
  if (minutes % 60 === 0) return `${minutes / 60} 小时`
  return `${minutes} 分钟`
}
const jobForm = reactive({ sourceId: '', jobKey: '', reason: '' })
const busy = ref('')
const actionError = ref('')
const actionCode = ref('')
const actionNote = ref('')

async function loadCapabilities() {
  capabilityError.value = ''
  try {
    capabilities.value = await ingestionApi.capabilities()
  } catch (cause) {
    const described = describeError(cause)
    capabilityError.value = `${described.message}${described.code ? `（${described.code}）` : ''}`
    capabilities.value = null
  }
}

async function loadSources() {
  sources.loading = true
  sources.error = ''
  sources.errorCode = ''
  try {
    sources.items = (await ingestionApi.sources()).items ?? []
  } catch (cause) {
    const described = describeError(cause)
    sources.error = described.message
    sources.errorCode = described.code
    sources.items = []
  } finally {
    sources.loading = false
  }
}

async function loadJobs() {
  jobs.loading = true
  jobs.error = ''
  jobs.errorCode = ''
  try {
    jobs.items = (await ingestionApi.jobs()).items ?? []
  } catch (cause) {
    const described = describeError(cause)
    jobs.error = described.message
    jobs.errorCode = described.code
    jobs.items = []
  } finally {
    jobs.loading = false
  }
}

function loadAll() {
  return Promise.all([loadCapabilities(), loadSources(), loadJobs(), loadAuthoritative()])
}

function resetAction() {
  actionError.value = ''
  actionCode.value = ''
  actionNote.value = ''
}

async function loadAuthoritative() {
  authoritative.loading = true
  authoritative.error = ''
  authoritative.errorCode = ''
  try {
    authoritative.items = await adminApi.sources()
  } catch (cause) {
    const described = describeError(cause)
    authoritative.error = described.message
    authoritative.errorCode = described.code
    authoritative.items = []
  } finally {
    authoritative.loading = false
  }
}

/**
 * 收紧字段白名单。
 *
 * 只允许收紧、不允许追加：白名单的作用是限制采集端能落地的字段，放开它的后果（把未复核的字段
 * 重新纳入采集）不是这个按钮该承担的责任，因此界面上不提供「加回字段」的入口。
 */
function tightenFields(row: Row) {
  const sourceId = String(row.id)
  const raw = (allowedFieldsDraft[sourceId] ?? '').trim()
  if (!raw) {
    actionError.value = '请填写收紧后的字段白名单，用逗号分隔。'
    return
  }
  const next = raw
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
  const current = ((row.allowedFields as string[]) ?? []).slice()
  const added = next.filter((field) => !current.includes(field))
  if (added.length > 0) {
    actionError.value = `不允许通过本入口新增字段：${added.join(', ')}。这里只能收紧白名单。`
    return
  }
  run(
    `fields:${sourceId}`,
    () => adminApi.setAllowedFields(sourceId, next, '管理台收紧字段白名单'),
    `已收紧为 ${next.length} 个字段。收紧是即时生效的，采集端下一次同步后即按新白名单过滤。`,
    loadAuthoritative,
  )
}

async function run(key: string, fn: () => Promise<unknown>, note: string, reload?: () => Promise<unknown>) {
  resetAction()
  busy.value = key
  try {
    await fn()
    actionNote.value = note
    if (reload) await reload()
  } catch (cause) {
    const described = describeError(cause)
    actionError.value = described.message
    actionCode.value = described.code
  } finally {
    busy.value = ''
  }
}

function syncSource(row: Row) {
  run(
    `sync:${String(row.id)}`,
    () => ingestionApi.syncSource(String(row.id)),
    '已从核心服务同步权威配置。若权威配置版本更高，本地运行参数会被覆盖为权威值。',
    loadSources,
  )
}

function saveUpdatePlan(row: Row) {
  const draft = planDraftOf(row)
  const newRecord = draft.newRecord.trim() === '' ? null : Number(draft.newRecord.trim())
  const fieldRefresh = draft.fieldRefresh.trim() === '' ? null : Number(draft.fieldRefresh.trim())
  if (newRecord !== null && (!Number.isInteger(newRecord) || newRecord < 60 || newRecord > 525600)) {
    actionError.value = '发现新记录的周期须在 60 到 525600 分钟之间（1 小时到 1 年），留空表示不设定。'
    return
  }
  if (
    fieldRefresh !== null &&
    (!Number.isInteger(fieldRefresh) || fieldRefresh < 60 || fieldRefresh > 525600)
  ) {
    actionError.value = '字段刷新周期须在 60 到 525600 分钟之间（1 小时到 1 年），留空表示不设定。'
    return
  }
  if ((newRecord !== null || fieldRefresh !== null) && draft.note.trim() === '') {
    actionError.value = '设定更新周期必须写明依据（来源声明频率／许可条款／人工评估），否则这个数字下次无法复核。'
    return
  }
  run(
    `plan:${String(row.id)}`,
    () =>
      ingestionApi.setSourceUpdatePlan(String(row.id), {
        newRecordIntervalMinutes: newRecord,
        fieldRefreshIntervalMinutes: fieldRefresh,
        note: draft.note.trim() || undefined,
      }),
    newRecord === null && fieldRefresh === null
      ? '已撤销业务更新周期：该来源退回「只由人工创建任务驱动」。'
      : '业务更新周期已保存。它只决定「多久该跑一轮」，与任务轮询间隔、失败退避是三件事。',
    loadSources,
  )
}

function toggleSource(row: Row) {
  const enabled = row.enabled !== true
  const reason = (sourceReasons[String(row.id)] ?? '').trim()
  if (!reason) {
    actionError.value = '启用或停用来源必须填写原因，该动作会写入审计记录。'
    return
  }
  run(
    `toggle:${String(row.id)}`,
    () => ingestionApi.setSourceEnabled(String(row.id), enabled, reason),
    enabled ? '来源已启用。' : '来源已停用，进行中的任务不会立即中断。',
    loadSources,
  )
}

function createJob() {
  if (!jobForm.sourceId.trim() || !jobForm.jobKey.trim()) {
    actionError.value = '来源标识与任务键都不能为空。'
    return
  }
  run(
    'createJob',
    () =>
      ingestionApi.createJob({
        sourceId: jobForm.sourceId.trim(),
        jobKey: jobForm.jobKey.trim(),
        reason: jobForm.reason.trim() || undefined,
      }),
    '采集任务已创建。任务键相同的重复创建不会产生第二个任务。',
    loadJobs,
  )
}

function runJob(row: Row, maxRounds: number) {
  run(
    `run:${String(row.id)}`,
    () => ingestionApi.runJob(String(row.id), maxRounds),
    `已驱动任务执行最多 ${maxRounds} 轮。轮次上限是为了避免一次操作把配额打满。`,
    loadJobs,
  )
}

function pauseJob(row: Row) {
  run(`pause:${String(row.id)}`, () => ingestionApi.pauseJob(String(row.id), '管理台手动暂停'), '任务已暂停。', loadJobs)
}

function resumeJob(row: Row) {
  run(`resume:${String(row.id)}`, () => ingestionApi.resumeJob(String(row.id), '管理台手动恢复'), '任务已恢复。', loadJobs)
}

async function inspectJob(row: Row) {
  const id = String(row.id)
  selectedJobId.value = id
  jobDetailError.value = ''
  jobDetailNote.value = ''
  try {
    const [statistics, batches, logs] = await Promise.all([
      ingestionApi.statistics(id),
      ingestionApi.batches(id),
      ingestionApi.logs(id),
    ])
    jobDetail.value = { statistics, batches, logs }
  } catch (cause) {
    const described = describeError(cause)
    jobDetailError.value = `${described.message}${described.code ? `（${described.code}）` : ''}`
    jobDetail.value = null
  }
}

function jobStatusBadge(status: unknown) {
  const value = String(status ?? '')
  if (value === 'RUNNING' || value === 'LEASED') return 'badge ok'
  if (value === 'PAUSED' || value === 'PENDING') return 'badge warn'
  if (value === 'FAILED' || value === 'DEAD') return 'badge danger'
  return 'badge neutral'
}

/**
 * 退避状态。
 *
 * 抓取失败会按连续失败次数退避（1、2、4、8… 秒，上限由运行参数决定），退避窗口内后台执行器
 * 不会取这个任务。所以「任务为什么没在跑」有两个完全不同的答案——停摆故障，或者正常的退避——
 * 而只看状态与 lastError 区分不出来：两者都显示 RUNNING 加一条错误。
 */
function backoffActive(row: Row): boolean {
  const next = str(row.nextAttemptAt)
  if (!next) return false
  const at = Date.parse(next)
  return Number.isFinite(at) && at > Date.now()
}

function backoffLabel(row: Row): string {
  const failures = num(row.consecutiveFailures)
  const next = str(row.nextAttemptAt)
  if (!next) return failures > 0 ? `连续失败 ${failures} 次（已复位）` : '—'
  if (!backoffActive(row)) return `连续失败 ${failures} 次（窗口已过）`
  const seconds = Math.max(1, Math.round((Date.parse(next) - Date.now()) / 1000))
  return `退避中 · 连续失败 ${failures} 次 · 约 ${seconds} 秒后重试`
}

onMounted(loadAll)
</script>

<template>
  <div>
    <div style="display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap">
      <h1>采集管理台</h1>
      <button class="btn small" @click="loadAll">刷新</button>
    </div>

    <div v-if="actionError" class="notice danger">
      <div>{{ actionError }}</div>
      <div v-if="actionCode" class="tiny mono" style="margin-top: 6px">错误码 {{ actionCode }}</div>
    </div>
    <div v-if="actionNote" class="notice info">{{ actionNote }}</div>

    <div class="panel">
      <div class="panel-head">
        <h2>采集能力与运行参数</h2>
        <span class="tiny faint">只读</span>
      </div>
      <div class="panel-body">
        <div v-if="capabilityError" class="notice danger">
          {{ capabilityError }}
          <div class="tiny" style="margin-top: 6px">
            读不到能力清单时，界面不展示任何「可选来源」，避免给出无法兑现的选项。
          </div>
        </div>
        <template v-else-if="capabilities">
          <div class="grid cols-2">
            <div>
              <h3>适配器类型</h3>
              <table class="data">
                <thead>
                  <tr>
                    <th>类型</th>
                    <th>说明</th>
                    <th>状态</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="kind in (capabilities.adapterKinds as Row[]) ?? []"
                    :key="String(kind.kind)"
                  >
                    <td class="mono tiny">{{ String(kind.kind) }}</td>
                    <td class="tiny">{{ String(kind.label ?? '') }}</td>
                    <td>
                      <span :class="kind.implemented ? 'badge ok' : 'badge neutral'">
                        {{ kind.implemented ? '已实现' : '未实现' }}
                      </span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div>
              <h3>运行参数</h3>
              <dl class="dl">
                <dt>批大小</dt>
                <dd>{{ String(capabilities.batchSize ?? '') }}</dd>
                <dt>单批最大字节</dt>
                <dd>{{ String(capabilities.maxBatchBytes ?? '') }}</dd>
                <dt>单轮最大页数</dt>
                <dd>{{ String(capabilities.maxPagesPerRun ?? '') }}</dd>
                <dt>租约秒数</dt>
                <dd>{{ String(capabilities.leaseSeconds ?? '') }}</dd>
                <dt>后台工作线程</dt>
                <dd>
                  {{ capabilities.workerEnabled ? '已启用' : '未启用' }}
                  <span class="tiny faint">· 间隔 {{ String(capabilities.workerIntervalMs ?? '') }} ms</span>
                </dd>
                <dt>交接最大重试</dt>
                <dd>{{ String(capabilities.maxHandoffAttempts ?? '') }}</dd>
              </dl>
              <p class="tiny faint" style="margin: 10px 0 0">
                未启用后台线程时，采集只会在手动触发时执行。这不是故障，而是本地部署的默认取值。
              </p>
            </div>
          </div>
        </template>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h2>来源运行配置</h2>
        <span class="tiny faint">权威配置在核心服务，这里只读同步结果</span>
      </div>
      <div class="panel-body tight">
        <AsyncBlock
          :loading="sources.loading"
          :error="sources.error"
          :error-code="sources.errorCode"
          :empty="sources.items.length === 0"
          empty-text="还没有登记任何来源运行配置。"
        >
          <div v-for="row in sources.items" :key="String(row.id)" class="result-item">
            <div class="result-meta">
              <strong>{{ String(row.displayName ?? row.sourceCode ?? '') }}</strong>
              <span class="mono tiny">{{ String(row.sourceCode ?? '') }}</span>
              <span :class="row.enabled ? 'badge ok' : 'badge neutral'">
                {{ row.enabled ? '已启用' : '已停用' }}
              </span>
              <span :class="row.licenseApproved ? 'badge ok' : 'badge danger'">
                许可{{ row.licenseApproved ? '已复核' : '未复核' }}
              </span>
              <span class="badge neutral">{{ String(row.licenseReviewState ?? '') }}</span>
              <span class="tiny faint">
                配置版本 {{ String(row.configVersion ?? '') }} / 已同步 {{ String(row.syncedConfigVersion ?? '—') }}
              </span>
            </div>

            <div v-if="!row.licenseApproved" class="notice danger" style="margin: 10px 0">
              <strong>许可未复核。</strong>
              该来源的授权范围尚未确认，即使配置完整也不应启动采集。请先在核心服务完成许可复核。
            </div>

            <dl class="dl" style="margin: 8px 0">
              <dt>适配器</dt>
              <dd class="mono tiny">{{ String(row.adapterKind ?? '—') }}</dd>
              <dt>端点</dt>
              <dd class="mono tiny">{{ String(row.baseEndpoint ?? '—') }}</dd>
              <dt>查询表达式</dt>
              <dd class="mono tiny">{{ String(row.queryExpression ?? '—') }}</dd>
              <dt>字段白名单</dt>
              <dd class="tiny">{{ (row.allowedFields as string[])?.join(', ') || '—' }}</dd>
              <dt>许可说明</dt>
              <dd class="tiny">{{ String(row.licenseNote ?? '—') }}</dd>
              <dt>更新周期</dt>
              <dd class="tiny">
                发现新记录 {{ minutesLabel(row.newRecordIntervalMinutes) }} · 字段刷新
                {{ minutesLabel(row.fieldRefreshIntervalMinutes) }}
                <span v-if="!row.hasUpdatePlan" class="faint">
                  （未设定：任务只由人工创建驱动，不会自动按周期排期）
                </span>
              </dd>
              <dt>下次到期</dt>
              <dd class="tiny">{{ row.nextDueAt ? String(row.nextDueAt) : '未排期' }}</dd>
              <dt>周期依据</dt>
              <dd class="tiny">{{ String(row.updatePlanNote ?? '—') }}</dd>
            </dl>

            <div class="btn-row" style="margin-bottom: 8px">
              <input
                v-model="planDraftOf(row).newRecord"
                type="number"
                min="60"
                max="525600"
                placeholder="发现新记录（分钟，留空=不设定）"
                style="flex: 1; min-width: 200px"
              />
              <input
                v-model="planDraftOf(row).fieldRefresh"
                type="number"
                min="60"
                max="525600"
                placeholder="字段刷新（分钟，留空=不设定）"
                style="flex: 1; min-width: 200px"
              />
              <input
                v-model="planDraftOf(row).note"
                type="text"
                placeholder="周期依据（设定周期时必填）"
                style="flex: 1; min-width: 240px"
              />
              <button
                class="btn small"
                :disabled="busy === `plan:${String(row.id)}`"
                @click="saveUpdatePlan(row)"
              >
                保存周期
              </button>
            </div>

            <div class="btn-row" style="margin-bottom: 8px">
              <input
                v-model="sourceReasons[String(row.id)]"
                type="text"
                placeholder="启用 / 停用原因（必填）"
                style="flex: 1; min-width: 220px"
              />
              <button
                class="btn small"
                :disabled="busy === `sync:${String(row.id)}`"
                @click="syncSource(row)"
              >
                从核心同步
              </button>
              <button
                class="btn small"
                :disabled="busy === `toggle:${String(row.id)}`"
                @click="toggleSource(row)"
              >
                {{ row.enabled ? '停用' : '启用' }}
              </button>
            </div>
          </div>
        </AsyncBlock>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h2>来源权威配置（核心服务）</h2>
        <span class="tiny faint">字段白名单只能收紧，不能在这里放开</span>
      </div>
      <div class="panel-body tight">
        <AsyncBlock
          :loading="authoritative.loading"
          :error="authoritative.error"
          :error-code="authoritative.errorCode"
          :empty="authoritative.items.length === 0"
          empty-text="核心服务中还没有登记来源配置。"
        >
          <div v-for="row in authoritative.items" :key="String(row.id)" class="result-item">
            <div class="result-meta">
              <strong>{{ String(row.displayName ?? row.sourceCode ?? '') }}</strong>
              <span class="mono tiny">{{ String(row.sourceCode ?? '') }}</span>
              <span :class="row.enabled ? 'badge ok' : 'badge neutral'">
                {{ row.enabled ? '权威已启用' : '权威已停用' }}
              </span>
              <span class="tiny faint">配置版本 {{ String(row.configVersion ?? '') }}</span>
            </div>
            <dl class="dl" style="margin: 8px 0">
              <dt>许可范围</dt>
              <dd class="tiny">{{ String(row.licenseScope ?? '—') }}</dd>
              <dt>基址</dt>
              <dd class="mono tiny">{{ String(row.baseUrl ?? '—') }}</dd>
              <dt>当前白名单</dt>
              <dd class="tiny">{{ (row.allowedFields as string[])?.join(', ') || '—' }}</dd>
            </dl>
            <div class="btn-row">
              <input
                v-model="allowedFieldsDraft[String(row.id)]"
                type="text"
                placeholder="收紧后的字段，逗号分隔"
                style="flex: 1; min-width: 220px"
              />
              <button class="btn small" :disabled="busy === `fields:${String(row.id)}`" @click="tightenFields(row)">
                收紧白名单
              </button>
            </div>
          </div>
        </AsyncBlock>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h2>采集任务</h2>
        <span class="tiny faint">交接采用至少一次投递，重复记录由核心服务按来源标识去重</span>
      </div>
      <div class="panel-body">
        <div class="panel" style="margin-bottom: 14px; box-shadow: none; background: var(--panel-alt)">
          <div class="panel-body">
            <h3>新建任务</h3>
            <div class="grid cols-3">
              <div class="field" style="margin: 0">
                <label for="job-source">来源标识</label>
                <input id="job-source" v-model="jobForm.sourceId" type="text" placeholder="sourceId" />
              </div>
              <div class="field" style="margin: 0">
                <label for="job-key">任务键</label>
                <input id="job-key" v-model="jobForm.jobKey" type="text" placeholder="例如 crossref-2026-w1" />
                <span class="tiny faint">同键重复创建不会产生第二个任务。</span>
              </div>
              <div class="field" style="margin: 0">
                <label for="job-reason">创建原因</label>
                <input id="job-reason" v-model="jobForm.reason" type="text" />
              </div>
            </div>
            <button class="btn primary" style="margin-top: 10px" :disabled="busy === 'createJob'" @click="createJob">
              创建任务
            </button>
          </div>
        </div>

        <AsyncBlock
          :loading="jobs.loading"
          :error="jobs.error"
          :error-code="jobs.errorCode"
          :empty="jobs.items.length === 0"
          empty-text="还没有采集任务。先在上方创建一个。"
        >
          <div class="scroll-x">
            <table class="data">
              <thead>
                <tr>
                  <th>任务</th>
                  <th>来源</th>
                  <th>状态</th>
                  <th class="num">抓取</th>
                  <th class="num">暂存</th>
                  <th class="num">已交接</th>
                  <th class="num">被拒</th>
                  <th>游标</th>
                  <th>最近错误</th>
                  <th>退避</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in jobs.items" :key="String(row.id)">
                  <td>
                    <div class="mono tiny">{{ String(row.id) }}</div>
                    <div class="tiny faint">{{ String(row.jobKey ?? '') }}</div>
                  </td>
                  <td class="mono tiny">{{ String(row.sourceConfigId ?? '') }}</td>
                  <td><span :class="jobStatusBadge(row.status)">{{ String(row.status ?? '') }}</span></td>
                  <td class="num">{{ String(row.totalFetched ?? 0) }}</td>
                  <td class="num">{{ String(row.totalStaged ?? 0) }}</td>
                  <td class="num">{{ String(row.totalHandedOff ?? 0) }}</td>
                  <td class="num">{{ String(row.totalRejected ?? 0) }}</td>
                  <td class="mono tiny">{{ String(row.cursor ?? '—') }}</td>
                  <td class="tiny">{{ String(row.lastError ?? '—') }}</td>
                  <td class="tiny">
                    <!-- 只给 lastError 时看不出「它正在退避」，运维会以为调度坏了。
                         退避中的任务不会被后台执行器取走，所以必须说明「到什么时候」。 -->
                    <span v-if="backoffLabel(row) === '—'" class="faint">—</span>
                    <span v-else :class="backoffActive(row) ? 'badge warn' : 'badge neutral'">
                      {{ backoffLabel(row) }}
                    </span>
                  </td>
                  <td>
                    <div class="btn-row">
                      <button class="btn small" :disabled="busy === `run:${String(row.id)}`" @click="runJob(row, 2)">
                        执行
                      </button>
                      <button class="btn small" :disabled="busy === `pause:${String(row.id)}`" @click="pauseJob(row)">
                        暂停
                      </button>
                      <button class="btn small" :disabled="busy === `resume:${String(row.id)}`" @click="resumeJob(row)">
                        恢复
                      </button>
                      <button class="btn small" @click="inspectJob(row)">明细</button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <p class="tiny faint" style="margin: 10px 0 0">
            四个计数不做合并：抓取量包含被拒条目，暂存量包含尚未交接的条目。只有「已交接」才意味着
            核心服务已受理，暂存量不等于已入库。
          </p>
        </AsyncBlock>

        <div v-if="selectedJobId" class="panel" style="margin-top: 16px; box-shadow: none">
          <div class="panel-head">
            <h3>任务明细 · <span class="mono">{{ selectedJobId }}</span></h3>
          </div>
          <div class="panel-body">
            <div v-if="jobDetailError" class="notice danger">{{ jobDetailError }}</div>
            <template v-else-if="jobDetail">
              <h3>统计</h3>
              <pre class="pre">{{ JSON.stringify(jobDetail.statistics, null, 2) }}</pre>
              <h3 style="margin-top: 14px">交接批次</h3>
              <pre class="pre">{{ JSON.stringify(jobDetail.batches, null, 2) }}</pre>
              <h3 style="margin-top: 14px">抓取日志</h3>
              <pre class="pre">{{ JSON.stringify(jobDetail.logs, null, 2) }}</pre>
            </template>
            <div v-else class="loading">正在读取明细…</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
