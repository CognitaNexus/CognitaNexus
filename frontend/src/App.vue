<script setup lang="ts">
/**
 * 应用外壳：报头、检索入口、导航、口径条、页脚。
 *
 * 报头里放一个常驻检索框而不是只放导航：这是一个学术检索站，读者十次里有八次是来找某篇
 * 论文或某个人，而不是来逛栏目的。
 *
 * 口径条放在正文之前而不是页脚：它改变的是「屏幕上所有数字的含义」。先看完数字再看限定
 * 条件，判断已经做完了。因此它常驻，但只用一条 3px 竖线着色——需要一直在视野里，不等于
 * 需要每次都抢注意力。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { useScopeStore } from '@/stores/scope'
import { notificationApi, queryApi } from '@/api'
import type { DataNature } from '@/api/types'
import NatureBadge from '@/components/NatureBadge.vue'
import SearchSuggest from '@/components/SearchSuggest.vue'

const session = useSessionStore()
const scope = useScopeStore()
const route = useRoute()
const router = useRouter()

/** 跳转链接的目标。用 ref 拿到元素后直接 focus，比靠 href="#id" 更可靠。 */
const mainEl = ref<HTMLElement | null>(null)

function focusMain() {
  mainEl.value?.focus()
}

const quickKeyword = ref('')

/** 服务端声明自己能否对外服务；未取到时保持 null，不猜成「正常」。 */
const serving = ref<boolean | null>(null)
const servingDetail = ref('')
const coreRevision = ref<number | null>(null)
const publishedRevision = ref<number | null>(null)

const natureOptions: { value: DataNature; label: string }[] = [
  { value: 'REAL', label: '真实公开口径' },
  { value: 'SIMULATED', label: '模拟数据口径' },
  { value: 'MIXED', label: '混合口径' },
  { value: 'ALL', label: '全部口径（演示）' },
]

async function loadConsistency() {
  try {
    const view = await queryApi.consistency()
    serving.value = view.serving
    coreRevision.value = view.coreCatalogRevision ?? null
    publishedRevision.value = view.publishedRevision
    servingDetail.value = view.revisionGap?.blocked
      ? `检测到修订空洞：${view.revisionGap.detail}`
      : ''
  } catch {
    // 读不到一致性状态并不等于视图正常。这里保持 serving=null（未知），
    // 而不是填 true——把「不知道」显示成「正常」是这类状态条最容易犯的错。
    serving.value = null
    servingDetail.value = '无法读取一致性状态：查询服务不可达或正在启动。'
  }
}

async function onScopeChange(event: Event) {
  const value = (event.target as HTMLSelectElement).value as DataNature
  scope.set(value)
  // 口径切换后重新读一次水位：不同口径的可见行数不同，时间点也不同步。
  await loadConsistency()
}

function quickSearch(keyword?: string) {
  const q = (keyword ?? quickKeyword.value).trim()
  router.push({ name: 'search', query: q ? { q } : {} })
}

/**
 * 选中一条建议。
 *
 * 成果直接进详情、学者直接进档案：用户已经在建议里认出了目标，再把他送到检索结果页去
 * 二次挑选，等于把他刚做完的判断再问一遍。学者档案的入口保留，是因为同名学者只能在
 * 档案里按机构核对，这一步不该被省掉。
 */
function onPickSuggestion(payload: { kind: 'achievement' | 'scholar'; id: string }) {
  if (payload.kind === 'scholar') {
    router.push({ name: 'scholar', params: { id: payload.id } })
    return
  }
  router.push({ name: 'achievement', params: { id: payload.id } })
}

function logout() {
  session.logout().then(() => router.push({ name: 'home' }))
}

const showScopeStrip = computed(() => scope.isDemo || serving.value === false || serving.value === null)

// ---------------------------------------------------------------------------
// 站内通知未读徽标（ADR-003）：登录期间每 30 秒拉一次未读数。
// 读不到就保持 null（导航上显示「通知」但不带数字），不猜成 0——「没有未读」和「读不到」
// 是两件事，把后者显示成 0 会让人错过该看的通知。
// ---------------------------------------------------------------------------
const unreadNotifications = ref<number | null>(null)
let pollTimer: number | null = null

async function pollUnread() {
  if (!session.authenticated) {
    unreadNotifications.value = null
    return
  }
  try {
    unreadNotifications.value = (await notificationApi.unreadCount()).unreadCount
  } catch {
    unreadNotifications.value = null
  }
}

onMounted(() => {
  loadConsistency()
  pollUnread()
  pollTimer = window.setInterval(pollUnread, 30_000)
})

onUnmounted(() => {
  if (pollTimer !== null) window.clearInterval(pollTimer)
})
</script>

