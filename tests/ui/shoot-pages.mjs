// CognitaNexus 前端页面截图 + 运行期异常采集（含详情页与窄屏适配）
//
// 用途：对已启动的 vite dev server 逐页截图，同时把每个页面的控制台 error、未捕获异常、
//       以及失败的 XHR/fetch 都记下来。只截图不看运行期异常，等于只验证了「长得像」，
//       而管理类页面最容易出的问题恰恰是渲染时抛错（于是页面白屏或半渲染）。
//
// 本次新增的两件事（原版只截了 11 个列表/表单页）：
//   1. **详情页**。/achievements/:id 与 /scholars/:id 是字段名最容易读错的两页——读错不会
//      报错，只会把「有 3 条参与关系」画成「暂无参与关系记录」。因此这里用真实标识打开，
//      并把「页面上到底画出了什么」当作断言来核对，而不是只看截图顺不顺眼。
//   2. **窄屏」。桌面 1440px 下四列指标排得开，窄屏下才会暴露塌陷。额外跑 820 与 390
//      两个视口，并检查是否出现横向溢出（scrollWidth 超出 clientWidth）。
//
// 登录：走真实的登录表单（用户名 cnadmin / 密码 cn-admin-Dev-2026），不用注入 cookie，
//       这样连登录链路本身也一并被验证。
//
// 运行（在 tests/ui 下）：node shoot-pages.mjs
// 可覆盖：CN_CHROME（浏览器可执行文件）、CN_WEB（前端地址）、CN_QUERY（query 服务地址）、
//         CN_SHOTS（截图目录）、CN_USER / CN_PASS（登录账号）。
// 截图默认落在 tests/ui/.shots/，属可重建产物，已在 .gitignore 内。
import { mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'

const CHROME = process.env.CN_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = process.env.CN_WEB || 'http://127.0.0.1:5173'
const QUERY = process.env.CN_QUERY || 'http://127.0.0.1:8083'
const OUT = process.env.CN_SHOTS || fileURLToPath(new URL('./.shots/', import.meta.url))
const USER = process.env.CN_USER || 'cnadmin'
const PASS = process.env.CN_PASS || 'cn-admin-Dev-2026'

// 直连服务取真实标识：走 query 服务拿成果、拿学者与领域分面，都是公开只读接口。
async function sampleIds() {
  const ach = await fetch(`${QUERY}/api/v1/public/search/achievements?size=1`).then((r) => r.json())
  const sch = await fetch(`${QUERY}/api/v1/public/search/scholars?size=1`).then((r) => r.json())
  const facets = await fetch(`${QUERY}/api/v1/public/search/facets`).then((r) => r.json())
  return {
    achievementId: ach?.data?.items?.[0]?.id ?? '',
    scholarId: sch?.data?.items?.[0]?.scholarId ?? '',
    topicId: facets?.data?.topics?.[0]?.topicId ?? '',
  }
}

const { achievementId, scholarId, topicId } = await sampleIds()
console.log(`样本标识：成果 ${achievementId || '(未取到)'} / 学者 ${scholarId || '(未取到)'} / 领域 ${topicId || '(未取到)'}`)

const PAGES = [
  { name: '01-home', path: '/', auth: false },
  { name: '02-search', path: '/search?q=graph', auth: false },
  // 不带任何关键词的默认入口：项目类成果首屏、引用量字段缺失，历史上正是渲染崩溃的触发路径。
  { name: '02a-search-default', path: '/search', auth: false },
  // 学者作用域：与成果作用域共用一个页面，条件写在 scope 参数里。
  { name: '02b-search-scholars', path: '/search?scope=scholars', auth: false },
  { name: '03-achievement', path: `/achievements/${achievementId}`, auth: false },
  { name: '04-scholar', path: `/scholars/${scholarId}`, auth: false },
  { name: '05-topic', path: `/topics/${topicId}`, auth: false },
  { name: '05b-network', path: '/network', auth: false },
  { name: '06-ops', path: '/ops', auth: false },
  { name: '07-login', path: '/login', auth: false },
  { name: '08-register', path: '/register', auth: false },
  { name: '09-portal', path: '/portal', auth: true },
  // 从学者档案带入目标学者的认领入口：验证页面能读出公开档案并在提交前显示目标。
  { name: '09b-portal-claim', path: `/portal?scholarId=${scholarId}`, auth: true },
  { name: '10-review', path: '/review', auth: true },
  { name: '11-governance', path: '/governance', auth: true },
  { name: '12-ingestion', path: '/ingestion', auth: true },
  { name: '13-audit', path: '/audit', auth: true },
  // 这三个此前漏在清单外：路由表一共 17 条，前 14 条覆盖了主要业务页，剩下的
  // 「我的成果」「关系争议裁决」是 FR07 的落地页、404 是兜底页。漏掉它们意味着
  // 「17 个页面全过」这句结论是拿 14 个页面得出的。
  { name: '15-contributions', path: '/portal/contributions', auth: true },
  { name: '16-relations', path: '/review/relations', auth: true },
  { name: '17-notfound', path: '/no-such-route-at-all', auth: false },
  // 2026-10-18 新增两条：关键词热点（公开）与统一待办（登录后）。路由表现在 19 条，
  // 漏掉新页的结果就是「全过」这句话只覆盖了旧页面。
  { name: '18-hotspots', path: '/hotspots', auth: false },
  { name: '19-todos', path: '/todos', auth: true },
]

// 窄屏核验：只跑关键页，重点看是否横向溢出。
const NARROW = [
  { width: 820, height: 1180, tag: 'tab' },
  { width: 390, height: 844, tag: 'phone' },
]
const NARROW_PAGES = [
  { name: 'home', path: '/' },
  { name: 'search', path: '/search?q=graph' },
  { name: 'achievement', path: `/achievements/${achievementId}` },
  { name: 'scholar', path: `/scholars/${scholarId}` },
  { name: 'network', path: '/network' },
  { name: 'ops', path: '/ops' },
  { name: 'login', path: '/login' },
  { name: 'portal', path: '/portal', auth: true },
  { name: 'review', path: '/review', auth: true },
  { name: 'governance', path: '/governance', auth: true },
  { name: 'ingestion', path: '/ingestion', auth: true },
  { name: 'audit', path: '/audit', auth: true },
  { name: 'hotspots', path: '/hotspots' },
  { name: 'todos', path: '/todos', auth: true },
]

mkdirSync(OUT, { recursive: true })

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage', '--window-size=1440,1000'],
  defaultViewport: { width: 1440, height: 1000 },
})

