/**
 * 三个服务的接口封装。
 *
 * 分层原则：一个后端服务的端点集中在一个对象里，且只做「路径 + 参数 + 类型」的搬运，
 * 不放界面判断。界面需要「这个错误该怎么办」时读 ApiError 上的语义 getter，而不是在这里
 * 把错误翻译成文案——文案属于视图，语义属于接口层。
 */

import { request } from './http'
import type {
  AnalyticsOverview,
  ClaimRequest,
  ConsistencyView,
  CooperationGraph,
  Facets,
  OutboxHealth,
  PathResult,
  PublicAchievementDetail,
  ScholarPage,
  SearchOptions,
  SearchPage,
  SessionView,
} from './types'

/* --------------------------- query-service（公开读） --------------------------- */

export interface AchievementQuery {
  q?: string
  type?: string
  yearFrom?: number
  yearTo?: number
  topic?: string
  organization?: string
  dataNature?: string
  sort?: string
  page?: number
  size?: number
  queryVersion?: string
}

export const queryApi = {
  searchAchievements(params: AchievementQuery) {
    return request<SearchPage>('query', '/api/v1/public/search/achievements', {
      query: { ...params },
    })
  },

  searchScholars(params: { q?: string; dataNature?: string; sort?: string; page?: number; size?: number }) {
    return request<ScholarPage>('query', '/api/v1/public/search/scholars', { query: { ...params } })
  },

  facets(dataNature?: string, limit = 50) {
    return request<Facets>('query', '/api/v1/public/search/facets', { query: { dataNature, limit } })
  },

  options() {
    return request<SearchOptions>('query', '/api/v1/public/search/options')
  },

  analyticsOverview(dataNature?: string) {
    return request<AnalyticsOverview>('query', '/api/v1/public/analytics/overview', {
      query: { dataNature },
    })
  },

  graph(params: {
    scholarId: string
    depth?: number
    maxNodes?: number
    dataNature?: string
    topic?: string
    organization?: string
  }) {
    return request<CooperationGraph>('query', '/api/v1/public/network/graph', { query: { ...params } })
  },

  shortestPath(params: { from: string; to: string; dataNature?: string }) {
    return request<PathResult>('query', '/api/v1/public/network/path', { query: { ...params } })
  },

  consistency() {
    return request<ConsistencyView>('query', '/api/v1/public/consistency')
  },
}

/* ------------------------------- core-service ------------------------------- */

export const authApi = {
  csrf() {
    return request<{ headerName?: string }>('core', '/api/v1/auth/csrf')
  },

  register(body: { username: string; password: string; displayName: string; email?: string }) {
    return request<Record<string, unknown>>('core', '/api/v1/auth/register', { method: 'POST', body })
  },

  login(body: { username: string; password: string }) {
    return request<SessionView>('core', '/api/v1/auth/login', { method: 'POST', body })
  },

  logout() {
    return request<Record<string, unknown>>('core', '/api/v1/auth/logout', { method: 'POST' })
  },

  /**
   * 读取当前会话。
   *
   * 未登录时后端返回 401，这不是异常而是正常状态，因此这里把 401 归一为 null，
   * 让调用方只需要判断「有没有会话」，而不用区分「没登录」与「服务坏了」。
   */
  async me(): Promise<SessionView | null> {
    try {
      return await request<SessionView>('core', '/api/v1/auth/me')
    } catch (error) {
      const status = (error as { httpStatus?: number }).httpStatus
      if (status === 401) return null
      throw error
    }
  },
}

export const publicApi = {
  achievement(achievementId: string) {
    return request<PublicAchievementDetail & Record<string, unknown>>(
      'core',
      `/api/v1/public/achievements/${achievementId}`,
    )
  },

  scholar(scholarId: string) {
    return request<Record<string, unknown>>('core', `/api/v1/public/scholars/${scholarId}`)
  },
}

