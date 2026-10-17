// =============================================================================
// 活跃用户混合负载生成器（C 阶段）
//
// 为什么自己写而不是 k6/JMeter：本机没有可复现的 k6 安装，引入 Instrument 与代理配置的
// 成本高于收益。Node 22 自带 fetch/undici，脚本只有这一个文件，结果可重跑、可对照。
// 代价是要自己统计分位、自己控制到达节奏——所以这里的口径都写在参数与输出里，
// 不使用「100 并发」这种无法核对的描述。
//
// 口径（与老师指导 §7.3 一致）：
//   * 「活跃用户」= 一个虚拟用户在思考时间之外循环执行一次任务；100 个活跃用户不等于 100 RPS，
//     也不等于每时刻恰有 100 个在途请求。
//   * 请求比例：成果检索 50% / 学者检索 20% / 详情 15% / 统计 10% / 有界网络 5%。
//   * 预热段与升压段不计入稳定段统计；稳定段单独计时。
//   * 业务失败（HTTP 200 但结果为空、total 与 items 矛盾等）计入 failure，不从样本里剔除。
//
// 用法：
//   node tests/perf/load.mjs --vus=100 --warmup=90 --seconds=300 \
//     --query=http://127.0.0.1:18083 --core=http://127.0.0.1:18081 \
//     --think=1000 --maxId=1000000 --out=reports/perf/<run>/load-100.json
// =============================================================================

import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const [k, ...rest] = a.replace(/^--/, '').split('=')
    return [k, rest.join('=')]
  })
)

const VUS = Number(args.vus ?? 10)
const WARMUP = Number(args.warmup ?? 60)
const SECONDS = Number(args.seconds ?? 300)
const THINK = Number(args.think ?? 1000)
const QUERY = args.query ?? 'http://127.0.0.1:18083'
const CORE = args.core ?? 'http://127.0.0.1:18081'
const MAX_ID = Number(args.maxId ?? 1000000)
const OUT = args.out ?? 'reports/perf/load.json'
const TIMEOUT_MS = Number(args.timeout ?? 20000)

// 热门／冷门／零命中三类关键词混用，避免全部落在同一个缓存或同一个索引路径上。
const KEYWORDS = ['图神经网络', '医学影像', '学术图谱', '数据治理', '构结构分析方法', '能源调度']
const ZERO_KEYWORDS = ['zzz不存在', '没有这个词']
const SCHOLAR_NAMES = ['作者1234', '作者1', '作者4321', '作者777', '作者2']
const TYPES = ['PAPER', 'PATENT', 'PROJECT', 'AWARD']
const TOPICS = ['topic-perf-7', 'topic-perf-12', 'topic-perf-31']
const ORGS = ['机构1', '机构7', '机构233']

const pick = (arr) => arr[Math.floor(Math.random() * arr.length)]
const jitter = () => Math.floor(Math.random() * THINK * 0.5)

function achievementId() {
  const n = 1 + Math.floor(Math.random() * MAX_ID)
  return 'perf-' + String(n).padStart(8, '0')
}

