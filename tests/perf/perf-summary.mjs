// =============================================================================
// 阶段六压测结果汇总与验收判定
//
// 输入：ops/perf/run-scale-benchmark.sh 的一个阶段输出目录（reports/perf/<stamp>/<stage>/）
// 输出：
//   <dir>/perf-summary.md   给报告直接引用的汇总（容量、实体数、各并发下分接口分位、计划、资源峰值）
//   stdout                  同样内容的控制台版 + 一行 ACCEPTANCE: 结论
//
// 为什么单独一个脚本，而不是让每个压测 job 各写一份摘要：
//   1. 「是否达标」必须是同一套判定逻辑的产物。每个 job 自己判 P95，早晚会出现一个
//      job 用 2s、另一个用 3s，然后报告里只引用好看的那个。
//   2. 汇总要能处理**不完整的运行**（只跑了 load、没跑图投影；某个并发档崩了）。
//      缺数据时必须写「缺数据」，而不是把缺失的那一档从表里删掉——删掉就等于无声地
//      声称「我们测过所有并发」。
//
// ## 验收口径（来自课程任务书与 AGENTS.md §3.1，脚本内不得修改）
//   * 关键搜索与路径查询 P95 ≤ 2000 ms
//   * 错误率（HTTP 错误 + 超时 + 业务失败）≤ 1%
//   * 两个条件必须在**每一个**测过的并发档上都成立，才算该接口达标。
//   * 500 万容量与 100 并发的「固定环境」要求另见
//     docs/ops/phase6-performance-capacity-plan.md §1，本脚本只判定它看到的数字。
//
// 用法：
//   node tests/perf/perf-summary.mjs --dir=reports/perf/20261125-120000/10k
//   node tests/perf/perf-summary.mjs --dir=... --enforce      # 未达标时退出码 5
//   node tests/perf/perf-summary.mjs --dir=... --json=summary.json
// =============================================================================

import { readFileSync, writeFileSync, existsSync, readdirSync, mkdirSync } from 'node:fs'
import { join, dirname } from 'node:path'

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const [k, ...rest] = a.replace(/^--/, '').split('=')
    return [k, rest.length === 0 ? 'true' : rest.join('=')]
  })
)

const DIR = args.dir
if (!DIR || !existsSync(DIR)) {
  console.error('必须给 --dir=<阶段输出目录>（ops/perf/run-scale-benchmark.sh 打印的目录）')
  process.exit(2)
}
const ENFORCE = args.enforce === 'true'

// ---------------------------------------------------------------------------
// 阈值：只在这里定义一次。改它就是改验收口径，必须走评审。
// ---------------------------------------------------------------------------
const THRESHOLDS = {
  p95Ms: 2000,
  errorRatePct: 1,
  note: '课程任务书阶段六：关键搜索与路径查询 P95 ≤ 2s、错误率 ≤ 1%；不得在本脚本里放宽'
}

// 「关键」= 搜索与图谱/路径。统计接口（analytics）也会导致用户可感知的慢，
// 但任务书的 2s 门槛写的是搜索与路径，因此 analytics 只报告不参与判定，
// 这一点必须在报告里明说，不能拿「全部接口都过了」冒充「关键接口都过了」。
const KEY_PREFIXES = ['/api/v1/public/search/', '/api/v1/public/network/']
const isKeyEndpoint = (endpoint) => KEY_PREFIXES.some((p) => endpoint.startsWith(p))

// ---------------------------------------------------------------------------
// 读取
// ---------------------------------------------------------------------------
function readJson(path) {
  try {
    return JSON.parse(readFileSync(path, 'utf8'))
  } catch {
    return null
  }
}

function readText(path) {
  try {
    return readFileSync(path, 'utf8')
  } catch {
    return null
  }
}

const capacity = readJson(join(DIR, 'capacity-model.json'))
const countsText = readText(join(DIR, 'counts.txt'))
const plansTsv = readText(join(DIR, 'plans/sql-plans.tsv'))
const preflightEnv = readText(join(DIR, 'preflight.env'))
const timingsEnv = readText(join(DIR, 'timings.env'))

const loadFiles = readdirSync(DIR)
  .filter((f) => /^load-\d+\.json$/.test(f))
  .sort((a, b) => Number(a.match(/\d+/)[0]) - Number(b.match(/\d+/)[0]))

const loads = loadFiles
  .map((f) => readJson(join(DIR, f)))
  .filter(Boolean)
  .map((r) => ({ ...r, file: `load-${r.vus}.json` }))

const resourceFiles = readdirSync(DIR).filter((f) => /^resources-.*\.tsv$/.test(f))

