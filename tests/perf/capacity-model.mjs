// =============================================================================
// 阶段六容量精算模型：1 万 / 10 万 / 100 万 / 500 万 去重成果
//
// 这个脚本只做算术，不连数据库、不写任何文件（除显式 --json/--markdown 指定）。
// 它存在的理由：阶段六要在导入前回答「这台机器能不能装下 500 万」，而凭印象回答
// 既不可复核也无法在评审里被推翻。因此把口径写死在代码里，每一行数字都能追到
// 一个可测量的来源。
//
// 两类来源，输出里必须分开标注，不允许混着写：
//   MEASURED —— 2026-10-17 在本仓库真实跑过 100 万 SIMULATED 数据后读到的大小
//                （docs/ai/第三批-容量与恢复验证记录-2026-10-17.md §3.2，单条证据可查）
//   ESTIMATE —— 由上述实测值按每行字节数外推，或由 Neo4j 5 记录格式的公开字节数推出
//
// 换算口径（不可省略）：
//   * 1 GB = 1024^3 B，1 MB = 1024^2 B（与 df -h / du -sh 的 GiB 口径一致）。
//   * PostgreSQL 体积按「堆 + 索引（含 GIN/trgm）」= pg_total_relation_size 口径，
//     不含 WAL；WAL 与临时文件单独一行，因为批量导入会额外写一遍。
//   * Neo4j 体积按「节点记录 + 关系记录 + 属性/字符串存储 + 索引」估算，见 NEO4J_BYTES。
//
// 用法：
//   node tests/perf/capacity-model.mjs
//   node tests/perf/capacity-model.mjs --avail-gb=21 --mem-gb=7.4 --json=/tmp/cap.json
//   node tests/perf/capacity-model.mjs --markdown=reports/perf/capacity.md --profile=base
//
// 主机自检与规模门禁（CI / 压测编排脚本共用同一份口径）：
//   node tests/perf/capacity-model.mjs --avail-gb=auto --mem-gb=auto --disk-path=.
//   node tests/perf/capacity-model.mjs --avail-gb=auto --mem-gb=auto --require-scale=5m
//
// `auto` 用真实读数而不是手填数字：磁盘用 statfsSync(bavail)（= df 的可用列，非总容量），
// 内存用 os.totalmem()。`--require-scale` 是**前置安全门禁**，不通过就非零退出，
// 让「本机跑不动 500 万」这件事在导入第一行之前就失败，而不是跑到一半写满磁盘。
//
// 退出码：
//   0  正常（未给 --require-scale，或给定规模的容量门禁通过）
//   2  参数错误
//   3  标定自检超差（模型与 2026-10-17 实测基线漂移，外推结果不可引用）
//   4  前置安全门禁阻断（磁盘或内存不足），**这不是脚本 bug，是资源事实**
// =============================================================================

import { writeFileSync, mkdirSync, statfsSync } from 'node:fs'
import { dirname } from 'node:path'
import os from 'node:os'

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const [k, ...rest] = a.replace(/^--/, '').split('=')
    return [k, rest.join('=')]
  })
)

const MiB = 1024 * 1024
const GiB = 1024 * MiB

// ---------------------------------------------------------------------------
// 0. 主机读数（auto 模式）
//
// 为什么必须支持 auto：容量门禁要回答的是「**这台机器现在**能不能装下」，而手填的
// `--avail-gb=21` 会随着别的测试数据卷、WAL、符号链接解析而立刻失真。CI 里 runner
// 规格由 `runner_label` 决定（ubuntu-latest 与自托管 8C32G 差一个数量级），更不能写死。
// ---------------------------------------------------------------------------
const HOST_PROBE = (() => {
  const diskPath = args['disk-path'] ?? process.cwd()
  const availRaw = args['avail-gb'] ?? '21'
  const memRaw = args['mem-gb'] ?? '7.4'
  const probe = {
    diskPath,
    availSource: 'arg',
    memSource: 'arg',
    cpuCount: os.cpus()?.length ?? null,
    platform: `${os.platform()} ${os.release()}`
  }
  let availGb
  let memGb
  if (availRaw === 'auto') {
    try {
      const st = statfsSync(diskPath)
      // bavail = 非特权用户可用块数，与 `df` 的 Available 列同口径；
      // 用 bsize 而不是 frsize：overlayfs/ext4 下二者相同，tmpfs 下 bsize 更保守。
      availGb = +((st.bavail * st.bsize) / GiB).toFixed(1)
      probe.availSource = 'statfs'
    } catch (err) {
      console.error(`无法读取 ${diskPath} 的可用空间（${err.code ?? err.message}）：请显式给 --avail-gb=<GB>`)
      process.exit(2)
    }
  } else {
    availGb = Number(availRaw)
  }
  if (memRaw === 'auto') {
    memGb = +(os.totalmem() / GiB).toFixed(1)
    probe.memSource = 'os.totalmem'
  } else {
    memGb = Number(memRaw)
  }
  if (!Number.isFinite(availGb) || availGb <= 0) {
    console.error(`--avail-gb=${availRaw} 不是有效数值`)
    process.exit(2)
  }
  if (!Number.isFinite(memGb) || memGb <= 0) {
    console.error(`--mem-gb=${memRaw} 不是有效数值`)
    process.exit(2)
  }
  return { ...probe, availGb, memGb }
})()

// --require-scale 接受 10k/100k/1m/5m，也接受报告里的中文标签（1万/10万/100万/500万）。
const SCALE_ALIASES = {
  '10k': '1万',
  '100k': '10万',
  '1m': '100万',
  '5m': '500万',
  '1万': '1万',
  '10万': '10万',
  '100万': '100万',
  '500万': '500万'
}
const REQUIRED_STAGE = args['require-scale'] ? SCALE_ALIASES[args['require-scale']] : null
if (args['require-scale'] && !REQUIRED_STAGE) {
  console.error(`未知 --require-scale=${args['require-scale']}，可选：${Object.keys(SCALE_ALIASES).join(' / ')}`)
  process.exit(2)
}

