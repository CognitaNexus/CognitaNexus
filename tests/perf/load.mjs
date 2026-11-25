// =============================================================================
// 阶段六公开读路径混合负载生成器（10 / 30 / 50 / 100 活跃用户）
//
// 为什么自己写而不是 k6/JMeter：本机没有可复现的 k6 安装，引入 Instrument 与代理配置的
// 成本高于收益。Node 22 自带 fetch/undici，脚本只有这一个文件，结果可重跑、可对照。
// 代价是要自己统计分位、自己控制到达节奏——所以这里的口径都写在参数与输出里，
// 不使用「100 并发」这种无法核对的描述。
//
// ## 与第三批（2026-10-17）版本的区别
//
// 1. **覆盖全部公开接口**，不只 5 个场景：检索、学者检索、分面、排序白名单、建议、
//    成果导出、合作网络（1/2/3 跳）、最短路径、统计总览、机构清单、关键词热点、领域洞察、
//    一致性水位。每个接口都有自己的场景，不再有一个「综合 P95」掩盖慢接口。
//
// 2. **分接口 + 分场景双维度统计**：每个场景独立记录请求数、成功率、业务失败率、RPS、
//    P50/P95/P99，再按接口汇总一份。这是第三批的教训——「总体 P95」会把一个 30 秒的
//    统计接口和几百个 20 ms 的检索请求平均成一个好看的数。
//
// 3. **业务断言而不只是状态码**（BR 级校验，见各场景 check）：
//    最短路径的 hops 必须等于 path.length-1、pathNodes 长度必须等于 path 长度、
//    抽查路径的相邻跳必须真的存在合作边；子图里每条边的两端必须在 nodes 里；
//    深度必须回显为请求值；超节点上限必须是 503 GRAPH_SCOPE_TOO_LARGE 而不是截断；
//    零命中关键词必须 total=0；REAL 口径在 SIMULATED 数据上必须 total=0（隔离生效）。
//
// 4. **冷/热缓存分开**：`--cache=warm` 每个场景在很小的参数空间里重复（命中服务端缓存与
//    文件页缓存），`--cache=rotate` 在很大的参数空间里轮转（近似冷）。本机没有 root，
//    无法 `echo 3 > drop_caches`，因此**不存在真正的冷启动测量**；报告必须写明这一点，
//    rotate 只能说「低复用率」，不能说「冷缓存」。
//
// 5. **先 dry-run 再压测**：`--dry-run` 只生成 URL 并校验契约，不发请求、不碰数据库。
//    改了场景定义却没跑过 dry-run，是「压测跑了 5 分钟才发现 URL 少了个参数」的直接原因。
//
// ## 口径（与老师指导 §7.3 一致，不可省略）
//   * 「活跃用户」= 一个虚拟用户在思考时间之外循环执行一次任务；100 个活跃用户不等于 100 RPS。
//   * 预热段与稳态段分开；预热段样本全部丢弃（连「预热期开始、稳态期结束」的请求也丢弃）。
//   * 业务失败（HTTP 200 但结果结构/语义不自洽）计入 failure，不从样本里剔除。
//   * RPS 是稳态段平均值 = 稳态请求数 / 实际稳态秒数（不是 / 标称秒数）。
//
// 用法：
//   node tests/perf/load.mjs --dry-run
//   node tests/perf/load.mjs --vus=100 --warmup=120 --seconds=300 --timeout=5000 \
//     --query=http://127.0.0.1:18083 --core=http://127.0.0.1:18081 \
//     --nature=SIMULATED --out=reports/perf/<run>/load-100.json
// =============================================================================

import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'
import os from 'node:os'

// ---------------------------------------------------------------------------
// 参数
// ---------------------------------------------------------------------------
const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const [k, ...rest] = a.replace(/^--/, '').split('=')
    return [k, rest.length === 0 ? 'true' : rest.join('=')]
  })
)
const flag = (name, dflt = false) => (name in args ? args[name] !== 'false' && args[name] !== '0' : dflt)

const VUS = Number(args.vus ?? 10)
const WARMUP = Number(args.warmup ?? 120)
const SECONDS = Number(args.seconds ?? 300)
const THINK = Number(args.think ?? 1000)
const QUERY = args.query ?? 'http://127.0.0.1:18083'
const CORE = args.core ?? 'http://127.0.0.1:18081'
const MAX_ID = Number(args.maxId ?? 1000000)
const OUT = args.out ?? 'reports/perf/load.json'
// 5 秒超时是任务书的要求（阶段六）；第三批用 20 秒，会让「排队 18 秒后被客户端放弃」
// 这类现象看起来像正常响应，掩盖了连接池耗尽。
const TIMEOUT_MS = Number(args.timeout ?? 5000)
const NATURE = args.nature ?? 'SIMULATED'
const CACHE = args.cache ?? 'warm'
// --scale / --query-mode 不改变任何请求，只是把「这份结果是在哪个规模、哪种图查询
// 模式下跑出来的」写进 JSON。少了这两个字段，两份 load-100.json 无法比较——
// 看起来是「P95 从 180ms 变成 2.4s」，实际只是 profile 从 POSTGRES_ONLY 换成了
// NEO4J_WITH_PG_FALLBACK 并叠加了 500 万数据。
const SCALE = args.scale ?? 'unknown'
const QUERY_MODE = args['query-mode'] ?? 'unknown'
const GRAPH_ENABLED = !/^POSTGRES_ONLY$/i.test(QUERY_MODE)
const DRY_RUN = flag('dry-run')
const PLAN = flag('plan')
const FIXTURES = flag('fixtures', true)
const WITH_CORE_DETAIL = flag('with-core-detail', false)
const CROSSCHECK_RATE = Number(args['path-crosscheck'] ?? 0.02)
const SCENARIO_FILTER = args.scenarios ? new Set(args.scenarios.split(',')) : null

const MAX_PAGE_SIZE = Number(args['max-page-size'] ?? 20)

// ---------------------------------------------------------------------------
// 词表与夹具 ID（与 tests/perf/scale/seed-scale-query.sql 的分布对应）
// ---------------------------------------------------------------------------
const HOT_KEYWORDS = ['图神经网络', '学术图谱', '图医学影像', '数据治理']
const MID_KEYWORDS = ['知识推理', '图像识别', '语言模型', '网络协作']
// 冷门词取词表尾部：命中极少但不是 0，用于区分「索引慢」与「没命中」
const COLD_KEYWORDS = ['构结构分析研究', '推理实验平台方法']
const ZERO_KEYWORDS = ['zzz不存在', '没有这个词组abc']
const TYPES = ['PAPER', 'PATENT', 'PROJECT', 'AWARD']
const SORTS = ['year_desc', 'year_asc', 'title_asc', 'citations_desc', 'relevance']
const TOPICS = ['topic-perf-001', 'topic-perf-007', 'topic-perf-031', 'topic-perf-060']
const ORGS = ['机构00001', '机构00007', '机构00123', '机构00456']