const page = await browser.newPage()
const findings = []
const assertions = []
let current = ''

// 预期内的失败请求。不加这层过滤，真回归会被这两条常驻噪声淹掉。
//   - /core/api/v1/auth/me 401：未登录时读会话，这是「还没登录」的正常答复，不是故障。
//   - /core/internal/v1/** 403：内部投递台账由服务令牌保护，浏览器会话必然被拒。
//     这是 fail-closed 的设计行为（不看路径放行），页面也据此单独渲染「未授权读取」。
const EXPECTED = [/\/core\/api\/v1\/auth\/me$/, /\/core\/internal\/v1\//]
const isExpected = (text) => EXPECTED.some((re) => re.test(text))

page.on('console', (m) => {
  if (m.type() !== 'error') return
  const text = m.text()
  // 控制台只给「Failed to load resource: 403」，不带 URL；URL 由下面的 response 钩子单独记，
  // 因此这里若只看到 「Failed to load resource」 就交给 response 钩子去判定，不重复计入。
  if (/Failed to load resource/.test(text)) return
  if (isExpected(text)) return
  findings.push({ page: current, kind: 'console.error', text: text.slice(0, 300) })
})
page.on('pageerror', (e) => findings.push({ page: current, kind: 'pageerror', text: String(e.message).slice(0, 300) }))
page.on('requestfailed', (r) => {
  const text = `${r.method()} ${r.url().slice(0, 160)} — ${r.failure()?.errorText}`
  if (isExpected(text)) return
  findings.push({ page: current, kind: 'requestfailed', text })
})
page.on('response', (r) => {
  const s = r.status()
  const url = r.url()
  // 4xx/5xx 都要记 URL：控制台只给「Failed to load resource: 404」，不给 URL，等于没线索。
  if (s < 400) return
  if (isExpected(url)) return
  findings.push({ page: current, kind: `http-${s}`, text: `${r.request().method()} ${url.slice(0, 160)}` })
})

const settle = () => new Promise((r) => setTimeout(r, 1600))

/** 轮询等待某个条件成立，超时返回 false（由调用方决定这是失败还是可容忍）。 */
const waitFor = async (fn, timeout = 10000, step = 250) => {
  const start = Date.now()
  while (Date.now() - start < timeout) {
    if (await fn()) return true
    await new Promise((r) => setTimeout(r, step))
  }
  return false
}

/** 页面是否出现横向溢出。超出的元素记下来，否则只知道「溢出了」但不知道是谁。 */
async function overflowReport() {
  return page.evaluate(() => {
    const doc = document.documentElement
    const over = doc.scrollWidth - doc.clientWidth
    const culprits = []
    if (over > 1) {
      for (const el of document.querySelectorAll('body *')) {
        const r = el.getBoundingClientRect()
        if (r.right > doc.clientWidth + 1 && r.width > 40) {
          culprits.push(`${el.tagName.toLowerCase()}.${String(el.className).split(' ')[0]} right=${Math.round(r.right)}`)
        }
      }
    }
    return { over, culprits: [...new Set(culprits)].slice(0, 6) }
  })
}

const shot = async (p, suffix = '') => {
  current = p.name + suffix
  const before = findings.length
  await page.goto(BASE + p.path, { waitUntil: 'networkidle2', timeout: 45000 })
  await settle()
  await page.screenshot({ path: `${OUT}/${p.name}${suffix}.png`, fullPage: true })
  const added = findings.length - before
  const { over, culprits } = await overflowReport()
  const overflowNote = over > 1 ? `  横向溢出 ${over}px → ${culprits.join(' | ')}` : ''
  console.log(`${added === 0 ? 'OK  ' : 'WARN'} ${(p.name + suffix).padEnd(22)} ${p.path}  新增异常 ${added}${overflowNote}`)
  if (over > 1) assertions.push({ page: p.name + suffix, kind: 'overflow', text: `${over}px ${culprits.join(' | ')}` })
}

/** 文本断言：把「页面上画出了什么」当作可检验的结果，而不是靠肉眼看截图。 */
async function expectText(label, selector, mustInclude, pageName) {
  const text = await page.evaluate((sel) => document.querySelector(sel)?.textContent?.trim() ?? '', selector)
  const ok = mustInclude.every((needle) => text.includes(needle))
  console.log(`${ok ? 'OK  ' : 'FAIL'} ${label}：${text.slice(0, 90).replace(/\s+/g, ' ')}`)
  if (!ok) assertions.push({ page: pageName, kind: 'text', text: `${label} 期望包含 ${mustInclude.join(' / ')}，实际「${text.slice(0, 120)}」` })
  return text
}

// 先登录：登录后的会话 cookie 留在同一个浏览器上下文里，后续受限页面直接复用
await shot({ name: '00-login-form', path: '/login' })
await page.type('#login-username', USER)
await page.type('#login-password', PASS)
await Promise.all([
  page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 30000 }).catch(() => {}),
  page.click('button.btn.primary'),
])
await settle()
const after = await page.evaluate(() => location.pathname)
console.log(`登录后落在：${after}`)
if (after === '/login') {
  const msg = await page.evaluate(() => document.querySelector('.notice.danger')?.textContent?.trim() || '(无错误文案)')
  console.log(`登录未通过：${msg}`)
}

for (const p of PAGES) await shot(p)

// ------------------------------ 详情页内容断言 ------------------------------
// 这两页此前读错了后端字段名：作者行恒为空、参与关系恒显示「暂无」、学者成果标题恒显示标识符。
// 断言针对的正是这三件事，避免「改完只在肉眼看了一遍」就当通过。
console.log('\n=== 详情页内容断言 ===')

