/**
 * 三个服务的接口封装。
 *
 * 分层原则：一个后端服务的端点集中在一个对象里，且只做「路径 + 参数 + 类型」的搬运，
 * 不放界面判断。界面需要「这个错误该怎么办」时读 ApiError 上的语义 getter，而不是在这里
 * 把错误翻译成文案——文案属于视图，语义属于接口层。
 */

import { request, requestEnvelope } from './http'
import type {
  AnalyticsOverview,
  CitationBatch,
  CitationBundle,
  CitationNetworkView,
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
  SearchSuggestions,
  SessionView,
  TopicInsight,
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

  /**
   * 检索结果导出（CSV 文件本体）。与在线检索同一套资格条件与口径归一；
   * 命中超过服务端上限时返回 400，message 会说明超限原因。
   */
  exportAchievementsCsv(params: Omit<AchievementQuery, 'page' | 'size' | 'queryVersion'>) {
    return requestEnvelope<Blob>('query', '/api/v1/public/search/achievements/export', {
      query: { ...params },
      responseType: 'blob',
    })
  },

  facets(dataNature?: string, limit = 50) {
    return request<Facets>('query', '/api/v1/public/search/facets', { query: { dataNature, limit } })
  },

  options() {
    return request<SearchOptions>('query', '/api/v1/public/search/options')
  },

  /**
   * 检索框建议。
   *
   * 与检索共用同一道公开资格屏障与数据性质口径，因此必须把当前口径传上去：漏传会让演示口径下
   * 的建议与真实口径下的结果对不上，用户点进详情才发现目标不存在。
   */
  suggest(q: string, dataNature?: string, limit = 6) {
    return request<SearchSuggestions>('query', '/api/v1/public/search/suggest', {
      query: { q, dataNature, limit },
    })
  },

  analyticsOverview(dataNature?: string) {
    return request<AnalyticsOverview>('query', '/api/v1/public/analytics/overview', {
      query: { dataNature },
    })
  },

  /**
   * 关键词热点（FR13）。
   *
   * 年份窗用可空整数表达「不限」：传 undefined 就是不限，不传 "all" 这类哨兵值。
   */
  keywordHotspots(params: {
    dataNature?: string
    yearFrom?: number | null
    yearTo?: number | null
    limit?: number
  }) {
    return request<Record<string, unknown>>('query', '/api/v1/public/analytics/keywords', {
      query: { ...params },
    })
  },

  topicInsight(topicId: string, dataNature?: string) {
    return request<TopicInsight>('query', `/api/v1/public/analytics/topics/${encodeURIComponent(topicId)}`, {
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
  /**
   * 本人修改口令。
   *
   * 服务端会让 authVersion +1 并把当前会话续到新版本，其余设备上的会话立即失效。
   * 成功后调用方应重新拉一次 /auth/me：会话里的 mustChangePassword 与 authVersion 都变了。
   */
  changePassword(body: { currentPassword: string; newPassword: string }) {
    return request<Record<string, unknown>>('core', '/api/v1/auth/password', {
      method: 'POST',
      body,
    })
  },

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

  /**
   * 相关成果推荐。
   *
   * 四条信号：同作者、共被引、共享参考文献、同主题。返回里带 evidence 与 reasons，
   * 页面必须把理由展示出来——只有一个分数时读者无法判断这是「同一批作者」还是
   * 「同一个大主题下的随机一篇」。
   */
  relatedAchievements(achievementId: string, limit = 8) {
    return request<RelatedAchievementsView>(
      'core',
      `/api/v1/public/achievements/${encodeURIComponent(achievementId)}/related`,
      { query: { limit } },
    )
  },

  /**
   * 学者的公开收藏数。
   *
   * 匿名访问档案页时只能走这个接口：{@code /account/favorites/{id}} 需要会话，
   * 未登录调用会得到 401，控制台里凭空多一条失败请求，用户也会以为页面坏了。
   */
  scholarFavoriteCount(scholarId: string) {
    return request<{ scholarId: string; favoriteCount: number; note?: string }>(
      'core',
      `/api/v1/public/scholars/${encodeURIComponent(scholarId)}/favorite-count`,
    )
  },

  /**
   * 引用导出：一次取全四种格式。
   *
   * 四种格式一次取完而不是按标签页各发一次请求：来回切换时逐次请求会让每个标签页都闪一次加载态，
   * 而且四次请求之间若成果恰好被撤稿，读者会在同一屏里看到两种结论。
   */
  citations(achievementId: string) {
    return request<CitationBundle>('core', `/api/v1/public/achievements/${achievementId}/citations`)
  },

  /**
   * 引用网络：本篇引用了谁、谁引用了本篇。
   *
   * 与「引用量」是两个事实：引用量是来源给的一个数字，引用网络是一条条边，且只覆盖
   * 已采集参考文献的那部分成果。接口把 coverage 与 platform 一起返回，就是为了让页面
   * 必须把覆盖率说出来，而不是把 0 条边显示成「没有被引用」。
   */
  citationNetwork(achievementId: string) {
    return request<CitationNetworkView>(
      'core',
      `/api/v1/public/achievements/${achievementId}/citation-network`,
    )
  },

  /**
   * 批量导出预览：一组成果 → 一份文献库文件。
   *
   * 读操作用 GET：平台的一次性 CSRF 令牌在校验通过后立即失效，让「看一眼会得到什么」去消耗写令牌，
   * 会让紧接着的下载莫名收到 403。下载走响应里的 downloadUrl（同样是 GET，可被浏览器直接导航）。
   */
  batchCitations(ids: string[], format: string) {
    return request<CitationBatch>('core', '/api/v1/public/citations/batch', {
      query: { ids, format },
    })
  },

  /**
   * 可纠错字段清单。
   *
   * 由服务端给出而不是写死在页面上：字段标签在服务端只有一处定义，两处各写一份早晚会漂移成
   * 「界面写作人、待办写参与人」，而处置人看到的只是两个说法。
   */
  correctionFields() {
    return request<{ code: string; label: string }[]>('core', '/api/v1/public/correction-fields')
  },

  /**
   * 提交一条纠错报告。无需登录。
   *
   * 响应只回执「受理了、这是第几次报告」，不含任何治理内部信息（积压总数、其它报告原文）。
   */
  reportCorrection(
    achievementId: string,
    body: { field: string; message: string; contact?: string },
  ) {
    return request<{
      accepted: boolean
      reportId: string
      reportCount: number
      field: string
      fieldLabel: string
      merged: boolean
      note: string
    }>('core', `/api/v1/public/achievements/${achievementId}/correction-reports`, {
      method: 'POST',
      body,
    })
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

  /** 补证后重新提交。只允许 NEED_MORE_EVIDENCE，claimId 不变（服务端还会再查一次状态）。 */
  resubmitClaim(claimId: string, statement: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/account/claims/${encodeURIComponent(claimId)}/resubmit`,
      { method: 'POST', body: { statement } },
    )
  },

  /**
   * 上传证明材料。
   *
   * 走统一的 multipart 通道（{@code form} 选项），而不是自带 fetch：自带的版本只从 Cookie 读
   * 令牌、从不重取，而 core 的令牌是**一次性**的——同一页面连传两份材料时，第二份必然 403，
   * 且报错文案看起来像权限不足。统一通道会重新取令牌并把写请求排队。
   *
   * 只发 file 一个 part：后端只绑定 @RequestPart("file")，多发的 part 会被丢掉，
   * 发一个「以为有用但实际被忽略」的说明字段，会让界面误以为说明已经保存。
   */
  uploadEvidence(claimId: string, file: File) {
    const form = new FormData()
    form.append('file', file)
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/account/claims/${encodeURIComponent(claimId)}/evidence`,
      { method: 'POST', form },
    )
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

  /** 我的成果：门户授权下的参与关系，含当前状态与可执行动作（由服务端判定）。 */
  contributions(scholarId: string, limit = 50) {
    return request<Record<string, unknown>[]>(
      'core',
      `/api/v1/account/contributions?scholarId=${encodeURIComponent(scholarId)}&limit=${limit}`,
    )
  },

  /**
   * 导出「我的成果」为 CSV 文件本体。
   *
   * 与列表接口不同，导出不分页：一份缺行的成果清单当事人可能一年后才发现。超出行数上限时
   * 服务端返回 400，message 会说明原因（不静默截断）。
   */
  exportContributionsCsv(scholarId: string) {
    return requestEnvelope<Blob>('core', '/api/v1/account/contributions/export', {
      query: { scholarId },
      responseType: 'blob',
    })
  },

  /**
   * 账号级数据导出（JSON）。
   *
   * 与「我的成果导出 CSV」不同：这个文件是「平台关于我的全部记录」，含认领、变更、提交、
   * 通知、参与关系。不含口令哈希与会话信息，服务端已过滤。
   */
  exportAccountData() {
    return requestEnvelope<Blob>('core', '/api/v1/account/export', { responseType: 'blob' })
  },

  /** 当前待处理的注销申请。没有申请时 open=false。 */
  closureStatus() {
    return request<{ open: boolean; id?: string; effectiveAt?: string; note?: string }>(
      'core',
      '/api/v1/account/closure',
    )
  },

  /** 提交注销申请。理由必填；进入冷静期，期间可撤销。 */
  requestClosure(reason: string) {
    return request<{ id: string; status: string; effectiveAt: string; note?: string }>(
      'core',
      '/api/v1/account/closure',
      { method: 'POST', body: { reason } },
    )
  },

  cancelClosure(requestId: string) {
    return request<{ cancelled: number }>(
      'core',
      `/api/v1/account/closure/${encodeURIComponent(requestId)}/cancel`,
      { method: 'POST' },
    )
  },

  /** 学者收藏：状态含公开收藏数；未登录时 favorited 为 null。 */
  favoriteStatus(scholarId: string) {
    return request<{ scholarId: string; favorited: boolean | null; favoriteCount: number }>(
      'core',
      `/api/v1/account/favorites/${encodeURIComponent(scholarId)}`,
    )
  },

  favorite(scholarId: string) {
    return request<{ favorited: boolean; favoriteCount: number; note?: string }>(
      'core',
      `/api/v1/account/favorites/${encodeURIComponent(scholarId)}`,
      { method: 'POST' },
    )
  },

  unfavorite(scholarId: string) {
    return request<{ favorited: boolean; favoriteCount: number; note?: string }>(
      'core',
      `/api/v1/account/favorites/${encodeURIComponent(scholarId)}`,
      { method: 'DELETE' },
    )
  },

  myFavorites(limit = 50) {
    return request<{ items: FavoriteScholarView[]; count: number; note?: string }>(
      'core',
      '/api/v1/account/favorites',
      { query: { limit } },
    )
  },

  /**
   * 本人确认参与关系。
   *
   * 这里不预判结果：确认能否成立取决于当前关系状态（待核验、争议、已排除各有不同结论），
   * 由服务端回答，界面按返回的状态与说明展示。
   */
  confirmContribution(contributionId: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/account/contributions/${encodeURIComponent(contributionId)}/confirm`,
      { method: 'POST' },
    )
  },

  disputeContribution(contributionId: string, reason: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/account/contributions/${encodeURIComponent(contributionId)}/dispute`,
      { method: 'POST', body: { reason } },
    )
  },

  withdrawDispute(contributionId: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/account/contributions/${encodeURIComponent(contributionId)}/withdraw-dispute`,
      { method: 'POST' },
    )
  },

  // ---------------------------------------------------------------------------
  // 学者门户资料自助维护
  // ---------------------------------------------------------------------------

  /** 可编辑视图：当前值 + 置顶清单 + 置顶上限（不把 3 写进前端）。 */
  scholarProfile(scholarId: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/account/scholars/${encodeURIComponent(scholarId)}/profile`,
    )
  },

  /**
   * 修改简介与主页链接。
   *
   * 字段缺省表示「不改」，空串表示「清空」——所以这里不能把 undefined 折叠成空串，
   * 否则「只改简介」会把主页一起清掉。
   */
  updateScholarProfile(
    scholarId: string,
    body: { researchSummary?: string; homepageUrl?: string },
  ) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/account/scholars/${encodeURIComponent(scholarId)}/profile`,
      { method: 'PUT', body },
    )
  },

  /** 整体替换置顶顺序；空数组表示清空。 */
  updateScholarFeatured(scholarId: string, achievementIds: string[]) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/account/scholars/${encodeURIComponent(scholarId)}/featured`,
      { method: 'PUT', body: { achievementIds } },
    )
  },

  uploadScholarAvatar(scholarId: string, file: File) {
    const form = new FormData()
    form.append('file', file)
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/account/scholars/${encodeURIComponent(scholarId)}/avatar`,
      { method: 'POST', form },
    )
  },

  removeScholarAvatar(scholarId: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/account/scholars/${encodeURIComponent(scholarId)}/avatar`,
      { method: 'DELETE' },
    )
  },

  // ---------------------------------------------------------------------------
  // 成果自助提交
  // ---------------------------------------------------------------------------

  /**
   * 提交一条成果。
   *
   * 带着幂等键：网络重试不该建出两条一样的提交。键由前端生成一次并在重试时复用——
   * 每次重试都换一个新键，等于没有幂等。
   */
  submitAchievement(body: SubmitAchievementBody, idempotencyKey?: string) {
    return request<SubmissionView>('core', '/api/v1/account/submissions', {
      method: 'POST',
      body,
      headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
    })
  },

  mySubmissions(limit = 20) {
    return request<{ items: SubmissionView[]; note?: string }>('core', '/api/v1/account/submissions', {
      query: { limit },
    })
  },

  withdrawSubmission(submissionId: string) {
    return request<SubmissionView>(
      'core',
      `/api/v1/account/submissions/${encodeURIComponent(submissionId)}/withdraw`,
      { method: 'POST' },
    )
  },

  /**
   * 从 BibTeX / RIS 文本批量导入。
   *
   * dryRun=true 只解析预览，不写库；确认后再用 dryRun=false 真正提交。
   * 服务端逐条独立入库：失败的条目不影响已成功的，返回值里逐条给结果。
   */
  importSubmissions(body: ImportSubmissionsBody) {
    return request<SubmissionImportResult>('core', '/api/v1/account/submissions/import', {
      method: 'POST',
      body,
    })
  },
}

