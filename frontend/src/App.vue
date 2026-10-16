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
import { computed, onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { useScopeStore } from '@/stores/scope'
import { queryApi } from '@/api'
import type { DataNature } from '@/api/types'
import NatureBadge from '@/components/NatureBadge.vue'

const session = useSessionStore()
const scope = useScopeStore()
const route = useRoute()
const router = useRouter()

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

function quickSearch() {
  const q = quickKeyword.value.trim()
  router.push({ name: 'search', query: q ? { q } : {} })
}

function logout() {
  session.logout().then(() => router.push({ name: 'home' }))
}

const showScopeStrip = computed(() => scope.isDemo || serving.value === false || serving.value === null)

onMounted(loadConsistency)
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <div class="topbar-inner">
        <RouterLink class="brand" to="/">
          <span class="brand-mark">Cognita<em>Nexus</em></span>
          <span class="brand-sub">学术成果分享平台 · 工程实例</span>
        </RouterLink>

        <form class="quick-search" @submit.prevent="quickSearch">
          <input
            v-model="quickKeyword"
            type="search"
            aria-label="检索成果或学者"
            placeholder="按标题、DOI、作者检索成果"
          />
          <button type="submit">检索</button>
        </form>

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
          <RouterLink to="/network">合作网络</RouterLink>
          <RouterLink to="/ops">一致性与投递</RouterLink>
          <template v-if="session.authenticated">
            <span class="nav-sep"></span>
            <RouterLink to="/portal">我的门户</RouterLink>
            <RouterLink to="/review">审核</RouterLink>
            <RouterLink to="/governance">治理</RouterLink>
            <RouterLink to="/ingestion">采集</RouterLink>
            <RouterLink to="/audit">审计</RouterLink>
          </template>
        </nav>
      </div>
    </header>

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

    <main class="page">
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