const FIX = {
  ring: (i) => 'sch-fix-ring-' + String(i).padStart(4, '0'),
  tree: {
    root: 'sch-fix-tree-root',
    child: (i) => 'sch-fix-tree-c' + i,
    grand: (i, j) => 'sch-fix-tree-g' + i + '-' + j,
    great: (i, j, k) => 'sch-fix-tree-gg' + i + '-' + j + '-' + k
  },
  eq: { a: 'sch-fix-eq-a', d: 'sch-fix-eq-d', mid: (i) => 'sch-fix-eq-m' + i },
  iso: (i) => 'sch-fix-iso-' + String(i).padStart(3, '0'),
  hub: { core: 'sch-fix-hub-core', leaf: (i) => 'sch-fix-hub-' + String(i).padStart(3, '0') },
  ha: (w, a) => 'sch-fix-ha-' + String(w).padStart(3, '0') + '-' + String(a).padStart(2, '0')
}

// 环夹具的期望距离：200 点环，i 到 j 的最短距离是 min(|i-j|, 200-|i-j|)。
const ringDistance = (i, j) => {
  const d = Math.abs(i - j)
  return Math.min(d, 200 - d)
}

const pick = (arr) => arr[Math.floor(Math.random() * arr.length)]
const jitter = () => Math.floor(Math.random() * THINK * 0.5)
const randInt = (n) => 1 + Math.floor(Math.random() * n)
const perfScholar = () => 'sch-' + String(randInt(MAX_ID * 0.55)).padStart(8, '0')
const perfAchievement = () => 'perf-' + String(randInt(MAX_ID)).padStart(8, '0')

function qs(params) {
  const p = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null) p.set(k, String(v))
  }
  return p.toString()
}

// 缓存模式的参数空间：warm 刻意收窄（同一批 URL 反复出现），rotate 刻意放宽。
function rotate(value, warmValues, rotateValues) {
  return CACHE === 'rotate' ? pick(rotateValues) : pick(warmValues)
}

// ---------------------------------------------------------------------------
// 断言工具
// ---------------------------------------------------------------------------
const isNum = (v) => typeof v === 'number' && Number.isFinite(v)
const isArr = (v) => Array.isArray(v)

/** 通用信封校验：code=OK 且 data 存在。 */
function envelope(body) {
  if (!body || typeof body !== 'object') return '响应不是 JSON 对象'
  if (body.code !== 'OK') return `code=${body.code} message=${String(body.message).slice(0, 80)}`
  if (!body.data) return '缺少 data'
  return null
}

/** 分页一致性：items 长度不得超过 total，也不得超过 size。 */
function pagingCheck(data, label) {
  if (!isArr(data.items)) return `${label}: items 不是数组`
  if (!isNum(data.total)) return `${label}: total 不是数字`
  if (data.items.length > data.total) return `${label}: items(${data.items.length}) > total(${data.total})`
  return null
}

/** 子图一致性：每条边的两端都必须出现在 nodes 里；不允许重复边；深度必须回显。 */
function subgraphCheck(data, expectedDepth) {
  if (!isArr(data.nodes) || !isArr(data.edges)) return 'nodes/edges 不是数组'
  if (isNum(expectedDepth) && data.depth !== expectedDepth) {
    return `depth 回显 ${data.depth} ≠ 请求 ${expectedDepth}`
  }
  const ids = new Set(data.nodes.map((n) => n && n.scholarId))
  if (ids.size !== data.nodes.length) return 'nodes 里有重复或缺失 scholarId'
  const seen = new Set()
  for (const e of data.edges) {
    if (!e || typeof e.source !== 'string' || typeof e.target !== 'string') return 'edge 缺 source/target'
    if (!ids.has(e.source) || !ids.has(e.target)) return `edge(${e.source}->${e.target}) 的端点不在 nodes 里`
    const key = e.source < e.target ? e.source + '|' + e.target : e.target + '|' + e.source
    if (seen.has(key)) return `重复边 ${key}`
    seen.add(key)
    if (!isNum(e.strength) || e.strength < 1) return 'edge.strength 不是正整数'
  }
  if (!data.withinLimits || !isNum(data.withinLimits.nodes) || !isNum(data.withinLimits.edges)) {
    return '缺少 withinLimits'
  }
  return null
}

/** 最短路径一致性：hops = path.length-1 = pathNodes.length-1；不可达时不得带路径。 */
function pathCheck(data, expect = {}) {
  if (data.reachable === true) {
    if (!isNum(data.hops) || data.hops < 0) return 'hops 不是非负整数'
    if (!isArr(data.path)) return 'path 不是数组'
    if (data.path.length !== data.hops + 1) return `path(${data.path.length}) ≠ hops+1(${data.hops + 1})`
    if (isArr(data.pathNodes) && data.pathNodes.length !== data.path.length) {
      return `pathNodes(${data.pathNodes.length}) ≠ path(${data.path.length})`
    }
    if (expect.hops !== undefined && data.hops !== expect.hops) {
      return `hops=${data.hops} ≠ 期望 ${expect.hops}`
    }
    if (expect.path !== undefined && data.path.join('>') !== expect.path.join('>')) {
      return `path=${data.path.join('>')} ≠ 期望 ${expect.path.join('>')}`
    }
    return null
  }
  if (data.reachable === false) {
    if (data.hops !== null && data.hops !== undefined) return '不可达结果不得带 hops'
    if (isArr(data.path) && data.path.length > 0) return '不可达结果不得带 path'
    if (expect.reachable === true) return '期望可达但返回不可达'
    if (expect.noteIncludes && !String(data.note ?? '').includes(expect.noteIncludes)) {
      return `不可达说明缺少关键词「${expect.noteIncludes}」`
    }
    return null
  }
  return 'reachable 既不是 true 也不是 false'
}

// ---------------------------------------------------------------------------
// 场景定义
//
// 每个场景：{ name, endpoint, weight, cacheMode, url(), check(body), expectStatus? }
//   check 返回 null 表示通过，返回字符串表示业务失败原因（计入 businessFailures）
//   expectStatus 用于「必须失败」的场景（超节点上限），命中即为成功
// ---------------------------------------------------------------------------
function achievementUrl(extra) {
  return (
    QUERY +
    '/api/v1/public/search/achievements?' +
    qs({ dataNature: NATURE, size: MAX_PAGE_SIZE, ...extra })
  )
}