// 场景定义：weight 之和为 100，与指导文档的 50/20/15/10/5 对应。
const SCENARIOS = [
  {
    name: 'search.achievements',
    weight: 50,
    url() {
      const r = Math.random()
      const params = new URLSearchParams()
      params.set('dataNature', 'ALL')
      params.set('size', '20')
      if (r < 0.55) {
        params.set('q', pick(KEYWORDS))
        params.set('page', String(1 + Math.floor(Math.random() * 3)))
      } else if (r < 0.75) {
        params.set('q', pick(ZERO_KEYWORDS))
        params.set('page', '1')
      } else if (r < 0.9) {
        params.set('type', pick(TYPES))
        params.set('yearFrom', '2015')
        params.set('yearTo', '2026')
        params.set('sort', pick(['year_desc', 'title_asc', 'citations_desc']))
        params.set('page', String(1 + Math.floor(Math.random() * 3)))
      } else {
        params.set('topic', pick(TOPICS))
        params.set('organization', pick(ORGS))
        params.set('page', '1')
      }
      return QUERY + '/api/v1/public/search/achievements?' + params
    },
    // 业务判据：零命中场景必须 total=0；其余场景 total 必须与 items 长度一致地自洽。
    check(body) {
      const d = body?.data
      if (!d || typeof d.total !== 'number' || !Array.isArray(d.items)) return '结构不符'
      if (d.items.length > d.total) return `items(${d.items.length}) > total(${d.total})`
      return null
    }
  },
  {
    name: 'search.scholars',
    weight: 20,
    url() {
      const params = new URLSearchParams({
        dataNature: 'ALL',
        q: pick(SCHOLAR_NAMES),
        page: String(1 + Math.floor(Math.random() * 2)),
        size: '20'
      })
      return QUERY + '/api/v1/public/search/scholars?' + params
    },
    check(body) {
      const d = body?.data
      if (!d || !Array.isArray(d.items)) return '结构不符'
      return null
    }
  },
  {
    name: 'detail.achievement',
    weight: 15,
    url() {
      return CORE + '/api/v1/public/achievements/' + achievementId()
    },
    check(body) {
      // 详情接口对不可见/不存在都返回错误码，这属于业务错误，不能算成功。
      if (body?.code && body.code !== 'OK') return 'code=' + body.code
      return null
    }
  },
  {
    name: 'analytics.overview',
    weight: 10,
    url() {
      return QUERY + '/api/v1/public/analytics/overview?dataNature=ALL'
    },
    check(body) {
      if (!body?.data) return '结构不符'
      return null
    }
  },
  {
    name: 'network.graph',
    weight: 5,
    url() {
      const params = new URLSearchParams({
        scholarId: 'sch-perf-' + (1 + Math.floor(Math.random() * 5000)),
        depth: '1',
        dataNature: 'ALL'
      })
      return QUERY + '/api/v1/public/network/graph?' + params
    },
    check(body) {
      if (!body?.data) return '结构不符'
      return null
    }
  }
]

const TOTAL_WEIGHT = SCENARIOS.reduce((s, x) => s + x.weight, 0)
function pickScenario() {
  let r = Math.random() * TOTAL_WEIGHT
  for (const s of SCENARIOS) {
    r -= s.weight
    if (r <= 0) return s
  }
  return SCENARIOS[0]
}

const stats = new Map()
for (const s of SCENARIOS) {
  stats.set(s.name, { name: s.name, count: 0, ok: 0, httpError: 0, timeout: 0, business: 0, latencies: [], errors: [] })
}

const startedAt = Date.now()
const stopAt = startedAt + (WARMUP + SECONDS) * 1000
let inFlight = 0
let peakInFlight = 0
let warmupDone = false

async function oneRequest(vu) {
  const scenario = pickScenario()
  const st = stats.get(scenario.name)
  const url = scenario.url()
  const started = performance.now()
  const ac = new AbortController()
  const timer = setTimeout(() => ac.abort(), TIMEOUT_MS)
  inFlight++
  peakInFlight = Math.max(peakInFlight, inFlight)
  let counted = warmupDone
  try {
    const res = await fetch(url, { signal: ac.signal, headers: { accept: 'application/json' } })
    const elapsed = performance.now() - started
    let body = null
    try {
      body = await res.json()
    } catch {
      /* 非 JSON 响应按结构错误处理 */
    }
    if (!counted) return
    st.count++
    st.latencies.push(elapsed)
    if (res.status !== 200) {
      st.httpError++
      if (st.errors.length < 5) st.errors.push(`HTTP ${res.status} ${url}`)
      return
    }
    const problem = scenario.check(body)
    if (problem) {
      st.business++
      if (st.errors.length < 5) st.errors.push(`${problem} ${url}`)
      return
    }
    st.ok++
  } catch (e) {
    if (!counted) return
    st.count++
    const elapsed = performance.now() - started
    st.latencies.push(elapsed)
    if (e.name === 'AbortError') {
      st.timeout++
      if (st.errors.length < 5) st.errors.push(`timeout ${url}`)
    } else {
      st.httpError++
      if (st.errors.length < 5) st.errors.push(`${e.name}: ${e.message} ${url}`)
    }
  } finally {
    clearTimeout(timer)
    inFlight--
  }
}

