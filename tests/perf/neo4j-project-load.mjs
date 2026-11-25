// =============================================================================
// Neo4j 批量图投影装载器（容量压测专用，不是生产投影链路）
//
// 为什么不用生产链路：生产是「事件驱动 + 单成果事务」，每个成果 4~6 条语句。
// 500 万成果意味着千万级往返，容量窗口中跑不完，而且测不到「图库装不装得下」。
// 本脚本用同一套 label / 属性 / 关系 / 版本守卫做批量 UNWIND 装载，只换「怎么写」：
//   * 写入语义与 Neo4jGraphProjectionStore 的 UPSERT_WORK / UPSERT_SCHOLARS /
//     CREATE_AUTHORED / CREATE_COOPERATED / ADVANCE_META 逐条对应（含 source_version 守卫）；
//   * 唯一差异是批大小（生产 500，这里可配）与「一条语句写一批」。
// 单成果事务的真实吞吐由 1 万阶段的回填实测给出，两者分别报告，绝不混用。
//
// 与生产链路的另两处刻意差别，必须写进报告：
//   1. 生产在任何写入前会先确认「整库只有一个投影作用域」。本脚本在 --clean 时也检查，
//      但不检查时会往现有库里叠加，因此**只能对压测库使用**。
//   2. 生产按成果整体替换（先删旧关系再插新关系）。批量装载是「空库一次性写入」，
//      不重放，因此没有删除环节；要重来必须 --clean。
//
// 用法：
//   node tests/perf/neo4j-project-load.mjs \
//     --uri=http://127.0.0.1:17474 --user=neo4j --password=*** --database=neo4j \
//     --dir=/home/admin/cn-test-runtime/graph-5m-1m --gen=gen0001 --batch=5000 --clean
// =============================================================================

import { readFileSync, createReadStream, existsSync, statSync } from 'node:fs'
import { createInterface } from 'node:readline'
import { execSync } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const [k, ...rest] = a.replace(/^--/, '').split('=')
    // 无 '=' 的开关（--clean / --dry-run）解析为 'true'，否则 '' 会让 `=== 'true'` 判断失效
    return [k, rest.length === 0 ? 'true' : rest.join('=')]
  })
)

const URI = (args.uri ?? 'http://127.0.0.1:17474').replace(/\/$/, '')
const USER = args.user ?? process.env.CN_TEST_NEO4J_USER ?? 'neo4j'
const PASSWORD = args.password ?? process.env.CN_TEST_NEO4J_PASSWORD ?? ''
const DATABASE = args.database ?? 'neo4j'
const DIR = args.dir ?? 'reports/perf/graph-load'
const GEN = args.gen ?? 'gen0001'
const BATCH = Number(args.batch ?? 5000)
const SCOPE = args.scope ?? process.env.CN_NEO4J_SCOPE ?? 'query-public'
const MATERIALIZED_THRESHOLD = Number(args['materialized-threshold'] ?? 20)
const CLEAN = args.clean === 'true' || args.clean === '1'
const GRAPH_DIR = args['graph-dir'] ?? ''
const ONLY = args.only ?? ''
// --dry-run：只校验 schema 能否解析、四个 TSV 的列数与类型映射是否成立，不连 Neo4j。
// 用途是「改了导出 SQL 的列顺序但忘了改这里的映射」这类问题——它不会报错，
// 只会安静地把 title 写进 pair_key。必须在装载前就能查出来。
const DRY_RUN = args['dry-run'] === 'true' || args['dry-run'] === '1'

const HERE = dirname(fileURLToPath(import.meta.url))
const SCHEMA_PATH = resolve(HERE, '../../services/query-service/src/main/resources/neo4j/schema.cypher')

if (!PASSWORD && !DRY_RUN) {
  console.error('缺少 Neo4j 口令：请设置 CN_TEST_NEO4J_PASSWORD 或传 --password=...')
  process.exit(2)
}

const AUTH = 'Basic ' + Buffer.from(`${USER}:${PASSWORD}`).toString('base64')

