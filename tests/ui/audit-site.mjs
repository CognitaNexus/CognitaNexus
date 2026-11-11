// CognitaNexus 全站导航与功能入口验收
//
// 为什么需要这个脚本（与 shoot-pages.mjs 的分工）：
//   shoot-pages.mjs 断言「页面画出了什么」，页面清单是手写的，且只覆盖 19 条路由；
//   路由表已经有 26 条。手写清单漏页的后果是「全过」这句话用 19 个页面得出。
//   本脚本反其道而行：**路由表直接从 frontend/src/router/index.ts 解析**，新增页面自动纳入，
//   不存在「忘了加进清单」这种漏洞。
//
// 它回答三个问题，正是「按钮点下去应该去哪」这一类的疑问：
//   1. 每条路由打不打得开（含登录守卫是否符合预期、页面是否白屏、有无 5xx）。
//   2. 页面里的每个内部链接，指向的地址**是否真的是一条路由**（死链）。这一层不用点，静态比对即可，
//      所以能把全站所有链接都过一遍。
//   3. 关键页上的链接点下去，是否真的换了地址、新页面是否有内容（渲染成 <a> 但点击被拦、
//      或跳到 404，只有真浏览器能发现）。
//
// 还有一节反查「孤儿路由」：从没被任何页面或导航引用、只能手输 URL 才能到达的页面。
// 这类页面不是错误，但它是「设计上应该有入口却没有」的证据，必须列出来让人判断。
//
// ⚠️ 默认只点链接，不点按钮。目标是**生产站点**，按钮里混着「执行合并」「停用任务」这类
// 真实写操作；验收不该有副作用。按钮只做清单与危险动作标记，点不点由人决定。
//
// 运行（在 tests/ui 下）：
//   node audit-site.mjs
//   CN_WEB=http://127.0.0.1:5173 node audit-site.mjs        # 对本地整栈
//   CN_CLICK_PAGES=6 node audit-site.mjs                    # 调整点击抽样的页数
// 可覆盖：CN_CHROME / CN_WEB / CN_QUERY / CN_USER / CN_PASS / CN_OUT / CN_CLICK_PAGES
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'

const CHROME = process.env.CN_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = process.env.CN_WEB || 'http://127.0.0.1:5173'
const QUERY = process.env.CN_QUERY || 'http://127.0.0.1:8083'
const OUT = process.env.CN_OUT || fileURLToPath(new URL('./.audit/', import.meta.url))
const USER = process.env.CN_USER || 'cnadmin'
const PASS = process.env.CN_PASS || 'cn-admin-Dev-2026'
// 抽样点击的页数上限。全站 26 页 × 每页 4 个链接在慢链路上要跑很久，而点击验证的价值集中在
// 主航路（首页→检索→详情→学者→门户）。其余页面靠静态死链判定已经足够。
const CLICK_PAGES = Number(process.env.CN_CLICK_PAGES || 100)

const ROUTER_FILE = fileURLToPath(new URL('../../frontend/src/router/index.ts', import.meta.url))

// ---------------------------------- 解析路由表 ----------------------------------
// 不用正则直接吃整条记录：路由对象里嵌着 meta 与 import()，非贪婪匹配会跨到下一条记录上，
// 于是「requiresAuth 是哪个页面的」会张冠李戴。这里按花括号配对截取，边界由语法决定而不是由
// 文本形状决定。
function parseRoutes(src) {
  const routes = []
  const starts = []
  const re = /\{\s*path:/g
  let m
  while ((m = re.exec(src)) !== null) starts.push(m.index)
  for (const start of starts) {
    let depth = 0
    let end = start
    for (let i = start; i < src.length; i += 1) {
      if (src[i] === '{') depth += 1
      else if (src[i] === '}') {
        depth -= 1
        if (depth === 0) {
          end = i + 1
          break
        }
      }
    }
    const block = src.slice(start, end)
    const path = /path:\s*'([^']+)'/.exec(block)?.[1] ?? ''
    const name = /name:\s*'([^']+)'/.exec(block)?.[1] ?? ''
    const title = /meta:\s*\{[^}]*title:\s*'([^']*)'/.exec(block)?.[1] ?? ''
    if (!path) continue
    routes.push({ path, name, title, requiresAuth: /requiresAuth:\s*true/.test(block) })
  }
  return routes
}

