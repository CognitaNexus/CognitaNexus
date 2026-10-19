// 检索页「勾选 → 批量导出引用」的浏览器级验证。
//
// 只跑纯函数测试是不够的：勾选框是否真的能和结果卡片对齐、点导出后预览面板是否出现、
// 换格式后旧预览是否作废、下载地址是否真的能取到文件——这些只有真实 DOM 才能回答。
//
// 关键是第 5 步：不去点下载按钮（无头环境下下载落盘路径各家不同），而是取出页面给出的
// href 直接 fetch。这样验证的是「页面上的下载入口指向的地址真的能返回那份正文」，
// 比断言按钮存在强得多。
//
// 运行（在 tests/ui 下）：node check-batch-citation.mjs
// 前置：整栈已启动（bash ops/local/stack.sh），前端 dev server 在 5173。
// 可覆盖：CN_CHROME（浏览器可执行文件）、CN_WEB（前端地址）、CN_SHOTS（截图目录）。
import { mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'

const CHROME = process.env.CN_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = process.env.CN_WEB || 'http://127.0.0.1:5173'
const OUT = process.env.CN_SHOTS || fileURLToPath(new URL('./.shots/', import.meta.url))

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok })
  console.log((ok ? '  PASS  ' : '  FAIL  ') + name + (detail ? '  -> ' + detail : ''))
}

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
})
const page = await browser.newPage()
await page.setViewport({ width: 1440, height: 1200 })

// 与 cn-shoot.mjs 同一份白名单：未登录时读会话的 401 与内部台账的 403 是设计行为，不是故障。
// 不加这层过滤，真回归会被这两条常驻噪声淹掉。
const EXPECTED = [/\/core\/api\/v1\/auth\/me$/, /\/core\/internal\/v1\//]
const isExpected = (text) => EXPECTED.some((re) => re.test(text))

const errors = []
page.on('console', (msg) => {
  if (msg.type() !== 'error') return
  const text = msg.text()
  // 控制台只给「Failed to load resource」，不带 URL；URL 由 response 钩子单独判。
  if (/Failed to load resource/.test(text)) return
  if (!isExpected(text)) errors.push('console: ' + text)
})
page.on('pageerror', (err) => errors.push('pageerror: ' + err.message))
page.on('requestfailed', (req) => {
  const text = req.url()
  if (!isExpected(text)) errors.push('requestfailed: ' + text)
})
page.on('response', (res) => {
  if (res.status() >= 400 && !isExpected(res.url())) errors.push(`http ${res.status()}: ${res.url()}`)
})

await page.goto(BASE + '/search', { waitUntil: 'networkidle2', timeout: 60000 })
await page.waitForSelector('.result-item', { timeout: 30000 })

const boxes = await page.$$('.result-item .pick input[type=checkbox]')
check('每条结果都带勾选框', boxes.length >= 2, boxes.length)

if (boxes.length >= 2) {
  await boxes[0].click()
  await boxes[1].click()
  await new Promise((r) => setTimeout(r, 200))
  const counter = await page.$eval('.panel-head', (el) => el.innerText)
  check('计数跟随勾选变为 2', counter.includes('已选 2 条'), counter.replace(/\s+/g, ' ').slice(0, 60))
}

// 点「导出引用」
const clicked = await page.$$eval('.panel-head button', (buttons) => {
  const target = buttons.find((b) => b.innerText.trim() === '导出引用')
  if (!target) return false
  target.click()
  return true
})
check('找到并点击「导出引用」', clicked)
await page.waitForFunction(
  () => {
    const pre = document.querySelector('pre.pre')
    return pre && pre.innerText.trim().length > 0
  },
  { timeout: 30000 },
)

const preview = await page.$eval('pre.pre', (el) => el.innerText)
// 首屏第一条可能是项目/专利类成果，那时条目类型是 @misc（只有论文用 @article），两种都算对。
check('预览出现 BibTeX 条目', /^@(article|misc)\{/.test(preview.trim()), preview.trim().slice(0, 24))
const entries = (preview.match(/^@/gm) || []).length
check('预览含两条条目', entries === 2, entries)

const summary = await page.$$eval('.panel-head', (heads) =>
  heads.map((h) => h.innerText).find((t) => t.includes('已导出')) ?? '',
)
check('结论文案给出导出条数', summary.includes('已导出 2 条引用'), summary.replace(/\s+/g, ' ').slice(0, 40))

const href = await page.$eval('.panel-body a.btn', (a) => a.getAttribute('href'))
check(
  '下载地址指向批量导出端点并带服务前缀',
  !!href && href.startsWith('/core/api/v1/public/citations/batch/download?'),
  href,
)

// 用页面给出的地址直接取，验证「页面上的下载入口」真的能返回那份正文
if (href) {
  const res = await fetch(BASE + href, { headers: { Accept: 'application/x-bibtex' } })
  const body = await res.text()
  check('下载地址返回 200', res.status === 200, res.status)
  check('下载正文与预览逐字一致', body.trim() === preview.trim(), `${body.length} vs ${preview.length}`)
  check('附件头给出 .bib 文件名',
    (res.headers.get('content-disposition') || '').includes('citations-bibtex-'),
    res.headers.get('content-disposition'))
}

// 换格式：旧预览必须作废，不能让人对着旧文本点下载
await page.select('.panel-head select', 'gb_t_7714')
await new Promise((r) => setTimeout(r, 300))
const staleGone = await page.evaluate(() => !document.querySelector('pre.pre'))
check('切换格式后旧预览立即作废', staleGone)

await page.$$eval('.panel-head button', (buttons) => {
  const target = buttons.find((b) => b.innerText.trim() === '导出引用')
  if (target) target.click()
})
await page.waitForFunction(
  () => {
    const pre = document.querySelector('pre.pre')
    return pre && pre.innerText.trim().startsWith('[1]')
  },
  { timeout: 30000 },
)
const gbPreview = await page.$eval('pre.pre', (el) => el.innerText)
check('国标预览逐条编号', gbPreview.trim().startsWith('[1] ') && gbPreview.includes('[2] '), gbPreview.slice(0, 20))

mkdirSync(OUT, { recursive: true })
await page.screenshot({ path: OUT + '/14-search-batch-citation.png', fullPage: true })

check('无控制台错误、无失败请求、无 4xx/5xx', errors.length === 0, errors.slice(0, 3))

const failed = results.filter((r) => !r.ok)
console.log(`\n${results.length} 项检查：${results.length - failed.length} 通过，${failed.length} 失败`)
if (failed.length) for (const item of failed) console.log('  FAILED: ' + item.name)

await browser.close()
process.exit(failed.length ? 1 : 0)
