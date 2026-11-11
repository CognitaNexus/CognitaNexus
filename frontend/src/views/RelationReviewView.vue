<script setup lang="ts">
/**
 * 关系争议裁决。
 *
 * 队列里只有当前处于争议状态（DISPUTED）的参与关系——它们此刻已经不在「确定合作」里了，因此这一页
 * 要回答的不是"要不要撤下"，而是"这条关系到底成不成立"。三个结论必须区分清楚，页面把各自的后果
 * 写在按钮旁边，避免裁决人凭字面理解：
 *
 *  - EXCLUDE：判定不成立 → 关系变为已排除，永久不计入确定关系（仍保留行与来源记录）。
 *  - CONFIRM：核验后确认 → 这是唯一能把待核验/争议关系升为「本人已确认」的路径。
 *  - RESTORE：撤销此前的争议/排除结论 → 回到自动关联，不等于确认。
 *
 * 裁决必须写理由：这条关系会长期影响公开视图与合作网络，事后必须能回答"当初为什么这么判"。
 */
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { adminApi } from '@/api'
import { describeError, useAsync } from '@/composables/useAsync'
import AsyncBlock from '@/components/AsyncBlock.vue'

const queue = useAsync<Record<string, unknown>[]>(() => adminApi.disputedContributions(50), [])

const busy = ref('')
const actionError = ref('')
const actionCode = ref('')
const actionNote = ref('')

/** 每条关系各自的裁决理由：放在一个以关系标识为键的字典里，避免多条记录共用同一个输入框。 */
const reasons = ref<Record<string, string>>({})

const total = computed(() => (queue.data.value ?? []).length)

const DECISIONS: { value: string; label: string; hint: string }[] = [
  { value: 'EXCLUDE', label: '判定不成立', hint: '关系变为已排除，永不计入确定关系，行与来源记录保留' },
  { value: 'CONFIRM', label: '核验后确认', hint: '关系变为本人已确认，计入确定关系并可建合作边' },
  { value: 'RESTORE', label: '撤销争议结论', hint: '关系回到自动关联；这不是确认，只是取消争议' },
]

function reasonOf(id: string) {
  return reasons.value[id] ?? ''
}

function setReason(id: string, value: string) {
  reasons.value = { ...reasons.value, [id]: value }
}

function resetActionState() {
  actionError.value = ''
  actionCode.value = ''
  actionNote.value = ''
}

async function resolve(contributionId: string, decision: string) {
  resetActionState()
  const reason = reasonOf(contributionId).trim()
  if (!reason) {
    actionError.value = '裁决必须写明理由：这条关系会长期影响公开视图与合作网络。'
    return
  }
  busy.value = `${decision}:${contributionId}`
  try {
    const result = await adminApi.resolveContribution(contributionId, decision, reason)
    actionNote.value = `${String(result.note ?? '已裁决')}（${String(result.previousState ?? '')} → ${String(
      result.relationState ?? '',
    )}）`
    // 裁决结果只以服务端为准：重新拉取队列，被裁决的记录会自然消失。
    await queue.run()
  } catch (cause) {
    const described = describeError(cause)
    actionError.value = described.message
    actionCode.value = described.code
  } finally {
    busy.value = ''
  }
}

onMounted(() => queue.run())
</script>

<template>
  <div>
    <div style="display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap">
      <h1>关系争议裁决</h1>
      <!-- 写成 queue.run() 而不是 queue.run：后者会把点击事件当成参数传进 run()，
           现在 run 接受 { silent } 选项，事件对象进去后既不是对象形状也不 silent，靠的是运气。 -->
      <button class="btn small" @click="queue.run()">刷新</button>
    </div>

    <div class="notice info">
      队列共 {{ total }} 条。争议关系在裁决前已被排除在「确定合作」之外，也不会生成合作网络边；
      本页决定它此后是否成立，不决定是否公开成果本身。
    </div>

    <div v-if="actionError" class="notice danger">
      <div>{{ actionError }}</div>
      <div v-if="actionCode" class="tiny mono" style="margin-top: 6px">错误码 {{ actionCode }}</div>
    </div>
    <div v-if="actionNote" class="notice ok">{{ actionNote }}</div>

    <div class="panel">
      <div class="panel-body tight">
        <AsyncBlock
          :loading="queue.loading.value"
          :error="queue.error.value"
          :error-code="queue.errorCode.value"
          :empty="total === 0"
          empty-text="当前没有待裁决的关系争议。"
        >
          <table class="data">
            <thead>
              <tr>
                <th>成果</th>
                <th>学者</th>
                <th>角色</th>
                <th>裁决</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in queue.data.value ?? []" :key="String(row.contributionId)">
                <td>
                  <RouterLink :to="`/achievements/${String(row.achievementId)}`">
                    {{ String(row.title ?? row.achievementId) }}
                  </RouterLink>
                  <div class="tiny faint">
                    {{ String(row.achievementTypeLabel ?? '') }} · {{ String(row.publicationYear ?? '年份未知') }}
                    · {{ String(row.dataNature ?? '') }}
                  </div>
                </td>
                <td>
                  <RouterLink :to="`/scholars/${String(row.scholarId)}`">
                    {{ String(row.scholarId) }}
                  </RouterLink>
                  <div class="tiny faint">{{ String(row.stateNote ?? '') }}</div>
                </td>
                <td class="tiny">{{ String(row.role ?? '') }}</td>
                <td>
                  <textarea
                    :value="reasonOf(String(row.contributionId))"
                    placeholder="裁决理由：核对了哪些来源记录、本人主张是什么、为什么采信或不采信。"
                    @input="setReason(String(row.contributionId), ($event.target as HTMLTextAreaElement).value)"
                  ></textarea>
                  <div style="display: flex; gap: 6px; flex-wrap: wrap; margin-top: 6px">
                    <button
                      v-for="option in DECISIONS"
                      :key="option.value"
                      class="btn small"
                      :class="{ danger: option.value === 'EXCLUDE', primary: option.value === 'CONFIRM' }"
                      :disabled="busy !== ''"
                      :title="option.hint"
                      @click="resolve(String(row.contributionId), option.value)"
                    >
                      {{ option.label }}
                    </button>
                  </div>
                  <div class="tiny faint" style="margin-top: 6px">
                    判定不成立＝已排除；核验后确认＝计入确定关系；撤销争议结论＝回到自动关联（不等于确认）
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
