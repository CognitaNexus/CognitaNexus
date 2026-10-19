<script setup lang="ts">
/**
 * 系统公告（管理员）。
 *
 * 三条必须说清楚的限制，写在页面上而不是帮助文档里：
 *  1. 公告发出去就**收不回来**：它已被复制进每个账号的通知，通知是不可变记录。写错只能再发一条更正。
 *  2. 收件人是**发布那一刻的启用账号**：发布之后才注册的账号收不到这条。
 *  3. 发布成功返回的是「写了多少条通知」，不是「多少人读了」。
 *
 * 后台另有一道 60 秒同标题拦截，防的是双击发布——那道拦截在服务端做，前端不做重复判断，
 * 因为前端拦不住第二次请求（双击时两次都可能已经发出）。
 */
import { onMounted, ref } from 'vue'
import { adminApi } from '@/api'
import { describeError } from '@/composables/useAsync'

const form = ref({ title: '', body: '', level: 'INFO' as 'INFO' | 'WARNING' })
const publishing = ref(false)
const error = ref('')
const result = ref<{ id: string; recipients: number; note?: string } | null>(null)
const history = ref<Record<string, unknown>[]>([])
const historyNote = ref('')
const loadingHistory = ref(false)

async function loadHistory() {
  loadingHistory.value = true
  try {
    const page = await adminApi.announcements(20)
    history.value = page.items ?? []
    historyNote.value = page.note ?? ''
  } catch {
    // 历史读不到不影响发布本身，单独吞掉错误，避免整页报错挡住发布表单。
    history.value = []
  } finally {
    loadingHistory.value = false
  }
}

async function publish() {
  if (publishing.value) return
  if (!form.value.title.trim() || !form.value.body.trim()) {
    error.value = '标题与正文都不能为空'
    return
  }
  error.value = ''
  result.value = null
  publishing.value = true
  try {
    result.value = await adminApi.publishAnnouncement({
      title: form.value.title.trim(),
      body: form.value.body.trim(),
      level: form.value.level,
    })
    form.value = { title: '', body: '', level: 'INFO' }
    await loadHistory()
  } catch (cause) {
    error.value = describeError(cause).message
  } finally {
    publishing.value = false
  }
}

function timeLabel(value: unknown): string {
  if (typeof value !== 'string') return '—'
  const at = new Date(value)
  return Number.isNaN(at.getTime()) ? value : at.toLocaleString()
}

onMounted(loadHistory)
</script>

<template>
  <div>
    <h1>系统公告</h1>
    <p class="small faint" style="margin-top: 0">
      发布后会给<strong>当前全部启用账号</strong>各写一条站内通知。公告<strong>无法撤回</strong>，
      发布之后才注册的账号收不到这条；写错只能再发一条更正。
    </p>

    <div class="panel">
      <div class="panel-head"><h2>发布新公告</h2></div>
      <div class="panel-body">
        <label class="tiny faint" for="anc-title">标题（200 字内）</label>
        <input
          id="anc-title"
          v-model="form.title"
          maxlength="200"
          style="width: 100%"
          placeholder="例如：平台将于 11 月 3 日 22:00 进行维护"
        />

        <label class="tiny faint" for="anc-body" style="margin-top: 10px; display: block">
          正文（2000 字内，纯文本）
        </label>
        <textarea id="anc-body" v-model="form.body" rows="6" maxlength="2000" style="width: 100%" />

        <label class="tiny faint" for="anc-level" style="margin-top: 10px; display: block">
          级别
        </label>
        <select id="anc-level" v-model="form.level" style="width: 200px">
          <option value="INFO">普通（INFO）</option>
          <option value="WARNING">提醒（WARNING）</option>
        </select>

        <div class="btn-row" style="margin-top: 12px">
          <button class="btn primary" :disabled="publishing" @click="publish">
            {{ publishing ? '发布中…' : '发布给全体启用账号' }}
          </button>
          <span class="tiny faint">60 秒内不能重复发同标题公告</span>
        </div>

        <p v-if="error" class="notice danger" style="margin-top: 12px">{{ error }}</p>
        <div v-if="result" class="notice info" style="margin-top: 12px">
          <div>已发布，写入 {{ result.recipients }} 条通知（公告 ID：{{ result.id }}）。</div>
          <div class="tiny faint" style="margin-top: 4px">{{ result.note }}</div>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h2>发布台账（最近 20 条）</h2>
        <span class="tiny faint">只用于追溯，不能撤回或编辑</span>
      </div>
      <div class="panel-body">
        <div v-if="loadingHistory" class="loading"><span class="spinner"></span> 正在读取…</div>
        <div v-else-if="history.length === 0" class="empty">还没有发布过公告。</div>
        <table v-else class="data">
          <thead>
            <tr>
              <th>标题</th>
              <th style="width: 80px">级别</th>
              <th style="width: 80px">收件数</th>
              <th style="width: 160px">发布时间</th>
              <th style="width: 120px">发布人</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in history" :key="String(row.id)">
              <td>
                {{ row.title }}
                <div class="tiny faint">{{ row.body }}</div>
              </td>
              <td class="small">{{ row.level }}</td>
              <td class="small">{{ row.recipients }}</td>
              <td class="small">{{ timeLabel(row.publishedAt) }}</td>
              <td class="small">{{ row.publishedByName }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="historyNote" class="tiny faint" style="margin: 10px 0 0">{{ historyNote }}</p>
      </div>
    </div>
  </div>
</template>
