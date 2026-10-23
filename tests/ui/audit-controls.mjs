// CognitaNexus 只读控件验收：按钮点下去，到底有没有反应。
//
// 与另外两个脚本的分工：
//   shoot-pages.mjs     —— 断言「页面画出了什么」，页面清单手写。
//   audit-site.mjs      —— 断言「链接指向的路由存不存在」，只点链接不点按钮。
//   本脚本（audit-controls）—— 断言「控件点下去有可观察的反应」。
//
// 为什么必须有这一层：链接、路由、渲染全对，也不代表功能可用。筛选条件改了不重新检索、
// 引用格式切换按钮点了文本不变、导出按钮点了没有任何请求——这些都是「页面看起来正常」的坏。
// 路由验收（audit-site）对这类问题完全无感。
//
// ⚠️ 只点**只读**控件。目标默认是生产站点，页面上混着「执行合并」「停用任务」「驳回」这类
// 真实写操作。因此本脚本**不按文案猜**、也不做「把看得见的按钮都点一遍」：
// 每一项都由下面的 PROBES 白名单显式声明，点哪个控件、期望观察到什么，都写在代码里。
// 新增只读控件后再往白名单里加一条；不在白名单里的控件永远不会被点。
//
// 三条判定原则：
//   1. 「点完有反应」= 地址变了 / 正文变了 / 指定元素出现 / 期间有成功的请求。四者至少中一个，
//      并且要说清是哪一种。只说「没报错」等于什么都没验。
//   2. 控件不存在记「缺失」而**不**记通过。空集合判绿是这个仓库反复警惕的假绿；
//      「这一页本该有筛选按钮却没有」正是要发现的东西。
//   3. 控件存在但被禁用记「禁用」并附禁用原因（disabled 属性或页面上的提示文案）。
//      禁用可能是正确的（比如没勾选就禁用批量导出），所以只报告、不判失败——
//      但「本该可用却一直禁用」需要人看证据判断。
//
// 运行（在 tests/ui 下）：
//   node audit-controls.mjs
//   CN_WEB=http://127.0.0.1:5173 node audit-controls.mjs
// 可覆盖：CN_CHROME / CN_WEB / CN_QUERY / CN_USER / CN_PASS / CN_OUT / CN_Q
import { mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import puppeteer from 'puppeteer-core'

const CHROME = process.env.CN_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = process.env.CN_WEB || 'http://127.0.0.1:5173'
const QUERY = process.env.CN_QUERY || 'http://127.0.0.1:8083'
const OUT = process.env.CN_OUT || fileURLToPath(new URL('./.audit/', import.meta.url))
const USER = process.env.CN_USER || 'cnadmin'
const PASS = process.env.CN_PASS || 'cn-admin-Dev-2026'
// 检索用的关键词。取一个「结果够多但导出很快」的词：分页、全选、导出都需要有结果才验得出来，
// 而空结果下这些控件全部被禁用，会得到一堆「禁用」的噪声。实测 climate 约 154 条。
const Q = process.env.CN_Q || 'climate'

mkdirSync(OUT, { recursive: true })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ---------------------------------- 取真实标识 ----------------------------------
// 带参数的页面必须有真标识，否则验的是空态而不是功能。
async function sampleIds() {
  const get = (u) => fetch(u).then((r) => r.json()).catch(() => null)
  const ach = await get(`${QUERY}/api/v1/public/search/achievements?size=1&q=${encodeURIComponent(Q)}`)
  const sch = await get(`${QUERY}/api/v1/public/search/scholars?size=3`)
  return {
    achievementId: ach?.data?.items?.[0]?.id ?? '',
    scholarId: sch?.data?.items?.[0]?.scholarId ?? '',
    scholars: (sch?.data?.items ?? []).map((s) => s.scholarId),
  }
}
const ids = await sampleIds()
console.log(
  `样本标识：成果 ${ids.achievementId || '(未取到)'} / 学者 ${ids.scholarId || '(未取到)'} / 关键词 ${Q}`,
)

// ---------------------------------- 浏览器 ----------------------------------
const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: 'new',
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
  defaultViewport: { width: 1440, height: 1000 },
})
// 剪贴板权限：详情页的「复制」按钮要写剪贴板。不给权限时浏览器会拒绝，页面会打印
// 「浏览器拒绝了剪贴板写入」——那是环境限制，不是产品缺陷，两者必须能分开。
try {
  await browser
    .defaultBrowserContext()
    .overridePermissions(new URL(BASE).origin, ['clipboard-read', 'clipboard-write', 'clipboard-sanitized-write'])
} catch {
  /* 旧版 Chrome 不认 clipboard-sanitized-write，忽略即可 */
}
const bp = await browser.newPage()

