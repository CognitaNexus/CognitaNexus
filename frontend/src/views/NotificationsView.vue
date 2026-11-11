<script setup lang="ts">
/**
 * 通知中心（ADR-003）。
 *
 * 四条原则：
 *  1. 只读服务端写好的结论与去向，不在前端拼措辞——通知文案是后端业务动作的一部分，
 *     与审计、台账同一批生成，前端二创会造成两处不一致；
 *  2. refType/refId 是可选字段：撤权类通知没有单一跳转目标，服务端可能不下发这两个字段
 *     （non_null 序列化会把 null 省略掉），这里同时挡 undefined 与 null；
 *  3. 「全部已读」作用于服务端本人的**全部**未读，不只是当前这一页。早期版本只处理已加载的
 *     列表，结果是「点了全部已读，未读数还是 30」——用户以为按钮坏了。分页不该改变按钮语义。
 *  4. 「移除」是归档不是删除：服务端保留数据供审计，只是不再出现在这个账号的列表与未读数里。
 *     因此这里措辞用「移除」，且在操作前明确告诉用户「不可恢复显示」，不假装是删除。
 */
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { notificationApi, type NotificationItem } from '@/api'
import AsyncBlock from '@/components/AsyncBlock.vue'

const PAGE = 20

const unreadOnly = ref(false)
const kindFilter = ref('')
const kinds = ref<string[]>([])
const items = ref<NotificationItem[]>([])
const exhausted = ref(false)
const loading = ref(false)
const busy = ref('')
const error = ref('')
const errorCode = ref('')
const actionNote = ref('')

/** 未读徽标（导航上显示的数字）。列表页操作后主动刷新，避免等下一次轮询。 */
const unread = ref<number | null>(null)

async function refreshUnread() {
  try {
    unread.value = (await notificationApi.unreadCount()).unreadCount
  } catch {
    unread.value = null
  }
}

async function loadKinds() {
  try {
    kinds.value = (await notificationApi.kinds()).kinds ?? []
  } catch {
    // 拿不到类型清单只是筛选下拉少几项，不影响列表本身，因此不报错。
    kinds.value = []
  }
}

async function load(reset: boolean) {
  loading.value = true
  error.value = ''
  errorCode.value = ''
  try {
    const page = await notificationApi.list({
      unreadOnly: unreadOnly.value,
      kind: kindFilter.value || undefined,
      limit: PAGE,
      offset: reset ? 0 : items.value.length,
    })
    items.value = reset ? page.items : [...items.value, ...page.items]
    exhausted.value = page.items.length < PAGE
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
    errorCode.value = ''
  } finally {
    loading.value = false
  }
}

async function reload() {
  items.value = []
  exhausted.value = false
  actionNote.value = ''
  await Promise.all([load(true), refreshUnread(), loadKinds()])
}

async function toggleFilter() {
  unreadOnly.value = !unreadOnly.value
  await reload()
}

async function changeKind(event: Event) {
  kindFilter.value = (event.target as HTMLSelectElement).value
  await reload()
}

const unreadInView = computed(() => items.value.filter((item) => !item.read).length)

async function markOne(item: NotificationItem) {
  if (item.read || busy.value) return
  busy.value = item.id
  try {
    await notificationApi.markRead([item.id])
    item.read = true
    await refreshUnread()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    busy.value = ''
  }
}

/** 全部已读：服务端全量处理，返回实际影响条数，前端据此给一句确认。 */
async function markAllRead() {
  if (busy.value) return
  busy.value = 'all-read'
  actionNote.value = ''
  try {
    const result = await notificationApi.markAllRead()
    items.value = items.value.map((item) => ({ ...item, read: true }))
    await refreshUnread()
    actionNote.value =
      result.markedRead === 0 ? '没有未读通知需要处理。' : `已把 ${result.markedRead} 条标记为已读。`
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    busy.value = ''
  }
}

async function removeOne(item: NotificationItem) {
  if (busy.value) return
  // 移除不可恢复显示：先确认。未读的额外提醒一句，避免把没看过的消息清掉。
  const hint = item.read
    ? '从列表移除这条通知？移除后不再显示（数据仍保留供审计）。'
    : '这条通知还没读，移除后不会再有提示，也不再显示。确定移除？'
  if (!window.confirm(hint)) return
  busy.value = item.id
  actionNote.value = ''
  try {
    const result = await notificationApi.archive([item.id])
    items.value = items.value.filter((row) => row.id !== item.id)
    await refreshUnread()
    actionNote.value = result.note ?? '已从列表移除。'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    busy.value = ''
  }
}

async function clearRead() {
  if (busy.value) return
  if (!window.confirm('把已读通知全部从列表移除？未读的会保留。')) return
  busy.value = 'clear-read'
  actionNote.value = ''
  try {
    const result = await notificationApi.archiveAllRead()
    await reload()
    actionNote.value =
      result.archived === 0 ? '没有可移除的已读通知。' : `已移除 ${result.archived} 条已读通知。`
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    busy.value = ''
  }
}

