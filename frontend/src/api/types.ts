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

/**
 * 领域归属的来源。
 *
 * SOURCE 是来源元数据给出的分类；INFERRED 是平台按受控词表推断出来的。界面上必须区分这两者：
 * 领域会进入分面与热点排名，把「系统猜的」显示成和「来源说的」一样，等于让读者把推断当成事实。
 */
export type AssignmentKind = 'SOURCE' | 'INFERRED'

/** 一条成果的领域归属。confidence 仅在 INFERRED 时出现（来源分类没有置信度这个概念）。 */
export interface TopicView {
  topicId: string
  canonicalName: string
  assignmentKind: AssignmentKind
  confidence?: number | null
}

/** 领域分面项。sourceProvided 与 systemInferred 之和等于 achievementCount。 */
export interface TopicFacetItem {
  topicId: string
  canonicalName: string
  achievementCount: number
  sourceProvided: number
  systemInferred: number
}

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
  // 下面四个「值未知」字段在报文里会**整段消失**（服务端 Jackson 配了 non_null），
  // 前端拿到的是 undefined 而不是 null。模板里判空必须同时挡 undefined，
  // 写 `!== null` 会放过它，进而在 undefined 上调用方法、把整页渲染打断。
  // 项目类成果本来就没有 DOI / 期刊 / 引用量，浏览入口首屏恰好全是项目，
  // 因此这条坑只在「不带关键词打开检索页」时出现。
  doi?: string | null
  venue?: string | null
  citationCount?: number | null
  citationSource?: string | null
  authors: string[]
  topics: TopicView[]
  relevance: number
  /**
   * 学术诚信标记。三个字段**始终存在**（服务端给的是「无诚信声明」这个值，而不是省略字段），
   * 所以这里不写成可选——写成可选会让模板里的 `item.integrity.noticed` 在类型上就不可靠。
   */
  integrity: IntegrityBadge
}

export interface IntegrityBadge {
  status: 'NONE' | 'CONCERN' | 'CORRECTION' | 'RETRACTION'
  /** 中文标签，如「已撤稿」。 */
  label: string
  noticed: boolean
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
  topics: TopicFacetItem[]
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

/** 领域榜单项。sourceProvided 与 systemInferred 之和等于 achievements。 */
export interface TopicRankItem {
  topicId: string
  canonicalName: string | null
  canonicalNameKnown: boolean
  achievements: number
  sourceProvided: number
  systemInferred: number
}

/** 领域洞察：单个领域在当前公开口径下的完整构成。 */
export interface TopicInsight {
  topic: { topicId: string; canonicalName: string | null; canonicalNameKnown: boolean }
  totals: {
    achievements: number
    sourceProvided: number
    systemInferred: number
    scholarsWithQualifiedRelation: number
    achievementsWithUnknownOrganization: number
  }
  byYear: { year: number; amount: number }[]
  achievementsWithUnknownYear: number
  byType: { key: string; achievements: number }[]
  topOrganizations: { key: string; achievements: number }[]
  topScholars: { scholarId: string; name: string | null; nameKnown: boolean; achievements: number }[]
  topVenues: { key: string; achievements: number }[]
  representativeAchievements: {
    achievementId: string
    title: string
    publicationYear: number | null
    publicationYearKnown: boolean
    citationCount: number | null
    citationCountKnown: boolean
    citationSource: string | null
    citationSourceKnown: boolean
  }[]
  scope: { dataNature: string; effectiveStatus: string; visibility: string; topicId: string; note: string }
  countingRules: Record<string, string>
  ruleVersion: string
  publishedRevision: number
}

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
  topTopics: TopicRankItem[]
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
  /** 管理员重置口令后为 true；仅用于页面提示，不阻断登录（平台没有邮件找回通道）。 */
  mustChangePassword?: boolean
  authVersion?: number
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
  topics: TopicView[]
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
  /** 服务端按申请状态给出的可重提／可追加材料标记，不在前端重复判断状态机。 */
  canResubmit?: boolean
  canAttachEvidence?: boolean
  evidence?: ClaimEvidence[]
  evidenceNote?: string
}

/**
 * 认领材料元信息。
 *
 * 只有元信息：材料本体必须经受保护端点下载。downloadUrl 是受保护端点的相对路径，
 * 不是文件地址，也不是静态目录链接——把它当直链存起来会在权限变化后失效。
 */
export interface ClaimEvidence {
  id: string
  contentType: string
  sizeBytes: number
  contentHash: string
  createdAt: string
  downloadUrl: string
}

/**
 * 引用导出的一条局限说明。
 *
 * code 给程序（按码决定展示样式、脚本按码断言），message 给人。两个字段都由服务端保证存在：
 * 后端三个服务都配了 non_null 序列化，缺字段会让界面渲染成空白而不是报错，所以这里声明为非可选。
 */
