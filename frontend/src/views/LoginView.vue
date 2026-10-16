<script setup lang="ts">
/**
 * 登录页。
 *
 * 这里刻意不写「记住我」「第三方登录」之类入口：本地部署下没有这些依赖，摆上一个点了会失败的
 * 按钮，比不摆它更糟——用户会以为是自己操作错了。
 *
 * 登录失败一律原样展示后端文案。会话 Cookie 由服务端下发，前端不落任何本地登录标记，
 * 因此刷新页面后是否已登录仍以 /auth/me 为准。
 */
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { describeError } from '@/composables/useAsync'

const session = useSessionStore()
const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const submitting = ref(false)
const error = ref('')
const errorCode = ref('')

async function submit() {
  if (submitting.value) return
  error.value = ''
  errorCode.value = ''
  if (!username.value || !password.value) {
    error.value = '用户名与密码都不能为空。'
    return
  }
  submitting.value = true
  try {
    await session.login(username.value, password.value)
    const redirect = route.query.redirect
    await router.push(typeof redirect === 'string' && redirect ? redirect : { name: 'home' })
  } catch (cause) {
    const described = describeError(cause)
    error.value = described.message
    errorCode.value = described.code
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div style="max-width: 420px; margin: 40px auto">
    <div class="panel">
      <div class="panel-head"><h2>登录</h2></div>
      <div class="panel-body">
        <div v-if="error" class="notice danger">
          <div>{{ error }}</div>
          <div v-if="errorCode" class="tiny mono" style="margin-top: 6px">错误码 {{ errorCode }}</div>
        </div>

        <form @submit.prevent="submit">
          <div class="field">
            <label for="login-username">用户名</label>
            <input id="login-username" v-model="username" type="text" autocomplete="username" />
          </div>
          <div class="field">
            <label for="login-password">密码</label>
            <input
              id="login-password"
              v-model="password"
              type="password"
              autocomplete="current-password"
            />
          </div>

          <div class="btn-row" style="margin-top: 4px">
            <button class="btn primary" type="submit" :disabled="submitting">
              {{ submitting ? '正在登录…' : '登录' }}
            </button>
            <RouterLink class="small" to="/register">没有账号？去注册</RouterLink>
          </div>
        </form>
      </div>
    </div>

    <p class="small dim">
      登录后可见「我的门户」「审核」「治理」「采集」「审计」。这些入口是否出现取决于账号实际具备的能力，
      未出现不代表被隐藏，而是服务端在会话里没有声明该能力。
    </p>
  </div>
</template>
