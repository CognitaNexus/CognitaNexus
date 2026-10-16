<script setup lang="ts">
/**
 * 治理与去重。
 *
 * 这一页处理的是平台上最不可逆的动作——合并实体。因此在交互上刻意设置了三重阻力：
 *
 *  1. 候选只展示证据，不给出「系统认为这两个是同一条」的建议。相似标题、同名学者都只是候选，
 *     判定必须由人做出，并且必须写明依据。
 *  2. 合并要显式指定幸存实体，界面不会默认选中某一边。默认值会让人顺着默认点下去，
 *     而选错幸存者意味着合并后要再合并回来。
 *  3. 台账按实体查询而不是全表浏览。合并的后果要靠「这条实体经历过什么」来理解，
 *     脱离实体看一串 MERGE 记录说明不了任何问题。
 */
import { onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { adminApi } from '@/api'
import { describeError } from '@/composables/useAsync'
import AsyncBlock from '@/components/AsyncBlock.vue'

type Row = Record<string, unknown>

function section() {
  return reactive({ items: [] as Row[], loading: false, error: '', errorCode: '' })
}

const candidates = section()
const anomalies = section()
const ledger = section()

/** 每个候选各自的处置选择，避免一次只处理一条时反复重填。 */
const candidateChoice = reactive<Record<string, { action: string; survivorId: string; reason: string }>>({})

const mergeForm = reactive({ entityType: 'achievement', survivorId: '', mergedId: '', reason: '' })
const ledgerQuery = ref('')

const busy = ref('')
const actionError = ref('')
const actionCode = ref('')
const actionNote = ref('')

async function loadSection(target: ReturnType<typeof section>, loader: () => Promise<Row[]>) {
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

function loadCandidates() {
  return loadSection(candidates, () => adminApi.governanceCandidates({ page: 1, size: 50 }))
}

function loadAnomalies() {
  return loadSection(anomalies, () => adminApi.anomalies({ page: 1, size: 50 }))
}

function loadAll() {
  return Promise.all([loadCandidates(), loadAnomalies()])
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

function choiceOf(id: string) {
  if (!candidateChoice[id]) {
    candidateChoice[id] = { action: 'REJECT', survivorId: '', reason: '' }
  }
  return candidateChoice[id]
}

function resolveCandidate(id: string) {
  const choice = choiceOf(id)
  if (!choice.reason.trim()) {
    actionError.value = '请先填写判定依据。合并会改变公开视图，依据必须可追溯。'
    return
  }
  if (choice.action === 'MERGE' && !choice.survivorId) {
    actionError.value = '合并必须显式指定幸存实体，请选择保留哪一边。'
    return
  }
  run(
    `candidate:${id}`,
    () =>
      adminApi.resolveCandidate(id, {
        action: choice.action,
        survivorId: choice.action === 'MERGE' ? choice.survivorId : undefined,
        reason: choice.reason.trim(),
      }),
    choice.action === 'MERGE' ? '已合并。被合并方保留在台账中，可查回合并前的快照。' : '已判定为不同实体，候选关闭。',
    loadCandidates,
  )
}

function resolveAnomaly(id: string) {
  run(`anomaly:${id}`, () => adminApi.resolveAnomaly(id), '异常已标记为处理完毕。', loadAnomalies)
}

function submitMerge() {
  if (!mergeForm.survivorId.trim() || !mergeForm.mergedId.trim() || !mergeForm.reason.trim()) {
    actionError.value = '幸存实体、被合并实体与依据都不能为空。'
    return
  }
  if (mergeForm.survivorId.trim() === mergeForm.mergedId.trim()) {
    actionError.value = '幸存实体与被合并实体不能是同一个。'
    return
  }
  const isAchievement = mergeForm.entityType === 'achievement'
  run(
    'merge',
    () =>
      isAchievement
        ? adminApi.mergeAchievements({
            survivorId: mergeForm.survivorId.trim(),
            mergedId: mergeForm.mergedId.trim(),
            reason: mergeForm.reason.trim(),
          })
        : adminApi.mergeScholars({
            survivorId: mergeForm.survivorId.trim(),
            mergedId: mergeForm.mergedId.trim(),
            reason: mergeForm.reason.trim(),
          }),
    isAchievement ? '成果合并完成。' : '学者合并完成。注意：同名不等于同一人，合并前请确认标识可信。',
    loadAll,
  )
}

function loadLedger() {
  const entityId = ledgerQuery.value.trim()
  if (!entityId) {
    actionError.value = '请输入要查询台账的实体标识。'
    return
  }
  loadSection(ledger, () => adminApi.ledger(entityId, 100))
}

onMounted(loadAll)
</script>

<template>
  <div>
    <div style="display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap">
      <h1>治理与去重</h1>
      <button class="btn small" @click="loadAll">刷新</button>
    </div>

    <div class="notice warn">
      <strong>相似不等于同一条，同名不等于同一人。</strong>
      本页的候选来自规则匹配，只表示「值得人看一眼」。任何自动流程都不会直接合并实体；
      这里每一次合并都会写入台账并触发投影重建。
    </div>

    <div v-if="actionError" class="notice danger">
      <div>{{ actionError }}</div>
      <div v-if="actionCode" class="tiny mono" style="margin-top: 6px">错误码 {{ actionCode }}</div>
    </div>
    <div v-if="actionNote" class="notice info">{{ actionNote }}</div>

    <div class="panel">
      <div class="panel-head">
        <h2>重复候选</h2>
        <span class="tiny faint">候选只提供证据，判定由人做出</span>
      </div>
      <div class="panel-body tight">
        <AsyncBlock
          :loading="candidates.loading"
          :error="candidates.error"
          :error-code="candidates.errorCode"
          :empty="candidates.items.length === 0"
          empty-text="当前没有待判定的重复候选。"
        >
          <div v-for="row in candidates.items" :key="String(row.id)" class="result-item">
            <div class="result-meta">
              <span class="badge neutral">{{ String(row.achievementType ?? '') }}</span>
              <span :class="row.status === 'OPEN' ? 'badge warn' : 'badge neutral'">
                {{ String(row.status ?? '') }}
              </span>
              <span class="mono tiny">{{ String(row.id) }}</span>
              <span v-if="row.createdAt" class="mono tiny">{{ String(row.createdAt) }}</span>
            </div>

            <div class="grid cols-2" style="margin: 10px 0">
              <div class="panel" style="margin: 0; box-shadow: none">
                <div class="panel-body tight">
                  <div class="tiny dim">候选 A</div>
                  <RouterLink class="mono tiny" :to="`/achievements/${String(row.leftAchievementId)}`">
                    {{ String(row.leftAchievementId) }}
                  </RouterLink>
                  <div style="margin-top: 8px">
                    <label class="tiny">
                      <input
                        type="radio"
                        :name="`survivor-${String(row.id)}`"
                        :value="String(row.leftAchievementId)"
                        :checked="choiceOf(String(row.id)).survivorId === String(row.leftAchievementId)"
                        @change="choiceOf(String(row.id)).survivorId = String(row.leftAchievementId)"
                      />
                      保留为幸存实体
                    </label>
                  </div>
                </div>
              </div>
              <div class="panel" style="margin: 0; box-shadow: none">
                <div class="panel-body tight">
                  <div class="tiny dim">候选 B</div>
                  <RouterLink class="mono tiny" :to="`/achievements/${String(row.rightAchievementId)}`">
                    {{ String(row.rightAchievementId) }}
                  </RouterLink>
                  <div style="margin-top: 8px">
                    <label class="tiny">
                      <input
                        type="radio"
                        :name="`survivor-${String(row.id)}`"
                        :value="String(row.rightAchievementId)"
                        :checked="choiceOf(String(row.id)).survivorId === String(row.rightAchievementId)"
                        @change="choiceOf(String(row.id)).survivorId = String(row.rightAchievementId)"
                      />
                      保留为幸存实体
                    </label>
                  </div>
                </div>
              </div>
            </div>

            <div v-if="row.evidence" class="pre" style="margin-bottom: 10px">
              {{ JSON.stringify(row.evidence, null, 2) }}
            </div>

            <div class="grid cols-2">
              <div class="field" style="margin: 0">
                <label :for="`cand-action-${String(row.id)}`">处置</label>
                <select :id="`cand-action-${String(row.id)}`" v-model="choiceOf(String(row.id)).action">
                  <option value="REJECT">判定为不同实体</option>
                  <option value="MERGE">合并为一条</option>
                </select>
              </div>
              <div class="field" style="margin: 0">
                <label :for="`cand-reason-${String(row.id)}`">判定依据（必填）</label>
                <input
                  :id="`cand-reason-${String(row.id)}`"
                  v-model="choiceOf(String(row.id)).reason"
                  type="text"
                  placeholder="例如：DOI 完全一致，确认为同一篇"
                />
              </div>
            </div>

            <div class="btn-row" style="margin-top: 10px">
              <button
                class="btn primary small"
                :disabled="busy === `candidate:${String(row.id)}`"
                @click="resolveCandidate(String(row.id))"
              >
                提交处置
              </button>
            </div>
          </div>
        </AsyncBlock>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h2>治理异常</h2>
        <span class="tiny faint">例如公开但缺少类型扩展、字段冲突等</span>
      </div>
      <div class="panel-body tight">
        <AsyncBlock
          :loading="anomalies.loading"
          :error="anomalies.error"
          :error-code="anomalies.errorCode"
          :empty="anomalies.items.length === 0"
          empty-text="当前没有未处理的治理异常。"
        >
          <table class="data">
            <thead>
              <tr>
                <th>类型</th>
                <th>相关实体</th>
                <th>详情</th>
                <th>状态</th>
                <th>发现时间</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in anomalies.items" :key="String(row.id)">
                <td><span class="badge warn">{{ String(row.kind ?? '') }}</span></td>
                <td class="mono tiny">{{ String(row.entityId ?? row.sourceRecordId ?? '—') }}</td>
                <td class="tiny">{{ String(row.detail ?? '') }}</td>
                <td class="tiny">{{ String(row.status ?? '') }}</td>
                <td class="mono tiny">{{ String(row.createdAt ?? '') }}</td>
                <td>
                  <button
                    class="btn small"
                    :disabled="busy === `anomaly:${String(row.id)}`"
                    @click="resolveAnomaly(String(row.id))"
                  >
                    标记已处理
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </AsyncBlock>
      </div>
    </div>

    <div class="grid cols-2">
      <div class="panel">
        <div class="panel-head"><h2>手动合并</h2></div>
        <div class="panel-body">
          <div class="field">
            <label for="merge-type">实体类型</label>
            <select id="merge-type" v-model="mergeForm.entityType">
              <option value="achievement">成果</option>
              <option value="scholar">学者</option>
            </select>
          </div>
          <div class="field">
            <label for="merge-survivor">幸存实体标识（保留）</label>
            <input id="merge-survivor" v-model="mergeForm.survivorId" type="text" />
          </div>
          <div class="field">
            <label for="merge-merged">被合并实体标识（并入上方）</label>
            <input id="merge-merged" v-model="mergeForm.mergedId" type="text" />
          </div>
          <div class="field">
            <label for="merge-reason">合并依据（必填）</label>
            <textarea id="merge-reason" v-model="mergeForm.reason"></textarea>
          </div>
          <button class="btn primary" :disabled="busy === 'merge'" @click="submitMerge">执行合并</button>
          <p class="tiny faint" style="margin: 10px 0 0">
            合并不可直接撤销。被合并实体会保留标识与合并前快照，若判定有误需要在台账中查回原始状态并另行处理。
          </p>
        </div>
      </div>

      <div class="panel">
        <div class="panel-head">
          <h2>治理台账</h2>
          <span class="tiny faint">按实体查询</span>
        </div>
        <div class="panel-body">
          <div class="btn-row" style="margin-bottom: 12px">
            <input
              v-model="ledgerQuery"
              type="text"
              placeholder="输入实体标识"
              style="flex: 1; min-width: 200px"
            />
            <button class="btn" @click="loadLedger">查询</button>
          </div>

          <AsyncBlock
            :loading="ledger.loading"
            :error="ledger.error"
            :error-code="ledger.errorCode"
            :empty="ledger.items.length === 0"
            empty-text="输入实体标识后可查看该实体经历过的合并与修订记录。"
          >
            <div v-for="row in ledger.items" :key="String(row.id)" class="result-item" style="padding: 10px 0">
              <div class="result-meta">
                <span class="badge neutral">{{ String(row.operation ?? '') }}</span>
                <span class="tiny">{{ String(row.entityType ?? '') }}</span>
                <span class="mono tiny">{{ String(row.createdAt ?? '') }}</span>
              </div>
              <div class="small" style="margin-top: 6px">
                幸存 {{ String(row.survivorId ?? '—') }} ← 并入 {{ String(row.mergedId ?? '—') }}
              </div>
              <div class="tiny dim">{{ String(row.reason ?? '') }}</div>
            </div>
          </AsyncBlock>
        </div>
      </div>
    </div>
  </div>
</template>