export const accountApi = {
  myClaims() {
    return request<ClaimRequest[]>('core', '/api/v1/account/claims')
  },

  submitClaim(body: { scholarId: string; statement: string }) {
    return request<Record<string, unknown>>('core', '/api/v1/account/claims', {
      method: 'POST',
      body,
    })
  },

  /**
   * 上传证明材料。
   *
   * 这里不能用 JSON：证据是二进制文件。但 http.ts 的封装只发 JSON，所以这个方法自带 fetch，
   * 唯一需要重复的是 CSRF 头——从 Cookie 读值。重复两行换取「上传走 multipart、其余走 JSON」
   * 的清晰边界，比在通用封装里塞一个 multipart 分支更容易看懂。
   *
   * 只发 file 一个 part：后端只绑定 @RequestPart("file")，多发的 part 会被丢掉，
   * 发一个「以为有用但实际被忽略」的说明字段，会让界面误以为说明已经保存。
   */
  async uploadEvidence(claimId: string, file: File) {
    const token = document.cookie
      .split(';')
      .map((part) => part.trim())
      .find((part) => part.startsWith('XSRF-TOKEN='))
    const form = new FormData()
    form.append('file', file)
    const response = await fetch(
      `/core/api/v1/account/claims/${encodeURIComponent(claimId)}/evidence`,
      {
        method: 'POST',
        credentials: 'same-origin',
        headers: token ? { 'X-XSRF-TOKEN': decodeURIComponent(token.slice('XSRF-TOKEN='.length)) } : {},
        body: form,
      },
    )
    const text = await response.text()
    const envelope = text ? JSON.parse(text) : {}
    if (!response.ok || (envelope.code && envelope.code !== 'OK')) {
      throw new Error(envelope.message || `上传失败：HTTP ${response.status}`)
    }
    return envelope.data as Record<string, unknown>
  },

  myChanges() {
    return request<Record<string, unknown>[]>('core', '/api/v1/account/portal-changes')
  },

  submitChange(body: { scholarId: string; fieldName: string; proposedValue: string }) {
    return request<Record<string, unknown>>('core', '/api/v1/account/portal-changes', {
      method: 'POST',
      body,
    })
  },

  myPortals() {
    return request<Record<string, unknown>[]>('core', '/api/v1/account/portals')
  },
}

