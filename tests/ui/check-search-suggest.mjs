// 检索范围扩展（摘要 / 作者名）与输入建议的浏览器级验证。
//
// 纯函数测试回答不了的四件事，这里必须用真实 DOM 回答：
//   1. 建议下拉是否真的挂到了输入框下面（而不是渲染在页面别处）；
//   2. 键盘能不能选中建议、回车是否真的走「选中项」而不是「照原样检索」；
//   3. 「按作者名搜成果」在页面上是否真的返回结果——这条以前是 0 条；
//   4. 只在摘要里出现的词，点进详情后摘要里是否真的有它。
//
// 断言用的数字全部来自真实接口，不写死：先取接口 total，再比对页面文案。
// 接口改了口径时页面也会跟着失败，不会出现「两边一起错」。
//
// 运行（在 tests/ui 下）：node check-search-suggest.mjs
// 前置：整栈已启动（bash ops/local/stack.sh），前端 dev server 在 5173。
// 可覆盖：CN_CHROME、CN_WEB、CN_QUERY（查询服务直连地址）、CN_SHOTS。
import { mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'

const CHROME = process.env.CN_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = process.env.CN_WEB || 'http://127.0.0.1:5173'
const QUERY = process.env.CN_QUERY || 'http://127.0.0.1:8083'
const OUT = process.env.CN_SHOTS || fileURLToPath(new URL('./.shots/', import.meta.url))

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok })
  console.log((ok ? '  PASS  ' : '  FAIL  ') + name + (detail ? '  -> ' + detail : ''))
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function apiSearch(keyword, size = 1, sort = 'relevance') {
  const url = `${QUERY}/api/v1/public/search/achievements?q=${encodeURIComponent(keyword)}&size=${size}&sort=${sort}`
  return (await fetch(url).then((r) => r.json())).data
}

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
})
const page = await browser.newPage()
await page.setViewport({ width: 1440, height: 1000 })