const routes = parseRoutes(readFileSync(ROUTER_FILE, 'utf8'))
console.log(`路由表解析出 ${routes.length} 条（含兜底页）：${routes.map((r) => r.path).join(' ')}`)

// ---------------------------------- 取真实标识 ----------------------------------
// 带参数的页面必须有真标识才能打开；用假标识验不出「字段读错」，只会得到一个合理的空态。
async function sampleIds() {
  const get = (u) => fetch(u).then((r) => r.json()).catch(() => null)
  const ach = await get(`${QUERY}/api/v1/public/search/achievements?size=1`)
  const sch = await get(`${QUERY}/api/v1/public/search/scholars?size=1`)
  const facets = await get(`${QUERY}/api/v1/public/search/facets`)
  return {
    achievementId: ach?.data?.items?.[0]?.id ?? '',
    scholarId: sch?.data?.items?.[0]?.scholarId ?? '',
    topicId: facets?.data?.topics?.[0]?.topicId ?? '',
  }
}

const ids = await sampleIds()
console.log(
  `样本标识：成果 ${ids.achievementId || '(未取到)'} / 学者 ${ids.scholarId || '(未取到)'} / 领域 ${ids.topicId || '(未取到)'}`,
)

/**
 * 把路由模板填成可访问的地址。
 *
 * 三条带参数的路由参数名都写成 :id，但**指的完全是三样东西**：/achievements/:id 是成果、
 * /scholars/:id 是学者、/topics/:id 是领域。第一版统一填成果标识，结果学者页拿着 ach_xxx
 * 去请求 /api/v1/public/scholars/ach_xxx，得到 404 并被记成「页面白屏」——把脚本自己的错
 * 报成了产品缺陷。参数必须按路由语义分配。
 *
 * 标识缺失时返回 null，由调用方记为「无法验证」而不是「通过」：空集合一律判绿，正是这个仓库
 * 反复警惕的假绿。
 */
function idFor(routePath) {
  if (routePath.startsWith('/achievements')) return ids.achievementId
  if (routePath.startsWith('/scholars')) return ids.scholarId
  if (routePath.startsWith('/topics')) return ids.topicId
  return ''
}

function concretePath(route) {
  if (route.path.includes('pathMatch')) return '/no-such-route-at-all'
  if (!route.path.includes(':id')) return route.path
  const id = idFor(route.path)
  if (!id) return null
  return route.path.replace(':id', encodeURIComponent(id))
}

/** 链接地址归一化：去掉 origin/query/hash，再按路由模板把参数位替换成 :id。 */
function normalize(href) {
  try {
    const u = new URL(href, BASE)
    if (u.origin !== new URL(BASE).origin) return null
    return u.pathname.replace(/\/+$/, '') || '/'
  } catch {
    return null
  }
}

function matchesRoute(pathname) {
  if (pathname === '/') return '/'
  for (const r of routes) {
    if (r.path === pathname) return r.path
    const pattern = r.path
      .replace(/:[^/]+\(\.\*\)\*/g, '.+')
      .replace(/:[^/]+/g, '[^/]+')
    if (new RegExp(`^${pattern}$`).test(pathname)) return r.path
  }
  return null
}

// ---------------------------------- 浏览器 ----------------------------------
mkdirSync(OUT, { recursive: true })

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
  defaultViewport: { width: 1440, height: 1000 },
})
const page = await browser.newPage()

const findings = []
// 预期内的失败请求。不加这层过滤，真回归会被常驻噪声淹掉；但每一条都必须写清为什么是预期，
// 否则白名单会慢慢变成「把看不懂的都加进去」。
//   - /core/api/v1/auth/me 401：未登录时读会话，这是「还没登录」的正常答复。
//   - /core/internal/v1/**：内部投递台账由服务令牌保护，浏览器会话必然被拒，fail-closed 的设计。
//   - /core/api/v1/account/scholars/{id}/profile 403：学者页在「已登录且有 portal:maintain」
//     时会顺带读一次可编辑视图，用来决定是否渲染维护面板。当前账号对该学者没有认领关系时返回
//     403，页面按设计回落到公开视图（ScholarDetailView.loadEditable 显式捕获并置 null）。
//     这是权限模型的正常结果，不是故障。
const KNOWN = [
  /\/core\/api\/v1\/auth\/me$/,
  /\/core\/internal\/v1\//,
  /\/api\/v1\/auth\/me$/,
  /\/core\/api\/v1\/account\/scholars\/[^/]+\/profile$/,
]
const known = (t) => KNOWN.some((re) => re.test(t))
let currentPage = 'startup'