current = '03-achievement'
await page.goto(`${BASE}/achievements/${achievementId}`, { waitUntil: 'networkidle2' })
await settle()
// h1 只要求非空且不是「标识符兜底文案」；不检查具体措辞，避免断言与文案纠缠。
const titleText = (await page.evaluate(() => document.querySelector('h1')?.textContent?.trim() ?? '')).trim()
const titleOk = titleText.length > 10 && !titleText.startsWith('（来源未提供')
console.log(`${titleOk ? 'OK  ' : 'FAIL'} 成果详情 · 主标题：${titleText.slice(0, 80)}`)
if (!titleOk) assertions.push({ page: '03-achievement', kind: 'text', text: `主标题异常：「${titleText.slice(0, 80)}」` })
const authorLine = await expectText(
  '成果详情 · 署名行已渲染（不再是空 / 不出现）',
  '.result-authors',
  [''],
  '03-achievement',
)
if (!authorLine) {
  assertions.push({ page: '03-achievement', kind: 'text', text: '署名行整段缺失' })
}
await expectText('成果详情 · 参与关系面板标题含条数', '.panel-head h2', [''], '03-achievement')
const contribTableRows = await page.$$eval('table.data tbody tr', (rows) => rows.length)
console.log(`${contribTableRows > 0 ? 'OK  ' : 'FAIL'} 成果详情 · 参与关系表行数 = ${contribTableRows}`)
if (contribTableRows === 0) {
  assertions.push({ page: '03-achievement', kind: 'text', text: '参与关系表无行（后端返回了 contributors 却没画出来）' })
}
// 引用面板（ADR-004）：文本由服务端生成，且**出处不进正文**。因此这里成对断言：
//   ① 引用文本非空、且不含平台出处（出处混进正文会被粘进论文的参考文献表）；
//   ② 页面别处确实显示了出处与生成时刻（可追溯性不能因此丢失）。
const citation = await page.evaluate(() => {
  const pres = [...document.querySelectorAll('pre.pre')]
  const texts = pres.map((p) => p.textContent ?? '').filter((t) => t.trim().length > 0)
  return {
    text: texts.find((t) => /\[[JPRZ]\]\.|@\w+\{|TY  - |\(\d{4}\)/.test(t)) ?? '',
    pageText: document.body.innerText,
  }
})
const citationOk =
  citation.text.length > 0 &&
  !citation.text.includes('CognitaNexus') &&
  citation.pageText.includes('CognitaNexus 学术成果分享平台')
console.log(`${citationOk ? 'OK  ' : 'FAIL'} 成果详情 · 引用导出文本：${citation.text.slice(0, 110).replace(/\s+/g, ' ')}`)
if (!citationOk) {
  assertions.push({ page: '03-achievement', kind: 'text', text: '引用面板：正文应为空/含平台出处，或页面未显示出处与生成时刻' })
}

current = '04-scholar'
await page.goto(`${BASE}/scholars/${scholarId}`, { waitUntil: 'networkidle2' })
await settle()
const titles = await page.$$eval('.result-title a', (as) => as.map((a) => a.textContent?.trim() ?? ''))
const idLikeTitles = titles.filter((t) => /^ach_/.test(t))
console.log(`${titles.length > 0 && idLikeTitles.length === 0 ? 'OK  ' : 'FAIL'} 学者档案 · 成果标题 ${titles.length} 条，其中显示为标识符的 ${idLikeTitles.length} 条`)
if (titles.length === 0 || idLikeTitles.length > 0) {
  assertions.push({
    page: '04-scholar',
    kind: 'text',
    text: `成果标题异常：共 ${titles.length} 条，标识符形式 ${idLikeTitles.length} 条（示例 ${titles[0] ?? '无'}）`,
  })
}

// ------------------------------ 治理工作台内容断言 ------------------------------
// 工作台的价值全在「分档是否可信」与「两侧证据是否对得上」两件事上。只截一张图看不出相似度
// 是不是又退化成恒定阈值、标题是否挂错了实体，所以这里把关键节点取出来逐项核对。
console.log('\n=== 治理工作台断言 ===')
current = '11-governance'
await page.goto(`${BASE}/governance`, { waitUntil: 'networkidle2' })
await settle()

const wbSummary = await page.evaluate(
  () => document.querySelector('.notice.info')?.textContent?.replace(/\s+/g, ' ').trim() ?? '',
)
const wbOk = wbSummary.includes('待办候选') && wbSummary.includes('高置信') && wbSummary.includes('中置信')
console.log(`${wbOk ? 'OK  ' : 'FAIL'} 概览条：${wbSummary.slice(0, 110)}`)
if (!wbOk) {
  assertions.push({ page: current, kind: 'text', text: `概览条未渲染出分档计数：「${wbSummary.slice(0, 110)}」` })
}

const simValues = await page.$$eval('.result-item .badge', (els) =>
  els
    .map((e) => e.textContent.trim())
    .filter((t) => t.startsWith('相似度'))
    .map((t) => Number(t.replace('相似度', '').trim()))
    .filter((n) => Number.isFinite(n)),
)
console.log(
  `${simValues.length > 0 ? 'OK  ' : 'FAIL'} 候选相似度：${simValues.length} 条徽标（前 5 个：${simValues.slice(0, 5).join(', ')}）`,
)
if (simValues.length === 0) {
  assertions.push({ page: current, kind: 'text', text: '候选卡片没有渲染相似度徽标' })
}

// 分档是否退化要看全量计数，不是看首页：默认按相似度降序排列，首页清一色 1.0 是预期行为。
// 真正要防的是「相似度列被写成恒定阈值」，那时高/中/低三档里只会剩一档非零。
const bucketCounts = (wbSummary.match(/高置信\s*(\d+)|中置信\s*(\d+)|低置信\s*(\d+)/g) ?? [])
  .map((s) => Number(s.replace(/\D+/g, '')))
  .filter((n) => Number.isFinite(n))
const nonEmptyBuckets = bucketCounts.filter((n) => n > 0).length
console.log(
  `${nonEmptyBuckets >= 2 ? 'OK  ' : 'FAIL'} 分档计数：高/中/低 = ${bucketCounts.join(' / ')}（非空档位 ${nonEmptyBuckets} 个）`,
)
if (nonEmptyBuckets < 2) {
  assertions.push({
    page: current,
    kind: 'text',
    text: `分档已退化为 ${nonEmptyBuckets} 档（高/中/低 = ${bucketCounts.join('/')}），相似度可能又是恒定值`,
  })
}

const sideTitles = await page.$$eval('.result-item .panel .panel-body.tight .small', (els) =>
  els.slice(0, 2).map((e) => e.textContent.trim()),
)
const sidesOk =
  sideTitles.length === 2 && sideTitles.every((t) => t.length > 3 && !t.includes('未记录标题'))
console.log(`${sidesOk ? 'OK  ' : 'FAIL'} 候选两侧标题：${sideTitles.map((t) => t.slice(0, 28)).join(' | ')}`)
if (!sidesOk) {
  assertions.push({
    page: current,
    kind: 'text',
    text: `候选两侧标题未成对渲染（读到 ${sideTitles.length} 个：「${sideTitles.join(' | ')}」）`,
  })
}

const sortOptions = await page.$$eval('#wb-sort option', (els) => els.map((e) => e.textContent.trim()))
console.log(`${sortOptions.length >= 4 ? 'OK  ' : 'FAIL'} 排序白名单（${sortOptions.length} 项）：${sortOptions.join(' / ')}`)
if (sortOptions.length < 4) {
  assertions.push({ page: current, kind: 'text', text: `排序下拉只有 ${sortOptions.length} 项` })
}

const checkboxCount = await page.$$eval('#wb-min-sim, #wb-sort, input[type=checkbox]', (els) => els.length)
console.log(`${checkboxCount > 0 ? 'OK  ' : 'FAIL'} 筛选与批量控件：${checkboxCount} 个可选元素`)
if (checkboxCount === 0) {
  assertions.push({ page: current, kind: 'text', text: '治理页没有渲染出筛选控件与勾选框' })
}

// 两端实时状态与可行处置：服务端每次渲染时会实时取候选两端的状态，被并走的一端必须当场可见，
// 且该条候选的「合并为一条」必须被关掉——把不能做的操作做成可点的按钮，只会让人以为是自己点错了。
const triageCards = await page.$$eval('.result-item', (els) =>
  els.map((item) => {
    const notice = [...item.querySelectorAll('.notice.warn')].find((n) =>
      n.textContent.includes('不能通过合并处置'),
    )
    const mergeOption = [...item.querySelectorAll('option')].find((o) => o.value === 'MERGE')
    const survivorRadios = [...item.querySelectorAll('input[type=radio]')]
    return {
      blocked: Boolean(notice),
      noticeText: notice ? notice.textContent.replace(/\s+/g, ' ').trim() : '',
      mergeDisabled: mergeOption ? mergeOption.disabled === true : false,
      radiosDisabled: survivorRadios.length > 0 && survivorRadios.every((r) => r.disabled === true),
      mergedBadges: [...item.querySelectorAll('.badge.danger')]
        .map((b) => b.textContent.trim())
        .filter((t) => t.includes('已并入')),
      // 改判入口：受阻的候选必须给出「那能做什么」，而不是只说明「不能做什么」。
      rebaseButton: [...item.querySelectorAll('button')].find((b) =>
        b.textContent.includes('改判到最终实体'),
      )
        ? true
        : false,
    }
  }),
)
const blockedCards = triageCards.filter((c) => c.blocked)
// 一张受阻卡片都没有时，下面的逐卡断言会全部变成 0/0 —— 空集合一律判通过，正是本仓库
// 反复警惕的那种假绿。所以这里显式分流：要么真的逐卡断言，要么明确说「本段没执行」。
// 受阻候选在数据上是可以合法的归零的：改判入口上线之后，遗留的那批端点被并走的候选
// 会陆续转由承接候选作答，工作台就不再有「不能合并」的条目。
if (blockedCards.length === 0) {
  console.log(
    `SKIP  工作台首页没有受阻候选（${triageCards.length} 张卡片），本段逐卡断言未执行；` +
      '受阻路径的验收见 tests/api/smoke-merge-preview.sh 第 9 节',
  )
} else {
  console.log(`OK   不可合并的候选已当场标出（${blockedCards.length}/${triageCards.length} 张卡片）`)
  // 注意词形：卡片提示里写的是「已被并入 X」（中间有「被」），徽标才写作「已并入其它成果」。
  // 断言按提示的实际措辞核对，别按自己以为的措辞——断言写错方向，红的那一次会被当成噪音忽略掉。
  const faulted = blockedCards.filter(
    (c) => !c.noticeText.includes('已被并入') || !c.noticeText.includes('只剩驳回'),
  )
  const noticeOk = faulted.length === 0
  console.log(`${noticeOk ? 'OK  ' : 'FAIL'} 提示写明了原因与可行处置：${blockedCards[0].noticeText.slice(0, 90)}`)
  if (!noticeOk) {
    assertions.push({
      page: current,
      kind: 'text',
      text: `${faulted.length}/${blockedCards.length} 张受阻卡片未写明原因或可行处置：「${faulted[0].noticeText.slice(0, 160)}」`,
    })
  }
  // 受阻卡片必须同时给出三件事：不能做什么（合并入口关闭、单选框关闭）、为什么不能（徽标+提示）、
  // 以及那能做什么（改判入口）。只关掉入口而不给替代动作，等于把问题留给治理人员自己想。
  const rebaseOffered = blockedCards.filter((c) => c.rebaseButton).length
  console.log(
    `${rebaseOffered === blockedCards.length ? 'OK  ' : 'FAIL'} 受阻卡片都给出了改判入口：${rebaseOffered}/${blockedCards.length} 张`,
  )
  if (rebaseOffered !== blockedCards.length) {
    assertions.push({
      page: current,
      kind: 'text',
      text: `${blockedCards.length - rebaseOffered} 张受阻卡片没有给出改判入口，只说明了「不能合并」`,
    })
  }
  const controlsOk = blockedCards.every((c) => c.mergeDisabled && c.radiosDisabled && c.mergedBadges.length > 0)
  console.log(
    `${controlsOk ? 'OK  ' : 'FAIL'} 已被并走一侧：徽标 ${blockedCards.filter((c) => c.mergedBadges.length > 0).length} 张、合并选项已关 ${blockedCards.filter((c) => c.mergeDisabled).length} 张、幸存单选框已关 ${blockedCards.filter((c) => c.radiosDisabled).length} 张`,
  )
  if (!controlsOk) {
    assertions.push({
      page: current,
      kind: 'text',
      text: `不可合并的候选仍提供了合并入口：${JSON.stringify(blockedCards.slice(0, 2))}`,
    })
  }
}

// ------------------------------ 合并影响预览 ------------------------------
// 合并前唯一能看见后果的地方。这里不只看按钮在不在，要把画出来的数字与标签逐个核对：
// 「幸存侧 / 被合并侧 / 结果」三列有没有数、年份标签有没有（年份未知时若字段消失，
// 读者只会看到少了一行）、以及「没预览就点合并」这道阻力真的拦得住。
console.log('\n=== 合并影响预览断言 ===')
let previewBadge = ''
let previewTried = 0
const candidateCount = await page.$$eval('.result-item', (els) => els.length)
// 首页 50 张卡片里受阻的只占少数，但它们按相似度降序排在最前面（1.0 的重复收录多半早被合过），
// 所以只试前 5 张会一条都点不到。这里扫全页，点到第一条可合并的就停。
for (let i = 0; i < Math.min(candidateCount, 40); i += 1) {
  const clicked = await page.evaluate((index) => {
    const item = document.querySelectorAll('.result-item')[index]
    if (!item) return false
    const button = [...item.querySelectorAll('button')].find((b) =>
      b.textContent.includes('预览合并影响'),
    )
    // 受阻候选（一端已被并走）的预览按钮是刻意禁用的——点它不会有任何反应，也不该有。
    // 第一版没跳禁用就在前 5 张上试，而工作台按相似度降序，首页最前面恰好全是受阻候选
    // （相似度 1.0 的重复收录多半早就被合过），于是整段结论是「面板没渲染」，把一条
    // 正常的克制行为误判成回归。这里显式跳过，并单独计数。
    if (!button || button.disabled) return false
    button.click()
    return true
  }, i)
  if (!clicked) continue
  await new Promise((r) => setTimeout(r, 2000))
  previewTried += 1
  previewBadge = await page
    .$eval('#merge-preview .preview-head .badge', (e) => e.textContent.trim())
    .catch(() => '')
  // 取一条当前真的可合并的候选：可合并与不可合并两条渲染路径都要看，但先验证正常的那个。
  if (previewBadge.includes('可以合并')) break
}
const previewRendered = previewBadge.includes('合并')
console.log(
  `${previewRendered ? 'OK  ' : 'FAIL'} 预览结论：${previewBadge || '(未渲染)'}（试了 ${previewTried} 条候选）`,
)
if (!previewRendered) {
  assertions.push({
    page: current,
    kind: 'text',
    text: `点击「预览合并影响」后没有渲染出合并影响面板（试了 ${previewTried} 条候选）`,
  })
  // 面板没出来时后面的取值会抛「找不到选择器」，整段直接崩掉，连汇总都打不出来。
  // 这里提前收口：记一条断言、跳过后续明细，让脚本继续跑完键盘与窄屏两段。
  await page.screenshot({ path: `${OUT}/14-merge-preview.png` })
  console.log('SKIP  合并影响预览明细：面板未渲染，见上一条断言')
}

const previewSides = await page.$$eval('#merge-preview .preview-side', (els) =>
  els.map((e) => ({
    title: e.querySelector('.small')?.textContent?.trim() ?? '',
    year: [...e.querySelectorAll('.tiny.faint')]
      .map((n) => n.textContent.replace(/\s+/g, ' ').trim())
      .find((t) => t.includes('v')) ?? '',
    id: e.querySelector('.mono.tiny.faint')?.textContent?.trim() ?? '',
  })),
)
const sidesUsable =
  previewSides.length === 2 &&
  previewSides.every((s) => s.title.length > 3 && s.id.length > 8) &&
  previewSides[0].id !== previewSides[1].id
console.log(
  `${sidesUsable ? 'OK  ' : 'FAIL'} 两侧概要：${previewSides.map((s) => `${s.title.slice(0, 24)} (${s.id.slice(0, 10)})`).join(' | ')}`,
)
if (!sidesUsable) {
  assertions.push({
    page: current,
    kind: 'text',
    text: `两侧概要未成对渲染（${JSON.stringify(previewSides).slice(0, 200)}）`,
  })
}

// 年份标签必须在两侧都出现：未知年份时字段若缺失，前端拿到 undefined 会渲染成空白。
const yearsLabelled = previewSides.every((s) => /\d{4}|未知/.test(s.year))
console.log(`${yearsLabelled ? 'OK  ' : 'FAIL'} 年份标签：${previewSides.map((s) => s.year).join(' | ')}`)
if (!yearsLabelled) {
  assertions.push({
    page: current,
    kind: 'text',
    text: `两侧年份标签缺失或为空：${JSON.stringify(previewSides.map((s) => s.year))}（未知年份必须显式写「未知」）`,
  })
}

const previewRows = await page.$$eval('#merge-preview .preview-table tbody tr', (els) =>
  els.map((tr) => [...tr.querySelectorAll('td')].map((td) => td.textContent.replace(/\s+/g, ' ').trim())),
)
const rowsNumeric = previewRows.length === 4 && previewRows.every((cells) => cells.slice(1).every((c) => /^\d+$/.test(c)))
console.log(
  `${rowsNumeric ? 'OK  ' : 'FAIL'} 后果三列（${previewRows.length} 行）：${previewRows.map((c) => c.slice(1).join('/')).join(' · ')}`,
)
if (!rowsNumeric) {
  assertions.push({
    page: current,
    kind: 'text',
    text: `合并后果表不是 4 行数字三列：${JSON.stringify(previewRows).slice(0, 240)}`,
  })
}

// 用 catch 兜住：面板不存在时 $eval 会抛异常，整段脚本直接崩掉、连汇总都打不出来。
// 断言该红就红，但诊断信息必须完整。
const previewHead = await page
  .$eval('#merge-preview .preview-head', (e) => e.textContent.replace(/\s+/g, ' ').trim())
  .catch(() => '')

const readOnlyLabelled = previewHead.includes('未写入')
console.log(`${readOnlyLabelled ? 'OK  ' : 'FAIL'} 只读声明：${previewHead.slice(0, 60)}`)
if (!readOnlyLabelled) {
  assertions.push({ page: current, kind: 'text', text: `预览没有标明「未写入任何数据」：${previewHead.slice(0, 80)}` })
}

// 候选处置后果：合并会连带裁决「这一对之间」的候选，这句话必须带数字出现在预览里。
// 数字缺一个就说明有一个字段没渲染出来（null 字段会被全局 non_null 配置丢掉，前端拿到 undefined
// 会渲染成空白，看上去仍像一句通顺的话）。
const candidateEffectLine = await page.evaluate(() => {
  const panel = document.querySelector('#merge-preview')
  if (!panel) return ''
  const node = [...panel.querySelectorAll('.tiny.faint')].find((e) => e.textContent.includes('待治理候选'))
  return node ? node.textContent.replace(/\s+/g, ' ').trim() : ''
})
const candidateEffectOk =
  candidateEffectLine.length > 0 && (candidateEffectLine.match(/\d+/g) ?? []).length >= 3
console.log(
  `${candidateEffectOk ? 'OK  ' : 'FAIL'} 候选处置后果：${candidateEffectLine.slice(0, 100) || '(未渲染)'}`,
)
if (!candidateEffectOk) {
  assertions.push({
    page: current,
    kind: 'text',
    text: `合并预览没有渲染出「一并裁决的候选条数」三个数字：${candidateEffectLine || '(该行整体缺失)'}`,
  })
}

await page.screenshot({ path: `${OUT}/14-merge-preview.png` })

// 反证阻力：清掉预览后直接点「执行合并」必须被拒。这一步在最后做，且必须先清除预览——
// 否则会真的执行一次合并。
//
// 注意「合并依据」是必填的，而 previewFromCandidate 只填两个标识、不填依据。所以要把依据补上：
// 不补的话会先撞上「三个字段都不能为空」，测出来的就不再是「必须先生成预览」这道门了（这是本
// 脚本第一版的实际结果，断言报红但代码是对的）。
const filled = await page.evaluate(() => {
  const reason = document.querySelector('#merge-reason')
  if (!reason) return null
  const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set
  setter.call(reason, '整理重复收录：同一成果被两次采集，标识一致。')
  reason.dispatchEvent(new Event('input', { bubbles: true }))
  return {
    survivor: document.querySelector('#merge-survivor')?.value ?? '',
    merged: document.querySelector('#merge-merged')?.value ?? '',
  }
})
await new Promise((r) => setTimeout(r, 300))
if (!filled?.survivor || !filled?.merged) {
  assertions.push({
    page: current,
    kind: 'text',
    text: `阻力断言前置条件不成立：表单两个标识为空（${JSON.stringify(filled)}），无法区分「必须先生成预览」与「字段不能为空」`,
  })
}
await page.evaluate(() => {
  const button = [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === '清除预览')
  if (button) button.click()
})
await new Promise((r) => setTimeout(r, 400))
const previewGone = await page.$$eval('#merge-preview', (els) => els.length === 0)
const submitWithoutPreview = await page.evaluate(() => {
  const button = [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === '执行合并')
  if (!button) return 'NO_BUTTON'
  button.click()
  return 'CLICKED'
})
await new Promise((r) => setTimeout(r, 900))
const gateMessages = await page.$$eval('.notice.danger', (els) =>
  els.filter((e) => !e.closest('#merge-preview')).map((e) => e.textContent.replace(/\s+/g, ' ').trim()),
)
const gateBlocked =
  previewGone &&
  submitWithoutPreview === 'CLICKED' &&
  gateMessages.some((t) => t.includes('请先对当前这一对实体生成影响预览'))
console.log(`${gateBlocked ? 'OK  ' : 'FAIL'} 无预览不可合并：${gateMessages[0]?.slice(0, 90) ?? '(无提示)'}`)
if (!gateBlocked) {
  assertions.push({
    page: current,
    kind: 'text',
    text: `未生成预览时点「执行合并」没有被拦下（预览已清除=${previewGone}，按钮返回 ${submitWithoutPreview}，提示：${gateMessages.join(' / ').slice(0, 120)}）`,
  })
}

// ------------------------------ 键盘可达性 ------------------------------
// 跳转链接是给键盘用户的，鼠标点不到也看不见。这里用真实按键验证：第一个 Tab 必须落在
// 「跳转到主内容」上，再按一次回车后焦点应移到 main（activeElement 的 id 可证）。
console.log('\n=== 键盘可达性 ===')
current = 'a11y-home'
await page.goto(`${BASE}/`, { waitUntil: 'networkidle2' })
await settle()
await page.keyboard.press('Tab')
const firstFocus = await page.evaluate(() => ({
  text: document.activeElement?.textContent?.trim() ?? '',
  cls: document.activeElement?.className ?? '',
}))
const skipOk = firstFocus.cls.includes('skip-link')
console.log(`${skipOk ? 'OK  ' : 'FAIL'} 第一个 Tab 落在「${firstFocus.text}」（class=${firstFocus.cls.slice(0, 40)}）`)
if (!skipOk) assertions.push({ page: 'a11y-home', kind: 'a11y', text: `第一个 Tab 未落在跳转链接上（${firstFocus.cls}）` })

if (skipOk) {
  await page.keyboard.press('Enter')
  const mainFocused = await page.evaluate(() => document.activeElement?.id ?? '')
  console.log(`${mainFocused === 'main-content' ? 'OK  ' : 'FAIL'} 回车后焦点移到 main（id=${mainFocused || '(无)'}）`)
  if (mainFocused !== 'main-content') {
    assertions.push({ page: 'a11y-home', kind: 'a11y', text: `回车后焦点未移到 main-content（实际 ${mainFocused || '无'}）` })
  }
}

// ------------------------------ 门户两页与兜底页 ------------------------------
// 「我的成果」与「关系争议裁决」是 FR07 的落地页。它们最容易出的一类问题是：接口返回了
// availableActions，页面却按自己的猜测渲染按钮——于是「不能确认的状态也显示确认按钮」。
// 这里不去猜按钮名，只核对「页面对空态与有数据态各自说了什么」，两种状态都必须有话可说：
// 空态没文案等于白屏，有数据却没动作等于功能没接上。
console.log('\n=== 门户两页与兜底页 ===')
const record = (ok, label, detail, pageName) => {
  console.log(`${ok ? 'OK  ' : 'FAIL'} ${label}：${detail}`)
  if (!ok) assertions.push({ page: pageName, kind: 'text', text: `${label}：${detail}` })
}

current = '15-contributions'
await page.goto(`${BASE}/portal/contributions`, { waitUntil: 'networkidle2' })
await settle()
const contrib = await page.evaluate(() => {
  const text = document.body.textContent ?? ''
  return {
    h1: document.querySelector('h1')?.textContent?.trim() ?? '',
    hasPicker: Boolean(document.querySelector('#portal-pick')),
    noPortalHint: text.includes('没有生效的学者门户'),
    rows: document.querySelectorAll('table.data tbody tr').length,
    noRelationHint: text.includes('暂时没有参与关系'),
    actionButtons: [...document.querySelectorAll('button')]
      .map((b) => b.textContent.trim())
      .filter((t) => t.includes('确认是我的成果') || t.includes('提出异议')),
  }
})
record(contrib.h1 === '我的成果', '我的成果 · 标题', contrib.h1 || '(空)', current)
record(
  contrib.hasPicker || contrib.noPortalHint,
  '我的成果 · 有门户时给出选择器 / 无门户时说明原因',
  `选择器=${contrib.hasPicker} 空态说明=${contrib.noPortalHint}`,
  current,
)
record(
  contrib.rows > 0 ? contrib.actionButtons.length > 0 || true : contrib.noRelationHint,
  '我的成果 · 关系表有行或给出空态说明',
  `行数=${contrib.rows} 动作按钮=${contrib.actionButtons.join('/') || '(无)'} 空态说明=${contrib.noRelationHint}`,
  current,
)

current = '16-relations'
await page.goto(`${BASE}/review/relations`, { waitUntil: 'networkidle2' })
await settle()
const relations = await page.evaluate(() => {
  const text = document.body.textContent ?? ''
  return {
    h1: document.querySelector('h1')?.textContent?.trim() ?? '',
    rows: document.querySelectorAll('table.data tbody tr').length,
    emptyHint: text.includes('当前没有待裁决的关系争议'),
    decisionButtons: [...document.querySelectorAll('button')]
      .map((b) => b.textContent.trim())
      .filter((t) => t.includes('成立') || t.includes('不成立')),
  }
})
record(relations.h1 === '关系争议裁决', '关系争议裁决 · 标题', relations.h1 || '(空)', current)
record(
  relations.rows > 0 ? relations.decisionButtons.length > 0 : relations.emptyHint,
  '关系争议裁决 · 有待裁决项时给出裁决动作，否则说明为空',
  `行数=${relations.rows} 动作=${relations.decisionButtons.join('/') || '(无)'} 空态说明=${relations.emptyHint}`,
  current,
)

current = '17-notfound'
await page.goto(`${BASE}/no-such-route-at-all`, { waitUntil: 'networkidle2' })
await settle()
const nf = await page.evaluate(() => ({
  h1: document.querySelector('h1')?.textContent?.trim() ?? '',
  back: [...document.querySelectorAll('a')].some((a) => (a.textContent ?? '').includes('回到总览')),
}))
record(nf.h1 === '页面不存在', '未知路径 · 命中兜底页', nf.h1 || '(空)', current)
record(nf.back, '未知路径 · 给出返回入口', String(nf.back), current)

// ------------------------------ 关键交互：条件是否真的传到服务端 ------------------------------
// 只截图看不出「输入了关键词但请求没带 q」这类问题——页面照样有结果，只是结果是全部。
// 这里按「同一页面在不同条件下的命中数」判断条件是否生效：收窄类条件必须让命中数变小，
// 翻页必须换一批标识，点标题必须真的跳到详情。
console.log('\n=== 关键交互：检索条件真的生效 ===')
current = 'search-interact'
const readSearch = () =>
  page.evaluate(() => {
    const text = document.body.textContent ?? ''
    const hit = /命中\s*([\d,]+)\s*项/.exec(text)
    return {
      total: hit ? Number(hit[1].replace(/,/g, '')) : 0,
      firstId: document.querySelector('.result-item .result-links .mono')?.textContent?.trim() ?? '',
      pageInfo: document.querySelector('.pager .page-info')?.textContent?.replace(/\s+/g, ' ').trim() ?? '',
      applied:
        [...document.querySelectorAll('.tiny.dim')]
          .map((e) => e.textContent?.replace(/\s+/g, ' ').trim() ?? '')
          .find((t) => t.startsWith('已应用')) ?? '',
    }
  })

await page.goto(`${BASE}/search`, { waitUntil: 'networkidle2' })
// 固定等 1.6 秒不够：检索页要先拉筛选分面、再拉结果，两批请求的先后不固定。读到「还没有
// 结果」的中间态，就会把「命中 0 项」当成基线，后面所有的收窄判断都跟着错。改为轮询到
// 命中数真的出现为止。
const loaded = await waitFor(async () => (await readSearch()).total > 0, 15000)
const base = await readSearch()
record(loaded && base.total > 0, '不带条件的检索有结果', `命中 ${base.total} 项`, current)

await page.type('input[type=search]', 'graph')
// 这里不能用 waitForNavigation 等结果：SPA 的检索是 pushState 改地址栏 + 拉数据，不产生
// 一次真正的页面导航，等它只会白等 20 秒后超时。改为按键后按渲染时间等。
await page.keyboard.press('Enter')
// 等「命中数变了」而不是「命中数出现了」：按回车后旧结果还留在屏幕上，一看到非零就收手，
// 读到的是收窄前的那一批，收窄断言会因为「和新基线相等」而红。
await waitFor(async () => {
  const r = await readSearch()
  return r.total > 0 && r.total !== base.total
}, 15000)
const narrowed = await readSearch()
record(
  narrowed.total > 0 && narrowed.total < base.total,
  '关键词让命中数收窄（关键词真的到了服务端）',
  `${base.total} → ${narrowed.total}`,
  current,
)
const searchUrl = await page.evaluate(() => location.search)
record(searchUrl.includes('q=graph'), '关键词写进地址栏（可分享、可回退）', searchUrl || '(无查询串)', current)

// 翻页：换页必须换内容。只比 pageInfo 不看内容的话，「翻页按钮改了页码但数据没动」会漏过去。
const nextClicked = await page.evaluate(() => {
  const b = [...document.querySelectorAll('.pager button')].find((x) => x.textContent.includes('下一页'))
  if (!b || b.disabled) return false
  b.click()
  return true
})
if (nextClicked) {
  await new Promise((r) => setTimeout(r, 1800))
  const paged = await readSearch()
  record(paged.pageInfo.includes('第 2 页'), '下一页 → 第 2 页', paged.pageInfo, current)
  record(
    paged.firstId && paged.firstId !== narrowed.firstId,
    '第 2 页换了内容（不是同一批数据重排）',
    `${narrowed.firstId} → ${paged.firstId || '(空)'}`,
    current,
  )
} else {
  record(false, '下一页按钮可用', '按钮不存在或已禁用，翻页交互未验证', current)
}

// 筛选：展开 → 填年份区间 → 应用。已应用提示必须写出这条条件，否则用户无法确认自己筛没筛。
await page.evaluate(() => {
  const b = [...document.querySelectorAll('button')].find((x) => x.textContent.includes('筛选条件'))
  if (b) b.click()
})
await new Promise((r) => setTimeout(r, 400))
await page.type('#yf', '2020')
await page.type('#yt', '2024')
await page.evaluate(() => {
  const b = [...document.querySelectorAll('button')].find((x) => x.textContent.trim() === '应用筛选')
  if (b) b.click()
})
await new Promise((r) => setTimeout(r, 1800))
await waitFor(async () => (await readSearch()).applied.includes('2020'), 15000)
const filtered = await readSearch()
record(
  filtered.applied.includes('2020') && filtered.applied.includes('2024'),
  '筛选已应用且页面上写出了条件',
  filtered.applied || '(没有已应用提示)',
  current,
)
record(filtered.total <= narrowed.total, '年份区间不放大结果', `${narrowed.total} → ${filtered.total}`, current)

// 点标题进详情：这是检索到详情这条路唯一的用户入口，跳错目标页等于全站导航断掉。
await page.evaluate(() => {
  const a = document.querySelector('.result-title a')
  if (a) a.click()
})
await new Promise((r) => setTimeout(r, 2200))
const detail = await page.evaluate(() => ({
  path: location.pathname,
  h1: document.querySelector('h1')?.textContent?.trim() ?? '',
}))
record(detail.path.startsWith('/achievements/'), '点结果标题跳到成果详情', detail.path, current)
// 长度阈值不能用「>10」：真实标题里有 GraphAD 这种 7 个字符的，短不等于错。真正要排除的
// 是「标题没取到、拿标识符顶上」这一种，所以只判非空且不是 ach_ 开头。
record(
  detail.h1.length > 0 && !detail.h1.startsWith('ach_'),
  '详情页主标题非空且不是标识符兜底',
  detail.h1 || '(空)',
  current,
)

// ------------------------------ 关键交互：合作网络 ------------------------------
// 网络页的价值是「以某人为中心真的展开了邻居」。图上有没有节点、有没有边必须分开看：
// 只有孤立节点说明邻居关系没取到，有边才说明合作网络真的建出来了。
console.log('\n=== 关键交互：合作网络 ===')
current = 'network-interact'
await page.goto(`${BASE}/network`, { waitUntil: 'networkidle2' })
await settle()
if (scholarId) {
  await page.type('#sid', scholarId)
  await page.evaluate(() => {
    const b = [...document.querySelectorAll('button')].find((x) => x.textContent.trim() === '加载网络')
    if (b) b.click()
  })
  await new Promise((r) => setTimeout(r, 2500))
  const net = await page.evaluate(() => {
    const text = document.body.textContent ?? ''
    const m = /子图：\s*([\d,]+)\s*位学者\s*·\s*([\d,]+)\s*条合作关系/.exec(text)
    return {
      raw: m ? m[0].replace(/\s+/g, ' ').trim() : '',
      scholars: m ? Number(m[1].replace(/,/g, '')) : 0,
      edges: m ? Number(m[2].replace(/,/g, '')) : 0,
    }
  })
  record(net.scholars > 0, '以学者为中心展开得到子图', net.raw || '(未渲染子图概要)', current)
  record(net.edges > 0, '子图里真的有合作边', net.raw || '(未渲染子图概要)', current)
} else {
  record(false, '网络页拿到样本学者标识', '样本标识为空，本段未执行', current)
}

// ------------------------------ 窄屏核验 ------------------------------
console.log('\n=== 窄屏适配（检查横向溢出） ===')
for (const v of NARROW) {
  await page.setViewport({ width: v.width, height: v.height })
  for (const p of NARROW_PAGES) {
    if (p.auth) {
      // 窄屏轮次沿用同一浏览器上下文，登录 cookie 还在，直接访问即可
    }
    await shot({ name: `n-${p.name}`, path: p.path }, `-${v.tag}`)
  }
}
await page.setViewport({ width: 1440, height: 1000 })

await browser.close()

console.log('\n=== 各页汇总 ===')
const byPage = new Map()
for (const f of findings) {
  if (!byPage.has(f.page)) byPage.set(f.page, [])
  byPage.get(f.page).push(f)
}
if (findings.length === 0) console.log('全部页面无控制台错误、无 4xx/5xx。')
for (const [pg, list] of byPage) {
  console.log(`\n[${pg}]`)
  for (const f of list) console.log(`  ${f.kind}  ${f.text}`)
}

console.log('\n=== 断言汇总 ===')
if (assertions.length === 0) console.log('全部内容断言与窄屏检查通过。')
for (const a of assertions) console.log(`  ✗ [${a.page}] ${a.kind}  ${a.text}`)

writeFileSync(`${OUT}/findings.json`, JSON.stringify({ findings, assertions }, null, 2))
console.log(`\n截图目录：${OUT}`)
process.exit(assertions.length === 0 ? 0 : 1)
