<script setup lang="ts">
/**
 * 我的成果：本人确认关系、提出异议、撤回异议。
 *
 * 这一页最容易做错的地方是"把不可行的操作也渲染成按钮"。关系状态决定了能做什么：待核验的关系
 * 本人确认不了（证据不足这件事不会因为一句声明消失），已排除的关系本人也恢复不了（推翻裁决属于
 * 治理端）。因此可执行动作一律以服务端返回的 availableActions 为准，前端不再自行推断一套规则——
 * 两处规则一旦分叉，界面会给出"看起来能点、点了报错"的按钮。
 *
 * 另一个刻意选择：异议必须填写理由，页面不给"一键否认"。一条没有依据的异议等于让一个按钮就能把
 * 他人成果从公开关系里拿掉，并且事后无法判断这是误点还是事实主张。
 */
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { accountApi } from '@/api'
import { describeError, useAsync } from '@/composables/useAsync'
import AsyncBlock from '@/components/AsyncBlock.vue'

const portals = useAsync<Record<string, unknown>[]>(() => accountApi.myPortals(), [])
const selected = ref('')
// 加载器读的是调用时刻的 selected，而不是创建时的值：切换门户后重新 run() 就能拿到新的列表。
const rows = useAsync<Record<string, unknown>[]>(() => accountApi.contributions(selected.value), [])

const busy = ref('')
const actionError = ref('')
const actionCode = ref('')
const actionNote = ref('')
const disputeTarget = ref('')
const disputeReason = ref('')

const portalOptions = computed(() => (portals.data.value ?? []).map((row) => String(row.scholarId)))

const STATE_LABEL: Record<string, string> = {
  AUTO: '自动关联',
  SELF_CONFIRMED: '本人已确认',
  PENDING_VERIFY: '待核验',
  DISPUTED: '争议中',
  EXCLUDED: '已排除',
}

function stateClass(state: string) {
  if (state === 'SELF_CONFIRMED') return 'tag ok'
  if (state === 'DISPUTED') return 'tag danger'
  if (state === 'EXCLUDED') return 'tag muted'
  if (state === 'PENDING_VERIFY') return 'tag warn'
  return 'tag'
}

async function loadPortals() {
  await portals.run()
  const first = portalOptions.value[0]
  if (!selected.value && first) {
    selected.value = first
  }
  if (selected.value) {
    await loadRows()
  }
}

async function loadRows() {
  if (!selected.value) return
  await rows.run()
}

function resetActionState() {
  actionError.value = ''
  actionCode.value = ''
  actionNote.value = ''
}

async function act(kind: 'confirm' | 'dispute' | 'withdraw', contributionId: string) {
  resetActionState()
  busy.value = `${kind}:${contributionId}`
  try {
    if (kind === 'confirm') {
      const result = await accountApi.confirmContribution(contributionId)
      actionNote.value = String(result.note ?? '已提交')
    } else if (kind === 'dispute') {
      const reason = disputeReason.value.trim()
      if (reason.length < 4) {
        actionError.value = '请说明提出异议的理由（至少 4 个字），治理端需要据此裁决。'
        return
      }
      const result = await accountApi.disputeContribution(contributionId, reason)
      actionNote.value = String(result.note ?? '已提出异议')
      disputeTarget.value = ''
      disputeReason.value = ''
    } else {
      const result = await accountApi.withdrawDispute(contributionId)
      actionNote.value = String(result.note ?? '已撤回异议')
    }
    // 状态只以服务端返回为准：提交成功后重新拉取，不做乐观更新。
    await loadRows()
  } catch (cause) {
    const described = describeError(cause)
    actionError.value = described.message
    actionCode.value = described.code
  } finally {
    busy.value = ''
  }
}

function actionsOf(row: Record<string, unknown>): string[] {
  const value = row.availableActions
  return Array.isArray(value) ? value.map((item) => String(item)) : []
}

/**
 * 导出「我的成果」。
 *
 * 导出的行数不受列表分页限制，因此按钮旁边要说明这一点：用户会拿这份文件去做考核材料，
 * 「界面上只有 50 条」和「导出有 137 条」之间的落差必须事先说清，而不是等他自己数出来。
 */
const exportBusy = ref(false)
const exportError = ref('')

async function exportCsv() {
  if (!selected.value || exportBusy.value) return
  exportBusy.value = true
  exportError.value = ''
  try {
    const envelope = await accountApi.exportContributionsCsv(selected.value)
    const url = URL.createObjectURL(envelope.data)
    const link = document.createElement('a')
    link.href = url
    link.download = `my-contributions-${selected.value}-${new Date().toISOString().slice(0, 10)}.csv`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } catch (cause) {
    exportError.value = describeError(cause).message
  } finally {
    exportBusy.value = false
  }
}

onMounted(loadPortals)
</script>