// ---------------------------------------------------------------------------
// 1. 实测基线（唯一允许写死的一组数字）
//
// 来源：100 万去重成果、3,000,470→2,999,440 条参与关系、1,724,226 条合作边的真实
// pg_total_relation_size / pg_relation_size 读数。字段含义：
//   heapB  = 每行堆字节（pg_relation_size / 行数）
//   indexB = 每行索引字节（(total - heap) / 行数）
// 这套每行字节只在「行内容形状不变」时成立：本模型假定 500 万阶段沿用的仍是同一批
// 字段与同一批索引。若迁移新增索引或字段，必须重测基线，而不是继续外推。
// ---------------------------------------------------------------------------
const MEASURED_1M = {
  source: 'docs/ai/第三批-容量与恢复验证记录-2026-10-17.md §3.2（2026-10-17 实测，Windows/22 核/PG16 默认参数）',
  works: 1_000_000,
  contributions: 2_999_440,
  contributionOrg: 1_500_470,
  achievementTopic: 1_491_780,
  coauthorEdges: 1_724_226,
  tables: [
    { name: 'qry_achievement', rows: 1_000_000, totalMiB: 460, heapMiB: 358 },
    { name: 'qry_contribution', rows: 2_999_440, totalMiB: 557, heapMiB: 289 },
    { name: 'qry_coauthor_edge', rows: 1_724_226, totalMiB: 314, heapMiB: 139 },
    { name: 'qry_contribution_org', rows: 1_500_470, totalMiB: 219, heapMiB: 110 },
    { name: 'qry_achievement_topic', rows: 1_491_780, totalMiB: 213, heapMiB: 120 }
  ],
  // core 侧同批 100 万实体的 pg_dump（custom，压缩后）体积；压缩后体量只能用于
  // 备份窗口估算，不能用来推断在线表体积。
  coreDumpMiB: 38,
  queryDumpMiB: 91
}

const PG_PER_ROW = Object.fromEntries(
  MEASURED_1M.tables.map((t) => [
    t.name,
    { heapB: (t.heapMiB * MiB) / t.rows, indexB: ((t.totalMiB - t.heapMiB) * MiB) / t.rows }
  ])
)

// ---------------------------------------------------------------------------
// 2. 作者数分布（与 tests/perf/scale/seed-scale-query.sql 必须逐字一致）
//
// 这条「两个文件必须一致」的约束由 ops/perf/run-scale-benchmark.sh 的实际计数对账
// 来兜底：造数完成后会把实测行数与本模型的预测行数对比，偏差超阈值即判定脚本漂移。
//
// 分布形状的理由：
//   * 6% 单作者成果：存在但很少，且它们一条合作边都不产生（用于验证「单学者」不被
//     伪造成有合作者）。
//   * 66% 2–3 位作者：最常见的形态，决定贡献表基数。
//   * 23% 4–5 位、4% 6–10 位：中等团队。
//   * 1% 11–20 位：大团队。上限刻意压在 20（= Neo4j 物化阈值默认值），
//     这样随机分布部分在 PG 与 Neo4j 上产生**完全相同的边集**，影子对照不会因为
//     物化阈值差异而报假差异；> 20 位的高作者数成果改为显式 fixture（见 fixtures-graph.sql），
//     专门用来走「不物化 → 二部图推导」那条路径。
// ---------------------------------------------------------------------------
const PROFILES = {
  // 与 2026-10-17 的 seed-query.sql 同形（2–4 位作者均匀），用于和历史结果对齐。
  base: {
    label: 'base（与 2026-10-17 实测同形：2–4 位作者均匀，仅论文建边）',
    buckets: [{ min: 2, max: 4, share: 1 }],
    pendingRelationPct: 3,
    papersOnly: true,
    paperShare: 0.55
  },
  // 阶段六默认档：3.2 位作者/成果、约 5.2 条合作边/成果，落在任务书要求的
  // 「每篇 3~4 位学者、3~6 条合作边」区间内。
  'graph-heavy': {
    label: 'graph-heavy（阶段六默认：单作者 6% / 2–3 位 66% / 4–5 位 23% / 6–10 位 4% / 11–20 位 1%）',
    buckets: [
      { min: 1, max: 1, share: 0.06 },
      { min: 2, max: 3, share: 0.66 },
      { min: 4, max: 5, share: 0.23 },
      { min: 6, max: 10, share: 0.04 },
      { min: 11, max: 20, share: 0.01 }
    ],
    pendingRelationPct: 3,
    // 生产投影（ProjectionApplier.pairs / EventPayloadReader.pairs）并**不**按 type 过滤，
    // 因此倾斜档按「全部类型建边」计算。规则文字写的是「共同论文」，代码里没有这条判断，
    // 该差异已作为发现项写进报告，不在本分支修改业务语义。
    papersOnly: false,
    paperShare: 0.55
  }
}

const PROFILE_KEY = args.profile ?? 'graph-heavy'
const PROFILE = PROFILES[PROFILE_KEY]
if (!PROFILE) {
  console.error(`未知 --profile=${PROFILE_KEY}，可选：${Object.keys(PROFILES).join(' / ')}`)
  process.exit(2)
}

