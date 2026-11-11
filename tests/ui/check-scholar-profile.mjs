// 学者门户「资料自助维护」的浏览器级验证。
//
// 这条链路只有真实环境能验：认领 → 授权 → 编辑 → 公开页可见。用假数据跑单测可以覆盖校验规则，
// 但回答不了三个问题：
//   1. 只有该门户的管理者才看得到编辑区（而不是所有登录用户都看得到）；
//   2. 保存后公开页真的变了（写成功但读不到，等于没生效）；
//   3. 头像上传走的是浏览器的 multipart，而不是被前端悄悄转成 JSON。
//
// 前置：整栈已启动，且管理员账号可用（默认 cnadmin / cn-admin-Dev-2026）。
// 副作用：会新建一个自检账号、认领一位未认领的学者并写入简介/主页/头像/置顶。
// 运行（在 tests/ui 下）：node check-scholar-profile.mjs
// 可覆盖：CN_CHROME、CN_WEB、CN_QUERY、CN_ADMIN_USER、CN_ADMIN_PASS、CN_SHOTS。
import { mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'

const CHROME = process.env.CN_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = process.env.CN_WEB || 'http://127.0.0.1:5173'
const QUERY = process.env.CN_QUERY || 'http://127.0.0.1:8083'
const ADMIN_USER = process.env.CN_ADMIN_USER || 'cnadmin'
const ADMIN_PASS = process.env.CN_ADMIN_PASS || 'cn-admin-Dev-2026'
const OUT = process.env.CN_SHOTS || fileURLToPath(new URL('./.shots/', import.meta.url))

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok })
  console.log((ok ? '  PASS  ' : '  FAIL  ') + name + (detail ? '  -> ' + detail : ''))
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
})
const page = await browser.newPage()
await page.setViewport({ width: 1440, height: 1100 })