/** 从 /proc 采样 TSV 里取峰值：这里只做「最大值」与「最低可用内存」，不做平滑。 */
function summarizeResources(file) {
  const text = readText(join(DIR, file))
  if (!text) return null
  const lines = text.trim().split('\n').slice(1).filter((l) => l.trim())
  if (lines.length === 0) return null
  const acc = { samples: lines.length, minMemAvailMb: Infinity, maxPgRssMb: 0, maxNeoRssMb: 0, maxJavaRssMb: 0, maxLoad1: 0, endDiskAvailGb: null }
  for (const line of lines) {
    const [, memAvail, , load1, disk, pgRss, neoRss, javaRss] = line.split('\t')
    const n = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0)
    acc.minMemAvailMb = Math.min(acc.minMemAvailMb, n(memAvail))
    acc.maxLoad1 = Math.max(acc.maxLoad1, n(load1))
    acc.maxPgRssMb = Math.max(acc.maxPgRssMb, n(pgRss))
    acc.maxNeoRssMb = Math.max(acc.maxNeoRssMb, n(neoRss))
    acc.maxJavaRssMb = Math.max(acc.maxJavaRssMb, n(javaRss))
    acc.endDiskAvailGb = n(disk)
  }
  return acc
}

/**
 * 一致性计数：counts.txt 由 counts-scale.sql 输出，格式是「标签 | 值」或「标签: 值」。
 * 这里只取报告需要的那几行；取不到就返回空表，由报告写「未采到」，不猜。
 */
function parseCounts(text) {
  if (!text) return {}
  const wanted = [
    '成果-投影行数',
    '成果-公开且生效',
    '成果-数据性质',
    '学者-去重',
    '作者关系行',
    '作者关系-合格',
    '合作边行',
    '合作边-不同作者对',
    '领域归属行',
    '发生机构行'
  ]
  const out = {}
  for (const line of text.split('\n')) {
    const trimmed = line.trim()
    for (const w of wanted) {
      if (trimmed.startsWith(w) && !(w in out)) out[w] = trimmed.slice(w.length).replace(/^[\s|:]+/, '').trim()
    }
  }
  return out
}

// ---------------------------------------------------------------------------
// 判定
// ---------------------------------------------------------------------------
/**
 * 验收判定：对每个「关键」接口在**每一个**并发档上检查 P95 与错误率。
 * 缺任何一个并发档的数据都记为 incomplete，不算通过。
 */
function evaluate(loads) {
  const expectedVus = (args['expect-vus'] ?? '')
    .split(',')
    .map((v) => v.trim())
    .filter(Boolean)
    .map(Number)
  const seenVus = loads.map((l) => l.vus)
  const missingVus = expectedVus.filter((v) => !seenVus.includes(v))

  const perEndpoint = new Map()
  for (const load of loads) {
    for (const e of load.endpoints ?? []) {
      if (!perEndpoint.has(e.endpoint)) {
        perEndpoint.set(e.endpoint, { endpoint: e.endpoint, key: isKeyEndpoint(e.endpoint), byVus: {} })
      }
      perEndpoint.get(e.endpoint).byVus[load.vus] = {
        p50: e.p50,
        p95: e.p95,
        p99: e.p99,
        rps: e.rps,
        requests: e.requests,
        successRate: e.successRate,
        errorRatePct:
          e.requests === 0 || e.successRate === null ? null : +((1 - e.successRate) * 100).toFixed(2)
      }
    }
  }

  const results = [...perEndpoint.values()].map((row) => {
    const violations = []
    for (const [vus, m] of Object.entries(row.byVus)) {
      if (m.p95 !== null && m.p95 > THRESHOLDS.p95Ms) {
        violations.push(`vus=${vus} P95=${m.p95}ms > ${THRESHOLDS.p95Ms}ms`)
      }
      if (m.errorRatePct !== null && m.errorRatePct > THRESHOLDS.errorRatePct) {
        violations.push(`vus=${vus} 错误率=${m.errorRatePct}% > ${THRESHOLDS.errorRatePct}%`)
      }
    }
    const measured = Object.keys(row.byVus).length > 0
    return {
      ...row,
      measured,
      violations,
      status: !measured ? '未测' : row.key ? (violations.length === 0 ? '达标' : '未达标') : '仅报告（非关键接口）'
    }
  })

  const keyRows = results.filter((r) => r.key && r.measured)
  const keyFailures = keyRows.filter((r) => r.status === '未达标')
  const incomplete = missingVus.length > 0

  return {
    thresholds: THRESHOLDS,
    expectedVus,
    measuredVus: seenVus,
    missingVus,
    endpoints: results,
    keyEndpointCount: keyRows.length,
    keyFailures: keyFailures.map((r) => ({ endpoint: r.endpoint, violations: r.violations })),
    // 通过 = 没有任何关键接口超阈 + 没有缺失的并发档 + 至少测过一个关键接口。
    // 「一个关键接口都没测到」不能算通过：那是没测，不是达标。
    passed: keyFailures.length === 0 && !incomplete && keyRows.length > 0,
    reason:
      keyRows.length === 0
        ? '没有任何关键接口的压测数据（load-*.json 缺失或不含 search/network 接口）'
        : incomplete
          ? `缺少并发档：${missingVus.join(', ')}`
          : keyFailures.length > 0
            ? `${keyFailures.length} 个关键接口未达标`
            : '关键接口在每个测过的并发档上均满足 P95/错误率门槛'
  }
}