page.on('console', (m) => {
  if (m.type() !== 'error') return
  const t = m.text()
  if (/Failed to load resource/.test(t)) return
  if (known(t)) return
  findings.push({ page: currentPage, kind: 'console.error', text: t.slice(0, 300) })
})
page.on('pageerror', (e) =>
  findings.push({ page: currentPage, kind: 'pageerror', text: String(e.message).slice(0, 300) }),
)
page.on('response', (r) => {
  const s = r.status()
  if (s < 400) return
  if (known(r.url())) return
  findings.push({ page: currentPage, kind: `http-${s}`, text: `${r.request().method()} ${r.url().slice(0, 160)}` })
})
page.on('requestfailed', (r) => {
  const t = `${r.method()} ${r.url().slice(0, 160)} — ${r.failure()?.errorText}`
  if (known(t)) return
  findings.push({ page: currentPage, kind: 'requestfailed', text: t })
})

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ---------------------------------- 登录 ----------------------------------
// 走真实登录表单，顺带验证登录链路本身；失败不中断——公开页面的验收不依赖登录，
// 但受限页面会全部落到 /login，那本身就是要报告的结论。
async function login() {
  currentPage = 'login'
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle2', timeout: 45000 })
  await sleep(800)
  try {
    await page.type('#login-username', USER)
    await page.type('#login-password', PASS)
    await Promise.all([
      page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 30000 }).catch(() => {}),
      page.click('button.btn.primary'),
    ])
    await sleep(1200)
  } catch (e) {
    return { ok: false, reason: `登录表单交互失败：${e.message}` }
  }
  const landed = await page.evaluate(() => location.pathname)
  if (landed === '/login') {
    const msg = await page.evaluate(
      () => document.querySelector('.notice.danger')?.textContent?.trim() || '(无错误文案)',
    )
    return { ok: false, reason: `登录未通过：${msg}` }
  }
  const me = await page
    .evaluate(() => fetch('/core/api/v1/auth/me', { credentials: 'include' }).then((r) => r.json()))
    .catch(() => null)
  return { ok: true, landed, username: me?.data?.username ?? '' }
}

const loginResult = await login()
console.log(
  `${loginResult.ok ? 'OK  ' : 'WARN'} 登录：${loginResult.ok ? `落在 ${loginResult.landed}（${loginResult.username}）` : loginResult.reason}`,
)

// ---------------------------------- 逐路由打开 ----------------------------------
/** 读页面状态：正文是否为空、标题、h1、以及该页所有内部链接与按钮。 */
async function inspect() {
  return page.evaluate(() => {
    const main = document.querySelector('#main-content') || document.querySelector('main') || document.body
    const text = (main.innerText || '').replace(/\s+/g, ' ').trim()
    const links = [...document.querySelectorAll('a[href]')]
      .map((a) => ({ href: a.getAttribute('href'), text: (a.textContent || '').trim().slice(0, 40) }))
      .filter((l) => l.href && (l.href.startsWith('/') || l.href.startsWith('http')))
    const buttons = [...document.querySelectorAll('button')].map((b) => ({
      text: (b.textContent || '').trim().slice(0, 40),
      disabled: b.disabled === true,
    }))
    return {
      url: location.href,
      path: location.pathname,
      docTitle: document.title,
      h1: document.querySelector('h1')?.textContent?.trim() ?? '',
      textLen: text.length,
      links,
      buttons,
    }
  })
}

// 危险动作词：生产站点上这些按钮的真实效果是改数据或对外发包，验收阶段一律不点。
const DANGEROUS =
  /删除|移除|注销|执行|提交|合并|重建|清理|停用|启用|保存|撤销|驳回|通过|确认|发布|导入|上传|导出|下载|抓取|运行|回滚|重置|解锁|封禁|同意|拒绝/

const routeRows = []
const allLinkTargets = new Set()
const deadLinks = []