/** 批量导入请求体。format 留空或 AUTO 时由服务端按内容判断。 */
export interface ImportSubmissionsBody {
  format?: 'AUTO' | 'BIBTEX' | 'RIS'
  content: string
  scholarId?: string
  dryRun?: boolean
}

/** 单条导入结果。status: PREVIEW / CREATED / DUPLICATE_SUSPECTED / SKIPPED / FAILED。 */
export interface SubmissionImportItem {
  index: number
  citationKey?: string
  title: string
  status: string
  submissionId?: string
  duplicateKind?: string
  duplicateNote?: string
  warnings?: string[]
  error?: string
}

export interface SubmissionImportResult {
  format: string
  parsed: number
  created: number
  skipped: number
  failed: number
  dryRun: boolean
  items: SubmissionImportItem[]
  skippedNotes: string[]
  note?: string
}

/** 收藏的学者。 */
export interface FavoriteScholarView {
  scholarId: string
  displayName: string
  organizationName?: string
  favoritedAt?: string
}

/** 相关成果的一条候选。 */
export interface RelatedAchievementItem {
  achievementId: string
  title: string
  publicationYear?: number
  venue?: string
  doi?: string
  citationCount?: number
  score: number
  evidence: {
    sharedAuthors: number
    coCitedBy: number
    sharedReferences: number
    sharedTopics: number
  }
  reasons: string[]
}

