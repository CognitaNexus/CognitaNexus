<script setup lang="ts">
/**
 * 账号设置：本人修改口令。
 *
 * 这一页存在的理由不是「表单没地方放」，而是平台**没有邮件通道**：
 *
 *  1. 忘记口令时没有任何自助找回路径，唯一出路是请管理员重置（管理端「账号与角色」）。
 *     这句实话写在页面上，比藏在帮助文档里更有用——否则用户会一直找「忘记密码」链接。
 *  2. 管理员重置后，账号带着「需修改」标记；这一页是清除标记的唯一入口。
 *     标记不清除不会阻断登录，但会一直在顶栏提示，避免一次性口令被长期使用。
 *  3. 改密会让其他设备上的会话立即失效。这条后果必须提前说，而不是等用户回办公室发现被登出。
 */
import { computed, onMounted, ref } from 'vue'
import { accountApi, authApi } from '@/api'
import { describeError } from '@/composables/useAsync'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()

const form = ref({ currentPassword: '', newPassword: '', confirmPassword: '' })
const busy = ref(false)
const error = ref('')
const errorCode = ref('')
const note = ref('')

const mismatch = computed(
  () => form.value.confirmPassword.length > 0 && form.value.newPassword !== form.value.confirmPassword,
)

/** 本地只挡「两次不一致」与空值；长度、字符类别由服务端策略裁定，不在这里复制一份规则。 */
const canSubmit = computed(
  () =>
    !busy.value &&
    form.value.currentPassword.length > 0 &&
    form.value.newPassword.length > 0 &&
    form.value.newPassword === form.value.confirmPassword,
)