for (const route of routes) {
  const path = concretePath(route)
  const label = route.path
  if (!path) {
    routeRows.push({ label, name: route.name, status: '无法验证', note: '缺少样本标识' })
    console.log(`SKIP ${label.padEnd(26)} 缺少样本标识，无法打开`)
    continue
  }
  currentPage = label
  const before = findings.length
  let nav = null
  try {
    nav = await page.goto(BASE + path, { waitUntil: 'networkidle2', timeout: 45000 })
  } catch (e) {
    routeRows.push({ label, name: route.name, status: '打不开', note: e.message.slice(0, 120) })
    console.log(`FAIL ${label.padEnd(26)} 打不开：${e.message.slice(0, 80)}`)
    continue
  }
  await sleep(1200)
  const info = await inspect()
  const added = findings.length - before
  const redirected = info.path !== path

  // 白屏判定用正文长度而不是「有没有报错」：渲染成功但内容为空同样是坏的。
  // 兜底页豁免：它本来就只写一句「页面不存在」加一个返回入口，正文三十几个字是设计如此，
  // 拿阈值去卡它等于把正确行为记成缺陷。
  const isFallback = route.path.includes('pathMatch')
  const blank = !isFallback && info.textLen < 80
  const status = isFallback
    ? info.h1 === '页面不存在'
      ? '兜底页（预期）'
      : '兜底页异常'
    : blank
      ? '白屏/内容过少'
      : redirected && route.requiresAuth && info.path === '/login'
        ? '守卫重定向'
        : '可打开'
  if (redirected) routeRows.push({ label, name: route.name, status, note: `重定向到 ${info.path}` })
  else routeRows.push({ label, name: route.name, status, note: '' })

  for (const l of info.links) {
    const p = normalize(l.href)
    if (!p) continue
    allLinkTargets.add(p)
    if (!matchesRoute(p)) deadLinks.push({ from: label, href: l.href, text: l.text })
  }

  const dangerous = info.buttons.filter((b) => DANGEROUS.test(b.text))
  routeRows[routeRows.length - 1].buttons = info.buttons.length
  routeRows[routeRows.length - 1].links = info.links.length
  routeRows[routeRows.length - 1].dangerous = dangerous.length
  routeRows[routeRows.length - 1].h1 = info.h1
  routeRows[routeRows.length - 1].textLen = info.textLen
  // 按钮文案去重留档：报告里要能回答「这一页到底有哪些功能入口」。只留计数的话，
  // 「按钮 180 个」既不能说明功能齐全，也不能说明有没有重复入口。
  routeRows[routeRows.length - 1].buttonTexts = [...new Set(info.buttons.map((b) => b.text).filter(Boolean))].slice(0, 40)

  const flag = added === 0 && !blank && (!redirected || !route.requiresAuth) ? 'OK  ' : 'WARN'
  console.log(
    `${flag} ${label.padEnd(26)} ${status.padEnd(8)} h1=${(info.h1 || '(空)').slice(0, 22).padEnd(24)} ` +
      `正文=${String(info.textLen).padStart(5)} 链接=${String(info.links.length).padStart(2)} 按钮=${String(info.buttons.length).padStart(2)}` +
      ` 异常=${added}${redirected ? ` → ${info.path}` : ''}`,
  )
}

// ---------------------------------- 孤儿路由 ----------------------------------
// 从没被任何页面链接到、也不在导航里的页面。不是错误，但它意味着「设计上应该有入口」这件事
// 没有落实——只能靠手输地址到达。
const navTargets = new Set(['/']) // 品牌 logo 指向首页，永远算作有入口
for (const t of allLinkTargets) navTargets.add(t)
const orphans = routes
  .filter((r) => !r.path.includes(':') && !r.path.includes('pathMatch'))
  .filter((r) => !navTargets.has(r.path))
  .map((r) => r.path)