const verdict = evaluate(loads)
const counts = parseCounts(countsText)
const resources = Object.fromEntries(resourceFiles.map((f) => [f, summarizeResources(f)]))

const plans = (plansTsv ?? '')
  .split('\n')
  .slice(1)
  .filter((l) => l.trim())
  .map((l) => {
    const [stmt, planning, execution] = l.split('\t')
    return { stmt, planning: Number(planning), execution: Number(execution) }
  })
  .filter((p) => Number.isFinite(p.execution))
  .sort((a, b) => b.execution - a.execution)
  .slice(0, 10)

// ---------------------------------------------------------------------------
// 输出
// ---------------------------------------------------------------------------
const fmt = (v, d = 1) => (v === null || v === undefined || Number.isNaN(v) ? '—' : Number(v).toFixed(d))

function toMarkdown() {
  const out = []
  const stage = capacity?.gate?.stage ?? capacity?.stages?.[0]?.stage ?? '未知'
  out.push(`# 阶段六压测汇总（${stage}）`)
  out.push('')
  out.push(`- 目录：\`${DIR}\``)
  out.push(`- 生成时间：${new Date().toISOString()}`)
  out.push(`- 规模：${loads[0]?.scale ?? capacity?.gate?.requiredScale ?? '未知'}；图查询模式：${loads[0]?.queryMode ?? '未知'}`)
  out.push(`- 数据性质：${loads[0]?.dataNature ?? 'SIMULATED'}（合成数据，非真实收录）`)
  out.push('')
  out.push('## 1. 前置预检')
  out.push('')
  out.push('```')
  out.push(preflightEnv?.trim() || '（无 preflight.env：本次运行未执行预检）')
  out.push('```')
  out.push('')
  out.push('## 2. 实体数与体积（实测计数，来自 counts-scale.sql）')
  out.push('')
  if (Object.keys(counts).length === 0) {
    out.push('未采到 counts.txt：本次运行没有执行计数步骤，实体数不可引用。')
  } else {
    out.push('| 指标 | 值 |')
    out.push('| --- | --- |')
    for (const [k, v] of Object.entries(counts)) out.push(`| ${k} | ${v} |`)
  }
  out.push('')
  out.push('## 3. 各并发档分接口分位（稳态段）')
  out.push('')
  if (loads.length === 0) {
    out.push('未找到 load-*.json：本次运行没有执行并发压测。')
  } else {
    const vusList = loads.map((l) => l.vus)
    out.push(`| 接口 | 关键 | ${vusList.map((v) => `P95@${v}`).join(' | ')} | 最差 P95 | 最大错误率 | 判定 |`)
    out.push(`| --- | :---: | ${vusList.map(() => '---:').join(' | ')} | ---: | ---: | --- |`)
    for (const row of verdict.endpoints) {
      const p95s = vusList.map((v) => row.byVus[v]?.p95 ?? null)
      const errs = vusList.map((v) => row.byVus[v]?.errorRatePct ?? null)
      const worstP95 = p95s.filter((v) => v !== null).length ? Math.max(...p95s.filter((v) => v !== null)) : null
      const worstErr = errs.filter((v) => v !== null).length ? Math.max(...errs.filter((v) => v !== null)) : null
      out.push(
        `| \`${row.endpoint}\` | ${row.key ? '是' : '否'} | ${p95s.map((v) => fmt(v, 0)).join(' | ')} | ${fmt(worstP95, 0)} | ${worstErr === null ? '—' : worstErr + '%'} | ${row.status} |`
      )
    }
    out.push('')
    out.push('| 并发 | 稳态 RPS | 请求数 | 成功率 | 峰值在途 |')
    out.push('| ---: | ---: | ---: | ---: | ---: |')
    for (const l of loads) {
      out.push(
        `| ${l.vus} | ${fmt(l.steadyRps, 2)} | ${l.steadyRequests} | ${l.overallSuccessRate === null ? '—' : (l.overallSuccessRate * 100).toFixed(2) + '%'} | ${l.peakInFlight} |`
      )
    }
  }
  out.push('')
  out.push('## 4. 执行计划 Top 10（EXPLAIN ANALYZE，按 Execution Time 降序）')
  out.push('')
  if (plans.length === 0) {
    out.push('未采到 plans/sql-plans.tsv：本次运行没有执行计划采集。')
  } else {
    out.push('| 语句 | Planning (ms) | Execution (ms) |')
    out.push('| --- | ---: | ---: |')
    for (const p of plans) out.push(`| ${p.stmt} | ${fmt(p.planning, 3)} | ${fmt(p.execution, 3)} |`)
  }
  out.push('')
  out.push('## 5. 资源峰值（每秒采样）')
  out.push('')
  if (Object.keys(resources).length === 0) {
    out.push('未采到 resources-*.tsv：本次运行没有启动采样器。')
  } else {
    out.push('| 采样文件 | 次数 | 最低 MemAvailable (MB) | PG RSS 峰值 (MB) | Neo4j RSS 峰值 (MB) | JVM RSS 峰值 (MB) | load1 峰值 | 结束磁盘可用 (GB) |')
    out.push('| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
    for (const [f, r] of Object.entries(resources)) {
      if (!r) continue
      out.push(
        `| ${f} | ${r.samples} | ${Number.isFinite(r.minMemAvailMb) ? r.minMemAvailMb : '—'} | ${r.maxPgRssMb} | ${r.maxNeoRssMb} | ${r.maxJavaRssMb} | ${fmt(r.maxLoad1, 2)} | ${r.endDiskAvailGb ?? '—'} |`
      )
    }
  }
  out.push('')
  out.push('## 6. 耗时')
  out.push('')
  out.push('```')
  out.push(timingsEnv?.trim() || '（无 timings.env）')
  out.push('```')
  out.push('')
  out.push('## 7. 验收判定')
  out.push('')
  out.push(`- 阈值：P95 ≤ ${THRESHOLDS.p95Ms} ms，错误率 ≤ ${THRESHOLDS.errorRatePct}%（${THRESHOLDS.note}）`)
  out.push(`- 期望并发档：${verdict.expectedVus.length ? verdict.expectedVus.join(', ') : '未指定（--expect-vus）'}`)
  out.push(`- 实测并发档：${verdict.measuredVus.length ? verdict.measuredVus.join(', ') : '无'}`)
  out.push(`- 缺失并发档：${verdict.missingVus.length ? verdict.missingVus.join(', ') : '无'}`)
  out.push(`- 关键接口数（已测）：${verdict.keyEndpointCount}`)
  out.push(`- 结论：**${verdict.passed ? '通过' : '未通过'}** —— ${verdict.reason}`)
  if (verdict.keyFailures.length) {
    out.push('')
    out.push('未达标明细：')
    out.push('')
    for (const f of verdict.keyFailures) {
      out.push(`- \`${f.endpoint}\`：${f.violations.join('；')}`)
    }
  }
  out.push('')
  out.push('## 8. 口径与不可引用项')
  out.push('')
  out.push('- 数据全部为 `SIMULATED` 合成数据。它证明的是容量与性能，**不是**真实收录量。')
  out.push('- 只有「关键接口在每个测过的并发档上都达标 + 并发档齐全」才判通过；缺档写「未通过」。')
  out.push('- `analytics.*` 不在任务书 2s 门槛的范围内，只报告不判定，报告不得用它替代关键接口结论。')
  out.push('- 服务、数据库与负载生成器同机部署时，CPU/IO 互相争抢，P95 只能作为同机对照，不代表独立部署。')
  out.push('')
  return out.join('\n') + '\n'
}

const md = toMarkdown()
const mdPath = join(DIR, 'perf-summary.md')
mkdirSync(dirname(mdPath), { recursive: true })
writeFileSync(mdPath, md)

const json = {
  generatedAt: new Date().toISOString(),
  dir: DIR,
  scale: loads[0]?.scale ?? capacity?.gate?.requiredScale ?? null,
  queryMode: loads[0]?.queryMode ?? null,
  dataNature: loads[0]?.dataNature ?? null,
  capacityGate: capacity?.gate ?? null,
  counts,
  loads: loads.map((l) => ({ vus: l.vus, steadyRps: l.steadyRps, steadyRequests: l.steadyRequests, overallSuccessRate: l.overallSuccessRate, peakInFlight: l.peakInFlight, measuredSteadySeconds: l.measuredSteadySeconds, environment: l.environment ?? null })),
  plansTop10: plans,
  resources,
  acceptance: verdict
}
if (args.json) {
  mkdirSync(dirname(args.json), { recursive: true })
  writeFileSync(args.json, JSON.stringify(json, null, 2))
  console.log(`JSON 已写入 ${args.json}`)
}

console.log(md)
console.log(`ACCEPTANCE: ${verdict.passed ? 'PASS' : 'FAIL'} — ${verdict.reason}`)
console.log(`汇总已写入 ${mdPath}`)

if (ENFORCE && !verdict.passed) process.exitCode = 5