export const adminApi = {
  overview() {
    return request<Record<string, unknown>>('core', '/api/v1/admin/core/overview')
  },

  accounts() {
    return request<Record<string, unknown>[]>('core', '/api/v1/admin/core/accounts')
  },

  disableAccount(accountId: string, reason: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/accounts/${accountId}/disable`,
      { method: 'POST', body: { reason } },
    )
  },

  grantRole(accountId: string, role: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/accounts/${accountId}/roles`,
      { method: 'POST', body: { role } },
    )
  },

  pendingAchievements(params: { page?: number; size?: number }) {
    return request<Record<string, unknown>[]>('core', '/api/v1/admin/core/achievements/pending', {
      query: params,
    })
  },

  confirmAchievement(achievementId: string, reason: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/achievements/${achievementId}/confirm`,
      { method: 'POST', body: { reason } },
    )
  },

  withdrawAchievement(achievementId: string, reason: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/achievements/${achievementId}/withdraw`,
      { method: 'POST', body: { reason } },
    )
  },

  provenance(achievementId: string) {
    return request<Record<string, unknown>[]>(
      'core',
      `/api/v1/admin/core/achievements/${achievementId}/provenance`,
    )
  },

  sources() {
    return request<Record<string, unknown>[]>('core', '/api/v1/admin/core/sources')
  },

  audit(params: { targetType?: string; targetId?: string; actorAccountId?: string; page?: number; size?: number }) {
    return request<Record<string, unknown>[]>('core', '/api/v1/admin/core/audit', { query: params })
  },

  claims(params: { page?: number; size?: number } = {}) {
    return request<Record<string, unknown>[]>('core', '/api/v1/admin/core/claims', { query: params })
  },

  decideClaim(claimId: string, decision: string, reason: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/claims/${claimId}/decision`,
      { method: 'POST', body: { decision, reason } },
    )
  },

  portalChanges(params: { page?: number; size?: number } = {}) {
    return request<Record<string, unknown>[]>('core', '/api/v1/admin/core/portal-changes', {
      query: params,
    })
  },

  decidePortalChange(changeId: string, approve: boolean, reason: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/portal-changes/${changeId}/decision`,
      { method: 'POST', body: { approve, reason } },
    )
  },

  governanceCandidates(params: { page?: number; size?: number } = {}) {
    return request<Record<string, unknown>[]>(
      'core',
      '/api/v1/admin/core/governance/candidates',
      { query: params },
    )
  },

  resolveCandidate(candidateId: string, body: { action: string; survivorId?: string; reason: string }) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/governance/candidates/${candidateId}/resolve`,
      { method: 'POST', body },
    )
  },

  anomalies(params: { page?: number; size?: number } = {}) {
    return request<Record<string, unknown>[]>('core', '/api/v1/admin/core/governance/anomalies', {
      query: params,
    })
  },

  resolveAnomaly(anomalyId: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/governance/anomalies/${anomalyId}/resolve`,
      { method: 'POST' },
    )
  },

  mergeAchievements(body: { survivorId: string; mergedId: string; reason: string }) {
    return request<Record<string, unknown>>('core', '/api/v1/admin/core/governance/achievements/merge', {
      method: 'POST',
      body,
    })
  },

  mergeScholars(body: { survivorId: string; mergedId: string; reason: string }) {
    return request<Record<string, unknown>>('core', '/api/v1/admin/core/governance/scholars/merge', {
      method: 'POST',
      body,
    })
  },

  /**
   * 登记（或覆盖）来源的权威配置。
   *
   * licenseReviewed 与 enabled 必须分开传：许可未复核而 enabled=true 是一个服务端会拒绝的组合，
   * 前端不替调用方把它纠正成 false——那样会让人以为「已经启用成功了」。
   */
  registerSource(body: {
    sourceCode: string
    displayName: string
    licenseScope?: string
    baseUrl?: string
    allowedFields: string[]
    enabled: boolean
    licenseReviewed: boolean
  }) {
    return request<Record<string, unknown>>('core', '/api/v1/admin/core/sources', {
      method: 'POST',
      body,
    })
  },

  /** 收紧来源字段白名单。收紧是即时生效的，放回被移除的字段不属于本端点职责。 */
  setAllowedFields(sourceId: string, allowedFields: string[], reason: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/sources/${sourceId}/allowed-fields`,
      { method: 'POST', body: { allowedFields, reason } },
    )
  },

  ledger(entityId: string, limit = 50) {
    return request<Record<string, unknown>[]>('core', '/api/v1/admin/core/governance/ledger', {
      query: { entityId, limit },
    })
  },
}

/* ---------------------------- ingestion-service ---------------------------- */

export const ingestionApi = {
  capabilities() {
    return request<Record<string, unknown>>('ingest', '/api/v1/admin/ingestion/capabilities')
  },

  sources() {
    return request<{ items: Record<string, unknown>[] }>('ingest', '/api/v1/admin/ingestion/sources')
  },

  syncSource(sourceId: string) {
    return request<Record<string, unknown>>(
      'ingest',
      `/api/v1/admin/ingestion/sources/${sourceId}/sync`,
      { method: 'POST' },
    )
  },

  setSourceEnabled(sourceId: string, enabled: boolean, reason: string) {
    return request<Record<string, unknown>>(
      'ingest',
      `/api/v1/admin/ingestion/sources/${sourceId}/enabled`,
      { method: 'POST', body: { enabled, reason } },
    )
  },

  jobs() {
    return request<{ items: Record<string, unknown>[] }>('ingest', '/api/v1/admin/ingestion/jobs')
  },

  createJob(body: { sourceId: string; jobKey: string; reason?: string }) {
    return request<Record<string, unknown>>('ingest', '/api/v1/admin/ingestion/jobs', {
      method: 'POST',
      body,
    })
  },

  runJob(jobId: string, maxRounds = 2) {
    return request<Record<string, unknown>>(
      'ingest',
      `/api/v1/admin/ingestion/jobs/${jobId}/run`,
      { method: 'POST', query: { maxRounds } },
    )
  },

  statistics(jobId: string) {
    return request<Record<string, unknown>>(
      'ingest',
      `/api/v1/admin/ingestion/jobs/${jobId}/statistics`,
    )
  },

  batches(jobId: string) {
    return request<Record<string, unknown>>(
      'ingest',
      `/api/v1/admin/ingestion/jobs/${jobId}/batches`,
    )
  },

  logs(jobId: string) {
    return request<Record<string, unknown>>('ingest', `/api/v1/admin/ingestion/jobs/${jobId}/logs`)
  },

  pauseJob(jobId: string, reason: string) {
    return request<Record<string, unknown>>(
      'ingest',
      `/api/v1/admin/ingestion/jobs/${jobId}/pause`,
      { method: 'POST', body: { reason } },
    )
  },

  resumeJob(jobId: string, reason: string) {
    return request<Record<string, unknown>>(
      'ingest',
      `/api/v1/admin/ingestion/jobs/${jobId}/resume`,
      { method: 'POST', body: { reason } },
    )
  },
}

/* ----------------------- core-service 内部运维视图（读） ----------------------- */

/**
 * 内部运维端点由服务令牌保护，浏览器侧没有令牌，因此访问必然 403。
 *
 * 这里仍然把这几个端点封装出来而不是直接写死一个「不可用」的占位：前端要展示的是
 * 「这些数字存在、但需要运维凭据才能读」这一事实，而不是伪造一组看起来正常的数字。
 * 403 会被页面识别为「未授权读取」，与「服务不可达」区分开。
 */
export const opsApi = {
  outboxHealth() {
    return request<OutboxHealth>('core', '/internal/v1/outbox/deliveries', { csrf: false })
  },

  deadLetters(consumerId: string, limit = 50) {
    return request<Record<string, unknown>>('core', '/internal/v1/outbox/dead-letters', {
      query: { consumerId, limit },
      csrf: false,
    })
  },

  requeueFailed(consumerId: string) {
    return request<Record<string, unknown>>('core', '/internal/v1/outbox/requeue-failed', {
      method: 'POST',
      query: { consumerId },
      csrf: false,
    })
  },
}