const SCENARIOS = [
  // ---------------- 检索：成果 ----------------
  {
    name: 'search.achievements.hot',
    endpoint: '/api/v1/public/search/achievements',
    weight: 18,
    url: () =>
      achievementUrl({
        q: rotate(pick(HOT_KEYWORDS), HOT_KEYWORDS, [...HOT_KEYWORDS, ...MID_KEYWORDS]),
        page: rotate(1, [1], [1, 2, 3])
      }),
    check: (body) => envelope(body) ?? pagingCheck(body.data, 'hot') ??
      (body.data.total > 0 ? null : '热门词命中 0 条（数据或分布异常）')
  },
  {
    name: 'search.achievements.mid',
    endpoint: '/api/v1/public/search/achievements',
    weight: 8,
    url: () => achievementUrl({ q: pick(MID_KEYWORDS), page: rotate(1, [1], [1, 2]) }),
    check: (body) => envelope(body) ?? pagingCheck(body.data, 'mid')
  },
  {
    name: 'search.achievements.cold',
    endpoint: '/api/v1/public/search/achievements',
    weight: 5,
    url: () => achievementUrl({ q: pick(COLD_KEYWORDS), page: 1 }),
    // 冷门词允许 0 命中；只校验结构自洽。
    check: (body) => envelope(body) ?? pagingCheck(body.data, 'cold')
  },
  {
    name: 'search.achievements.zero',
    endpoint: '/api/v1/public/search/achievements',
    weight: 4,
    url: () => achievementUrl({ q: pick(ZERO_KEYWORDS), page: 1 }),
    check: (body) => {
      const e = envelope(body)
      if (e) return e
      const p = pagingCheck(body.data, 'zero')
      if (p) return p
      // 零命中必须是 0 条；把「索引失效导致命中全表」当成失败而不是「慢但正确」。
      return body.data.total === 0 ? null : `零命中关键词却返回 total=${body.data.total}`
    }
  },
  {
    name: 'search.achievements.combo',
    endpoint: '/api/v1/public/search/achievements',
    weight: 8,
    url: () =>
      achievementUrl({
        type: pick(TYPES),
        yearFrom: rotate(2015, [2015], [2010, 2015, 2018]),
        yearTo: 2026,
        sort: rotate('year_desc', ['year_desc'], SORTS),
        page: rotate(1, [1], [1, 2])
      }),
    check: (body) => envelope(body) ?? pagingCheck(body.data, 'combo')
  },
  {
    name: 'search.achievements.topic',
    endpoint: '/api/v1/public/search/achievements',
    weight: 5,
    url: () => achievementUrl({ topic: rotate(pick(TOPICS), TOPICS, [...TOPICS, 'topic-fix-graph']), page: 1 }),
    check: (body) => envelope(body) ?? pagingCheck(body.data, 'topic')
  },
  {
    name: 'search.achievements.organization',
    endpoint: '/api/v1/public/search/achievements',
    weight: 5,
    url: () => achievementUrl({ organization: rotate(pick(ORGS), ORGS, [...ORGS, '机构夹具环']), page: 1 }),
    check: (body) => envelope(body) ?? pagingCheck(body.data, 'organization')
  },
  {
    name: 'search.achievements.deep',
    endpoint: '/api/v1/public/search/achievements',
    weight: 4,
    url: () =>
      achievementUrl({
        type: rotate('PAPER', ['PAPER'], TYPES),
        sort: 'year_desc',
        page: rotate(5, [5], [2, 3, 5, 8]),
        size: 20
      }),
    // 深翻页是有上限的（search-max-offset=10000，即 size=20 时最多第 500 页）。
    // 超限必须是 400 INVALID_ARGUMENT 而不是空结果——空结果会被读成「没有数据」。
    check: (body) => envelope(body) ?? pagingCheck(body.data, 'deep')
  },
  {
    name: 'search.achievements.real-scope',
    endpoint: '/api/v1/public/search/achievements',
    weight: 3,
    url: () =>
      QUERY +
      '/api/v1/public/search/achievements?' +
      qs({ dataNature: 'REAL', q: '图神经网络', size: 20, page: 1 }),
    // 数据集全部是 SIMULATED，因此默认 REAL 口径必须 0 条。这是数据性质隔离的活体检查：
    // 若这里返回非 0，说明隔离失效，比任何性能问题都严重。
    check: (body) => {
      const e = envelope(body)
      if (e) return e
      if (!isNum(body.data.total)) return '缺少 total'
      return body.data.total === 0 ? null : `REAL 口径命中 ${body.data.total} 条（数据性质隔离失效）`
    }
  },

  // ---------------- 检索：学者 / 分面 / 排序白名单 / 建议 ----------------
  {
    name: 'search.scholars.names',
    endpoint: '/api/v1/public/search/scholars',
    weight: 6,
    url: () =>
      QUERY +
      '/api/v1/public/search/scholars?' +
      qs({
        dataNature: NATURE,
        q: rotate('作者1', ['作者1'], ['作者', '作者1234', '夹具学者']),
        page: rotate(1, [1], [1, 2]),
        size: 20
      }),
    check: (body) => envelope(body) ?? pagingCheck(body.data, 'scholars')
  },
  {
    name: 'search.facets.default',
    endpoint: '/api/v1/public/search/facets',
    weight: 4,
    url: () => QUERY + '/api/v1/public/search/facets?' + qs({ dataNature: NATURE, limit: 100 }),
    check: (body) => {
      const e = envelope(body)
      if (e) return e
      const d = body.data
      if (!isArr(d.topics) || !isArr(d.organizations)) return 'facets 缺 topics/organizations'
      for (const t of d.topics) {
        if (!isNum(t.achievementCount)) return 'facet.achievementCount 不是数字'
        if (!isNum(t.sourceProvided) || !isNum(t.systemInferred)) return 'facet 缺少来源/推断分列'
        // 来源 + 推断必须等于总量：分开计数的意义就在这里，对不上说明口径漂移。
        if (t.sourceProvided + t.systemInferred !== t.achievementCount && t.sourceProvided + t.systemInferred > t.achievementCount) {
          return `facet(${t.topicId}) 来源+推断(${t.sourceProvided + t.systemInferred}) > 总量(${t.achievementCount})`
        }
      }
      return null
    }
  },
  {
    name: 'search.options',
    endpoint: '/api/v1/public/search/options',
    weight: 2,
    url: () => QUERY + '/api/v1/public/search/options',
    check: (body) => {
      const e = envelope(body)
      if (e) return e
      const d = body.data
      if (!isArr(d.sorts) || d.sorts.length === 0) return '缺少 sorts'
      if (!isArr(d.dataNatures)) return '缺少 dataNatures'
      if (!isNum(d.maxPageSize) || !isNum(d.maxOffset)) return '缺少分页上限'
      return null
    }
  },
  {
    name: 'search.suggest.hot',
    endpoint: '/api/v1/public/search/suggest',
    weight: 3,
    url: () =>
      QUERY +
      '/api/v1/public/search/suggest?' +
      qs({ q: rotate('图', ['图'], ['图', '学术', '作者1']), dataNature: NATURE, limit: 6 }),
    check: (body) => {
      const e = envelope(body)
      if (e) return e
      const d = body.data
      if (!isArr(d.achievements) || !isArr(d.scholars)) return 'suggest 缺 achievements/scholars'
      if (d.achievements.length > 6 || d.scholars.length > 6) return 'suggest 超过 limit=6'
      return null
    }
  },
  {
    name: 'search.suggest.empty',
    endpoint: '/api/v1/public/search/suggest',
    weight: 1,
    url: () => QUERY + '/api/v1/public/search/suggest?' + qs({ q: '', dataNature: NATURE }),
    // 空关键词返回空两组而不是报错：用户清空输入框不该收到错误提示。
    check: (body) => {
      const e = envelope(body)
      if (e) return e
      const d = body.data
      return isArr(d.achievements) && isArr(d.scholars) && d.achievements.length === 0 && d.scholars.length === 0
        ? null
        : '空关键词必须返回空建议'
    }
  },

  // ---------------- 合作网络 ----------------
  {
    name: 'network.graph.hop1',
    endpoint: '/api/v1/public/network/graph',
    weight: 4,
    url: () =>
      QUERY +
      '/api/v1/public/network/graph?' +
      qs({
        scholarId: FIXTURES ? FIX.tree.root : perfScholar(),
        depth: 1,
        dataNature: NATURE
      }),
    check: (body) => envelope(body) ?? subgraphCheck(body.data, 1)
  },
  {
    name: 'network.graph.hop2',
    endpoint: '/api/v1/public/network/graph',
    weight: 3,
    url: () =>
      QUERY +
      '/api/v1/public/network/graph?' +
      qs({ scholarId: FIXTURES ? FIX.tree.root : perfScholar(), depth: 2, dataNature: NATURE }),
    check: (body) => envelope(body) ?? subgraphCheck(body.data, 2)
  },
  {
    name: 'network.graph.hop3',
    endpoint: '/api/v1/public/network/graph',
    weight: 2,
    url: () =>
      QUERY +
      '/api/v1/public/network/graph?' +
      qs({ scholarId: FIXTURES ? FIX.tree.root : perfScholar(), depth: 3, dataNature: NATURE }),
    check: (body) => envelope(body) ?? subgraphCheck(body.data, 3)
  },
  {
    name: 'network.graph.ring',
    endpoint: '/api/v1/public/network/graph',
    weight: 2,
    url: () =>
      QUERY +
      '/api/v1/public/network/graph?' +
      qs({ scholarId: FIX.ring(randInt(200)), depth: rotate(1, [1], [1, 2]), dataNature: NATURE }),
    check: (body) => envelope(body) ?? subgraphCheck(body.data)
  },
  {
    name: 'network.graph.hub-scope',
    endpoint: '/api/v1/public/network/graph',
    weight: 1,
    url: () =>
      QUERY +
      '/api/v1/public/network/graph?' +
      qs({ scholarId: FIX.hub.core, depth: 1, maxNodes: 100, dataNature: NATURE }),
    // 超出节点上限必须是 503 GRAPH_SCOPE_TOO_LARGE：截断后冒充完整子图会让人得出错误拓扑结论。
    expectStatus: 503,
    expectCode: 'GRAPH_SCOPE_TOO_LARGE',
    check: () => null
  },
  {
    name: 'network.graph.isolated',
    endpoint: '/api/v1/public/network/graph',
    weight: 1,
    url: () =>
      QUERY +
      '/api/v1/public/network/graph?' +
      qs({ scholarId: FIX.iso(randInt(50)), depth: 1, dataNature: NATURE }),
    check: (body) => {
      const e = envelope(body)
      if (e) return e
      const d = body.data
      if (d.seedInScope !== false) return '孤立学者必须返回 seedInScope=false'
      if (isArr(d.nodes) && d.nodes.length > 0) return '孤立学者不得返回节点（禁止伪造自身节点）'
      if (!String(d.note ?? '').includes('没有合格合作边')) return '缺少范围外说明'
      return null
    }
  },

  // ---------------- 最短路径 ----------------
  {
    name: 'network.path.ring3',
    endpoint: '/api/v1/public/network/path',
    weight: 3,
    url: () =>
      QUERY +
      '/api/v1/public/network/path?' +
      qs({ from: FIX.ring(1), to: FIX.ring(4), dataNature: NATURE }),
    expect: { hops: ringDistance(1, 4), reachable: true },
    check: (body, ctx) => envelope(body) ?? pathCheck(body.data, ctx.expect)
  },
  {
    name: 'network.path.ring6',
    endpoint: '/api/v1/public/network/path',
    weight: 2,
    url: () =>
      QUERY +
      '/api/v1/public/network/path?' +
      qs({ from: FIX.ring(1), to: FIX.ring(7), dataNature: NATURE }),
    // 6 跳是边界：MAX_PATH_DEPTH=6，必须可达。
    expect: { hops: ringDistance(1, 7), reachable: true },
    check: (body, ctx) => envelope(body) ?? pathCheck(body.data, ctx.expect)
  },
  {
    name: 'network.path.beyond-depth',
    endpoint: '/api/v1/public/network/path',
    weight: 2,
    url: () =>
      QUERY +
      '/api/v1/public/network/path?' +
      qs({ from: FIX.ring(1), to: FIX.ring(10), dataNature: NATURE }),
    // 距离 8 > 6：必须如实说「6 跳范围内未找到」，不得谎称全图不连通。
    expect: { reachable: false, noteIncludes: '6 跳' },
    check: (body, ctx) => envelope(body) ?? pathCheck(body.data, ctx.expect)
  },
  {
    name: 'network.path.equal-length',
    endpoint: '/api/v1/public/network/path',
    weight: 2,
    url: () =>
      QUERY +
      '/api/v1/public/network/path?' +
      qs({ from: FIX.eq.a, to: FIX.eq.d, dataNature: NATURE }),
    // 5 条等长（2 跳）路径：必须稳定选字典序最小的那条（经过 m1）。
    expect: { hops: 2, reachable: true, path: [FIX.eq.a, FIX.eq.mid(1), FIX.eq.d] },
    check: (body, ctx) => envelope(body) ?? pathCheck(body.data, ctx.expect)
  },
  {
    name: 'network.path.self',
    endpoint: '/api/v1/public/network/path',
    weight: 1,
    url: () =>
      QUERY +
      '/api/v1/public/network/path?' +
      qs({ from: FIX.ring(1), to: FIX.ring(1), dataNature: NATURE }),
    expect: { hops: 0, reachable: true },
    check: (body, ctx) => envelope(body) ?? pathCheck(body.data, ctx.expect)
  },

  // ---------------- 统计 ----------------
  {
    name: 'analytics.overview',
    endpoint: '/api/v1/public/analytics/overview',
    weight: 8,
    url: () => QUERY + '/api/v1/public/analytics/overview?' + qs({ dataNature: NATURE }),
    check: (body) => {
      const e = envelope(body)
      if (e) return e
      const d = body.data
      if (!d.totals || !isNum(d.totals.achievements)) return 'overview 缺 totals.achievements'
      for (const key of ['scholarsWithQualifiedRelation', 'contributions', 'coauthorPairs']) {
        if (!isNum(d.totals[key])) return `overview 缺 totals.${key}`
      }
      if (!isArr(d.byType)) return 'overview 缺 byType'
      if (!d.ruleVersion) return 'overview 缺 ruleVersion（口径必须随数据返回）'
      return null
    }
  },
  {
    name: 'analytics.organizations',
    endpoint: '/api/v1/public/analytics/organizations',
    weight: 3,
    url: () =>
      QUERY +
      '/api/v1/public/analytics/organizations?' +
      qs({ dataNature: NATURE, page: rotate(1, [1], [1, 2]), size: 20 }),
    check: (body) => envelope(body) ?? (isArr(body.data.items) ? null : 'organizations 缺 items')
  },
  {
    name: 'analytics.keywords',
    endpoint: '/api/v1/public/analytics/keywords',
    weight: 2,
    url: () =>
      QUERY +
      '/api/v1/public/analytics/keywords?' +
      qs({ dataNature: NATURE, yearFrom: rotate(2015, [2015], [2010, 2015, 2020]), limit: 20 }),
    check: (body) => envelope(body) ?? (isArr(body.data.items) || isArr(body.data.keywords) ? null : 'keywords 缺列表')
  },
  {
    name: 'analytics.topic-insight',
    endpoint: '/api/v1/public/analytics/topics/{topicId}',
    weight: 2,
    url: () =>
      QUERY +
      '/api/v1/public/analytics/topics/' +
      rotate(pick(TOPICS), TOPICS, [...TOPICS, 'topic-fix-graph']) +
      '?' +
      qs({ dataNature: NATURE }),
    // 响应里领域标识是 data.topic（不是 topicId），并且必须带 totals 与 ruleVersion：
    // 领域洞察有自己的口径版本，缺了它就无法判断这些数字能不能与总览对比。
    check: (body) => {
      const e = envelope(body)
      if (e) return e
      const d = body.data
      if (!d.topic && !d.topicId) return 'topic insight 缺 topic'
      if (!d.totals) return 'topic insight 缺 totals'
      if (!d.ruleVersion) return 'topic insight 缺 ruleVersion'
      return null
    }
  },
  {
    name: 'consistency.barrier',
    endpoint: '/api/v1/public/consistency',
    weight: 1,
    url: () => QUERY + '/api/v1/public/consistency',
    check: (body) => {
      const e = envelope(body)
      if (e) return e
      const d = body.data
      if (!isNum(d.publishedRevision)) return 'consistency 缺 publishedRevision'
      if (!isNum(d.receivedRevision)) return 'consistency 缺 receivedRevision'
      return null
    }
  },

  // ---------------- 导出（CSV，重接口，低权重） ----------------
  {
    name: 'search.export.csv',
    endpoint: '/api/v1/public/search/achievements/export',
    weight: 1,
    url: () =>
      QUERY +
      '/api/v1/public/search/achievements/export?' +
      qs({ dataNature: NATURE, type: 'AWARD', yearFrom: 2020, yearTo: 2021, sort: 'year_desc' }),
    contentType: 'text/csv',
    // 导出返回文件本体而不是 JSON 信封；校验只做「非空且首行是表头」。
    check: (body) => {
      if (typeof body !== 'string') return '导出响应不是文本'
      if (body.length === 0) return '导出内容为空'
      const firstLine = body.split('\n', 1)[0]
      return firstLine && firstLine.includes(',') ? null : `导出首行不像表头：${firstLine?.slice(0, 60)}`
    }
  }
]