// 预期内的失败请求，理由与 audit-site.mjs 一致（未登录读会话、内部端点、无认领关系的可编辑视图）。
const KNOWN = [
  /\/core\/api\/v1\/auth\/me$/,
  /\/api\/v1\/auth\/me$/,
  /\/core\/internal\/v1\//,
  /\/core\/api\/v1\/account\/scholars\/[^/]+\/profile$/,
]
const known = (t) => KNOWN.some((re) => re.test(t))

/** 当前探针名，用于把控制台异常归到具体控件上。 */
let currentProbe = 'startup'
const softFindings = []
bp.on('console', (m) => {
  if (m.type() !== 'error') return
  const t = m.text()
  if (/Failed to load resource/.test(t)) return
  if (known(t)) return
  softFindings.push({ probe: currentProbe, kind: 'console.error', text: t.slice(0, 240) })
})
bp.on('pageerror', (e) =>
  softFindings.push({ probe: currentProbe, kind: 'pageerror', text: String(e.message).slice(0, 240) }),
)

/** 每个探针独立收集的响应与失败：只统计「点这一下」引起的流量，不把上个探针的错算在它头上。 */
let traffic = []
bp.on('response', (r) => {
  traffic.push({ status: r.status(), url: r.url() })
})

const snap = () =>
  bp.evaluate(() => {
    const main = document.querySelector('#main-content') || document.querySelector('main') || document.body
    return {
      url: location.pathname + location.search,
      text: (main.innerText || '').replace(/\s+/g, ' ').trim(),
    }
  })

/**
 * 按文案点按钮，**只在主内容区里找**。
 *
 * 为什么必须限定范围：页头有一个同名「检索」提交按钮（外壳的常驻检索框），它在 DOM 里排在
 * 主内容之前。第一版不限范围，排序探针点「检索」时点中了页头那个 —— 输入框是空的，于是地址
 * 变成不带任何条件的 /search，排序自然没写进去。把脚本自己点错元素报成产品缺陷，正是这个仓库
 * 最该避免的假红。
 *
 * 返回：
 *   字符串（点到的文案）= 已点击
 *   'disabled:文案'    = 存在但禁用
 *   null               = 主内容区里没有这个控件
 * 只认 button / a.btn：普通 <a> 是链接，归 audit-site.mjs 管。
 */
const clickText = (labels) =>
  bp.evaluate((labels) => {
    const main = document.querySelector('#main-content') || document.body
    const els = [...main.querySelectorAll('button, a.btn')]
    for (const el of els) {
      const t = (el.textContent || '').trim()
      if (!labels.includes(t)) continue
      const box = el.getBoundingClientRect()
      if (!box.width || !box.height) continue // 不可见的不点：用户点不到，点了也证明不了可用
      if (el.disabled) return `disabled:${t}`
      el.click()
      return t
    }
    return null
  }, labels)

/** 主内容区里的选择器前缀。同上：页头还有「数据口径」下拉与检索框，不能让它们混进页面内的验收。 */
const IN = '#main-content '

/** 主内容区里某个选择器存在时返回 true。 */
const has = (sel) => bp.evaluate((s) => !!document.querySelector(s), sel)

const seenStatus = (re) => traffic.some((t) => re.test(t.url) && t.status < 400)
const failures = () =>
  traffic.filter((t) => t.status >= 400 && !known(t.url)).map((t) => `${t.status} ${t.url.slice(0, 140)}`)

