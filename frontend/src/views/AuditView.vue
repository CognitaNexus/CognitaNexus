<script setup lang="ts">
/**
 * 审计与溯源。
 *
 * 这一页回答的是「这个结论是怎么来的」，而不是「现在是什么」。因此三块内容都围绕痕迹组织：
 *
 *  1. 审计记录：谁在什么时候对哪个对象做了什么，必须带原因。查询按对象维度而不是全表翻页，
 *     因为脱离对象看一串 ACHIEVEMENT_CONFIRMED 说明不了任何问题。
 *  2. 字段溯源：每个字段的取值来自哪条来源记录、是原值还是人工值。来源原值不可被人工修订覆盖，
 *     这一点只有在逐字段的层级标注上才能被验证。
 *  3. 账号与角色：封禁与授角同样是有后果的管理动作，因此与审计放在同一页，操作前必须写原因。
 */
import { onMounted, reactive, ref } from 'vue'
import { adminApi } from '@/api'
import { describeError } from '@/composables/useAsync'
import AsyncBlock from '@/components/AsyncBlock.vue'

type Row = Record<string, unknown>

const audit = reactive({ items: [] as Row[], loading: false, error: '', errorCode: '', total: null as number | null })
const provenance = reactive({ items: [] as Row[], loading: false, error: '', errorCode: '' })
const accounts = reactive({ items: [] as Row[], loading: false, error: '', errorCode: '' })

const auditFilter = reactive({ targetType: '', targetId: '', actorAccountId: '' })
const provenanceId = ref('')
const accountReasons = reactive<Record<string, string>>({})
const roleDraft = reactive<Record<string, string>>({})
const roleReason = ref('')

const busy = ref('')
const actionError = ref('')
const actionCode = ref('')
const actionNote = ref('')

const ROLE_OPTIONS = ['USER', 'REVIEWER', 'PLATFORM_ADMIN']

async function loadSection(
  target: { items: Row[]; loading: boolean; error: string; errorCode: string },
  loader: () => Promise<Row[]>,
) {
  target.loading = true
  target.error = ''
  target.errorCode = ''
  try {
    target.items = await loader()
  } catch (cause) {
    const described = describeError(cause)
    target.error = described.message
    target.errorCode = described.code
    target.items = []
  } finally {
    target.loading = false
  }
}

function searchAudit() {
  audit.total = null
  return loadSection(audit, () =>
    adminApi.audit({
      targetType: auditFilter.targetType || undefined,
      targetId: auditFilter.targetId || undefined,
      actorAccountId: auditFilter.actorAccountId || undefined,
      page: 1,
      size: 50,
    }),
  )
}

function loadProvenance() {
  const id = provenanceId.value.trim()
  if (!id) {
    actionError.value = '请输入成果标识。'
    return Promise.resolve()
  }
  return loadSection(provenance, () => adminApi.provenance(id))
}

function loadAccounts() {
  return loadSection(accounts, () => adminApi.accounts())
}

function loadAll() {
  return Promise.all([searchAudit(), loadAccounts()])
}