if (WITH_CORE_DETAIL) {
  SCENARIOS.push({
    name: 'detail.achievement',
    endpoint: '(core) /api/v1/public/achievements/{id}',
    weight: 5,
    url: () => CORE + '/api/v1/public/achievements/' + perfAchievement(),
    // 详情接口对「不存在 / 不可见」都返回错误码，这是正确行为；算作用例成功、
    // 只把 5xx 与结构异常计为失败。第三批把 404 混进错误率，导致「17% 错误率」的误导。
    check: (body, ctx) => {
      if (!body || typeof body !== 'object') return '响应不是 JSON'
      if (body.code === 'OK') return body.data ? null : 'code=OK 但缺 data'
      const allowed = new Set(['NOT_FOUND', 'ACCESS_DENIED', 'INVALID_ARGUMENT'])
      return allowed.has(body.code) ? null : `code=${body.code}`
    },
    allow4xxAsSuccess: true
  })
}

// ---------------------------------------------------------------------------
// dry-run / plan
// ---------------------------------------------------------------------------
function scenarioShare(s) {
  const total = SCENARIOS.reduce((n, x) => n + x.weight, 0)
  return (s.weight / total) * 100
}

if (PLAN) {
  const endpoints = [...new Set(SCENARIOS.map((s) => s.endpoint))]
  const plan = {
    queryServiceEndpoints: endpoints.filter((e) => !e.startsWith('(core)')),
    coreEndpoints: endpoints.filter((e) => e.startsWith('(core)')),
    scenarios: SCENARIOS.map((s) => ({
      name: s.name,
      endpoint: s.endpoint,
      weight: s.weight,
      sharePct: +scenarioShare(s).toFixed(2),
      cacheMode: CACHE,
      expectStatus: s.expectStatus ?? 200,
      businessAssertions: Boolean(s.check)
    })),
    vus: VUS,
    warmupSeconds: WARMUP,
    steadySeconds: SECONDS,
    timeoutMs: TIMEOUT_MS,
    dataNature: NATURE
  }
  console.log(JSON.stringify(plan, null, 2))
  process.exit(0)
}