// ---------------------------------------------------------------------------
// 与生产实现逐条对应的写入语句
// ---------------------------------------------------------------------------
const UPSERT_WORKS = `
UNWIND $rows AS row
MERGE (w:Work {work_id: row.workId})
ON CREATE SET w.source_version = -1
WITH w, row, coalesce(w.source_version, -1) AS current
WHERE current <= row.sourceVersion
SET w.title = row.title,
    w.is_public = row.isPublic,
    w.data_origin = row.dataOrigin,
    w.topic_ids = row.topicIds,
    w.organizations = row.organizations,
    w.author_count = row.authorCount,
    w.edges_materialized = row.edgesMaterialized,
    w.source_version = row.sourceVersion,
    w.updated_at_epoch_ms = timestamp()
RETURN count(w) AS applied`

const UPSERT_SCHOLARS = `
UNWIND $rows AS row
MERGE (s:Scholar {scholar_id: row.scholarId})
WITH s, row, coalesce(s.source_version, -1) AS current
WHERE current <= row.sourceVersion
SET s.scholar_name = row.scholarName,
    s.domain = row.domain,
    s.organization = row.organization,
    s.data_origin = row.dataOrigin,
    s.source_version = row.sourceVersion
RETURN count(s) AS applied`

const CREATE_AUTHORED = `
UNWIND $rows AS row
MATCH (w:Work {work_id: row.workId})
MATCH (s:Scholar {scholar_id: row.scholarId})
MERGE (s)-[r:AUTHORED {work_id: row.workId}]->(w)
SET r.relation_state = row.relationState,
    r.author_order = row.authorOrder,
    r.work_title = row.workTitle`

const CREATE_COOPERATED = `
UNWIND $rows AS row
MATCH (a:Scholar {scholar_id: row.scholarA})
MATCH (b:Scholar {scholar_id: row.scholarB})
CREATE (a)-[c:COOPERATED_WITH]->(b)
SET c.work_id = row.workId,
    c.work_title = row.workTitle,
    c.pair_key = row.pairKey,
    c.data_origin = row.dataOrigin,
    c.source_version = row.sourceVersion,
    c.rule_version = 'NETWORK_V1',
    c.is_valid = true`

const ADVANCE_META = `
MERGE (m:GraphProjectionMeta {scope: $scope})
ON CREATE SET m.projected_version = -1
WITH m, m.projected_version AS current
FOREACH (_ IN CASE WHEN $projectedVersion > current THEN [1] ELSE [] END |
  SET m.projected_version = $projectedVersion,
      m.catalog_revision = $catalogRevision,
      m.generation = $generation,
      m.updated_at_epoch_ms = $updatedAtEpochMs)
RETURN m.projected_version AS projectedVersion`

// ---------------------------------------------------------------------------
// HTTP 事务
// ---------------------------------------------------------------------------
async function tx(statements) {
  const res = await fetch(`${URI}/db/${DATABASE}/tx/commit`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: AUTH, accept: 'application/json' },
    body: JSON.stringify({ statements })
  })
  if (!res.ok) {
    throw new Error(`Neo4j HTTP ${res.status}：${(await res.text()).slice(0, 400)}`)
  }
  const body = await res.json()
  if (Array.isArray(body.errors) && body.errors.length > 0) {
    const first = body.errors[0]
    throw new Error(`Neo4j ${first.code}：${first.message}`)
  }
  return body
}

async function runOne(statement, parameters) {
  return tx([{ statement, parameters }])
}

// ---------------------------------------------------------------------------
// schema：直接读服务端资源文件，避免「脚本里的 DDL 与服务里的 DDL」两套口径
// ---------------------------------------------------------------------------
function loadSchemaStatements() {
  if (!existsSync(SCHEMA_PATH)) {
    throw new Error(`找不到 schema 文件：${SCHEMA_PATH}`)
  }
  const raw = readFileSync(SCHEMA_PATH, 'utf8')
  const withoutComments = raw
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l.length > 0 && !l.startsWith('//'))
    .join('\n')
  return withoutComments
    .split(';')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}