// ---------------------------------------------------------------------------
// 3. Neo4j 5 记录字节数（ESTIMATE，可按实测标定）
//
// 这些是「一个对象平均占多少字节」的口径，来自 Neo4j 5 固定记录格式与属性/字符串存储的
// 公开结构（节点记录 32B 块、关系记录 40B、属性块与动态字符串各自对齐）。它不是
// 官方承诺，而是可被 1 万阶段实测直接替换的标定量：run-scale-benchmark.sh 在 1 万
// 阶段会读取 du -sb 的实际 store 体积回填 calibration.json，之后各阶段的预测以
// 标定后的系数为准。
// ---------------------------------------------------------------------------
const NEO4J_BYTES = {
  note: 'ESTIMATE：Neo4j 5.26 Community 固定记录格式 + 属性/字符串存储 + 单属性索引条目',
  scholarNode: 180, // scholar_id/name/domain/organization/data_origin/source_version
  workNode: 240, // + title/topic_ids/organizations/author_count/edges_materialized/updated_at
  authoredRel: 120, // 记录 + work_id/work_title/relation_state/author_order
  cooperatedRel: 230, // 记录 + work_id/work_title/pair_key/data_origin/source_version/rule_version/is_valid
  scholarIdIndex: 40,
  workIdIndex: 40,
  scholarSecondaryIndex: 20, // domain / organization / data_origin 各一条
  workSecondaryIndex: 16, // is_public / data_origin / source_version / edges_materialized
  authoredRelIndex: 24, // AUTHORED(work_id)
  cooperatedRelIndex: 24, // work_id / pair_key / data_origin
  metaNode: 200,
  // 事务日志在批量导入期间会与 store 同量级增长；Neo4j 默认保留 7 天。
  // 容量规划必须显式把它算进去，否则「导入完发现磁盘满了」。
  txLogFactor: 0.35
}

// ---------------------------------------------------------------------------
// 3b. V5／V6 新增的 trigram 与表达式索引（ESTIMATE，必须由 1 万阶段实测回填）
//
// 这是本模型最容易低估的一块，必须单独列出来而不是揉进每行字节里：
// 2026-10-17 的实测基线**不含** V5（ix_qry_achievement_abstract_trgm、
// ix_qry_contribution_name_trgm）与 V6（venue_lower_trgm、abstract_lower_trgm、
// doi_lower、scholar_name_lower_trgm）。这些迁移是在那批压测之后才加的，
// 直接按基线外推会系统性低估「今天的 schema」在 500 万下的体积。
//
// 系数的量级依据：GIN trigram 索引的每行成本主要由被索引文本的长度与重复度决定，
// 中文按字符三元组分词，一行 40–60 字的摘要会产生上百个三元组；但跨行重复的三元组
// 会共享 posting list，因此边际成本远低于文本字节数。给的是一个可被实测推翻的估算。
// ---------------------------------------------------------------------------
const EXTRA_INDEX_BYTES = {
  note: 'ESTIMATE；区间约 ±60%（取决于摘要文本长度、姓名与机构名的重复度）。1 万阶段应以 pg_indexes 实际体积回填',
  perAchievementRow: 90, // abstract_trgm + venue_lower_trgm + abstract_lower_trgm + doi_lower
  perContributionRow: 55 // scholar_name_trgm + scholar_name_lower_trgm
}

// ---------------------------------------------------------------------------
// 4. 内存口径
//
// 2C8G 无 Swap 是本机硬约束。下面每一项都是「能让该阶段跑起来」的下限，不是推荐值：
//   PG shared_buffers     —— 索引可用性依赖它；小于数据量时大量读走 page cache 再落盘
//   PG work_mem / 并行排序 —— 统计类聚合（领域/机构 TOP）会外排到临时文件
//   Neo4j pagecache      —— 必须能放下索引与热点记录；官方建议 pagecache ≥ store 大小
//   Neo4j heap           —— 查询中的 collect()/排序/tx state
//   两服务 JVM            —— core 与 query 各自 -Xmx（默认 JVM 上限 = 物理内存 1/4）
//   负载生成器            —— Node 进程 + 每条请求的延迟样本（数组，100 VU 规模可忽略）
// ---------------------------------------------------------------------------
const MEMORY = {
  note: 'ESTIMATE：各进程的运行下限，单位为 GiB;不含操作系统与页缓存回写余量',
  pgSharedBuffersGb: (gb) => Math.min(4, Math.max(0.25, roundTo(gb * 0.18, 0.25))),
  pgWorkMemMb: 8,
  neo4jPagecacheGb: (storeGb) => roundTo(storeGb * 1.0, 0.25),
  neo4jHeapGb: (storeGb) => roundTo(Math.min(4, Math.max(0.5, storeGb * 0.35)), 0.25),
  serviceJvmGb: 0.75, // query-service -Xmx；core 只在测详情接口时才需要同时在线
  coreJvmGb: 0.75,
  loadgenGb: 0.35,
  osReserveGb: 0.9
}

// ---------------------------------------------------------------------------
// 5. 计算
// ---------------------------------------------------------------------------
function roundTo(value, step) {
  return Math.round(value / step) * step
}

function bucketStats(bucket) {
  const n = bucket.max - bucket.min + 1
  let sum = 0
  let sumSq = 0
  for (let k = bucket.min; k <= bucket.max; k++) {
    sum += k
    sumSq += k * k
  }
  return { mean: sum / n, meanSq: sumSq / n, share: bucket.share }
}

/** 与合作者数分布无关的常数：E[k]、E[k²]、E[C(k,2)]，以及「合格关系」下的折减。 */
function profileMoments(profile) {
  let mean = 0
  let meanSq = 0
  for (const b of profile.buckets) {
    const s = bucketStats(b)
    mean += s.mean * s.share
    meanSq += s.meanSq * s.share
  }
  const rawPairs = (meanSq - mean) / 2
  // 3% 关系为 PENDING_VERIFY：它既不计入确定合作，也不产生边（BR-IMP-08）。
  const keep = 1 - profile.pendingRelationPct / 100
  const qualifiedPairs = profile.papersOnly ? rawPairs * keep * profile.paperShare : rawPairs * keep
  return { mean, meanSq, rawPairs, qualifiedPairs, qualifiedAuthors: mean * keep, keep }
}