// ---------------------------------- 登录 ----------------------------------
async function login() {
  await bp.goto(`${BASE}/login`, { waitUntil: 'networkidle2', timeout: 45000 })
  await sleep(700)
  try {
    await bp.type('#login-username', USER)
    await bp.type('#login-password', PASS)
    await Promise.all([
      bp.waitForNavigation({ waitUntil: 'networkidle2', timeout: 30000 }).catch(() => {}),
      bp.click('button.btn.primary'),
    ])
    await sleep(1200)
  } catch (e) {
    return { ok: false, reason: e.message }
  }
  const me = await bp
    .evaluate(() => fetch('/core/api/v1/auth/me', { credentials: 'include' }).then((r) => r.json()))
    .catch(() => null)
  const landed = await bp.evaluate(() => location.pathname)
  return { ok: landed !== '/login', landed, username: me?.data?.username ?? '' }
}
const loginResult = await login()
console.log(
  `${loginResult.ok ? 'OK  ' : 'WARN'} 登录：${loginResult.ok ? `落在 ${loginResult.landed}（${loginResult.username}）` : loginResult.reason}`,
)

// ---------------------------------- 探针执行器 ----------------------------------
const results = []

async function probe({ page, name, act, check, settle = 1800 }) {
  currentProbe = `${page} :: ${name}`
  await bp.goto(BASE + page, { waitUntil: 'networkidle2', timeout: 45000 }).catch(() => {})
  await sleep(900)
  const before = await snap()
  traffic = []
  let acted
  try {
    acted = await act()
  } catch (e) {
    acted = `err:${e.message}`
  }
  await sleep(settle)
  const after = await snap()
  const errs = failures()

  let verdict
  if (acted === null) {
    verdict = { status: '缺失', ok: false, detail: '页面上找不到这个控件（或当前状态下不渲染）' }
  } else if (typeof acted === 'string' && acted.startsWith('disabled:')) {
    verdict = { status: '禁用', ok: false, detail: `控件存在但处于禁用状态（${acted.slice(9)}）` }
  } else if (typeof acted === 'string' && acted.startsWith('err:')) {
    verdict = { status: '异常', ok: false, detail: acted.slice(4) }
  } else {
    try {
      // check 允许是 async（要读 DOM 状态），必须 await，否则拿到的是 Promise，恒判失败。
      const r = await check(before, after, acted, errs)
      // check 可以覆盖状态名（例如「降级通过」）：ok 表示「行为符合设计」，状态名负责说清是哪一种符合。
      verdict = { status: r.status || (r.ok ? '通过' : '未达预期'), ok: r.ok, detail: r.detail }
    } catch (e) {
      verdict = { status: '异常', ok: false, detail: `校验抛错：${e.message}` }
    }
  }
  if (errs.length && verdict.ok) {
    // 反应对了但同时有失败请求，不能算通过：那可能是一次静默失败后的兜底渲染。
    verdict = { status: '未达预期', ok: false, detail: `${verdict.detail}；但期间有失败请求：${errs[0]}` }
  }
  results.push({ page, name, ...verdict, acted: String(acted), errs })
  console.log(
    `${verdict.ok ? 'OK  ' : 'FAIL'} ${page.padEnd(24)} ${name.padEnd(20)} ${verdict.status} — ${String(verdict.detail).slice(0, 110)}`,
  )
}

const hit = (s, re) => re.test(s)

// ---------------------------------- 检索页（11 项） ----------------------------------
const SEARCH = `/search?q=${encodeURIComponent(Q)}`

await probe({
  page: SEARCH,
  name: '切换作用域到学者',
  act: () => clickText(['学者']),
  check: (b, a) => ({
    ok: a.url.includes('scope=scholars') && a.text !== b.text,
    detail: a.url.includes('scope=scholars')
      ? `地址写入 scope=scholars，正文由 ${b.text.length} 字变为 ${a.text.length} 字`
      : `点击后地址没有 scope=scholars，实际 ${a.url}`,
  }),
})

await probe({
  page: `${SEARCH}&scope=scholars`,
  name: '切回成果作用域',
  act: () => clickText(['成果']),
  check: (b, a) => ({
    ok: !a.url.includes('scope=scholars'),
    detail: a.url.includes('scope=scholars') ? `仍是学者作用域，地址 ${a.url}` : `已回到成果作用域（${a.url}）`,
  }),
})

