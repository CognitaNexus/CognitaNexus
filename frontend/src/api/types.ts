/**
 * 后端响应类型。
 *
 * 只声明界面真正会读的字段，不做「把后端 DTO 全量抄一遍」的镜像：抄全之后每次后端加字段都要
 * 动前端类型，而多出来的字段前端并不用；更糟的是镜子会让人误以为类型对齐等于契约对齐。
 * 这里刻意保留 `[key: string]: unknown` 的开放口，读取未声明字段时必须显式断言，从而在代码里
 * 留下「这个字段还没进契约」的痕迹。
 */

export type DataNature = 'REAL' | 'SIMULATED' | 'MIXED' | 'ALL'
export type AchievementKind = 'PAPER' | 'PATENT' | 'PROJECT' | 'AWARD'

/** 作用域说明：随每个读接口返回，供界面标注当前口径。 */
export interface ScopeEnvelope {
  dataNatureScope: DataNature
  scopeNote: string
  publishedRevision: number
  generatedAt: string
  ruleVersion: string
}

/* ------------------------------- 公开：检索 ------------------------------- */

export interface SearchItem {
  id: string
  type: AchievementKind
  title: string
  dataNature: DataNature
  visibility: string
  effectiveStatus: string
  publicationYear: number | null
  doi: string | null
  venue: string | null
  citationCount: number | null
  citationSource: string | null
  authors: string[]
  topics: { topicId: string; canonicalName: string }[]
  relevance: number
}

export interface SearchPage extends ScopeEnvelope {
  items: SearchItem[]
  page: number
  size: number
  total: number
  totalRelation: string
  queryVersion: string
  appliedSort: string
  maxOffset: number
}

export interface ScholarHit {
  scholarId: string
  scholarName: string
  qualifiedAchievementCount: number
  firstYear: number | null
}

export interface ScholarPage extends ScopeEnvelope {
  items: ScholarHit[]
  page: number
  size: number
  total: number
  queryVersion: string
  countingNote: string
}

export interface Facets {
  topics: { topicId: string; canonicalName: string; achievementCount: number }[]
  organizations: { name: string; achievement_count: number }[]
  dataNatureScope: DataNature
  scopeNote: string
}

export interface SearchOptions {
  sorts: string[]
  dataNatures: DataNature[]
  maxPageSize: number
  defaultPageSize: number
  maxOffset: number
  ruleVersion: string
  note: string
}

/* ------------------------------- 公开：统计 ------------------------------- */

export interface AnalyticsOverview {
  totals: {
    achievements: number
    scholarsWithQualifiedRelation: number
    contributions: number
    coauthorPairs: number
    achievementsWithUnknownOrganization: number
  }
  byType: { key: string; achievements: number }[]
  byYear: { year: number; amount: number }[]
  achievementsWithUnknownYear: number
  byNature: { key: DataNature; achievements: number }[]
  topOrganizations: { key: string; achievements: number }[]
  topTopics: { key: string; achievements: number }[]
  topVenues: { key: string; achievements: number }[]
  citationsBySource: { citationSource: string; achievementsWithCitation: number; citationSum: number }[]
  scope: { dataNature: DataNature; effectiveStatus: string; visibility: string; timeWindow: string; note: string }
  countingRules: Record<string, string>
  ruleVersion: string
  publishedRevision: number
}

/* ------------------------------- 公开：网络 ------------------------------- */

export interface GraphNode {
  scholarId: string
  scholarName: string
  qualifiedAchievementCount: number
}

export interface GraphEdge {
  source: string
  target: string
  strength: number
}

export interface CooperationGraph {
  graphVersion: string
  ruleVersion: string
  publishedRevision: number
  dataNatureScope: DataNature
  edgeRule: string
  nodes: GraphNode[]
  edges: GraphEdge[]
  truncated?: boolean
  withinLimits?: boolean
  note?: string
}

export interface PathResult {
  graphVersion: string
  ruleVersion: string
  publishedRevision: number
  dataNatureScope: DataNature
  from: string
  to: string
  reachable: boolean
  hops: number
  path: string[]
  pathNodes: GraphNode[]
  withinLimits: boolean
  pathRule: string
  note?: string
}

/* ---------------------------- 一致性 / 运维视图 ---------------------------- */

export interface ConsistencyView {
  activeGeneration: string
  receivedRevision: number
  highestSeenRevision: number
  publishedRevision: number
  receiveLag: number
  publishLag: number
  stateReceived?: number
  stateApplied?: number
  stateIsolated?: number
  stateFailed?: number
  oldestIsolatedRevision: number
  revisionGap: {
    expectedNextRevision: number
    lowestReceivedRevision: number | null
    missingRevisions: number
    blocked: boolean
    detail: string
    recovery?: string
  }
  coreCatalogRevision?: number
  requiredPublicRevision?: number
  coreReachable: boolean
  serving: boolean
  lagBehindCoreRevision?: number
  note: Record<string, string> | string
}

export interface OutboxHealth {
  catalogRevision: number
  consumers: {
    consumerId: string
    pendingCount: number
    oldestPendingRevision: number
    deadLetterCount: number
    deadLetterOldestRevision: number
    deadLetterNewestRevision: number
  }[]
  note: string
}

/* ------------------------------- 身份与门户 ------------------------------- */

export interface SessionView {
  accountId: string
  username: string
  displayName: string
  roles: string[]
  capabilities: string[]
}

export interface PublicAchievementDetail {
  id: string
  type: AchievementKind
  title: string
  titleNorm: string
  dataNature: DataNature
  publicationYear: number | null
  visibility: string
  effectiveStatus: string
  version: number
  paper?: {
    doi: string | null
    venue: string | null
    publishedDate: string | null
    preprintStatus: string | null
    citationCount: number | null
    citationSource: string | null
    abstractText: string | null
  }
  contributions: {
    scholarId: string
    scholarName?: string
    role: string
    authorOrder: number
    relationState: string
    organizations: string[]
  }[]
  topics: { topicId: string; canonicalName: string }[]
}

/**
 * 认领申请。
 *
 * 字段与 core 的 AccountController.claimView 对齐：主键名是 id 而不是 claimId，
 * 审核意见是 decisionReason。这条对齐是必要的——门户页要同时展示「申请状态」与「审核意见」，
 * 字段名对不上会在模板里静默变成 undefined 并渲染成空白，而不是报错。
 */
export interface ClaimRequest {
  id: string
  scholarId: string
  status: string
  statement: string | null
  submittedAt: string
  decidedAt: string | null
  decisionReason: string | null
  version: number
}
