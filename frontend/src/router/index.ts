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
    path: '/topics/:id',
    name: 'topic',
    component: () => import('@/views/TopicInsightView.vue'),
    meta: { title: '领域洞察' },
  },
  {
    path: '/hotspots',
    name: 'hotspots',
    component: () => import('@/views/HotspotView.vue'),
    meta: { title: '关键词热点' },
  },
  {
    path: '/organizations',
    name: 'organizations',
    component: () => import('@/views/OrganizationView.vue'),
    meta: { title: '机构' },
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
    path: '/portal/account',
    name: 'portal-account',
    component: () => import('@/views/AccountSettingsView.vue'),
    meta: { title: '账号设置', requiresAuth: true },
  },
  {
    path: '/portal/contributions',
    name: 'portal-contributions',
    component: () => import('@/views/PortalContributionsView.vue'),
    meta: { title: '我的成果', requiresAuth: true },
  },
  {
    path: '/portal/submissions',
    name: 'portal-submissions',
    component: () => import('@/views/PortalSubmissionsView.vue'),
    meta: { title: '提交成果', requiresAuth: true },
  },
  {
    path: '/governance/submissions',
    name: 'submission-queue',
    component: () => import('@/views/SubmissionQueueView.vue'),
    meta: { title: '待审成果', requiresAuth: true },
  },
  {
    path: '/review/relations',
    name: 'review-relations',
    component: () => import('@/views/RelationReviewView.vue'),
    meta: { title: '关系争议裁决', requiresAuth: true },
  },
  {
    path: '/todos',
    name: 'todos',
    component: () => import('@/views/TodosView.vue'),
    meta: { title: '待办', requiresAuth: true },
  },
  {
    path: '/notifications',
    name: 'notifications',
    component: () => import('@/views/NotificationsView.vue'),
    meta: { title: '通知', requiresAuth: true },
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
    path: '/governance/citations',
    name: 'citation-ops',
    component: () => import('@/views/CitationOpsView.vue'),
    meta: { title: '引用网络采集', requiresAuth: true },
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
    path: '/announcements',
    name: 'announcements',
    component: () => import('@/views/AnnouncementView.vue'),
    meta: { title: '系统公告', requiresAuth: true },
  },
  {
    path: '/favorites',
    name: 'favorites',
    component: () => import('@/views/FavoritesView.vue'),
    meta: { title: '我的收藏', requiresAuth: true },
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