export interface CitationWarning {
  code: string
  message: string
}

/**
 * 单一引用格式的渲染结果。
 *
 * text 就是最终文本，界面不再二次加工：格式的规范细节（国标作者超过三位加「等」、APA 的
 * & 连接、BibTeX 转义）只在服务端实现一处，前端再拼一遍必然与它漂移。
 */
export interface CitationFormatEntry {
  code: string
  label: string
  text: string
  fileName: string
  mediaType: string
  machineReadable: boolean
  downloadUrl: string
  warnings: CitationWarning[]
}

/**
 * 一份成果的全部引用视图。
 *
 * provenance 与 exportedOn 刻意不在 text 里：引用会被粘进论文的参考文献表，任何非书目信息
 * 都会跟着进那张表。出处与生成时刻属于界面说明。
 */
export interface CitationBundle {
  achievementId: string
  citeKey: string
  defaultFormat: string
  provenance: string
  exportedOn: string
  warnings: CitationWarning[]
  formats: CitationFormatEntry[]
  note: string
}

/**
 * 批量导出文件里的一条条目。
 *
 * text 是**未编号**的单条正文，与单条端点逐字一致：编号属于「文件里的第几条」，
 * 由服务端在装配整份文件时统一加，因此这里不带 [1]。
 */
export interface CitationBatchEntry {
  achievementId: string
  citeKey: string
  text: string
  warnings: CitationWarning[]
}

/**
 * 一条没能写进文件的成果。
 *
 * reason 只有两个值：NOT_AVAILABLE（存在性与公开资格在批量入口上合并成同一个原因，否则这个接口
 * 就成了探测受限成果的通道）与 DUPLICATE_TARGET（合并重定向后与已选中的另一条指向同一条记录）。
 */
export interface CitationBatchSkipped {
  achievementId: string
  reason: string
  message: string
}

/**
 * 批量导出结果。
 *
 * text 与下载得到的字节逐字一致——界面上的「先看再下」必须是真的同一份内容。
 */
export interface CitationBatch {
  format: string
  formatLabel: string
  mediaType: string
  machineReadable: boolean
  fileName: string
  requested: number
  exported: number
  skippedCount: number
  entries: CitationBatchEntry[]
  skipped: CitationBatchSkipped[]
  warnings: CitationWarning[]
  text: string
  provenance: string
  exportedOn: string
  downloadUrl: string
  note: string
}

/**
 * 检索建议（输入框下拉）。
 *
 * 两组结果分开返回而不是混排：学者名与论文题名排在一个列表里，用户点错是必然的。
 * achievements 只含题名与年份，够渲染建议项即可——点进去之后详情页会给出完整信息。
 */
export interface SearchSuggestionAchievement {
  id: string
  title: string
  type: string
  publicationYear: number | null
}

export interface SearchSuggestionScholar {
  scholarId: string
  scholarName: string
  qualifiedAchievementCount: number
}

export interface SearchSuggestions {
  keyword: string | null
  dataNatureScope: string
  achievements: SearchSuggestionAchievement[]
  scholars: SearchSuggestionScholar[]
  note: string
}

/**
 * 引用网络。
 *
 * 三个块缺一不可：coverage 回答「这几条边是怎么来的」，references/citedBy 回答「有哪些边」，
 * platform 回答「整个平台覆盖了多少」——只给边数，读者会把它当成「全世界的被引数」。
 */
export interface CitationNetworkView {
  achievementId: string
  redirectedFrom?: string | null
  redirectNote?: string
  coverage: {
    observed: boolean
    /** OBSERVED=采到了；NOT_FOUND=来源查不到这一篇；NONE=还没采过。三者含义不同。 */
    outcome: string
    provider?: string | null
    providerWorkId?: string | null
    fetchedAt?: string | null
    referencesTotal?: number | null
    referencesListed?: number | null
    resolvedInPlatform?: number
    external?: number
    selfCitations?: number
    unidentified?: number
    listingGap?: number | null
    listingNote?: string
  }
  references: { count: number; items: CitedWorkView[]; note: string }
  citedBy: { count: number; items: CitedWorkView[]; note: string }
  platform: {
    observedWorks: number
    notFoundWorks?: number
    publicPapers: number
    coveragePercent: number | null
    note: string
  }
  reportedCitationCountNote?: string
}

/** 引用网络里的一篇成果。 */
export interface CitedWorkView {
  id: string
  title: string
  type?: string | null
  typeLabel: string
  publicationYear?: number | null
  yearKnown: boolean
  venue?: string | null
  doi?: string | null
}
