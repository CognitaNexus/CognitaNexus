// 引用网络面板的浏览器级验证。
//
// 为什么必须用浏览器验：这块内容最容易出的不是接口错，而是「页面把话说错」——
// 0 条被引可以显示成「没有被引用」，也可以显示成「本站只覆盖了 1% 的成果」，两者数字相同、
// 含义相反。接口把 coverage 与 platform 一起返回，就是为了让页面没法回避覆盖率这句话；
// 这条约束只有把渲染结果读出来才能确认。
//
// 六条断言：
//   1. 四个 KPI 与接口返回逐项相等（页面没有自己再算一遍）；
//   2. 「本篇引用」列表条数等于接口 references.count，且每条都能点进详情页；
//   3. 页面写明平台覆盖率（已采集篇数 / 可见论文数），不把 0 条读成「没有被引用」；
//   4. 未采集的成果显示「未采集」而不是「0 篇」；
//   5. 采集台显示已采集/待补/覆盖率，并能看到补水入口；
//   6. 整页无 console 报错、无 pageerror。
//
// 前置：整栈已启动（含前端 dev server），且已补过参考文献（/governance/citations）。
// 用法（在 tests/ui 下）：node check-citation-network.mjs
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
const ADMIN_USER = process.env.ADMIN_USER || 'cnadmin'
const ADMIN_PASS = process.env.ADMIN_PASS || 'cn-admin-Dev-2026'

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok })
  console.log((ok ? '  PASS  ' : '  FAIL  ') + name + (detail ? '  -> ' + detail : ''))
}

function psql(sql) {
  const out = execFileSync(
    PSQL,
    ['-h', '127.0.0.1', '-p', '5432', '-U', 'core_app', '-d', 'core_db', '-At', '-F', '\t', '-c', sql],
    {
      env: { ...process.env, PGPASSWORD: 'cn_core_app_dev', PGCLIENTENCODING: 'UTF8' },
      encoding: 'utf8',
    },
  )
  return out.split('\n').map((line) => line.trim()).filter(Boolean)
}

const PUBLIC_TARGET =
  "a.visibility='PUBLIC' AND a.effective_status='EFFECTIVE' AND a.merged_into_id IS NULL"

mkdirSync(OUT, { recursive: true })

// 样例：站内边最多的那一篇（保证页面上有东西可数）
const sample = psql(
  "SELECT o.achievement_id FROM core_citation_observation o" +
    ' JOIN core_citation_reference r ON r.citing_achievement_id = o.achievement_id' +
    "  AND r.resolution='RESOLVED' AND r.cited_achievement_id <> r.citing_achievement_id" +
    " WHERE o.outcome='OBSERVED' GROUP BY o.achievement_id ORDER BY count(*) DESC LIMIT 1;",
)[0]
// 样例：未采集的一篇（验证「未采集」不等于「0 篇」）
const unobserved = psql(
  'SELECT a.id FROM core_achievement a JOIN core_paper p ON p.achievement_id = a.id' +
    ' LEFT JOIN core_citation_observation o ON o.achievement_id = a.id' +
    ' WHERE o.achievement_id IS NULL AND p.doi_norm IS NOT NULL AND ' +
    PUBLIC_TARGET +
    ' LIMIT 1;',
)[0]

if (!sample) {
  console.log('库里没有站内引用边：请先触发一次补水（/governance/citations）')
  process.exit(1)
}

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
})
const page = await browser.newPage()
await page.setViewport({ width: 1440, height: 1400 })

const errors = []
page.on('console', (msg) => {
  if (msg.type() !== 'error') return
  if (/Failed to load resource/.test(msg.text())) return
  errors.push('console: ' + msg.text())
})
page.on('pageerror', (err) => errors.push('pageerror: ' + err.message))

async function api(path) {
  const response = await fetch(CORE + path, { headers: { Accept: 'application/json' } })
  return { status: response.status, body: await response.json() }
}

/** 取「引用网络」那个 panel 的文本，避免整页文本里别的数字混进来。 */
async function networkPanel() {
  return page.evaluate(() => {
    const panels = Array.from(document.querySelectorAll('.panel'))
    const panel = panels.find((p) => (p.querySelector('h2')?.textContent || '').includes('引用网络'))
    if (!panel) return null
    const kpis = Array.from(panel.querySelectorAll('.kpi')).map((k) => ({
      value: (k.querySelector('.kpi-value')?.textContent || '').trim(),
      label: (k.querySelector('.kpi-label')?.textContent || '').trim(),
    }))
    const sections = Array.from(panel.querySelectorAll('section')).map((s) => ({
      title: (s.querySelector('h3')?.textContent || '').trim(),
      items: Array.from(s.querySelectorAll('li a')).map((a) => ({
        text: (a.textContent || '').trim(),
        href: a.getAttribute('href') || '',
      })),
    }))
    return { text: panel.innerText, kpis, sections }
  })
}

// ---------------------------------------------------------------------------
// 1. 已采集成果的引用网络面板
// ---------------------------------------------------------------------------
const detail = await api(`/api/v1/public/achievements/${sample}/citation-network`)
const view = detail.body.data || {}
await page.goto(`${BASE}/achievements/${sample}`, { waitUntil: 'networkidle2' })
await page.waitForFunction(
  () =>
    Array.from(document.querySelectorAll('.panel h2')).some((h) =>
      (h.textContent || '').includes('引用网络'),
    ),
  { timeout: 20000 },
)
await page.screenshot({ path: OUT + 'citation-network.png', fullPage: true })