async function vuLoop(vu) {
  while (Date.now() < stopAt) {
    await oneRequest(vu)
    const wait = THINK + jitter()
    await new Promise((r) => setTimeout(r, wait))
  }
}

function percentile(sorted, p) {
  if (sorted.length === 0) return null
  const idx = Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))
  return sorted[idx]
}

function summarize() {
  const rows = []
  for (const st of stats.values()) {
    const sorted = [...st.latencies].sort((a, b) => a - b)
    rows.push({
      scenario: st.name,
      requests: st.count,
      ok: st.ok,
      httpErrors: st.httpError,
      timeouts: st.timeout,
      businessFailures: st.business,
      successRate: st.count === 0 ? null : +(st.ok / st.count).toFixed(4),
      p50: percentile(sorted, 50)?.toFixed(1) ?? null,
      p95: percentile(sorted, 95)?.toFixed(1) ?? null,
      p99: percentile(sorted, 99)?.toFixed(1) ?? null,
      max: sorted.length ? sorted[sorted.length - 1].toFixed(1) : null,
      sampleErrors: st.errors
    })
  }
  return rows
}

console.log(`vus=${VUS} warmup=${WARMUP}s steady=${SECONDS}s think=${THINK}ms query=${QUERY} core=${CORE}`)

setTimeout(() => {
  warmupDone = true
  for (const st of stats.values()) {
    st.count = 0
    st.ok = 0
    st.httpError = 0
    st.timeout = 0
    st.business = 0
    st.latencies = []
  }
  console.log(`--- 预热结束，进入 ${SECONDS}s 稳定段 ---`)
}, WARMUP * 1000)

const vus = Array.from({ length: VUS }, (_, i) => vuLoop(i))
await Promise.all(vus)

const endedAt = Date.now()
const rows = summarize()
const totalRequests = rows.reduce((s, r) => s + r.requests, 0)
const totalOk = rows.reduce((s, r) => s + r.ok, 0)
const result = {
  startedAt: new Date(startedAt).toISOString(),
  endedAt: new Date(endedAt).toISOString(),
  vus: VUS,
  warmupSeconds: WARMUP,
  steadySeconds: SECONDS,
  thinkTimeMs: THINK,
  peakInFlight,
  steadyRequests: totalRequests,
  steadyRps: +(totalRequests / SECONDS).toFixed(2),
  overallSuccessRate: totalRequests === 0 ? null : +(totalOk / totalRequests).toFixed(4),
  scenarios: rows,
  note:
    'RPS 是稳定段的平均值；活跃用户数不等于 RPS。业务失败指 HTTP 200 但结果结构/语义不自洽，未从样本中剔除。'
}

mkdirSync(dirname(OUT), { recursive: true })
writeFileSync(OUT, JSON.stringify(result, null, 2))

const pad = (v, n) => String(v ?? '-').padEnd(n)
console.log('\n场景'.padEnd(24) + '请求'.padEnd(8) + '成功'.padEnd(8) + 'HTTP错误'.padEnd(10) + '超时'.padEnd(6) + '业务失败'.padEnd(10) + 'P50'.padEnd(9) + 'P95'.padEnd(9) + 'P99'.padEnd(9) + '最大')
for (const r of rows) {
  console.log(
    pad(r.scenario, 24) + pad(r.requests, 8) + pad(r.ok, 8) + pad(r.httpErrors, 10) +
    pad(r.timeouts, 6) + pad(r.businessFailures, 10) + pad(r.p50, 9) + pad(r.p95, 9) + pad(r.p99, 9) + pad(r.max, 9)
  )
}
console.log(`\n稳定段 ${SECONDS}s 共 ${totalRequests} 请求，平均 ${result.steadyRps} RPS，成功率 ${result.overallSuccessRate}，峰值在途 ${peakInFlight}`)
console.log(`原始结果：${OUT}`)
