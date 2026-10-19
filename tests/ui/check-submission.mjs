// 成果自助提交的浏览器级验证：作者填表提交 → 审核人裁决 → 作者看到结论。
//
// 为什么必须用浏览器：这条链路的每一个失败点都在「人看得见的地方」——
// 表单能不能提交、队列里能不能一眼看出该不该批、驳回没写原因时会不会被拦、
// 裁决后作者能否看到「通过」与可点开的成果。接口全绿而页面空白，是这类功能最常见的状况。
//
// 六条断言：
//   1. 作者能在页面上提交，并立刻在「我的提交」里看到「待审核」——不是提交完什么反馈都没有；
//   2. 提交件在审核前不会出现在「待审队列」之外的任何公开入口（检索页搜不到）；
//   3. 审核端驳回时不写原因会被前端拦住（错误提示可见），不会把一条无原因驳回发出去；
//   4. 写原因批准后，队列里的这一条消失；
//   5. 作者侧状态变成「已通过并建档」，并给出可点开的成果链接；
//   6. 全程无控制台异常。
//
// 前置：整栈已启动（含前端 dev server）。
// 副作用：创建一个自检账号、一条提交、一条成果（题名带随机后缀）。
// 用法（在 tests/ui 下）：node check-submission.mjs
// 可覆盖：CN_CHROME、CN_WEB、CN_ADMIN_USER、CN_ADMIN_PASS、CN_SHOTS。
import { mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'

const CHROME = process.env.CN_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = process.env.CN_WEB || 'http://127.0.0.1:5173'
const ADMIN_USER = process.env.CN_ADMIN_USER || 'cnadmin'
const ADMIN_PASS = process.env.CN_ADMIN_PASS || 'cn-admin-Dev-2026'
const OUT = process.env.CN_SHOTS || fileURLToPath(new URL('./.shots/', import.meta.url))

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok })
  console.log((ok ? '  PASS  ' : '  FAIL  ') + name + (detail ? '  -> ' + detail : ''))
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
})
const page = await browser.newPage()
await page.setViewport({ width: 1440, height: 1200 })