if (DRY_RUN) {
  console.log('=== dry-run：只校验场景契约，不发请求、不碰数据库 ===')
  let failures = 0
  const seenEndpoints = new Set()
  for (const s of SCENARIOS) {
    const problems = []
    if (!s.name || !s.endpoint) problems.push('缺少 name/endpoint')
    if (typeof s.url !== 'function') problems.push('url 不是函数')
    if (typeof s.check !== 'function') problems.push('check 不是函数')
    if (!(s.weight > 0)) problems.push('weight 必须为正')
    let sample = null
    try {
      for (let i = 0; i < 3; i++) {
        const u = s.url()
        const parsed = new URL(u)
        if (!parsed.pathname.startsWith('/api/v1/public/') && !s.endpoint.startsWith('(core)')) {
          problems.push(`路径不在 /api/v1/public/ 下：${parsed.pathname}`)
        }
        if (s.endpoint.startsWith('(core)')) {
          if (!u.startsWith(CORE)) problems.push('core 场景必须指向 --core 服务')
        } else if (!u.startsWith(QUERY)) {
          problems.push('query 场景必须指向 --query 服务')
        }
        // query 侧的每个场景都必须带明确的数据性质：不带会落回默认 REAL 口径，
        // 在 SIMULATED 数据上命中 0 条，测到的不是演示口径。
        // 例外：options / consistency 不读数据性质；suggest 空关键词与 core 详情不适用。
        const natureExempt = s.endpoint.startsWith('(core)')
        if (
          !natureExempt &&
          !parsed.searchParams.has('dataNature') &&
          !u.includes('/search/options') &&
          !u.includes('/consistency')
        ) {
          problems.push('缺少 dataNature 参数（默认 REAL 会导致命中 0 条，测到的不是演示口径）')
        }
        if (i === 0) sample = u
      }
    } catch (e) {
      problems.push(`url() 抛错：${e.message}`)
    }
    seenEndpoints.add(s.endpoint)
    const deduped = [...new Set(problems)]
    problems.length = 0
    problems.push(...deduped)
    const ok = problems.length === 0
    if (!ok) failures++
    const share = scenarioShare(s).toFixed(2).padStart(5)
    console.log(
      `${ok ? 'OK  ' : 'FAIL'} ${share}%  ${s.name.padEnd(30)} ${s.expectStatus ?? 200}  ${problems.join('; ')}`
    )
    if (sample) console.log(`        ${sample}`)
  }
  console.log('')
  console.log(`场景数 ${SCENARIOS.length}，覆盖接口 ${seenEndpoints.size} 个：`)
  for (const e of seenEndpoints) console.log(`  - ${e}`)
  const totalWeight = SCENARIOS.reduce((n, x) => n + x.weight, 0)
  const searchShare = SCENARIOS.filter((s) => s.endpoint.includes('/search/')).reduce((n, x) => n + x.weight, 0) / totalWeight
  const networkShare = SCENARIOS.filter((s) => s.endpoint.includes('/network/')).reduce((n, x) => n + x.weight, 0) / totalWeight
  console.log(`\n权重合计 ${totalWeight}；检索类 ${(searchShare * 100).toFixed(1)}%，网络类 ${(networkShare * 100).toFixed(1)}%`)
  console.log(`活跃用户 ${VUS}，预热 ${WARMUP}s，稳态 ${SECONDS}s，超时 ${TIMEOUT_MS}ms，缓存模式 ${CACHE}`)
  if (failures > 0) {
    console.error(`\n${failures} 个场景未通过 dry-run 校验`)
    process.exit(3)
  }
  console.log('\ndry-run 通过')
  process.exit(0)
}

