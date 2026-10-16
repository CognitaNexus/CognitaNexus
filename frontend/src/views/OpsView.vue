<script setup lang="ts">
/**
 * 一致性与投递。
 *
 * 这一页是「平台现在能不能信」的仪表盘，因此它必须把「不知道」和「正常」分开：
 *
 *  1. 读不到一致性状态时显示未知，而不是默认正常。把未知渲染成绿色，是状态页最常见的谎报。
 *  2. 三个水位分别展示，不做合并：接收水位、已见最高水位、已发布水位分别对应不同的故障。
 *     发布落后说明投影在补课，接收落后说明核心服务的投递没到。
 *  3. 修订空洞单列。水位连续不代表修订连续——中间丢了一条修订，水位照样能往前走，
 *     而公开视图会永远对不齐。有空洞时必须显式报警并要求人工介入。
 *  4. 死信单独一张表并带重投入口。失败记录被静默跳过，等于把「投递失败」变成了「数据不一致」。
 */
import { computed, onMounted, ref } from 'vue'
import { opsApi, queryApi } from '@/api'
import type { ConsistencyView } from '@/api/types'
import { describeError } from '@/composables/useAsync'
import AsyncBlock from '@/components/AsyncBlock.vue'

const consistency = ref<ConsistencyView | null>(null)
const consistencyError = ref('')
const consistencyCode = ref('')
const consistencyLoading = ref(false)

/** 内部运维端点需要服务令牌，浏览器侧必然被拒。这里把「被拒」与「不可达」分开表示。 */
type OpsState = 'unknown' | 'unauthorized' | 'ok' | 'error'
const outboxState = ref<OpsState>('unknown')
const outbox = ref<Record<string, unknown> | null>(null)
const outboxMessage = ref('')

const deadLetterConsumer = ref('')
const deadLetters = ref<Record<string, unknown> | null>(null)
const deadLetterMessage = ref('')
const busy = ref('')
const actionNote = ref('')

async function loadConsistency() {
  consistencyLoading.value = true
  consistencyError.value = ''
  consistencyCode.value = ''
  try {
    consistency.value = await queryApi.consistency()
  } catch (cause) {
    const described = describeError(cause)
    consistencyError.value = described.message
    consistencyCode.value = described.code
    consistency.value = null
  } finally {
    consistencyLoading.value = false
  }
}

async function loadOutbox() {
  outboxState.value = 'unknown'
  outbox.value = null
  outboxMessage.value = ''
  try {
    outbox.value = (await opsApi.outboxHealth()) as unknown as Record<string, unknown>
    outboxState.value = 'ok'
  } catch (cause) {
    const described = describeError(cause)
    if (described.code === 'ACCESS_DENIED' || described.code === 'AUTH_REQUIRED') {
      outboxState.value = 'unauthorized'
      outboxMessage.value =
        '投递台账由服务令牌保护，浏览器会话读不到。这不是故障：真正需要读它的场景是运维巡检，' +
        '请使用 ops 脚本以服务身份访问同一端点。'
    } else {
      outboxState.value = 'error'
      outboxMessage.value = `${described.message}${described.code ? `（${described.code}）` : ''}`
    }
  }
}

function loadAll() {
  return Promise.all([loadConsistency(), loadOutbox()])
}

const gap = computed(() => consistency.value?.revisionGap ?? null)

/** 服务端是否声明可以对外服务。未知时既不标绿也不标红。 */
const servingLabel = computed(() => {
  if (!consistency.value) return '未知'
  return consistency.value.serving ? '可对外服务' : '公开视图正在更新'
})

async function inspectDeadLetters() {
  const consumerId = deadLetterConsumer.value.trim()
  deadLetterMessage.value = ''
  deadLetters.value = null
  if (!consumerId) {
    deadLetterMessage.value = '请输入消费者标识（consumerId）。'
    return
  }
  busy.value = 'deadLetters'
  try {
    deadLetters.value = await opsApi.deadLetters(consumerId, 50)
  } catch (cause) {
    const described = describeError(cause)
    deadLetterMessage.value =
      described.code === 'ACCESS_DENIED' || described.code === 'AUTH_REQUIRED'
        ? '需要服务令牌。请使用 ops 脚本以服务身份访问同一端点。'
        : `${described.message}${described.code ? `（${described.code}）` : ''}`
  } finally {
    busy.value = ''
  }
}