// ---------------------------------- 关键页点击验证 ----------------------------------
// 只点链接：链接是只读的跳转，点错了最多停在 404；不点按钮，避免在生产库上触发写操作。
async function clickSample(route) {
  const path = concretePath(route)
  if (!path) return null
  currentPage = `${route.path}#click`
  await page.goto(BASE + path, { waitUntil: 'networkidle2', timeout: 45000 })
  await sleep(1200)
  const start = await page.evaluate(() => location.pathname)
  const candidates = await page.evaluate(() => {
    const out = []
    for (const a of document.querySelectorAll('a[href]')) {
      const href = a.getAttribute('href') || ''
      if (!href.startsWith('/')) continue
      const text = (a.textContent || '').trim().slice(0, 30)
      const box = a.getBoundingClientRect()
      if (box.width === 0 || box.height === 0) continue // 不可见的不点，点了也反映不出「用户点得到」
      out.push({ href, text })
    }
    // 同一个目标只试一次；把「回首页/去检索」这类通用入口排后面，优先验证页面自己的业务跳转。
    const seen = new Set()
    const uniq = []
    for (const c of out) {
      if (seen.has(c.href)) continue
      seen.add(c.href)
      uniq.push(c)
    }
    return uniq.sort((a, b) => (a.href === '/' ? 1 : 0) - (b.href === '/' ? 1 : 0))
  })
  // 指向当前页自身的链接（例如人已经在 /search，再点导航里的「成果检索」）点完地址当然不变。
  // 把它当成「点击无效」是假阳性——第一版就报了这么一条出来。
  const here = normalize(start)
  const usable = candidates.filter((c) => normalize(c.href) !== here)
  const results = []
  for (const c of usable.slice(0, 4)) {
    const target = matchesRoute(normalize(c.href) || '')
    if (!target) {
      results.push({ href: c.href, text: c.text, ok: false, reason: '目标不是已知路由（死链）' })
      continue
    }
    const clicked = await page.evaluate((href) => {
      const a = [...document.querySelectorAll('a[href]')].find((x) => x.getAttribute('href') === href)
      if (!a) return false
      a.click()
      return true
    }, c.href)
    if (!clicked) {
      results.push({ href: c.href, text: c.text, ok: false, reason: '元素取不到' })
      continue
    }
    await sleep(1500)
    const after = await page.evaluate(() => ({
      path: location.pathname,
      h1: document.querySelector('h1')?.textContent?.trim() ?? '',
      textLen: (document.querySelector('#main-content') || document.body).innerText.trim().length,
    }))
    const changed = after.path !== start
    results.push({
      href: c.href,
      text: c.text,
      ok: changed && after.textLen > 80,
      reason: changed ? `跳到 ${after.path}，h1=${after.h1.slice(0, 24) || '(空)'}` : '点击后地址没变',
    })
    await page.goto(BASE + path, { waitUntil: 'networkidle2', timeout: 45000 }).catch(() => {})
    await sleep(600)
  }
  return results
}

// 点击抽样挑「主航路 + 按钮最多的页」，按链接数降序取前 CLICK_PAGES 个，避免把预算花在内容很少的页上。
// 默认全量：只抽几页时，「点击都正常」这句话覆盖的是那几页，报告里必须写清覆盖范围。
const clickTargets = routes
  .map((r) => ({ r, n: routeRows.find((x) => x.label === r.path)?.buttons ?? 0 }))
  .filter((x) => x.r.path && !x.r.path.includes('pathMatch'))
  .sort((a, b) => b.n - a.n)
  .slice(0, CLICK_PAGES)
  .map((x) => x.r)

const clickResults = []
for (const route of clickTargets) {
  const res = await clickSample(route)
  if (!res) continue
  for (const r of res) clickResults.push({ page: route.path, ...r })
  const bad = res.filter((x) => !x.ok).length
  console.log(`${bad === 0 ? 'OK  ' : 'WARN'} 点击 ${route.path.padEnd(24)} 试 ${res.length} 个链接，失败 ${bad}`)
}

await browser.close()

// ---------------------------------- 汇总 ----------------------------------
const bad = routeRows.filter((r) => r.status !== '可打开' && r.status !== '兜底页（预期）')
const failedClicks = clickResults.filter((c) => !c.ok)
const findingsByPage = new Map()
for (const f of findings) {
  if (!findingsByPage.has(f.page)) findingsByPage.set(f.page, [])
  findingsByPage.get(f.page).push(f)
}

console.log('\n=== 路由覆盖 ===')
console.log(`共 ${routeRows.length} 条：可打开 ${routeRows.length - bad.length}，异常 ${bad.length}`)
for (const r of bad) console.log(`  ✗ ${r.label} — ${r.status} ${r.note}`)