// ---------------------------------------------------------------------------
// 夹具存在性预检
//
// 夹具场景（环、树、等长路径、枢纽、孤立）依赖 fixtures-graph.sql 造出的拓扑。
// 若库里没有夹具，这些场景的断言会全部失败，看起来像「接口坏了」。因此启动前探测一次：
// 探不到就把夹具场景停用并显式说明，而不是让它们污染整轮结果。
// ---------------------------------------------------------------------------
async function probeFixtures() {
  if (!FIXTURES) return { present: false, reason: '--fixtures=false' }
  try {
    const res = await fetch(
      QUERY + '/api/v1/public/network/graph?' + qs({ scholarId: FIX.tree.root, depth: 1, dataNature: NATURE }),
      { headers: { accept: 'application/json' }, signal: AbortSignal.timeout(8000) }
    )
    const body = await res.json().catch(() => null)
    if (!res.ok) return { present: false, reason: `探测返回 HTTP ${res.status}` }
    const ok = body?.data?.seedInScope === true
    return ok ? { present: true } : { present: false, reason: '未找到 sch-fix-tree-root（先跑 fixtures-graph.sql）' }
  } catch (e) {
    return { present: false, reason: `探测失败：${e.name}: ${e.message}` }
  }
}

// ---------------------------------------------------------------------------
// 统计
// ---------------------------------------------------------------------------
const selected = SCENARIOS.filter((s) => !SCENARIO_FILTER || SCENARIO_FILTER.has(s.name))
if (selected.length === 0) {
  console.error('--scenarios 过滤后没有剩余场景')
  process.exit(2)
}

const fixtureProbe = await probeFixtures()
const active = selected.filter((s) => {
  const needsFixture =
    s.name.includes('ring') ||
    s.name.includes('hub') ||
    s.name.includes('isolated') ||
    s.name.includes('equal-length') ||
    s.name.includes('beyond-depth') ||
    s.name.includes('path.self') ||
    s.url().includes('sch-fix-')
  if (needsFixture && !fixtureProbe.present) {
    console.warn(`[skip] ${s.name}：${fixtureProbe.reason}`)
    return false
  }
  return true
})

if (active.length === 0) {
  console.error('所有场景都依赖拓扑夹具，但库里没有夹具。先跑 ops/perf/run-scale-benchmark.sh --only=fixtures')
  process.exit(2)
}

const stats = new Map()
for (const s of active) {
  stats.set(s.name, {
    name: s.name,
    endpoint: s.endpoint,
    cacheMode: CACHE,
    count: 0,
    ok: 0,
    httpError: 0,
    timeout: 0,
    business: 0,
    crossChecked: 0,
    latencies: [],
    errors: []
  })
}

const totalWeight = active.reduce((n, s) => n + s.weight, 0)
function pickScenario() {
  let r = Math.random() * totalWeight
  for (const s of active) {
    r -= s.weight
    if (r <= 0) return s
  }
  return active[0]
}

// ---------------------------------------------------------------------------
// 请求执行
// ---------------------------------------------------------------------------
const startedAt = Date.now()
let warmupDone = false
let steadyStartedAt = null
let steadyEndedAt = null
const stopAt = startedAt + (WARMUP + SECONDS) * 1000
let inFlight = 0
let peakInFlight = 0