/**
 * Neo4j 物化阈值（cn.query.graph.max-authors-per-work-for-materialized-edge，缺省 20）：
 * 作者数 ≤ 20 的公开成果才物化 COOPERATED_WITH；超过的只留 AUTHORED，由二部图推导。
 * 因此 Neo4j 的边数比 PG 少，二者差额正是「高作者数成果」这一 fixture 要覆盖的路径。
 */
const MATERIALIZED_AUTHOR_THRESHOLD = 20

function materializedMoments(profile) {
  let pairs = 0
  let mean = 0
  for (const b of profile.buckets) {
    const lo = b.min
    const hi = Math.min(b.max, MATERIALIZED_AUTHOR_THRESHOLD)
    if (hi < Math.max(lo, 1) || hi < 1) continue
    if (lo === 1 && hi === 1) {
      mean += 1 * b.share
      continue
    }
    const effLo = Math.max(lo, 2)
    if (effLo > hi) {
      mean += lo * b.share
      continue
    }
    const n = hi - effLo + 1
    let sum = 0
    let sumSq = 0
    for (let k = effLo; k <= hi; k++) {
      sum += k
      sumSq += k * k
    }
    const shareThisPart = (b.share * n) / (b.max - b.min + 1)
    pairs += ((sumSq / n - sum / n) / 2) * shareThisPart
    mean += (sum / n) * shareThisPart
  }
  const keep = 1 - profile.pendingRelationPct / 100
  return { pairs: pairs * keep, mean }
}

function pgEstimate(counts) {
  const rows = {
    qry_achievement: counts.works,
    qry_contribution: counts.contributions,
    qry_coauthor_edge: counts.edges,
    qry_contribution_org: counts.orgs,
    qry_achievement_topic: counts.topics
  }
  const tables = Object.entries(rows).map(([name, n]) => {
    const per = PG_PER_ROW[name]
    const heap = (n * per.heapB) / GiB
    const index = (n * per.indexB) / GiB
    return { name, rows: n, heapGb: heap, indexGb: index, totalGb: heap + index }
  })
  const total = tables.reduce((s, t) => s + t.totalGb, 0)
  // V5／V6 索引单列：它们不在 2026-10-17 的实测基线里（见 EXTRA_INDEX_BYTES 说明）。
  const extraIndexGb =
    (rows.qry_achievement * EXTRA_INDEX_BYTES.perAchievementRow +
      rows.qry_contribution * EXTRA_INDEX_BYTES.perContributionRow) /
    GiB
  // 其余投影表（inbox 正文、水位、重建记录、checkpoint）：100 万实测里合计不到 60 MB，
  // 但 inbox 正文随事件量增长，5 亿级事件才会显著，这里按 2% + 固定 30 MB 计。
  const others = total * 0.02 + 30 / 1024
  // WAL：批量导入期间每个新行都会写一遍 WAL；checkpoint 能截断，但仍需预留一份。
  // 索引重建期间的临时排序文件按最大的两张表估。
  const withExtra = total + extraIndexGb
  const wal = withExtra * 0.45
  const temp = Math.max(...tables.map((t) => t.totalGb)) * 0.6
  return {
    tables,
    tablesGb: total,
    extraIndexGb,
    othersGb: others,
    walGb: wal,
    tempGb: temp,
    // 落库合计 = 表与索引（含 V5/V6）+ 其它投影表
    diskGb: withExtra + others,
    // 规划用量 = 落库合计 + WAL 预留 + 索引重建临时文件。
    // 安全预检必须用这个数：只按落库量算，导入到一半就会因为 WAL 与临时排序文件而满盘。
    planningGb: withExtra + others + wal + temp
  }
}

function neo4jEstimate(counts, moments) {
  const scholars = counts.scholars
  const works = counts.works
  const authored = counts.neoAuthored
  const cooperated = counts.neoCooperated

  const nodeStore =
    (scholars * NEO4J_BYTES.scholarNode + works * NEO4J_BYTES.workNode + NEO4J_BYTES.metaNode) / GiB
  const relStore =
    (authored * NEO4J_BYTES.authoredRel + cooperated * NEO4J_BYTES.cooperatedRel) / GiB
  const indexStore =
    (scholars * (NEO4J_BYTES.scholarIdIndex + NEO4J_BYTES.scholarSecondaryIndex * 3) +
      works * (NEO4J_BYTES.workIdIndex + NEO4J_BYTES.workSecondaryIndex * 4) +
      authored * NEO4J_BYTES.authoredRelIndex +
      cooperated * NEO4J_BYTES.cooperatedRelIndex * 3) /
    GiB
  const store = nodeStore + relStore + indexStore
  const txLog = store * NEO4J_BYTES.txLogFactor
  return { nodeStoreGb: nodeStore, relStoreGb: relStore, indexStoreGb: indexStore, storeGb: store, txLogGb: txLog, diskGb: store + txLog }
}

function scholarPool(works, moments, worksPerScholar) {
  return Math.round((works * moments.mean) / worksPerScholar)
}

// fixtfixture 附加量（见 tests/perf/scale/fixtures-graph.sql，数量固定，与 n 无关）
const FIXTURES = {
  ringScholars: 200,
  ringWorks: 200,
  chainScholars: 10 + 30 + 60,
  chainWorks: 9 + 29 + 59,
  equalPathScholars: 2 + 5,
  equalPathWorks: 5 * 2,
  treeScholars: 1 + 3 + 9 + 27,
  treeWorks: 3 + 9 + 27,
  isolatedScholars: 50,
  isolatedWorks: 50,
  hubScholars: 1 + 300,
  hubWorks: 300,
  highAuthorWorks: 200,
  highAuthorScholars: 200 * 40,
  highAuthorAvgAuthors: 40
}

/**
 * 结构 fixture 的精确行数（口径与 tests/perf/scale/fixtures-graph.sql 一致）。
 *
 * fixture 是**固定附加量**，不随 n 变化：1 万阶段与 500 万阶段都有同样的一批
 * 环／链／等长路径／孤立点／枢纽／高作者数成果。因此报告里的「去重成果数」必须
 * 分成「随机部分 n」与「fixture」两行写，不能把 fixture 混进 n 再声称规模精确等于 n。
 */