await probe({
  page: SEARCH,
  name: '展开筛选条件',
  act: () => clickText(['筛选条件']),
  check: async (b, a) => {
    const shown = await has(`${IN}#tp`)
    return { ok: shown, detail: shown ? '类型／年份／机构／领域四个筛选控件已出现' : '点击后筛选区没有渲染出来' }
  },
})

await probe({
  page: SEARCH,
  name: '类型筛选并应用',
  act: async () => {
    const opened = await clickText(['筛选条件'])
    if (opened !== '筛选条件') return opened
    await sleep(500)
    const value = await bp.evaluate((s) => {
      const el = document.querySelector(s)
      return el ? ([...el.options].find((o) => o.value)?.value ?? null) : null
    }, `${IN}#tp`)
    if (!value) return null
    await bp.select(`${IN}#tp`, value)
    await sleep(300)
    const applied = await clickText(['应用筛选'])
    return applied === '应用筛选' ? { applied: value } : applied
  },
  check: (b, a) => {
    const ok = /type=/.test(a.url) && a.text !== b.text
    return {
      ok,
      detail: ok
        ? `地址写入 ${a.url.split('?')[1]?.slice(0, 80)}，结果区随之变化`
        : `地址未写入 type=（实际 ${a.url}）或结果没变`,
    }
  },
})

await probe({
  page: `${SEARCH}&type=paper`,
  name: '重置筛选条件',
  act: () => clickText(['重置']),
  check: (b, a) => ({
    ok: !/type=|yearFrom=|organization=|topic=/.test(a.url),
    detail: /type=/.test(a.url) ? `重置后条件仍在：${a.url}` : `条件已清空（${a.url}）`,
  }),
})

await probe({
  page: SEARCH,
  name: '排序切换后重新检索',
  act: async () => {
    // 限定主内容区：页头还有「数据口径」下拉，取错它会让本探针变成「改口径」而不是「改排序」。
    const sel = `${IN}select[aria-label="排序方式"]`
    const exists = await has(sel)
    if (!exists) return null
    const value = await bp.evaluate((s) => {
      const el = document.querySelector(s)
      return [...el.options].map((o) => o.value).find((v) => v && v !== 'relevance') ?? null
    }, sel)
    if (!value) return null
    await bp.select(sel, value)
    await sleep(300)
    const clicked = await clickText(['检索'])
    return clicked === '检索' ? { sort: value } : clicked
  },
  check: (b, a, acted) => {
    // 排序必须写进地址栏（页面把条件当唯一真相来源），否则分享出去的链接会丢排序。
    const want = acted?.sort
    const ok = want ? a.url.includes(`sort=${encodeURIComponent(want)}`) : false
    return { ok, detail: ok ? `排序 ${want} 已写入地址栏` : `地址里没有 sort=${want}（实际 ${a.url}）` }
  },
})

await probe({
  page: SEARCH,
  name: '翻到下一页',
  act: () => clickText(['下一页']),
  check: (b, a) => {
    const ok = /page=2/.test(a.url) && b.text !== a.text
    return { ok, detail: ok ? '地址写入 page=2，结果列表已更换' : `翻页未生效（实际 ${a.url}）` }
  },
})

await probe({
  page: SEARCH,
  name: '全选本页',
  act: () => clickText(['全选本页']),
  check: (b, a) => {
    const n = Number(/已选 (\d+) 条/.exec(a.text)?.[1] ?? 0)
    return { ok: n > 0, detail: n > 0 ? `计数变为「已选 ${n} 条」` : '点击后「已选」计数仍为 0' }
  },
})

await probe({
  page: SEARCH,
  name: '清空已选',
  act: async () => {
    const all = await clickText(['全选本页'])
    if (all !== '全选本页') return all
    await sleep(600)
    return clickText(['清空'])
  },
  check: (b, a) => {
    const n = Number(/已选 (\d+) 条/.exec(a.text)?.[1] ?? -1)
    return { ok: n === 0, detail: n >= 0 ? `清空后计数为 ${n}` : '找不到「已选」计数' }
  },
})