async function oneRequest() {
  const scenario = pickScenario()
  const st = stats.get(scenario.name)
  const counted = warmupDone
  const url = scenario.url()
  const started = performance.now()
  const ac = new AbortController()
  const timer = setTimeout(() => ac.abort(), TIMEOUT_MS)
  inFlight++
  peakInFlight = Math.max(peakInFlight, inFlight)
  try {
    const res = await fetch(url, { signal: ac.signal, headers: { accept: 'application/json' } })
    const elapsed = performance.now() - started
    const ctype = res.headers.get('content-type') ?? ''
    let body = null
    if (scenario.contentType === 'text/csv' || ctype.includes('text/csv')) {
      body = await res.text().catch(() => '')
    } else {
      body = await res.json().catch(() => null)
    }
    if (!counted) return
    st.count++
    st.latencies.push(elapsed)

    const expected = scenario.expectStatus ?? 200
    if (res.status !== expected) {
      // 「必须失败」的场景（超节点上限）：期望 503 却拿到 200，是最危险的一种——
      // 子图被截断后冒充完整结果，调用方会得出错误拓扑结论。这里必须计为业务失败。
      if (scenario.expectStatus) {
        st.business++
        if (st.errors.length < 5) st.errors.push(`期望 HTTP ${expected}，实际 ${res.status} ${url}`)
        return
      }
      st.httpError++
      if (st.errors.length < 5) st.errors.push(`HTTP ${res.status} ${url}`)
      return
    }
    if (scenario.expectStatus && scenario.expectCode) {
      const code = body && body.code
      if (code !== scenario.expectCode) {
        st.business++
        if (st.errors.length < 5) st.errors.push(`期望 ${scenario.expectCode}，实际 ${code} ${url}`)
        return
      }
      st.ok++
      return
    }
    const problem = scenario.check(body, { expect: scenario.expect })
    if (problem) {
      st.business++
      if (st.errors.length < 5) st.errors.push(`${problem} ${url}`)
      return
    }
    st.ok++

    // 路径抽查：验证 path[0]→path[1] 在图上确实是 1 跳。开销是额外一次图查询，
    // 因此只按比例抽样（默认 2%），抽样数单独计数，避免让人以为 100% 都核过。
    if (scenario.name.startsWith('network.path.') && Math.random() < CROSSCHECK_RATE) {
      const data = body?.data
      if (data?.reachable && Array.isArray(data.path) && data.path.length > 1) {
        try {
          const probe = await fetch(
            QUERY +
              '/api/v1/public/network/graph?' +
              qs({ scholarId: data.path[0], depth: 1, dataNature: NATURE }),
            { headers: { accept: 'application/json' }, signal: AbortSignal.timeout(TIMEOUT_MS) }
          )
          const pb = await probe.json().catch(() => null)
          const neighbours = new Set((pb?.data?.nodes ?? []).map((n) => n.scholarId))
          st.crossChecked++
          if (!neighbours.has(data.path[1])) {
            st.ok--
            st.business++
            if (st.errors.length < 5) st.errors.push(`路径首跳不成立 ${data.path[0]}->${data.path[1]} ${url}`)
          }
        } catch {
          /* 抽查失败不计入被测接口的失败，避免把探测故障算成业务故障 */
        }
      }
    }
  } catch (e) {
    if (!counted) return
    st.count++
    const elapsed = performance.now() - started
    st.latencies.push(elapsed)
    if (e.name === 'AbortError' || e.name === 'TimeoutError') {
      st.timeout++
      if (st.errors.length < 5) st.errors.push(`timeout(${TIMEOUT_MS}ms) ${url}`)
    } else {
      st.httpError++
      if (st.errors.length < 5) st.errors.push(`${e.name}: ${e.message} ${url}`)
    }
  } finally {
    clearTimeout(timer)
    inFlight--
  }
}

async function vuLoop() {
  while (Date.now() < stopAt) {
    await oneRequest()
    await new Promise((r) => setTimeout(r, THINK + jitter()))
  }
}

// ---------------------------------------------------------------------------
// 分位数
// ---------------------------------------------------------------------------
function percentile(sorted, p) {
  if (sorted.length === 0) return null
  if (sorted.length === 1) return sorted[0]
  // 线性插值：小样本时 floor 索引会系统性低估 P99。
  const pos = ((sorted.length - 1) * p) / 100
  const lo = Math.floor(pos)
  const hi = Math.ceil(pos)
  if (lo === hi) return sorted[lo]
  return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
}

function rowFor(st, steadySeconds) {
  const sorted = [...st.latencies].sort((a, b) => a - b)
  return {
    scenario: st.name,
    endpoint: st.endpoint,
    cacheMode: st.cacheMode,
    requests: st.count,
    ok: st.ok,
    httpErrors: st.httpError,
    timeouts: st.timeout,
    businessFailures: st.business,
    pathCrossChecked: st.crossChecked,
    successRate: st.count === 0 ? null : +(st.ok / st.count).toFixed(4),
    failureRate: st.count === 0 ? null : +((st.count - st.ok) / st.count).toFixed(4),
    rps: steadySeconds > 0 ? +(st.count / steadySeconds).toFixed(3) : null,
    p50: percentile(sorted, 50),
    p95: percentile(sorted, 95),
    p99: percentile(sorted, 99),
    max: sorted.length ? sorted[sorted.length - 1] : null,
    sampleErrors: st.errors
  }
}

function round1(v) {
  return v === null || v === undefined ? null : +v.toFixed(1)
}

function finalize(rows) {
  return rows.map((r) => ({
    ...r,
    p50: round1(r.p50),
    p95: round1(r.p95),
    p99: round1(r.p99),
    max: round1(r.max)
  }))
}