async function ensureSchema() {
  const statements = loadSchemaStatements()
  // 一条 HTTP 事务里提交全部 DDL：Neo4j 允许 schema 与数据混合提交，但 DDL 之间要串行，
  // 因此这里一次提交全部语句，由服务端按顺序执行。
  await tx(statements.map((statement) => ({ statement })))
  console.log(`schema 就绪：${statements.length} 条语句（来自 ${SCHEMA_PATH}）`)
}

/**
 * 离线校验：schema 可解析 + 每个 TSV 的列数正确 + 关键字段非空。
 * 只读前 --dry-run-rows 行（默认 200），不连数据库。
 */
async function dryRun() {
  console.log('=== dry-run：只校验 schema 与 TSV 契约，不连接 Neo4j ===')
  let failures = 0

  const statements = loadSchemaStatements()
  console.log(`schema：${statements.length} 条语句（${SCHEMA_PATH}）`)
  for (const st of statements) {
    if (!/^CREATE (CONSTRAINT|INDEX)/.test(st)) {
      console.log(`  [warn] 非 CREATE 语句（启动时会重复执行，必须幂等）：${st.slice(0, 60)}`)
    }
    if (!/IF NOT EXISTS/.test(st)) {
      console.log(`  [FAIL] 语句缺少 IF NOT EXISTS（重复启动会失败）：${st.slice(0, 60)}`)
      failures++
    }
  }

  const expectCols = { works: 9, scholars: 4, authored: 5, cooperated: 7 }
  const required = {
    works: ['workId', 'title', 'isPublic', 'dataOrigin', 'sourceVersion'],
    scholars: ['scholarId', 'dataOrigin', 'sourceVersion'],
    authored: ['scholarId', 'workId', 'relationState', 'authorOrder'],
    cooperated: ['scholarA', 'scholarB', 'workId', 'pairKey', 'dataOrigin']
  }

  for (const [name, cols] of Object.entries(expectCols)) {
    const path = resolve(DIR, `${name}.tsv`)
    if (!existsSync(path)) {
      console.log(`  [skip] ${path} 不存在`)
      continue
    }
    let rows = 0
    let badCols = 0
    let sample = null
    for await (const row of readTsv(path, MAPPERS[name])) {
      rows++
      if (sample === null) sample = row
      if (rows >= Number(args['dry-run-rows'] ?? 200)) break
    }
    console.log(`  ${name}：前 ${rows} 行解析成功，样本列数 ${cols}`)
    // 列数不匹配时 mapper 会给出 undefined 字段，而不是报错，因此在这里显式检查
    if (sample) {
      for (const field of required[name]) {
        const v = sample[field]
        if (v === undefined || v === null || v === '') {
          console.log(`  [FAIL] ${name}: 字段 ${field} 解析为空（样本 ${JSON.stringify(sample).slice(0, 160)}）`)
          failures++
        }
      }
    }
    if (badCols > 0) {
      console.log(`  [FAIL] ${name}: ${badCols} 行列数不符`)
      failures++
    }
  }

  const lines = readFileSync(resolve(HERE, '..', '..', 'tests/perf/neo4j-project-load.mjs'), 'utf8')
  for (const constant of ['UPSERT_WORKS', 'UPSERT_SCHOLARS', 'CREATE_AUTHORED', 'CREATE_COOPERATED', 'ADVANCE_META']) {
    if (!lines.includes(`const ${constant}`)) {
      console.log(`  [FAIL] 缺少写入语句 ${constant}`)
      failures++
    }
  }

  if (failures > 0) {
    console.error(`\ndry-run 失败：${failures} 项`)
    process.exit(3)
  }
  console.log('\ndry-run 通过')
  process.exit(0)
}

// ---------------------------------------------------------------------------
// TSV 流式读取：500 万行不能整体读进内存
// ---------------------------------------------------------------------------
async function* readTsv(path, mapper) {
  const rl = createInterface({ input: createReadStream(path, 'utf8'), crlfDelay: Infinity })
  let lineNo = 0
  let bad = 0
  for await (const line of rl) {
    lineNo++
    if (line === '') continue
    const cols = line.split('\t')
    const row = mapper(cols)
    if (row === null) {
      bad++
      if (bad <= 3) console.warn(`  跳过无法解析的行 ${lineNo}：${line.slice(0, 120)}`)
      continue
    }
    yield row
  }
}

