<script setup lang="ts">
/**
 * 审核工作台：待治理确认的成果、认领申请、门户修订申请。
 *
 * 三件事在这一页上共用一套「先填依据、再提交决定」的交互，而不是逐行的快捷按钮：
 *
 *  1. 每一项决定都必须带 reason，服务端也会拒绝空依据。把输入框摆在动作旁边，是为了让
 *     「写清楚为什么」成为提交的一部分，而不是提交失败后再回头补。
 *  2. 提交后不乐观更新本地状态，而是重新拉取列表。审核会改变成果的公开状态与投影水位，
 *     本地猜测的状态和真实状态很容易分叉。
 *  3. 无权限时如实展示 ACCESS_DENIED。入口出现但请求被拒，说明授权在服务端；把这当成
 *     前端 bug 去隐藏入口，会掩盖配置问题。
 */
import { onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { adminApi } from '@/api'
import { describeError } from '@/composables/useAsync'
import AsyncBlock from '@/components/AsyncBlock.vue'

type Row = Record<string, unknown>

const pending = reactive({ items: [] as Row[], loading: false, error: '', errorCode: '' })
const claims = reactive({ items: [] as Row[], loading: false, error: '', errorCode: '' })
const changes = reactive({ items: [] as Row[], loading: false, error: '', errorCode: '' })

const reasons = reactive<Record<string, string>>({})
const busy = ref('')
const actionError = ref('')
const actionCode = ref('')
const actionNote = ref('')

const CLAIM_DECISIONS = [
  { value: 'APPROVE', label: '通过', cls: 'btn primary small' },
  { value: 'NEED_MORE_EVIDENCE', label: '需补证', cls: 'btn small' },
  { value: 'DISPUTE', label: '存疑', cls: 'btn small' },
  { value: 'REJECT', label: '驳回', cls: 'btn danger small' },
]

async function loadSection(target: { items: Row[]; loading: boolean; error: string; errorCode: string }, loader: () => Promise<Row[]>) {
  target.loading = true
  target.error = ''
  target.errorCode = ''
  try {
    target.items = await loader()
  } catch (cause) {
    const described = describeError(cause)
    target.error = described.message
    target.errorCode = described.code
    // 失败时清空列表：留着上一次的内容会让人以为「这些还需要处理」。
    target.items = []
  } finally {
    target.loading = false
  }
}

function loadAll() {
  return Promise.all([
    loadSection(pending, () => adminApi.pendingAchievements({ page: 1, size: 50 })),
    loadSection(claims, () => adminApi.claims({ page: 1, size: 50 })),
    loadSection(changes, () => adminApi.portalChanges({ page: 1, size: 50 })),
  ])
}

function resetAction() {
  actionError.value = ''
  actionCode.value = ''
  actionNote.value = ''
}

/** 认领材料元信息。服务端只给元信息与受保护下载路径，材料本体不进入列表响应。 */
function evidenceOf(row: Row): Array<Record<string, unknown>> {
  const raw = row.evidence
  return Array.isArray(raw) ? (raw as Array<Record<string, unknown>>) : []
}

function formatSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '大小未知'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MiB`
}

async function run(key: string, fn: () => Promise<unknown>, note: string, reload: () => Promise<unknown>) {
  resetAction()
  busy.value = key
  try {
    await fn()
    actionNote.value = note
    await reload()
  } catch (cause) {
    const described = describeError(cause)
    actionError.value = described.message
    actionCode.value = described.code
  } finally {
    busy.value = ''
  }
}

function decideAchievement(id: string, action: 'confirm' | 'withdraw') {
  const reason = (reasons[`ach:${id}`] ?? '').trim()
  if (!reason) {
    actionError.value = '请先填写决定依据。审核决定会写入审计记录，无法留空的理由提交。'
    return
  }
  run(
    `ach:${id}:${action}`,
    () => (action === 'confirm' ? adminApi.confirmAchievement(id, reason) : adminApi.withdrawAchievement(id, reason)),
    action === 'confirm' ? '已确认发布。该成果将进入下一次投影发布批次。' : '已撤回公开。',
    () => loadSection(pending, () => adminApi.pendingAchievements({ page: 1, size: 50 })),
  )
}

function decideClaim(id: string, decision: string) {
  const reason = (reasons[`claim:${id}`] ?? '').trim()
  if (!reason) {
    actionError.value = '请先填写审核意见。'
    return
  }
  run(
    `claim:${id}`,
    () => adminApi.decideClaim(id, decision, reason),
    `认领申请已处理（${decision}）。通过后该账号才会获得对应的门户授权。`,
    () => loadSection(claims, () => adminApi.claims({ page: 1, size: 50 })),
  )
}

function decideChange(id: string, approve: boolean) {
  const reason = (reasons[`change:${id}`] ?? '').trim()
  if (!reason) {
    actionError.value = '请先填写审核意见。'
    return
  }
  run(
    `change:${id}`,
    () => adminApi.decidePortalChange(id, approve, reason),
    approve ? '已通过修订申请，字段将按申请值更新并留痕。' : '已驳回修订申请，字段保持当前值。',
    () => loadSection(changes, () => adminApi.portalChanges({ page: 1, size: 50 })),
  )
}

function statusBadge(status: unknown) {
  const value = String(status ?? '')
  if (value === 'PENDING_REVIEW' || value === 'PENDING' || value === 'PENDING_GOVERNANCE') return 'badge warn'
  if (value === 'APPROVED' || value === 'ACCEPTED' || value === 'PUBLISHED') return 'badge ok'
  if (value === 'REJECTED' || value === 'DECLINED' || value === 'DISPUTED') return 'badge danger'
  return 'badge neutral'
}

onMounted(loadAll)
</script>

<template>
  <div>
    <div style="display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap">
      <h1>审核工作台</h1>
      <button class="btn small" @click="loadAll">刷新</button>
    </div>

    <div v-if="actionError" class="notice danger">
      <div>{{ actionError }}</div>
      <div v-if="actionCode" class="tiny mono" style="margin-top: 6px">错误码 {{ actionCode }}</div>
    </div>
    <div v-if="actionNote" class="notice info">{{ actionNote }}</div>

    <div class="panel">
      <div class="panel-head">
        <h2>待治理确认的成果</h2>
        <span class="tiny faint">确认前不会出现在公开检索中</span>
      </div>
      <div class="panel-body tight">
        <AsyncBlock
          :loading="pending.loading"
          :error="pending.error"
          :error-code="pending.errorCode"
          :empty="pending.items.length === 0"
          empty-text="当前没有待治理确认的成果。"
        >
          <div v-for="row in pending.items" :key="String(row.id)" class="result-item">
            <div class="result-title">{{ String(row.title ?? '(无标题)') }}</div>
            <div class="result-meta">
              <span :class="statusBadge(row.effectiveStatus)">{{ String(row.effectiveStatus ?? '') }}</span>
              <span>{{ String(row.typeLabel ?? row.type ?? '') }}</span>
              <span v-if="row.publicationYear">年份 {{ String(row.publicationYear) }}</span>
              <span class="mono tiny">{{ String(row.id) }}</span>
              <RouterLink class="tiny" :to="`/achievements/${String(row.id)}`">查看详情</RouterLink>
            </div>
            <div class="field" style="margin: 10px 0 8px">
              <label :for="`ach-reason-${String(row.id)}`">决定依据（必填）</label>
              <input
                :id="`ach-reason-${String(row.id)}`"
                v-model="reasons[`ach:${String(row.id)}`]"
                type="text"
                placeholder="例如：来源元数据完整，作者与机构可核验"
              />
            </div>
            <div class="btn-row">
              <button
                class="btn primary small"
                :disabled="busy === `ach:${String(row.id)}:confirm`"
                @click="decideAchievement(String(row.id), 'confirm')"
              >
                确认发布
              </button>
              <button
                class="btn danger small"
                :disabled="busy === `ach:${String(row.id)}:withdraw`"
                @click="decideAchievement(String(row.id), 'withdraw')"
              >
                撤回
              </button>
            </div>
          </div>
        </AsyncBlock>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h2>认领申请</h2>
        <span class="tiny faint">通过后会授予该账号对应学者门户的维护授权</span>
      </div>
      <div class="panel-body tight">
        <AsyncBlock
          :loading="claims.loading"
          :error="claims.error"
          :error-code="claims.errorCode"
          :empty="claims.items.length === 0"
          empty-text="当前没有待处理的认领申请。"
        >
          <div v-for="row in claims.items" :key="String(row.id)" class="result-item">
            <div class="result-meta">
              <span :class="statusBadge(row.status)">{{ String(row.status ?? '') }}</span>
              <span class="mono tiny">{{ String(row.id) }}</span>
              <RouterLink class="tiny" :to="`/scholars/${String(row.scholarId)}`">
                学者 {{ String(row.scholarId) }}
              </RouterLink>
              <span v-if="row.submittedAt" class="mono tiny">{{ String(row.submittedAt) }}</span>
            </div>
            <p class="small" style="margin: 8px 0">
              {{ String(row.statement ?? row.note ?? '（申请人未填写说明）') }}
            </p>

            <div class="tiny" style="margin: 0 0 10px">
              <div class="dim">
                材料 {{ evidenceOf(row).length }} 份 · 本体经核心服务鉴权后代理下载，列表里不含路径
              </div>
              <div v-if="evidenceOf(row).length === 0" class="faint" style="margin-top: 4px">
                申请人尚未上传材料。仅凭说明不足以核验身份时，请选择「需补证」，并在审核意见里写明需要哪一类材料。
              </div>
              <div
                v-for="item in evidenceOf(row)"
                :key="String(item.id)"
                style="margin-top: 5px"
              >
                <a :href="`/core${String(item.downloadUrl)}`">下载材料</a>
                · {{ String(item.contentType) }} · {{ formatSize(Number(item.sizeBytes)) }}
                · <span class="mono faint">{{ String(item.createdAt) }}</span>
                · 指纹 <span class="mono faint">{{ String(item.contentHash).slice(0, 16) }}…</span>
              </div>
            </div>

            <div class="field" style="margin: 0 0 8px">
              <label :for="`claim-reason-${String(row.id)}`">审核意见（必填）</label>
              <input
                :id="`claim-reason-${String(row.id)}`"
                v-model="reasons[`claim:${String(row.id)}`]"
                type="text"
                placeholder="例如：邮箱域名与机构一致，材料齐全"
              />
            </div>
            <div class="btn-row">
              <button
                v-for="option in CLAIM_DECISIONS"
                :key="option.value"
                :class="option.cls"
                :disabled="busy === `claim:${String(row.id)}`"
                @click="decideClaim(String(row.id), option.value)"
              >
                {{ option.label }}
              </button>
            </div>
          </div>
        </AsyncBlock>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h2>门户修订申请</h2>
        <span class="tiny faint">通过后按申请值更新字段，来源原值仍保留在溯源记录中</span>
      </div>
      <div class="panel-body tight">
        <AsyncBlock
          :loading="changes.loading"
          :error="changes.error"
          :error-code="changes.errorCode"
          :empty="changes.items.length === 0"
          empty-text="当前没有待处理的修订申请。"
        >
          <div v-for="row in changes.items" :key="String(row.id)" class="result-item">
            <div class="result-meta">
              <span :class="statusBadge(row.status)">{{ String(row.status ?? '') }}</span>
              <span class="mono tiny">{{ String(row.id) }}</span>
              <span class="tiny">{{ String(row.fieldName ?? '') }}</span>
              <span v-if="row.requiresReview === false" class="badge neutral">不需复核</span>
            </div>
            <dl class="dl" style="margin: 8px 0">
              <dt>当前值</dt>
              <dd>{{ String(row.currentValue ?? '—') }}</dd>
              <dt>申请值</dt>
              <dd><strong>{{ String(row.proposedValue ?? '—') }}</strong></dd>
            </dl>
            <div class="field" style="margin: 0 0 8px">
              <label :for="`change-reason-${String(row.id)}`">审核意见（必填）</label>
              <input
                :id="`change-reason-${String(row.id)}`"
                v-model="reasons[`change:${String(row.id)}`]"
                type="text"
              />
            </div>
            <div class="btn-row">
              <button
                class="btn primary small"
                :disabled="busy === `change:${String(row.id)}`"
                @click="decideChange(String(row.id), true)"
              >
                通过
              </button>
              <button
                class="btn danger small"
                :disabled="busy === `change:${String(row.id)}`"
                @click="decideChange(String(row.id), false)"
              >
                驳回
              </button>
            </div>
          </div>
        </AsyncBlock>
      </div>
    </div>
  </div>
</template>