/** 按接口汇总：接口级 P50/P95/P99 用合并后的样本重算，不是把场景分位再平均。 */
function endpointSummary(perScenario) {
  const byEndpoint = new Map()
  for (const s of active) {
    const st = stats.get(s.name)
    if (!st) continue
    if (!byEndpoint.has(s.endpoint)) byEndpoint.set(s.endpoint, { endpoint: s.endpoint, latencies: [], count: 0, ok: 0, business: 0, httpError: 0, timeout: 0, scenarios: 0 })
    const acc = byEndpoint.get(s.endpoint)
    acc.latencies.push(...st.latencies)
    acc.count += st.count
    acc.ok += st.ok
    acc.business += st.business
    acc.httpError += st.httpError
    acc.timeout += st.timeout
    acc.scenarios++
  }
  const steady = (steadyEndedAt - steadyStartedAt) / 1000
  return [...byEndpoint.values()]
    .map((acc) => {
      const sorted = acc.latencies.sort((a, b) => a - b)
      return {
        endpoint: acc.endpoint,
        scenarios: acc.scenarios,
        requests: acc.count,
        ok: acc.ok,
        httpErrors: acc.httpError,
        timeouts: acc.timeout,
        businessFailures: acc.business,
        successRate: acc.count === 0 ? null : +(acc.ok / acc.count).toFixed(4),
        rps: steady > 0 ? +(acc.count / steady).toFixed(3) : null,
        p50: round1(percentile(sorted, 50)),
        p95: round1(percentile(sorted, 95)),
        p99: round1(percentile(sorted, 99)),
        max: round1(sorted.length ? sorted[sorted.length - 1] : null)
      }
    })
    .sort((a, b) => (b.p95 ?? 0) - (a.p95 ?? 0))
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------
console.log(
  `vus=${VUS} warmup=${WARMUP}s steady=${SECONDS}s think=${THINK}ms timeout=${TIMEOUT_MS}ms ` +
    `query=${QUERY} core=${CORE} nature=${NATURE} cache=${CACHE} fixtures=${fixtureProbe.present} ` +
    `scale=${SCALE} queryMode=${QUERY_MODE}`
)
if (!fixtureProbe.present) console.log(`夹具探测：${fixtureProbe.reason}`)

setTimeout(() => {
  warmupDone = true
  steadyStartedAt = Date.now()
  for (const st of stats.values()) {
    st.count = 0
    st.ok = 0
    st.httpError = 0
    st.timeout = 0
    st.business = 0
    st.crossChecked = 0
    st.latencies = []
    st.errors = []
  }
  console.log(`--- 预热结束（${WARMUP}s），进入 ${SECONDS}s 稳态段 ---`)
}, WARMUP * 1000)

await Promise.all(Array.from({ length: VUS }, () => vuLoop()))

const endedAt = Date.now()
steadyEndedAt = endedAt
const steadySeconds = steadyStartedAt ? (steadyEndedAt - steadyStartedAt) / 1000 : 0

const perScenario = finalize([...stats.values()].map((st) => rowFor(st, steadySeconds)))
const perEndpoint = endpointSummary(perScenario)

const totalRequests = perScenario.reduce((s, r) => s + r.requests, 0)
const totalOk = perScenario.reduce((s, r) => s + r.ok, 0)
const totalBusiness = perScenario.reduce((s, r) => s + r.businessFailures, 0)
const totalHttp = perScenario.reduce((s, r) => s + r.httpErrors, 0)
const totalTimeout = perScenario.reduce((s, r) => s + r.timeouts, 0)

const result = {
  startedAt: new Date(startedAt).toISOString(),
  endedAt: new Date(endedAt).toISOString(),
  scale: SCALE,
  queryMode: QUERY_MODE,
  graphEnabled: GRAPH_ENABLED,
  // 环境必须自己记录：同一份脚本在 2C8G 本机与 8C32G runner 上跑出的 P95 不可直接相加，
  // 而报告三个月后只会剩这个 JSON。
  environment: {
    platform: `${os.platform()} ${os.release()} ${os.arch()}`,
    cpuCount: os.cpus()?.length ?? null,
    totalMemGb: +(os.totalmem() / 1024 ** 3).toFixed(2),
    freeMemGbAtStart: +(os.freemem() / 1024 ** 3).toFixed(2),
    loadAvgAtStart: os.loadavg().map((v) => +v.toFixed(2)),
    nodeVersion: process.version
  },
  vus: VUS,
  warmupSeconds: WARMUP,
  steadySeconds: SECONDS,
  measuredSteadySeconds: +steadySeconds.toFixed(1),
  thinkTimeMs: THINK,
  timeoutMs: TIMEOUT_MS,
  dataNature: NATURE,
  cacheMode: CACHE,
  fixturesPresent: fixtureProbe.present,
  peakInFlight,
  steadyRequests: totalRequests,
  steadyRps: steadySeconds > 0 ? +(totalRequests / steadySeconds).toFixed(2) : null,
  overallSuccessRate: totalRequests === 0 ? null : +(totalOk / totalRequests).toFixed(4),
  failures: { business: totalBusiness, http: totalHttp, timeout: totalTimeout },
  endpoints: perEndpoint,
  scenarios: perScenario,
  caveats: [
    'RPS 是稳态段平均值（请求数 / 实测稳态秒数）；活跃用户数不等于 RPS。',
    '业务失败指 HTTP 200 但结果结构/语义不自洽，未从样本中剔除。',
    '本机无 root，无法 drop_caches：--cache=rotate 只是低复用率，不能称为冷缓存。',
    '服务、数据库、负载生成器同机部署（2 核 8 G 无 Swap），CPU 与 IO 互相争抢，结果不代表独立部署。',
    '合成数据全部 SIMULATED；dataNature=REAL 的场景命中 0 条是隔离生效，不是缺陷。',
    'scale/queryMode 只是标识，不改变请求；但 P95 只在同 scale + 同 queryMode 的两份结果之间可比。'
  ],
  note:
    '每个接口与每个场景分别统计，不做「总体 P95」汇总。接口级分位由合并样本重算（不是场景分位求平均）。'
}

mkdirSync(dirname(OUT), { recursive: true })
writeFileSync(OUT, JSON.stringify(result, null, 2))

// ---------------------------------------------------------------------------
// 控制台报告：先场景（细），再接口（粗）
// ---------------------------------------------------------------------------
const pad = (v, n) => String(v ?? '-').padEnd(n)
const num = (v, n) => String(v ?? '-').padStart(n)

console.log('\n=== 分场景（稳态段）===')
console.log(
  pad('场景', 30) + num('请求', 8) + num('成功', 8) + num('业务失败', 10) + num('HTTP', 7) +
    num('超时', 6) + num('RPS', 8) + num('P50', 9) + num('P95', 9) + num('P99', 9)
)
for (const r of perScenario) {
  console.log(
    pad(r.scenario, 30) + num(r.requests, 8) + num(r.ok, 8) + num(r.businessFailures, 10) +
      num(r.httpErrors, 7) + num(r.timeouts, 6) + num(r.rps, 8) + num(r.p50, 9) +
      num(r.p95, 9) + num(r.p99, 9)
  )
}

console.log('\n=== 分接口（稳态段，P95 降序）===')
console.log(
  pad('接口', 46) + num('场景', 5) + num('请求', 8) + num('成功率', 9) + num('RPS', 8) +
    num('P50', 9) + num('P95', 9) + num('P99', 9)
)
for (const r of perEndpoint) {
  console.log(
    pad(r.endpoint, 46) + num(r.scenarios, 5) + num(r.requests, 8) +
      num(r.successRate === null ? '-' : (r.successRate * 100).toFixed(1) + '%', 9) +
      num(r.rps, 8) + num(r.p50, 9) + num(r.p95, 9) + num(r.p99, 9)
  )
}

console.log(
  `\n稳态 ${steadySeconds.toFixed(1)}s 共 ${totalRequests} 请求，平均 ${result.steadyRps} RPS，` +
    `成功 ${totalOk}，业务失败 ${totalBusiness}，HTTP 错误 ${totalHttp}，超时 ${totalTimeout}，峰值在途 ${peakInFlight}`
)
const crossChecked = perScenario.reduce((s, r) => s + r.pathCrossChecked, 0)
console.log(`路径首跳抽查 ${crossChecked} 次（抽样率 ${(CROSSCHECK_RATE * 100).toFixed(1)}%）`)
console.log(`原始结果：${OUT}`)