const nullOf = (v) => (v === undefined || v === '\\N' ? null : v)
const boolOf = (v) => v === 't' || v === 'true'
const intOf = (v, d = 0) => {
  const n = Number(nullOf(v))
  return Number.isFinite(n) ? n : d
}
const listOf = (v) => {
  const s = nullOf(v)
  return s === null || s === '' ? [] : s.split('\u001f')
}

const MAPPERS = {
  works: (c) => ({
    workId: c[0],
    title: c[1] ?? '',
    isPublic: boolOf(c[2]),
    dataOrigin: c[3],
    sourceVersion: intOf(c[4]),
    topicIds: listOf(c[5]),
    organizations: listOf(c[6]),
    authorCount: intOf(c[7]),
    edgesMaterialized: boolOf(c[8])
  }),
  scholars: (c) => ({
    scholarId: c[0],
    scholarName: c[1] ?? '',
    dataOrigin: c[2],
    sourceVersion: intOf(c[3])
  }),
  authored: (c) => ({
    scholarId: c[0],
    workId: c[1],
    workTitle: c[2] ?? '',
    relationState: c[3],
    authorOrder: intOf(c[4])
  }),
  cooperated: (c) => ({
    scholarA: c[0],
    scholarB: c[1],
    workId: c[2],
    workTitle: c[3] ?? '',
    pairKey: c[4],
    dataOrigin: c[5],
    sourceVersion: intOf(c[6])
  })
}

// ---------------------------------------------------------------------------
// 分阶段装载
// ---------------------------------------------------------------------------
async function loadPhase(name, file, statement) {
  const path = resolve(DIR, file)
  if (!existsSync(path)) {
    throw new Error(`缺少输入文件 ${path}（先跑 export-graph-source.sql）`)
  }
  const sizeBytes = statSync(path).size
  const mapper = MAPPERS[name]

  let buffer = []
  let loaded = 0
  let batches = 0
  const t0 = Date.now()
  let lastLog = t0

  const flush = async () => {
    if (buffer.length === 0) return
    await runOne(statement, { rows: buffer })
    loaded += buffer.length
    batches++
    buffer = []
    const now = Date.now()
    if (now - lastLog > 5000) {
      lastLog = now
      const rate = loaded / ((now - t0) / 1000)
      process.stdout.write(
        `  ${name}: ${loaded.toLocaleString('en-US')} 行，${rate.toFixed(0)} 行/秒，RSS ${(process.memoryUsage().rss / 1048576).toFixed(0)} MB\n`
      )
    }
  }

  for await (const row of readTsv(path, mapper)) {
    buffer.push(row)
    if (buffer.length >= BATCH) await flush()
  }
  await flush()

  const elapsedMs = Date.now() - t0
  return {
    phase: name,
    inputBytes: sizeBytes,
    rows: loaded,
    batches,
    elapsedMs,
    rowsPerSecond: elapsedMs === 0 ? null : +((loaded / elapsedMs) * 1000).toFixed(1)
  }
}

function graphDirBytes() {
  if (!GRAPH_DIR || !existsSync(GRAPH_DIR)) return null
  try {
    const out = execSync(`du -sb ${JSON.stringify(GRAPH_DIR)}`, { encoding: 'utf8' })
    return Number(out.split(/\s+/)[0])
  } catch {
    return null
  }
}

async function countGraph() {
  const res = await tx([
    {
      statement: `MATCH (s:Scholar) WITH count(s) AS scholars
                  MATCH (w:Work) WITH scholars, count(w) AS works
                  MATCH ()-[a:AUTHORED]->() WITH scholars, works, count(a) AS authored
                  MATCH ()-[c:COOPERATED_WITH]->() WITH scholars, works, authored, count(c) AS cooperated
                  RETURN scholars, works, authored, cooperated`
    }
  ])
  const row = res.results[0].data[0].row
  return { scholars: row[0], works: row[1], authored: row[2], cooperated: row[3] }
}

