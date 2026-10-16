import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

/**
 * 路由表。
 *
 * 约定：公开浏览页不设守卫（公开检索是平台的核心能力），只有门户与管理页做「未登录先登录」
 * 的就地跳转。这里的守卫只解决「把未登录用户送到登录页」，不承担授权判断——授权在服务端，
 * 越权请求会拿到 ACCESS_DENIED，由页面如实展示。
 */
const routes: RouteRecordRaw[] = [
  { path: '/', name: 'home', component: () => import('@/views/HomeView.vue'), meta: { title: '总览' } },
  {
    path: '/search',
    name: 'search',
    component: () => import('@/views/SearchView.vue'),
    meta: { title: '成果检索' },
  },
  {
    path: '/achievements/:id',
    name: 'achievement',
    component: () => import('@/views/AchievementDetailView.vue'),
    meta: { title: '成果详情' },
  },
  {
    path: '/scholars/:id',
    name: 'scholar',
    component: () => import('@/views/ScholarDetailView.vue'),
    meta: { title: '学者档案' },
  },
  {
    path: '/network',
    name: 'network',
    component: () => import('@/views/NetworkView.vue'),
    meta: { title: '合作网络' },
  },
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { title: '登录' } },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { title: '注册' },
  },
  {
    path: '/portal',
    name: 'portal',
    component: () => import('@/views/PortalView.vue'),
    meta: { title: '我的门户', requiresAuth: true },
  },
  {
    path: '/review',
    name: 'review',
    component: () => import('@/views/ReviewView.vue'),
    meta: { title: '审核工作台', requiresAuth: true },
  },
  {
    path: '/governance',
    name: 'governance',
    component: () => import('@/views/GovernanceView.vue'),
    meta: { title: '治理与去重', requiresAuth: true },
  },
  {
    path: '/ingestion',
    name: 'ingestion',
    component: () => import('@/views/IngestionView.vue'),
    meta: { title: '采集管理台', requiresAuth: true },
  },
  {
    path: '/audit',
    name: 'audit',
    component: () => import('@/views/AuditView.vue'),
    meta: { title: '审计查询', requiresAuth: true },
  },
  {
    path: '/ops',
    name: 'ops',
    component: () => import('@/views/OpsView.vue'),
    meta: { title: '一致性与投递' },
  },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFoundView.vue') },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · CognitaNexus` : 'CognitaNexus 学术成果分享平台'
})

export default router