/** kind → 展示标签。服务端 kind 是稳定枚举，前端只负责选词。 */
function kindLabel(kind: string): string {
  switch (kind) {
    case 'CLAIM_DECIDED':
      return '认领裁决'
    case 'PORTAL_CHANGE_DECIDED':
      return '门户变更'
    case 'ACHIEVEMENT_MERGED':
      return '成果合并'
    case 'RELATION_DECIDED':
      return '关系裁决'
    case 'SOURCE_LICENSE_REVOKED':
      return '许可撤销'
    case 'SOURCE_LICENSE_RESTORED':
      return '许可恢复'
    case 'SUBMISSION_DECIDED':
      return '提交裁决'
    case 'SYSTEM_ANNOUNCEMENT':
      return '系统公告'
    default:
      return kind
  }
}

function timeLabel(iso: string): string {
  const at = new Date(iso)
  if (Number.isNaN(at.getTime())) return iso
  return at.toLocaleString()
}

/** 跳转去向：按 refType 决定。没有 ref 的通知（如撤权批次、系统公告）就没有链接。 */
function targetOf(item: NotificationItem): { name: string; params: Record<string, string> } | null {
  if (!item.refType || !item.refId) return null
  if (item.refType === 'SCHOLAR') return { name: 'scholar', params: { id: item.refId } }
  if (item.refType === 'ACHIEVEMENT') return { name: 'achievement', params: { id: item.refId } }
  return null
}

onMounted(async () => {
  await Promise.all([load(true), refreshUnread(), loadKinds()])
})
</script>

<template>
  <div>
    <h1>通知</h1>
    <p class="small faint" style="margin-top: 0">
      站内通知只来自平台的业务动作（认领裁决、成果合并、关系裁决、来源许可变更、管理员公告），
      用户之间不能互发。通知不是正式送达文书，重要决定同时可在对应页面查询。
    </p>

    <div class="panel">
      <div class="panel-head">
        <h2>通知列表</h2>
        <span class="tiny faint">
          {{ unread === null ? '未读数未知' : `未读 ${unread} 条` }}
        </span>
      </div>
      <div class="panel-body">
        <div class="notif-toolbar">
          <select class="notif-kind" :value="kindFilter" @change="changeKind">
            <option value="">全部类型</option>
            <option v-for="kind in kinds" :key="kind" :value="kind">
              {{ kindLabel(kind) }}
            </option>
          </select>
          <button class="btn small" :disabled="busy !== ''" @click="toggleFilter">
            {{ unreadOnly ? '显示全部通知' : '只看未读' }}
          </button>
          <button
            class="btn small"
            :disabled="busy !== '' || (unread ?? 0) === 0"
            @click="markAllRead"
          >
            全部标为已读
          </button>
          <button class="btn small" :disabled="busy !== ''" @click="clearRead">清空已读</button>
          <span v-if="unreadInView > 0" class="tiny faint">本页未读 {{ unreadInView }} 条</span>
        </div>

        <p v-if="actionNote" class="tiny faint" style="margin: 8px 0 0">{{ actionNote }}</p>

        <AsyncBlock
          :loading="loading"
          :error="error"
          :error-code="errorCode"
          :empty="items.length === 0"
          :empty-text="
            unreadOnly
              ? '没有未读通知。'
              : kindFilter
                ? '这个类型下还没有通知。'
                : '还没有通知。发生与你相关的业务动作时会出现在这里。'
          "
        >
          <ul class="notif-list" style="list-style: none; margin: 12px 0 0; padding: 0">
            <li
              v-for="item in items"
              :key="item.id"
              class="notif-item"
              :class="{ unread: !item.read }"
            >
              <div class="notif-meta">
                <strong v-if="!item.read" aria-label="未读">●</strong>
                <span class="tiny faint">{{ kindLabel(item.kind) }}</span>
                <span class="small dim">{{ timeLabel(item.createdAt) }}</span>
              </div>
              <p class="notif-title">{{ item.title }}</p>
              <p class="small notif-body">{{ item.body }}</p>
              <div class="notif-actions">
                <RouterLink
                  v-if="targetOf(item)"
                  class="small"
                  :to="{ name: targetOf(item)!.name, params: targetOf(item)!.params }"
                  >查看关联对象</RouterLink
                >
                <button
                  v-if="!item.read"
                  class="btn small"
                  :disabled="busy !== ''"
                  @click="markOne(item)"
                >
                  标记已读
                </button>
                <button class="btn small" :disabled="busy !== ''" @click="removeOne(item)">
                  移除
                </button>
              </div>
            </li>
          </ul>
          <div v-if="!exhausted" style="margin-top: 12px">
            <button class="btn" :disabled="loading" @click="load(false)">加载更多</button>
          </div>
          <p v-else-if="items.length > 0" class="tiny faint" style="margin-top: 12px">没有更多了。</p>
        </AsyncBlock>
      </div>
    </div>
  </div>
</template>

<style scoped>
.notif-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.notif-kind {
  min-width: 140px;
  padding: 4px 8px;
}

.notif-item {
  border-top: 1px solid var(--line, #ddd);
  padding: 12px 4px;
}

.notif-meta {
  display: flex;
  gap: 8px;
  align-items: baseline;
  flex-wrap: wrap;
}

.notif-title {
  margin: 6px 0 4px;
  font-weight: 600;
}

.notif-body {
  margin: 0 0 6px;
  white-space: pre-wrap;
}

.notif-actions {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
</style>