async function profileQueries() {
  // 这 4 条形状与 Neo4jCollaborationGraphRepository 实际发出的语句一致（只把参数写成字面量，
  // 以便 PROFILE 能给出具体行数）。PROFILE 会真正执行，只在压测库上跑。
  const samples = [
    {
      name: 'network.graph depth=1（物化边 + 未物化二部图）',
      statement: `
        PROFILE MATCH (a:Scholar)-[c:COOPERATED_WITH]-(b:Scholar)
        WHERE a.scholar_id IN $ids AND c.is_valid = true AND c.data_origin = 'SIMULATED'
        WITH a.scholar_id AS aId, b.scholar_id AS bId,
             collect(DISTINCT {workId: c.work_id, title: c.work_title, dataOrigin: c.data_origin}) AS evidence
        RETURN aId, bId, size(evidence) AS strength, evidence
        ORDER BY aId, bId LIMIT $limit`,
      parameters: { ids: ['sch-fix-ring-0001', 'sch-fix-hub-core', 'sch-00000001'], limit: 20000 }
    },
    {
      name: 'network.graph 二部图（高作者数成果）',
      statement: `
        PROFILE MATCH (a:Scholar)-[ra:AUTHORED]->(w:Work)<-[rb:AUTHORED]-(b:Scholar)
        WHERE a.scholar_id IN $ids AND a.scholar_id <> b.scholar_id
          AND w.is_public = true AND w.edges_materialized = false
          AND ra.relation_state IN ['AUTO','SELF_CONFIRMED']
          AND rb.relation_state IN ['AUTO','SELF_CONFIRMED']
          AND w.data_origin = 'SIMULATED'
        WITH a.scholar_id AS aId, b.scholar_id AS bId,
             collect(DISTINCT {workId: w.work_id, title: w.title, dataOrigin: w.data_origin}) AS evidence
        RETURN aId, bId, size(evidence) AS strength, evidence
        ORDER BY aId, bId LIMIT $limit`,
      parameters: { ids: ['sch-fix-ha-001-01', 'sch-fix-ha-001-02'], limit: 20000 }
    },
    {
      name: '按 ID 取学者',
      statement: `PROFILE MATCH (s:Scholar) WHERE s.scholar_id IN $ids
                  RETURN s.scholar_id AS scholarId, s.scholar_name AS scholarName
                  ORDER BY scholarId`,
      parameters: { ids: ['sch-fix-ring-0001', 'sch-fix-ring-0002', 'sch-fix-tree-root'] }
    },
    {
      name: '未物化成绩单（edges_materialized 索引是否被用）',
      statement: `PROFILE MATCH (w:Work) WHERE w.is_public = true AND w.edges_materialized = false
                  RETURN count(w) AS unmaterializedWorks`,
      parameters: {}
    }
  ]
  const out = []
  for (const s of samples) {
    const res = await tx([{ statement: s.statement, parameters: s.parameters, resultDataContents: ['plan'] }])
    const plan = res.results[0]?.plan
    const summary = { name: s.name, time: res.results[0]?.stats?.time ?? null, plan: flattenPlan(plan) }
    out.push(summary)
    console.log(`\n[PROFILE] ${s.name}  (${summary.time} ms)`)
    for (const line of summary.plan) console.log('  ' + line)
  }
  return out
}