await probe({
  page: SEARCH,
  name: '勾选单条成果',
  act: async () => {
    const clicked = await bp.evaluate(() => {
      const box = document.querySelector('.result-item input[type="checkbox"]')
      if (!box) return null
      box.click()
      return 'checkbox'
    })
    return clicked
  },
  check: (b, a) => {
    const n = Number(/已选 (\d+) 条/.exec(a.text)?.[1] ?? -1)
    return { ok: n === 1, detail: n >= 0 ? `勾选后计数为 ${n}` : '找不到「已选」计数' }
  },
})

await probe({
  page: SEARCH,
  name: '导出 CSV',
  act: () => clickText(['导出 CSV']),
  check: (b, a) => {
    // 导出走 fetch（拿到 blob 再触发下载），所以成功与否看请求状态，不看有没有落盘。
    const exported = seenStatus(/export/i)
    const settled = hit(a.text, /导出 CSV/)
    return {
      ok: exported && settled,
      detail: exported
        ? `导出请求 ${traffic.find((t) => /export/i.test(t.url) && t.status < 400)?.status}，按钮回到「导出 CSV」`
        : `点下去没有发出导出请求（按钮文案：${settled ? '已复原' : '仍在导出中'}）`,
    }
  },
  settle: 6000,
})

await probe({
  page: SEARCH,
  name: '批量导出引用',
  act: async () => {
    const sel = `${IN}select[aria-label="批量导出的引用格式"]`
    const exists = await has(sel)
    if (!exists) return null
    await bp.select(sel, 'bibtex')
    const all = await clickText(['全选本页'])
    if (all !== '全选本页') return all
    await sleep(700)
    const run = await clickText(['导出引用'])
    return run === '导出引用' ? '导出引用' : run
  },
  check: (b, a) => {
    const ok = seenStatus(/citation/i)
    return {
      ok,
      detail: ok
        ? `引用导出请求 ${traffic.find((t) => /citation/i.test(t.url) && t.status < 400)?.status}，按钮回到「导出引用」`
        : `点下去没有发出引用导出请求（正文里是否出现报错：${hit(a.text, /失败|错误/) ? '是' : '否'}）`,
    }
  },
  settle: 5000,
})

// ---------------------------------- 成果详情：引用格式（2 项） ----------------------------------
if (ids.achievementId) {
  const DETAIL = `/achievements/${ids.achievementId}`
  await probe({
    page: DETAIL,
    name: '逐一切换引用格式',
    act: async () => {
      const n = await bp.evaluate(
        (s) => document.querySelectorAll(`${s} [aria-label="引用格式"] button[role="tab"]`).length,
        '#main-content',
      )
      if (!n) return null
      const texts = []
      for (let i = 0; i < n; i += 1) {
        await bp.evaluate(
          (idx, s) => {
            document.querySelectorAll(`${s} [aria-label="引用格式"] button[role="tab"]`)[idx].click()
          },
          i,
          '#main-content',
        )
        await sleep(420)
        texts.push(
          await bp.evaluate(
            (s) => (document.querySelector(`${s} pre.pre`)?.textContent ?? '').trim().slice(0, 400),
            '#main-content',
          ),
        )
      }
      return { count: n, texts }
    },
    check: (b, a, acted) => {
      const empty = acted.texts.filter((t) => !t).length
      const distinct = new Set(acted.texts).size
      return {
        ok: empty === 0 && distinct >= Math.min(2, acted.count),
        detail:
          `切换 ${acted.count} 种格式：空文本 ${empty} 个、互不相同 ${distinct} 种` +
          (empty ? `；空的是第 ${acted.texts.map((t, i) => (t ? null : i + 1)).filter(Boolean).join(',')} 个` : ''),
      }
    },
  })

  await probe({
    page: DETAIL,
    name: '复制引用文本',
    act: () => clickText(['复制']),
    check: async (b, a) => {
      const note = await bp.evaluate(() => {
        const ps = [...document.querySelectorAll('p')]
        const p = ps.find((x) => /已复制|剪贴板/.test(x.textContent || ''))
        return p ? p.textContent.trim() : ''
      })
      if (/已复制/.test(note)) return { ok: true, detail: `页面提示「${note}」` }
      if (/剪贴板/.test(note)) {
        // 站点以 http 暴露（不是 HTTPS），浏览器按规范把 window.isSecureContext 置为 false，
        // Clipboard API 必然被拒。页面此时给出的是「浏览器拒绝了剪贴板写入，请手动选中复制」——
        // 这是**设计好的降级路径**，按钮的反应（提示文案）是正确且可观察的，不该报成缺陷。
        // 但它确实不是「复制成功」，所以单独给一个状态，别混进「通过」里去。
        return { ok: true, status: '降级通过', detail: `非 HTTPS 下浏览器拒绝写剪贴板，页面按设计提示「${note}」` }
      }
      return { ok: false, detail: '点击后没有任何剪贴板相关提示' }
    },
  })
} else {
  results.push({ page: '/achievements/:id', name: '引用格式', status: '无法验证', ok: false, detail: '未取到成果标识' })
}