export interface RelatedAchievementsView {
  achievementId: string
  redirectedFrom?: string
  items: RelatedAchievementItem[]
  count: number
  method?: string
  note?: string
  emptyReason?: string
}

/** 自助提交表单。类型目前只支持论文（其余类型需要申请号等必填事实，走治理端录入）。 */
export interface SubmitAchievementBody {
  type?: string
  title: string
  doi?: string
  venue?: string
  publicationYear?: number | null
  abstractText?: string
  authorsRaw?: string
  scholarId?: string
}

/** 提交件视图。`duplicate*` 只在查重命中时出现（服务端 non_null 序列化会省略空字段）。 */
export interface SubmissionView {
  id: string
  type: string
  typeLabel?: string
  title: string
  doi?: string
  venue?: string
  publicationYear?: number
  abstractText?: string
  authorsRaw?: string
  scholarId?: string
  status: string
  statusLabel?: string
  decisionReason?: string
  decidedAt?: string
  achievementId?: string
  duplicateOf?: string
  duplicateKind?: string
  duplicateNote?: string
  duplicateTitle?: string
  submittedAt?: string
  open?: boolean
}

export interface NotificationItem {
  id: string
  kind: string
  title: string
  body: string
  /** 非必填：撤权类通知指向一批成果，服务端不下发 ref 字段（non_null 序列化会省略 null）。 */
  refType?: string
  refId?: string
  createdAt: string
  read: boolean
}