<template>
  <div class="shell">
    <!--
      跳过导航：这个外壳的顶栏有品牌、检索框、口径选择、导航与账号区，键盘用户若想直接
      读正文，得先 Tab 穿过十几个元素。跳转目标 main 带 tabindex="-1"，这样焦点能被程序化
      移到正文上（不要写 tabindex="0"：那会把正文本身塞进 Tab 序列，Tab 一圈会多停一次）。
    -->
    <a class="skip-link" href="#main-content" @click.prevent="focusMain">跳转到主内容</a>

    <header class="topbar">
      <div class="topbar-inner">
        <RouterLink class="brand" to="/">
          <span class="brand-mark">Cognita<em>Nexus</em></span>
          <span class="brand-sub">学术成果分享平台 · 工程实例</span>
        </RouterLink>

        <SearchSuggest
          v-model="quickKeyword"
          :data-nature="scope.nature"
          aria-label="检索成果"
          placeholder="按题名、作者、摘要、载体或 DOI 检索"
          @submit="quickSearch"
          @pick="onPickSuggestion"
        />

        <div class="topbar-right">
          <label class="tiny faint nowrap" for="scope-select">数据口径</label>
          <select
            id="scope-select"
            :value="scope.nature"
            style="width: auto; height: 28px; font-size: 12.5px"
            @change="onScopeChange"
          >
            <option v-for="option in natureOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>

          <template v-if="session.authenticated">
            <span class="small dim nowrap">{{ session.session?.displayName }}</span>
            <button class="btn small" @click="logout">退出</button>
          </template>
          <template v-else>
            <RouterLink class="small" to="/login">登录</RouterLink>
            <span class="faint">·</span>
            <RouterLink class="small" to="/register">注册</RouterLink>
          </template>
        </div>
      </div>

      <div class="nav-row">
        <nav class="nav">
          <RouterLink to="/">总览</RouterLink>
          <RouterLink to="/search">成果检索</RouterLink>
          <RouterLink to="/hotspots">关键词热点</RouterLink>
          <RouterLink to="/network">合作网络</RouterLink>
          <RouterLink to="/ops">一致性与投递</RouterLink>
          <template v-if="session.authenticated">
            <RouterLink class="notif-link" to="/notifications">
              通知<span v-if="unreadNotifications !== null && unreadNotifications > 0" class="notif-badge">{{
                unreadNotifications > 99 ? '99+' : unreadNotifications
              }}</span>
            </RouterLink>
            <span class="nav-sep"></span>
            <RouterLink to="/portal">我的门户</RouterLink>
            <RouterLink to="/portal/contributions">我的成果</RouterLink>
            <RouterLink to="/portal/account">账号设置</RouterLink>
            <RouterLink to="/favorites">我的收藏</RouterLink>
            <RouterLink to="/portal/submissions">提交成果</RouterLink>
            <RouterLink to="/todos">待办</RouterLink>
            <RouterLink to="/review">审核</RouterLink>
            <RouterLink to="/review/relations">关系争议</RouterLink>
            <RouterLink to="/governance">治理</RouterLink>
            <RouterLink to="/governance/submissions">待审成果</RouterLink>
            <RouterLink to="/ingestion">采集</RouterLink>
            <RouterLink to="/governance/citations">引用网络</RouterLink>
            <RouterLink to="/audit">审计</RouterLink>
            <RouterLink to="/announcements">系统公告</RouterLink>
          </template>
        </nav>
      </div>
    </header>

    <!-- 一次性口令提示：不阻断登录，但必须一直在，直到本人改密。 -->
    <div v-if="session.session?.mustChangePassword" class="scope-strip">
      <div class="strip warn">
        <strong>当前口令是管理员重置的一次性口令。</strong>
        为账号安全，请<RouterLink to="/portal/account">立即修改口令</RouterLink>。
      </div>
    </div>

    <!-- 口径条常驻：它改变的是屏幕上所有数字的含义。 -->
    <div v-if="showScopeStrip" class="scope-strip">
      <div v-if="scope.isDemo" class="strip demo">
        <strong>当前为演示口径（不限数据性质）。</strong>
        本页数字包含模拟与混合数据，只能用于演示与容量验证，
        <strong>不得作为平台真实收录口径引用</strong>。切回「真实公开口径」可查看仅含真实公开元数据的统计。
      </div>
      <div v-if="serving === false" class="strip danger">
        <strong>公开视图正在更新，当前查询结果可能不可用。</strong>
        {{ servingDetail || '本地已发布水位落后于权威要求，请稍后重试。' }}
      </div>
      <div v-else-if="serving === null" class="strip warn">{{ servingDetail }}</div>
    </div>

    <main id="main-content" ref="mainEl" class="page" tabindex="-1">
      <RouterView :key="route.fullPath" />
    </main>

    <footer class="footer">
      <div class="footer-inner">
        <span>
          CognitaNexus · 学术成果分享平台 · 当前口径
          <NatureBadge :nature="scope.nature" />
        </span>
        <span class="mono">
          <template v-if="publishedRevision !== null">
            已发布修订 {{ publishedRevision }}
            <template v-if="coreRevision !== null"> / 权威修订 {{ coreRevision }}</template>
          </template>
          <template v-else>水位未知</template>
        </span>
      </div>
    </footer>
  </div>
</template>