async function requeue() {
  const consumerId = deadLetterConsumer.value.trim()
  if (!consumerId) {
    deadLetterMessage.value = '请输入消费者标识（consumerId）。'
    return
  }
  busy.value = 'requeue'
  actionNote.value = ''
  try {
    await opsApi.requeueFailed(consumerId)
    actionNote.value = `已将 ${consumerId} 的失败投递重新入队。重投后请刷新一致性状态观察发布水位是否推进。`
    await Promise.all([loadConsistency(), inspectDeadLetters()])
  } catch (cause) {
    const described = describeError(cause)
    deadLetterMessage.value =
      described.code === 'ACCESS_DENIED' || described.code === 'AUTH_REQUIRED'
        ? '需要服务令牌。请使用 ops 脚本以服务身份访问同一端点。'
        : `${described.message}${described.code ? `（${described.code}）` : ''}`
  } finally {
    busy.value = ''
  }
}

function parseConsumers(): Record<string, unknown>[] {
  const value = outbox.value?.consumers
  return Array.isArray(value) ? (value as Record<string, unknown>[]) : []
}

function parseDeadLetterItems(): Record<string, unknown>[] {
  const value = deadLetters.value?.items ?? deadLetters.value?.deadLetters
  return Array.isArray(value) ? (value as Record<string, unknown>[]) : []
}

onMounted(loadAll)
</script>