function resetAction() {
  actionError.value = ''
  actionCode.value = ''
  actionNote.value = ''
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

function disableAccount(row: Row) {
  const id = String(row.id ?? '')
  const reason = (accountReasons[id] ?? '').trim()
  if (!reason) {
    actionError.value = '封禁账号必须填写原因。'
    return
  }
  run(`disable:${id}`, () => adminApi.disableAccount(id, reason), `账号 ${id} 已封禁。`, loadAccounts)
}

function grantRole(row: Row) {
  const id = String(row.id ?? '')
  const role = (roleDraft[id] ?? '').trim()
  if (!role) {
    actionError.value = '请选择要授予的角色。'
    return
  }
  if (!roleReason.value.trim()) {
    actionError.value = '授予角色必须填写原因，该操作会写入审计记录。'
    return
  }
  run(
    `role:${id}`,
    () => adminApi.grantRole(id, role),
    `已为账号 ${id} 授予 ${role}。该账号需要重新读取会话才能看到新增能力。`,
    loadAccounts,
  )
}

/** 溯源值层级：来源原值与人工修订值必须在视觉上区分开，不能只靠文字描述。 */
function levelBadge(level: unknown) {
  const value = String(level ?? '')
  if (value === 'SOURCE_ORIGINAL' || value === 'SOURCE') return 'badge ok'
  if (value === 'HUMAN_REVISED' || value === 'HUMAN') return 'badge warn'
  return 'badge neutral'
}

function levelLabel(level: unknown) {
  const value = String(level ?? '')
  if (value === 'SOURCE_ORIGINAL' || value === 'SOURCE') return '来源原值'
  if (value === 'HUMAN_REVISED' || value === 'HUMAN') return '人工值'
  return value || '未知'
}

onMounted(loadAll)
</script>

<template>
  <div>
    <div style="display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap">
      <h1>审计与溯源</h1>
      <button class="btn small" @click="loadAll">刷新</button>
    </div>

    <div v-if="actionError" class="notice danger">
      <div>{{ actionError }}</div>
      <div v-if="actionCode" class="tiny mono" style="margin-top: 6px">错误码 {{ actionCode }}</div>
    </div>
    <div v-if="actionNote" class="notice info">{{ actionNote }}</div>

    <div class="panel">
      <div class="panel-head">
        <h2>审计记录</h2>
        <span class="tiny faint">按对象或操作者查询，不做全表浏览</span>
      </div>
      <div class="panel-body">
        <div class="btn-row" style="margin-bottom: 14px">
          <select v-model="auditFilter.targetType" style="width: auto">
            <option value="">全部对象类型</option>
            <option value="ACHIEVEMENT">成果</option>
            <option value="SCHOLAR">学者</option>
            <option value="CLAIM_REQUEST">认领申请</option>
            <option value="PORTAL_CHANGE_REQUEST">门户修订</option>
            <option value="SOURCE_CONFIG">来源配置</option>
            <option value="ACCOUNT">账号</option>
          </select>
          <input
            v-model="auditFilter.targetId"
            type="text"
            placeholder="对象标识"
            style="flex: 1; min-width: 200px"
          />
          <input
            v-model="auditFilter.actorAccountId"
            type="text"
            placeholder="操作者账号标识"
            style="flex: 1; min-width: 200px"
          />
          <button class="btn" @click="searchAudit">查询</button>
        </div>

        <AsyncBlock
          :loading="audit.loading"
          :error="audit.error"
          :error-code="audit.errorCode"
          :empty="audit.items.length === 0"
          empty-text="没有匹配的审计记录。"
        >
          <div class="scroll-x">
            <table class="data">
              <thead>
                <tr>
                  <th>时间</th>
                  <th>操作</th>
                  <th>对象</th>
                  <th>操作者</th>
                  <th>原因</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in audit.items" :key="String(row.id ?? index)">
                  <td class="mono tiny">{{ String(row.occurredAt ?? row.createdAt ?? '') }}</td>
                  <td><span class="badge neutral">{{ String(row.action ?? row.operation ?? '') }}</span></td>
                  <td class="mono tiny">
                    {{ String(row.targetType ?? row.entityType ?? '') }}
                    <div>{{ String(row.targetId ?? row.entityId ?? '') }}</div>
                  </td>
                  <td class="mono tiny">{{ String(row.actorAccountId ?? row.actor ?? '—') }}</td>
                  <td class="tiny">{{ String(row.reason ?? '—') }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-if="audit.total !== null" class="tiny faint" style="margin: 10px 0 0">
            共 {{ audit.total }} 条，本页展示 {{ audit.items.length }} 条。
          </p>
        </AsyncBlock>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h2>成果字段溯源</h2>
        <span class="tiny faint">来源原值不可被人工修订覆盖</span>
      </div>
      <div class="panel-body">
        <div class="btn-row" style="margin-bottom: 14px">
          <input
            v-model="provenanceId"
            type="text"
            placeholder="成果标识"
            style="flex: 1; min-width: 240px"
          />
          <button class="btn" @click="loadProvenance">查询溯源</button>
        </div>

        <AsyncBlock
          :loading="provenance.loading"
          :error="provenance.error"
          :error-code="provenance.errorCode"
          :empty="provenance.items.length === 0"
          empty-text="输入成果标识后可查看每个字段的取值来源。"
        >
          <div class="scroll-x">
            <table class="data">
              <thead>
                <tr>
                  <th>字段</th>
                  <th>值层级</th>
                  <th>取值</th>
                  <th>来源记录</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in provenance.items" :key="String(row.fieldName ?? index)">
                  <td class="mono tiny">{{ String(row.fieldName ?? '') }}</td>
                  <td><span :class="levelBadge(row.valueLevel)">{{ levelLabel(row.valueLevel) }}</span></td>
                  <td class="tiny">{{ String(row.valueText ?? '—') }}</td>
                  <td class="mono tiny">{{ String(row.sourceRecordId ?? '—') }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="small dim" style="margin: 10px 0 0">
            同一个字段可能同时存在来源原值与人工值。它们在库中是两条独立记录，人工值只影响展示口径，
            不会覆盖来源原值——这正是这一页要让人可以直接核对的事。
          </p>
        </AsyncBlock>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h2>账号与角色</h2>
        <span class="tiny faint">封禁与授角都会写入审计记录</span>
      </div>
      <div class="panel-body">
        <div class="field" style="max-width: 520px">
          <label for="role-reason">授角原因（授予角色时必填）</label>
          <input id="role-reason" v-model="roleReason" type="text" placeholder="例如：调整审核分工" />
        </div>

        <AsyncBlock
          :loading="accounts.loading"
          :error="accounts.error"
          :error-code="accounts.errorCode"
          :empty="accounts.items.length === 0"
          empty-text="没有可管理的账号。"
        >
          <div v-for="row in accounts.items" :key="String(row.id)" class="result-item">
            <div class="result-meta">
              <strong>{{ String(row.displayName ?? row.username ?? '') }}</strong>
              <span class="mono tiny">{{ String(row.username ?? '') }}</span>
              <span :class="row.status === 'DISABLED' ? 'badge danger' : 'badge ok'">
                {{ row.status === 'DISABLED' ? '已封禁' : String(row.status ?? '未知') }}
              </span>
              <span class="tiny faint">
                角色：{{ (row.roles as string[])?.join(', ') || '无' }}
              </span>
              <span class="tiny faint">会话版本 {{ String(row.authVersion ?? '') }}</span>
            </div>

            <div class="btn-row" style="margin-top: 8px">
              <input
                v-model="accountReasons[String(row.id)]"
                type="text"
                placeholder="封禁原因（必填）"
                style="flex: 1; min-width: 200px"
              />
              <select v-model="roleDraft[String(row.id)]" style="width: auto">
                <option value="">选择角色…</option>
                <option v-for="role in ROLE_OPTIONS" :key="role" :value="role">{{ role }}</option>
              </select>
              <button class="btn small" :disabled="busy === `role:${String(row.id)}`" @click="grantRole(row)">
                授予角色
              </button>
              <button
                class="btn danger small"
                :disabled="busy === `disable:${String(row.id)}`"
                @click="disableAccount(row)"
              >
                封禁
              </button>
            </div>
          </div>
        </AsyncBlock>

        <p class="tiny faint" style="margin: 10px 0 0">
          角色只决定能力集合，不决定对象级归属。平台管理员不会因此自动获得某个学者门户的维护权，
          门户归属仍需通过认领流程取得。
        </p>
      </div>
    </div>
  </div>
</template>