console.log('\n=== 死链（链接目标不是任何一条路由）===')
if (deadLinks.length === 0) console.log('  无')
for (const d of deadLinks.slice(0, 40)) console.log(`  ✗ [${d.from}] ${d.href}「${d.text}」`)

console.log('\n=== 孤儿路由（没有任何页面链接到，只能手输地址）===')
console.log(orphans.length === 0 ? '  无' : `  ${orphans.join('  ')}`)

console.log('\n=== 点击跳转验证 ===')
console.log(`试了 ${clickResults.length} 个链接，失败 ${failedClicks.length}`)
for (const c of failedClicks) console.log(`  ✗ [${c.page}] ${c.href}「${c.text}」— ${c.reason}`)

console.log('\n=== 页面运行期异常 ===')
if (findings.length === 0) console.log('  全部页面无控制台错误、无 4xx/5xx')
for (const [pg, list] of findingsByPage) {
  console.log(`[${pg}]`)
  for (const f of list.slice(0, 8)) console.log(`  ${f.kind}  ${f.text}`)
  if (list.length > 8) console.log(`  …另有 ${list.length - 8} 条`)
}

const report = { generatedAt: new Date().toISOString(), base: BASE, login: loginResult, routes: routeRows, deadLinks, orphans, clickResults, findings }
writeFileSync(`${OUT}/audit.json`, JSON.stringify(report, null, 2))

const md = [
  `# 全站验收报告（${BASE}）`,
  '',
  `- 生成时间：${report.generatedAt}`,
  `- 登录：${loginResult.ok ? `通过（${loginResult.username}）` : `未通过 — ${loginResult.reason}`}`,
  `- 路由：${routeRows.length} 条，异常 ${bad.length} 条`,
  `- 死链：${deadLinks.length} 个`,
  `- 孤儿路由：${orphans.length} 个`,
  `- 点击验证：${clickResults.length} 次，失败 ${failedClicks.length} 次`,
  `- 运行期异常：${findings.length} 条`,
  '',
  '## 路由覆盖',
  '',
  '| 路由 | 名称 | 状态 | h1 | 正文长度 | 链接 | 按钮 | 需确认 | 备注 |',
  '| --- | --- | --- | --- | --- | --- | --- | --- | --- |',
  ...routeRows.map(
    (r) =>
      `| ${r.label} | ${r.name} | ${r.status} | ${(r.h1 || '').replace(/\|/g, '/')} | ${r.textLen ?? ''} | ${r.links ?? ''} | ${r.buttons ?? ''} | ${r.dangerous ?? ''} | ${r.note || ''} |`,
  ),
  '',
  '## 未通过项',
  '',
  ...(bad.length === 0 ? ['无'] : bad.map((r) => `- ${r.label}：${r.status} ${r.note}`)),
  '',
  '## 死链',
  '',
  ...(deadLinks.length === 0 ? ['无'] : deadLinks.map((d) => `- 来自 ${d.from}：${d.href}「${d.text}」`)),
  '',
  '## 孤儿路由',
  '',
  ...(orphans.length === 0 ? ['无'] : orphans.map((o) => `- ${o}`)),
  '',
  '## 各页功能入口（按钮清单）',
  '',
  '> ⚠️ 本脚本**只点链接、不点按钮**：目标站是生产环境，按钮里混着「执行合并」「停用任务」这类真实写操作。',
  '> 按钮在这里只做存在性与命名留档，用于判断「这一页的功能入口是否齐全、有没有重复」，不代表它可用。',
  '',
  ...routeRows
    .filter((r) => r.buttonTexts && r.buttonTexts.length > 0)
    .map(
      (r) =>
        `- **${r.label}**（${r.buttons} 个，其中危险动作 ${r.dangerous} 个）：${r.buttonTexts.join(' / ')}`,
    ),
  '',
  '## 点击跳转失败',
  '',
  ...(failedClicks.length === 0 ? ['无'] : failedClicks.map((c) => `- ${c.page} → ${c.href}「${c.text}」：${c.reason}`)),
].join('\n')
writeFileSync(`${OUT}/report.md`, md)

console.log(`\n报告：${OUT}/report.md`)
process.exit(bad.length === 0 && deadLinks.length === 0 && failedClicks.length === 0 ? 0 : 1)
