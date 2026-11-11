<script setup lang="ts">
/**
 * 注册页。
 *
 * 注册成功不自动登录：本平台的账号能力（能否认领、能否审核）由管理员另行授予，把注册与登录
 * 合成一步会让「我刚注册成功，为什么进不了审核台」变成一个需要解释的困惑。这里注册完成后
 * 提示去登录，并说明默认注册得到的是普通账号。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { authApi } from '@/api'
import { describeError } from '@/composables/useAsync'

const router = useRouter()

const username = ref('')
const displayName = ref('')
const email = ref('')
const password = ref('')
const passwordAgain = ref('')

const submitting = ref(false)
const error = ref('')
const errorCode = ref('')
const done = ref(false)

/** 本地只做「两次密码是否一致」这类不依赖服务端的检查；其余规则由服务端裁定。 */
const mismatch = computed(
  () => password.value !== '' && passwordAgain.value !== '' && password.value !== passwordAgain.value,
)

async function submit() {
  if (submitting.value) return
  error.value = ''
  errorCode.value = ''
  if (!username.value || !displayName.value || !password.value) {
    error.value = '用户名、显示名与密码都不能为空。'
    return
  }
  if (mismatch.value) {
    error.value = '两次输入的密码不一致。'
    return
  }
  submitting.value = true
  try {
    await authApi.register({
      username: username.value,
      password: password.value,
      displayName: displayName.value,
      email: email.value || undefined,
    })
    done.value = true
  } catch (cause) {
    const described = describeError(cause)
    error.value = described.message
    errorCode.value = described.code
  } finally {
    submitting.value = false
  }
}

function goLogin() {
  router.push({ name: 'login' })
}
</script>

<template>
  <div style="max-width: 460px; margin: 40px auto">
    <div class="panel">
      <div class="panel-head"><h2>注册</h2></div>
      <div class="panel-body">
        <div v-if="done" class="notice info">
          <div><strong>注册已提交。</strong>请前往登录页使用新账号登录。</div>
          <div class="btn-row" style="margin-top: 10px">
            <button class="btn primary small" @click="goLogin">去登录</button>
          </div>
        </div>

        <template v-else>
          <div v-if="error" class="notice danger">
            <div>{{ error }}</div>
            <div v-if="errorCode" class="tiny mono" style="margin-top: 6px">错误码 {{ errorCode }}</div>
          </div>

          <form @submit.prevent="submit">
            <div class="field">
              <label for="reg-username">用户名</label>
              <input id="reg-username" v-model="username" type="text" autocomplete="username" />
              <span class="tiny faint">用于登录，注册后不可更改。</span>
            </div>
            <div class="field">
              <label for="reg-display">显示名</label>
              <input id="reg-display" v-model="displayName" type="text" />
            </div>
            <div class="field">
              <label for="reg-email">邮箱（可选）</label>
              <input id="reg-email" v-model="email" type="email" autocomplete="email" />
            </div>
            <div class="field">
              <label for="reg-password">密码</label>
              <input id="reg-password" v-model="password" type="password" autocomplete="new-password" />
            </div>
            <div class="field">
              <label for="reg-password2">确认密码</label>
              <input
                id="reg-password2"
                v-model="passwordAgain"
                type="password"
                autocomplete="new-password"
              />
              <span v-if="mismatch" class="tiny" style="color: var(--danger)">两次输入的密码不一致。</span>
            </div>

            <div class="btn-row" style="margin-top: 4px">
              <button class="btn primary" type="submit" :disabled="submitting">
                {{ submitting ? '正在提交…' : '注册' }}
              </button>
              <RouterLink class="small" to="/login">已有账号？去登录</RouterLink>
            </div>
          </form>
        </template>
      </div>
    </div>

    <p class="small dim">
      新注册账号只具备浏览与门户自服务的默认能力。审核、治理、采集、审计等入口需要管理员单独授予角色，
      这不是「隐藏起来」，而是服务端会直接拒绝未授权请求。
    </p>
  </div>
</template>
