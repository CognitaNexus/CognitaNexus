<script setup lang="ts">
/**
 * 我的收藏（学者）。
 *
 * 页面上要写清「收藏不是订阅」：这个功能最容易被误解成「关注后新成果会通知我」。
 * 平台当前不做这类通知——新成果入库是高频事件，沿关注关系扩散会一次发出几百条通知且无法撤回。
 * 与其让用户等一个永远不会来的提醒，不如提前说清楚。
 */
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { accountApi, type FavoriteScholarView } from '@/api'
import { describeError } from '@/composables/useAsync'

const items = ref<FavoriteScholarView[]>([])
const note = ref('')
const loading = ref(false)
const error = ref('')
const busy = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const page = await accountApi.myFavorites(100)
    items.value = page.items ?? []
    note.value = page.note ?? ''
  } catch (cause) {
    error.value = describeError(cause).message
  } finally {
    loading.value = false
  }
}

async function remove(scholarId: string) {
  if (busy.value) return
  busy.value = scholarId
  error.value = ''
  try {
    await accountApi.unfavorite(scholarId)
    items.value = items.value.filter((row) => row.scholarId !== scholarId)
  } catch (cause) {
    error.value = describeError(cause).message
  } finally {
    busy.value = ''
  }
}

function timeLabel(iso?: string): string {
  if (!iso) return '—'
  const at = new Date(iso)
  return Number.isNaN(at.getTime()) ? iso : at.toLocaleString()
}

onMounted(load)
</script>

<template>
  <div>
    <h1>我的收藏</h1>
    <p class="small faint" style="margin-top: 0">
      收藏是个人列表，<strong>不会</strong>因为这位学者有新成果而给你发通知。
      平台目前不做「关注更新提醒」：新成果入库是高频事件，一次可能上千条，
      沿关注关系扩散会发出大量无法撤回的通知。
    </p>

    <div class="panel">
      <div class="panel-head">
        <h2>收藏的学者（{{ items.length }}）</h2>
        <span class="tiny faint">按收藏时间倒序，最多 100 位</span>
      </div>
      <div class="panel-body">
        <div v-if="loading" class="loading"><span class="spinner"></span> 正在读取…</div>
        <div v-else-if="error" class="notice danger">{{ error }}</div>
        <div v-else-if="items.length === 0" class="empty">
          还没有收藏任何学者。在学者档案页点「收藏」即可加入这里。
        </div>
        <table v-else class="data">
          <thead>
            <tr>
              <th>学者</th>
              <th>机构</th>
              <th style="width: 160px">收藏时间</th>
              <th style="width: 100px"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in items" :key="row.scholarId">
              <td>
                <RouterLink :to="`/scholars/${encodeURIComponent(row.scholarId)}`">
                  {{ row.displayName }}
                </RouterLink>
                <div class="tiny faint mono">{{ row.scholarId }}</div>
              </td>
              <td class="small">{{ row.organizationName || '—' }}</td>
              <td class="small">{{ timeLabel(row.favoritedAt) }}</td>
              <td>
                <button
                  class="btn small"
                  :disabled="busy !== ''"
                  @click="remove(row.scholarId)"
                >
                  取消收藏
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-if="note" class="tiny faint" style="margin: 10px 0 0">{{ note }}</p>
      </div>
    </div>
  </div>
</template>
