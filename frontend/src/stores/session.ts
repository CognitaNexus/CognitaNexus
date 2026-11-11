import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api'
import type { SessionView } from '@/api/types'

/**
 * 会话状态。
 *
 * 只保存「后端确认过的事实」：会话内容来自 /auth/me 或登录响应，不在前端推断角色。
 * 界面按 capabilities 决定显示什么入口，但这只是不给出无用的按钮——真正的授权判断始终在
 * 服务端。前端隐藏入口不构成授权，这一点在需求里是明确写过的，所以任何写操作失败时
 * 都要如实展示错误，而不是假设「按钮显示了就一定能成功」。
 */
export const useSessionStore = defineStore('session', () => {
  const session = ref<SessionView | null>(null)
  const loading = ref(false)
  const loaded = ref(false)
  const error = ref('')

  const authenticated = computed(() => session.value !== null)

  const capabilities = computed(() => new Set(session.value?.capabilities ?? []))

  function can(capability: string): boolean {
    return capabilities.value.has(capability)
  }

  /** 是否为平台管理员（用于显示管理入口）。 */
  const isAdmin = computed(() => can('PLATFORM_ADMIN') || can('ACCOUNT_ADMIN'))

  async function refresh(): Promise<void> {
    loading.value = true
    error.value = ''
    try {
      session.value = await authApi.me()
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : String(cause)
      session.value = null
    } finally {
      loading.value = false
      loaded.value = true
    }
  }

  async function login(username: string, password: string): Promise<void> {
    session.value = await authApi.login({ username, password })
    loaded.value = true
  }

  async function logout(): Promise<void> {
    await authApi.logout()
    session.value = null
  }

  return { session, loading, loaded, error, authenticated, isAdmin, capabilities, can, refresh, login, logout }
})