<template>
  <div>
    <div style="display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap">
      <h1>我的成果</h1>
      <button class="btn small" @click="loadPortals">刷新</button>
    </div>

    <div class="notice info">
      本人确认与提出异议都只改变关系的确认状态，不删除关系、不覆盖来源记录的原始署名。
      争议状态在治理端裁决前不计入「确定合作」，也不会用于生成合作网络边。
    </div>

    <div v-if="actionError" class="notice danger">
      <div>{{ actionError }}</div>
      <div v-if="actionCode" class="tiny mono" style="margin-top: 6px">错误码 {{ actionCode }}</div>
    </div>
    <div v-if="actionNote" class="notice ok">{{ actionNote }}</div>

    <div class="panel">
      <div class="panel-head">
        <h2>选择门户</h2>
        <span class="tiny faint">只有持有生效授权的学者门户才能在此操作</span>
      </div>
      <div class="panel-body">
        <AsyncBlock
          :loading="portals.loading.value"
          :error="portals.error.value"
          :error-code="portals.errorCode.value"
          :empty="portalOptions.length === 0"
          empty-text="当前账号还没有生效的学者门户。请先在「我的门户」提交认领并通过审核。"
        >
          <div class="field" style="max-width: 420px">
            <label for="portal-pick">学者标识</label>
            <select id="portal-pick" v-model="selected" @change="loadRows">
              <option v-for="id in portalOptions" :key="id" :value="id">{{ id }}</option>
            </select>
          </div>
        </AsyncBlock>
      </div>
    </div>

    <div class="panel" v-if="selected">
      <div class="panel-head">
        <h2>参与关系</h2>
        <span class="tiny faint">状态由服务端判定，可执行动作随记录一起返回</span>
        <button
          v-if="selected"
          class="btn small"
          :disabled="exportBusy"
          @click="exportCsv"
        >
          {{ exportBusy ? '导出中…' : '导出 CSV' }}
        </button>
      </div>
      <div v-if="exportError" class="notice warn" style="margin: 10px 12px 0">
        {{ exportError }}
      </div>
      <p v-if="selected" class="tiny faint" style="margin: 10px 12px 0">
        导出不分页，包含该门户下全部参与关系（含待核验与争议行，后者在「计入合作网络」列标为「否」）。
        列里同时给出数据性质与生效状态：那是判断这行能不能写进对外材料的依据。
      </p>
      <div class="panel-body tight">
        <AsyncBlock
          :loading="rows.loading.value"
          :error="rows.error.value"
          :error-code="rows.errorCode.value"
          :empty="(rows.data.value ?? []).length === 0"
          empty-text="该学者名下暂时没有参与关系。"
        >
          <table class="data">
            <thead>
              <tr>
                <th>成果</th>
                <th>角色</th>
                <th>关系状态</th>
                <th>说明</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows.data.value ?? []" :key="String(row.contributionId)">
                <td>
                  <RouterLink :to="`/achievements/${String(row.achievementId)}`">
                    {{ String(row.title ?? row.achievementId) }}
                  </RouterLink>
                  <div class="tiny faint">
                    {{ String(row.achievementTypeLabel ?? row.achievementType ?? '') }}
                    · {{ String(row.publicationYear ?? '年份未知') }}
                  </div>
                </td>
                <td class="tiny">{{ String(row.role ?? '') }}</td>
                <td>
                  <span :class="stateClass(String(row.relationState))">
                    {{ STATE_LABEL[String(row.relationState)] ?? String(row.relationState) }}
                  </span>
                  <div class="tiny faint" v-if="row.qualified === false">不计入确定关系</div>
                </td>
                <td class="tiny faint" style="max-width: 320px">{{ String(row.stateNote ?? '') }}</td>
                <td>
                  <div style="display: flex; gap: 6px; flex-wrap: wrap">
                    <button
                      v-if="actionsOf(row).includes('CONFIRM')"
                      class="btn small primary"
                      :disabled="busy !== ''"
                      @click="act('confirm', String(row.contributionId))"
                    >
                      确认是我的成果
                    </button>
                    <button
                      v-if="actionsOf(row).includes('DISPUTE')"
                      class="btn small"
                      :disabled="busy !== ''"
                      @click="disputeTarget = disputeTarget === String(row.contributionId) ? '' : String(row.contributionId)"
                    >
                      提出异议
                    </button>
                    <button
                      v-if="actionsOf(row).includes('WITHDRAW_DISPUTE')"
                      class="btn small"
                      :disabled="busy !== ''"
                      @click="act('withdraw', String(row.contributionId))"
                    >
                      撤回异议
                    </button>
                    <span v-if="actionsOf(row).length === 0" class="tiny faint">
                      该状态本人无可执行操作
                    </span>
                  </div>
                  <div v-if="disputeTarget === String(row.contributionId)" style="margin-top: 8px">
                    <textarea
                      v-model="disputeReason"
                      placeholder="说明异议依据：署名不是我、单位不符、成果被错误归属等，治理端会据此核对来源记录。"
                    ></textarea>
                    <button
                      class="btn small danger"
                      style="margin-top: 6px"
                      :disabled="busy !== ''"
                      @click="act('dispute', String(row.contributionId))"
                    >
                      提交异议
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </AsyncBlock>
      </div>
    </div>
  </div>
</template>