function fixtureCounts() {
  const f = FIXTURES
  const ring = f.ringWorks // 200 条边构成 200 点环
  const chain = 9 - 1 + (29 - 1) + (59 - 1) // 链长 L 的边数 = L-1
  const equalPath = f.equalPathWorks // 5 条等长（3 跳）路径，每条 3 段边、由 2 段组成
  const tree = f.treeWorks // 树：每篇合作成果连一条边
  const isolated = 0 // 单作者成果：无合作边
  const hub = f.hubWorks // 枢纽各连 1 条边
  const edges = ring + chain + equalPath + tree + isolated + hub
  const works =
    f.ringWorks +
    f.chainWorks +
    f.equalPathWorks +
    f.treeWorks +
    f.isolatedWorks +
    f.hubWorks +
    f.highAuthorWorks
  const contributions =
    f.ringWorks * 2 +
    f.chainWorks * 2 +
    f.equalPathWorks * 2 +
    f.treeWorks * 2 +
    f.isolatedWorks * 1 +
    f.hubWorks * 2 +
    f.highAuthorWorks * f.highAuthorAvgAuthors
  const highAuthorPairs =
    f.highAuthorWorks * ((f.highAuthorAvgAuthors * (f.highAuthorAvgAuthors - 1)) / 2)
  const scholarsAdd =
    f.ringScholars +
    f.chainScholars +
    f.equalPathScholars +
    f.treeScholars +
    f.isolatedScholars +
    f.hubScholars +
    f.highAuthorScholars
  return { works, edges, contributions, highAuthorPairs, scholarsAdd, orgs: Math.round(contributions * 0.5), topics: works }
}

const STAGES = [
  { label: '1万', works: 10_000 },
  { label: '10万', works: 100_000 },
  { label: '100万', works: 1_000_000 },
  { label: '500万', works: 5_000_000 }
]

const worksPerScholar = Number(args['works-per-scholar'] ?? 6)
const availGb = HOST_PROBE.availGb
const memGb = HOST_PROBE.memGb
const diskHeadroomGb = Number(args['disk-headroom-gb'] ?? 4)

const moments = profileMoments(PROFILE)
const matMoments = materializedMoments(PROFILE)
const fx = fixtureCounts()

const rows = STAGES.map((stage) => {
  const n = stage.works
  const worksTotal = n + fx.works
  const contributions = Math.round(n * moments.mean) + fx.contributions
  const edges = Math.round(n * moments.qualifiedPairs) + fx.edges + fx.highAuthorPairs
  const orgs = Math.round(contributions * 0.5)
  const topics = Math.round(n * 1.49) + fx.topics
  const scholars = scholarPool(n, moments, worksPerScholar) + fx.scholarsAdd
  // 只有公开 + 生效的成果才在图上留下 AUTHORED / COOPERATED_WITH；100 万实测的
  // PUBLIC+EFFECTIVE 占比为 82.8%（828,174 / 1,000,000）。
  const publicShare = 0.828
  const neoAuthoredPublic = Math.round(contributions * publicShare)
  const neoCooperated = Math.round(n * matMoments.pairs) + fx.edges
  const counts = {
    works: worksTotal,
    contributions,
    edges,
    orgs,
    topics,
    scholars,
    neoAuthored: neoAuthoredPublic,
    neoCooperated
  }
  const pg = pgEstimate(counts)
  const neo = neo4jEstimate(counts, moments)
  const diskGb = pg.planningGb + neo.diskGb
  const mem = estimateMemory(pg, neo)
  return {
    stage: stage.label,
    works: worksTotal,
    randomWorks: n,
    fixtureWorks: fx.works,
    scholars,
    contributions,
    edges,
    neoAuthored: neoAuthoredPublic,
    neoCooperated,
    orgs,
    topics,
    authorsPerWork: moments.mean,
    edgesPerWork: moments.qualifiedPairs,
    pg,
    neo4j: neo,
    diskGb,
    memory: mem,
    verdict: verdictFor(diskGb, mem)
  }
})

function estimateMemory(pg, neo) {
  const sharedBuffers = MEMORY.pgSharedBuffersGb(pg.tablesGb)
  const workMem = (MEMORY.pgWorkMemMb * 6) / 1024
  const recommendedPagecache = MEMORY.neo4jPagecacheGb(neo.storeGb)
  const recommendedHeap = MEMORY.neo4jHeapGb(neo.storeGb)
  // 同机最低配：pagecache 只装得下约 1/4 的 store（索引与热点记录）。
  // 此时能跑通，但缓存命中率不再是「全部」，P95 会被磁盘 IO 主导——
  // 因此该配置下的数字只能当「下限压测」，不能当容量结论。
  const minimalPagecache = roundTo(Math.max(0.5, neo.storeGb * 0.25), 0.25)
  const minimalHeap = roundTo(Math.max(0.5, Math.min(2, neo.storeGb * 0.2)), 0.25)
  const fixed = MEMORY.serviceJvmGb + MEMORY.coreJvmGb + MEMORY.loadgenGb + MEMORY.osReserveGb
  return {
    pgSharedBuffersGb: sharedBuffers,
    pgWorkMemGb: workMem,
    neo4jPagecacheGb: recommendedPagecache,
    neo4jHeapGb: recommendedHeap,
    minimalPagecacheGb: minimalPagecache,
    minimalHeapGb: minimalHeap,
    servicesGb: MEMORY.serviceJvmGb + MEMORY.coreJvmGb,
    loadgenGb: MEMORY.loadgenGb,
    osReserveGb: MEMORY.osReserveGb,
    totalGb: sharedBuffers + workMem + recommendedPagecache + recommendedHeap + fixed,
    minimalTotalGb: sharedBuffers + workMem + minimalPagecache + minimalHeap + fixed,
    // 只跑一侧（只有 PG 或只有 Neo4j）时能省下的部分
    pgOnlyGb: sharedBuffers + workMem + fixed,
    neo4jOnlyGb: minimalPagecache + minimalHeap + fixed
  }
}

