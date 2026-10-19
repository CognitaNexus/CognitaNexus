// 学者档案页「引用量」区块的浏览器级验证。
//
// 为什么要用浏览器验：这块内容最容易出的不是「接口错」，而是「页面把话说错」——
// 数字对得上、标题却叫「引用趋势」，读者会把它读成逐年增长；篇均的分母换成成果总数，
// 页面上仍然显示得像个正常数字。这些只有把渲染结果拿出来比对才能发现。
//
// 五条断言：
//   1. 页面上的四个数字与接口返回逐项相等（页面没有自己再算一遍）；
//   2. 柱子数量等于 byYear 的年份桶数（没画出来的年份不是被静默丢弃）；
//   3. 柱子的提示文本里带该年成果数与合计引用（只有总数无法判断「一篇高」还是「多篇低」）；
//   4. 说明文字写明「不是每年新增引用」与来源不可相加；
//   5. 无引用量数据的学者显示空态，不出现「0」当成篇均。
//
// 前置：整栈已启动（含前端 dev server）。
// 用法（在 tests/ui 下）：node check-citation-metrics.mjs
// 可覆盖：CN_CHROME、CN_WEB、CN_CORE、CN_PSQL。
import { execFileSync } from 'node:child_process'
import { mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'

const CHROME = process.env.CN_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = process.env.CN_WEB || 'http://127.0.0.1:5173'
const CORE = process.env.CN_CORE || 'http://127.0.0.1:8081'
const PSQL = process.env.CN_PSQL || 'D:/dev-tools/pgsql/bin/psql.exe'
const OUT = process.env.CN_SHOTS || fileURLToPath(new URL('./.shots/', import.meta.url))

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok })
  console.log((ok ? '  PASS  ' : '  FAIL  ') + name + (detail ? '  -> ' + detail : ''))
}

function psql(sql) {
  const out = execFileSync(
    PSQL,
    ['-h', '127.0.0.1', '-p', '5432', '-U', 'core_app', '-d', 'core_db', '-At', '-F', '\t', '-c', sql],
    { env: { ...process.env, PGPASSWORD: 'cn_core_app_dev', PGCLIENTENCODING: 'UTF8' }, encoding: 'utf8' },
  )
  return out.split('\n').map((line) => line.trim()).filter(Boolean)
}

const QUALIFIED = "c.relation_state IN ('AUTO','SELF_CONFIRMED')"
const PUBLIC_WORK =
  "a.visibility='PUBLIC' AND a.effective_status='EFFECTIVE' AND a.merged_into_id IS NULL"

mkdirSync(OUT, { recursive: true })

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
})
const page = await browser.newPage()
await page.setViewport({ width: 1440, height: 1200 })

const errors = []
page.on('console', (msg) => {
  if (msg.type() !== 'error') return
  if (/Failed to load resource/.test(msg.text())) return
  errors.push('console: ' + msg.text())
})
page.on('pageerror', (err) => errors.push('pageerror: ' + err.message))

/** 直接打 core：不走前端，拿到「服务端说的」作为比对基准。 */
async function api(path) {
  const response = await fetch(CORE + path, { headers: { Accept: 'application/json' } })
  return { status: response.status, body: await response.json() }
}

// ---- 1. 引用量最高的学者：数字与图 ----
const topRows = psql(`
  SELECT c.scholar_id
  FROM core_contribution c
  JOIN core_achievement a ON a.id=c.achievement_id
  LEFT JOIN core_paper p ON p.achievement_id=a.id
  WHERE ${QUALIFIED} AND ${PUBLIC_WORK}
  GROUP BY c.scholar_id
  ORDER BY coalesce(sum(p.citation_count),0) DESC
  LIMIT 1`)
const topId = topRows[0]

if (!topId) {
  console.log('库里没有样本，跳过')
  await browser.close()
  process.exit(0)
}

const detail = await api('/api/v1/public/scholars/' + topId)
const citations = detail.body?.data?.metrics?.citations ?? {}
check('接口返回引用量区块', Object.keys(citations).length > 0, Object.keys(citations).length)