async function submit() {
  if (!canSubmit.value) return
  busy.value = true
  error.value = ''
  errorCode.value = ''
  note.value = ''
  try {
    const result = await authApi.changePassword({
      currentPassword: form.value.currentPassword,
      newPassword: form.value.newPassword,
    })
    form.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
    note.value = String(result.note ?? '口令已修改。')
    // 会话里的 authVersion 与 mustChangePassword 都已变化，必须以服务端为准重新读取
    await session.refresh()
  } catch (cause) {
    const described = describeError(cause)
    error.value = described.message
    errorCode.value = described.code
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------------------
// 数据导出
// ---------------------------------------------------------------------------
const exportBusy = ref(false)
const exportError = ref('')
const exportNote = ref('')

async function downloadExport() {
  if (exportBusy.value) return
  exportBusy.value = true
  exportError.value = ''
  exportNote.value = ''
  try {
    const envelope = await accountApi.exportAccountData()
    const url = URL.createObjectURL(envelope.data)
    const link = document.createElement('a')
    link.href = url
    link.download = `account-export-${new Date().toISOString().slice(0, 10)}.json`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
    exportNote.value =
      '已开始下载。文件里是你账号相关的记录；口令哈希、会话信息与认领材料文件本体不在其中。'
  } catch (cause) {
    exportError.value = describeError(cause).message
  } finally {
    exportBusy.value = false
  }
}

// ---------------------------------------------------------------------------
// 注销申请
// ---------------------------------------------------------------------------
const closure = ref<{ open: boolean; id?: string; effectiveAt?: string; note?: string }>({
  open: false,
})
const closureReason = ref('')
const closureConfirm = ref('')
const closureBusy = ref(false)
const closureError = ref('')
const closureNote = ref('')

const canRequestClosure = computed(
  () =>
    !closureBusy.value &&
    !closure.value.open &&
    closureReason.value.trim().length > 0 &&
    closureConfirm.value.trim() === '注销',
)

async function loadClosure() {
  try {
    closure.value = await accountApi.closureStatus()
  } catch {
    // 读不到申请状态不该让整页失败；保持 open=false，用户点击申请时服务端仍会拦重复申请。
    closure.value = { open: false }
  }
}

async function requestClosure() {
  if (!canRequestClosure.value) return
  closureBusy.value = true
  closureError.value = ''
  closureNote.value = ''
  try {
    const result = await accountApi.requestClosure(closureReason.value.trim())
    closureNote.value = result.note ?? '申请已登记。'
    closureReason.value = ''
    closureConfirm.value = ''
    await loadClosure()
  } catch (cause) {
    const described = describeError(cause)
    closureError.value = described.message
  } finally {
    closureBusy.value = false
  }
}

async function cancelClosure() {
  if (!closure.value.id || closureBusy.value) return
  closureBusy.value = true
  closureError.value = ''
  closureNote.value = ''
  try {
    await accountApi.cancelClosure(closure.value.id)
    closureNote.value = '注销申请已撤销，账号保持可用。'
    await loadClosure()
  } catch (cause) {
    closureError.value = describeError(cause).message
  } finally {
    closureBusy.value = false
  }
}

function dateLabel(iso?: string): string {
  if (!iso) return '—'
  const at = new Date(iso)
  return Number.isNaN(at.getTime()) ? iso : at.toLocaleString()
}

onMounted(loadClosure)
</script>

<template>
  <div class="panel">
    <div class="panel-head">
      <h1 style="margin: 0">账号设置</h1>
      <span class="tiny faint">登录名与显示名由注册时确定，不在此页修改</span>
    </div>

    <div class="panel-body">
      <div v-if="session.session?.mustChangePassword" class="strip warn" style="margin-bottom: 16px">
        <strong>当前口令是管理员重置的一次性口令。</strong>
        它只被显示过一次，且不会长期有效——请立即在下方改成只有你知道的口令。
      </div>

      <div class="result-item" style="margin-bottom: 16px">
        <div class="result-meta">
          <strong>{{ session.session?.displayName }}</strong>
          <span class="mono tiny">{{ session.session?.username }}</span>
          <span class="badge neutral">{{ (session.session?.roles ?? []).join(' · ') }}</span>
        </div>
      </div>

      <form style="max-width: 420px" @submit.prevent="submit">
        <div class="field">
          <label for="pwd-current">当前口令</label>
          <input
            id="pwd-current"
            v-model="form.currentPassword"
            type="password"
            autocomplete="current-password"
          />
        </div>
        <div class="field">
          <label for="pwd-new">新口令</label>
          <input
            id="pwd-new"
            v-model="form.newPassword"
            type="password"
            autocomplete="new-password"
          />
          <span class="tiny faint">至少 10 位，须同时包含字母与数字。</span>
        </div>
        <div class="field">
          <label for="pwd-confirm">再次输入新口令</label>
          <input
            id="pwd-confirm"
            v-model="form.confirmPassword"
            type="password"
            autocomplete="new-password"
          />
          <span v-if="mismatch" class="tiny" style="color: var(--danger)">两次输入的新口令不一致。</span>
        </div>

        <div v-if="error" class="strip danger" style="margin: 12px 0">
          <strong>{{ error }}</strong>
          <span v-if="errorCode" class="mono tiny">（{{ errorCode }}）</span>
        </div>
        <div v-if="note" class="strip ok" style="margin: 12px 0">{{ note }}</div>

        <div class="btn-row">
          <button class="btn" type="submit" :disabled="!canSubmit">
            {{ busy ? '正在修改…' : '修改口令' }}
          </button>
          <span class="tiny faint">修改后，其他设备上的会话会立即失效，需要重新登录。</span>
        </div>
      </form>

      <p class="small dim" style="margin-top: 20px; max-width: 640px">
        平台没有接入邮件或短信，因此<strong>无法自助找回口令</strong>。忘记口令时请联系平台管理员，
        由管理员在「审计 → 账号与角色」中重置为一次性口令并当面告知；登录后请回到本页立即修改。
      </p>
    </div>
  </div>

  <div class="panel" style="margin-top: 16px">
    <div class="panel-head">
      <h2>导出我的数据</h2>
      <span class="tiny faint">JSON，一次下载</span>
    </div>
    <div class="panel-body">
      <p class="small" style="margin-top: 0">
        导出的内容包括：账号基本信息、门户授权、认领申请、门户变更申请、成果提交、各门户下的参与关系、
        最近的站内通知。
      </p>
      <p class="small dim">
        不包含：口令哈希与会话信息、来源采集报文原文、认领材料文件本体、与你无关的审计事件。
        通知最多 500 条，提交/认领/变更各最多 100 条，上限写在文件的 limits 字段里。
      </p>
      <div class="btn-row">
        <button class="btn" :disabled="exportBusy" @click="downloadExport">
          {{ exportBusy ? '正在生成…' : '下载数据文件' }}
        </button>
      </div>
      <div v-if="exportError" class="strip danger" style="margin-top: 12px">{{ exportError }}</div>
      <div v-if="exportNote" class="strip ok" style="margin-top: 12px">{{ exportNote }}</div>
    </div>
  </div>

  <div class="panel" style="margin-top: 16px">
    <div class="panel-head">
      <h2>注销账号</h2>
      <span class="tiny faint">需管理员执行，冷静期内可撤销</span>
    </div>
    <div class="panel-body">
      <p class="small" style="margin-top: 0">
        注销后账号<strong>停止使用</strong>：无法登录、既有会话立即失效。平台<strong>不会删除</strong>
        你已产生的业务数据——你批准的成果、提交记录与审计记录会保留，以这个账号为操作人的历史仍然可查。
      </p>
      <p class="small dim">
        原因不是懒：这些记录是「谁在什么时候做了什么」的证据。删掉之后，其他人对这些成果的质疑就
        再也无法追溯。若你希望某些内容不再公开，请在「我的成果」中逐条处理，或联系管理员。
      </p>

      <div v-if="closure.open" class="strip warn" style="margin: 12px 0">
        <div><strong>已提交注销申请。</strong></div>
        <div class="small" style="margin-top: 4px">
          冷静期结束时间：{{ dateLabel(closure.effectiveAt) }}。在此之前管理员不能执行注销，
          你可以随时撤销。
        </div>
        <div class="btn-row" style="margin-top: 10px">
          <button class="btn small" :disabled="closureBusy" @click="cancelClosure">
            {{ closureBusy ? '处理中…' : '撤销注销申请' }}
          </button>
        </div>
      </div>

      <template v-else>
        <div class="field" style="max-width: 480px">
          <label for="closure-reason">注销理由（必填，仅管理员可见）</label>
          <input id="closure-reason" v-model="closureReason" maxlength="500" />
        </div>
        <div class="field" style="max-width: 480px">
          <label for="closure-confirm">输入「注销」确认</label>
          <input id="closure-confirm" v-model="closureConfirm" maxlength="4" placeholder="注销" />
        </div>
        <div class="btn-row">
          <button class="btn danger" :disabled="!canRequestClosure" @click="requestClosure">
            {{ closureBusy ? '提交中…' : '提交注销申请' }}
          </button>
          <span class="tiny faint">提交后进入 7 天冷静期，期间可撤销</span>
        </div>
      </template>

      <div v-if="closureError" class="strip danger" style="margin-top: 12px">{{ closureError }}</div>
      <div v-if="closureNote" class="strip ok" style="margin-top: 12px">{{ closureNote }}</div>
    </div>
  </div>
</template>