const EXPECTED = [/\/core\/api\/v1\/auth\/me$/, /\/core\/internal\/v1\//]
const isExpected = (text) => EXPECTED.some((re) => re.test(text))
const errors = []
page.on('console', (msg) => {
  if (msg.type() !== 'error') return
  if (/Failed to load resource/.test(msg.text())) return
  if (!isExpected(msg.text())) errors.push('console: ' + msg.text())
})
page.on('pageerror', (err) => errors.push('pageerror: ' + err.message))

mkdirSync(OUT, { recursive: true })

async function api(path, { method = 'GET', body } = {}) {
  return page.evaluate(
    async (path, method, body) => {
      let token = ''
      if (method !== 'GET') {
        await fetch('/core/api/v1/auth/csrf', { credentials: 'same-origin' })
        const match = document.cookie
          .split(';')
          .map((part) => part.trim())
          .find((part) => part.startsWith('XSRF-TOKEN='))
        token = match ? decodeURIComponent(match.slice('XSRF-TOKEN='.length)) : ''
      }
      const init = { method, credentials: 'same-origin', headers: { Accept: 'application/json' } }
      if (token) init.headers['X-XSRF-TOKEN'] = token
      if (body !== undefined) {
        init.headers['Content-Type'] = 'application/json'
        init.body = JSON.stringify(body)
      }
      const response = await fetch(path, init)
      const text = await response.text()
      let payload = {}
      try {
        payload = text ? JSON.parse(text) : {}
      } catch {
        payload = { raw: text.slice(0, 120) }
      }
      return { status: response.status, payload }
    },
    path,
    method,
    body,
  )
}

async function login(username, password) {
  await page.goto(BASE + '/login', { waitUntil: 'networkidle2', timeout: 60000 })
  await page.waitForSelector('#login-username', { timeout: 20000 })
  await page.$eval('#login-username', (el) => (el.value = ''))
  await page.$eval('#login-password', (el) => (el.value = ''))
  await page.type('#login-username', username)
  await page.type('#login-password', password)
  await Promise.all([
    page.waitForFunction(() => !location.pathname.startsWith('/login'), { timeout: 30000 }).catch(() => null),
    page.click('form button[type=submit]'),
  ])
  await sleep(400)
  return !page.url().includes('/login')
}

async function logout() {
  await page.evaluate(() => {
    const target = Array.from(document.querySelectorAll('button')).find(
      (b) => b.textContent.trim() === '退出',
    )
    if (target) target.click()
  })
  await sleep(600)
}

await page.goto(BASE + '/', { waitUntil: 'networkidle2', timeout: 60000 })

const stamp = Date.now().toString(36)
const username = 'uisub' + stamp
const password = 'UiSub-' + stamp + '-2026'
const title = '界面自检提交的论文 ' + stamp

const registered = await api('/core/api/v1/auth/register', {
  method: 'POST',
  body: { username, password, displayName: '提交自检 ' + stamp },
})
check('注册自检账号', registered.status === 200 || registered.status === 201, registered.status)

const loggedIn = await login(username, password)
check('自检账号可登录', loggedIn, page.url())

// ---- 1. 在页面上提交 ----
await page.goto(BASE + '/portal/submissions', { waitUntil: 'networkidle2', timeout: 60000 })
await page.waitForSelector('#sub-title', { timeout: 20000 })
await page.type('#sub-title', title)
await page.type('#sub-venue', '界面自检期刊')
await page.type('#sub-year', '2023')
const submitButton = await page.$('button.btn.primary')
await submitButton.click()
await page
  .waitForFunction((text) => document.body.innerText.includes(text), { timeout: 20000 }, '已提交，待审核')
  .catch(() => null)
const afterSubmit = await page.evaluate(() => document.body.innerText)
check('页面给出「已提交，待审核」的反馈', afterSubmit.includes('已提交，待审核'))
check('我的提交列表里出现该条并标为待审核', afterSubmit.includes(title) && afterSubmit.includes('待审核'))

// ---- 2. 审核前公开入口搜不到 ----
const publicSearch = await page.evaluate(async (keyword) => {
  const response = await fetch('/query/api/v1/public/search/achievements?q=' + encodeURIComponent(keyword) + '&size=5')
  const body = await response.json()
  return (body.data || {}).total ?? -1
}, title)
check('未审核的提交在公开检索里不出现', publicSearch === 0, publicSearch)

// ---- 3. 审核端：无原因驳回被拦 ----
await logout()
await login(ADMIN_USER, ADMIN_PASS)
await page.goto(BASE + '/governance/submissions', { waitUntil: 'networkidle2', timeout: 60000 })
await page.waitForSelector('h1', { timeout: 20000 })

const foundRow = await page
  .waitForFunction((text) => document.body.innerText.includes(text), { timeout: 20000 }, title)
  .then(() => true)
  .catch(() => false)
check('审核队列里能看到这条提交', foundRow)

/**
 * 只在「包含本次题名的那张卡片」里找按钮。
 *
 * 队列里会有多条待审记录（历史自检留下的、别人提交的），按整页第一个按钮去点，
 * 点到的一定是最旧那条——而且脚本还会「成功地」把别人的提交批掉。
 */
const panelIndex = await page.evaluate(
  (text) => Array.from(document.querySelectorAll('article.panel')).findIndex((p) => p.innerText.includes(text)),
  title,
)
check('队列里定位到本次提交的卡片', panelIndex >= 0, panelIndex)

async function clickInPanel(label) {
  return page.evaluate(
    (index, label) => {
      const panel = document.querySelectorAll('article.panel')[index]
      if (!panel) return false
      const target = Array.from(panel.querySelectorAll('button')).find(
        (b) => b.textContent.trim() === label,
      )
      if (!target) return false
      target.click()
      return true
    },
    panelIndex,
    label,
  )
}

async function typeInPanel(index, text) {
  const selector = `article.panel:nth-of-type(${index + 1}) textarea`
  const handle = await page.$(selector)
  if (!handle) return false
  await handle.type(text)
  return true
}

const clickedReject = await clickInPanel('驳回')
check('找到「驳回」按钮', clickedReject)
await sleep(500)
const afterReject = await page.evaluate(() => document.body.innerText)
check(
  '没写原因就驳回会被拦住并说明为什么',
  afterReject.includes('必须写明原因'),
  afterReject.includes('必须写明原因') ? '' : '未看到提示',
)

const stillInQueue = await page.evaluate((text) => document.body.innerText.includes(text), title)
check('被拦住的驳回没有把提交移出队列', stillInQueue)

// ---- 4. 写原因后批准 ----
const typedReason = await typeInPanel(panelIndex, '界面自检：材料齐全，批准建档')
check('在本次卡片里填写裁决说明', typedReason)
const clickedApprove = await clickInPanel('批准并建档')
check('找到「批准并建档」按钮', clickedApprove)
await page
  .waitForFunction(() => document.body.innerText.includes('已通过并建档'), { timeout: 20000 })
  .catch(() => null)
const afterApprove = await page.evaluate(() => document.body.innerText)
check('批准后给出建档结果', afterApprove.includes('已通过并建档'), afterApprove.includes('已通过并建档') ? '' : '未看到结果')

const gone = await page.evaluate((text) => !document.body.innerText.includes(text), title)
check('已批准的提交离开待审队列', gone)

// ---- 5. 作者侧看到结论与成果链接 ----
await logout()
await login(username, password)
await page.goto(BASE + '/portal/submissions', { waitUntil: 'networkidle2', timeout: 60000 })
await page.waitForSelector('table', { timeout: 20000 }).catch(() => null)
const authorView = await page.evaluate(() => document.body.innerText)
check('作者侧状态变为「已通过并建档」', authorView.includes('已通过并建档'))
const achievementHref = await page.$$eval('a', (as) =>
  as.map((a) => a.getAttribute('href') || '').filter((href) => href.startsWith('/achievements/')),
)
check('作者侧给出可点开的成果链接', achievementHref.length > 0, achievementHref[0])

if (achievementHref[0]) {
  await page.goto(BASE + achievementHref[0], { waitUntil: 'networkidle2', timeout: 60000 })
  await page.waitForSelector('h1', { timeout: 20000 })
  const detailText = await page.evaluate(() => document.body.innerText)
  check('打开成果详情能看到提交的题名', detailText.includes(title), detailText.slice(0, 60))
}

await page.screenshot({ path: OUT + 'submission.png', fullPage: false })

check('全程无控制台异常', errors.length === 0, errors.slice(0, 3).join(' | '))

await browser.close()

const failed = results.filter((r) => !r.ok)
console.log(`\n结果：${results.length - failed.length}/${results.length} 通过`)
if (failed.length) {
  console.log('失败项：\n' + failed.map((f) => '  - ' + f.name).join('\n'))
  process.exitCode = 1
}