const panel = await networkPanel()
check('页面出现引用网络面板', !!panel)
if (!panel) {
  await browser.close()
  process.exit(1)
}

const kpiValue = (labelPart) => {
  const hit = panel.kpis.find((k) => k.label.includes(labelPart))
  return hit ? Number(hit.value) : NaN
}
check(
  'KPI「来源给出的条目」与接口一致',
  kpiValue('来源给出的参考文献条目') === (view.coverage?.referencesListed ?? 0),
  panel.kpis.map((k) => k.value).join('/'),
)
check(
  'KPI「收录于本平台」与接口一致',
  kpiValue('其中收录于本平台') === view.references.count,
  `${kpiValue('其中收录于本平台')} vs ${view.references.count}`,
)
check(
  'KPI「本平台内引用本篇」与接口一致',
  kpiValue('本平台内引用本篇') === view.citedBy.count,
  `${kpiValue('本平台内引用本篇')} vs ${view.citedBy.count}`,
)
check(
  'KPI「已采集成果」与接口一致',
  kpiValue('全平台已采集参考文献的成果') === view.platform.observedWorks,
  `${kpiValue('全平台已采集参考文献的成果')} vs ${view.platform.observedWorks}`,
)

const refSection = panel.sections.find((s) => s.title.includes('本篇引用'))
const citSection = panel.sections.find((s) => s.title.includes('被引'))
check(
  '「本篇引用」列表条数等于接口 count',
  refSection && refSection.items.length === view.references.count,
  `${refSection?.items.length} vs ${view.references.count}`,
)
check(
  '「被引」列表条数等于接口 count',
  citSection && citSection.items.length === view.citedBy.count,
  `${citSection?.items.length} vs ${view.citedBy.count}`,
)
check(
  '列表项都能点进详情页',
  (refSection?.items || []).every((item) => item.href.startsWith('/achievements/')),
  (refSection?.items || [])[0]?.href,
)

check(
  '页面写明平台覆盖率（已采集/可见论文）',
  panel.text.includes(String(view.platform.publicPapers)) &&
    /覆盖\s*[\d.]+%|已采集参考文献/.test(panel.text),
  panel.text.replace(/\s+/g, ' ').slice(0, 120),
)
check(
  '「被引」说明写明只统计已采集成果',
  panel.text.includes('只统计本平台已采集参考文献的成果'),
)
check(
  '页面不把 0 条被引说成「没有被引用」',
  !/没有被任何人引用|无人引用|暂无引用/.test(panel.text),
)

// ---------------------------------------------------------------------------
// 2. 未采集成果：说「未采集」，不说「0 篇」
// ---------------------------------------------------------------------------
if (unobserved) {
  await page.goto(`${BASE}/achievements/${unobserved}`, { waitUntil: 'networkidle2' })
  await page.waitForFunction(
    () =>
      Array.from(document.querySelectorAll('.panel h2')).some((h) =>
        (h.textContent || '').includes('引用网络'),
      ),
    { timeout: 20000 },
  )
  const empty = await networkPanel()
  check('未采集面板出现', !!empty)
  check(
    '未采集：写明「尚未采集」且说明不等于没有引用',
    !!empty && empty.text.includes('尚未采集') && empty.text.includes('不表示本篇没有引用任何文献'),
    empty?.text.replace(/\s+/g, ' ').slice(0, 140),
  )
  await page.screenshot({ path: OUT + 'citation-network-empty.png', fullPage: true })
} else {
  console.log('  SKIP  没有未采集的可见论文，跳过空态断言')
}

// ---------------------------------------------------------------------------
// 3. 采集台（需要管理员会话：它是运维入口，匿名访问会被路由守卫挡回登录页）
// ---------------------------------------------------------------------------
await page.goto(BASE + '/login', { waitUntil: 'networkidle2', timeout: 60000 })
await page.waitForSelector('#login-username', { timeout: 20000 })
await page.$eval('#login-username', (el) => (el.value = ''))
await page.$eval('#login-password', (el) => (el.value = ''))
await page.type('#login-username', ADMIN_USER)
await page.type('#login-password', ADMIN_PASS)
await Promise.all([
  page.waitForFunction(() => !location.pathname.startsWith('/login'), { timeout: 30000 }).catch(() => null),
  page.click('form button[type=submit]'),
])
check('管理员登录成功（采集台需要）', !page.url().includes('/login'), page.url())

await page.goto(`${BASE}/governance/citations`, { waitUntil: 'networkidle2' })
await page.waitForFunction(
  () => document.body.innerText.includes('引用网络采集'),
  { timeout: 20000 },
)
const opsText = await page.evaluate(() => document.body.innerText)
await page.screenshot({ path: OUT + 'citation-ops.png', fullPage: true })
check('采集台显示已采集与待补', opsText.includes('已采集参考文献的成果') && opsText.includes('待补成果'))
check('采集台给出覆盖率', /覆盖率/.test(opsText))
check('采集台有补水入口与来源选择', opsText.includes('开始补水') && opsText.includes('Crossref'))

check('无 console 报错、无 pageerror', errors.length === 0, errors.slice(0, 2).join(' | '))

await browser.close()

const failed = results.filter((r) => !r.ok).length
console.log(`\n合计 ${results.length} 项，失败 ${failed} 项`)
process.exit(failed === 0 ? 0 : 1)