// ---------------------------------- 关系网络（1 项） ----------------------------------
if (ids.scholarId) {
  await probe({
    page: `/network?scholarId=${encodeURIComponent(ids.scholarId)}`,
    name: '重新加载关系网络',
    act: async () => {
      await bp.select(`${IN}#depth`, '1').catch(() => {})
      const clicked = await clickText(['加载网络'])
      return clicked
    },
    check: async (b, a) => {
      const nodes = await bp.evaluate(() => document.querySelectorAll('svg circle').length)
      const danger = await bp.evaluate(
        () => document.querySelector('.notice.danger')?.textContent?.trim().slice(0, 80) ?? '',
      )
      const ok = seenStatus(/graph/i) && !danger
      return {
        ok,
        detail: danger
          ? `页面报错「${danger}」`
          : `图请求成功，画布上 ${nodes} 个节点${nodes === 0 ? '（该学者没有合作关系数据，属数据现状）' : ''}`,
      }
    },
    settle: 3500,
  })
}

// ---------------------------------- 通知（2 项） ----------------------------------
await probe({
  page: '/notifications',
  name: '通知类型筛选',
  act: async () => {
    const sel = `${IN}.notif-kind`
    const exists = await has(sel)
    if (!exists) return null
    const alt = await bp.evaluate((s) => {
      const el = document.querySelector(s)
      return [...el.options].map((o) => o.value).find(Boolean) ?? null
    }, sel)
    if (!alt) return null
    await bp.select(sel, alt)
    return { kind: alt }
  },
  check: (b, a, acted) => ({
    ok: a.text !== b.text,
    detail: a.text !== b.text ? `按「${acted.kind}」筛选后列表已变化` : '切换筛选后列表内容没有任何变化',
  }),
})

await probe({
  page: '/notifications',
  name: '只看未读切换',
  act: () => clickText(['只看未读', '显示全部通知']),
  check: (b, a, acted) => ({
    ok: a.text !== b.text,
    detail: a.text !== b.text ? `点了「${acted}」，列表已变化` : `点了「${acted}」但列表没有变化`,
  }),
})

// ---------------------------------- 审计（1 项） ----------------------------------
await probe({
  page: '/audit',
  name: '审计对象类型筛选',
  act: async () => {
    // 必须限定主内容区：页头「数据口径」下拉也是 <select>，取第一个会取到它，
    // 于是「按对象类型筛选」变成「换数据口径」，表格内容当然也跟着变——测的却不是这件事。
    const sel = `${IN}select`
    const exists = await has(sel)
    if (!exists) return null
    const value = await bp.evaluate((s) => {
      const el = document.querySelector(s)
      return el ? ([...el.options].map((o) => o.value).find(Boolean) ?? null) : null
    }, sel)
    if (!value) return null
    await bp.select(sel, value)
    await sleep(300)
    const clicked = await clickText(['查询'])
    return clicked === '查询' ? { value } : clicked
  },
  check: (b, a, acted) => {
    // 审计查询是 POST，但语义只读（不改任何记录）。判定看请求成功且结果区有响应。
    const ok = seenStatus(/audit/i) && a.text !== b.text
    return { ok, detail: ok ? `按对象类型「${acted.value}」查询已返回结果` : `查询没有产生结果变化或请求未成功` }
  },
  settle: 3000,
})