// 与其它脚本同一份白名单：未登录时读会话的 401、内部台账的 403 是设计行为。
const EXPECTED = [/\/core\/api\/v1\/auth\/me$/, /\/core\/internal\/v1\//]
const isExpected = (text) => EXPECTED.some((re) => re.test(text))

const errors = []
page.on('console', (msg) => {
  if (msg.type() !== 'error') return
  const text = msg.text()
  if (/Failed to load resource/.test(text)) return
  if (!isExpected(text)) errors.push('console: ' + text)
})
page.on('pageerror', (err) => errors.push('pageerror: ' + err.message))
page.on('requestfailed', (req) => {
  if (!isExpected(req.url())) errors.push('requestfailed: ' + req.url())
})
page.on('response', (res) => {
  if (res.status() >= 400 && !isExpected(res.url())) errors.push(`http ${res.status()}: ${res.url()}`)
})

mkdirSync(OUT, { recursive: true })

// ---- 1. 顶栏建议 ----
await page.goto(BASE + '/', { waitUntil: 'networkidle2', timeout: 60000 })

check(
  '顶栏检索框声明为 combobox 且初始收起',
  (await page.$eval('.topbar .suggest input', (el) => el.getAttribute('aria-expanded'))) === 'false',
)

await page.click('.topbar .suggest input')
await page.type('.topbar .suggest input', 'graph', { delay: 40 })
await page.waitForSelector('.topbar .suggest-list', { timeout: 15000 })
const topItems = await page.$$eval('.topbar .suggest-item', (nodes) =>
  nodes.map((n) => ({
    label: n.querySelector('.suggest-label')?.textContent?.trim() ?? '',
    meta: n.querySelector('.suggest-meta')?.textContent?.trim() ?? '',
    scholar: n.classList.contains('scholar'),
  })),
)
check('输入 graph 后出现建议列表', topItems.length > 0, topItems.length + ' 条')
check(
  '首条建议是前缀命中（不是只在中间出现 graph）',
  (topItems[0]?.label ?? '').toLowerCase().startsWith('graph'),
  topItems[0]?.label,
)
check(
  '建议项带年份与类型元信息',
  /\d{4}|年份未知/.test(topItems[0]?.meta ?? '') && (topItems[0]?.meta ?? '').includes('·'),
  topItems[0]?.meta,
)

// ---- 2. Esc 收起后再点输入框还能看到建议 ----
await page.keyboard.press('Escape')
check('Esc 收起建议', (await page.$('.topbar .suggest-list')) === null)
await page.click('.topbar .suggest input')
const reopened = await page
  .waitForSelector('.topbar .suggest-list', { timeout: 8000 })
  .then(() => true)
  .catch(() => false)
check('收起后点输入框能重新展开（输入框仍带焦点，不会再触发 focus）', reopened)

// ---- 3. 键盘选择并跳转 ----
await page.keyboard.press('ArrowDown')
const activeLabel = await page
  .$eval('.topbar .suggest-item.active .suggest-label', (el) => el.textContent.trim())
  .catch(() => '')
check('ArrowDown 高亮首条建议', activeLabel.length > 0, activeLabel.slice(0, 40))

await page.keyboard.press('Enter')
const enteredDetail = await page
  .waitForFunction(() => /\/achievements\//.test(location.pathname), { timeout: 20000 })
  .then(() => true)
  .catch(() => false)
check('回车进入成果详情（而不是按原关键词检索）', enteredDetail, page.url())

await page.waitForFunction(() => document.querySelector('h1')?.textContent?.trim().length > 0, {
  timeout: 20000,
})
const detailTitle = await page.$eval('h1', (el) => el.textContent.trim())
check(
  '详情标题就是被选中的那条建议',
  detailTitle.includes(activeLabel.slice(0, 16)),
  detailTitle.slice(0, 48),
)
await page.screenshot({ path: OUT + 'suggest-detail.png' })

// ---- 4. 学者建议 ----
await page.goto(BASE + '/', { waitUntil: 'networkidle2' })
await page.click('.topbar .suggest input')
await page.type('.topbar .suggest input', 'kuai', { delay: 40 })
await page.waitForSelector('.topbar .suggest-item.scholar', { timeout: 15000 })
const scholarItems = await page.$$eval('.topbar .suggest-item.scholar', (nodes) =>
  nodes.map((n) => ({
    label: n.querySelector('.suggest-label')?.textContent?.trim() ?? '',
    meta: n.querySelector('.suggest-meta')?.textContent?.trim() ?? '',
  })),
)
check('作者名输入能给出学者建议', scholarItems.length > 0, scholarItems.map((s) => s.label).join(' / '))
check(
  '学者建议标注公开成果数（不是总量）',
  /学者 · 公开成果 \d+ 篇/.test(scholarItems[0]?.meta ?? ''),
  scholarItems[0]?.meta,
)

const firstScholar = scholarItems[0]?.label ?? ''
await page.click('.topbar .suggest-item.scholar')
const enteredScholar = await page
  .waitForFunction(() => /\/scholars\//.test(location.pathname), { timeout: 20000 })
  .then(() => true)
  .catch(() => false)
check('点击学者建议进入学者档案', enteredScholar, page.url())
if (enteredScholar) {
  await page.waitForFunction(() => document.querySelector('h1')?.textContent?.trim().length > 0, {
    timeout: 20000,
  })
  const scholarHeading = await page.$eval('h1', (el) => el.textContent.trim())
  check('档案页标题就是该学者', scholarHeading.includes(firstScholar), scholarHeading.slice(0, 40))
}

// ---- 5. 作者名检索成果（本次扩展的核心） ----
const authorData = await apiSearch('kuai')
check('接口：作者名 kuai 能命中成果', authorData.total > 0, `${authorData.total} 条`)

await page.goto(BASE + '/search?q=kuai', { waitUntil: 'networkidle2', timeout: 60000 })
await page.waitForSelector('.result-item', { timeout: 30000 })
const heading = await page.$eval('.panel-head h2', (el) => el.textContent.trim()).catch(() => '')
check(
  '页面命中数与接口一致',
  heading.replace(/[^\d]/g, '') === String(authorData.total),
  `${heading} / 接口 ${authorData.total}`,
)

const titles = await page.$$eval('.result-item .result-title', (nodes) =>
  nodes.map((n) => n.textContent.trim()),
)
check(
  '命中的成果题名里不含关键词（证明走的是作者名而不是题名）',
  titles.length > 0 && titles.every((t) => !t.toLowerCase().includes('kuai')),
  titles[0]?.slice(0, 40),
)
await page.screenshot({ path: OUT + 'search-author-hit.png' })

// ---- 6. 摘要命中：题名里没有该词，摘要里有 ----
const absData = await apiSearch('transformer', 100, 'citations_desc')
const abstractOnly = absData.items.find((item) => !item.title.toLowerCase().includes('transformer'))
check('接口：存在「题名不含 transformer 但被检索命中」的成果', !!abstractOnly, `${absData.total} 条命中`)

if (abstractOnly) {
  await page.goto(BASE + '/achievements/' + abstractOnly.id, { waitUntil: 'networkidle2', timeout: 60000 })
  await page.waitForFunction(() => document.querySelector('h1')?.textContent?.trim().length > 0, {
    timeout: 20000,
  })
  const absTitle = await page.$eval('h1', (el) => el.textContent.trim())
  const bodyText = await page.$eval('main', (el) => el.innerText).catch(() => '')
  check('详情页题名确实不含该关键词', !absTitle.toLowerCase().includes('transformer'), absTitle.slice(0, 48))
  check(
    '详情页正文里能读到该关键词（摘要命中不是误报）',
    bodyText.toLowerCase().includes('transformer'),
    `正文 ${bodyText.length} 字`,
  )
}

// ---- 7. 建议不是检索的前置条件 ----
await page.goto(BASE + '/search?q=graph', { waitUntil: 'networkidle2' })
await page.waitForSelector('.result-item', { timeout: 30000 })
await page.click('form.btn-row .suggest input')
await page.keyboard.press('Escape')
await page.keyboard.press('Enter')
await sleep(800)
check('关闭建议后直接回车仍能检索（建议不是前置条件）', /q=graph/.test(page.url()), page.url())

check('全程无控制台错误、无 4xx/5xx', errors.length === 0, errors.slice(0, 3).join(' | '))

await browser.close()

const failed = results.filter((r) => !r.ok)
console.log(`\n结果：${results.length - failed.length}/${results.length} 通过`)
if (failed.length) {
  console.log('失败项：\n' + failed.map((f) => '  - ' + f.name).join('\n'))
  process.exitCode = 1
}