await page.goto(BASE + '/scholars/' + topId, { waitUntil: 'networkidle2', timeout: 60000 })
await page.waitForSelector('h1', { timeout: 20000 })

const hasPanel = await page
  .waitForFunction(() => Array.from(document.querySelectorAll('h2')).some((h) => h.textContent.trim() === '引用量'), {
    timeout: 20000,
  })
  .then(() => true)
  .catch(() => false)
check('档案页出现「引用量」面板', hasPanel)

const pageText = await page.evaluate(() => document.body.innerText)
const kpi = await page.$$eval('.citation-kpi .kpi-value', (nodes) => nodes.map((n) => n.textContent.trim()))

check(
  '累计引用与接口一致',
  kpi[0] === String(citations.totalCitations),
  `${kpi[0]} vs ${citations.totalCitations}`,
)
check(
  '篇均引用与接口一致',
  kpi[1] === String(citations.averagePerWork),
  `${kpi[1]} vs ${citations.averagePerWork}`,
)
check(
  '单篇最高与接口一致',
  kpi[2] === String(citations.maxWorkCitations),
  `${kpi[2]} vs ${citations.maxWorkCitations}`,
)

const bars = await page.$$eval('.citation-trend svg rect', (nodes) =>
  nodes.map((n) => n.querySelector('title')?.textContent || ''),
)
// 每根柱子都有一条 title：年份桶数量必须与接口一致，缺年份不是被「抹掉」而是另有说明。
check('柱子数 = 接口年份桶数', bars.length === (citations.byYear || []).length, `${bars.length} vs ${(citations.byYear || []).length}`)

const firstBar = bars[0] || ''
check(
  '柱子提示含该年成果数与合计引用',
  /年发表 \d+ 项/.test(firstBar) && /合计 \d+ 次/.test(firstBar),
  firstBar,
)

check(
  '页面写明「不是每年新增引用」',
  pageText.includes('不是「每年新增引用」'),
  pageText.includes('不是「每年新增引用」') ? '' : '未找到口径说明',
)
check('页面写明跨来源不可直接相加', pageText.includes('跨来源求和只能作为量级参考'))
check('页面写明不提供 h-index', pageText.includes('h-index'))

// 标题必须是「按发表年份」这类说法，不能叫「引用趋势」——后者会被读成逐年增长。
check(
  '图例用「按发表年份」而不是「趋势」',
  pageText.includes('该年发表成果的当前累计引用量') && !pageText.includes('引用趋势'),
)

await page.screenshot({ path: OUT + 'citation-metrics.png', fullPage: false })

// ---- 2. 无引用量数据的学者：空态而不是 0 ----
const emptyId = psql(`
  SELECT c.scholar_id
  FROM core_contribution c
  JOIN core_achievement a ON a.id=c.achievement_id
  LEFT JOIN core_paper p ON p.achievement_id=a.id
  WHERE ${QUALIFIED} AND ${PUBLIC_WORK}
  GROUP BY c.scholar_id
  HAVING count(p.citation_count)=0
  LIMIT 1`)[0]

if (emptyId) {
  await page.goto(BASE + '/scholars/' + emptyId, { waitUntil: 'networkidle2', timeout: 60000 })
  await page.waitForSelector('h1', { timeout: 20000 })
  const emptyText = await page.evaluate(() => document.body.innerText)
  check(
    '无数据学者不画空坐标图、给出说明',
    emptyText.includes('暂无可用的引用量记录'),
    emptyText.includes('暂无可用的引用量记录') ? '' : '未找到空态文案',
  )
  const emptyKpi = await page.$$('.citation-kpi')
  check('无数据时不显示 KPI 数字（避免「0」被当成篇均）', emptyKpi.length === 0, emptyKpi.length)
} else {
  console.log('  SKIP  库中没有无引用量数据的学者样本')
}

check('全程无页面异常', errors.length === 0, errors.slice(0, 3).join(' | '))

await browser.close()

const failed = results.filter((r) => !r.ok)
console.log(`\n结果：${results.length - failed.length}/${results.length} 通过`)
if (failed.length) {
  console.log('失败项：\n' + failed.map((f) => '  - ' + f.name).join('\n'))
  process.exitCode = 1
}