/** 把 Neo4j 计划树压平成「算子 / 估计行 / 实际行 / DB命中」的可读行。 */
function flattenPlan(node, depth = 0, acc = []) {
  if (!node) return acc
  const args = node.args ?? {}
  const rows = args.EstimatedRows ?? args.Rows ?? ''
  const dbHits = args.DbHits ?? ''
  const ident = args.Identifier ?? ''
  acc.push(
    `${'  '.repeat(depth)}${node.operatorType}${ident ? `(${ident})` : ''} rows=${rows} dbHits=${dbHits}`
  )
  for (const child of node.children ?? []) flattenPlan(child, depth + 1, acc)
  return acc
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------
const report = {
  startedAt: new Date().toISOString(),
  uri: URI,
  database: DATABASE,
  gen: GEN,
  scope: SCOPE,
  batchSize: BATCH,
  materializedThreshold: MATERIALIZED_THRESHOLD,
  storeBytesBefore: null,
  storeBytesAfter: null,
  phases: [],
  graph: null,
  profile: null
}

if (DRY_RUN) {
  await dryRun()
}

console.log(`Neo4j 批量装载 → ${URI} db=${DATABASE} gen=${GEN} batch=${BATCH} dir=${DIR}`)

await ensureSchema()

if (CLEAN) {
  // 与生产 requireExclusiveScope 同样的保护：库里存在其它 scope 时拒绝整库清空。
  const scopes = await tx([{ statement: 'MATCH (m:GraphProjectionMeta) RETURN m.scope AS scope' }])
  const existing = scopes.results[0].data.map((d) => d.row[0]).filter(Boolean)
  const foreign = existing.filter((s) => s !== SCOPE)
  if (foreign.length > 0) {
    console.error(
      `拒绝整库清空：库内存在其它投影作用域 ${JSON.stringify(foreign)}（本次 scope=${SCOPE}）。` +
        '批量装载只能对独占的压测实例执行。'
    )
    process.exit(4)
  }
  console.log('清空现有节点（--clean，作用域独占已确认）…')
  await runOne('MATCH (n) DETACH DELETE n', {})
}

report.storeBytesBefore = graphDirBytes()

const phases = [
  ['works', 'works.tsv', UPSERT_WORKS],
  ['scholars', 'scholars.tsv', UPSERT_SCHOLARS],
  ['authored', 'authored.tsv', CREATE_AUTHORED],
  ['cooperated', 'cooperated.tsv', CREATE_COOPERATED]
]

for (const [name, file, statement] of phases) {
  if (ONLY && ONLY !== name) continue
  process.stdout.write(`装载 ${name} …\n`)
  const res = await loadPhase(name, file, statement)
  report.phases.push(res)
  console.log(
    `  ${name}: ${res.rows.toLocaleString('en-US')} 行 / ${res.batches} 批 / ${(res.elapsedMs / 1000).toFixed(1)} s / ${res.rowsPerSecond} 行每秒`
  )
}

if (!ONLY) {
  await runOne(ADVANCE_META, {
    scope: SCOPE,
    projectedVersion: Number(args['projected-version'] ?? 1),
    catalogRevision: Number(args['catalog-revision'] ?? 1),
    generation: GEN,
    updatedAtEpochMs: Date.now()
  })
}

report.storeBytesAfter = graphDirBytes()
report.graph = await countGraph()
report.profile = await profileQueries()
report.finishedAt = new Date().toISOString()

console.log('\n=== 图库实测 ===')
console.log(
  `Scholar=${report.graph.scholars.toLocaleString('en-US')} Work=${report.graph.works.toLocaleString('en-US')} ` +
    `AUTHORED=${report.graph.authored.toLocaleString('en-US')} COOPERATED_WITH=${report.graph.cooperated.toLocaleString('en-US')}`
)
if (report.storeBytesBefore !== null && report.storeBytesAfter !== null) {
  console.log(
    `store 体积：${(report.storeBytesBefore / 1024 ** 3).toFixed(2)} GB → ${(report.storeBytesAfter / 1024 ** 3).toFixed(2)} GB`
  )
}
const totalRows = report.phases.reduce((s, p) => s + p.rows, 0)
const totalMs = report.phases.reduce((s, p) => s + p.elapsedMs, 0)
console.log(`合计 ${totalRows.toLocaleString('en-US')} 行 / ${(totalMs / 1000).toFixed(1)} s → ${((totalRows / totalMs) * 1000).toFixed(0)} 行每秒`)

const outPath = resolve(DIR, 'neo4j-load-report.json')
const { writeFileSync, mkdirSync } = await import('node:fs')
mkdirSync(dirname(outPath), { recursive: true })
writeFileSync(outPath, JSON.stringify(report, null, 2))
console.log(`报告：${outPath}`)