const EXPECTED = [/\/core\/api\/v1\/auth\/me$/, /\/core\/internal\/v1\//]
/**
 * 本脚本自己会故意制造一次 401（匿名写入必须被拒绝）。
 *
 * 那条响应是断言目标的证据，不是缺陷；不排除它，最后那条「全程无 4xx」的结论就永远不成立，
 * 而人看到红叉的第一反应是去翻代码，而不是想到「这条是我自己要的」。
 */
const tolerated = []
const isExpected = (text) => EXPECTED.some((re) => re.test(text)) || tolerated.some((re) => re.test(text))
const errors = []
page.on('console', (msg) => {
  if (msg.type() !== 'error') return
  const text = msg.text()
  if (/Failed to load resource/.test(text)) return
  if (!isExpected(text)) errors.push('console: ' + text)
})
page.on('pageerror', (err) => errors.push('pageerror: ' + err.message))
page.on('response', (res) => {
  if (res.status() >= 400 && !isExpected(res.url())) errors.push(`http ${res.status()}: ${res.url()}`)
})

mkdirSync(OUT, { recursive: true })

/** 在页面上下文里发请求：这样 Cookie 与 CSRF 的取值方式与真实前端一致。 */
async function api(path, { method = 'GET', body } = {}) {
  return page.evaluate(
    async (path, method, body) => {
      const needCsrf = method !== 'GET'
      let token = ''
      if (needCsrf) {
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
    const buttons = Array.from(document.querySelectorAll('button'))
    const target = buttons.find((b) => b.textContent.trim() === '退出')
    if (target) target.click()
  })
  await sleep(600)
}

/**
 * 按可见文案点按钮。
 *
 * 不能用 `button.btn.primary` 取第一个：页面上的主按钮不止一个（保存资料、保存置顶），
 * 顺序由模板决定，将来在上面插一个按钮，这条脚本就会去点别的按钮而「成功」地什么也没保存。
 */
async function clickButton(text) {
  const buttons = await page.$$('button')
  for (const button of buttons) {
    const label = await button.evaluate((el) => el.textContent.trim())
    if (label.includes(text)) {
      await button.click()
      return true
    }
  }
  return false
}

// ---- 1. 造一个真实认领链：新账号认领一位未认领学者，管理员批准 ----
await page.goto(BASE + '/', { waitUntil: 'networkidle2', timeout: 60000 })

const stamp = Date.now().toString(36)
const username = 'uismoke' + stamp
const password = 'UiSmoke-' + stamp + '-2026'

const registered = await api('/core/api/v1/auth/register', {
  method: 'POST',
  body: { username, password, displayName: '界面自检 ' + stamp },
})
check('注册自检账号', registered.status === 200 || registered.status === 201, registered.status)

// 找一位未认领学者：从检索接口取候选，再逐个看认领状态。
let scholarId = ''
for (let pageNo = 1; pageNo <= 3 && !scholarId; pageNo += 1) {
  const listing = await fetch(
    `${QUERY}/api/v1/public/search/scholars?q=&size=20&page=${pageNo}`,
  ).then((r) => r.json())
  for (const row of listing.data.items) {
    const detail = await api(`/core/api/v1/public/scholars/${row.scholarId}`)
    const status = detail.payload?.data?.claimedStatus
    if (status === 'UNCLAIMED') {
      scholarId = row.scholarId
      break
    }
  }
}
check('找到一位未认领学者', !!scholarId, scholarId)

const loggedIn = await login(username, password)
check('自检账号可登录', loggedIn, page.url())

const claim = await api('/core/api/v1/account/claims', {
  method: 'POST',
  body: { scholarId, statement: '自有成果，界面自检' },
})
const claimId = claim.payload?.data?.id
check('提交认领申请', !!claimId, claim.payload?.data?.status)

await logout()
await login(ADMIN_USER, ADMIN_PASS)
const approved = await api(`/core/api/v1/admin/core/claims/${claimId}/decision`, {
  method: 'POST',
  body: { decision: 'APPROVE', reason: '界面自检授权' },
})
check('管理员批准认领', approved.status === 200, approved.payload?.data?.status)
await logout()

await login(username, password)

// ---- 2. 档案页出现编辑区 ----
await page.goto(BASE + '/scholars/' + scholarId, { waitUntil: 'networkidle2', timeout: 60000 })
await page.waitForSelector('h1', { timeout: 20000 })
const hasEditor = await page
  .waitForSelector('#profile-summary', { timeout: 15000 })
  .then(() => true)
  .catch(() => false)
check('认领人能看到资料编辑区', hasEditor)
if (!hasEditor) {
  const probe = await api(`/core/api/v1/account/scholars/${scholarId}/profile`)
  console.log('  诊断：直接请求编辑视图 ->', probe.status, JSON.stringify(probe.payload).slice(0, 200))
  console.log('  诊断：页面运行期错误 ->', errors.slice(-4).join(' | '))
}

// ---- 3. 改简介与主页 ----
const summary = '界面自检写入的研究简介 ' + stamp
const homepage = 'https://example.edu/~uismoke-' + stamp
await page.$eval('#profile-summary', (el) => (el.value = ''))
await page.type('#profile-summary', summary)
await page.$eval('#profile-homepage', (el) => (el.value = ''))
await page.type('#profile-homepage', homepage)
const clickedSave = await clickButton('保存资料')
check('找到「保存资料」按钮', clickedSave)
await page.waitForFunction(
  (text) => document.body.innerText.includes(text),
  { timeout: 20000 },
  '资料已保存并立即生效',
).catch(() => null)
const saved = await page.evaluate(() => document.body.innerText.includes('资料已保存并立即生效'))
check('保存资料成功', saved)

await page.reload({ waitUntil: 'networkidle2' })
await page.waitForSelector('h1', { timeout: 20000 })
const shownSummary = await page.evaluate(() => document.body.innerText)
check('刷新后简介已更新', shownSummary.includes(summary), shownSummary.includes(summary) ? '' : '未找到简介')
check('首页链接指向填写的主页', (await page.$$eval('a', (as) => as.map((a) => a.href))).some((h) => h === homepage))

// ---- 4. 上传头像（真实 multipart）----
const png = Buffer.from(
  '89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489' +
    '0000000d49444154789c6360000002000100ffff03000006000557bfabd40000000049454e44ae426082',
  'hex',
)
const pngPath = OUT + 'avatar-' + stamp + '.png'
writeFileSync(pngPath, png)
const fileInput = await page.$('input[type=file]')
await fileInput.uploadFile(pngPath)
await page.waitForFunction(() => document.body.innerText.includes('头像已更新'), { timeout: 20000 }).catch(() => null)
check('上传头像成功', await page.evaluate(() => document.body.innerText.includes('头像已更新')))

const avatarSrc = await page
  .waitForFunction(() => document.querySelector('img.scholar-avatar')?.getAttribute('src') || '', {
    timeout: 20000,
  })
  .then((handle) => handle.jsonValue())
  .catch(() => '')
check('页头显示头像图片', !!avatarSrc && avatarSrc.includes('/avatar'), avatarSrc)

// 头像必须真的能取到字节：只断言 <img> 存在，图裂了也会通过。
const avatarOk = await page.evaluate(async (src) => {
  const response = await fetch(src, { credentials: 'same-origin' })
  const buffer = await response.arrayBuffer()
  return { status: response.status, type: response.headers.get('content-type'), size: buffer.byteLength }
}, avatarSrc)
check(
  '头像地址返回真实图片字节',
  avatarOk.status === 200 && (avatarOk.type || '').startsWith('image/') && avatarOk.size > 50,
  `${avatarOk.status} ${avatarOk.type} ${avatarOk.size}B`,
)

// ---- 5. 置顶代表作 ----
const boxes = await page.$$('.featured-picker input[type=checkbox]')
check('可置顶的候选非空', boxes.length > 0, boxes.length)
if (boxes.length >= 2) {
  await boxes[0].click()
  await boxes[1].click()
  await sleep(200)
  const ranks = await page.$$eval('.featured-option .badge', (nodes) => nodes.map((n) => n.textContent.trim()))
  check('勾选后标出名次', ranks[0] === '第 1 条' && ranks[1] === '第 2 条', ranks.join(' / '))

  const clickedFeatured = await clickButton('保存置顶')
  check('找到「保存置顶」按钮', clickedFeatured)
  await page.waitForFunction(() => document.body.innerText.includes('置顶已更新'), { timeout: 20000 }).catch(() => null)
  check('保存置顶成功', await page.evaluate(() => document.body.innerText.includes('置顶已更新')))

  await page.reload({ waitUntil: 'networkidle2' })
  await page.waitForSelector('.featured-list', { timeout: 20000 }).catch(() => null)
  const featuredCount = await page.$$eval('.featured-list li', (nodes) => nodes.length).catch(() => 0)
  check('公开页出现置顶代表作', featuredCount === 2, featuredCount)
  await page.screenshot({ path: OUT + 'scholar-profile.png', fullPage: false })
}

// ---- 6. 匿名访客看不到编辑区 ----
await logout()
await page.goto(BASE + '/scholars/' + scholarId, { waitUntil: 'networkidle2', timeout: 60000 })
await page.waitForSelector('h1', { timeout: 20000 })
check('匿名访客看不到资料编辑区（入口隐藏，且服务端也会拒绝写入）', (await page.$('#profile-summary')) === null)

// 这次写入必须被拒绝，因此它的 401 不参与「全程无 4xx」的判定。
tolerated.push(new RegExp(`/account/scholars/${scholarId}/profile$`))
const anonymousWrite = await api(`/core/api/v1/account/scholars/${scholarId}/profile`, {
  method: 'PUT',
  body: { researchSummary: '匿名写入' },
})
check('匿名写入被服务端拒绝', anonymousWrite.status === 401 || anonymousWrite.status === 403, anonymousWrite.status)

check('全程无控制台错误、无 4xx/5xx（未登录读会话除外）', errors.length === 0, errors.slice(0, 3).join(' | '))

await browser.close()

const failed = results.filter((r) => !r.ok)
console.log(`\n结果：${results.length - failed.length}/${results.length} 通过`)
if (failed.length) {
  console.log('失败项：\n' + failed.map((f) => '  - ' + f.name).join('\n'))
  process.exitCode = 1
}