export const notificationApi = {
  list(
    params: { unreadOnly?: boolean; kind?: string; limit?: number; offset?: number } = {},
  ) {
    return request<{ items: NotificationItem[]; unreadOnly: boolean; kind?: string }>(
      'core',
      '/api/v1/notifications',
      { query: { ...params } },
    )
  },

  unreadCount() {
    return request<{ unreadCount: number }>('core', '/api/v1/notifications/unread-count')
  },

  /** 本人列表中出现过的类型，用于筛选下拉。 */
  kinds() {
    return request<{ kinds: string[] }>('core', '/api/v1/notifications/kinds')
  },

  markRead(ids: string[]) {
    return request<{ markedRead: number }>('core', '/api/v1/notifications/read', {
      method: 'POST',
      body: { ids },
    })
  },

  /** 全部已读：服务端把本人的全部未读置为已读，不只是当前这一页。 */
  markAllRead() {
    return request<{ markedRead: number }>('core', '/api/v1/notifications/read-all', {
      method: 'POST',
    })
  },

  /** 从本人列表移除（归档，不物理删除）。 */
  archive(ids: string[]) {
    return request<{ archived: number; note?: string }>('core', '/api/v1/notifications/archive', {
      method: 'POST',
      body: { ids },
    })
  },

  /** 清空已读：只移除已读的，未读不动。 */
  archiveAllRead() {
    return request<{ archived: number; note?: string }>(
      'core',
      '/api/v1/notifications/archive-read',
      { method: 'POST' },
    )
  },
}

