import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useSessionStore } from './stores/session'
import './styles.css'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)

// 启动时读一次会话：刷新页面后登录态由服务端 Cookie 决定，前端不落任何本地副本。
//
// 这一步必须发生在 app.use(router) 之前，而不是「挂载之前」就行。Vue Router 4 的初始导航
// 在 install 时就开始，若守卫先于会话刷新执行，session.authenticated 必然还是 false，
// 已登录用户直接打开或刷新任何一个需登录页面都会被弹去 /login——而且页头随即又会显示
// 已登录，页面与页头互相矛盾。这类缺陷只在「直达 URL / 硬刷新」时出现，站内点进去
// 一切正常，因此很容易在只做站内点选的验证里漏掉。
const session = useSessionStore()

/**
 * 需登录页面的就地跳转。
 *
 * 只处理「未登录」，不处理「已登录但无权限」：后者由服务端返回 ACCESS_DENIED，页面如实展示。
 * 在前端按角色自行拦截会给人「这里就是权限边界」的错觉，而真正的边界在服务端。
 * 跳转时带上 redirect，登录后回到原目标。
 */
router.beforeEach((to) => {
  if (!to.meta.requiresAuth) return true
  if (session.authenticated) return true
  return { name: 'login', query: { redirect: to.fullPath } }
})

await session.refresh()
app.use(router)
app.mount('#app')
