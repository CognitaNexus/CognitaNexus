<script setup lang="ts">
/**
 * 我的门户：认领申请、档案修订申请与已生效的门户授权。
 *
 * 三条业务红线在这一页上最容易被绕过，所以每条都在界面上显式写出来：
 *
 *  1. 提交修订申请不等于信息已被改写——申请要经审核才生效，页面把「申请值」与「当前值」并列展示，
 *     不把用户填的值直接当成新事实显示。
 *  2. 审核状态只以服务端返回为准：本地提交成功后只重新拉取列表，不乐观更新状态。
 *  3. 证据材料只在服务端鉴权后可读，页面只展示元信息与受保护下载入口，不提供任何直链。
 *
 * 认领从学者档案发起（`/portal?scholarId=…`），内部标识仍可作为辅助信息填写，但不是普通用户的
 * 唯一入口：要求用户自己去找一串内部 ID 才能认领自己的门户，等于把「这个门户是谁的」交给复制粘贴。
 */
import { onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { accountApi, publicApi } from '@/api'
import type { ClaimRequest } from '@/api/types'
import { describeError, useAsync } from '@/composables/useAsync'
import AsyncBlock from '@/components/AsyncBlock.vue'

/** 后端 PortalChangeRequest.Field 的取值；这里硬编码字面量而不从接口拉取，
 *  原因是接口列表只返回中文说明，字段名本身是契约的一部分，写死能让不一致在类型检查时暴露。 */
const FIELD_OPTIONS: { value: string; label: string; requiresReview: boolean }[] = [
  { value: 'RESEARCH_SUMMARY', label: '研究简介', requiresReview: true },
  { value: 'ORGANIZATION_NAME', label: '所属机构', requiresReview: true },
  { value: 'DISPLAY_ORDER', label: '署名顺序', requiresReview: false },
  { value: 'TOPIC_SET', label: '领域标注', requiresReview: false },
]

const route = useRoute()

const portals = useAsync<Record<string, unknown>[]>(() => accountApi.myPortals(), [])
const claims = useAsync<ClaimRequest[]>(() => accountApi.myClaims(), [])
const changes = useAsync<Record<string, unknown>[]>(() => accountApi.myChanges(), [])

/** 从学者检索页带过来的目标学者：只认学者标识，姓名与机构从公开档案读，读不到就说读不到。 */
const broughtScholarId = typeof route.query.scholarId === 'string' ? route.query.scholarId : ''
const broughtScholar = ref<Record<string, unknown> | null>(null)
const broughtScholarError = ref('')

const claimForm = ref({ scholarId: broughtScholarId, statement: '' })
const changeForm = ref({ scholarId: '', fieldName: 'RESEARCH_SUMMARY', proposedValue: '' })
const busy = ref('')
const actionError = ref('')
const actionCode = ref('')
const actionNote = ref('')

/** 认领材料按申请分组展示，避免用户在多个申请之间传错文件。 */
const uploadTarget = ref('')
const uploadFile = ref<File | null>(null)

/** 当前展开的申请：材料清单与补证／重提都在这一行内完成，不跳到另一个页面。 */
const expandedClaim = ref('')
const resubmitStatement = ref('')

function loadAll() {
  return Promise.all([portals.run(), claims.run(), changes.run()])
}

function resetActionState() {
  actionError.value = ''
  actionCode.value = ''
  actionNote.value = ''
}

async function loadBroughtScholar() {
  if (!broughtScholarId) return
  try {
    broughtScholar.value = await publicApi.scholar(broughtScholarId)
  } catch {
    // 读不到不等于不能申请：可能是尚未公开或标识有误。如实说明，不编造姓名与机构。
    broughtScholarError.value =
      '未能读取该学者档案（可能标识有误，或该档案当前不可公开）。仍可提交申请，审核会按标识核对。'
  }
}

function toggleExpand(row: ClaimRequest) {
  if (expandedClaim.value === row.id) {
    expandedClaim.value = ''
    return
  }
  expandedClaim.value = row.id
  uploadTarget.value = row.id
  resubmitStatement.value = row.statement ?? ''
}

function formatSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '大小未知'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MiB`
}

async function submitResubmit(claimId: string) {
  resetActionState()
  if (!resubmitStatement.value.trim()) {
    actionError.value = '重新提交时必须填写说明（至少要说明补充了什么）。'
    return
  }
  busy.value = 'resubmit'
  try {
    await accountApi.resubmitClaim(claimId, resubmitStatement.value.trim())
    actionNote.value =
      '已重新提交，申请回到待审核状态。是否通过由审核决定，本页不预判结果。'
    await claims.run()
  } catch (cause) {
    const described = describeError(cause)
    actionError.value = described.message
    actionCode.value = described.code
  } finally {
    busy.value = ''
  }
}

async function submitClaim() {
  resetActionState()
  if (!claimForm.value.scholarId || !claimForm.value.statement) {
    actionError.value = '学者标识与说明都不能为空。'
    return
  }
  busy.value = 'claim'
  try {
    await accountApi.submitClaim({
      scholarId: claimForm.value.scholarId.trim(),
      statement: claimForm.value.statement.trim(),
    })
    actionNote.value = '认领申请已提交，状态为待审核。审核结果以服务端为准，本页不预判。'
    claimForm.value.statement = ''
    await Promise.all([claims.run(), portals.run()])
  } catch (cause) {
    const described = describeError(cause)
    actionError.value = described.message
    actionCode.value = described.code
  } finally {
    busy.value = ''
  }
}

async function submitEvidence() {
  resetActionState()
  if (!uploadTarget.value || !uploadFile.value) {
    actionError.value = '请选择要上传的材料文件。'
    return
  }
  busy.value = 'evidence'
  try {
    const result = await accountApi.uploadEvidence(uploadTarget.value, uploadFile.value)
    actionNote.value = `材料已上传（${String(result.sizeBytes ?? '?')} 字节，指纹 ${
      String(result.contentHash ?? '').slice(0, 16)
    }…）。响应不含文件路径，仅审核者可经鉴权下载。`
    uploadFile.value = null
    await claims.run()
  } catch (cause) {
    const described = describeError(cause)
    actionError.value = described.message
    actionCode.value = described.code
  } finally {
    busy.value = ''
  }
}

async function submitChange() {
  resetActionState()
  if (!changeForm.value.scholarId || !changeForm.value.proposedValue) {
    actionError.value = '学者标识与申请值都不能为空。'
    return
  }
  busy.value = 'change'
  try {
    await accountApi.submitChange({
      scholarId: changeForm.value.scholarId.trim(),
      fieldName: changeForm.value.fieldName,
      proposedValue: changeForm.value.proposedValue.trim(),
    })
    actionNote.value = '修订申请已提交。注意：这只是申请，字段的当前值不会立即改变。'
    changeForm.value.proposedValue = ''
    await changes.run()
  } catch (cause) {
    const described = describeError(cause)
    actionError.value = described.message
    actionCode.value = described.code
  } finally {
    busy.value = ''
  }
}

function statusBadge(status: unknown) {
  const value = String(status ?? '')
  if (value === 'PENDING_REVIEW' || value === 'PENDING') return 'badge warn'
  if (value === 'APPROVED' || value === 'ACCEPTED') return 'badge ok'
  if (value === 'REJECTED' || value === 'DECLINED') return 'badge danger'
  return 'badge neutral'
}

onMounted(() => {
  loadAll()
  loadBroughtScholar()
})
</script>

<template>
  <div>
    <div style="display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap">
      <h1>我的门户</h1>
      <button class="btn small" @click="loadAll">刷新</button>
    </div>

    <div v-if="actionError" class="notice danger">
      <div>{{ actionError }}</div>
      <div v-if="actionCode" class="tiny mono" style="margin-top: 6px">错误码 {{ actionCode }}</div>
    </div>
    <div v-if="actionNote" class="notice info">{{ actionNote }}</div>

    <div class="panel">
      <div class="panel-head">
        <h2>已生效的门户授权</h2>
        <span class="tiny faint">只有审核通过的认领才会出现在这里</span>
      </div>
      <div class="panel-body tight">
        <AsyncBlock
          :loading="portals.loading.value"
          :error="portals.error.value"
          :error-code="portals.errorCode.value"
          :empty="(portals.data.value ?? []).length === 0"
          empty-text="当前账号还没有生效的学者门户。认领申请通过审核后会在这里出现。"
        >
          <table class="data">
            <thead>
              <tr>
                <th>学者</th>
                <th>授权时间</th>
                <th>来源认领</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in portals.data.value ?? []" :key="String(row.authorityId)">
                <td>
                  <RouterLink :to="`/scholars/${String(row.scholarId)}`">
                    {{ String(row.scholarId) }}
                  </RouterLink>
                </td>
                <td class="mono tiny">{{ String(row.grantedAt ?? '') }}</td>
                <td class="mono tiny">{{ String(row.claimRequestId ?? '—') }}</td>
              </tr>
            </tbody>
          </table>
        </AsyncBlock>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h2>认领申请</h2>
        <span class="tiny faint">同名不代表同一人，审核会逐个核对</span>
      </div>
      <div class="panel-body">
        <div class="grid cols-2">
          <form @submit.prevent="submitClaim">
            <h3>提交新认领</h3>

            <div v-if="broughtScholarId" class="notice info tiny" style="margin-bottom: 12px">
              <div>
                本次申请的目标学者：
                <strong v-if="broughtScholar">{{ String(broughtScholar.displayName ?? '') }}</strong>
                <span v-if="broughtScholar?.organizationName" class="faint">
                  （{{ String(broughtScholar.organizationName) }}）
                </span>
                <span v-else-if="!broughtScholar" class="faint">正在读取档案…</span>
              </div>
              <div class="mono faint" style="margin-top: 4px">{{ broughtScholarId }}</div>
              <div v-if="broughtScholarError" class="tiny" style="margin-top: 6px">
                {{ broughtScholarError }}
              </div>
            </div>

            <div class="field">
              <label for="claim-scholar">学者标识</label>
              <input id="claim-scholar" v-model="claimForm.scholarId" type="text" placeholder="scholarId" />
              <span class="tiny faint">
                通常不需要手动填写：在
                <RouterLink to="/search?scope=scholars">学者检索</RouterLink>
                里找到本人档案，点「申请管理该门户」即可带入。标识只作为辅助信息。
              </span>
            </div>
            <div class="field">
              <label for="claim-statement">申请说明</label>
              <textarea
                id="claim-statement"
                v-model="claimForm.statement"
                placeholder="说明你与该学者的关系，例如任职机构、邮箱所属域名、个人主页等可核验信息。"
              ></textarea>
            </div>
            <button class="btn primary" type="submit" :disabled="busy === 'claim'">
              {{ busy === 'claim' ? '正在提交…' : '提交认领' }}
            </button>
          </form>

          <div class="notice" style="align-self: start">
            <h3 style="margin-top: 0">上传证明材料</h3>
            <p class="small dim" style="margin-bottom: 0">
              材料按申请上传：在下方「认领申请」表里展开要补证的那一份，就能看到它已有的材料并追加新文件。
              只接受 PDF / PNG / JPEG，单文件不超过 10 MiB。材料经核心服务鉴权后代理下载，
              不通过静态文件目录暴露；申请人本人与授权审核者可读，其他人拿不到材料，也拿不到材料的标识。
            </p>
          </div>
        </div>

        <div class="scroll-x" style="margin-top: 16px">
          <AsyncBlock
            :loading="claims.loading.value"
            :error="claims.error.value"
            :error-code="claims.errorCode.value"
            :empty="(claims.data.value ?? []).length === 0"
            empty-text="还没有提交过认领申请。"
          >
            <table class="data">
              <thead>
                <tr>
                  <th>申请</th>
                  <th>学者</th>
                  <th>状态</th>
                  <th>提交时间</th>
                  <th>材料</th>
                  <th>审核意见</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <template v-for="row in claims.data.value ?? []" :key="String(row.id)">
                  <tr>
                    <td class="mono tiny">{{ String(row.id) }}</td>
                    <td class="mono tiny">{{ String(row.scholarId) }}</td>
                    <td><span :class="statusBadge(row.status)">{{ String(row.status ?? '未知') }}</span></td>
                    <td class="mono tiny">{{ String(row.submittedAt ?? '') }}</td>
                    <td class="tiny">
                      {{ (row.evidence ?? []).length }} 份
                      <span v-if="(row.evidence ?? []).length === 0" class="faint">（未上传）</span>
                    </td>
                    <td class="tiny">{{ String(row.decisionReason ?? '—') }}</td>
                    <td>
                      <button class="btn small" @click="toggleExpand(row)">
                        {{ expandedClaim === row.id ? '收起' : '材料与补证' }}
                      </button>
                    </td>
                  </tr>
                  <tr v-if="expandedClaim === row.id">
                    <td colspan="7">
                      <div class="tiny dim" style="margin-bottom: 8px">
                        材料本体不随列表返回，只经核心服务鉴权代理下载；申请人本人与授权审核者可读。
                      </div>

                      <div v-if="(row.evidence ?? []).length === 0" class="tiny faint">
                        这份申请还没有材料。可在下方选择文件上传。
                      </div>
                      <div v-else class="tiny" style="margin-bottom: 10px">
                        <div
                          v-for="item in row.evidence ?? []"
                          :key="item.id"
                          style="margin-bottom: 4px"
                        >
                          <a :href="`/core${item.downloadUrl}`">下载</a>
                          · {{ item.contentType }} · {{ formatSize(item.sizeBytes) }}
                          · <span class="mono faint">{{ item.createdAt }}</span>
                          · 指纹 <span class="mono faint">{{ item.contentHash.slice(0, 16) }}…</span>
                        </div>
                      </div>

                      <div v-if="row.canAttachEvidence" class="field" style="max-width: 420px">
                        <label :for="`evidence-${row.id}`">追加材料（PDF / PNG / JPEG，≤ 10 MiB）</label>
                        <input
                          :id="`evidence-${row.id}`"
                          type="file"
                          accept="application/pdf,image/png,image/jpeg"
                          @change="
                            (e) => {
                              uploadTarget = String(row.id)
                              uploadFile = (e.target as HTMLInputElement).files?.[0] ?? null
                            }
                          "
                        />
                        <button
                          class="btn small"
                          style="margin-top: 8px"
                          :disabled="busy === 'evidence'"
                          @click="submitEvidence"
                        >
                          {{ busy === 'evidence' ? '正在上传…' : '上传到这份申请' }}
                        </button>
                      </div>

                      <div v-if="row.canResubmit" style="margin-top: 12px; max-width: 620px">
                        <div class="field">
                          <label :for="`resubmit-${row.id}`">补证说明（重新提交时写入）</label>
                          <textarea
                            :id="`resubmit-${row.id}`"
                            v-model="resubmitStatement"
                            placeholder="说明补充了什么材料、对应审核意见中的哪一条。"
                          ></textarea>
                        </div>
                        <button
                          class="btn primary small"
                          :disabled="busy === 'resubmit'"
                          @click="submitResubmit(String(row.id))"
                        >
                          {{ busy === 'resubmit' ? '正在重新提交…' : '重新提交这份申请' }}
                        </button>
                      </div>

                      <div v-else class="tiny faint" style="margin-top: 10px">
                        该申请已不在补证阶段，不能追加材料或重新提交。要补充新的身份证明，请重新发起一次认领申请。
                      </div>
                    </td>
                  </tr>
                </template>
              </tbody>
            </table>
          </AsyncBlock>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h2>档案修订申请</h2>
        <span class="tiny faint">申请值不会立即生效</span>
      </div>
      <div class="panel-body">
        <div class="grid cols-2">
          <form @submit.prevent="submitChange">
            <h3>提交修订申请</h3>
            <div class="field">
              <label for="change-scholar">学者标识</label>
              <input id="change-scholar" v-model="changeForm.scholarId" type="text" placeholder="scholarId" />
            </div>
            <div class="field">
              <label for="change-field">字段</label>
              <select id="change-field" v-model="changeForm.fieldName">
                <option v-for="option in FIELD_OPTIONS" :key="option.value" :value="option.value">
                  {{ option.label }}{{ option.requiresReview ? '（需审核）' : '（仍需记录留痕）' }}
                </option>
              </select>
            </div>
            <div class="field">
              <label for="change-value">申请值</label>
              <textarea id="change-value" v-model="changeForm.proposedValue"></textarea>
            </div>
            <button class="btn primary" type="submit" :disabled="busy === 'change'">
              {{ busy === 'change' ? '正在提交…' : '提交修订申请' }}
            </button>
          </form>

          <div class="notice warn" style="align-self: start">
            <strong>来源原值不可被人工修订覆盖。</strong>
            无论申请是否通过，来源采集到的原始值都会保留在溯源记录中。门户修订只影响平台的展示口径，
            并且每一次变更都会写入治理台账，可以随时查回「谁在什么时候把哪个字段从什么改成了什么」。
          </div>
        </div>

        <div class="scroll-x" style="margin-top: 16px">
          <AsyncBlock
            :loading="changes.loading.value"
            :error="changes.error.value"
            :error-code="changes.errorCode.value"
            :empty="(changes.data.value ?? []).length === 0"
            empty-text="还没有提交过修订申请。"
          >
            <table class="data">
              <thead>
                <tr>
                  <th>学者</th>
                  <th>字段</th>
                  <th>当前值</th>
                  <th>申请值</th>
                  <th>状态</th>
                  <th>提交时间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in changes.data.value ?? []" :key="String(row.id)">
                  <td class="mono tiny">{{ String(row.scholarId) }}</td>
                  <td class="tiny">{{ String(row.fieldName) }}</td>
                  <td class="tiny">{{ String(row.currentValue ?? '—') }}</td>
                  <td class="tiny"><strong>{{ String(row.proposedValue ?? '—') }}</strong></td>
                  <td><span :class="statusBadge(row.status)">{{ String(row.status ?? '未知') }}</span></td>
                  <td class="mono tiny">{{ String(row.submittedAt ?? '') }}</td>
                </tr>
              </tbody>
            </table>
          </AsyncBlock>
        </div>
      </div>
    </div>
  </div>
</template>