function verdictFor(diskGb, mem) {
  const diskOk = diskGb + diskHeadroomGb <= availGb
  const recommendedOk = mem.totalGb <= memGb * 0.9
  const minimalOk = mem.minimalTotalGb <= memGb * 0.9
  if (diskOk && recommendedOk) return '可实测（推荐配置）'
  if (diskOk && minimalOk) return '仅同机最低配置可跑（pagecache 不足，P95 不得当作容量结论）'
  if (!diskOk) return `不可在本机实测：磁盘需 ${(diskGb + diskHeadroomGb).toFixed(1)} GB（含 ${diskHeadroomGb} GB 余量），可用 ${availGb} GB`
  return '不可在本机实测：内存不足'
}

/**
 * 前置安全门禁：把「能不能在本机跑这一档」写成可判定的布尔，而不是一句描述。
 *
 * 判定口径与 verdictFor 一致，但**磁盘算上余量、内存只用下限**：门禁的职责是拦住
 * 必然失败或必然 OOM 的运行，不是拦「跑得动但缓存不足」的运行（后者已在报告里用
 * verdict 标注 P95 不得当作容量结论）。
 *
 * 注意：90% 内存上限是给操作系统与页缓存回写留的余量，不是可以商量的目标值；
 * 修改它等于修改验收口径，必须走评审。
 */
function gateFor(row) {
  const reasons = []
  const diskNeed = row.diskGb + diskHeadroomGb
  if (diskNeed > availGb) {
    reasons.push(
      `磁盘不足：需要 ${diskNeed.toFixed(2)} GB（含 ${diskHeadroomGb} GB 安全余量），可用 ${availGb} GB，缺 ${(diskNeed - availGb).toFixed(2)} GB`
    )
  }
  const memLimitGb = +(memGb * 0.9).toFixed(2)
  if (row.memory.minimalTotalGb > memLimitGb) {
    reasons.push(
      `内存不足：同机最低配置需 ${row.memory.minimalTotalGb.toFixed(2)} GB（shared_buffers+pagecache+heap+JVM+负载生成器+系统余量），可用 ${memGb} GB 的 90% = ${memLimitGb} GB`
    )
  }
  const mode =
    row.memory.totalGb <= memLimitGb
      ? 'recommended'
      : row.memory.minimalTotalGb <= memLimitGb
        ? 'minimal'
        : 'blocked'
  return { ok: reasons.length === 0, mode, diskNeedGb: +diskNeed.toFixed(2), memLimitGb, reasons }
}

// ---------------------------------------------------------------------------
// 6. 标定自检：模型必须能复现 100 万实测值，否则就是口径漂移
// ---------------------------------------------------------------------------
const calibration = (() => {
  const base = PROFILES.base
  const m = profileMoments(base)
  const mat = materializedMoments(base)
  const predictedContributions = Math.round(MEASURED_1M.works * m.mean)
  const predictedEdges = Math.round(MEASURED_1M.works * m.qualifiedPairs)
  const pg = pgEstimate({
    works: MEASURED_1M.works,
    contributions: MEASURED_1M.contributions,
    edges: MEASURED_1M.coauthorEdges,
    orgs: MEASURED_1M.contributionOrg,
    topics: MEASURED_1M.achievementTopic
  })
  const measuredTablesGb =
    MEASURED_1M.tables.reduce((s, t) => s + t.totalMiB, 0) / 1024
  const checks = [
    {
      what: '参与关系行数',
      predicted: predictedContributions,
      measured: MEASURED_1M.contributions,
      tolerance: 0.1
    },
    {
      what: '合作边行数',
      predicted: predictedEdges,
      measured: MEASURED_1M.coauthorEdges,
      tolerance: 0.1
    },
    {
      what: '五张投影表合计体积',
      predictedGiB: pg.tablesGb,
      measuredGiB: measuredTablesGb,
      tolerance: 0.15
    }
  ].map((c) => {
    const predicted = c.predicted ?? c.predictedGiB
    const measured = c.measured ?? c.measuredGiB
    const deviation = Math.abs(predicted - measured) / measured
    return {
      ...c,
      predicted,
      measured,
      deviationPct: +(deviation * 100).toFixed(2),
      ok: deviation <= c.tolerance
    }
  })
  return {
    input: {
      authors: m.mean,
      rawPairsPerWork: m.rawPairs,
      qualifiedPairsPerWork: m.qualifiedPairs,
      materializedPairsPerWork: mat.pairs
    },
    checks,
    ok: checks.every((c) => c.ok)
  }
})()

// ---------------------------------------------------------------------------
// 7. 输出
// ---------------------------------------------------------------------------
const fmtGb = (v) => `${v.toFixed(2)} GB`
const fmtInt = (v) => v.toLocaleString('en-US')

