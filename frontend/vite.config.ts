import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 三个后端服务各自独立监听端口。开发期一律经由本服务器的代理转发，而不是让浏览器直连 8081/8082/8083：
//
//  1) 会话 Cookie 不带端口，但带域与路径。直连多端口会让「谁设置了这枚 Cookie」变得难以判断，
//     而 core 的 CSRF 校验依赖双提交 Cookie，一旦跨源就整体失效。
//  2) 浏览器同源策略会把跨端口请求当作跨源，需要后端开 CORS 并显式允许携带凭据。为了开发方便
//     在后端放宽跨源，等于把这条配置带进生产；宁可全部走同源代理。
//  3) 代理保留各自前缀，前端代码里「这个请求属于哪个服务」是一眼可见的，而不是藏在 baseURL 里。
const CORE = process.env.CN_CORE_URL ?? 'http://127.0.0.1:8081'
const INGESTION = process.env.CN_INGESTION_URL ?? 'http://127.0.0.1:8082'
const QUERY = process.env.CN_QUERY_URL ?? 'http://127.0.0.1:8083'

/**
 * 构造一个服务代理。
 *
 * 匹配键必须带边界，不能只写前缀。原因：代理键按前缀匹配，而页面路由与 API 前缀在字面上
 * 可能互为前缀——「采集管理台」的页面路由是 /ingestion，API 前缀是 /ingest。若代理键写成
 * /ingest，直接访问或刷新 /ingestion 会被判成 API 请求，重写成 /ion 转发给采集服务并返回 500。
 * 表现为「站内点进去正常、刷新就挂」，排查时很容易误判成后端故障。
 *
 * 因此键写成 ^/ingest($|/)，只吃「/ingest」与「/ingest/...」两种形态，不吃 /ingestion。
 * 边界匹配还意味着这类冲突不会在新增页面路由时重新出现（例如将来加 /query-builder 页）。
 */
function proxyTo(target: string, prefix: string) {
  return {
    target,
    changeOrigin: true,
    // 先按边界判断是否属于本服务，再把前缀去掉：/core/api/v1/... → /api/v1/...
    // 直接在 rewrite 里做前缀替换是不够的——rewrite 只在代理已命中后执行，
    // 而「命中」这一步正是被过宽的前缀键误判的地方。
    rewrite: (path: string) => path.replace(new RegExp(`^${prefix}`), ''),
  }
}

/** 与 proxyTo 配套的代理键；vite 只把以 ^ 开头的键当作正则来匹配。 */
function proxyKey(prefix: string) {
  return `^${prefix}($|/)`
}

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  /**
   * 构建目标必须与 tsconfig 的 target 一致（ES2022）。
   *
   * 默认目标（chrome87/es2020）不支持顶层 await，而入口处必须先 await 一次会话再挂载，
   * 否则首屏会先按「未登录」渲染再跳成已登录。降级写法（把入口包成 async 函数）能绕开这个限制，
   * 但那会把「挂载时机」藏进一个 .then 里；明确提高构建目标更直接，代价是放弃 2020 年前的浏览器。
   */
  build: {
    target: 'es2022',
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      [proxyKey('/core')]: proxyTo(CORE, '/core'),
      [proxyKey('/ingest')]: proxyTo(INGESTION, '/ingest'),
      [proxyKey('/query')]: proxyTo(QUERY, '/query'),
    },
  },
})
