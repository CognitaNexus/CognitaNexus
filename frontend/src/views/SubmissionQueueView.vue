<script setup lang="ts">
/**
 * 待审成果队列。
 *
 * 审核界面最容易出的问题不是「操作不了」，而是「看不出来该不该批」。因此每条都直接给出三样东西：
 * 作者填的原值（题名/DOI/载体/年份/署名）、查重命中的那一条（题名 + 命中方式）、以及
 * 「疑似重复不等于重复」的说明。三样齐了，人才能在一次阅读里做完判断。
 *
 * 裁决必须带原因，且原因会被原样发给提交人：写「不行」等于让作者反复试。
 */
import { computed, onMounted, ref } from 'vue'
import { adminApi, type SubmissionView } from '@/api'
import { describeError } from '@/composables/useAsync'

const items = ref<SubmissionView[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const note = ref('')
const busy = ref('')
const reasons = ref<Record<string, string>>({})

const openCount = computed(() => items.value.length)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const envelope = await adminApi.submissions({ page: 1, size: 50 })
    items.value = envelope.data ?? []
    total.value = Number((envelope.meta?.total as number | undefined) ?? items.value.length)
  } catch (cause) {
    error.value = describeError(cause).message
  } finally {
    loading.value = false
  }
}

async function decide(row: SubmissionView, decision: string) {
  const reason = (reasons.value[row.id] ?? '').trim()
  if ((decision === 'REJECT' || decision === 'NEED_MORE_INFO') && !reason) {
    error.value = '驳回或要求补充材料时必须写明原因：这条说明会原样发给提交人'
    return
  }
  busy.value = row.id
  error.value = ''
  note.value = ''
  try {
    const result = await adminApi.decideSubmission(row.id, decision, reason)
    note.value = result.achievementId
      ? `${result.message}（成果 ${result.achievementId}）`
      : result.message
    // 不出队列的状态只有两种：批准与驳回。要求补充材料仍留在队列里，直到作者补齐。
    if (decision !== 'NEED_MORE_INFO') {
      items.value = items.value.filter((item) => item.id !== row.id)
      total.value = Math.max(0, total.value - 1)
    } else {
      items.value = items.value.map((item) =>
        item.id === row.id
          ? { ...item, status: 'NEED_MORE_INFO', statusLabel: '待补充材料', decisionReason: reason }
          : item,
      )
    }
  } catch (cause) {
    error.value = describeError(cause).message
  } finally {
    busy.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div style="display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap">
      <h1 style="margin-bottom: 4px">待审成果</h1>
      <span class="tiny faint">队列共 {{ total }} 条，本页 {{ openCount }} 条（先提交先处理）</span>
      <button class="btn small" type="button" @click="load()">刷新</button>
    </div>

    <div class="notice info">
      批准即建档：成果立刻进入公开目录，参与关系状态为「本人确认」，字段值级别为人工值（此后来源自动更新不得覆盖）。
      查重命中只是提示——相似题名不等于重复，判断权在你。
    </div>

    <p v-if="error" class="notice danger">{{ error }}</p>
    <p v-if="note" class="notice info">{{ note }}</p>

    <div v-if="loading" class="loading"><span class="spinner"></span> 正在读取…</div>
    <div v-else-if="items.length === 0" class="empty">队列已清空：当前没有待审的提交。</div>

    <article v-for="row in items" v-else :key="row.id" class="panel">
      <div class="panel-head">
        <h2 style="font-size: 16px">{{ row.title }}</h2>
        <span class="tiny faint mono">{{ row.id }}</span>
      </div>
      <div class="panel-body">
        <table class="data" style="margin-bottom: 10px">
          <tbody>
            <tr>
              <th style="width: 120px">DOI</th>
              <td>{{ row.doi || '（未填）' }}</td>
            </tr>
            <tr>
              <th>载体</th>
              <td>{{ row.venue || '（未填）' }}</td>
            </tr>
            <tr>
              <th>年份</th>
              <td>{{ row.publicationYear ?? '未知（不折算）' }}</td>
            </tr>
            <tr>
              <th>作者署名</th>
              <td>{{ row.authorsRaw || '（未填）' }}</td>
            </tr>
            <tr>
              <th>关联门户</th>
              <td>
                <template v-if="row.scholarId">
                  批准时建立「本人确认」关系：<span class="mono">{{ row.scholarId }}</span>
                </template>
                <span v-else class="faint">未指定：批准只建成果，不建参与关系</span>
              </td>
            </tr>
            <tr v-if="row.abstractText">
              <th>摘要</th>
              <td class="small">{{ row.abstractText }}</td>
            </tr>
          </tbody>
        </table>

        <div v-if="row.duplicateOf" class="notice warn" style="margin-bottom: 10px">
          <strong>疑似重复：</strong>
          <span>{{ row.duplicateTitle || row.duplicateOf }}</span>
          <span class="tiny">（{{ row.duplicateKind === 'DOI' ? 'DOI 相同' : '题名相似' }}）</span>
          <div class="tiny">{{ row.duplicateNote }}</div>
          <RouterLink class="tiny" :to="`/achievements/${row.duplicateOf}`">打开已有成果对照</RouterLink>
        </div>

        <label class="tiny faint" :for="`reason-${row.id}`">裁决说明（驳回/补充材料时必填，会原样发给提交人）</label>
        <textarea
          :id="`reason-${row.id}`"
          v-model="reasons[row.id]"
          rows="2"
          maxlength="1000"
          style="width: 100%"
          placeholder="例如：请补充 DOI 或期刊卷期信息"
        />

        <div class="btn-row" style="margin-top: 8px">
          <button class="btn primary" type="button" :disabled="busy === row.id" @click="decide(row, 'APPROVE')">
            批准并建档
          </button>
          <button class="btn" type="button" :disabled="busy === row.id" @click="decide(row, 'NEED_MORE_INFO')">
            要求补充材料
          </button>
          <button class="btn" type="button" :disabled="busy === row.id" @click="decide(row, 'REJECT')">
            驳回
          </button>
        </div>
      </div>
    </article>
  </div>
</template>