function toMarkdown() {
  const lines = []
  lines.push('# 阶段六容量精算（自动生成，勿手工改数）')
  lines.push('')
  lines.push(`- 生成命令：\`node tests/perf/capacity-model.mjs${Object.keys(args).length ? ' --' + Object.entries(args).map(([k, v]) => `${k}=${v}`).join(' --') : ''}\``)
  lines.push(`- 作者数分布：${PROFILE.label}`)
  lines.push(`- 学者池口径：每学者平均承担 ${worksPerScholar} 项成果（\`--works-per-scholar\`）`)
  lines.push(
    `- 本机可用磁盘 ${availGb} GB（来源 ${HOST_PROBE.availSource}${HOST_PROBE.availSource === 'statfs' ? `，路径 ${HOST_PROBE.diskPath}` : ''}，预留 ${diskHeadroomGb} GB 安全余量），` +
      `内存 ${memGb} GB（来源 ${HOST_PROBE.memSource}）`
  )
  lines.push('')
  lines.push('## 1. 规模与实体数')
  lines.push('')
  lines.push('| 阶段 | 去重成果 | 学者 | 作者关系 | 合作边（PG） | Neo4j AUTHORED | Neo4j COOPERATED | 领域行 | 机构行 |')
  lines.push('| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
  for (const r of rows) {
    lines.push(
      `| ${r.stage} | ${fmtInt(r.works)} | ${fmtInt(r.scholars)} | ${fmtInt(r.contributions)} | ${fmtInt(r.edges)} | ${fmtInt(r.neoAuthored)} | ${fmtInt(r.neoCooperated)} | ${fmtInt(r.topics)} | ${fmtInt(r.orgs)} |`
    )
  }
  lines.push('')
  lines.push('## 2. PostgreSQL 体积（ESTIMATE，按 100 万实测每行字节外推）')
  lines.push('')
  lines.push('| 阶段 | 表与索引（基线外推） | V5/V6 新增索引（估算） | 其他投影表 | WAL 预留 | 索引重建临时文件 | 规划用量 |')
  lines.push('| --- | ---: | ---: | ---: | ---: | ---: | ---: |')
  for (const r of rows) {
    lines.push(
      `| ${r.stage} | ${fmtGb(r.pg.tablesGb)} | ${fmtGb(r.pg.extraIndexGb)} | ${fmtGb(r.pg.othersGb)} | ${fmtGb(r.pg.walGb)} | ${fmtGb(r.pg.tempGb)} | ${fmtGb(r.pg.planningGb)} |`
    )
  }
  lines.push('')
  lines.push('## 3. Neo4j 体积（ESTIMATE）')
  lines.push('')
  lines.push('| 阶段 | 节点存储 | 关系存储 | 索引 | store 合计 | 事务日志 | 磁盘合计 |')
  lines.push('| --- | ---: | ---: | ---: | ---: | ---: | ---: |')
  for (const r of rows) {
    lines.push(
      `| ${r.stage} | ${fmtGb(r.neo4j.nodeStoreGb)} | ${fmtGb(r.neo4j.relStoreGb)} | ${fmtGb(r.neo4j.indexStoreGb)} | ${fmtGb(r.neo4j.storeGb)} | ${fmtGb(r.neo4j.txLogGb)} | ${fmtGb(r.neo4j.diskGb)} |`
    )
  }
  lines.push('')
  lines.push('## 4. 内存需求与判定')
  lines.push('')
  lines.push('| 阶段 | 磁盘合计 | shared_buffers | pagecache | Neo4j heap | 推荐合计 | 最低合计 | 判定 |')
  lines.push('| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |')
  for (const r of rows) {
    lines.push(
      `| ${r.stage} | ${fmtGb(r.diskGb)} | ${r.memory.pgSharedBuffersGb.toFixed(2)} GB | ${r.memory.neo4jPagecacheGb.toFixed(2)} GB | ${r.memory.neo4jHeapGb.toFixed(2)} GB | ${r.memory.totalGb.toFixed(2)} GB | ${r.memory.minimalTotalGb.toFixed(2)} GB | ${r.verdict} |`
    )
  }
  lines.push('')
  lines.push('## 5. 标定自检（模型 vs 2026-10-17 实测）')
  lines.push('')
  lines.push('| 指标 | 模型预测 | 实测 | 偏差 | 结论 |')
  lines.push('| --- | ---: | ---: | ---: | --- |')
  for (const c of calibration.checks) {
    lines.push(
      `| ${c.what} | ${Number.isInteger(c.predicted) ? fmtInt(c.predicted) : c.predicted.toFixed(2)} | ${Number.isInteger(c.measured) ? fmtInt(c.measured) : c.measured.toFixed(2)} | ${c.deviationPct}% | ${c.ok ? '通过' : '超差'} |`
    )
  }
  lines.push('')
  lines.push('## 6. 口径与未覆盖')
  lines.push('')
  lines.push(`- 实测基线来源：${MEASURED_1M.source}`)
  lines.push(`- Neo4j 字节数口径：${NEO4J_BYTES.note}。这是 ESTIMATE，1 万阶段实测后会回填标定系数。`)
  lines.push(`- V5/V6 新增索引口径：${EXTRA_INDEX_BYTES.note}。2026-10-17 的实测基线不含这些索引，`)
  lines.push('  因此「表+索引」一列与历史报告的数字不可直接相加——历史数字对应的是旧 schema。')
  lines.push(`- WAL 与索引重建临时文件单独列出（分别为落库量的 ${45}% 与最大表体积的 60%），`)
  lines.push('  不并入「落库合计」：它们在导入完成后会被回收，但导入期间确实需要这块磁盘。')
  lines.push('- 未计入：Neo4j Bloom/APOC 等插件、备份副本、OBS 归档、Redis、第三方向量索引。')
  lines.push('- 未计入：core 侧权威库（`core_achievement` 及类型扩展表）。它由 `tests/perf/seed-core.sql` 造，量级与本模型不同，须单独估算。')
  return lines.join('\n') + '\n'
}

const gate = REQUIRED_STAGE
  ? (() => {
      const row = rows.find((r) => r.stage === REQUIRED_STAGE)
      const evaluated = gateFor(row)
      return {
        requiredScale: args['require-scale'],
        stage: REQUIRED_STAGE,
        host: {
          availGb,
          memGb,
          availSource: HOST_PROBE.availSource,
          memSource: HOST_PROBE.memSource,
          diskPath: HOST_PROBE.diskPath,
          cpuCount: HOST_PROBE.cpuCount
        },
        ...evaluated
      }
    })()
  : null

const summary = {
  generatedAt: new Date().toISOString(),
  profile: PROFILE_KEY,
  profileLabel: PROFILE.label,
  hostProbe: HOST_PROBE,
  gate,
  moments: {
    authorsPerWork: +moments.mean.toFixed(4),
    rawPairsPerWork: +moments.rawPairs.toFixed(4),
    qualifiedPairsPerWork: +moments.qualifiedPairs.toFixed(4),
    materializedPairsPerWork: +matMoments.pairs.toFixed(4),
    qualifiedAuthorsPerWork: +moments.qualifiedAuthors.toFixed(4)
  },
  host: { availGb, memGb, diskHeadroomGb, worksPerScholar, ...HOST_PROBE },
  stages: rows,
  calibration,
  fixtures: FIXTURES,
  sources: {
    measured: MEASURED_1M.source,
    neo4jBytes: NEO4J_BYTES.note,
    pgPerRow: PG_PER_ROW
  }
}

if (args.json) {
  mkdirSync(dirname(args.json), { recursive: true })
  writeFileSync(args.json, JSON.stringify(summary, null, 2))
  console.log(`JSON 已写入 ${args.json}`)
}
if (args.markdown) {
  mkdirSync(dirname(args.markdown), { recursive: true })
  writeFileSync(args.markdown, toMarkdown())
  console.log(`Markdown 已写入 ${args.markdown}`)
}

if (!args.json && !args.markdown) {
  console.log('=== 阶段六容量精算 ===')
  console.log(PROFILE.label)
  console.log(
    `主机读数：磁盘可用 ${availGb} GB（${HOST_PROBE.availSource}${HOST_PROBE.availSource === 'statfs' ? ` ${HOST_PROBE.diskPath}` : ''}）` +
      `，内存 ${memGb} GB（${HOST_PROBE.memSource}），CPU ${HOST_PROBE.cpuCount ?? '?'} 核，${HOST_PROBE.platform}`
  )
  console.log(
    `作者数/成果=${moments.mean.toFixed(2)}  合格合作边/成果=${moments.qualifiedPairs.toFixed(2)}  ` +
      `Neo4j 物化边/成果=${matMoments.pairs.toFixed(2)}`
  )
  console.log('')
  const head = ['阶段', '成果', '学者', '作者关系', 'PG边', 'Neo4j边']
  console.log(head[0].padEnd(6) + head.slice(1).map((h) => h.padStart(13)).join(''))
  for (const r of rows) {
    console.log(
      r.stage.padEnd(6) +
        [r.works, r.scholars, r.contributions, r.edges, r.neoCooperated]
          .map((v) => fmtInt(v).padStart(13))
          .join('')
    )
  }
  console.log('')
  const head2 = ['阶段', 'PG体积', 'Neo4j体积', '磁盘合计', '内存推荐', '内存最低', '判定']
  console.log(
    head2[0].padEnd(6) +
      head2[1].padStart(11) +
      head2[2].padStart(13) +
      head2[3].padStart(12) +
      head2[4].padStart(12) +
      head2[5].padStart(12) +
      '  ' +
      head2[6]
  )
  for (const r of rows) {
    console.log(
      r.stage.padEnd(6) +
        fmtGb(r.pg.planningGb).padStart(11) +
        fmtGb(r.neo4j.diskGb).padStart(13) +
        fmtGb(r.diskGb).padStart(12) +
        (r.memory.totalGb.toFixed(2) + ' GB').padStart(12) +
        (r.memory.minimalTotalGb.toFixed(2) + ' GB').padStart(12) +
        '  ' + r.verdict
    )
  }
  console.log('')
  console.log('=== 标定自检（对照 2026-10-17 的 100 万实测）===')
  for (const c of calibration.checks) {
    const p = Number.isInteger(c.predicted) ? fmtInt(c.predicted) : c.predicted.toFixed(2)
    const m = Number.isInteger(c.measured) ? fmtInt(c.measured) : c.measured.toFixed(2)
    console.log(`  ${c.ok ? 'OK  ' : 'FAIL'} ${c.what}: 模型 ${p} / 实测 ${m}（偏差 ${c.deviationPct}%）`)
  }
  console.log(`  自检结论：${calibration.ok ? '通过' : '超差，必须重测基线后再外推'}`)
}

// ---------------------------------------------------------------------------
// 8. 规模门禁输出（--require-scale）
//
// 输出格式刻意做成「一行结论 + 逐条原因」，CI 里 human 与 machine 都能读：
//   GATE: BLOCKED scale=5m disk_need=38.12GB avail=13.9GB mem_limit=14.40GB
// 门禁结论同时写入 summary.gate，自动化不必解析自然语言。
// ---------------------------------------------------------------------------
if (gate) {
  const status = gate.ok ? 'PASS' : 'BLOCKED'
  console.log('')
  console.log('=== 前置安全门禁（--require-scale）===')
  console.log(
    `GATE: ${status} scale=${gate.requiredScale} stage=${gate.stage} disk_need=${gate.diskNeedGb.toFixed(2)}GB ` +
      `avail=${gate.host.availGb}GB mem_limit=${gate.memLimitGb}GB mem_avail=${gate.host.memGb}GB mode=${gate.mode}`
  )
  for (const r of gate.reasons) console.log(`  阻断原因：${r}`)
  if (!gate.ok) {
    console.log('  处置：换更大磁盘/内存的 runner（--runner_label 指向自托管或云上大规格机器），')
    console.log('        或把 --scale 降到本机装得下的档位。**不要**用 --force 越线跑：那只会得到一份写满磁盘的半成品，')
    console.log('        而且它不能被引用为「已验证 500 万」。')
  }
}

// 退出码优先级：门禁（资源事实）> 标定自检（模型漂移）。两者都失败时先报门禁，
// 因为门禁是「跑不跑得动」，自检是「数字可不可引用」。
if (gate && !gate.ok) {
  process.exitCode = 4
} else {
  // 自检不通过时以非零退出，避免「模型漂移但仍被当作结论引用」。
  process.exitCode = calibration.ok ? 0 : 3
}