export const adminApi = {
  /** 争议关系裁决队列：只列当前处于 DISPUTED 的参与关系。 */
  disputedContributions(limit = 50) {
    return request<Record<string, unknown>[]>(
      'core',
      `/api/v1/admin/core/contributions/disputed?limit=${limit}`,
    )
  },

  /**
   * 裁决一条参与关系。
   *
   * decision 只有三种：EXCLUDE（判定不成立）、CONFIRM（核验后确认）、RESTORE（撤销此前结论
   * 回到自动关联）。服务端会按当前状态拒绝不相符的结论，这里不重复推断。
   */
  resolveContribution(contributionId: string, decision: string, reason: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/contributions/${encodeURIComponent(contributionId)}/resolution`,
      { method: 'POST', body: { decision, reason } },
    )
  },

  /**
   * 引用网络覆盖率与待补规模。
   *
   * 三个数字一起看才有意义：已采集篇数决定边数上限，可见论文总数决定覆盖率，待补篇数是还要跑多久。
   */
  citationStats(params: { search?: string; topicId?: string } = {}) {
    return request<{
      observedWorks: number
      notFoundWorks: number
      publicPapers: number
      coveragePercent: number | null
      pendingWorks: number
      note: string
    }>('core', '/api/v1/admin/core/citations/stats', { query: { ...params } })
  },

  /**
   * 触发一轮参考文献补水（走采集服务的来源通道）。
   *
   * provider 默认 AUTO：先试 OpenAlex（批量、但受每日共享额度限制），额度不可用时自动改用
   * Crossref（免费、逐篇）。回执里的 stoppedReason 与 creditsRemaining 是判断「补完了还是被额度挡住了」的依据。
   */
  hydrateCitations(
    params: { limit?: number; search?: string; topicId?: string; provider?: string } = {},
  ) {
    return request<{
      target: number
      worksFetched: number
      worksMatched: number
      referencesWritten: number
      resolvedEdges: number
      externalReferences: number
      notFoundWorks: number
      rounds: number
      provider: string
      creditsRemaining: number | null
      stoppedReason: string
      note: string
    }>('ingest', '/api/v1/admin/ingestion/citations/hydrate', {
      method: 'POST',
      query: {
        limit: params.limit ?? 200,
        ...(params.search ? { search: params.search } : {}),
        ...(params.topicId ? { topicId: params.topicId } : {}),
        provider: params.provider ?? 'AUTO',
      },
    })
  },

  /**
   * 重新解析外部引用：新入库成果登记了来源作品标识后，原先「解析不到」的引用可以升级为站内边。
   * 这一步只读标识注册表，不访问外网、不消耗来源配额。
   */
  resolveCitations(limit = 5000) {
    return request<{ keysScanned: number; keysResolved: number; rowsResolved: number }>(
      'core',
      `/api/v1/admin/core/citations/resolve?limit=${limit}`,
      { method: 'POST' },
    )
  },

  /** 自助提交的待审队列。每条都带查重提示（若有），审核员不必自己再去搜一遍。 */
  submissions(params: { page?: number; size?: number } = {}) {
    return requestEnvelope<SubmissionView[]>('core', '/api/v1/admin/core/submissions', {
      query: { page: params.page ?? 1, size: params.size ?? 20 },
    })
  },

  /**
   * 裁决一条提交。
   *
   * decision 只接受 APPROVE / REJECT / NEED_MORE_INFO；驳回与要求补充必须给原因，
   * 服务端会拒绝空白原因（没有原因的驳回，提交人只能反复试）。
   */
  decideSubmission(submissionId: string, decision: string, reason: string) {
    return request<{ status: string; achievementId?: string; message: string }>(
      'core',
      `/api/v1/admin/core/submissions/${encodeURIComponent(submissionId)}/decision`,
      { method: 'POST', body: { decision, reason } },
    )
  },

  overview() {
    return request<Record<string, unknown>>('core', '/api/v1/admin/core/overview')
  },

  /**
   * 最近的学术诚信声明。
   *
   * activeOnly 默认 true：治理台首先要看的是「现在有哪些成果被标了撤稿/更正」，撤销历史另查。
   * 这不是权限问题，而是按用途分开——把两类混在一张表里，看的人要自己分辨哪条还算数。
   */
  integrityNotices(params: { limit?: number; activeOnly?: boolean } = {}) {
    const query = new URLSearchParams()
    if (params.limit) query.set('limit', String(params.limit))
    if (params.activeOnly !== undefined) query.set('activeOnly', String(params.activeOnly))
    const suffix = query.toString() ? `?${query.toString()}` : ''
    return request<Record<string, unknown>>('core', `/api/v1/admin/core/integrity-notices${suffix}`)
  },

  /** 某条成果的声明汇总与明细（含已撤销）。 */
  achievementIntegrity(achievementId: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/achievements/${encodeURIComponent(achievementId)}/integrity-notices`,
    )
  },

  /**
   * 登记一条声明。
   *
   * 传字符串类型而不是枚举：合法值由服务端领域枚举解析，报错时能直接说出「只能是这三者之一」；
   * 前端再维护一份枚举，迟早会与服务端不一致，而这种不一致表现为「选项里没有的那个值被拒」。
   */
  registerIntegrityNotice(
    achievementId: string,
    body: {
      noticeType: string
      declaredBy: string
      reason: string
      sourceUrl?: string | null
      announcedOn?: string | null
    },
  ) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/achievements/${encodeURIComponent(achievementId)}/integrity-notices`,
      { method: 'POST', body },
    )
  },

  /** 撤销一条声明，理由必填。 */
  revokeIntegrityNotice(noticeId: string, reason: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/integrity-notices/${encodeURIComponent(noticeId)}/revoke`,
      { method: 'POST', body: { reason } },
    )
  },

  /**
   * 统一待办。
   *
   * 与 overview 分开而不是复用：overview 是「库里有什麼」的计数，todos 是「今天该做什么」的
   * 待办。两者会包含相同的数字，但语义不同——把它们合成一个接口，前端就得自己决定哪个数字
   * 该出现在哪张卡上，而这件事应该由服务端按能力裁剪后给出。
   */
  todos() {
    return request<Record<string, unknown>>('core', '/api/v1/admin/core/todos')
  },

  accounts() {
    return request<Record<string, unknown>[]>('core', '/api/v1/admin/core/accounts')
  },

  /**
   * 重置他人口令。
   *
   * 返回的 temporaryPassword 只出现这一次，服务端不保存明文；调用方必须在界面上把它显示出来
   * 并提示「关闭后无法再次查看」，不能只弹一句「重置成功」。
   */
  resetAccountPassword(accountId: string, reason: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/accounts/${accountId}/password-reset`,
      { method: 'POST', body: { reason } },
    )
  },

  disableAccount(accountId: string, reason: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/accounts/${accountId}/disable`,
      { method: 'POST', body: { reason } },
    )
  },

  /** 发布系统公告：给全体启用账号各写一条通知，返回实际收件账号数。 */
  publishAnnouncement(body: { title: string; body: string; level?: 'INFO' | 'WARNING' }) {
    return request<{ id: string; recipients: number; note?: string }>(
      'core',
      '/api/v1/admin/core/announcements',
      { method: 'POST', body },
    )
  },

  /** 公告发布台账（只用于追溯，不能撤回）。 */
  announcements(limit = 20) {
    return request<{ items: Record<string, unknown>[]; note?: string }>(
      'core',
      '/api/v1/admin/core/announcements',
      { query: { limit } },
    )
  },

  /**
   * 待处理注销申请。
   *
   * 只返回 PENDING：已裁决的申请留在库里供追溯，但治理台需要的是「现在要处理什么」。
   * effectiveAt（冷静期结束时间）由服务端给出，前端不自己算——冷静期天数属服务端规则，
   * 两边各算一次迟早会不一致，而不一致的表现是「界面说能批、服务端拒了」。
   */
  closureRequests(params: { limit?: number; offset?: number } = {}) {
    return request<{ items: Record<string, unknown>[]; total: number; note?: string }>(
      'core',
      '/api/v1/admin/core/closure-requests',
      { query: params },
    )
  },

  /** 裁决一条注销申请：APPROVE 会在后端停用账号并撤销角色，REJECT 只改状态。note 为必填理由。 */
  decideClosure(requestId: string, decision: 'APPROVE' | 'REJECT', note: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/closure-requests/${encodeURIComponent(requestId)}/decide`,
      { method: 'POST', body: { decision, note } },
    )
  },

  grantRole(accountId: string, role: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/accounts/${accountId}/roles`,
      { method: 'POST', body: { role } },
    )
  },

  /**
   * 撤销角色。
   *
   * 理由走 query：DELETE 带正文在部分代理上会被丢掉，而服务端要求理由非空——
   * 丢掉正文会变成「403/400 说不清原因」，而不是一条被拒绝的合法请求。
   */
  revokeRole(accountId: string, role: string, reason: string) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/accounts/${accountId}/roles/${role}`,
      { method: 'DELETE', query: { reason } },
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

  /**
   * 治理工作台候选列表：按可信度排序，支持相似度下限筛选。
   *
   * meta 里带回分档计数与白名单排序键，供页面直接展示，不需要前端再猜一套阈值。
   */
  governanceWorkbench(
    params: { minSimilarity?: number; sort?: string; page?: number; size?: number } = {},
  ) {
    // 用信封版本：分档计数与排序白名单由服务端给出，前端不另立一套阈值。
    return requestEnvelope<Record<string, unknown>[]>(
      'core',
      '/api/v1/admin/core/governance/candidates/workbench',
      { query: params },
    )
  },

  /** 批量判定候选为「不同实体」。不提供批量合并，合并必须逐条指定幸存实体。 */
  batchRejectCandidates(body: { candidateIds: string[]; reason: string }) {
    return request<Record<string, unknown>>(
      'core',
      '/api/v1/admin/core/governance/candidates/batch-reject',
      { method: 'POST', body },
    )
  },

  resolveCandidate(candidateId: string, body: { action: string; survivorId?: string; reason: string }) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/governance/candidates/${candidateId}/resolve`,
      { method: 'POST', body },
    )
  },

  /**
   * 候选改判：把问题转到两端重定向后的最终实体上。
   *
   * 用于「端点已被合并」的候选——它们既不能再合并（重定向不覆盖首次结论），也不该被驳回
   * （驳回等于宣称两端比较过）。返回里带上承接候选的标识与结果状态。
   */
  rebaseCandidate(candidateId: string, body: { reason: string }) {
    return request<Record<string, unknown>>(
      'core',
      `/api/v1/admin/core/governance/candidates/${candidateId}/rebase`,
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

  /**
   * 运行一次数据质量巡检。
   *
   * 只读业务数据、只登记与复核异常，不改任何成果。可重复运行：同一问题在未处理期间只占一条
   * 待办，重复运行刷新计数；已经消失的问题会被服务端自动关闭。返回每一类检测项本次命中的条数。
   */
  scanAnomalies() {
    return request<Record<string, unknown>>(
      'core',
      '/api/v1/admin/core/governance/anomalies/scan',
      { method: 'POST' },
    )
  },

  /**
   * 合并影响预览（只读）。
   *
   * 与 mergeAchievements 分开成一个方法：预览不产生任何写入，把它塞进合并方法里会让
   * 「看一眼」变成一次有副作用的调用。界面上先看后果、再决定是否合并，两步各自独立。
   */
  mergePreview(params: { survivorId: string; mergedId: string }) {
    return request<Record<string, unknown>>(
      'core',
      '/api/v1/admin/core/governance/achievements/merge-preview',
      { query: params },
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

  /**
   * 设定来源的业务更新周期。
   *
   * 三个周期互不相同，这里只改第三个：调度轮询是服务配置，失败退避由任务自己持有。
   * 两个周期都可传 null（表示撤销），此时必须同时给 note（撤销时 note 由服务端沿用旧值）。
   */
  setSourceUpdatePlan(
    sourceId: string,
    body: {
      newRecordIntervalMinutes: number | null
      fieldRefreshIntervalMinutes: number | null
      note?: string
    },
  ) {
    return request<Record<string, unknown>>(
      'ingest',
      `/api/v1/admin/ingestion/sources/${sourceId}/update-plan`,
      { method: 'PUT', body },
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

  /** 停止任务：与暂停不同，停止是终态，没有恢复入口。 */
  stopJob(jobId: string, reason: string) {
    return request<Record<string, unknown>>(
      'ingest',
      `/api/v1/admin/ingestion/jobs/${jobId}/stop`,
      { method: 'POST', body: { reason } },
    )
  },

  /** 失败重试：把 FAILED 任务从检查点放回队列，已交接过的那批不会重复交付。 */
  retryJob(jobId: string, reason: string) {
    return request<Record<string, unknown>>(
      'ingest',
      `/api/v1/admin/ingestion/jobs/${jobId}/retry`,
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
