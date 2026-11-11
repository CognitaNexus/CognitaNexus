<script setup lang="ts">
/**
 * 治理与去重。
 *
 * 这一页处理的是平台上最不可逆的动作——合并实体。因此在交互上刻意设置了三重阻力：
 *
 *  1. 候选只展示证据，不给出「系统认为这两个是同一条」的建议。相似标题、同名学者都只是候选，
 *     判定必须由人做出，并且必须写明依据。
 *  2. 合并要显式指定幸存实体，界面不会默认选中某一边。默认值会让人顺着默认点下去，
 *     而选错幸存者意味着合并后要再合并回来。
 *  3. 台账按实体查询而不是全表浏览。合并的后果要靠「这条实体经历过什么」来理解，
 *     脱离实体看一串 MERGE 记录说明不了任何问题。
 */
import { onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { adminApi } from '@/api'
import { describeError } from '@/composables/useAsync'
import AsyncBlock from '@/components/AsyncBlock.vue'

type Row = Record<string, unknown>

function section() {
  return reactive({ items: [] as Row[], loading: false, error: '', errorCode: '' })
}

const candidates = section()
const anomalies = section()
const ledger = section()
const integrity = section()

/**
 * 诚信声明登记表单。
 *
 * 表单与列表放在同一屏而不是弹窗：登记声明是一件需要对照着「这条成果现在还有什么声明」
 * 才能下判断的操作，弹窗会把唯一可参照的信息挡住。
 */
const integrityForm = reactive({
  achievementId: '',
  noticeType: 'RETRACTION',
  declaredBy: '',
  reason: '',
  sourceUrl: '',
  announcedOn: '',
})
const integrityBusy = ref('')
const integrityError = ref('')
const integrityNote = ref('')
const revokeTarget = ref('')
const revokeReason = ref('')

const INTEGRITY_TYPES = [
  { value: 'CONCERN', label: '关注声明（结论待定）' },
  { value: 'CORRECTION', label: '更正（主体仍成立）' },
  { value: 'RETRACTION', label: '撤稿（不再作为有效研究引用）' },
]

/**
 * 工作台概览。
 *
 * 分档阈值与排序白名单都由服务端给出，前端不另立一套：两边各写一份阈值，界面上就会出现
 * 「页面说高置信、列表却按另一条线排序」这种自相矛盾，而没人能判断哪一份是对的。
 */
const workbench = reactive({
  openTotal: 0,
  filteredTotal: 0,
  page: 1,
  size: 50,
  high: 0,
  mid: 0,
  low: 0,
  highLow: 0.9,
  midLow: 0.7,
  allowedSorts: [] as string[],
  bucketNote: '',
})

/**
 * 列表筛选。
 *
 * 相似度下限用空串表示「不限」，而不是 0：0 也是一个阈值，用同一个值表达两种含义，
 * 会让「我明明没筛」与「我要看全部」在请求参数上长得一样。
 */
const filters = reactive({ minSimilarity: '' as '' | number, sort: 'similarity_desc', page: 1 })

/** 排序键的中文标签。键本身来自服务端白名单，这里只负责把键翻成人话。 */
const SORT_LABELS: Record<string, string> = {
  similarity_desc: '相似度从高到低',
  created_asc: '登记时间从早到晚',
  created_desc: '登记时间从晚到早',
  type_asc: '成果类型',
}

function sortLabel(key: string): string {
  return SORT_LABELS[key] ?? key
}

/** 批量驳回的选中集合与依据。合并刻意不提供批量入口（见提交处说明）。 */
const selectedIds = ref<string[]>([])
const batchReason = ref('')

/** 每个候选各自的处置选择，避免一次只处理一条时反复重填。 */
const candidateChoice = reactive<Record<string, { action: string; survivorId: string; reason: string }>>({})

const mergeForm = reactive({ entityType: 'achievement', survivorId: '', mergedId: '', reason: '' })

/**
 * 合并影响预览。
 *
 * 合并的后果大部分看不见：被合并实体的标题年份被丢弃、同一学者两侧的关系并成一条、来源归属
 * 因重复而不再挂接。这些只在台账快照里留痕，也就是只有合并之后才读得到。所以合并前先取一份
 * 只读后果摆在眼前，让「相似度够高就先合了再说」不再是唯一可行的路径。
 *
 * previewPair 记的是「这份预览属于哪一对实体」：表单是自由输入的，输入一变旧预览就作废。
 * 不加这条绑定的话，改了标识却还看着上一对的后果点下合并，是最危险的一种误操作。
 */
const preview = reactive({
  loading: false,
  error: '',
  errorCode: '',
  data: null as Row | null,
})
const previewPair = reactive({ survivorId: '', mergedId: '' })

function previewMatchesForm(): boolean {
  return (
    preview.data !== null &&
    previewPair.survivorId === mergeForm.survivorId.trim() &&
    previewPair.mergedId === mergeForm.mergedId.trim()
  )
}

function listOf(source: Row | null, key: string): Row[] {
  if (!source) return []
  const value = source[key]
  return Array.isArray(value) ? (value as Row[]) : []
}

function stringListOf(source: Row | null, key: string): string[] {
  return listOf(source, key).map((item) => str(item))
}

function previewSection(key: string): Row {
  const data = preview.data
  if (!data) return {}
  const value = data[key]
  return value && typeof value === 'object' ? (value as Row) : {}
}

/**
 * 后果段落的引导语。
 *
 * 有阻断原因时不能说「执行后」：这次合并会被服务端拒绝，后果一条都不会发生。把「会迁移 3 条」
 * 「会一并裁决 1 条」原样留着，读者读到的是「操作已经成立、只是还没做」，而真实状态是「现在
 * 做不成」。这两种读法会导致完全不同的下一步动作，所以引导语必须跟着阻断状态变。
 */
function previewConsequenceLabel(): string {
  return str(preview.data?.canMerge) === 'true'
    ? '执行后：'
    : '若阻断原因全部解决后执行：'
}

async function loadPreview() {
  if (!mergeForm.survivorId.trim() || !mergeForm.mergedId.trim()) {
    actionError.value = '请先填写幸存实体与被合并实体标识。'
    return
  }
  if (mergeForm.survivorId.trim() === mergeForm.mergedId.trim()) {
    actionError.value = '幸存实体与被合并实体不能是同一个。'
    return
  }
  if (mergeForm.entityType !== 'achievement') {
    actionError.value = '影响预览目前只覆盖成果合并：学者合并的授权处置与姓名别名迁移另有规则。'
    return
  }
  resetAction()
  preview.loading = true
  preview.error = ''
  preview.errorCode = ''
  try {
    preview.data = await adminApi.mergePreview({
      survivorId: mergeForm.survivorId.trim(),
      mergedId: mergeForm.mergedId.trim(),
    })
    previewPair.survivorId = mergeForm.survivorId.trim()
    previewPair.mergedId = mergeForm.mergedId.trim()
  } catch (cause) {
    const described = describeError(cause)
    preview.error = described.message
    preview.errorCode = described.code
    preview.data = null
  } finally {
    preview.loading = false
  }
}

/**
 * 从候选卡片直接预览：把候选两端填进表单，省去手抄两个标识。
 *
 * 幸存侧取当前单选框的选择（未选时默认左边）：候选卡片上选好的幸存方与预览里展示的幸存方
 * 必须是同一个，否则「我看着 A 的后果，提交的却是 B」。
 */
async function previewFromCandidate(row: Row) {
  const left = str(row.leftAchievementId)
  const right = str(row.rightAchievementId)
  const chosen = choiceOf(String(row.id)).survivorId
  const survivorId = chosen === right ? right : left
  mergeForm.entityType = 'achievement'
  mergeForm.survivorId = survivorId
  mergeForm.mergedId = survivorId === left ? right : left
  await loadPreview()
  if (preview.data) {
    document.getElementById('merge-preview')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
}

function clearPreview() {
  preview.data = null
  preview.error = ''
  preview.errorCode = ''
  previewPair.survivorId = ''
  previewPair.mergedId = ''
}

const ledgerQuery = ref('')

const busy = ref('')
const actionError = ref('')
const actionCode = ref('')
const actionNote = ref('')

async function loadSection(target: ReturnType<typeof section>, loader: () => Promise<Row[]>) {
  target.loading = true
  target.error = ''
  target.errorCode = ''
  try {
    target.items = await loader()
  } catch (cause) {
    const described = describeError(cause)
    target.error = described.message
    target.errorCode = described.code
    target.items = []
  } finally {
    target.loading = false
  }
}

function num(value: unknown, fallback = 0): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

function str(value: unknown): string {
  return value === null || value === undefined ? '' : String(value)
}

/** 从候选证据里取字段。取不到就留空——治理页面不猜内容。 */
function evidenceOf(row: Row): Record<string, unknown> {
  const raw = row.evidence
  return raw && typeof raw === 'object' ? (raw as Record<string, unknown>) : {}
}

function similarityOf(row: Row): number {
  return num(row.similarity)
}

/** 按候选标识取当前页的行：裁决时要判断「这一条能不能合并」，而裁决函数只拿到 id。 */
function rowById(id: string): Row | undefined {
  return candidates.items.find((item) => str(item.id) === id)
}

/** 相似度分档标签。阈值取自服务端概览，若无则退回默认档。 */
function tierOf(row: Row): { label: string; cls: string } {
  const similarity = similarityOf(row)
  if (similarity >= workbench.highLow) return { label: '高置信', cls: 'ok' }
  if (similarity >= workbench.midLow) return { label: '中置信', cls: 'warn' }
  return { label: '低置信', cls: 'neutral' }
}

function fmtSimilarity(row: Row): string {
  return similarityOf(row).toFixed(2)
}

const VISIBILITY_LABELS: Record<string, string> = {
  PUBLIC: '公开',
  RESTRICTED: '受限',
  HIDDEN: '隐藏',
}

const EFFECTIVE_LABELS: Record<string, string> = {
  EFFECTIVE: '生效',
  PENDING_GOVERNANCE: '待治理',
  WITHDRAWN: '已撤回',
}

/** 候选两侧的展示信息。标题、年份、可见性都与该侧实体标识取自同一次排序，不会错位。 */
/**
 * 候选一端的展示信息。
 *
 * 优先用服务端实时带回的 `left` / `right`，只有在它缺失（`known` 为假）时才回落到候选自带的
 * evidence 快照。这个顺序不能反：evidence 是登记候选那一刻写下的，候选登记之后可能已经有别的
 * 治理动作把这一端合并走了，而它仍然显示「EFFECTIVE / PUBLIC」。用快照渲染，治理人员会照着
 * 一个已经不成立的状态去点合并，得到的是一个错误码，而不是「这一端已经并走了」。
 */
function sideOf(row: Row, side: 'left' | 'right') {
  const evidence = evidenceOf(row)
  const live = (side === 'left' ? row.left : row.right) as Row | undefined
  const known = live !== undefined && str(live.known) === 'true'
  const id = str(side === 'left' ? row.leftAchievementId : row.rightAchievementId)
  const visibility = known ? str(live.visibility) : str(evidence[`${side}Visibility`])
  const effective = known ? str(live.effectiveStatus) : str(evidence[`${side}EffectiveStatus`])
  const year = known
    ? str(live.publicationYear) || '未知'
    : str(evidence[`${side}Year`]) || '—'
  return {
    id,
    known,
    title: str(known ? live.title : evidence[`${side}Title`]) || '（未取到标题）',
    year,
    visibility: VISIBILITY_LABELS[visibility] ?? (visibility || '—'),
    effective: EFFECTIVE_LABELS[effective] ?? (effective || '—'),
    merged: known && live.merged === true,
    mergedIntoId: known ? str(live.mergedIntoId) : '',
    stale: !known,
  }
}

/**
 * 这一条候选能不能通过合并处置。
 *
 * 规则与服务端的阻断条件一致：任一端已被并走时，合并会被拒绝（重定向不覆盖首次结论）。
 * 对应地，卡片把「保留为幸存实体」这个选项关掉，并说明只能驳回——把不能做的事做成可点的按钮，
 * 只会让人以为是自己点错了。
 */
function canMergeThis(row: Row): boolean {
  return str(row.recommendedAction) !== 'REJECT_ONLY' && !sideOf(row, 'left').merged && !sideOf(row, 'right').merged
}

function applyWorkbenchMeta(meta?: Record<string, unknown>) {
  if (!meta) return
  workbench.openTotal = num(meta.openTotal)
  workbench.filteredTotal = num(meta.total)
  workbench.page = num(meta.page, 1)
  workbench.size = num(meta.size, 50)
  workbench.high = num(meta.high)
  workbench.mid = num(meta.mid)
  workbench.low = num(meta.low)
  workbench.highLow = num(meta.highLow, 0.9)
  workbench.midLow = num(meta.midLow, 0.7)
  workbench.bucketNote = str(meta.bucketNote)
  const sorts = meta.allowedSorts
  workbench.allowedSorts = Array.isArray(sorts) ? sorts.map(str) : []
}

const totalPages = () => Math.max(1, Math.ceil(workbench.filteredTotal / Math.max(1, workbench.size)))
const canPrev = () => filters.page > 1
const canNext = () => filters.page < totalPages()

function loadCandidates() {
  candidates.loading = true
  candidates.error = ''
  candidates.errorCode = ''
  return adminApi
    .governanceWorkbench({
      minSimilarity: filters.minSimilarity === '' ? undefined : filters.minSimilarity,
      sort: filters.sort,
      page: filters.page,
      size: workbench.size || 50,
    })
    .then((envelope) => {
      candidates.items = envelope.data
      applyWorkbenchMeta(envelope.meta)
      // 翻页或改筛选后清空勾选：选中项若停留在看不见的条目上，批量操作会作用于
      // 一批当前屏幕上并不存在的候选，而操作人无从核对。
      selectedIds.value = []
    })
    .catch((cause) => {
      const described = describeError(cause)
      candidates.error = described.message
      candidates.errorCode = described.code
      candidates.items = []
    })
    .finally(() => {
      candidates.loading = false
    })
}

/** 改筛选或排序都回到第一页：留在第 7 页会让「我明明改了筛选」看到一片空白。 */
function applyFilters() {
  filters.page = 1
  return loadCandidates()
}

function goPage(delta: number) {
  const next = filters.page + delta
  if (next < 1 || next > totalPages()) return
  filters.page = next
  return loadCandidates()
}

function loadAnomalies() {
  return loadSection(anomalies, () => adminApi.anomalies({ page: 1, size: 50 }))
}

/**
 * 最近一次巡检报告。
 *
 * 保留它而不是「跑完就丢」：巡检的结论是「本次命中了多少」，与「现在还有多少待办」是两个数字。
 * 待办列表被人工关掉几条之后，只有报告还能说清这次扫描到底看到了什么。
 */
const scanReport = ref<Row | null>(null)

function scanFindings(): Row[] {
  const list = scanReport.value?.findings
  return Array.isArray(list) ? (list as Row[]) : []
}

/**
 * 巡检结果的一句话总结。
 *
 * 逐类列出命中条数而不是只说「巡检完成」：治理人员要知道这次到底扫到了什么，否则跑一次巡检
 * 与不跑没有区别。被上限截断的检测项单独点出来——截断后的数字不能当全量读。
 */
function scanNote(result: unknown): string {
  if (!result || typeof result !== 'object') return '巡检完成。'
  const view = result as Row
  scanReport.value = view
  const findings = Array.isArray(view.findings) ? (view.findings as Row[]) : []
  const hit = findings.filter((f) => num(f.detected) > 0)
  if (hit.length === 0) {
    return '本次巡检未发现新的数据质量问题；已有待办未受影响。'
  }
  const parts = hit.map((f) => {
    const truncated = f.truncated === true ? '，已截断' : ''
    return `${String(f.label ?? f.kind ?? '')} ${num(f.detected)} 条${truncated}`
  })
  return (
    `本次命中 ${hit.length} 类问题：${parts.join('；')}。` +
    `新建待办 ${num(view.created)} 条、刷新 ${num(view.refreshed)} 条、自动关闭 ${num(view.autoClosed)} 条（问题已不再命中）。`
  )
}

function runScan() {
  return run('scan', () => adminApi.scanAnomalies(), scanNote, loadAnomalies)
}

function loadAll() {
  return Promise.all([loadCandidates(), loadAnomalies(), loadIntegrity()])
}

/** 最近的生效声明。撤销历史不在这里显示，避免「看着像还有撤稿标记」的误读。 */
function loadIntegrity() {
  return loadSection(integrity, async () => {
    const data = await adminApi.integrityNotices({ limit: 50, activeOnly: true })
    return (data.items as Row[] | undefined) ?? []
  })
}

async function submitIntegrity() {
  integrityError.value = ''
  integrityNote.value = ''
  const achievementId = integrityForm.achievementId.trim()
  if (!achievementId) {
    integrityError.value = '需要填写成果标识：声明必须挂在具体一条成果上。'
    return
  }
  integrityBusy.value = 'register'
  try {
    await adminApi.registerIntegrityNotice(achievementId, {
      noticeType: integrityForm.noticeType,
      declaredBy: integrityForm.declaredBy.trim(),
      reason: integrityForm.reason.trim(),
      sourceUrl: integrityForm.sourceUrl.trim() || null,
      announcedOn: integrityForm.announcedOn.trim() || null,
    })
    integrityNote.value = '声明已登记；公开详情与检索结果会带上对应标记。'
    integrityForm.reason = ''
    integrityForm.sourceUrl = ''
    integrityForm.announcedOn = ''
    await loadIntegrity()
  } catch (cause) {
    const described = describeError(cause)
    integrityError.value = described.message
  } finally {
    integrityBusy.value = ''
  }
}

async function revokeIntegrity(noticeId: string) {
  integrityError.value = ''
  integrityNote.value = ''
  const reason = revokeReason.value.trim()
  if (reason.length < 10) {
    integrityError.value = '撤销理由至少 10 字：撤销一条撤稿声明本身是需要解释的动作。'
    return
  }
  integrityBusy.value = `revoke:${noticeId}`
  try {
    await adminApi.revokeIntegrityNotice(noticeId, reason)
    integrityNote.value = '声明已撤销；若这是唯一一条生效声明，成果会恢复为「无诚信声明」。'
    revokeTarget.value = ''
    revokeReason.value = ''
    await loadIntegrity()
  } catch (cause) {
    const described = describeError(cause)
    integrityError.value = described.message
  } finally {
    integrityBusy.value = ''
  }
}

function resetAction() {
  actionError.value = ''
  actionCode.value = ''
  actionNote.value = ''
}

/** 本次动作使多少条成果经重评估进入公开目录。没有该字段就当作 0。 */
function releasedCountOf(result: unknown): number {
  if (!result || typeof result !== 'object') return 0
  return num((result as Record<string, unknown>).releasedCount)
}

/**
 * 裁决之后的补充说明。
 *
 * 裁决解除的只是「待治理」的成因之一，成果能不能公开要由服务端按既定条件重评估。把结果说出来，
 * 而不是笼统地报「操作成功」：否则治理人员会以为清空候选就等于成果上线，过一阵发现检索里还是没有。
 */
function releaseNote(result: unknown): string {
  const released = releasedCountOf(result)
  if (released > 0) {
    return `另有 ${released} 条成果经重评估进入公开目录。`
  }
  return '本次没有成果因此进入公开目录：相关成果仍存在其它待治理成因（缺稳定标识、数据性质非真实或类型扩展不唯一）。'
}

/**
 * 执行一个动作并处理结果提示。
 *
 * note 允许传函数，是为了让提示能引用服务端返回的计数——固定文案无法区分「裁决完成果就上线了」
 * 与「裁决完成果还在待治理」，而这正是治理人员最需要知道的差别。
 */
async function run(
  key: string,
  fn: () => Promise<unknown>,
  note: string | ((result: unknown) => string),
  reload?: () => Promise<unknown>,
) {
  resetAction()
  busy.value = key
  try {
    const result = await fn()
    actionNote.value = typeof note === 'function' ? note(result) : note
    if (reload) await reload()
  } catch (cause) {
    const described = describeError(cause)
    actionError.value = described.message
    actionCode.value = described.code
  } finally {
    busy.value = ''
  }
}

function choiceOf(id: string) {
  if (!candidateChoice[id]) {
    candidateChoice[id] = { action: 'REJECT', survivorId: '', reason: '' }
  }
  return candidateChoice[id]
}

function resolveCandidate(id: string) {
  const choice = choiceOf(id)
  if (!choice.reason.trim()) {
    actionError.value = '请先填写判定依据。合并会改变公开视图，依据必须可追溯。'
    return
  }
  if (choice.action === 'MERGE' && !choice.survivorId) {
    actionError.value = '合并必须显式指定幸存实体，请选择保留哪一边。'
    return
  }
  // 前端这一道与服务端的阻断条件同源：任一端已被并走时合并必被拒绝。放在这里是为了让提示说明
  // 「为什么不能」，而不是把一次注定失败的写请求发出去再翻译错误码。
  const row = rowById(id)
  if (choice.action === 'MERGE' && row && !canMergeThis(row)) {
    actionError.value =
      '这条候选不能通过合并处置：至少一端已被并入其它成果（重定向不覆盖首次结论）。请改判到最终实体，或驳回。'
    return
  }
  run(
    `candidate:${id}`,
    () =>
      adminApi.resolveCandidate(id, {
        action: choice.action,
        survivorId: choice.action === 'MERGE' ? choice.survivorId : undefined,
        reason: choice.reason.trim(),
      }),
    (result) =>
      (choice.action === 'MERGE'
        ? '已合并。被合并方保留在台账中，可查回合并前的快照。'
        : '已判定为不同实体，候选关闭。') + releaseNote(result),
    loadCandidates,
  )
}

/**
 * 把候选改判到两端重定向后的最终实体。
 *
 * 为什么值得给这条候选单独一个按钮：端点被并走之后，这条候选既不能再合并，也不该被驳回——
 * 驳回写进库里的结论是「判定为不同实体」，可两端从未被比较过。以前只有这两条路，等于逼治理
 * 人员在「留下一条永远做不掉死条目」与「写一条不实结论」之间二选一。改判把问题原样转到最终
 * 实体上，问题不会丢，结论也不会被编出来。
 */
function rebaseCandidate(id: string) {
  const choice = choiceOf(id)
  if (!choice.reason.trim()) {
    actionError.value = '请先填写改判依据。改判会改变「这个问题由谁作答」，依据必须可追溯。'
    return
  }
  run(
    `rebase:${id}`,
    () => adminApi.rebaseCandidate(id, { reason: choice.reason.trim() }),
    (result) => rebaseNote(result),
    loadCandidates,
  )
}

/** 改判结果的说明：如实区分「转到另一条候选」与「两端本就是同一条」。 */
function rebaseNote(result: unknown): string {
  const row = (result ?? {}) as Record<string, unknown>
  const status = str(row.status)
  const target = str(row.rebasedToId)
  const existed = row.targetExisted === true
  if (status === 'MERGED') {
    return (
      '已判定为已合并：两端重定向后指向同一条实体，这条候选提出的问题已经由合并回答，候选关闭。' +
      releaseNote(result)
    )
  }
  const where = existed
    ? `这一对最终实体之间已有待治理候选 ${target || '（标识未返回）'}`
    : `已登记承接候选 ${target || '（标识未返回）'}`
  return `${where}，原候选的问题已转由它作答（原候选标记为 REBASED，不删除：这条问题后来去哪儿了必须查得到）。${releaseNote(result)}`
}

function resolveAnomaly(id: string) {
  run(`anomaly:${id}`, () => adminApi.resolveAnomaly(id), '异常已标记为处理完毕。', loadAnomalies)
}

/**
 * 公众纠错的处置细节。
 *
 * 摘要只给一行摘录，处置人要判断「这条报告是否成立」必须看到原文、累计次数与联系方式。
 * 这四项都来自 detail，不在表格里就永远没人点开——而这张表本来就没有详情展开。
 */
function correctionOf(row: Record<string, unknown>): {
  report: string
  count: number
  lastReportedAt: string
  contact: string
} | null {
  if (String(row.kind ?? '') !== 'PUBLIC_CORRECTION_REPORT') return null
  const detail = (row.detail ?? {}) as Record<string, unknown>
  const text = (value: unknown) => (typeof value === 'string' ? value : '')
  const count = typeof detail.reportCount === 'number' ? detail.reportCount : 1
  return {
    report: text(detail.report),
    count,
    lastReportedAt: text(detail.lastReportedAt),
    contact: text(detail.contact),
  }
}

function submitMerge() {
  if (!mergeForm.survivorId.trim() || !mergeForm.mergedId.trim() || !mergeForm.reason.trim()) {
    actionError.value = '幸存实体、被合并实体与依据都不能为空。'
    return
  }
  if (mergeForm.survivorId.trim() === mergeForm.mergedId.trim()) {
    actionError.value = '幸存实体与被合并实体不能是同一个。'
    return
  }
  const isAchievement = mergeForm.entityType === 'achievement'
  // 成果合并要求先看过影响：这一步是刻意设置的阻力，不是提示。合并不可逆，而它的后果
  // （元数据丢弃、关系合并、来源不再挂接）此前只有合并之后才能在台账里读到。
  if (isAchievement && !previewMatchesForm()) {
    actionError.value = '请先对当前这一对实体生成影响预览：合并会丢弃被合并方的元数据、合并重复的参与关系与来源归属，这些在选择幸存方之前必须看见。'
    return
  }
  // 预览已经给出了结论，就不要再把注定被拒的请求发出去：一是省掉一次必然失败的写操作，
  // 二是让「不可合并」的阻断原因停在用户眼前，而不是被一个后端错误码盖掉。
  if (isAchievement && preview.data && str(preview.data.canMerge) === 'false') {
    const blockers = stringListOf(preview.data, 'blockers')
    actionError.value = blockers.length
      ? `影响预览显示当前不可合并，已阻止提交：${blockers[0]}`
      : '影响预览显示当前不可合并，已阻止提交。'
    return
  }
  run(
    'merge',
    () =>
      isAchievement
        ? adminApi.mergeAchievements({
            survivorId: mergeForm.survivorId.trim(),
            mergedId: mergeForm.mergedId.trim(),
            reason: mergeForm.reason.trim(),
          })
        : adminApi.mergeScholars({
            survivorId: mergeForm.survivorId.trim(),
            mergedId: mergeForm.mergedId.trim(),
            reason: mergeForm.reason.trim(),
          }),
    isAchievement ? '成果合并完成。被合并方保留在台账中，可查回合并前快照。' : '学者合并完成。注意：同名不等于同一人，合并前请确认标识可信。',
    async () => {
      clearPreview()
      await loadAll()
    },
  )
}

function isSelected(id: string) {
  return selectedIds.value.includes(id)
}

function toggleSelect(id: string) {
  selectedIds.value = isSelected(id)
    ? selectedIds.value.filter((item) => item !== id)
    : [...selectedIds.value, id]
}

function selectVisible() {
  selectedIds.value = candidates.items.map((row) => str(row.id)).filter((id) => id !== '')
}

function clearSelection() {
  selectedIds.value = []
}

/**
 * 批量判定为不同实体。
 *
 * 只提供批量驳回，不提供批量合并。两个方向的代价不对称：驳回错了只是留下两个各自独立的实体，
 * 之后还能再合并；合并错了要把参与关系、来源归属与门户授权从幸存实体上拆回来，其中门户授权
 * 一旦被撤销无法自动恢复。每次合并都要求显式指定幸存实体，那正是「相似不得直接合并」这条
 * 红线的落点，所以这里没有「全选后一键合并」。
 */
async function batchReject() {
  if (selectedIds.value.length === 0) {
    actionError.value = '请先勾选要判定为「不同实体」的候选。'
    return
  }
  if (!batchReason.value.trim()) {
    actionError.value = '批量判定同样需要写明依据。依据会逐条写入审计记录，没有依据的批量操作无法复核。'
    return
  }
  resetAction()
  busy.value = 'batch-reject'
  const ids = [...selectedIds.value]
  try {
    const result = await adminApi.batchRejectCandidates({
      candidateIds: ids,
      reason: batchReason.value.trim(),
    })
    const resolvedCount = num(result.resolved)
    const skipped = Array.isArray(result.skipped) ? (result.skipped as unknown[]).map(str) : []
    actionNote.value =
      `已驳回 ${resolvedCount} 条候选。` +
      releaseNote(result) +
      (skipped.length
        ? `另有 ${skipped.length} 条未被处理：${skipped.slice(0, 3).join('；')}${skipped.length > 3 ? ' …' : ''}`
        : '')
    selectedIds.value = []
    batchReason.value = ''
    await loadCandidates()
  } catch (cause) {
    const described = describeError(cause)
    actionError.value = described.message
    actionCode.value = described.code
  } finally {
    busy.value = ''
  }
}

/** 按档批量驳回的目标档位与条数。低置信是默认档：它通常占积压的大头，且驳回代价最低。 */
const bucketToReject = ref('LOW')
const bucketRejectLimit = ref(50)

/**
 * 按档批量驳回。
 *
 * 服务于一个具体处境：候选积压到两万多条时，「逐页勾选」在人力上不可完成，而分档计数只回答
 * 「哪一档最多」。这里让服务端按同一套阈值取一档的前 N 条再逐条裁决。
 *
 * 只做驳回、不做合并：按档取出的这批候选没有人逐条看过，而合并要求显式指定幸存实体，那正是
 * 「相似标题不得直接合并」的落点。驳回错了可以再合并，方向不对称，所以只开放可逆的一侧。
 */
async function batchRejectByBucket() {
  if (!batchReason.value.trim()) {
    actionError.value = '按档批量驳回同样需要写明依据。依据会逐条写入审计记录。'
    return
  }
  resetAction()
  busy.value = 'batch-reject-bucket'
  try {
    const result = await adminApi.batchRejectCandidatesByBucket({
      bucket: bucketToReject.value,
      limit: bucketRejectLimit.value,
      reason: batchReason.value.trim(),
    })
    const resolvedCount = num(result.resolved)
    const skipped = Array.isArray(result.skipped) ? (result.skipped as unknown[]).map(str) : []
    actionNote.value =
      `已按「${bucketToReject.value}」档驳回 ${resolvedCount} 条候选。` +
      releaseNote(result) +
      (skipped.length
        ? `另有 ${skipped.length} 条未被处理：${skipped.slice(0, 3).join('；')}${skipped.length > 3 ? ' …' : ''}`
        : '')
    await loadCandidates()
  } catch (cause) {
    const described = describeError(cause)
    actionError.value = described.message
    actionCode.value = described.code
  } finally {
    busy.value = ''
  }
}

function loadLedger() {
  const entityId = ledgerQuery.value.trim()
  if (!entityId) {
    actionError.value = '请输入要查询台账的实体标识。'
    return
  }
  loadSection(ledger, () => adminApi.ledger(entityId, 100))
}

onMounted(loadAll)
</script>

<template>
  <div>
    <div style="display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap">
      <h1>治理与去重</h1>
      <button class="btn small" @click="loadAll">刷新</button>
    </div>

    <div class="notice warn">
      <strong>相似不等于同一条，同名不等于同一人。</strong>
      本页的候选来自规则匹配，只表示「值得人看一眼」。任何自动流程都不会直接合并实体；
      这里每一次合并都会写入台账并触发投影重建。
    </div>

    <div v-if="actionError" class="notice danger">
      <div>{{ actionError }}</div>
      <div v-if="actionCode" class="tiny mono" style="margin-top: 6px">错误码 {{ actionCode }}</div>
    </div>
    <div v-if="actionNote" class="notice info">{{ actionNote }}</div>

    <div class="panel">
      <div class="panel-head">
        <h2>重复候选</h2>
        <span class="tiny faint">候选只提供证据，判定由人做出</span>
      </div>
      <div class="panel-body tight">
        <div class="notice info" style="margin-bottom: 12px">
          <div>
            待办候选 <strong>{{ workbench.openTotal }}</strong> 条： 高置信
            <strong>{{ workbench.high }}</strong> 条（相似度 ≥ {{ workbench.highLow }}）、 中置信
            <strong>{{ workbench.mid }}</strong> 条（≥ {{ workbench.midLow }}）、 低置信
            <strong>{{ workbench.low }}</strong> 条。
          </div>
          <div class="tiny faint" style="margin-top: 4px">{{ workbench.bucketNote }}</div>
        </div>

        <div class="btn-row" style="margin-bottom: 12px; align-items: center; flex-wrap: wrap">
          <label class="tiny" for="wb-min-sim">相似度下限</label>
          <select
            id="wb-min-sim"
            v-model="filters.minSimilarity"
            style="width: auto"
            @change="applyFilters"
          >
            <option value="">不限</option>
            <option :value="workbench.highLow">仅高置信（≥ {{ workbench.highLow }}）</option>
            <option :value="workbench.midLow">中置信及以上（≥ {{ workbench.midLow }}）</option>
          </select>
          <label class="tiny" for="wb-sort">排序</label>
          <select id="wb-sort" v-model="filters.sort" style="width: auto" @change="applyFilters">
            <option v-for="key in workbench.allowedSorts" :key="key" :value="key">
              {{ sortLabel(key) }}
            </option>
          </select>
          <span class="tiny faint">当前筛选命中 {{ workbench.filteredTotal }} 条</span>
        </div>

        <div
          v-if="candidates.items.length > 0"
          style="
            display: flex;
            align-items: center;
            gap: 10px;
            flex-wrap: wrap;
            border: 1px solid var(--rule);
            border-radius: var(--radius);
            padding: 9px 13px;
            margin-bottom: 8px;
          "
        >
          <label class="tiny">
            <input
              type="checkbox"
              :checked="selectedIds.length > 0 && selectedIds.length === candidates.items.length"
              @change="selectedIds.length === candidates.items.length ? clearSelection() : selectVisible()"
            />
            本页全选
          </label>
          <span class="tiny">已选 <strong>{{ selectedIds.length }}</strong> 条</span>
          <button class="btn small" :disabled="selectedIds.length === 0" @click="clearSelection">
            清空
          </button>
          <input
            v-model="batchReason"
            type="text"
            placeholder="批量判定的依据（必填）"
            style="flex: 1; min-width: 220px"
          />
          <button
            class="btn primary small"
            :disabled="busy === 'batch-reject' || selectedIds.length === 0"
            @click="batchReject"
          >
            {{ busy === 'batch-reject' ? '处理中…' : '判定为不同实体' }}
          </button>
        </div>
        <div
          class="tiny"
          style="
            display: flex;
            gap: 10px;
            flex-wrap: wrap;
            align-items: center;
            border: 1px solid var(--rule);
            border-radius: var(--radius);
            padding: 9px 13px;
            margin-bottom: 8px;
          "
        >
          <span>按档批量驳回：</span>
          <select v-model="bucketToReject" style="width: 190px">
            <option value="LOW">低置信（&lt; {{ workbench.midLow }}）· {{ workbench.low }} 条</option>
            <option value="MID">
              中置信（{{ workbench.midLow }}–{{ workbench.highLow }}）· {{ workbench.mid }} 条
            </option>
            <option value="HIGH">高置信（≥ {{ workbench.highLow }}）· {{ workbench.high }} 条</option>
          </select>
          <input
            v-model.number="bucketRejectLimit"
            type="number"
            min="1"
            max="200"
            style="width: 80px"
            title="单次上限 200 条"
          />
          <button
            class="btn small"
            :disabled="busy === 'batch-reject-bucket'"
            @click="batchRejectByBucket"
          >
            {{ busy === 'batch-reject-bucket' ? '处理中…' : '驳回本档这一批' }}
          </button>
          <span class="faint">
            用上面的「依据」输入框；服务端按同一套阈值取该档前 N 条并逐条裁决
          </span>
        </div>
        <p v-if="candidates.items.length > 0" class="tiny faint" style="margin: 0 0 12px">
          只提供批量驳回，不提供批量合并：驳回错了可以再合并，合并错了要把参与关系、来源归属与门户授权从幸存实体上拆回来，两边代价不对称。合并必须逐条指定幸存实体。
          裁决完成后服务端会按既定条件重评估相关成果的发布资格并回报结果——裁决解除的是待治理的成因之一，成果能否公开仍由条件判定，不因「候选清空了」自动成立。
        </p>

        <AsyncBlock
          :loading="candidates.loading"
          :error="candidates.error"
          :error-code="candidates.errorCode"
          :empty="candidates.items.length === 0"
          empty-text="当前没有待判定的重复候选。"
        >
          <div v-for="row in candidates.items" :key="String(row.id)" class="result-item">
            <div class="result-meta">
              <label class="tiny" style="display: inline-flex; align-items: center; gap: 5px">
                <input
                  type="checkbox"
                  :checked="isSelected(String(row.id))"
                  @change="toggleSelect(String(row.id))"
                />
                选择
              </label>
              <span :class="`badge ${tierOf(row).cls}`">{{ tierOf(row).label }}</span>
              <span class="badge neutral">相似度 {{ fmtSimilarity(row) }}</span>
              <span class="badge neutral">{{ String(row.achievementType ?? '') }}</span>
              <span :class="row.status === 'OPEN' ? 'badge warn' : 'badge neutral'">
                {{ String(row.status ?? '') }}
              </span>
              <span class="mono tiny">{{ String(row.id) }}</span>
              <span v-if="row.createdAt" class="mono tiny">{{ String(row.createdAt) }}</span>
            </div>

            <div v-if="!canMergeThis(row)" class="notice warn" style="margin-bottom: 10px">
              这条候选不能通过合并处置：{{
                sideOf(row, 'left').merged
                  ? `候选 A 已被并入 ${sideOf(row, 'left').mergedIntoId || '其它成果'}`
                  : ''
              }}{{ sideOf(row, 'left').merged && sideOf(row, 'right').merged ? '，' : ''
              }}{{
                sideOf(row, 'right').merged
                  ? `候选 B 已被并入 ${sideOf(row, 'right').mergedIntoId || '其它成果'}`
                  : ''
              }}。重定向不覆盖首次结论，因此「保留为幸存实体」已被关闭；可处置方式只剩驳回，
              或把这条候选改判到两端重定向后的最终实体。
              <div class="tiny" style="margin-top: 6px">
                驳回答的是「这两条不是同一条」；若你并没有比较过这两端（其中一端已经被并走，
                比较本身无从做起），应改用改判：问题会原样转到最终实体上，不会留下一条不实的结论。
              </div>
            </div>
            <div v-else-if="sideOf(row, 'left').stale || sideOf(row, 'right').stale" class="notice warn" style="margin-bottom: 10px">
              至少一端的状态没能实时取到，下面显示的是候选登记时的证据快照，可能已经过期。
            </div>

            <div class="grid cols-2" style="margin: 10px 0">
              <div class="panel" style="margin: 0; box-shadow: none">
                <div class="panel-body tight">
                  <div class="tiny dim">
                    候选 A · {{ sideOf(row, 'left').year }} 年 · {{ sideOf(row, 'left').visibility }} /
                    {{ sideOf(row, 'left').effective }}
                    <span v-if="sideOf(row, 'left').merged" class="badge danger">已并入其它成果</span>
                  </div>
                  <div class="small" style="margin: 4px 0 6px">{{ sideOf(row, 'left').title }}</div>
                  <RouterLink class="mono tiny" :to="`/achievements/${sideOf(row, 'left').id}`">
                    {{ sideOf(row, 'left').id }}
                  </RouterLink>
                  <div style="margin-top: 8px">
                    <label class="tiny">
                      <input
                        type="radio"
                        :name="`survivor-${String(row.id)}`"
                        :value="sideOf(row, 'left').id"
                        :disabled="!canMergeThis(row)"
                        :checked="choiceOf(String(row.id)).survivorId === sideOf(row, 'left').id"
                        @change="choiceOf(String(row.id)).survivorId = sideOf(row, 'left').id"
                      />
                      保留为幸存实体
                    </label>
                  </div>
                </div>
              </div>
              <div class="panel" style="margin: 0; box-shadow: none">
                <div class="panel-body tight">
                  <div class="tiny dim">
                    候选 B · {{ sideOf(row, 'right').year }} 年 · {{ sideOf(row, 'right').visibility }} /
                    {{ sideOf(row, 'right').effective }}
                    <span v-if="sideOf(row, 'right').merged" class="badge danger">已并入其它成果</span>
                  </div>
                  <div class="small" style="margin: 4px 0 6px">{{ sideOf(row, 'right').title }}</div>
                  <RouterLink class="mono tiny" :to="`/achievements/${sideOf(row, 'right').id}`">
                    {{ sideOf(row, 'right').id }}
                  </RouterLink>
                  <div style="margin-top: 8px">
                    <label class="tiny">
                      <input
                        type="radio"
                        :name="`survivor-${String(row.id)}`"
                        :value="sideOf(row, 'right').id"
                        :disabled="!canMergeThis(row)"
                        :checked="choiceOf(String(row.id)).survivorId === sideOf(row, 'right').id"
                        @change="choiceOf(String(row.id)).survivorId = sideOf(row, 'right').id"
                      />
                      保留为幸存实体
                    </label>
                  </div>
                </div>
              </div>
            </div>

            <details v-if="row.evidence" class="tiny" style="margin-bottom: 10px">
              <summary class="faint">查看证据快照</summary>
              <div class="pre" style="margin-top: 8px">{{ JSON.stringify(row.evidence, null, 2) }}</div>
            </details>

            <div class="grid cols-2">
              <div class="field" style="margin: 0">
                <label :for="`cand-action-${String(row.id)}`">处置</label>
                <select :id="`cand-action-${String(row.id)}`" v-model="choiceOf(String(row.id)).action">
                  <option value="REJECT">判定为不同实体</option>
                  <option value="MERGE" :disabled="!canMergeThis(row)">
                    合并为一条{{ canMergeThis(row) ? '' : '（任一端已被并走，不可选）' }}
                  </option>
                </select>
              </div>
              <div class="field" style="margin: 0">
                <label :for="`cand-reason-${String(row.id)}`">判定依据（必填）</label>
                <input
                  :id="`cand-reason-${String(row.id)}`"
                  v-model="choiceOf(String(row.id)).reason"
                  type="text"
                  placeholder="例如：DOI 完全一致，确认为同一篇"
                />
              </div>
            </div>

            <div class="btn-row" style="margin-top: 10px">
              <button
                class="btn small"
                :disabled="preview.loading || !canMergeThis(row)"
                :title="canMergeThis(row) ? '' : '任一端已被并入其它成果，这条候选不能通过合并处置'"
                @click="previewFromCandidate(row)"
              >
                预览合并影响
              </button>
              <button
                class="btn primary small"
                :disabled="busy === `candidate:${String(row.id)}`"
                @click="resolveCandidate(String(row.id))"
              >
                提交处置
              </button>
              <!-- 改判只在受阻的候选上出现：能正常合并/驳回的候选不需要它，把它摆在那里
                   只会让人以为「改判」是常规处置之一。 -->
              <button
                v-if="!canMergeThis(row)"
                class="btn small"
                :disabled="busy === `rebase:${String(row.id)}`"
                title="沿合并链把两端解析到最终实体，问题转到最终实体这一对上"
                @click="rebaseCandidate(String(row.id))"
              >
                改判到最终实体
              </button>
            </div>
          </div>

          <div class="btn-row" style="margin-top: 12px; align-items: center">
            <button class="btn small" :disabled="!canPrev() || candidates.loading" @click="goPage(-1)">
              上一页
            </button>
            <span class="tiny faint">第 {{ workbench.page }} / {{ totalPages() }} 页</span>
            <button class="btn small" :disabled="!canNext() || candidates.loading" @click="goPage(1)">
              下一页
            </button>
          </div>
        </AsyncBlock>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h2>治理异常</h2>
        <span class="tiny faint"
          >例如公开但缺少领域归属、论文无稳定标识、同名学者聚集等；巡检只登记待办，不自动修改成果</span
        >
      </div>
      <div class="panel-body" style="padding-bottom: 0">
        <div class="btn-row" style="align-items: center">
          <button class="btn small" :disabled="busy === 'scan'" @click="runScan()">
            {{ busy === 'scan' ? '巡检中…' : '运行数据质量巡检' }}
          </button>
          <span class="tiny faint">可重复运行：同一问题在未处理期间只占一条待办，计数随巡检刷新</span>
        </div>
        <div v-if="scanReport" class="tiny" style="margin-top: 8px">
          <div class="faint">
            规则版本 {{ String(scanReport.ruleVersion ?? '') }} · 扫描于
            {{ String(scanReport.scannedAt ?? '') }} · 年份口径
            {{ String((scanReport.yearBounds as Row | undefined)?.minYear ?? '') }}–{{
              String((scanReport.yearBounds as Row | undefined)?.maxYear ?? '')
            }}
          </div>
          <table class="data" style="margin-top: 8px">
            <thead>
              <tr>
                <th>检测项</th>
                <th>本次命中</th>
                <th>新建</th>
                <th>刷新</th>
                <th>自动关闭</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="f in scanFindings()" :key="String(f.kind)">
                <td>
                  {{ String(f.label ?? '') }}
                  <span v-if="f.truncated === true" class="badge warn">已截断</span>
                </td>
                <td class="mono">{{ num(f.detected) }}</td>
                <td class="mono">{{ num(f.created) }}</td>
                <td class="mono">{{ num(f.refreshed) }}</td>
                <td class="mono">{{ num(f.autoClosed) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <div class="panel-body tight">
        <AsyncBlock
          :loading="anomalies.loading"
          :error="anomalies.error"
          :error-code="anomalies.errorCode"
          :empty="anomalies.items.length === 0"
          empty-text="当前没有未处理的治理异常。"
        >
          <table class="data">
            <thead>
              <tr>
                <th>检测项</th>
                <th>相关实体</th>
                <th>说明</th>
                <th>状态</th>
                <th>发现时间</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in anomalies.items" :key="String(row.id)">
                <td>
                  <span class="badge warn">{{ String(row.kindLabel ?? row.kind ?? '') }}</span>
                  <div class="mono tiny faint">{{ String(row.kind ?? '') }}</div>
                </td>
                <td class="mono tiny">
                  <RouterLink
                    v-if="String(row.entityId ?? '').startsWith('ach_')"
                    :to="`/achievements/${String(row.entityId)}`"
                    >{{ String(row.entityId) }}</RouterLink
                  >
                  <template v-else>{{ String(row.entityId ?? row.sourceRecordId ?? '—') }}</template>
                </td>
                <td class="tiny">
                  {{ String(row.summary ?? '') }}
                  <template v-if="correctionOf(row)">
                    <div class="tiny" style="margin-top: 4px">
                      报告原文：<span class="dim">{{ correctionOf(row)!.report }}</span>
                    </div>
                    <div class="tiny faint" style="margin-top: 2px">
                      累计 {{ correctionOf(row)!.count }} 次 · 最近
                      {{ correctionOf(row)!.lastReportedAt || '—' }}
                      <template v-if="correctionOf(row)!.contact">
                        · 联系方式 {{ correctionOf(row)!.contact }}
                      </template>
                      <template v-else> · 未留联系方式</template>
                    </div>
                  </template>
                </td>
                <td class="tiny">{{ String(row.status ?? '') }}</td>
                <td class="mono tiny">{{ String(row.createdAt ?? '') }}</td>
                <td>
                  <button
                    class="btn small"
                    :disabled="busy === `anomaly:${String(row.id)}`"
                    @click="resolveAnomaly(String(row.id))"
                  >
                    标记已处理
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </AsyncBlock>
      </div>
    </div>

    <div class="grid cols-2">
      <div class="panel">
        <div class="panel-head"><h2>手动合并</h2></div>
        <div class="panel-body">
          <div class="field">
            <label for="merge-type">实体类型</label>
            <select id="merge-type" v-model="mergeForm.entityType">
              <option value="achievement">成果</option>
              <option value="scholar">学者</option>
            </select>
          </div>
          <div class="field">
            <label for="merge-survivor">幸存实体标识（保留）</label>
            <input id="merge-survivor" v-model="mergeForm.survivorId" type="text" />
          </div>
          <div class="field">
            <label for="merge-merged">被合并实体标识（并入上方）</label>
            <input id="merge-merged" v-model="mergeForm.mergedId" type="text" />
          </div>
          <div class="field">
            <label for="merge-reason">合并依据（必填）</label>
            <textarea id="merge-reason" v-model="mergeForm.reason"></textarea>
          </div>
          <div class="btn-row" style="margin-bottom: 10px">
            <button
              class="btn"
              :disabled="preview.loading"
              @click="loadPreview"
            >
              {{ preview.loading ? '读取中…' : '预览影响' }}
            </button>
            <button class="btn primary" :disabled="busy === 'merge'" @click="submitMerge">执行合并</button>
            <button v-if="preview.data" class="btn small" @click="clearPreview">清除预览</button>
          </div>

          <div v-if="preview.error" class="notice danger" id="merge-preview-error">
            {{ preview.error }}
            <span class="tiny faint">（{{ preview.errorCode }}）</span>
          </div>

          <div v-else-if="preview.data" class="preview" id="merge-preview">
            <div class="preview-head">
              <span class="badge" :class="preview.data.canMerge ? 'ok' : 'danger'">
                {{ preview.data.canMerge ? '可以合并' : '当前不可合并' }}
              </span>
              <span class="tiny faint">只读预览 · 未写入任何数据 · 不产生台账</span>
            </div>

            <div v-if="stringListOf(preview.data, 'blockers').length" class="notice danger" style="margin-top: 10px">
              <div v-for="(item, index) in stringListOf(preview.data, 'blockers')" :key="index">{{ item }}</div>
            </div>

            <div class="preview-sides">
              <div v-for="side in (['survivor', 'merged'] as const)" :key="side" class="preview-side">
                <div class="tiny faint">{{ side === 'survivor' ? '幸存（保留这一条）' : '被合并（并入上方）' }}</div>
                <div class="small" style="margin: 4px 0">{{ str(previewSection(side).title) }}</div>
                <div class="tiny faint">
                  {{ str(previewSection(side).type) }} ·
                  {{ str(previewSection(side).publicationYearLabel) }} ·
                  {{ str(previewSection(side).visibility) }} ·
                  {{ str(previewSection(side).effectiveStatus) }} ·
                  v{{ str(previewSection(side).version) }}
                </div>
                <div class="mono tiny faint">{{ str(previewSection(side).id) }}</div>
              </div>
            </div>

            <table class="preview-table">
              <thead>
                <tr>
                  <th>合并后如何变化</th>
                  <th>幸存侧</th>
                  <th>被合并侧</th>
                  <th>结果</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>
                    参与关系
                    <div class="tiny faint">
                      会迁移 {{ str(previewSection('contributions').willTransfer) }} 条；{{
                        str(previewSection('contributions').willMergeIntoExisting)
                      }}
                      位学者两侧都有，合并为一条
                    </div>
                  </td>
                  <td class="mono">{{ str(previewSection('contributions').survivorRows) }}</td>
                  <td class="mono">{{ str(previewSection('contributions').mergedRows) }}</td>
                  <td class="mono">{{ str(previewSection('contributions').resultingRows) }}</td>
                </tr>
                <tr>
                  <td>
                    来源归属
                    <div class="tiny faint">
                      重复的来源记录不再重复挂接：{{ str(previewSection('sources').willSkipDuplicate) }} 条
                    </div>
                  </td>
                  <td class="mono">{{ str(previewSection('sources').survivorRows) }}</td>
                  <td class="mono">{{ str(previewSection('sources').mergedRows) }}</td>
                  <td class="mono">{{ str(previewSection('sources').resultingRows) }}</td>
                </tr>
                <tr>
                  <td>
                    领域标签
                    <div class="tiny faint">
                      重复标签跳过 {{ str(previewSection('topics').willSkipDuplicate) }} 个
                    </div>
                  </td>
                  <td class="mono">{{ str(previewSection('topics').survivorRows) }}</td>
                  <td class="mono">{{ str(previewSection('topics').mergedRows) }}</td>
                  <td class="mono">{{ str(previewSection('topics').resultingRows) }}</td>
                </tr>
                <tr>
                  <td>
                    稳定标识
                    <div class="tiny faint">{{ str(previewSection('identifiers').effect) }}</div>
                  </td>
                  <td class="mono">{{ str(previewSection('identifiers').survivorCount) }}</td>
                  <td class="mono">{{ str(previewSection('identifiers').mergedCount) }}</td>
                  <td class="mono">{{ str(previewSection('identifiers').resultingCount) }}</td>
                </tr>
              </tbody>
            </table>

            <details v-if="listOf(previewSection('contributions'), 'transferList').length" class="preview-details">
              <summary>
                会迁移的参与关系（{{ listOf(previewSection('contributions'), 'transferList').length }} 条）
              </summary>
              <div
                v-for="row in listOf(previewSection('contributions'), 'transferList')"
                :key="str(row.scholarId)"
                class="tiny"
              >
                {{ str(row.scholarName) || str(row.scholarId) }} · {{ str(row.relationState) }} · {{ str(row.role) }}
              </div>
            </details>

            <details v-if="listOf(previewSection('contributions'), 'duplicateList').length" class="preview-details">
              <summary>
                两侧都有的学者（{{ listOf(previewSection('contributions'), 'duplicateList').length }} 位）
              </summary>
              <div
                v-for="row in listOf(previewSection('contributions'), 'duplicateList')"
                :key="str(row.scholarId)"
                class="tiny"
              >
                {{ str(row.scholarName) || str(row.scholarId) }}：幸存侧 {{ str(row.survivorRelationState) }} /
                被合并侧 {{ str(row.relationState) }} —— {{ str(row.effect) }}
              </div>
            </details>

            <div v-if="stringListOf(preview.data, 'warnings').length" class="notice warn" style="margin-top: 12px">
              <div v-for="(item, index) in stringListOf(preview.data, 'warnings')" :key="index" style="margin-bottom: 6px">
                {{ item }}
              </div>
            </div>

            <div class="tiny faint">
              {{ previewConsequenceLabel() }}被合并成果置为
              {{ str(previewSection('effects').mergedEffectiveStatus) }}，重定向到
              {{ str(previewSection('effects').redirectTo) }}，幸存成果版本升到
              {{ str(previewSection('effects').survivorVersion) }}；{{ str(previewSection('effects').ledgerAndAudit) }}；{{
                str(previewSection('effects').event)
              }}。
            </div>

            <div class="tiny faint" style="margin-top: 6px">
              {{ previewConsequenceLabel() }}待治理候选：这一对之间的
              {{ str(previewSection('effects').candidatesClosedWithMerge) }}
              条会随合并一并裁决为已合并（合并已经回答了候选提出的问题）；
              被合并侧另有
              {{ str(previewSection('effects').otherCandidatesOnMergedSide) }}
              条、幸存侧另有
              {{ str(previewSection('effects').otherCandidatesOnSurvivorSide) }}
              条牵涉其它实体，本次不会被处置，合并后仍需人工逐条判断。
            </div>
          </div>

          <p class="tiny faint" style="margin: 10px 0 0">
            合并不可直接撤销。被合并实体会保留标识与合并前快照，若判定有误需要在台账中查回原始状态并另行处理。
          </p>
        </div>
      </div>

      <div class="panel">
        <div class="panel-head">
          <h2>治理台账</h2>
          <span class="tiny faint">按实体查询</span>
        </div>
        <div class="panel-body">
          <div class="btn-row" style="margin-bottom: 12px">
            <input
              v-model="ledgerQuery"
              type="text"
              placeholder="输入实体标识"
              style="flex: 1; min-width: 200px"
            />
            <button class="btn" @click="loadLedger">查询</button>
          </div>

          <AsyncBlock
            :loading="ledger.loading"
            :error="ledger.error"
            :error-code="ledger.errorCode"
            :empty="ledger.items.length === 0"
            empty-text="输入实体标识后可查看该实体经历过的合并与修订记录。"
          >
            <div v-for="row in ledger.items" :key="String(row.id)" class="result-item" style="padding: 10px 0">
              <div class="result-meta">
                <span class="badge neutral">{{ String(row.operation ?? '') }}</span>
                <span class="tiny">{{ String(row.entityType ?? '') }}</span>
                <span class="mono tiny">{{ String(row.createdAt ?? '') }}</span>
              </div>
              <div class="small" style="margin-top: 6px">
                幸存 {{ String(row.survivorId ?? '—') }} ← 并入 {{ String(row.mergedId ?? '—') }}
              </div>
              <div class="tiny dim">{{ String(row.reason ?? '') }}</div>
            </div>
          </AsyncBlock>
        </div>
      </div>

      <div class="panel">
        <div class="panel-head">
          <h2>学术诚信声明</h2>
          <span class="tiny faint">撤稿 / 更正 / 关注声明</span>
        </div>
        <div class="panel-body">
          <div class="notice info" style="margin-bottom: 12px">
            声明<b>不改写成果的任何字段</b>：它是挂在成果上的一条独立判断，纠正内容仍走修订与裁决流程。
            撤稿成果<b>仍然可以检索</b>（引用过它的人需要查到发生了什么），但详情与列表都会显示标记。
            同一成果同一类型同时只能有一条生效声明，撤销必须写理由。
          </div>

          <div class="btn-row" style="flex-wrap: wrap; gap: 8px; margin-bottom: 12px">
            <input
              v-model="integrityForm.achievementId"
              type="text"
              placeholder="成果标识（ach_…）"
              style="flex: 2; min-width: 220px"
            />
            <select v-model="integrityForm.noticeType" style="flex: 1; min-width: 160px">
              <option v-for="type in INTEGRITY_TYPES" :key="type.value" :value="type.value">
                {{ type.label }}
              </option>
            </select>
            <input
              v-model="integrityForm.declaredBy"
              type="text"
              placeholder="声明主体（期刊 / 机构 / 平台治理）"
              style="flex: 2; min-width: 200px"
            />
          </div>
          <div class="btn-row" style="flex-wrap: wrap; gap: 8px; margin-bottom: 12px">
            <input
              v-model="integrityForm.sourceUrl"
              type="text"
              placeholder="可核查链接（http/https，可空）"
              style="flex: 2; min-width: 220px"
            />
            <input
              v-model="integrityForm.announcedOn"
              type="date"
              style="flex: 1; min-width: 140px"
              title="声明方公布的日期，可空"
            />
          </div>
          <textarea
            v-model="integrityForm.reason"
            rows="3"
            placeholder="登记理由（至少 10 字）：读者要能据此判断这次声明的依据"
            style="width: 100%; margin-bottom: 8px"
          ></textarea>
          <div class="btn-row" style="margin-bottom: 10px">
            <button
              class="btn"
              :disabled="integrityBusy === 'register'"
              @click="submitIntegrity"
            >
              {{ integrityBusy === 'register' ? '登记中…' : '登记声明' }}
            </button>
            <span v-if="integrityError" class="tiny" style="color: var(--danger, #b00)">{{
              integrityError
            }}</span>
            <span v-else-if="integrityNote" class="tiny faint">{{ integrityNote }}</span>
          </div>

          <AsyncBlock
            :loading="integrity.loading"
            :error="integrity.error"
            :error-code="integrity.errorCode"
            :empty="integrity.items.length === 0"
            empty-text="当前没有生效中的诚信声明。"
          >
            <div
              v-for="row in integrity.items"
              :key="String(row.id)"
              class="result-item"
              style="padding: 10px 0"
            >
              <div class="result-meta">
                <span class="badge danger">{{ String(row.typeLabel ?? row.type ?? '') }}</span>
                <span class="tiny">{{ String(row.declaredBy ?? '') }}</span>
                <span class="mono tiny">{{ String(row.achievementId ?? '') }}</span>
                <span class="tiny faint">登记 {{ String(row.recordedAt ?? '') }}</span>
              </div>
              <div class="small" style="margin-top: 6px">{{ String(row.reason ?? '') }}</div>
              <div v-if="row.sourceUrl" class="tiny" style="margin-top: 4px">
                <a :href="String(row.sourceUrl)" target="_blank" rel="noopener noreferrer">
                  {{ String(row.sourceUrl) }}
                </a>
              </div>
              <div class="btn-row" style="margin-top: 8px">
                <button
                  class="btn small"
                  @click="revokeTarget = revokeTarget === String(row.id) ? '' : String(row.id)"
                >
                  {{ revokeTarget === String(row.id) ? '取消' : '撤销' }}
                </button>
              </div>
              <div v-if="revokeTarget === String(row.id)" style="margin-top: 6px">
                <textarea
                  v-model="revokeReason"
                  rows="2"
                  placeholder="撤销理由（至少 10 字）：事后要能回答这条声明为什么被推翻"
                  style="width: 100%; margin-bottom: 6px"
                ></textarea>
                <button
                  class="btn small"
                  :disabled="integrityBusy === `revoke:${String(row.id)}`"
                  @click="revokeIntegrity(String(row.id))"
                >
                  {{ integrityBusy === `revoke:${String(row.id)}` ? '撤销中…' : '确认撤销' }}
                </button>
              </div>
            </div>
          </AsyncBlock>
        </div>
      </div>
    </div>
  </div>
</template>