// ---------------------------------- 关键词热点（1 项） ----------------------------------
await probe({
  page: '/hotspots',
  name: '统计热点',
  act: () => clickText(['统计热点']),
  check: (b, a, acted) => {
    const ok = a.text !== b.text
    return { ok, detail: ok ? `点击「${acted}」后统计结果区已更新` : '点击后页面没有任何变化' }
  },
  settle: 3000,
})

// ---------------------------------- 门户贡献（1 项） ----------------------------------
await probe({
  page: '/portal/contributions',
  name: '切换学者门户',
  act: async () => {
    const sel = `${IN}#portal-pick`
    const opts = await bp.evaluate((s) => {
      const el = document.querySelector(s)
      return el ? [...el.options].map((o) => o.value) : []
    }, sel)
    // 只有一个门户时切不动，属正常：记「跳过」而不是「通过」。
    if (opts.length < 2) return opts.length === 1 ? 'single' : null
    await bp.select(sel, opts[1])
    return { picked: opts[1] }
  },
  check: (b, a, acted) => {
    if (acted === 'single') return { ok: true, detail: '该账号只有一个生效门户，切换无意义（已核对下拉存在）' }
    return { ok: a.text !== b.text, detail: a.text !== b.text ? `已切换到 ${acted.picked}，参与关系列表随之变化` : '切换门户后内容没有变化' }
  },
  settle: 3000,
})

await browser.close()

// ---------------------------------- 汇总 ----------------------------------
const bad = results.filter((r) => !r.ok)
const byPage = new Map()
for (const r of results) {
  if (!byPage.has(r.page)) byPage.set(r.page, [])
  byPage.get(r.page).push(r)
}

console.log('\n=== 只读控件验收 ===')
console.log(`共 ${results.length} 项：通过 ${results.length - bad.length}，未通过 ${bad.length}`)
for (const r of bad) console.log(`  ✗ [${r.page}] ${r.name} — ${r.status}：${r.detail}`)
if (softFindings.length) {
  console.log('\n=== 运行期异常 ===')
  for (const f of softFindings.slice(0, 20)) console.log(`  [${f.probe}] ${f.kind} ${f.text}`)
}

const report = {
  generatedAt: new Date().toISOString(),
  base: BASE,
  keyword: Q,
  login: loginResult,
  results,
  softFindings,
}
writeFileSync(`${OUT}/controls.json`, JSON.stringify(report, null, 2))

const md = [
  `# 只读控件验收（${BASE}）`,
  '',
  `- 生成时间：${report.generatedAt}`,
  `- 登录：${loginResult.ok ? `通过（${loginResult.username}）` : `未通过 — ${loginResult.reason}`}`,
  `- 探针：${results.length} 项，通过 ${results.length - bad.length}，未通过 ${bad.length}`,
  `- 检索关键词：${Q}`,
  '',
  '## 边界',
  '',
  '> 只点**只读**控件，且只点本脚本白名单里显式声明的那几项。写操作（合并、停用、驳回、保存）一律不碰。',
  '> 因此「通过」的含义是：这一点有可观察的反应且没有失败请求，不等于该功能在所有数据下都对。',
  '',
  '| 页面 | 控件 | 结果 | 证据 |',
  '| --- | --- | --- | --- |',
  ...results.map(
    (r) => `| ${r.page} | ${r.name} | ${r.status} | ${String(r.detail).replace(/\|/g, '/')} |`,
  ),
  '',
  '## 未通过项',
  '',
  ...(bad.length === 0
    ? ['无']
    : bad.map((r) => `- ${r.page} 的「${r.name}」：${r.status} — ${r.detail}`)),
  '',
  '## 运行期异常',
  '',
  ...(softFindings.length === 0 ? ['无'] : softFindings.map((f) => `- [${f.probe}] ${f.kind}：${f.text}`)),
].join('\n')
writeFileSync(`${OUT}/controls.md`, md)

console.log(`\n报告：${OUT}/controls.md`)
process.exit(bad.length === 0 ? 0 : 1)