<template>
  <div>
    <div style="display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap">
      <h1>一致性与投递</h1>
      <button class="btn small" @click="loadAll">刷新</button>
    </div>

    <div v-if="actionNote" class="notice info">{{ actionNote }}</div>

    <div class="panel">
      <div class="panel-head">
        <h2>公开视图水位</h2>
        <span class="tiny faint">来自查询服务的对外一致性端点</span>
      </div>
      <div class="panel-body">
        <AsyncBlock
          :loading="consistencyLoading"
          :error="consistencyError"
          :error-code="consistencyCode"
          :empty="consistency === null"
          empty-text="尚未取到一致性状态。"
        >
          <template v-if="consistency">
            <div class="grid cols-4" style="margin-bottom: 16px">
              <div class="metric">
                <div class="metric-label">接收水位</div>
                <div class="metric-value">{{ consistency.receivedRevision }}</div>
                <div class="metric-sub">已收到的最高修订号</div>
              </div>
              <div class="metric">
                <div class="metric-label">已见最高水位</div>
                <div class="metric-value">{{ consistency.highestSeenRevision }}</div>
                <div class="metric-sub">接收落后 {{ consistency.receiveLag }}</div>
              </div>
              <div class="metric">
                <div class="metric-label">已发布水位</div>
                <div class="metric-value">{{ consistency.publishedRevision }}</div>
                <div class="metric-sub">发布落后 {{ consistency.publishLag }}</div>
              </div>
              <div class="metric">
                <div class="metric-label">权威修订</div>
                <div class="metric-value">
                  {{ consistency.coreCatalogRevision ?? '未知' }}
                </div>
                <div class="metric-sub">
                  {{ consistency.coreReachable ? '核心服务可达' : '核心服务不可达' }}
                  <template v-if="consistency.lagBehindCoreRevision">
                    · 落后 {{ consistency.lagBehindCoreRevision }}
                  </template>
                </div>
              </div>
            </div>

            <div
              class="notice"
              :class="consistency.serving ? 'info' : 'danger'"
              style="margin-bottom: 14px"
            >
              <strong>{{ servingLabel }}</strong>
              <template v-if="consistency.requiredPublicRevision !== undefined">
                · 对外所需的修订为 {{ consistency.requiredPublicRevision }}
              </template>
              <div v-if="!consistency.serving" class="tiny" style="margin-top: 6px">
                本地已发布水位低于权威要求时，公开读接口会以失败关闭（fail-closed）而不是返回过期数据。
              </div>
            </div>

            <div
              class="notice"
              :class="gap?.blocked ? 'danger' : 'info'"
              style="margin-bottom: 14px"
            >
              <strong>{{ gap?.blocked ? '检测到修订空洞，发布已阻塞。' : '修订连续。' }}</strong>
              <div class="tiny" style="margin-top: 6px">{{ gap?.detail }}</div>
              <dl v-if="gap" class="dl" style="margin-top: 10px">
                <dt>期望的下一个修订</dt>
                <dd class="mono">{{ gap.expectedNextRevision }}</dd>
                <dt>已收到的最大连续修订</dt>
                <dd class="mono">{{ gap.lowestReceivedRevision ?? '无' }}</dd>
                <dt>缺失条数</dt>
                <dd class="mono">{{ gap.missingRevisions }}</dd>
              </dl>
              <div v-if="gap?.recovery" class="tiny" style="margin-top: 8px">
                处置建议：{{ gap.recovery }}
              </div>
            </div>

            <dl class="dl">
              <dt>活动分代</dt>
              <dd class="mono">{{ consistency.activeGeneration }}</dd>
              <dt>投递状态分布</dt>
              <dd class="small">
                已接收 {{ consistency.stateReceived ?? 0 }} · 已应用 {{ consistency.stateApplied ?? 0 }} ·
                已隔离 {{ consistency.stateIsolated ?? 0 }} · 失败 {{ consistency.stateFailed ?? 0 }}
              </dd>
              <dt>最早已隔离修订</dt>
              <dd class="mono">{{ consistency.oldestIsolatedRevision }}</dd>
            </dl>

            <p class="small dim" style="margin: 12px 0 0">
              三个水位不做合并：接收落后说明投递没到，发布落后说明投影还在补课，权威落后说明核心服务
              本身的写入尚未被查询侧看到。把三者合成一个「同步状态」会掩盖掉具体是哪一段断了。
            </p>
          </template>
        </AsyncBlock>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h2>投递台账</h2>
        <span class="tiny faint">需要服务令牌</span>
      </div>
      <div class="panel-body">
        <div v-if="outboxState === 'unauthorized'" class="notice warn">
          <strong>未授权读取。</strong>{{ outboxMessage }}
        </div>
        <div v-else-if="outboxState === 'error'" class="notice danger">
          {{ outboxMessage }}
        </div>

        <template v-if="outboxState === 'ok' && outbox">
          <dl class="dl" style="margin-bottom: 14px">
            <dt>权威修订</dt>
            <dd class="mono">{{ String(outbox.catalogRevision ?? '') }}</dd>
          </dl>
          <div class="scroll-x">
            <table class="data">
              <thead>
                <tr>
                  <th>消费者</th>
                  <th class="num">待投递</th>
                  <th class="num">最早待投递</th>
                  <th class="num">死信数</th>
                  <th class="num">死信区间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in parseConsumers()" :key="String(row.consumerId)">
                  <td class="mono tiny">{{ String(row.consumerId) }}</td>
                  <td class="num">{{ String(row.pendingCount ?? 0) }}</td>
                  <td class="num">{{ String(row.oldestPendingRevision ?? 0) }}</td>
                  <td class="num">
                    <span :class="Number(row.deadLetterCount ?? 0) > 0 ? 'badge danger' : 'badge ok'">
                      {{ String(row.deadLetterCount ?? 0) }}
                    </span>
                  </td>
                  <td class="num tiny">
                    {{ String(row.deadLetterOldestRevision ?? '—') }} ~
                    {{ String(row.deadLetterNewestRevision ?? '—') }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="small dim" style="margin: 10px 0 0">{{ String(outbox.note ?? '') }}</p>
        </template>

        <div v-else-if="outboxState === 'unknown'" class="loading">正在读取投递台账…</div>

        <hr style="border: none; border-top: 1px solid var(--border); margin: 18px 0" />

        <h3>死信查询与重投</h3>
        <p class="small dim">
          失败投递如果不重投，投影会永远停在某个修订上；而水位显示「已接收」会让人误以为它已经到了。
          重投以消费者为粒度，不会影响其他消费者。
        </p>
        <div class="btn-row" style="margin-bottom: 12px">
          <input
            v-model="deadLetterConsumer"
            type="text"
            placeholder="consumerId，例如 query-service"
            style="flex: 1; min-width: 240px"
          />
          <button class="btn" :disabled="busy === 'deadLetters'" @click="inspectDeadLetters">查询死信</button>
          <button class="btn primary" :disabled="busy === 'requeue'" @click="requeue">重新入队失败项</button>
        </div>

        <div v-if="deadLetterMessage" class="notice warn">{{ deadLetterMessage }}</div>

        <div v-if="deadLetters" class="scroll-x">
          <table class="data">
            <thead>
              <tr>
                <th>修订</th>
                <th>事件类型</th>
                <th>聚合</th>
                <th class="num">失败次数</th>
                <th>最近错误</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in parseDeadLetterItems()" :key="String(row.eventId ?? index)">
                <td class="mono tiny">{{ String(row.revision ?? '') }}</td>
                <td class="tiny">{{ String(row.eventType ?? '') }}</td>
                <td class="mono tiny">{{ String(row.aggregateId ?? '—') }}</td>
                <td class="num">{{ String(row.failedCount ?? row.attempts ?? 0) }}</td>
                <td class="tiny">{{ String(row.lastError ?? '—') }}</td>
              </tr>
              <tr v-if="parseDeadLetterItems().length === 0">
                <td colspan="5" class="dim small">该消费者没有死信记录。</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>
